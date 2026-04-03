"""Node functions for the code generation StateGraph.

Each node receives the full CodegenState dict and returns a partial
dict update.  Nodes use tools (Java API callbacks + sandbox execute)
to perform their work.
"""

from __future__ import annotations

import logging
import os
from typing import Any, Dict

from ..sandbox.sandbox_factory import create_sandbox, get_sandbox, mark_for_preview
from ..tools.java_api_client import JavaApiClient

logger = logging.getLogger(__name__)


# ======================================================================
# Front-chain nodes (requirement → codegen)
# ======================================================================


async def requirement_analyze(state: Dict[str, Any]) -> Dict[str, Any]:
    """Analyze PRD and produce a file plan.

    Calls Java ModelService to generate project structure, then sanitizes
    and validates the file list.
    """
    client = JavaApiClient.from_state(state)
    task_id = state["task_id"]

    await client.update_step(task_id, "requirement_analyze", "running", progress=10)

    # Resolve template
    stack = {
        "backend": state.get("stack_backend", "springboot"),
        "frontend": state.get("stack_frontend", "vue3"),
        "db": state.get("stack_db", "mysql"),
    }
    template_info = await client.template_resolve(
        stack=stack, template_id=state.get("template_key", "")
    )

    # Generate project structure via Java ModelService
    structure = await client.generate_project_structure(
        prd=state.get("prd", ""),
        stack=stack,
        instructions=state.get("instructions", ""),
    )

    files = structure.get("files", [])
    # Sanitize: remove unsafe paths
    safe_files = [
        f for f in files
        if not f.startswith("/") and ".." not in f
    ]

    # Build file plan with group classification
    file_plan = []
    for path in safe_files:
        group = _classify_file_group(path)
        file_plan.append({"path": path, "group": group, "priority": 0})

    await client.update_step(
        task_id, "requirement_analyze", "finished",
        progress=100,
        output_summary=f"Generated file plan with {len(file_plan)} files",
    )

    return {
        "file_plan": file_plan,
        "file_list": safe_files,
        "template_key": template_info.get("template_key"),
        "current_step": "requirement_analyze",
    }


async def sql_generate(state: Dict[str, Any]) -> Dict[str, Any]:
    """Generate database schema and infrastructure files."""
    return await _generate_files_by_groups(
        state, groups={"database", "infra", "docs"}, step_code="sql_generate"
    )


async def codegen_backend(state: Dict[str, Any]) -> Dict[str, Any]:
    """Generate backend source code files."""
    return await _generate_files_by_groups(
        state, groups={"backend"}, step_code="codegen_backend"
    )


async def codegen_frontend(state: Dict[str, Any]) -> Dict[str, Any]:
    """Generate frontend source code files."""
    return await _generate_files_by_groups(
        state, groups={"frontend"}, step_code="codegen_frontend"
    )


# ======================================================================
# Sandbox back-chain nodes (build → test → preview)
# ======================================================================


async def sandbox_init(state: Dict[str, Any]) -> Dict[str, Any]:
    """Initialize the sandbox container and write all generated files into it."""
    from ..config import SandboxConfig

    task_id = state["task_id"]
    config = SandboxConfig.from_env()
    sandbox = await create_sandbox(task_id, config)

    # Write all generated files into the sandbox
    generated = state.get("generated_files", {})
    for path, content in generated.items():
        await sandbox.write(f"/app/{path}", content)

    logger.info("Sandbox initialized with %d files for task %s", len(generated), task_id)

    return {
        "sandbox_id": sandbox.container_id,
        "sandbox_port": sandbox.host_port,
        "current_step": "sandbox_init",
    }


async def sandbox_build_verify(state: Dict[str, Any]) -> Dict[str, Any]:
    """Run build commands inside the sandbox and capture results.

    The agent executes npm install / npm run build (or equivalent)
    inside the sandbox container and observes the full output.
    """
    task_id = state["task_id"]
    sandbox = await get_sandbox(task_id)
    if sandbox is None:
        return {"build_status": "failed", "build_log": "Sandbox not found"}

    client = JavaApiClient.from_state(state)
    await client.update_step(task_id, "build_verify", "running", progress=10)

    # Detect project type and run build
    has_package_json = await sandbox.file_exists("/app/package.json")
    has_pom = await sandbox.file_exists("/app/pom.xml")

    build_log_parts = []

    if has_package_json:
        # Node.js project
        result = await sandbox.execute("cd /app && npm install --prefer-offline 2>&1", timeout=120)
        build_log_parts.append(f"=== npm install (exit={result.exit_code}) ===\n{result.stdout}")

        if result.exit_code == 0:
            result = await sandbox.execute("cd /app && npm run build 2>&1", timeout=180)
            build_log_parts.append(f"=== npm run build (exit={result.exit_code}) ===\n{result.stdout}")

    elif has_pom:
        # Maven project
        result = await sandbox.execute(
            "cd /app && mvn -q -DskipTests package 2>&1", timeout=300
        )
        build_log_parts.append(f"=== mvn package (exit={result.exit_code}) ===\n{result.stdout}")

    else:
        build_log_parts.append("No recognizable build system found (no package.json or pom.xml)")
        result = type("R", (), {"exit_code": 1})()

    build_log = "\n\n".join(build_log_parts)
    build_status = "passed" if result.exit_code == 0 else "failed"

    await client.update_step(
        task_id, "build_verify", "finished" if build_status == "passed" else "running",
        progress=70 if build_status == "passed" else 50,
        output_summary=f"Build {build_status}",
    )

    return {
        "build_status": build_status,
        "build_log": build_log,
        "current_step": "build_verify",
    }


async def sandbox_build_fix(state: Dict[str, Any]) -> Dict[str, Any]:
    """Attempt to fix build errors inside the sandbox.

    Reads the build log, identifies error files, reads their content,
    requests LLM to generate fixes, writes fixes back, and re-builds.
    """
    task_id = state["task_id"]
    sandbox = await get_sandbox(task_id)
    if sandbox is None:
        return {"build_status": "failed", "build_log": "Sandbox not found for fix"}

    client = JavaApiClient.from_state(state)
    build_log = state.get("build_log", "")
    round_num = state.get("build_fix_round", 0) + 1

    await client.log(task_id, "info", f"Build fix attempt {round_num}")

    # Extract error file paths from build log (common patterns)
    error_files = _extract_error_files(build_log)

    if not error_files:
        # No specific files identified — try a general fix
        await client.log(task_id, "warn", "No specific error files found in build log")
        return {
            "build_fix_round": round_num,
            "build_status": "failed",
            "current_step": "build_fix",
        }

    # For each error file: read current content, request fix from LLM
    for file_path in error_files[:5]:  # limit to 5 files per round
        current = await sandbox.read(f"/app/{file_path}")

        # Extract relevant error lines for this file
        file_errors = _extract_errors_for_file(build_log, file_path)

        fixed_content = await client.generate_file_content(
            file_path=file_path,
            prd=state.get("prd", ""),
            tech_stack=f"{state.get('stack_backend', '')} {state.get('stack_frontend', '')}",
            project_structure="\n".join(state.get("file_list", [])),
            group=_classify_file_group(file_path),
            instructions=f"Fix the following build errors:\n{file_errors}\n\nCurrent file content:\n{current[:3000]}",
        )

        if fixed_content and fixed_content.strip():
            await sandbox.write(f"/app/{file_path}", fixed_content)
            await client.log(task_id, "info", f"Fixed: {file_path}")

    # Re-run build to verify fix
    verify_result = await sandbox_build_verify(state)

    return {
        **verify_result,
        "build_fix_round": round_num,
        "current_step": "build_fix",
    }


async def sandbox_smoke_test(state: Dict[str, Any]) -> Dict[str, Any]:
    """Start the dev server in sandbox and run smoke tests.

    Boots the application, polls for health, and optionally tests
    specific API endpoints or pages.
    """
    task_id = state["task_id"]
    sandbox = await get_sandbox(task_id)
    if sandbox is None:
        return {"smoke_status": "failed", "smoke_log": "Sandbox not found"}

    client = JavaApiClient.from_state(state)
    await client.update_step(task_id, "smoke_test", "running", progress=10)

    # Start dev server in background
    await sandbox.execute(
        "cd /app && npm run dev -- --host 0.0.0.0 > /tmp/dev-server.log 2>&1 &",
        timeout=10,
    )

    # Poll for health
    healthy = False
    smoke_log_parts = []

    for attempt in range(20):
        await _async_sleep(3)
        result = await sandbox.execute(
            "curl -s -o /dev/null -w '%{http_code}' http://localhost:5173/ 2>/dev/null || echo 000",
            timeout=10,
        )
        status_code = result.stdout.strip()
        smoke_log_parts.append(f"Health check {attempt + 1}: HTTP {status_code}")

        if status_code in ("200", "304", "301", "302"):
            healthy = True
            break

    if not healthy:
        # Capture dev server logs for debugging
        log_result = await sandbox.execute("cat /tmp/dev-server.log 2>/dev/null | tail -50", timeout=10)
        smoke_log_parts.append(f"\n=== Dev server logs ===\n{log_result.stdout}")

    smoke_log = "\n".join(smoke_log_parts)
    smoke_status = "passed" if healthy else "failed"

    await client.update_step(
        task_id, "smoke_test",
        "finished" if smoke_status == "passed" else "running",
        progress=90 if smoke_status == "passed" else 60,
        output_summary=f"Smoke test {smoke_status}",
    )

    return {
        "smoke_status": smoke_status,
        "smoke_log": smoke_log,
        "current_step": "smoke_test",
    }


async def sandbox_preview_deploy(state: Dict[str, Any]) -> Dict[str, Any]:
    """Register the sandbox as a preview environment.

    Since the smoke test already started the dev server in the sandbox,
    we just register the sandbox port with the Java gateway.  This
    replaces the 6-phase PreviewDeployService flow entirely.
    """
    task_id = state["task_id"]
    sandbox = await get_sandbox(task_id)
    if sandbox is None:
        return {"preview_status": "failed", "preview_url": ""}

    client = JavaApiClient.from_state(state)

    # Mark sandbox for preview reuse (skip cleanup)
    host_port = await mark_for_preview(task_id)
    if host_port is None:
        return {"preview_status": "failed", "preview_url": ""}

    # Notify Java gateway to register the route
    preview_url = await client.notify_sandbox_ready(
        task_id=task_id,
        host_port=host_port,
        container_id=sandbox.container_id or "",
    )

    await client.update_step(
        task_id, "preview_deploy", "finished",
        progress=100,
        output_summary=f"Preview ready at {preview_url}",
        output={"host_port": host_port, "preview_url": preview_url},
    )

    return {
        "preview_url": preview_url,
        "preview_status": "ready",
        "current_step": "preview_deploy",
    }


# ======================================================================
# Routing functions (conditional edges)
# ======================================================================


def should_fix_build(state: Dict[str, Any]) -> str:
    """Route after build_verify: fix or proceed to smoke test."""
    if state.get("build_status") == "passed":
        return "smoke_test"
    max_rounds = 3
    if state.get("build_fix_round", 0) >= max_rounds:
        return "smoke_test"  # give up fixing, try smoke test anyway
    return "build_fix"


def should_fix_smoke(state: Dict[str, Any]) -> str:
    """Route after smoke_test: retry fix or proceed to preview."""
    if state.get("smoke_status") == "passed":
        return "preview_deploy"
    max_rounds = 2
    if state.get("smoke_fix_round", 0) >= max_rounds:
        return "preview_deploy"  # deploy anyway with degraded status
    return "build_fix"  # try fixing build issues that cause runtime failure


# ======================================================================
# Internal helpers
# ======================================================================


async def _generate_files_by_groups(
    state: Dict[str, Any],
    groups: set[str],
    step_code: str,
) -> Dict[str, Any]:
    """Generate code files for the specified groups via Java ModelService."""
    client = JavaApiClient.from_state(state)
    task_id = state["task_id"]

    await client.update_step(task_id, step_code, "running", progress=10)

    file_plan = state.get("file_plan", [])
    target_files = [f for f in file_plan if f.get("group") in groups]

    generated = dict(state.get("generated_files", {}))
    success_count = 0
    fail_count = 0

    tech_stack = f"{state.get('stack_backend', '')} {state.get('stack_frontend', '')} {state.get('stack_db', '')}"
    project_structure = "\n".join(state.get("file_list", []))

    for i, file_item in enumerate(target_files):
        path = file_item["path"]
        group = file_item["group"]

        try:
            content = await client.generate_file_content(
                file_path=path,
                prd=state.get("prd", ""),
                tech_stack=tech_stack,
                project_structure=project_structure,
                group=group,
                instructions=state.get("instructions", ""),
            )
            if content and content.strip():
                generated[path] = content
                success_count += 1
            else:
                fail_count += 1
        except Exception as exc:
            logger.warning("Failed to generate %s: %s", path, exc)
            fail_count += 1

        # Progress update
        progress = int(10 + 80 * (i + 1) / max(len(target_files), 1))
        await client.update_step(task_id, step_code, "running", progress=progress)

    await client.update_step(
        task_id, step_code, "finished",
        progress=100,
        output_summary=f"Generated {success_count} files, {fail_count} failures",
    )

    return {
        "generated_files": generated,
        "current_step": step_code,
    }


def _classify_file_group(path: str) -> str:
    """Classify a file path into a group."""
    lower = path.lower()
    if any(ext in lower for ext in (".sql", "schema", "migration", "flyway")):
        return "database"
    if any(d in lower for d in ("dockerfile", "docker-compose", ".yml", ".yaml", "nginx", "script")):
        return "infra"
    if any(d in lower for d in ("readme", "deploy", "docs/", ".md")):
        return "docs"
    if any(d in lower for d in (".vue", ".tsx", ".jsx", "frontend/", "src/views", "src/components", "src/pages")):
        return "frontend"
    return "backend"


def _extract_error_files(build_log: str) -> list[str]:
    """Extract file paths mentioned in build error logs."""
    import re

    files = set()
    # Java pattern: /path/File.java:[line,col] error
    for m in re.finditer(r"(/\S+\.java):\[?\d+", build_log):
        files.add(m.group(1).lstrip("/app/"))
    # TypeScript/Vue pattern: file.ts(line,col): error
    for m in re.finditer(r"(\S+\.(?:ts|tsx|vue))\(\d+,\d+\)", build_log):
        files.add(m.group(1))
    # Generic: ERROR in ./path/file
    for m in re.finditer(r"ERROR in [./]*(\S+\.(?:ts|tsx|js|jsx|vue|java|py))", build_log):
        files.add(m.group(1))
    return list(files)


def _extract_errors_for_file(build_log: str, file_path: str) -> str:
    """Extract error lines relevant to a specific file."""
    lines = build_log.split("\n")
    relevant = []
    for i, line in enumerate(lines):
        if file_path in line:
            # Include context: 2 lines before and 5 lines after
            start = max(0, i - 2)
            end = min(len(lines), i + 6)
            relevant.extend(lines[start:end])
            relevant.append("---")
    return "\n".join(relevant[:50])  # limit output


async def _async_sleep(seconds: int) -> None:
    """Async sleep helper."""
    import asyncio
    await asyncio.sleep(seconds)


class _JavaApiClientFromState:
    """Helper to create JavaApiClient from graph state."""

    @staticmethod
    def from_state(state: Dict[str, Any]) -> JavaApiClient:
        from ..config import CallbackConfig
        timeout = int(os.getenv("DEEPAGENT_CALLBACK_TIMEOUT", "120"))
        config = CallbackConfig(
            base_url=state.get("callback_base_url", "http://localhost:8080"),
            api_key=state.get("callback_api_key", "smartark-internal"),
            timeout=timeout,
        )
        return JavaApiClient(config)


# Monkey-patch for convenience
JavaApiClient.from_state = staticmethod(_JavaApiClientFromState.from_state)
