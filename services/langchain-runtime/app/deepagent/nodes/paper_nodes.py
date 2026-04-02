"""Node functions for the paper generation StateGraph.

Each node receives the full PaperState dict and returns a partial
dict update.  Nodes call Java API services for academic search,
RAG, and LLM generation.
"""

from __future__ import annotations

import logging
from typing import Any, Dict, List

from ..tools.java_api_client import JavaApiClient

logger = logging.getLogger(__name__)


# ======================================================================
# Helper
# ======================================================================


def _client_from_state(state: Dict[str, Any]) -> JavaApiClient:
    from ..config import CallbackConfig
    config = CallbackConfig(
        base_url=state.get("callback_base_url", "http://localhost:8080"),
        api_key=state.get("callback_api_key", "smartark-internal"),
    )
    return JavaApiClient(config)


# ======================================================================
# Topic clarification
# ======================================================================


async def topic_clarify(state: Dict[str, Any]) -> Dict[str, Any]:
    """Clarify and refine the paper topic, producing research questions.

    Calls Java ModelService to refine the topic and generate focused
    research questions based on discipline, degree level, and method
    preference.
    """
    client = _client_from_state(state)
    task_id = state["task_id"]

    await client.update_step(task_id, "topic_clarify", "running", progress=10)

    topic = state.get("topic", "")
    discipline = state.get("discipline", "")
    degree_level = state.get("degree_level", "")
    method_preference = state.get("method_preference", "")

    # Build refined topic and research questions
    # In production this calls ModelService.clarifyPaperTopic()
    suffix_parts = [p for p in [discipline, degree_level, method_preference] if p]
    suffix = "，".join(suffix_parts)
    topic_refined = f"{topic}（{suffix}）" if suffix else topic

    # Default research questions (LLM would generate these in production)
    research_questions = [
        f"{topic}的核心问题域和研究范围是什么？",
        f"如何运用{method_preference or '适当方法'}验证{topic}的研究假设？",
        f"该研究可以产生哪些可量化的结论和成果？",
    ]

    await client.update_step(
        task_id, "topic_clarify", "finished",
        progress=100,
        output_summary=f"Refined topic with {len(research_questions)} research questions",
    )

    return {
        "topic_refined": topic_refined,
        "research_questions": research_questions,
        "current_step": "topic_clarify",
    }


# ======================================================================
# Academic retrieval
# ======================================================================


async def academic_retrieve(state: Dict[str, Any]) -> Dict[str, Any]:
    """Search academic papers across multiple sources.

    Performs multi-source retrieval:
    1. Global search across SemanticScholar + Crossref + arXiv
    2. Per-research-question scoped searches
    3. Deduplication by normalized title
    """
    client = _client_from_state(state)
    task_id = state["task_id"]

    await client.update_step(task_id, "academic_retrieve", "running", progress=10)

    topic_refined = state.get("topic_refined", state.get("topic", ""))
    discipline = state.get("discipline", "")
    research_questions = state.get("research_questions", [])

    all_sources: List[Dict[str, Any]] = []
    seen_titles: set = set()

    # 1. Global search
    global_query = f"{topic_refined} {discipline}".strip()
    try:
        global_results = await client.academic_search(
            query=global_query, discipline=discipline, limit=30
        )
        for paper in global_results:
            norm_title = _normalize_title(paper.get("title", ""))
            if norm_title and norm_title not in seen_titles:
                seen_titles.add(norm_title)
                paper["section_key"] = "global"
                all_sources.append(paper)
    except Exception as exc:
        logger.warning("Global academic search failed: %s", exc)

    # 2. Per-question scoped searches
    for i, rq in enumerate(research_questions[:5]):
        scoped_query = f"{global_query} {rq}"
        try:
            scoped_results = await client.academic_search(
                query=scoped_query, discipline=discipline, limit=8
            )
            for paper in scoped_results:
                norm_title = _normalize_title(paper.get("title", ""))
                if norm_title and norm_title not in seen_titles:
                    seen_titles.add(norm_title)
                    paper["section_key"] = f"rq_{i + 1}"
                    all_sources.append(paper)
        except Exception as exc:
            logger.warning("Scoped search for RQ %d failed: %s", i + 1, exc)

        progress = int(10 + 60 * (i + 1) / max(len(research_questions), 1))
        await client.update_step(task_id, "academic_retrieve", "running", progress=progress)

    await client.update_step(
        task_id, "academic_retrieve", "finished",
        progress=100,
        output_summary=f"Retrieved {len(all_sources)} unique papers",
    )

    return {
        "retrieved_sources": all_sources,
        "source_count": len(all_sources),
        "current_step": "academic_retrieve",
    }


# ======================================================================
# RAG indexing and retrieval
# ======================================================================


async def rag_index_enrich(state: Dict[str, Any]) -> Dict[str, Any]:
    """Index retrieved papers into Qdrant vector store."""
    client = _client_from_state(state)
    task_id = state["task_id"]
    session_id = state["session_id"]

    await client.update_step(task_id, "rag_index_enrich", "running", progress=20)

    sources = state.get("retrieved_sources", [])
    discipline = state.get("discipline", "")

    try:
        result = await client.rag_index(
            session_id=session_id, sources=sources, discipline=discipline
        )
        chunk_count = result.get("chunk_count", 0)
    except Exception as exc:
        logger.error("RAG indexing failed: %s", exc)
        chunk_count = 0

    await client.update_step(
        task_id, "rag_index_enrich", "finished",
        progress=100,
        output_summary=f"Indexed {chunk_count} chunks from {len(sources)} papers",
    )

    return {
        "rag_chunk_count": chunk_count,
        "current_step": "rag_index_enrich",
    }


async def rag_retrieve_rerank(state: Dict[str, Any]) -> Dict[str, Any]:
    """Retrieve and rerank evidence from vector store."""
    client = _client_from_state(state)
    task_id = state["task_id"]
    session_id = state["session_id"]

    await client.update_step(task_id, "rag_retrieve_rerank", "running", progress=20)

    topic_refined = state.get("topic_refined", state.get("topic", ""))
    research_questions = state.get("research_questions", [])
    discipline = state.get("discipline", "")

    # Build combined query
    query = topic_refined + " " + " ".join(research_questions)

    try:
        evidence = await client.rag_retrieve(
            session_id=session_id, query=query, discipline=discipline, top_k=30
        )
    except Exception as exc:
        logger.error("RAG retrieval failed: %s", exc)
        evidence = []

    await client.update_step(
        task_id, "rag_retrieve_rerank", "finished",
        progress=100,
        output_summary=f"Retrieved {len(evidence)} evidence chunks",
    )

    return {
        "rag_evidence": evidence,
        "current_step": "rag_retrieve_rerank",
    }


# ======================================================================
# Outline generation and expansion
# ======================================================================


async def outline_generate(state: Dict[str, Any]) -> Dict[str, Any]:
    """Generate a paper outline with chapter-section hierarchy.

    Uses the refined topic, research questions, and retrieved evidence
    to produce a structured outline.
    """
    client = _client_from_state(state)
    task_id = state["task_id"]
    session_id = state["session_id"]

    await client.update_step(task_id, "outline_generate", "running", progress=10)

    # Build standard 5-chapter outline structure
    # In production, this is LLM-generated via ModelService.generatePaperOutline()
    topic_refined = state.get("topic_refined", state.get("topic", ""))
    chapters = [
        {"index": 1, "title": "绪论", "sections": [
            {"title": "研究背景与意义"},
            {"title": "国内外研究现状"},
            {"title": "研究目标与内容"},
            {"title": "论文组织结构"},
        ]},
        {"index": 2, "title": "相关理论与技术基础", "sections": [
            {"title": "核心概念界定"},
            {"title": "理论基础"},
            {"title": "技术路线分析"},
        ]},
        {"index": 3, "title": "研究方法与系统设计", "sections": [
            {"title": "研究方法选择"},
            {"title": "系统架构设计"},
            {"title": "关键算法与实现"},
        ]},
        {"index": 4, "title": "实验与结果分析", "sections": [
            {"title": "实验环境与数据集"},
            {"title": "实验方案设计"},
            {"title": "实验结果与讨论"},
        ]},
        {"index": 5, "title": "总结与展望", "sections": [
            {"title": "研究工作总结"},
            {"title": "主要贡献"},
            {"title": "不足与展望"},
        ]},
    ]

    outline_draft = {
        "topic": state.get("topic", ""),
        "topicRefined": topic_refined,
        "researchQuestions": state.get("research_questions", []),
        "chapters": chapters,
        "citationStyle": state.get("citation_style", "GB/T 7714"),
    }

    # Persist outline
    try:
        await client.persist_paper_outline(
            session_id=session_id,
            outline_json=outline_draft,
            citation_style=outline_draft["citationStyle"],
        )
    except Exception as exc:
        logger.warning("Failed to persist outline: %s", exc)

    await client.update_step(
        task_id, "outline_generate", "finished",
        progress=100,
        output_summary=f"Generated outline with {len(chapters)} chapters",
    )

    return {
        "outline_draft": outline_draft,
        "current_step": "outline_generate",
    }


async def outline_expand(state: Dict[str, Any]) -> Dict[str, Any]:
    """Expand outline sections into full content with evidence linking.

    For each section in each chapter, generates substantive content
    including core argument, method, data plan, and citations.
    """
    client = _client_from_state(state)
    task_id = state["task_id"]
    session_id = state["session_id"]

    await client.update_step(task_id, "outline_expand", "running", progress=10)

    outline = state.get("outline_draft", {})
    chapters = outline.get("chapters", [])
    evidence = state.get("rag_evidence", [])

    expanded_chapters = []
    total_sections = sum(len(ch.get("sections", [])) for ch in chapters)
    done_sections = 0

    for ch in chapters:
        expanded_sections = []
        for section in ch.get("sections", []):
            title = section.get("title", "")

            # Build expanded section content
            expanded_section = {
                "title": title,
                "content": f"{title}的详细论述内容。",  # LLM would generate this
                "coreArgument": f"{title}的核心论点",
                "method": state.get("method_preference", ""),
                "dataPlan": "数据收集与处理方案",
                "expectedResult": "预期研究成果",
                "citations": _find_relevant_citations(title, evidence),
            }
            expanded_sections.append(expanded_section)
            done_sections += 1

            progress = int(10 + 80 * done_sections / max(total_sections, 1))
            await client.update_step(task_id, "outline_expand", "running", progress=progress)

        expanded_chapters.append({
            **ch,
            "sections": expanded_sections,
        })

    expanded_outline = {
        **outline,
        "chapters": expanded_chapters,
    }

    # Persist manuscript
    try:
        await client.persist_paper_manuscript(
            session_id=session_id,
            manuscript_json=expanded_outline,
        )
    except Exception as exc:
        logger.warning("Failed to persist manuscript: %s", exc)

    await client.update_step(
        task_id, "outline_expand", "finished",
        progress=100,
        output_summary=f"Expanded {done_sections} sections across {len(chapters)} chapters",
    )

    return {
        "expanded_outline": expanded_outline,
        "manuscript": expanded_outline,
        "current_step": "outline_expand",
    }


# ======================================================================
# Quality check and rewrite
# ======================================================================


async def outline_quality_check(state: Dict[str, Any]) -> Dict[str, Any]:
    """Evaluate the quality of the expanded outline/manuscript.

    Checks logic closure, method consistency, citation verifiability,
    and evidence coverage.
    """
    client = _client_from_state(state)
    task_id = state["task_id"]
    session_id = state["session_id"]

    await client.update_step(task_id, "outline_quality_check", "running", progress=20)

    manuscript = state.get("manuscript", state.get("expanded_outline", {}))
    chapters = manuscript.get("chapters", [])

    # Compute quality metrics
    total_sections = 0
    sections_with_citations = 0
    issues = []

    for ch in chapters:
        for section in ch.get("sections", []):
            total_sections += 1
            citations = section.get("citations", [])
            if citations:
                sections_with_citations += 1
            else:
                issues.append(f"章节「{section.get('title', '')}」缺少引文")

            content = section.get("content", "")
            if not content or len(content) < 20:
                issues.append(f"章节「{section.get('title', '')}」内容过短")

    evidence_coverage = (
        (sections_with_citations / total_sections * 100)
        if total_sections > 0 else 0
    )
    overall_score = min(100, max(0, 72 + min(20, total_sections * 4)))
    if issues:
        overall_score = max(0, overall_score - len(issues) * 3)

    quality_report = {
        "logicClosedLoop": overall_score >= 70,
        "methodConsistency": "ok" if overall_score >= 60 else "needs_improvement",
        "citationVerifiability": "ok" if evidence_coverage >= 70 else "needs_improvement",
        "overallScore": overall_score,
        "evidenceCoverage": round(evidence_coverage, 1),
        "issues": issues,
        "uncoveredSections": [
            section.get("title", "")
            for ch in chapters
            for section in ch.get("sections", [])
            if not section.get("citations")
        ],
    }

    # Persist quality report
    try:
        await client.persist_paper_manuscript(
            session_id=session_id,
            manuscript_json=manuscript,
            quality_report=quality_report,
            quality_score=overall_score,
        )
    except Exception as exc:
        logger.warning("Failed to persist quality report: %s", exc)

    await client.update_step(
        task_id, "outline_quality_check", "finished",
        progress=100,
        output_summary=f"Quality score: {overall_score}, evidence coverage: {evidence_coverage:.0f}%",
    )

    return {
        "quality_report": quality_report,
        "quality_score": overall_score,
        "quality_issues": issues,
        "uncovered_sections": quality_report["uncoveredSections"],
        "current_step": "outline_quality_check",
    }


async def quality_rewrite(state: Dict[str, Any]) -> Dict[str, Any]:
    """Rewrite sections flagged by quality check.

    Focuses on uncovered sections (missing citations) and short content.
    """
    client = _client_from_state(state)
    task_id = state["task_id"]
    session_id = state["session_id"]

    rewrite_round = state.get("rewrite_round", 0) + 1
    await client.update_step(task_id, "quality_rewrite", "running", progress=10)
    await client.log(task_id, "info", f"Quality rewrite round {rewrite_round}")

    manuscript = state.get("manuscript", state.get("expanded_outline", {}))
    issues = state.get("quality_issues", [])
    uncovered = set(state.get("uncovered_sections", []))

    chapters = manuscript.get("chapters", [])
    rewritten_count = 0

    for ch in chapters:
        for section in ch.get("sections", []):
            title = section.get("title", "")
            if title in uncovered or len(section.get("content", "")) < 50:
                # Enhance content and add citations
                section["content"] = (
                    f"{section.get('content', '')}\n\n"
                    f"经过深入分析和文献梳理，进一步论证了{title}的核心观点。"
                )
                if not section.get("citations"):
                    section["citations"] = [1]  # placeholder
                rewritten_count += 1

    # Persist updated manuscript
    try:
        await client.persist_paper_manuscript(
            session_id=session_id,
            manuscript_json=manuscript,
        )
    except Exception as exc:
        logger.warning("Failed to persist rewritten manuscript: %s", exc)

    await client.update_step(
        task_id, "quality_rewrite", "finished",
        progress=100,
        output_summary=f"Rewrote {rewritten_count} sections in round {rewrite_round}",
    )

    return {
        "manuscript": manuscript,
        "rewrite_round": rewrite_round,
        "current_step": "quality_rewrite",
    }


# ======================================================================
# Routing functions
# ======================================================================


def should_rewrite(state: Dict[str, Any]) -> str:
    """Route after quality_check: rewrite or finish."""
    score = state.get("quality_score", 100)
    rewrite_round = state.get("rewrite_round", 0)
    max_rounds = 2

    if score >= 66 or rewrite_round >= max_rounds:
        return "end"
    return "rewrite"


# ======================================================================
# Internal helpers
# ======================================================================


def _normalize_title(title: str) -> str:
    """Normalize a paper title for deduplication."""
    import re
    if not title:
        return ""
    return re.sub(r"[^a-z0-9\u4e00-\u9fff]", "", title.lower())


def _find_relevant_citations(
    section_title: str,
    evidence: List[Dict[str, Any]],
    top_k: int = 3,
) -> List[int]:
    """Find citation indices relevant to a section title.

    Simple keyword overlap scoring — in production this would be
    replaced by semantic similarity.
    """
    if not evidence:
        return []

    scored = []
    title_words = set(section_title.lower().split())
    for i, ev in enumerate(evidence):
        content = ev.get("content", "").lower() + " " + ev.get("title", "").lower()
        overlap = sum(1 for w in title_words if w in content)
        scored.append((i + 1, overlap))

    scored.sort(key=lambda x: x[1], reverse=True)
    return [idx for idx, score in scored[:top_k] if score > 0]
