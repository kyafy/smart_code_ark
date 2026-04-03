"""State schema for the code generation graph."""

from __future__ import annotations

import operator
from typing import Any, Annotated, Dict, List, Optional
from typing_extensions import TypedDict


def _merge_dicts(a: Dict[str, str], b: Dict[str, str]) -> Dict[str, str]:
    """Reducer for parallel nodes that each produce a subset of generated_files."""
    return {**a, **b}


class CodegenState(TypedDict, total=False):
    """Typed state carried through the code generation StateGraph."""

    # --- identity ---
    task_id: str
    project_id: str
    user_id: int

    # --- run tracking (Phase 2) ---
    run_id: str                        # unique per pipeline execution, used in metrics & logs
    node_metrics: Optional[Dict[str, Any]]  # latest node metrics snapshot

    # --- per-task LLM override (Phase 3) ---
    llm_config_override: Optional[Dict[str, Any]]  # forwarded from CodegenRunRequest.llm_config

    # --- inputs ---
    instructions: str
    prd: str
    stack_backend: str
    stack_frontend: str
    stack_db: str
    template_key: Optional[str]

    # --- planning ---
    file_plan: List[Dict[str, Any]]  # [{path, group, priority}, ...]
    file_list: List[str]

    # --- workspace ---
    workspace_dir: str
    # Annotated with _merge_dicts so parallel codegen nodes can write concurrently
    generated_files: Annotated[Dict[str, str], _merge_dicts]

    # --- contract validation (Phase 2) ---
    contract_violations: List[str]

    # --- sandbox ---
    sandbox_id: Optional[str]  # Docker container ID
    sandbox_port: Optional[int]  # host port mapped to sandbox

    # --- build / verify ---
    build_status: str  # pending | passed | failed
    build_log: str
    build_fix_round: int

    # --- smoke test ---
    smoke_status: str  # pending | passed | failed
    smoke_log: str
    smoke_fix_round: int

    # --- preview ---
    preview_url: str
    preview_status: str  # pending | ready | failed

    # --- quality ---
    quality_score: float
    quality_issues: List[str]

    # --- memory (Phase 4) ---
    short_term_memories: List[str]       # recent checkpoint summaries for this task
    long_term_memories: List[str]        # cross-task success/failure patterns
    memory_context: str                  # assembled context pack injected into prompts
    fix_history: List[Dict[str, Any]]    # per-task fix records: [{file, error, fix, round}, ...]

    # --- control ---
    current_step: str
    error: Optional[str]

    # --- callback ---
    callback_base_url: str
    callback_api_key: str
