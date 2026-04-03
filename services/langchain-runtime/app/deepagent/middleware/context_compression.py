"""Context compression middleware — intelligent context assembly for LLM prompts.

Replaces naive hard-truncation with:
  1. PRD summarization (extract entities, rules, API constraints)
  2. Cross-file dependency injection (import targets, interface signatures)
  3. Fix context rolling window (previous fix diffs for build_fix)
  4. Semantic truncation (section-boundary-aware, relevance-ranked)
"""

from __future__ import annotations

import logging
import re
from typing import Any, Dict, List, Optional, Set

logger = logging.getLogger(__name__)

# Section boundary patterns for semantic truncation
_SECTION_MARKERS = re.compile(
    r"^(?:#{1,4}\s|(?:\d+\.)+\s|[一二三四五六七八九十]+[、.]\s|"
    r"(?:功能|模块|接口|需求|业务|数据|流程|规则|约束|字段)\s*[:：])",
    re.MULTILINE,
)


class ContextCompressionMiddleware:
    """Middleware that compresses and assembles context for LLM prompts.

    Usage:
        mw = ContextCompressionMiddleware()
        compressed = mw.compress_prd(prd, file_path="UserService.java", max_chars=3000)
        deps = mw.extract_dependencies(file_path, state)
    """

    def __init__(
        self,
        prd_max_chars: int = 3000,
        instructions_max_chars: int = 1000,
        structure_max_chars: int = 2000,
        dependency_max_chars: int = 1500,
        fix_context_max_chars: int = 2000,
    ) -> None:
        self._prd_max = prd_max_chars
        self._instructions_max = instructions_max_chars
        self._structure_max = structure_max_chars
        self._dependency_max = dependency_max_chars
        self._fix_context_max = fix_context_max_chars

    def compress_prd(
        self,
        prd: str,
        file_path: str = "",
        max_chars: Optional[int] = None,
    ) -> str:
        """Compress PRD with semantic truncation and relevance ranking.

        Instead of prd[:3000], this method:
        1. Splits PRD into sections
        2. Scores each section by relevance to the target file
        3. Assembles highest-scored sections within budget
        4. Truncates at section boundaries, not mid-sentence
        """
        limit = max_chars or self._prd_max
        if len(prd) <= limit:
            return prd

        sections = self._split_sections(prd)
        if not sections:
            return prd[:limit]

        # Score sections by relevance to file_path
        scored = []
        for i, section in enumerate(sections):
            score = self._relevance_score(section, file_path)
            # Boost first section (usually overview/summary)
            if i == 0:
                score += 5
            scored.append((score, i, section))

        # Sort by score descending, then by original order for ties
        scored.sort(key=lambda x: (-x[0], x[1]))

        # Assemble within budget
        result_parts: List[str] = []
        used_chars = 0
        selected_indices: Set[int] = set()

        for score, idx, section in scored:
            if used_chars + len(section) > limit:
                # Try to fit a truncated version of this section
                remaining = limit - used_chars
                if remaining > 200:
                    result_parts.append((idx, section[:remaining] + "..."))
                    used_chars = limit
                break
            result_parts.append((idx, section))
            selected_indices.add(idx)
            used_chars += len(section)

        # Re-sort by original order for coherence
        result_parts.sort(key=lambda x: x[0])
        return "\n\n".join(part for _, part in result_parts)

    def extract_cross_file_deps(
        self,
        file_path: str,
        state: Dict[str, Any],
    ) -> str:
        """Extract interface signatures of files that the target file likely imports.

        Scans generated_files for files that the target would depend on,
        and extracts their key interfaces (class definitions, exports, types).
        """
        generated = state.get("generated_files", {})
        if not generated:
            return ""

        file_plan = state.get("file_plan", [])
        lower_path = file_path.lower()

        # Determine what this file likely imports
        dep_paths = self._infer_dependencies(lower_path, file_plan)

        dep_snippets = []
        total_chars = 0

        for dep_path in dep_paths:
            content = generated.get(dep_path, "")
            if not content:
                continue

            # Extract interface signatures only
            signatures = self._extract_signatures(dep_path, content)
            if signatures:
                snippet = f"// {dep_path}\n{signatures}"
                if total_chars + len(snippet) > self._dependency_max:
                    break
                dep_snippets.append(snippet)
                total_chars += len(snippet)

        if not dep_snippets:
            return ""

        return (
            "--- 相关文件接口签名 (保持一致性) ---\n"
            + "\n\n".join(dep_snippets)
        )

    def build_fix_context(
        self,
        state: Dict[str, Any],
        current_file: str,
        current_errors: str,
    ) -> str:
        """Build enriched fix context with rolling window of previous fixes."""
        parts = [f"当前构建错误:\n{current_errors}"]

        # Add fix history from current task
        fix_history = state.get("fix_history", [])
        if fix_history:
            history_parts = []
            for record in fix_history[-3:]:
                history_parts.append(
                    f"Round {record.get('round', '?')}: "
                    f"{record.get('file', '?')} - {record.get('fix_summary', '')[:150]}"
                )
            parts.append("前几轮修复记录:\n" + "\n".join(history_parts))

        # Add related file content snippets for cross-file errors
        generated = state.get("generated_files", {})
        related = self._find_related_error_files(current_errors, generated, current_file)
        for rel_path, snippet in related[:2]:
            parts.append(f"相关文件 {rel_path} (前50行):\n{snippet}")

        result = "\n\n".join(parts)
        return result[:self._fix_context_max]

    def compress_project_structure(
        self,
        file_list: List[str],
        current_group: str = "",
        current_file: str = "",
        max_chars: Optional[int] = None,
    ) -> str:
        """Compress project structure with relevance to current file/group.

        Prioritizes files in the same group/directory as the current file.
        """
        limit = max_chars or self._structure_max
        full_structure = "\n".join(file_list)
        if len(full_structure) <= limit:
            return full_structure

        # Separate same-group files (high priority) from others
        current_dir = "/".join(current_file.split("/")[:-1]) if "/" in current_file else ""
        same_group = []
        other_files = []

        for f in file_list:
            f_group = _classify_group(f)
            if f_group == current_group or (current_dir and f.startswith(current_dir)):
                same_group.append(f)
            else:
                other_files.append(f)

        # Build structure: same-group first, then fill with others
        result = "\n".join(same_group)
        remaining = limit - len(result)
        if remaining > 100 and other_files:
            other_str = "\n".join(other_files)
            if len(other_str) <= remaining:
                result += "\n" + other_str
            else:
                result += "\n" + other_str[:remaining - 20] + "\n... (更多文件省略)"

        return result[:limit]

    def _split_sections(self, text: str) -> List[str]:
        """Split text into sections at heading/marker boundaries."""
        positions = [m.start() for m in _SECTION_MARKERS.finditer(text)]
        if not positions:
            # Fall back to paragraph splitting
            return [p.strip() for p in text.split("\n\n") if p.strip()]

        # Add start position
        if positions[0] != 0:
            positions.insert(0, 0)

        sections = []
        for i in range(len(positions)):
            start = positions[i]
            end = positions[i + 1] if i + 1 < len(positions) else len(text)
            section = text[start:end].strip()
            if section:
                sections.append(section)
        return sections

    def _relevance_score(self, section: str, file_path: str) -> int:
        """Score a PRD section's relevance to a file path."""
        if not file_path:
            return 0

        score = 0
        lower_section = section.lower()
        lower_path = file_path.lower()

        # Extract meaningful words from file path
        path_words = set(re.findall(r"[a-z]+", lower_path))
        path_words.discard("src")
        path_words.discard("main")
        path_words.discard("java")
        path_words.discard("js")
        path_words.discard("ts")

        for word in path_words:
            if len(word) > 2 and word in lower_section:
                score += 2

        # Bonus for matching domain terms
        if any(kw in lower_section for kw in ("接口", "api", "endpoint", "controller")):
            if "controller" in lower_path or "api" in lower_path:
                score += 3
        if any(kw in lower_section for kw in ("数据", "字段", "表", "entity", "model")):
            if "entity" in lower_path or "model" in lower_path or ".sql" in lower_path:
                score += 3
        if any(kw in lower_section for kw in ("页面", "组件", "视图", "component", "view", "page")):
            if any(ext in lower_path for ext in (".vue", ".tsx", ".jsx")):
                score += 3

        return score

    def _infer_dependencies(
        self,
        file_path: str,
        file_plan: List[Dict[str, Any]],
    ) -> List[str]:
        """Infer which files the given file_path likely depends on."""
        deps = []
        lower = file_path.lower()

        # Name-based heuristic: Service depends on Entity/Repository
        base_name = file_path.split("/")[-1].split(".")[0].lower()
        # Remove suffixes to get domain name
        for suffix in ("service", "controller", "repository", "mapper", "dao", "impl"):
            base_name = base_name.replace(suffix, "")

        if not base_name:
            return []

        for item in file_plan:
            dep_path = item.get("path", "")
            dep_lower = dep_path.lower()
            if dep_lower == file_path.lower():
                continue

            dep_base = dep_path.split("/")[-1].split(".")[0].lower()

            # Same domain name in different layers
            if base_name in dep_base and dep_lower != lower:
                deps.append(dep_path)

            # Frontend depends on API types
            if _is_frontend_path(lower) and ("api" in dep_lower or "types" in dep_lower):
                deps.append(dep_path)

        return deps[:5]

    def _extract_signatures(self, file_path: str, content: str) -> str:
        """Extract interface signatures (class/function/export declarations) from content."""
        lines = content.split("\n")
        signatures = []

        for line in lines:
            stripped = line.strip()
            # Java: class/interface/enum declarations, public method signatures
            if re.match(r"(public\s+)?(class|interface|enum|record)\s+\w+", stripped):
                signatures.append(stripped)
            elif re.match(r"(public|protected)\s+\w+.*\(.*\)\s*\{?\s*$", stripped):
                signatures.append(stripped.rstrip("{").strip())
            # TypeScript/JS: export declarations
            elif re.match(r"export\s+(default\s+)?(function|class|interface|type|const|enum)\s+", stripped):
                signatures.append(stripped)
            # Vue: defineProps/defineEmits
            elif "defineProps" in stripped or "defineEmits" in stripped:
                signatures.append(stripped)

            if len(signatures) >= 20:
                break

        return "\n".join(signatures)

    def _find_related_error_files(
        self,
        error_text: str,
        generated: Dict[str, str],
        exclude_file: str,
    ) -> List[tuple]:
        """Find files mentioned in errors that might be related to the current fix."""
        related = []
        for path, content in generated.items():
            if path == exclude_file or not content:
                continue
            if path in error_text:
                first_50_lines = "\n".join(content.split("\n")[:50])
                related.append((path, first_50_lines))
        return related


    # ==================================================================
    # Paper-specific compression
    # ==================================================================

    def compress_evidence(
        self,
        evidence: List[Dict[str, Any]],
        section_title: str = "",
        max_items: int = 8,
        max_chars: int = 3000,
    ) -> str:
        """Compress RAG evidence for injection into paper LLM prompts.

        Ranks evidence by relevance to section_title, then assembles
        top-K items within character budget.
        """
        if not evidence:
            return ""

        scored = []
        title_words = set(section_title.lower().split()) if section_title else set()

        for i, ev in enumerate(evidence):
            content = ev.get("content", "")
            title = ev.get("title", "")
            score = ev.get("rerank_score", ev.get("vector_score", 0.0))
            # Boost by keyword overlap with section title
            if title_words:
                text_lower = (content + " " + title).lower()
                overlap = sum(1 for w in title_words if len(w) > 1 and w in text_lower)
                score += overlap * 0.1
            scored.append((score, i, ev))

        scored.sort(key=lambda x: -x[0])

        parts = []
        total = 0
        for _, idx, ev in scored[:max_items]:
            title = ev.get("title", "未知来源")
            year = ev.get("year", "")
            content = ev.get("content", "")[:400]
            snippet = f"[{idx + 1}] {title} ({year}): {content}"
            if total + len(snippet) > max_chars:
                break
            parts.append(snippet)
            total += len(snippet)

        return "\n\n".join(parts)

    def build_chapter_evidence_map(
        self,
        chapters: List[Dict[str, Any]],
        evidence: List[Dict[str, Any]],
    ) -> Dict[str, List[int]]:
        """Map each chapter/section to relevant evidence indices.

        Returns {section_title: [evidence_indices]}.
        """
        mapping: Dict[str, List[int]] = {}
        for ch in chapters:
            for section in ch.get("sections", []):
                title = section.get("title", "")
                if not title:
                    continue
                relevant = self._rank_evidence_for_section(title, evidence)
                mapping[title] = relevant
        return mapping

    def build_prior_chapters_summary(
        self,
        chapters: List[Dict[str, Any]],
        current_chapter_index: int,
        max_chars: int = 1500,
    ) -> str:
        """Build a summary of chapters before the current one.

        Used to maintain coherence: later chapters know what earlier
        chapters discussed, without injecting full text.
        """
        summaries = []
        total = 0
        for ch in chapters:
            if ch.get("index", 0) >= current_chapter_index:
                break
            ch_title = ch.get("title", "")
            sections = ch.get("sections", [])
            section_summaries = []
            for sec in sections:
                content = sec.get("content", "")
                if content and len(content) > 20:
                    # Take first 100 chars as summary
                    section_summaries.append(
                        f"  - {sec.get('title', '')}: {content[:100]}..."
                    )
            if section_summaries:
                ch_summary = f"第{ch.get('index', '?')}章 {ch_title}:\n" + "\n".join(section_summaries)
                if total + len(ch_summary) > max_chars:
                    break
                summaries.append(ch_summary)
                total += len(ch_summary)

        if not summaries:
            return ""
        return "--- 前序章节摘要 (保持论述连贯) ---\n" + "\n\n".join(summaries)

    def _rank_evidence_for_section(
        self,
        section_title: str,
        evidence: List[Dict[str, Any]],
        top_k: int = 5,
    ) -> List[int]:
        """Rank evidence items by relevance to section title, return indices."""
        title_words = set(section_title.lower().split())
        scored = []
        for i, ev in enumerate(evidence):
            text = (ev.get("content", "") + " " + ev.get("title", "")).lower()
            overlap = sum(1 for w in title_words if len(w) > 1 and w in text)
            base_score = ev.get("rerank_score", ev.get("vector_score", 0.0))
            scored.append((overlap * 0.3 + base_score, i))
        scored.sort(key=lambda x: -x[0])
        return [idx for _, idx in scored[:top_k] if _ > 0]


def _classify_group(path: str) -> str:
    lower = path.lower()
    if any(ext in lower for ext in (".vue", ".tsx", ".jsx", ".css")):
        return "frontend"
    if any(ext in lower for ext in (".sql",)):
        return "database"
    if any(ext in lower for ext in (".java", ".py", ".go")):
        return "backend"
    return "other"


def _is_frontend_path(path: str) -> bool:
    return any(ext in path for ext in (".vue", ".tsx", ".jsx", ".ts", ".js")) or "frontend/" in path
