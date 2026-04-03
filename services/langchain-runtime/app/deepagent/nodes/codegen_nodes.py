"""Node functions for the code generation StateGraph.

Each node receives the full CodegenState dict and returns a partial
dict update.  Nodes use tools (Java API callbacks + sandbox execute)
to perform their work.

Phase 4: integrated with MemoryBridge, SmartRetry, DynamicPrompt,
ContextCompression, and AdaptiveModelSwitch middleware.
"""

from __future__ import annotations

import asyncio
import logging
import os
from typing import Any, Dict, List, Optional

from ..cancellation import check_cancelled
from ..middleware.memory_bridge import MemoryBridgeMiddleware
from ..middleware.smart_retry import SmartRetryMiddleware
from ..middleware.dynamic_prompt import DynamicPromptMiddleware
from ..middleware.context_compression import ContextCompressionMiddleware
from ..middleware.adaptive_model_switch import AdaptiveModelSwitchMiddleware
from ..middleware.code_quality import CodeQualityMiddleware
from ..sandbox.sandbox_factory import create_sandbox, get_sandbox, mark_for_preview
from ..tools.java_api_client import JavaApiClient
from ..tools.llm_codegen_client import LLMCodegenClient
from ..tools.node_metrics_collector import NodeMetricsCollector

logger = logging.getLogger(__name__)

# --- Middleware singletons (initialized once per process) ---
_memory_bridge = MemoryBridgeMiddleware()
_smart_retry = SmartRetryMiddleware()
_dynamic_prompt = DynamicPromptMiddleware()
_context_compression = ContextCompressionMiddleware()
_model_switch = AdaptiveModelSwitchMiddleware()
_code_quality = CodeQualityMiddleware()


# ======================================================================
# Front-chain nodes (requirement → codegen)
# ======================================================================


async def requirement_analyze(state: Dict[str, Any]) -> Dict[str, Any]:
    """Analyze PRD and produce a file plan.

    Calls Java ModelService to generate project structure, then sanitizes
    and validates the file list.
    """
    check_cancelled(state)
    async with NodeMetricsCollector(state, "requirement_analyze") as metrics:
        client = JavaApiClient.from_state(state)
        task_id = state["task_id"]

        # Phase 4: load memories before node execution
        memory_state = await _memory_bridge.before_node(state, "requirement_analyze")
        state = {**state, **memory_state}

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

        # Generate project structure — prefer direct LLM, fall back to Java
        llm_client = LLMCodegenClient.from_state(state, node_name="requirement_analyze")
        if llm_client is not None:
            logger.info("requirement_analyze: using direct LLM for project structure")
            # Phase 4: smart retry for structure generation
            structure = await _smart_retry.wrap_generate_structure(
                generate_fn=llm_client.generate_project_structure,
                prd=state.get("prd", ""),
                stack=stack,
                instructions=state.get("instructions", ""),
            )
        else:
            structure = await client.generate_project_structure(
                prd=state.get("prd", ""),
                stack=stack,
                instructions=state.get("instructions", ""),
            )
        metrics.record_model_call()  # one LLM call for structure generation

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

        # Phase 4: save checkpoint after node
        await _memory_bridge.after_node(
            state, "requirement_analyze",
            output_summary=f"Generated {len(file_plan)} files structure plan",
        )

        return {
            **memory_state,
            "file_plan": file_plan,
            "file_list": safe_files,
            "template_key": template_info.get("template_key"),
            "current_step": "requirement_analyze",
        }


async def sql_generate(state: Dict[str, Any]) -> Dict[str, Any]:
    """Generate database schema and infrastructure files."""
    check_cancelled(state)
    async with NodeMetricsCollector(state, "sql_generate") as metrics:
        return await _generate_files_by_groups(
            state, groups={"database", "infra", "docs"}, step_code="sql_generate", metrics=metrics
        )


async def codegen_backend(state: Dict[str, Any]) -> Dict[str, Any]:
    """Generate backend source code files."""
    check_cancelled(state)
    async with NodeMetricsCollector(state, "codegen_backend") as metrics:
        return await _generate_files_by_groups(
            state, groups={"backend"}, step_code="codegen_backend", metrics=metrics
        )


async def codegen_frontend(state: Dict[str, Any]) -> Dict[str, Any]:
    """Generate frontend source code files."""
    check_cancelled(state)
    async with NodeMetricsCollector(state, "codegen_frontend") as metrics:
        return await _generate_files_by_groups(
            state, groups={"frontend"}, step_code="codegen_frontend", metrics=metrics
        )


# ======================================================================
# Sandbox back-chain nodes (build → test → preview)
# ======================================================================


async def sandbox_init(state: Dict[str, Any]) -> Dict[str, Any]:
    """Initialize the sandbox container and write all generated files into it."""
    check_cancelled(state)
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
    """Run build commands inside the sandbox and capture results."""
    check_cancelled(state)
    task_id = state["task_id"]
    sandbox = await get_sandbox(task_id)
    if sandbox is None:
        return {"build_status": "failed", "build_log": "Sandbox not found"}

    client = JavaApiClient.from_state(state)
    await client.update_step(task_id, "build_verify", "running", progress=10)

    # Detect project roots and run build in the correct sub-directory.
    frontend_root, backend_root = await _detect_project_roots(sandbox, state)
    has_package_json = frontend_root is not None
    has_pom = backend_root is not None

    build_log_parts = []

    if has_package_json:
        # Node.js project
        result = await sandbox.execute(
            f"cd {frontend_root} && npm install --prefer-offline 2>&1",
            timeout=120,
        )
        build_log_parts.append(f"=== npm install (exit={result.exit_code}) ===\n{result.stdout}")

        if result.exit_code == 0:
            result = await sandbox.execute(
                f"cd {frontend_root} && npm run build 2>&1",
                timeout=180,
            )
            build_log_parts.append(f"=== npm run build (exit={result.exit_code}) ===\n{result.stdout}")

    elif has_pom:
        # Maven project
        result = await sandbox.execute(
            f"cd {backend_root} && mvn -q -DskipTests package 2>&1", timeout=300
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

    Phase 4 enhancements:
    - Memory bridge: load fix history, check long-term patterns for known fixes
    - Dynamic prompt: progressive fix instructions by round number
    - Context compression: enriched error context with related file snippets
    - Smart retry: semantic retry on empty/low-quality fix output
    - Save successful fix patterns to long-term memory
    """
    check_cancelled(state)
    task_id = state["task_id"]
    sandbox = await get_sandbox(task_id)
    if sandbox is None:
        return {"build_status": "failed", "build_log": "Sandbox not found for fix"}

    client = JavaApiClient.from_state(state)
    build_log = state.get("build_log", "")
    round_num = state.get("build_fix_round", 0) + 1

    # Phase 4: load memories before fix
    memory_state = await _memory_bridge.before_node(state, "build_fix")
    state = {**state, **memory_state}

    await client.log(task_id, "info", f"Build fix attempt {round_num}")

    # Fast-path: auto-heal known npm ETARGET issue before LLM-based file fixing.
    frontend_root, _ = await _detect_project_roots(sandbox, state)
    if frontend_root and "No matching version found for @types/date-fns@" in build_log:
        await client.log(task_id, "warn", "Auto-fix npm ETARGET: removing @types/date-fns from package.json")
        await sandbox.execute(
            f"cd {frontend_root} && npm pkg delete devDependencies.@types/date-fns && npm pkg delete dependencies.@types/date-fns",
            timeout=20,
        )

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
    llm_client = LLMCodegenClient.from_state(state, node_name="build_fix")
    tech_stack = f"{state.get('stack_backend', '')} {state.get('stack_frontend', '')}"
    project_structure = "\n".join(state.get("file_list", []))
    fix_history = list(state.get("fix_history", []))

    for file_path in error_files[:5]:  # limit to 5 files per round
        check_cancelled(state)
        current = await sandbox.read(f"/app/{file_path}")

        # Phase 4: enrich error context with compression middleware
        file_errors = _extract_errors_for_file(build_log, file_path)
        enriched_context = _context_compression.build_fix_context(
            state, file_path, file_errors,
        )

        # Phase 4: check long-term memory for proven fix patterns
        fix_hint = _memory_bridge.build_fix_hint(state, file_errors)

        # Phase 4: progressive fix instructions by round
        round_hint = _dynamic_prompt.enhance_instructions(
            base_instructions="",
            state=state,
            file_path=file_path,
            step_code="build_fix",
        )

        # Phase 4: escalation hint from smart retry
        escalation = _smart_retry.get_escalation_hint(file_path, round_num)

        fix_instructions = (
            f"Fix the following build errors:\n{enriched_context}\n\n"
            f"Current file content:\n{current[:3000]}"
            f"{fix_hint}"
            f"{round_hint}"
            f"{escalation}"
        )

        if llm_client is not None:
            try:
                # Phase 4: smart retry wrapping
                fixed_content = await _smart_retry.wrap_generate_file(
                    generate_fn=llm_client.generate_file_content,
                    file_path=file_path,
                    prd=state.get("prd", ""),
                    tech_stack=tech_stack,
                    project_structure=project_structure,
                    group=_classify_file_group(file_path),
                    instructions=fix_instructions,
                )
            except Exception as exc:
                logger.warning("build_fix: direct LLM failed for %s, falling back to Java: %s", file_path, exc)
                fixed_content = await client.generate_file_content(
                    file_path=file_path,
                    prd=state.get("prd", ""),
                    tech_stack=tech_stack,
                    project_structure=project_structure,
                    group=_classify_file_group(file_path),
                    instructions=fix_instructions,
                )
        else:
            fixed_content = await client.generate_file_content(
                file_path=file_path,
                prd=state.get("prd", ""),
                tech_stack=tech_stack,
                project_structure=project_structure,
                group=_classify_file_group(file_path),
                instructions=fix_instructions,
            )

        if fixed_content and fixed_content.strip():
            await sandbox.write(f"/app/{file_path}", fixed_content)
            await client.log(task_id, "info", f"Fixed: {file_path}")

            # Phase 4: record fix history for rolling window
            fix_history.append({
                "file": file_path,
                "round": round_num,
                "error_summary": file_errors[:200],
                "fix_summary": f"Regenerated {file_path} with fix instructions",
            })

    # Re-run build to verify fix
    verify_result = await sandbox_build_verify(state)

    # Phase 4: save fix patterns based on result
    build_passed = verify_result.get("build_status") == "passed"
    for file_path in error_files[:5]:
        file_errors = _extract_errors_for_file(build_log, file_path)
        await _memory_bridge.save_fix_pattern(
            state,
            error_pattern=file_errors[:200],
            fix_action=f"round_{round_num}_regenerated",
            file_path=file_path,
            success=build_passed,
        )

    # Phase 4: save checkpoint
    await _memory_bridge.after_node(
        state, "build_fix",
        output_summary=f"Round {round_num}: {'passed' if build_passed else 'failed'}, fixed {len(error_files)} files",
        failure_reason="" if build_passed else verify_result.get("build_log", "")[:300],
        fixed_actions=[f"fixed:{f}" for f in error_files[:5]],
    )

    result = {
        **verify_result,
        "build_fix_round": round_num,
        "fix_history": fix_history,
        "current_step": "build_fix",
    }
    # When invoked from the smoke_fix path (build already passed, smoke failed),
    # also increment smoke_fix_round so should_fix_smoke() can enforce its limit.
    if state.get("build_status") == "passed":
        result["smoke_fix_round"] = state.get("smoke_fix_round", 0) + 1
    return result


async def sandbox_smoke_test(state: Dict[str, Any]) -> Dict[str, Any]:
    """Start the dev server in sandbox and run smoke tests."""
    check_cancelled(state)
    task_id = state["task_id"]
    sandbox = await get_sandbox(task_id)
    if sandbox is None:
        return {"smoke_status": "failed", "smoke_log": "Sandbox not found"}

    client = JavaApiClient.from_state(state)
    await client.update_step(task_id, "smoke_test", "running", progress=10)

    frontend_root, _ = await _detect_project_roots(sandbox, state)
    if frontend_root is None:
        smoke_log = "No frontend package.json found. Cannot start dev server."
        await client.update_step(
            task_id, "smoke_test", "running", progress=60, output_summary="Smoke test failed"
        )
        return {"smoke_status": "failed", "smoke_log": smoke_log, "current_step": "smoke_test"}

    # Start dev server in background from detected frontend root.
    await sandbox.execute(
        f"cd {frontend_root} && npm run dev -- --host 0.0.0.0 > /tmp/dev-server.log 2>&1 &",
        timeout=10,
    )

    # Poll for health
    healthy = False
    smoke_log_parts = []

    for attempt in range(20):
        check_cancelled(state)
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
    """Register the sandbox as a preview environment."""
    check_cancelled(state)
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
    from ..config import DeepAgentConfig
    cfg = DeepAgentConfig.from_env()
    file_count = len(state.get("file_plan", []))
    max_build, _, max_total = cfg.fix_limits(file_count)
    build_rounds = state.get("build_fix_round", 0)
    total = build_rounds + state.get("smoke_fix_round", 0)
    if build_rounds >= max_build or total >= max_total:
        logger.info("Build fix limit reached (build=%d, total=%d, tier=%s), skipping to smoke_test",
                     build_rounds, total, "complex" if file_count > cfg.complexity_file_threshold else "simple")
        return "smoke_test"  # give up fixing, try smoke test anyway
    return "build_fix"


def should_fix_smoke(state: Dict[str, Any]) -> str:
    """Route after smoke_test: retry fix or proceed to preview."""
    if state.get("smoke_status") == "passed":
        return "preview_deploy"
    from ..config import DeepAgentConfig
    cfg = DeepAgentConfig.from_env()
    file_count = len(state.get("file_plan", []))
    _, max_smoke, max_total = cfg.fix_limits(file_count)
    smoke_rounds = state.get("smoke_fix_round", 0)
    total = state.get("build_fix_round", 0) + smoke_rounds
    if smoke_rounds >= max_smoke or total >= max_total:
        logger.info("Smoke fix limit reached (smoke=%d, total=%d, tier=%s), skipping to preview_deploy",
                     smoke_rounds, total, "complex" if file_count > cfg.complexity_file_threshold else "simple")
        return "preview_deploy"  # deploy anyway with degraded status
    return "build_fix"  # try fixing build issues that cause runtime failure


# ======================================================================
# Internal helpers
# ======================================================================


async def _detect_project_roots(sandbox, state: Dict[str, Any]) -> tuple[Optional[str], Optional[str]]:
    """Detect frontend/backend roots in sandbox from file_list and common fallbacks."""
    file_list: List[str] = state.get("file_list", []) or []
    frontend_candidates: List[str] = []
    backend_candidates: List[str] = []

    for p in file_list:
        if p.lower().endswith("package.json"):
            d = p.rsplit("/", 1)[0] if "/" in p else ""
            frontend_candidates.append(f"/app/{d}" if d else "/app")
        if p.lower().endswith("pom.xml"):
            d = p.rsplit("/", 1)[0] if "/" in p else ""
            backend_candidates.append(f"/app/{d}" if d else "/app")

    # Common conventions fallback
    frontend_candidates.extend(["/app", "/app/frontend", "/app/web", "/app/client"])
    backend_candidates.extend(["/app", "/app/backend"])

    # Deduplicate while preserving order
    frontend_candidates = list(dict.fromkeys(frontend_candidates))
    backend_candidates = list(dict.fromkeys(backend_candidates))

    frontend_root = None
    for c in frontend_candidates:
        if await sandbox.file_exists(f"{c}/package.json"):
            frontend_root = c
            break

    backend_root = None
    for c in backend_candidates:
        if await sandbox.file_exists(f"{c}/pom.xml"):
            backend_root = c
            break

    return frontend_root, backend_root


async def _generate_files_by_groups(
    state: Dict[str, Any],
    groups: set[str],
    step_code: str,
    metrics: Optional[NodeMetricsCollector] = None,
) -> Dict[str, Any]:
    """Generate code files for the specified groups.

    Phase 4: integrated with all middleware layers.
    """
    client = JavaApiClient.from_state(state)
    task_id = state["task_id"]

    # Phase 4: load memories before generation
    memory_state = await _memory_bridge.before_node(state, step_code)
    state = {**state, **memory_state}

    await client.update_step(task_id, step_code, "running", progress=10)

    file_plan = state.get("file_plan", [])
    target_files = [f for f in file_plan if f.get("group") in groups]

    generated = dict(state.get("generated_files", {}))
    tech_stack = f"{state.get('stack_backend', '')} {state.get('stack_frontend', '')} {state.get('stack_db', '')}"
    project_structure = "\n".join(state.get("file_list", []))
    prd = state.get("prd", "")
    instructions = state.get("instructions", "")

    llm_client = LLMCodegenClient.from_state(state, node_name=step_code)

    if llm_client is not None:
        # Concurrent generation via direct LLM calls
        logger.info("%s: generating %d files concurrently via direct LLM (model=%s)", step_code, len(target_files), llm_client._model)
        success_count, fail_count = await _generate_files_concurrent(
            llm_client, target_files, prd, tech_stack, project_structure,
            instructions, generated, task_id, step_code, client, metrics=metrics,
            state=state,
        )
    else:
        # Serial fallback via Java model proxy
        logger.info("%s: generating %d files serially via Java proxy", step_code, len(target_files))
        success_count, fail_count = await _generate_files_serial(
            client, target_files, prd, tech_stack, project_structure,
            instructions, generated, task_id, step_code, metrics=metrics,
            state=state,
        )

    await client.update_step(
        task_id, step_code, "finished",
        progress=100,
        output_summary=f"Generated {success_count} files, {fail_count} failures",
    )

    # Phase 4: save checkpoint after generation
    await _memory_bridge.after_node(
        state, step_code,
        output_summary=f"Generated {success_count}/{len(target_files)} files for {', '.join(groups)}",
    )

    return {
        **memory_state,
        "generated_files": generated,
    }


async def _generate_files_concurrent(
    llm_client: LLMCodegenClient,
    target_files: List[Dict[str, Any]],
    prd: str,
    tech_stack: str,
    project_structure: str,
    instructions: str,
    generated: Dict[str, str],
    task_id: str,
    step_code: str,
    callback_client: JavaApiClient,
    metrics: Optional[NodeMetricsCollector] = None,
    state: Optional[Dict[str, Any]] = None,
) -> tuple[int, int]:
    """Generate all files in a group concurrently with middleware integration."""
    _state = state or {}

    async def _generate_one(file_item: Dict[str, Any]) -> tuple[str, Optional[str]]:
        path = file_item["path"]
        group = file_item["group"]
        try:
            # Phase 4: compress PRD with relevance to this file
            compressed_prd = _context_compression.compress_prd(prd, file_path=path)

            # Phase 4: compress project structure with group focus
            compressed_structure = _context_compression.compress_project_structure(
                _state.get("file_list", []),
                current_group=group,
                current_file=path,
            )

            # Phase 4: extract cross-file dependency signatures
            deps = _context_compression.extract_cross_file_deps(path, _state)

            # Phase 4: enhance instructions with dynamic prompt
            enhanced_instructions = _dynamic_prompt.enhance_instructions(
                base_instructions=instructions,
                state=_state,
                file_path=path,
                step_code=step_code,
            )

            # Phase 4: append dependency context
            if deps:
                enhanced_instructions += f"\n\n{deps}"

            # Phase 4: smart retry wrapping with quality check
            content = await _smart_retry.wrap_generate_file(
                generate_fn=llm_client.generate_file_content,
                file_path=path,
                prd=compressed_prd,
                tech_stack=tech_stack,
                project_structure=compressed_structure,
                group=group,
                instructions=enhanced_instructions,
                quality_checker=_code_quality._check_quality,
            )
            return path, content if (content and content.strip()) else None
        except asyncio.TimeoutError:
            logger.warning("%s: timeout generating %s (degrade)", step_code, path)
            await callback_client.log(task_id, "warn", f"file_timeout: {path}")
            return path, None
        except Exception as exc:
            logger.warning("%s: error generating %s: %s", step_code, path, exc)
            return path, None

    tasks = [_generate_one(f) for f in target_files]
    results = await asyncio.gather(*tasks, return_exceptions=False)

    success_count = 0
    fail_count = 0
    for path, content in results:
        if content is not None:
            generated[path] = content
            success_count += 1
            if metrics:
                metrics.record_subtask(path, success=True)
                metrics.record_model_call()
        else:
            generated[path] = ""  # placeholder so contract_validate can detect missing
            fail_count += 1
            if metrics:
                metrics.record_subtask(path, success=False)
                metrics.mark_degrade(f"file empty/timeout: {path}")

    # Single progress update after all concurrent tasks complete
    await callback_client.update_step(task_id, step_code, "running", progress=90)
    return success_count, fail_count


async def _generate_files_serial(
    client: JavaApiClient,
    target_files: List[Dict[str, Any]],
    prd: str,
    tech_stack: str,
    project_structure: str,
    instructions: str,
    generated: Dict[str, str],
    task_id: str,
    step_code: str,
    metrics: Optional[NodeMetricsCollector] = None,
    state: Optional[Dict[str, Any]] = None,
) -> tuple[int, int]:
    """Serial fallback: generate files one-by-one via Java proxy with middleware."""
    _state = state or {}
    success_count = 0
    fail_count = 0

    for i, file_item in enumerate(target_files):
        path = file_item["path"]
        group = file_item["group"]
        try:
            # Phase 4: enhance instructions
            enhanced_instructions = _dynamic_prompt.enhance_instructions(
                base_instructions=instructions,
                state=_state,
                file_path=path,
                step_code=step_code,
            )

            # Phase 4: compress PRD
            compressed_prd = _context_compression.compress_prd(prd, file_path=path)

            content = await client.generate_file_content(
                file_path=path,
                prd=compressed_prd,
                tech_stack=tech_stack,
                project_structure=project_structure,
                group=group,
                instructions=enhanced_instructions,
            )
            if content and content.strip():
                generated[path] = content
                success_count += 1
                if metrics:
                    metrics.record_subtask(path, success=True)
                    metrics.record_model_call()
            else:
                fail_count += 1
                if metrics:
                    metrics.record_subtask(path, success=False)
        except Exception as exc:
            logger.warning("Failed to generate %s: %s", path, exc)
            fail_count += 1
            if metrics:
                metrics.record_subtask(path, success=False)

        progress = int(10 + 80 * (i + 1) / max(len(target_files), 1))
        await client.update_step(task_id, step_code, "running", progress=progress)

    return success_count, fail_count


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


    # Note: JavaApiClient.from_state() is now a proper classmethod
    # defined in java_api_client.py (Phase 4 cleanup).
