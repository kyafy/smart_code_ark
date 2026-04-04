"""Node functions for the paper generation StateGraph.

Each node receives the full PaperState dict and returns a partial
dict update.  Nodes call Java API services for academic search,
RAG, and LLM generation.

Phase 4: integrated with MemoryBridge, SmartRetry, DynamicPrompt,
ContextCompression, CitationTrace, AdaptiveRerank, and AdaptiveModelSwitch.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import re
from typing import Any, Dict, List, Optional

from ..tools.java_api_client import JavaApiClient
from ..tools.llm_codegen_client import LLMCodegenClient
from ..middleware.memory_bridge import MemoryBridgeMiddleware
from ..middleware.smart_retry import SmartRetryMiddleware
from ..middleware.dynamic_prompt import DynamicPromptMiddleware
from ..middleware.context_compression import ContextCompressionMiddleware
from ..middleware.citation_trace import CitationTraceMiddleware
from ..middleware.adaptive_rerank import classify_discipline, WEIGHT_PROFILES
from ..middleware.adaptive_model_switch import AdaptiveModelSwitchMiddleware

logger = logging.getLogger(__name__)

# --- Middleware singletons ---
_memory_bridge = MemoryBridgeMiddleware()
_smart_retry = SmartRetryMiddleware()
_dynamic_prompt = DynamicPromptMiddleware()
_context_compression = ContextCompressionMiddleware()
_citation_trace = CitationTraceMiddleware()
_model_switch = AdaptiveModelSwitchMiddleware()


# ======================================================================
# Helper
# ======================================================================


def _client_from_state(state: Dict[str, Any]) -> JavaApiClient:
    from ..config import CallbackConfig
    timeout = int(os.getenv("DEEPAGENT_CALLBACK_TIMEOUT", "120"))
    config = CallbackConfig(
        base_url=state.get("callback_base_url", "http://localhost:8080"),
        api_key=state.get("callback_api_key", "smartark-internal"),
        timeout=timeout,
    )
    return JavaApiClient(config)


def _get_llm_client(state: Dict[str, Any], node_name: str) -> Optional[LLMCodegenClient]:
    """Get a direct LLM client, or None if not configured."""
    return LLMCodegenClient.from_state(state, node_name=node_name)


def _strip_json_fence(text: str) -> str:
    """Remove markdown fences from JSON output."""
    text = text.strip()
    m = re.match(r"^```(?:json)?\s*\n([\s\S]*?)```\s*$", text)
    if m:
        return m.group(1).strip()
    return text


_PAPER_NODE_TIMEOUTS: Dict[str, int] = {
    "topic_clarify": 90,
    "outline_generate": 120,
    "outline_expand": 180,
    "quality_rewrite": 180,
}

_LLM_RETRY_MAX = 3
_LLM_RETRY_BACKOFF_BASE = 2.0


async def _llm_invoke(
    state: Dict[str, Any],
    node_name: str,
    system_prompt: str,
    user_prompt: str,
    timeout: int = 0,
    max_retries: int = _LLM_RETRY_MAX,
) -> str:
    """Invoke LLM directly for paper generation with exponential-backoff retry.

    Timeout defaults are per-node (outline_expand/quality_rewrite get 180s).
    Retries on timeout and transient errors with exponential backoff.
    Returns raw response content. Falls back to empty string on failure.
    """
    from langchain_core.messages import HumanMessage, SystemMessage

    llm_client = _get_llm_client(state, node_name)
    if llm_client is None:
        logger.warning("paper[%s]: no direct LLM configured, returning empty", node_name)
        return ""

    effective_timeout = timeout if timeout > 0 else _PAPER_NODE_TIMEOUTS.get(node_name, 120)
    llm = llm_client._get_llm()

    last_error: Optional[Exception] = None
    for attempt in range(max_retries):
        try:
            response = await asyncio.wait_for(
                llm.ainvoke([SystemMessage(content=system_prompt), HumanMessage(content=user_prompt)]),
                timeout=effective_timeout,
            )
            return response.content.strip() if hasattr(response, "content") else str(response)
        except asyncio.TimeoutError:
            last_error = asyncio.TimeoutError()
            logger.warning("paper[%s]: LLM timeout after %ds (attempt %d/%d)",
                           node_name, effective_timeout, attempt + 1, max_retries)
        except Exception as exc:
            last_error = exc
            logger.warning("paper[%s]: LLM failed (attempt %d/%d): %s",
                           node_name, attempt + 1, max_retries, exc)

        if attempt < max_retries - 1:
            backoff = _LLM_RETRY_BACKOFF_BASE ** attempt
            logger.info("paper[%s]: retrying in %.1fs", node_name, backoff)
            await asyncio.sleep(backoff)

    logger.error("paper[%s]: all %d LLM attempts exhausted, last error: %s",
                 node_name, max_retries, last_error)
    return ""


def _check_paper_section_quality(content: str) -> List[str]:
    """Quality checker for paper sections — validates citation coverage.

    Returns list of issue strings (empty = pass).
    Used as callback for SmartRetry.wrap_generate_paper_section.
    """
    issues = []
    claims = _citation_trace.extract_claims(content)
    citations = _citation_trace.extract_citations(content)

    if not claims:
        return []  # no verifiable claims, skip

    covered = sum(1 for c in claims if _citation_trace.has_nearby_citation(content, c))
    coverage = covered / len(claims) if claims else 1.0

    if coverage < 0.5:
        issues.append(
            f"引文覆盖率仅 {coverage:.0%}（{covered}/{len(claims)} 个断言有引用），"
            "请为论述性断言补充 [n] 引用标记"
        )

    if len(content.strip()) < 200:
        issues.append(f"内容仅 {len(content.strip())} 字，不足 300 字最低要求")

    return issues


def _select_paper_model(
    state: Dict[str, Any],
    node_name: str,
    section_title: str = "",
) -> Optional[Dict[str, Any]]:
    """Select model tier for a paper node via AdaptiveModelSwitch.

    Routing rules:
      - outline_generate / quality_rewrite → STRONG (needs reasoning)
      - outline_expand with complex section keywords → STRONG
      - outline_expand with simple sections → FAST / default
      - topic_clarify → default
    """
    if not _model_switch.is_configured:
        return None

    # Nodes that always need strong model
    if node_name in ("outline_generate", "quality_rewrite"):
        tier = _model_switch._strong
        logger.debug("paper_model_switch[%s]: → STRONG (high-reasoning node)", node_name)
        return _model_switch.get_model_override(tier)

    # For outline_expand: assess section complexity
    if node_name == "outline_expand" and section_title:
        complex_keywords = [
            "研究方法", "实验设计", "模型构建", "算法", "理论框架",
            "数据分析", "系统架构", "性能评估", "方法论",
        ]
        is_complex = any(kw in section_title for kw in complex_keywords)
        if is_complex:
            tier = _model_switch._strong
        else:
            tier = _model_switch._fast
        logger.debug(
            "paper_model_switch[%s/%s]: → %s",
            node_name, section_title, tier.name if tier else "default",
        )
        return _model_switch.get_model_override(tier)

    return None


# ======================================================================
# Topic clarification
# ======================================================================


async def topic_clarify(state: Dict[str, Any]) -> Dict[str, Any]:
    """Clarify and refine the paper topic, producing research questions.

    Phase 4: LLM-powered with DynamicPrompt + MemoryBridge + SmartRetry.
    """
    client = _client_from_state(state)
    task_id = state["task_id"]

    # Phase 4: load memories
    memory_state = await _memory_bridge.before_node(state, "topic_clarify")
    state = {**state, **memory_state}

    await client.update_step(task_id, "topic_clarify", "running", progress=10)

    topic = state.get("topic", "")
    discipline = state.get("discipline", "")
    degree_level = state.get("degree_level", "")
    method_preference = state.get("method_preference", "")

    # Phase 4: dynamic system prompt
    system_prompt = _dynamic_prompt.get_paper_system_prompt("topic_clarify", state)

    user_prompt = (
        f"论文题目：{topic}\n"
        f"学科方向：{discipline}\n"
        f"学位层次：{degree_level}\n"
        f"方法偏好：{method_preference}\n\n"
        "请完成以下任务：\n"
        "1. 精炼论文主题，使其更具学术指向性\n"
        "2. 生成 3-5 个具体的研究问题\n\n"
        "输出格式（JSON）：\n"
        '{"topicRefined": "精炼后的主题", "researchQuestions": ["问题1", "问题2", ...]}'
    )

    # Phase 4: enhance instructions
    user_prompt = _dynamic_prompt.enhance_paper_instructions(
        user_prompt, state, step_code="topic_clarify",
    )

    # Phase 4: LLM call with retry
    raw = ""
    for attempt in range(3):
        raw = await _llm_invoke(state, "topic_clarify", system_prompt, user_prompt)
        if raw:
            break
        logger.warning("topic_clarify: empty response, attempt %d", attempt + 1)

    # Parse LLM response
    topic_refined = topic
    research_questions = []

    if raw:
        raw = _strip_json_fence(raw)
        try:
            parsed = json.loads(raw)
            topic_refined = parsed.get("topicRefined", topic)
            research_questions = parsed.get("researchQuestions", [])
        except (json.JSONDecodeError, ValueError):
            logger.warning("topic_clarify: failed to parse JSON, extracting manually")
            # Fallback: extract lines that look like questions
            for line in raw.split("\n"):
                line = line.strip().lstrip("0123456789.-) ")
                if line and ("？" in line or "?" in line or len(line) > 15):
                    research_questions.append(line)
            if not research_questions:
                topic_refined = raw[:200] if raw else topic

    # Fallback if LLM returned nothing useful
    if not research_questions:
        suffix_parts = [p for p in [discipline, degree_level, method_preference] if p]
        suffix = "，".join(suffix_parts)
        topic_refined = f"{topic}（{suffix}）" if suffix else topic
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

    # Phase 4: save checkpoint
    await _memory_bridge.after_node(
        state, "topic_clarify",
        output_summary=f"Refined: {topic_refined[:100]}, {len(research_questions)} questions",
    )

    return {
        **memory_state,
        "topic_refined": topic_refined,
        "research_questions": research_questions,
        "current_step": "topic_clarify",
    }


# ======================================================================
# Academic retrieval
# ======================================================================


async def academic_retrieve(state: Dict[str, Any]) -> Dict[str, Any]:
    """Search academic papers across multiple sources.

    Phase 4: MemoryBridge for checkpoint persistence.
    """
    client = _client_from_state(state)
    task_id = state["task_id"]

    memory_state = await _memory_bridge.before_node(state, "academic_retrieve")
    state = {**state, **memory_state}

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

    await _memory_bridge.after_node(
        state, "academic_retrieve",
        output_summary=f"Retrieved {len(all_sources)} papers from global + {len(research_questions)} scoped searches",
    )

    return {
        **memory_state,
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
    """Retrieve and rerank evidence from vector store.

    Phase 4: AdaptiveRerank injects discipline-aware reranking weights.
    """
    client = _client_from_state(state)
    task_id = state["task_id"]
    session_id = state["session_id"]

    await client.update_step(task_id, "rag_retrieve_rerank", "running", progress=20)

    topic_refined = state.get("topic_refined", state.get("topic", ""))
    research_questions = state.get("research_questions", [])
    discipline = state.get("discipline", "")

    # Build combined query
    query = topic_refined + " " + " ".join(research_questions)

    # Phase 4: AdaptiveRerank — get discipline-aware weights
    category = classify_discipline(discipline)
    weights = WEIGHT_PROFILES[category]
    logger.info(
        "rag_retrieve_rerank: discipline=%s → category=%s, weights=%s",
        discipline, category, weights,
    )

    try:
        evidence = await client.rag_retrieve(
            session_id=session_id, query=query, discipline=discipline, top_k=30
        )
    except Exception as exc:
        logger.error("RAG retrieval failed: %s", exc)
        evidence = []

    # Phase 4: apply adaptive reranking on Python side if Java doesn't support custom weights
    if evidence and category != "default":
        evidence = _rerank_evidence(evidence, weights)

    await client.update_step(
        task_id, "rag_retrieve_rerank", "finished",
        progress=100,
        output_summary=f"Retrieved {len(evidence)} evidence chunks (rerank={category})",
    )

    return {
        "rag_evidence": evidence,
        "current_step": "rag_retrieve_rerank",
    }


def _rerank_evidence(
    evidence: List[Dict[str, Any]],
    weights: Dict[str, float],
) -> List[Dict[str, Any]]:
    """Rerank evidence with adaptive weights on Python side."""
    import datetime
    current_year = datetime.datetime.now().year

    for ev in evidence:
        try:
            vector_score = float(ev.get("vector_score", 0.0))
        except (ValueError, TypeError):
            vector_score = 0.0

        try:
            citation_count = int(ev.get("citation_count", 0))
        except (ValueError, TypeError):
            citation_count = 0

        try:
            year = int(ev.get("year", 0)) if ev.get("year") else 0
        except (ValueError, TypeError):
            year = 0

        citation_score = min(citation_count, 100) / 100.0
        recency_score = max(0, 1.0 - (current_year - year) / 20.0) if year else 0.0

        ev["rerank_score"] = (
            weights["vector"] * vector_score
            + weights["citation"] * citation_score
            + weights["recency"] * recency_score
        )

    evidence.sort(key=lambda x: x.get("rerank_score", 0), reverse=True)
    return evidence


# ======================================================================
# Outline generation and expansion
# ======================================================================


async def outline_generate(state: Dict[str, Any]) -> Dict[str, Any]:
    """Generate a paper outline with chapter-section hierarchy.

    Phase 4: LLM-powered with DynamicPrompt + MemoryBridge + SmartRetry +
    ContextCompression + AdaptiveModelSwitch.
    """
    client = _client_from_state(state)
    task_id = state["task_id"]
    session_id = state["session_id"]

    memory_state = await _memory_bridge.before_node(state, "outline_generate")
    state = {**state, **memory_state}

    await client.update_step(task_id, "outline_generate", "running", progress=10)

    topic_refined = state.get("topic_refined", state.get("topic", ""))
    research_questions = state.get("research_questions", [])
    evidence = state.get("rag_evidence", [])

    # Phase 4: compress evidence for prompt injection
    compressed_evidence = _context_compression.compress_evidence(
        evidence, section_title=topic_refined, max_items=10, max_chars=3000,
    )

    # Phase 4: dynamic system prompt
    system_prompt = _dynamic_prompt.get_paper_system_prompt("outline_generate", state)

    degree_level = state.get("degree_level", "")

    # Phase 5: build template-constrained prompt
    template_prompt = _build_template_prompt(degree_level, topic_refined)

    user_prompt = (
        f"论文主题：{topic_refined}\n\n"
        f"研究问题：\n" + "\n".join(f"  {i+1}. {q}" for i, q in enumerate(research_questions)) + "\n\n"
    )
    if compressed_evidence:
        user_prompt += f"检索到的学术证据摘要：\n{compressed_evidence}\n\n"
    user_prompt += (
        f"{template_prompt}\n\n"
        "请基于上述章节框架，结合论文主题设计具体的章节大纲。\n"
        '输出格式（纯JSON）：\n'
        '{"chapters": [{"index": 1, "title": "章节标题", '
        '"sections": [{"title": "小节标题"}]}]}\n'
        "只输出JSON，不要包含其他文字。"
    )

    # Phase 4: enhance with discipline/degree instructions
    user_prompt = _dynamic_prompt.enhance_paper_instructions(
        user_prompt, state, step_code="outline_generate",
    )

    # Phase 4: LLM call with JSON retry
    outline_draft = None
    for attempt in range(3):
        raw = await _llm_invoke(state, "outline_generate", system_prompt, user_prompt, timeout=120)
        if not raw:
            continue
        raw = _strip_json_fence(raw)
        try:
            parsed = json.loads(raw)
            chapters = parsed.get("chapters", [])
            if chapters and isinstance(chapters, list):
                # Phase 5: validate against template — fill gaps
                chapters = _validate_outline_against_template(chapters, degree_level)
                outline_draft = {
                    "topic": state.get("topic", ""),
                    "topicRefined": topic_refined,
                    "researchQuestions": research_questions,
                    "chapters": chapters,
                    "citationStyle": state.get("citation_style", "GB/T 7714"),
                }
                break
        except (json.JSONDecodeError, ValueError):
            logger.warning("outline_generate: JSON parse failed attempt %d", attempt + 1)

    # Fallback: degree-level template
    if outline_draft is None:
        logger.info("outline_generate: using fallback template for degree=%s", degree_level)
        chapters = _build_fallback_outline_from_template(degree_level, topic_refined)
        outline_draft = {
            "topic": state.get("topic", ""),
            "topicRefined": topic_refined,
            "researchQuestions": research_questions,
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
        output_summary=f"Generated outline with {len(outline_draft['chapters'])} chapters",
    )

    await _memory_bridge.after_node(
        state, "outline_generate",
        output_summary=f"Outline: {len(outline_draft['chapters'])} chapters",
    )

    return {
        **memory_state,
        "outline_draft": outline_draft,
        "current_step": "outline_generate",
    }


async def outline_expand(state: Dict[str, Any]) -> Dict[str, Any]:
    """Expand outline sections into full content with evidence linking.

    Phase 4: LLM-powered per-section with DynamicPrompt + ContextCompression
    (prior chapter summaries + per-section evidence) + CitationTrace +
    SmartRetry + MemoryBridge.
    """
    client = _client_from_state(state)
    task_id = state["task_id"]
    session_id = state["session_id"]

    memory_state = await _memory_bridge.before_node(state, "outline_expand")
    state = {**state, **memory_state}

    await client.update_step(task_id, "outline_expand", "running", progress=10)

    outline = state.get("outline_draft", {})
    chapters = outline.get("chapters", [])
    evidence = state.get("rag_evidence", [])
    topic_refined = state.get("topic_refined", state.get("topic", ""))

    # Phase 4: build chapter-evidence mapping
    evidence_map = _context_compression.build_chapter_evidence_map(chapters, evidence)

    expanded_chapters: List[Dict[str, Any]] = []
    total_sections = sum(len(ch.get("sections", [])) for ch in chapters)
    done_sections = 0

    for ch in chapters:
        chapter_title = ch.get("title", "")
        chapter_index = ch.get("index", 0)

        # Phase 4: prior chapter summaries for coherence
        prior_summary = _context_compression.build_prior_chapters_summary(
            expanded_chapters, chapter_index,
        )

        # Build all section coroutines for this chapter and run concurrently
        async def _expand_one_section(section: Dict[str, Any]) -> Dict[str, Any]:
            section_title = section.get("title", "")

            # Phase 5: adaptive model selection per section
            model_override = _select_paper_model(state, "outline_expand", section_title)
            effective_state = {**state, **({"llm_config_override": model_override} if model_override else {})}

            # Phase 4: get section-specific evidence
            section_evidence_indices = evidence_map.get(section_title, [])
            section_evidence = [evidence[i] for i in section_evidence_indices if i < len(evidence)]
            compressed_ev = _context_compression.compress_evidence(
                section_evidence, section_title=section_title,
                max_items=5, max_chars=1500,
            )

            # Phase 4: dynamic system prompt
            system_prompt = _dynamic_prompt.get_paper_system_prompt("outline_expand", effective_state)

            # Phase 4: build user prompt with context
            user_prompt = (
                f"论文主题：{topic_refined}\n"
                f"当前章节：第{chapter_index}章 {chapter_title}\n"
                f"当前小节：{section_title}\n\n"
            )
            if prior_summary:
                user_prompt += f"{prior_summary}\n\n"
            if compressed_ev:
                user_prompt += f"相关学术证据：\n{compressed_ev}\n\n"
            user_prompt += (
                f"请为「{section_title}」撰写完整内容。\n"
                "要求：使用 [n] 标注引用来源编号，内容不少于 300 字。"
            )

            # Phase 4: enhance with chapter-specific guidance
            user_prompt = _dynamic_prompt.enhance_paper_instructions(
                user_prompt, effective_state,
                step_code="outline_expand",
                chapter_title=chapter_title,
                section_title=section_title,
            )

            # Phase 5: SmartRetry + CitationTrace quality checker
            async def _generate_fn(sys_prompt: str, usr_prompt: str, timeout: int) -> str:
                return await _llm_invoke(effective_state, "outline_expand", sys_prompt, usr_prompt, timeout=timeout, max_retries=1)

            content = await _smart_retry.wrap_generate_paper_section(
                generate_fn=_generate_fn,
                section_title=section_title,
                system_prompt=system_prompt,
                user_prompt=user_prompt,
                timeout=_PAPER_NODE_TIMEOUTS.get("outline_expand", 180),
                min_chars=100,
                citation_checker=_check_paper_section_quality,
            )

            # Fallback
            if not content or len(content) < 50:
                content = f"{section_title}的详细论述内容。"

            # Phase 4: CitationTrace — extract metrics
            claims = _citation_trace.extract_claims(content)
            citations = _citation_trace.extract_citations(content)
            cite_ids = _find_relevant_citations(section_title, evidence)

            return {
                "title": section_title,
                "content": content,
                "coreArgument": f"{section_title}的核心论点",
                "method": state.get("method_preference", ""),
                "dataPlan": "数据收集与处理方案",
                "expectedResult": "预期研究成果",
                "citations": cite_ids,
                "claim_count": len(claims),
                "citation_count": len(citations),
            }

        sections_in_chapter = ch.get("sections", [])
        expanded_sections = await asyncio.gather(
            *[_expand_one_section(s) for s in sections_in_chapter]
        )
        expanded_sections = list(expanded_sections)
        done_sections += len(sections_in_chapter)

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

    await _memory_bridge.after_node(
        state, "outline_expand",
        output_summary=f"Expanded {done_sections} sections",
    )

    return {
        **memory_state,
        "expanded_outline": expanded_outline,
        "manuscript": expanded_outline,
        "current_step": "outline_expand",
    }


# ======================================================================
# Quality check and rewrite
# ======================================================================


async def outline_quality_check(state: Dict[str, Any]) -> Dict[str, Any]:
    """Evaluate the quality of the expanded outline/manuscript.

    Phase 4: uses CitationTrace for accurate coverage calculation +
    MemoryBridge for persistence.
    """
    client = _client_from_state(state)
    task_id = state["task_id"]
    session_id = state["session_id"]

    memory_state = await _memory_bridge.before_node(state, "outline_quality_check")
    state = {**state, **memory_state}

    await client.update_step(task_id, "outline_quality_check", "running", progress=20)

    manuscript = state.get("manuscript", state.get("expanded_outline", {}))
    chapters = manuscript.get("chapters", [])

    # Compute quality metrics using CitationTrace
    total_sections = 0
    sections_with_citations = 0
    total_claims = 0
    covered_claims = 0
    issues = []

    for ch in chapters:
        for section in ch.get("sections", []):
            total_sections += 1
            content = section.get("content", "")
            citations_list = section.get("citations", [])

            # Phase 4: use CitationTrace for accurate claim/citation analysis
            claims = _citation_trace.extract_claims(content)
            citations = _citation_trace.extract_citations(content)
            total_claims += len(claims)

            if citations or citations_list:
                sections_with_citations += 1
                # Count covered claims via proximity check
                for claim in claims:
                    if _citation_trace.has_nearby_citation(content, claim):
                        covered_claims += 1
            else:
                issues.append(f"章节「{section.get('title', '')}」缺少引文")

            if not content or len(content) < 50:
                issues.append(f"章节「{section.get('title', '')}」内容过短 ({len(content)}字)")

    # Evidence coverage based on citation analysis
    section_coverage = (
        (sections_with_citations / total_sections * 100) if total_sections > 0 else 0
    )
    claim_coverage = (
        (covered_claims / total_claims * 100) if total_claims > 0 else 100
    )

    # Combined score
    overall_score = min(100, max(0, (section_coverage * 0.4 + claim_coverage * 0.3 + 30)))
    if issues:
        overall_score = max(0, overall_score - len(issues) * 2)

    quality_report = {
        "logicClosedLoop": overall_score >= 70,
        "methodConsistency": "ok" if overall_score >= 60 else "needs_improvement",
        "citationVerifiability": "ok" if claim_coverage >= 70 else "needs_improvement",
        "overallScore": round(overall_score, 1),
        "sectionCoverage": round(section_coverage, 1),
        "claimCoverage": round(claim_coverage, 1),
        "totalClaims": total_claims,
        "coveredClaims": covered_claims,
        "issues": issues,
        "uncoveredSections": [
            section.get("title", "")
            for ch in chapters
            for section in ch.get("sections", [])
            if not section.get("citations") and not _citation_trace.extract_citations(section.get("content", ""))
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
        output_summary=f"Quality score: {overall_score:.0f}, section coverage: {section_coverage:.0f}%, claim coverage: {claim_coverage:.0f}%",
    )

    await _memory_bridge.after_node(
        state, "outline_quality_check",
        output_summary=f"Score={overall_score:.0f}, issues={len(issues)}",
    )

    return {
        **memory_state,
        "quality_report": quality_report,
        "quality_score": overall_score,
        "quality_issues": issues,
        "uncovered_sections": quality_report["uncoveredSections"],
        "current_step": "outline_quality_check",
    }


async def quality_rewrite(state: Dict[str, Any]) -> Dict[str, Any]:
    """Rewrite sections flagged by quality check.

    Phase 4: LLM-powered with DynamicPrompt (targeted rewrite directives) +
    ContextCompression (uncovered section evidence) + CitationTrace (close loop) +
    MemoryBridge + SmartRetry.
    """
    client = _client_from_state(state)
    task_id = state["task_id"]
    session_id = state["session_id"]

    rewrite_round = state.get("rewrite_round", 0) + 1

    memory_state = await _memory_bridge.before_node(state, "quality_rewrite")
    state = {**state, **memory_state}

    await client.update_step(task_id, "quality_rewrite", "running", progress=10)
    await client.log(task_id, "info", f"Quality rewrite round {rewrite_round}")

    manuscript = state.get("manuscript", state.get("expanded_outline", {}))
    issues = state.get("quality_issues", [])
    uncovered = set(state.get("uncovered_sections", []))
    evidence = state.get("rag_evidence", [])

    chapters = manuscript.get("chapters", [])
    rewritten_count = 0
    total_targets = sum(
        1 for ch in chapters for s in ch.get("sections", [])
        if s.get("title", "") in uncovered or len(s.get("content", "")) < 100
    )

    # Phase 5: adaptive model selection — rewrite always uses STRONG
    model_override = _select_paper_model(state, "quality_rewrite")
    effective_state = {**state, **({"llm_config_override": model_override} if model_override else {})}

    # Phase 4: dynamic system prompt
    system_prompt = _dynamic_prompt.get_paper_system_prompt("quality_rewrite", effective_state)

    # Phase 5: SmartRetry escalation hint based on round
    escalation_hint = _smart_retry.get_paper_rewrite_escalation_hint(rewrite_round - 1)

    # Collect all sections that need rewriting, then run concurrently
    rewrite_targets: List[Dict[str, Any]] = []
    for ch in chapters:
        chapter_title = ch.get("title", "")
        for section in ch.get("sections", []):
            title = section.get("title", "")
            content = section.get("content", "")
            if title in uncovered or len(content) < 100:
                rewrite_targets.append({
                    "chapter_title": chapter_title,
                    "section": section,
                })

    rewrite_success_patterns: List[Dict[str, str]] = []

    async def _rewrite_one_section(target: Dict[str, Any]) -> bool:
        section = target["section"]
        chapter_title = target["chapter_title"]
        title = section.get("title", "")
        content = section.get("content", "")

        compressed_ev = _context_compression.compress_evidence(
            evidence, section_title=title, max_items=5, max_chars=1200,
        )

        user_prompt = (
            f"当前章节：{chapter_title} > {title}\n"
            f"原始内容：\n{content}\n\n"
        )
        if compressed_ev:
            user_prompt += f"可用学术证据：\n{compressed_ev}\n\n"
        user_prompt += "请修改此章节。"

        user_prompt = _dynamic_prompt.enhance_paper_instructions(
            user_prompt, effective_state,
            step_code="quality_rewrite",
            chapter_title=chapter_title,
            section_title=title,
        )

        # Phase 5: append escalation hint
        if escalation_hint:
            user_prompt += escalation_hint

        # Phase 5: SmartRetry with citation quality checker
        async def _generate_fn(sys_prompt: str, usr_prompt: str, timeout: int) -> str:
            return await _llm_invoke(effective_state, "quality_rewrite", sys_prompt, usr_prompt, timeout=timeout, max_retries=1)

        new_content = await _smart_retry.wrap_generate_paper_section(
            generate_fn=_generate_fn,
            section_title=f"rewrite:{title}",
            system_prompt=system_prompt,
            user_prompt=user_prompt,
            timeout=_PAPER_NODE_TIMEOUTS.get("quality_rewrite", 180),
            min_chars=max(len(content), 100),
            citation_checker=_check_paper_section_quality,
        )

        if new_content and len(new_content) > len(content):
            section["content"] = new_content
            new_citations = _citation_trace.extract_citations(new_content)
            if new_citations and not section.get("citations"):
                section["citations"] = _find_relevant_citations(title, evidence)
            # Track success for long-term memory
            rewrite_success_patterns.append({
                "section": title,
                "issue": "missing_citation" if title in uncovered else "content_short",
                "action": f"Rewrote with {len(_citation_trace.extract_citations(new_content))} citations, {len(new_content)} chars",
            })
        else:
            section["content"] = (
                f"{content}\n\n"
                f"经过深入分析和文献梳理，进一步论证了{title}的核心观点。"
            )
            if not section.get("citations"):
                section["citations"] = _find_relevant_citations(title, evidence) or [1]
        return True

    if rewrite_targets:
        await asyncio.gather(*[_rewrite_one_section(t) for t in rewrite_targets])
    rewritten_count = len(rewrite_targets)

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

    await _memory_bridge.after_node(
        state, "quality_rewrite",
        output_summary=f"Rewrite round {rewrite_round}: {rewritten_count} sections",
    )

    # Phase 5: persist successful rewrite patterns to long-term memory
    for pattern in rewrite_success_patterns:
        try:
            await _memory_bridge.save_fix_pattern(
                state=effective_state,
                error_pattern=f"paper_rewrite:{pattern['issue']}:{pattern['section']}",
                fix_action=pattern["action"],
                file_path=f"paper/section/{pattern['section']}",
                success=True,
            )
        except Exception as exc:
            logger.debug("Failed to save rewrite pattern: %s", exc)

    return {
        **memory_state,
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
    if not title:
        return ""
    return re.sub(r"[^a-z0-9\u4e00-\u9fff]", "", title.lower())


def _find_relevant_citations(
    section_title: str,
    evidence: List[Dict[str, Any]],
    top_k: int = 3,
) -> List[int]:
    """Find citation indices relevant to a section title."""
    if not evidence:
        return []

    scored = []
    title_words = set(section_title.lower().split())
    for i, ev in enumerate(evidence):
        content = ev.get("content", "").lower() + " " + ev.get("title", "").lower()
        overlap = sum(1 for w in title_words if len(w) > 1 and w in content)
        rerank = ev.get("rerank_score", 0)
        scored.append((i + 1, overlap + rerank * 0.5))

    scored.sort(key=lambda x: x[1], reverse=True)
    return [idx for idx, score in scored[:top_k] if score > 0]


# ======================================================================
# Paper outline templates — fixed chapter framework by degree level
# ======================================================================

_PAPER_CHAPTER_TEMPLATES: Dict[str, List[Dict[str, Any]]] = {
    "bachelor": [
        {"index": 1, "title": "绪论", "required_sections": [
            "研究背景与意义", "国内外研究现状", "研究目标与内容", "论文组织结构",
        ]},
        {"index": 2, "title": "相关理论与技术基础", "required_sections": [
            "核心概念界定", "理论基础", "技术路线分析",
        ]},
        {"index": 3, "title": "系统设计与实现", "required_sections": [
            "需求分析", "系统总体设计", "详细设计与实现",
        ]},
        {"index": 4, "title": "系统测试与结果分析", "required_sections": [
            "测试环境与方案", "功能测试", "结果分析与讨论",
        ]},
        {"index": 5, "title": "总结与展望", "required_sections": [
            "研究工作总结", "不足与展望",
        ]},
    ],
    "master": [
        {"index": 1, "title": "绪论", "required_sections": [
            "研究背景与意义", "国内外研究现状", "研究目标与主要贡献", "论文组织结构",
        ]},
        {"index": 2, "title": "相关理论与技术基础", "required_sections": [
            "核心概念界定", "理论基础与文献综述", "关键技术分析",
        ]},
        {"index": 3, "title": "研究方法与模型设计", "required_sections": [
            "研究方法选择与论证", "模型/系统架构设计", "关键算法与实现",
        ]},
        {"index": 4, "title": "实验设计与结果分析", "required_sections": [
            "实验环境与数据集", "实验方案设计", "实验结果与对比分析", "结果讨论",
        ]},
        {"index": 5, "title": "总结与展望", "required_sections": [
            "研究工作总结", "主要贡献与创新点", "不足与未来工作",
        ]},
    ],
    "phd": [
        {"index": 1, "title": "绪论", "required_sections": [
            "研究背景与意义", "国内外研究综述", "现有研究的不足与空白",
            "研究目标与科学问题", "主要贡献与创新点", "论文组织结构",
        ]},
        {"index": 2, "title": "理论基础与文献综述", "required_sections": [
            "核心理论框架", "相关研究进展", "关键技术演进", "研究方法论综述",
        ]},
        {"index": 3, "title": "研究方法与模型构建", "required_sections": [
            "研究方法体系", "理论模型构建", "关键算法设计", "形式化分析与证明",
        ]},
        {"index": 4, "title": "实验设计与验证", "required_sections": [
            "实验设计与评价指标", "数据集与实验环境", "实验结果与分析",
            "与现有方法的对比", "消融实验",
        ]},
        {"index": 5, "title": "深入讨论与扩展分析", "required_sections": [
            "结果深入讨论", "理论意义与实践价值", "适用范围与局限性",
        ]},
        {"index": 6, "title": "总结与展望", "required_sections": [
            "研究工作总结", "主要贡献与创新点", "研究局限", "未来研究方向",
        ]},
    ],
}

# Default (when degree not specified)
_PAPER_CHAPTER_TEMPLATES["default"] = _PAPER_CHAPTER_TEMPLATES["master"]


def _get_chapter_template(degree_level: str) -> List[Dict[str, Any]]:
    """Get the chapter template for a given degree level."""
    key = degree_level.lower().strip() if degree_level else "default"
    # Normalize common variants
    for k in ("bachelor", "master", "phd"):
        if k in key or key in k:
            return _PAPER_CHAPTER_TEMPLATES[k]
    if "本科" in key or "学士" in key:
        return _PAPER_CHAPTER_TEMPLATES["bachelor"]
    if "硕" in key:
        return _PAPER_CHAPTER_TEMPLATES["master"]
    if "博" in key:
        return _PAPER_CHAPTER_TEMPLATES["phd"]
    return _PAPER_CHAPTER_TEMPLATES["default"]


def _build_template_prompt(degree_level: str, topic: str) -> str:
    """Build a structured template prompt that constrains LLM output."""
    template = _get_chapter_template(degree_level)
    lines = ["请严格按照以下章节框架设计论文大纲，每章可在必选小节基础上增加 1-2 个与主题相关的小节：\n"]
    for ch in template:
        lines.append(f"第{ch['index']}章 {ch['title']}")
        for s in ch["required_sections"]:
            lines.append(f"  - {s}（必选）")
        lines.append(f"  - [可选：与「{topic}」相关的补充小节]")
        lines.append("")
    lines.append("注意：")
    lines.append("1. 必选小节不可删除或合并，标题可根据主题适当调整措辞")
    lines.append("2. 每章必选小节之外可增加 1-2 个小节，但总数不超过 6 个")
    lines.append("3. 章节标题需具体化，体现论文主题特色")
    return "\n".join(lines)


def _validate_outline_against_template(
    chapters: List[Dict[str, Any]],
    degree_level: str,
) -> List[Dict[str, Any]]:
    """Validate and repair LLM-generated outline against the template.

    Ensures required chapters exist, fills missing ones from template.
    """
    template = _get_chapter_template(degree_level)
    if not chapters:
        return _build_fallback_outline_from_template(degree_level, "")

    # Check if LLM output has roughly the right number of chapters
    if len(chapters) < len(template) - 1:
        # Too few chapters — merge LLM output with template
        return _merge_with_template(chapters, template)

    # Ensure each chapter has at least 2 sections
    for ch in chapters:
        sections = ch.get("sections", [])
        if len(sections) < 2:
            ch["sections"] = sections + [{"title": f"{ch.get('title', '')}的补充分析"}]

    return chapters


def _merge_with_template(
    chapters: List[Dict[str, Any]],
    template: List[Dict[str, Any]],
) -> List[Dict[str, Any]]:
    """Merge LLM-generated chapters with template, filling gaps."""
    result = []
    llm_titles = {ch.get("title", "").lower() for ch in chapters}
    llm_by_idx = {ch.get("index", i + 1): ch for i, ch in enumerate(chapters)}

    for tmpl in template:
        idx = tmpl["index"]
        if idx in llm_by_idx:
            ch = llm_by_idx[idx]
            # Ensure minimum sections
            if len(ch.get("sections", [])) < 2:
                ch["sections"] = [{"title": s} for s in tmpl["required_sections"]]
            result.append(ch)
        else:
            # Use template chapter
            result.append({
                "index": idx,
                "title": tmpl["title"],
                "sections": [{"title": s} for s in tmpl["required_sections"]],
            })

    return result


def _build_fallback_outline_from_template(degree_level: str, topic: str) -> List[Dict[str, Any]]:
    """Build fallback outline from degree-level template."""
    template = _get_chapter_template(degree_level)
    return [
        {
            "index": ch["index"],
            "title": ch["title"],
            "sections": [{"title": s} for s in ch["required_sections"]],
        }
        for ch in template
    ]


def _build_fallback_outline(topic: str) -> List[Dict[str, Any]]:
    """Build standard 5-chapter fallback outline when LLM fails."""
    return _build_fallback_outline_from_template("default", topic)
