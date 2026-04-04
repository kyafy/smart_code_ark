"""Code generation StateGraph with parallel domains and sandbox back-chain.

Graph topology (Phase 5):

    requirement_analyze
           ↓
    plan_validate  (rule-based pre-flight check, no LLM)
    ┌──────┴─────────┬──────────────────┐
sql_generate   codegen_backend   codegen_frontend   ← parallel via Send API
    └──────┬─────────┴──────────────────┘
    artifact_contract_validate  (cross-domain consistency check)
           ↓
    sandbox_init → compile_check → build_verify
                                       ↓ (conditional)
                                   build_fix  ←→  build_verify  (loop, max 3 rounds)
                                       ↓
                                   smoke_test
                                       ↓ (conditional)
                                   build_fix  ←→  smoke_test    (loop, max 2 rounds)
                                       ↓
                                   preview_deploy → END
"""

from __future__ import annotations

import logging

from langgraph.graph import END, StateGraph

from ..nodes.artifact_contract_validate_node import artifact_contract_validate
from ..nodes.codegen_nodes import (
    codegen_backend,
    codegen_frontend,
    requirement_analyze,
    sandbox_build_fix,
    sandbox_build_verify,
    sandbox_compile_check,
    sandbox_init,
    sandbox_preview_deploy,
    sandbox_smoke_test,
    should_fix_build,
    should_fix_smoke,
    sql_generate,
)
from ..nodes.plan_validate_node import plan_validate, route_to_parallel_codegen
from ..state.codegen_state import CodegenState

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Optional: LangGraph RetryPolicy (requires langgraph >= 0.2)
# ---------------------------------------------------------------------------
try:
    from langgraph.pregel import RetryPolicy as _RetryPolicy
    import httpx as _httpx

    _CODEGEN_RETRY = _RetryPolicy(
        max_attempts=2,
        retry_on=(_httpx.TimeoutException, _httpx.NetworkError),
        wait=1.0,
        wait_factor=2.0,
        wait_max=10.0,
    )
    logger.info("codegen_graph: LangGraph RetryPolicy enabled for generation nodes")
except Exception:
    _CODEGEN_RETRY = None  # type: ignore[assignment]
    logger.info("codegen_graph: RetryPolicy not available, skipping node-level retry")


def build_codegen_graph() -> StateGraph:
    """Build and return the compiled code generation graph.

    Returns a compiled StateGraph ready for .invoke() or .ainvoke().
    """
    graph = StateGraph(CodegenState)

    # --- Front-chain ---
    _add = lambda name, fn, retry=None: (  # noqa: E731
        graph.add_node(name, fn, retry=retry) if retry is not None
        else graph.add_node(name, fn)
    )

    _add("requirement_analyze", requirement_analyze)
    _add("plan_validate", plan_validate)

    # Parallel generation nodes — retry on transient LLM / network errors
    _add("sql_generate", sql_generate, retry=_CODEGEN_RETRY)
    _add("codegen_backend", codegen_backend, retry=_CODEGEN_RETRY)
    _add("codegen_frontend", codegen_frontend, retry=_CODEGEN_RETRY)

    _add("artifact_contract_validate", artifact_contract_validate)

    # --- Sandbox back-chain ---
    _add("sandbox_init", sandbox_init)
    _add("compile_check", sandbox_compile_check)
    _add("build_verify", sandbox_build_verify)
    _add("build_fix", sandbox_build_fix)
    _add("smoke_test", sandbox_smoke_test)
    _add("preview_deploy", sandbox_preview_deploy)

    # --- Edges: front-chain ---
    graph.set_entry_point("requirement_analyze")
    graph.add_edge("requirement_analyze", "plan_validate")

    # Fan-out: plan_validate → [sql_generate, codegen_backend, codegen_frontend] in parallel
    graph.add_conditional_edges(
        "plan_validate",
        route_to_parallel_codegen,
    )

    # Fan-in: all three parallel nodes → artifact_contract_validate
    graph.add_edge("sql_generate", "artifact_contract_validate")
    graph.add_edge("codegen_backend", "artifact_contract_validate")
    graph.add_edge("codegen_frontend", "artifact_contract_validate")

    graph.add_edge("artifact_contract_validate", "sandbox_init")

    # --- Edges: sandbox chain ---
    graph.add_edge("sandbox_init", "compile_check")
    graph.add_edge("compile_check", "build_verify")

    graph.add_conditional_edges(
        "build_verify",
        should_fix_build,
        {
            "build_fix": "build_fix",
            "smoke_test": "smoke_test",
        },
    )

    graph.add_edge("build_fix", "build_verify")

    graph.add_conditional_edges(
        "smoke_test",
        should_fix_smoke,
        {
            "build_fix": "build_fix",
            "preview_deploy": "preview_deploy",
        },
    )

    graph.add_edge("preview_deploy", END)

    return graph.compile()
