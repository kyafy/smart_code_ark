"""Paper generation StateGraph.

Graph topology:
    topic_clarify → academic_retrieve → rag_index_enrich → rag_retrieve_rerank
        → outline_generate → outline_expand → outline_quality_check
        ←→ quality_rewrite  (conditional loop, max 2 rounds)
        → END
"""

from __future__ import annotations

from langgraph.graph import END, StateGraph

from ..nodes.paper_nodes import (
    academic_retrieve,
    outline_expand,
    outline_generate,
    outline_quality_check,
    quality_rewrite,
    rag_index_enrich,
    rag_retrieve_rerank,
    should_rewrite,
    topic_clarify,
)
from ..state.paper_state import PaperState


def build_paper_graph() -> StateGraph:
    """Build and return the compiled paper generation graph.

    Returns a compiled StateGraph ready for .invoke() or .ainvoke().
    """
    graph = StateGraph(PaperState)

    # --- Nodes ---
    graph.add_node("topic_clarify", topic_clarify)
    graph.add_node("academic_retrieve", academic_retrieve)
    graph.add_node("rag_index_enrich", rag_index_enrich)
    graph.add_node("rag_retrieve_rerank", rag_retrieve_rerank)
    graph.add_node("outline_generate", outline_generate)
    graph.add_node("outline_expand", outline_expand)
    graph.add_node("outline_quality_check", outline_quality_check)
    graph.add_node("quality_rewrite", quality_rewrite)

    # --- Edges: sequential pipeline ---
    graph.set_entry_point("topic_clarify")
    graph.add_edge("topic_clarify", "academic_retrieve")
    graph.add_edge("academic_retrieve", "rag_index_enrich")
    graph.add_edge("rag_index_enrich", "rag_retrieve_rerank")
    graph.add_edge("rag_retrieve_rerank", "outline_generate")
    graph.add_edge("outline_generate", "outline_expand")
    graph.add_edge("outline_expand", "outline_quality_check")

    # --- Conditional: quality check → rewrite loop or END ---
    graph.add_conditional_edges(
        "outline_quality_check",
        should_rewrite,
        {
            "rewrite": "quality_rewrite",
            "end": END,
        },
    )

    # After rewrite, re-check quality
    graph.add_edge("quality_rewrite", "outline_quality_check")

    return graph.compile()
