"""State schema for the code generation graph."""

from __future__ import annotations

from typing import Any, Dict, List, Optional
from typing_extensions import TypedDict


class CodegenState(TypedDict, total=False):
    """Typed state carried through the code generation StateGraph."""

    # --- identity ---
    task_id: str
    project_id: str
    user_id: int

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
    generated_files: Dict[str, str]  # path -> content

    # --- sandbox ---
    sandbox_id: Optional[str]  # Docker container ID
    sandbox_port: Optional[int]  # host port mapped to sandbox

    # --- build / verify ---
    build_status: str  # pending | passed | failed
    build_log: str
    build_fix_round: int
    contract_violations: List[str]

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

    # --- control ---
    current_step: str
    error: Optional[str]

    # --- callback ---
    callback_base_url: str
    callback_api_key: str
