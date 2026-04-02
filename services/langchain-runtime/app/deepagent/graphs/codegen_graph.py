"""Code generation StateGraph with sandbox back-chain.

Graph topology:
    requirement_analyze → sql_generate → codegen_backend → codegen_frontend
        → sandbox_init → sandbox_build_verify
        ←→ sandbox_build_fix  (conditional loop, max 3 rounds)
        → sandbox_smoke_test
        ←→ sandbox_build_fix  (conditional loop for runtime issues)
        → sandbox_preview_deploy → END
"""

from __future__ import annotations

from langgraph.graph import END, StateGraph

from ..nodes.codegen_nodes import (
    codegen_backend,
    codegen_frontend,
    requirement_analyze,
    sandbox_build_fix,
    sandbox_build_verify,
    sandbox_init,
    sandbox_preview_deploy,
    sandbox_smoke_test,
    should_fix_build,
    should_fix_smoke,
    sql_generate,
)
from ..state.codegen_state import CodegenState


def build_codegen_graph() -> StateGraph:
    """Build and return the compiled code generation graph.

    Returns a compiled StateGraph ready for .invoke() or .ainvoke().
    """
    graph = StateGraph(CodegenState)

    # --- Front-chain: requirement analysis → code generation ---
    graph.add_node("requirement_analyze", requirement_analyze)
    graph.add_node("sql_generate", sql_generate)
    graph.add_node("codegen_backend", codegen_backend)
    graph.add_node("codegen_frontend", codegen_frontend)

    # --- Sandbox back-chain: build → fix → test → preview ---
    graph.add_node("sandbox_init", sandbox_init)
    graph.add_node("build_verify", sandbox_build_verify)
    graph.add_node("build_fix", sandbox_build_fix)
    graph.add_node("smoke_test", sandbox_smoke_test)
    graph.add_node("preview_deploy", sandbox_preview_deploy)

    # --- Edges: front-chain (sequential) ---
    graph.set_entry_point("requirement_analyze")
    graph.add_edge("requirement_analyze", "sql_generate")
    graph.add_edge("sql_generate", "codegen_backend")
    graph.add_edge("codegen_backend", "codegen_frontend")
    graph.add_edge("codegen_frontend", "sandbox_init")

    # --- Edges: sandbox chain ---
    graph.add_edge("sandbox_init", "build_verify")

    # After build_verify: conditional → fix or smoke_test
    graph.add_conditional_edges(
        "build_verify",
        should_fix_build,
        {
            "build_fix": "build_fix",
            "smoke_test": "smoke_test",
        },
    )

    # After build_fix: always re-verify
    graph.add_edge("build_fix", "build_verify")

    # After smoke_test: conditional → fix or preview
    graph.add_conditional_edges(
        "smoke_test",
        should_fix_smoke,
        {
            "build_fix": "build_fix",
            "preview_deploy": "preview_deploy",
        },
    )

    # Preview deploy → END
    graph.add_edge("preview_deploy", END)

    return graph.compile()
