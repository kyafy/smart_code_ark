"""State schema for the paper generation graph."""

from __future__ import annotations

from typing import Any, Dict, List, Optional
from typing_extensions import TypedDict


class PaperState(TypedDict, total=False):
    """Typed state carried through the paper generation StateGraph."""

    # --- identity ---
    task_id: str
    session_id: int

    # --- inputs ---
    topic: str
    discipline: str
    degree_level: str
    method_preference: str

    # --- topic clarification ---
    topic_refined: str
    research_questions: List[str]

    # --- academic retrieval ---
    retrieved_sources: List[Dict[str, Any]]
    source_count: int

    # --- RAG ---
    rag_chunk_count: int
    rag_evidence: List[Dict[str, Any]]

    # --- outline ---
    outline_draft: Dict[str, Any]  # chapter-section hierarchy
    expanded_outline: Dict[str, Any]
    chapter_evidence_map: Dict[str, Any]
    citation_style: str  # default "GB/T 7714"

    # --- quality ---
    quality_report: Dict[str, Any]
    quality_score: float
    quality_issues: List[str]
    uncovered_sections: List[str]
    rewrite_round: int

    # --- manuscript ---
    manuscript: Dict[str, Any]

    # --- memory (Phase 4) ---
    short_term_memories: List[str]
    long_term_memories: List[str]
    memory_context: str

    # --- control ---
    current_step: str
    error: Optional[str]

    # --- callback ---
    callback_base_url: str
    callback_api_key: str
