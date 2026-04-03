"""plan_validate node — pure rule-based pre-flight check before code generation.

No LLM calls.  Validates the file plan produced by requirement_analyze and
fails fast on critical structural issues.  On success, fans out to the three
parallel generation domains via LangGraph Send API.
"""

from __future__ import annotations

import logging
import os
from typing import Any, Dict, List

from ..tools.java_api_client import JavaApiClient

logger = logging.getLogger(__name__)

# Patterns that must exist in a valid file plan
_REQUIRED_ENTRY_FILES = {
    "package.json",
    "pom.xml",
    "build.gradle",
    "Pipfile",
    "go.mod",
    "Cargo.toml",
}

_ALLOWED_MAX_FILES = max(50, int(os.getenv("DEEPAGENT_PLAN_MAX_FILES", "260")))


async def plan_validate(state: Dict[str, Any]) -> Dict[str, Any]:
    """Validate the file plan before fanning out to parallel code generation.

    Hard checks (fail fast, no LLM cost):
    - file_plan is non-empty
    - no absolute paths or path-traversal sequences
    - at least one recognised entry-file is present
    - total file count is within safe limits
    """
    client = JavaApiClient.from_state(state)
    task_id = state["task_id"]

    await client.update_step(task_id, "plan_validate", "running", progress=10)

    file_plan: List[Dict[str, Any]] = state.get("file_plan", [])
    violations: List[str] = []

    # --- Check 1: non-empty plan ---
    if not file_plan:
        violations.append("file_plan is empty — requirement_analyze produced no files")

    # --- Check 2: no unsafe paths ---
    for item in file_plan:
        path: str = item.get("path", "")
        if path.startswith("/"):
            violations.append(f"absolute path not allowed: {path}")
        if ".." in path.split("/"):
            violations.append(f"path traversal not allowed: {path}")

    # --- Check 3: entry file exists ---
    all_paths = {item.get("path", "").lower() for item in file_plan}
    has_entry = any(
        any(ep in p for p in all_paths)
        for ep in (e.lower() for e in _REQUIRED_ENTRY_FILES)
    )
    if not has_entry and file_plan:
        violations.append(
            f"no recognised entry file found (expected one of {sorted(_REQUIRED_ENTRY_FILES)})"
        )

    # --- Check 4: file count sanity ---
    if len(file_plan) > _ALLOWED_MAX_FILES:
        violations.append(
            f"file_plan too large: {len(file_plan)} files (max {_ALLOWED_MAX_FILES})"
        )

    # Hard failures abort the pipeline
    if violations:
        error_msg = "; ".join(violations)
        await client.update_step(
            task_id, "plan_validate", "failed",
            progress=0,
            error_code="PLAN_INVALID",
            error_message=error_msg,
        )
        await client.log(task_id, "error", f"plan_validate failed: {error_msg}")
        raise ValueError(f"plan_validate failed: {error_msg}")

    # Soft warnings (non-fatal)
    warnings: List[str] = []
    if not any("dockerfile" in p.lower() or "docker-compose" in p.lower() for p in all_paths):
        warnings.append("no Dockerfile or docker-compose.yml found in file plan")
    for w in warnings:
        await client.log(task_id, "warn", f"plan_validate: {w}")
        logger.warning("plan_validate[%s]: %s", task_id, w)

    await client.update_step(
        task_id, "plan_validate", "finished",
        progress=100,
        output_summary=f"Plan validated: {len(file_plan)} files, {len(warnings)} warnings",
    )
    logger.info("plan_validate: OK — %d files, %d warnings", len(file_plan), len(warnings))

    return {"current_step": "plan_validate"}


def route_to_parallel_codegen(state: Dict[str, Any]):
    """Conditional edge function: fan out to the three generation domains in parallel.

    Returns a list of Send objects so LangGraph executes all three nodes
    concurrently and merges their state updates via the _merge_dicts reducer.
    """
    from langgraph.types import Send  # local import avoids circular deps at module load

    return [
        Send("sql_generate", state),
        Send("codegen_backend", state),
        Send("codegen_frontend", state),
    ]
