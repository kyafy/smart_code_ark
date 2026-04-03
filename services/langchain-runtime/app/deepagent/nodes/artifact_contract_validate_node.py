"""artifact_contract_validate node — cross-domain consistency check.

Runs after the three parallel generation domains (sql, backend, frontend)
have merged their outputs.  Performs lightweight text-based checks to catch
obvious inconsistencies before handing off to the sandbox chain.

No LLM calls.  Violations are logged and stored in state but do NOT abort
the pipeline (degraded build is still attempted — sandbox can sometimes fix
minor issues).
"""

from __future__ import annotations

import logging
import re
from typing import Any, Dict, List, Set

from ..cancellation import check_cancelled
from ..tools.java_api_client import JavaApiClient

logger = logging.getLogger(__name__)


def _extract_table_names_from_sql(sql_content: str) -> Set[str]:
    """Extract CREATE TABLE names from SQL DDL."""
    return set(re.findall(r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?[`\"]?(\w+)[`\"]?",
                          sql_content, re.IGNORECASE))


def _extract_entity_table_refs(java_content: str) -> Set[str]:
    """Extract @Table(name=...) and simple entity class names from Java source."""
    names: Set[str] = set()
    for m in re.finditer(r'@Table\s*\(\s*name\s*=\s*["\'](\w+)["\']', java_content, re.IGNORECASE):
        names.add(m.group(1).lower())
    return names


def _extract_frontend_api_paths(content: str) -> Set[str]:
    """Extract REST API path prefixes from fetch/axios/request calls."""
    paths: Set[str] = set()
    for m in re.finditer(r"""(?:axios\.|fetch\(|request\(|api\.)[^\n]*['"/](\/api\/[\w/{}]+)""",
                         content, re.IGNORECASE):
        paths.add(m.group(1))
    return paths


def _extract_backend_request_mappings(content: str) -> Set[str]:
    """Extract @RequestMapping / @GetMapping etc. paths from Spring controllers."""
    paths: Set[str] = set()
    for m in re.finditer(r'@(?:Request|Get|Post|Put|Delete|Patch)Mapping\s*\(\s*(?:value\s*=\s*)?["{\']([\w/{}]+)["\'}]',
                         content, re.IGNORECASE):
        paths.add(m.group(1))
    return paths


async def artifact_contract_validate(state: Dict[str, Any]) -> Dict[str, Any]:
    """Validate cross-domain consistency after parallel generation.

    Checks:
    1. SQL table names that appear in .sql files have a corresponding JPA
       @Table reference in backend Java sources.
    2. Frontend API paths (axios/fetch calls) have at least a prefix match
       in backend controller mappings.

    Violations are non-fatal: the pipeline continues to the sandbox chain.
    """
    check_cancelled(state)
    client = JavaApiClient.from_state(state)
    task_id = state["task_id"]

    await client.update_step(task_id, "artifact_contract_validate", "running", progress=10)

    generated: Dict[str, str] = state.get("generated_files", {})
    violations: List[str] = []

    # Bucket files by domain
    sql_files = {p: c for p, c in generated.items() if p.lower().endswith(".sql")}
    java_files = {p: c for p, c in generated.items()
                  if p.lower().endswith(".java") and "entity" in p.lower()}
    controller_files = {p: c for p, c in generated.items()
                        if p.lower().endswith(".java") and "controller" in p.lower()}
    frontend_files = {p: c for p, c in generated.items()
                      if any(p.lower().endswith(ext) for ext in (".vue", ".ts", ".tsx", ".js", ".jsx"))}

    # --- Check 1: SQL tables → backend entities ---
    all_sql_tables: Set[str] = set()
    for content in sql_files.values():
        all_sql_tables |= _extract_table_names_from_sql(content)

    all_entity_refs: Set[str] = set()
    for content in java_files.values():
        all_entity_refs |= _extract_entity_table_refs(content)

    for table in all_sql_tables:
        if all_entity_refs and table.lower() not in {r.lower() for r in all_entity_refs}:
            violations.append(f"SQL table '{table}' has no matching @Table in backend entities")

    # --- Check 2: Frontend API paths → backend controller mappings ---
    all_frontend_paths: Set[str] = set()
    for content in frontend_files.values():
        all_frontend_paths |= _extract_frontend_api_paths(content)

    all_controller_paths: Set[str] = set()
    for content in controller_files.values():
        all_controller_paths |= _extract_backend_request_mappings(content)

    for path in all_frontend_paths:
        # Prefix match is enough (path params handled)
        prefix = "/".join(path.split("/")[:3])  # e.g. /api/user
        matched = any(cp.startswith(prefix) or prefix.startswith(cp)
                      for cp in all_controller_paths)
        if all_controller_paths and not matched:
            violations.append(f"Frontend API path '{path}' has no matching backend controller mapping")

    # Log violations (non-fatal)
    for v in violations:
        await client.log(task_id, "warn", f"contract: {v}")
        logger.warning("artifact_contract_validate[%s]: %s", task_id, v)

    missing_empty = [p for p, c in generated.items() if not c or not c.strip()]
    if missing_empty:
        await client.log(task_id, "warn",
                         f"contract: {len(missing_empty)} file(s) generated empty/timeout: "
                         + ", ".join(missing_empty[:5]))

    await client.update_step(
        task_id, "artifact_contract_validate", "finished",
        progress=100,
        output_summary=(
            f"Contract check: {len(violations)} violations, "
            f"{len(missing_empty)} empty files, "
            f"{len(generated)} total files"
        ),
    )
    logger.info(
        "artifact_contract_validate[%s]: %d violations, %d empty, %d total",
        task_id, len(violations), len(missing_empty), len(generated),
    )

    return {
        "contract_violations": violations,
        "current_step": "artifact_contract_validate",
    }
