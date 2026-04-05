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

        # Phase 6: Fixture mode — load pre-saved files, skip LLM entirely
        from ..config import DeepAgentConfig as _DACfg
        _fixture_cfg = _DACfg.from_env()
        if _fixture_cfg.fixture_mode and _fixture_cfg.fixture_dir:
            import json as _json
            from pathlib import Path as _Path
            fixture_path = _Path(_fixture_cfg.fixture_dir) / "generated_files.json"
            if fixture_path.exists():
                logger.info("requirement_analyze: FIXTURE MODE — loading from %s", fixture_path)
                with open(fixture_path, encoding="utf-8") as _f:
                    fixture_data = _json.load(_f)
                await client.update_step(task_id, "requirement_analyze", "finished",
                                         progress=100, output_summary="Loaded from fixture")
                return {
                    **memory_state,
                    "file_plan": fixture_data.get("file_plan", []),
                    "file_list": fixture_data.get("file_list", []),
                    "generated_files": fixture_data.get("generated_files", {}),
                    "template_key": fixture_data.get("template_key"),
                    "current_step": "requirement_analyze",
                }
            else:
                logger.warning("requirement_analyze: fixture file not found: %s", fixture_path)

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

        # Phase 6: materialize golden config files (bypass LLM)
        golden_files: Dict[str, str] = {}
        if _fixture_cfg.golden_config_enabled:
            prd_text = state.get("prd", "")
            project_slug = _slugify(prd_text[:50]) if prd_text else "app"
            display = prd_text[:30] if prd_text else "Application"
            golden_files = _materialize_golden_configs(
                file_plan,
                stack_backend=state.get("stack_backend", "springboot"),
                stack_frontend=state.get("stack_frontend", "vue3"),
                project_name=project_slug,
                display_name=display,
            )
        golden_count = len(golden_files)
        if golden_count:
            logger.info("requirement_analyze: materialized %d golden config files", golden_count)

        await client.update_step(
            task_id, "requirement_analyze", "finished",
            progress=100,
            output_summary=f"Generated file plan with {len(file_plan)} files ({golden_count} golden configs)",
        )

        # Phase 4: save checkpoint after node
        await _memory_bridge.after_node(
            state, "requirement_analyze",
            output_summary=f"Generated {len(file_plan)} files structure plan ({golden_count} golden)",
        )

        return {
            **memory_state,
            "file_plan": file_plan,
            "file_list": safe_files,
            "generated_files": golden_files,  # Phase 6: pre-populate with golden configs
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


async def sandbox_compile_check(state: Dict[str, Any]) -> Dict[str, Any]:
    """Fast syntax/type check before full build — catches errors early.

    Runs lightweight compile commands (tsc --noEmit, mvn compile) and fixes
    errors in-place using an internal loop. Uses its own round counter
    (compile_check_round) independent of build_fix_round.

    Phase 5: inserted between sandbox_init and build_verify.
    """
    check_cancelled(state)
    task_id = state["task_id"]
    sandbox = await get_sandbox(task_id)
    if sandbox is None:
        return {"compile_check_round": 0, "compile_check_log": "", "current_step": "compile_check"}

    from ..config import DeepAgentConfig
    cfg = DeepAgentConfig.from_env()

    client = JavaApiClient.from_state(state)
    await client.update_step(task_id, "compile_check", "running", progress=5)

    frontend_root, backend_root = await _detect_project_roots(sandbox, state)
    if frontend_root is None and backend_root is None:
        logger.info("compile_check: no recognizable project roots, skipping")
        await client.update_step(task_id, "compile_check", "finished", progress=8)
        return {"compile_check_round": 0, "compile_check_log": "No project roots found", "current_step": "compile_check"}

    max_rounds = cfg.max_compile_check_rounds
    timeout = cfg.compile_check_timeout
    compile_round = 0
    compile_log = ""

    # Pre-check: does the sandbox have tsc / mvn available?
    has_tsconfig = False
    has_mvn = False
    if frontend_root:
        has_tsconfig = await sandbox.file_exists(f"{frontend_root}/tsconfig.json")
    if backend_root:
        mvn_check = await sandbox.execute("which mvn 2>/dev/null", timeout=5)
        has_mvn = mvn_check.exit_code == 0

    if not has_tsconfig and not has_mvn:
        logger.info("compile_check: no tsconfig.json and no mvn, skipping")
        await client.update_step(task_id, "compile_check", "finished", progress=8)
        return {"compile_check_round": 0, "compile_check_log": "No compile tools applicable", "current_step": "compile_check"}

    # Ensure npm install for tsc (shared with build_verify)
    if has_tsconfig:
        has_node_modules = await sandbox.file_exists(f"{frontend_root}/node_modules/.package-lock.json")
        if not has_node_modules:
            install_result = await sandbox.execute(
                f"cd {frontend_root} && npm install --prefer-offline 2>&1",
                timeout=120,
            )
            if install_result.exit_code != 0:
                logger.warning("compile_check: npm install failed, skipping tsc")
                has_tsconfig = False
                compile_log = f"npm install failed (exit={install_result.exit_code})"

    compile_heal_applied = False  # guard: auto-heal runs at most once per compile_check

    while compile_round < max_rounds:
        check_cancelled(state)
        errors_found = False
        log_parts = []

        # --- Frontend: TypeScript type check ---
        if has_tsconfig:
            result = await sandbox.execute(
                f"cd {frontend_root} && npx tsc --noEmit 2>&1",
                timeout=timeout,
            )
            log_parts.append(f"=== tsc --noEmit (exit={result.exit_code}) ===\n{result.stdout}")
            if result.exit_code != 0:
                errors_found = True

        # --- Backend: Maven compile check ---
        if has_mvn:
            result = await sandbox.execute(
                f"cd {backend_root} && mvn -q compile 2>&1",
                timeout=timeout,
            )
            log_parts.append(f"=== mvn compile (exit={result.exit_code}) ===\n{result.stdout}")
            if result.exit_code != 0:
                errors_found = True

        compile_log = "\n\n".join(log_parts)

        if not errors_found:
            logger.info("compile_check: passed on round %d for task %s", compile_round, task_id)
            break

        # --- Phase 6: Auto-heal before LLM fix (once per loop to prevent infinite retry) ---
        if cfg.auto_heal_enabled and not compile_heal_applied:
            healed, heal_desc = await _auto_heal_build_errors(
                sandbox, compile_log, frontend_root if has_tsconfig else None,
                backend_root if has_mvn else None,
            )
            if healed:
                compile_heal_applied = True  # prevent re-entry on next loop
                logger.info("compile_check: auto-heal applied: %s", heal_desc)
                continue  # Re-run compile check with healed files

        # --- Fix error files via LLM ---
        compile_round += 1
        error_files = _extract_error_files(compile_log)
        if not error_files:
            logger.warning("compile_check: errors detected but no files extracted, skipping fix")
            break

        await client.log(task_id, "info", f"Compile check round {compile_round}: fixing {len(error_files[:5])} files")

        llm_client = LLMCodegenClient.from_state(state, node_name="compile_check")
        if llm_client is None:
            logger.warning("compile_check: no LLM client configured, skipping fix")
            break

        tech_stack = f"{state.get('stack_backend', '')} {state.get('stack_frontend', '')}"
        project_structure = "\n".join(state.get("file_list", []))

        for file_path in error_files[:5]:
            check_cancelled(state)
            try:
                current = await sandbox.read(f"/app/{file_path}")
            except Exception:
                logger.warning("compile_check: cannot read %s, skipping", file_path)
                continue

            file_errors = _extract_errors_for_file(compile_log, file_path)
            enriched_context = _context_compression.build_fix_context(
                state, file_path, file_errors,
            )
            fix_hint = _memory_bridge.build_fix_hint(state, file_errors)

            fix_instructions = (
                f"Fix the following compilation/type errors:\n{enriched_context}\n\n"
                f"Current file content:\n{current[:3000]}"
                f"{fix_hint}"
            )

            try:
                fixed_content = await _smart_retry.wrap_generate_file(
                    generate_fn=llm_client.generate_file_content,
                    file_path=file_path,
                    prd=state.get("prd", ""),
                    tech_stack=tech_stack,
                    project_structure=project_structure,
                    group=_classify_file_group(file_path),
                    instructions=fix_instructions,
                    quality_checker=_code_quality._check_quality,
                )
            except Exception as exc:
                logger.warning("compile_check: LLM fix failed for %s: %s", file_path, exc)
                continue

            if fixed_content and fixed_content.strip():
                await sandbox.write(f"/app/{file_path}", fixed_content)
                logger.info("compile_check: fixed %s (round %d)", file_path, compile_round)

    await client.update_step(task_id, "compile_check", "finished", progress=8,
                             output_summary=f"Compile check done: {compile_round} fix rounds")

    return {
        "compile_check_round": compile_round,
        "compile_check_log": compile_log[-2000:],
        "current_step": "compile_check",
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
        # Node.js project — skip npm install if node_modules already exists (e.g., from compile_check)
        has_node_modules = await sandbox.file_exists(f"{frontend_root}/node_modules/.package-lock.json")
        if has_node_modules:
            build_log_parts.append("=== npm install (skipped, node_modules already up-to-date) ===")
            install_ok = True
        else:
            result = await sandbox.execute(
                f"cd {frontend_root} && npm install --prefer-offline 2>&1",
                timeout=120,
            )
            build_log_parts.append(f"=== npm install (exit={result.exit_code}) ===\n{result.stdout}")
            install_ok = result.exit_code == 0

        if install_ok:
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

    # Phase 6: Auto-heal rule engine — deterministic fixes before LLM
    from ..config import DeepAgentConfig as _DAC
    _p6_cfg = _DAC.from_env()
    frontend_root, backend_root = await _detect_project_roots(sandbox, state)
    healed = False
    heal_desc = ""
    if _p6_cfg.auto_heal_enabled:
        healed, heal_desc = await _auto_heal_build_errors(
            sandbox, build_log, frontend_root, backend_root,
        )
    if healed:
        await client.log(task_id, "info", f"Auto-heal applied: {heal_desc}")
        # Re-run build to check if auto-heal resolved everything
        verify_result = await sandbox_build_verify(state)
        if verify_result.get("build_status") == "passed":
            await client.log(task_id, "info", "Auto-heal resolved all build errors, skipping LLM fix")
            await _memory_bridge.after_node(
                state, "build_fix",
                output_summary=f"Round {round_num}: auto-healed ({heal_desc})",
            )
            return {
                **verify_result,
                "build_fix_round": round_num,
                "fix_history": list(state.get("fix_history", [])),
                "current_step": "build_fix",
            }
        # Auto-heal partially helped, update build_log for LLM fix
        build_log = verify_result.get("build_log", build_log)
        await client.log(task_id, "info", "Auto-heal partial, proceeding to LLM fix")

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

        # Phase 6: precision patch — send full file content + error context,
        # instruct LLM to output the COMPLETE fixed file (not a diff) but
        # only modify the parts that caused errors.
        fix_instructions = (
            f"以下文件存在编译/构建错误，请修复。\n\n"
            f"--- 错误信息 ---\n{enriched_context}\n\n"
            f"--- 当前文件完整内容 ({file_path}) ---\n{current}\n\n"
            f"--- 修复要求 ---\n"
            f"1. 只修改导致错误的部分，不要改动其他正常代码\n"
            f"2. 保持原有代码风格、命名约定和导入结构\n"
            f"3. 输出修复后的完整文件内容（不要输出 diff 或 Markdown 代码块）\n"
            f"4. 如果错误是 import 缺失，只添加缺失的 import，不要重组所有 import\n"
            f"5. 如果错误是类型不匹配，只修改类型声明，不要重写业务逻辑\n"
            f"{fix_hint}"
            f"{round_hint}"
            f"{escalation}"
        )

        if llm_client is not None:
            try:
                fixed_content = await _smart_retry.wrap_generate_file(
                    generate_fn=llm_client.generate_file_content,
                    file_path=file_path,
                    prd=state.get("prd", "")[:1500],  # reduce PRD for fix (less noise)
                    tech_stack=tech_stack,
                    project_structure=project_structure,
                    group=_classify_file_group(file_path),
                    instructions=fix_instructions,
                )
            except Exception as exc:
                logger.warning("build_fix: direct LLM failed for %s, falling back to Java: %s", file_path, exc)
                fixed_content = await client.generate_file_content(
                    file_path=file_path,
                    prd=state.get("prd", "")[:1500],
                    tech_stack=tech_stack,
                    project_structure=project_structure,
                    group=_classify_file_group(file_path),
                    instructions=fix_instructions,
                )
        else:
            fixed_content = await client.generate_file_content(
                file_path=file_path,
                prd=state.get("prd", "")[:1500],
                tech_stack=tech_stack,
                project_structure=project_structure,
                group=_classify_file_group(file_path),
                instructions=fix_instructions,
            )

        if fixed_content and fixed_content.strip():
            await sandbox.write(f"/app/{file_path}", fixed_content)
            await client.log(task_id, "info", f"Fixed: {file_path}")

            fix_history.append({
                "file": file_path,
                "round": round_num,
                "error_summary": file_errors[:200],
                "fix_summary": f"Precision-patched {file_path}",
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

    # Package source code and register downloadable artifact.
    try:
        zip_b64 = await sandbox.export_app_zip_base64()
        artifact_info = await client.upload_artifact(
            task_id=task_id,
            artifact_type="zip",
            file_name=f"smartark_{task_id}.zip",
            content_base64=zip_b64,
        )
        await client.update_step(
            task_id,
            "package",
            "finished",
            progress=100,
            output_summary="Artifact packaged",
            output=artifact_info,
        )
    except Exception as exc:
        logger.warning("Artifact packaging/upload failed for %s: %s", task_id, exc)
        await client.update_step(
            task_id,
            "package",
            "running",
            progress=80,
            output_summary=f"Artifact packaging skipped: {exc}",
        )

    await client.update_step(
        task_id, "preview_deploy", "finished",
        progress=100,
        output_summary=f"Preview ready at {preview_url}",
        output={"host_port": host_port, "preview_url": preview_url},
    )

    # Phase 6: save fixture snapshot for future test runs
    from ..config import DeepAgentConfig as _DACfg2
    _fix_cfg = _DACfg2.from_env()
    if _fix_cfg.save_fixture:
        try:
            import json as _json2
            from pathlib import Path as _Path2
            fixture_dir = _Path2(_fix_cfg.fixture_dir or "/tmp/smartark/fixtures")
            fixture_dir.mkdir(parents=True, exist_ok=True)
            fixture = {
                "file_plan": state.get("file_plan", []),
                "file_list": state.get("file_list", []),
                "generated_files": state.get("generated_files", {}),
                "template_key": state.get("template_key"),
                "stack": {
                    "backend": state.get("stack_backend"),
                    "frontend": state.get("stack_frontend"),
                    "db": state.get("stack_db"),
                },
            }
            fixture_path = fixture_dir / f"{task_id}.json"
            with open(fixture_path, "w", encoding="utf-8") as _ff:
                _json2.dump(fixture, _ff, ensure_ascii=False)
            # Also save as latest for easy fixture_mode loading
            latest_path = fixture_dir / "generated_files.json"
            with open(latest_path, "w", encoding="utf-8") as _fl:
                _json2.dump(fixture, _fl, ensure_ascii=False)
            logger.info("Fixture saved: %s (+ latest symlink)", fixture_path)
        except Exception as exc:
            logger.warning("Fixture save failed (non-fatal): %s", exc)

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


# File generation priority for dependency ordering within a group.
# Lower number = generated first. Files not matching any pattern get 50.
_FILE_PRIORITY_PATTERNS = [
    # Database layer first (entities depend on schema)
    (10, [".sql", "migration", "flyway", "schema"]),
    # Entity / Model / DTO (everything depends on these)
    (20, ["entity", "model", "pojo", "dto", "vo", "domain", "bean"]),
    # Repository / DAO (services depend on these)
    (25, ["repository", "repo", "dao", "mapper"]),
    # Service (controllers depend on these)
    (30, ["service", "usecase", "handler"]),
    # Controller / API (depends on service)
    (35, ["controller", "resource", "endpoint", "api"]),
    # Config / Application (standalone)
    (40, ["config", "configuration", "application", "properties"]),
    # Frontend: store → composable → component → page → router
    (42, ["store", "pinia"]),
    (44, ["composable", "hook", "use"]),
    (46, ["component"]),
    (48, ["view", "page"]),
    (49, ["router", "route"]),
    # Tests last
    (60, ["test", "spec", "__test"]),
]


def _file_generation_priority(path: str) -> int:
    """Return generation priority for a file (lower = earlier)."""
    lower = path.lower()
    for priority, patterns in _FILE_PRIORITY_PATTERNS:
        if any(p in lower for p in patterns):
            return priority
    return 50


def _build_related_file_context(
    current_path: str,
    generated: Dict[str, str],
    max_chars: int = 2000,
) -> str:
    """Extract snippets from already-generated files that the current file likely depends on.

    E.g., when generating UserService.java, include UserEntity.java's class/field signatures.
    """
    if not generated:
        return ""

    current_lower = current_path.lower()
    # Infer the domain/entity name from current file
    base = current_path.split("/")[-1].rsplit(".", 1)[0].lower()
    # Strip common suffixes to get domain name: UserService → user, UserController → user
    for suffix in ("controller", "service", "repository", "repo", "dao", "mapper",
                    "page", "list", "form", "detail", "view", "test", "spec",
                    "request", "response", "dto", "vo"):
        if base.endswith(suffix) and len(base) > len(suffix):
            base = base[: -len(suffix)]
            break

    if len(base) < 2:
        return ""

    snippets = []
    chars = 0
    for gen_path, content in generated.items():
        if not content or gen_path == current_path:
            continue
        gen_lower = gen_path.lower()
        # Match by domain name (e.g., "user" matches UserEntity, UserRepository)
        if base not in gen_lower:
            continue
        # Extract first 40 lines (signatures, class definition, fields)
        lines = content.split("\n")[:40]
        snippet = "\n".join(lines)
        if chars + len(snippet) > max_chars:
            break
        snippets.append(f"// --- {gen_path} (前40行) ---\n{snippet}")
        chars += len(snippet)

    return "\n\n".join(snippets)


async def _generate_files_by_groups(
    state: Dict[str, Any],
    groups: set[str],
    step_code: str,
    metrics: Optional[NodeMetricsCollector] = None,
) -> Dict[str, Any]:
    """Generate code files for the specified groups.

    Phase 4: integrated with all middleware layers.
    Phase 6: files sorted by dependency priority (Entity → Service → Controller).
    """
    client = JavaApiClient.from_state(state)
    task_id = state["task_id"]

    # Phase 4: load memories before generation
    memory_state = await _memory_bridge.before_node(state, step_code)
    state = {**state, **memory_state}

    await client.update_step(task_id, step_code, "running", progress=10)

    file_plan = state.get("file_plan", [])
    target_files = [f for f in file_plan if f.get("group") in groups]

    # Phase 6: sort by dependency priority so earlier files can inform later ones
    target_files.sort(key=lambda f: _file_generation_priority(f.get("path", "")))

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
        # Do not return memory_state from parallel codegen branches.
        # Otherwise multiple branches concurrently write keys like
        # short_term_memories/long_term_memories/memory_context and trigger
        # LangGraph INVALID_CONCURRENT_GRAPH_UPDATE.
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

        # Phase 6: skip files already populated (golden config or template cache)
        existing = generated.get(path)
        if existing and existing.strip():
            logger.debug("%s: skipping %s (already populated, %d chars)", step_code, path, len(existing))
            return path, existing

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

            # Phase 6: inject already-generated file snippets for cross-file consistency
            related_snippets = _build_related_file_context(path, generated)
            if related_snippets:
                enhanced_instructions += f"\n\n--- 已生成的相关文件（请保持接口一致） ---\n{related_snippets}"

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

    # Phase 6: tiered generation — group files by priority tier, generate
    # each tier concurrently but tiers sequentially (Entity before Service
    # before Controller). This lets later files reference already-generated content.
    from itertools import groupby
    sorted_files = sorted(target_files, key=lambda f: _file_generation_priority(f.get("path", "")))
    tiers = []
    for _, group_iter in groupby(sorted_files, key=lambda f: _file_generation_priority(f.get("path", ""))):
        tiers.append(list(group_iter))

    success_count = 0
    fail_count = 0

    for tier in tiers:
        tasks = [_generate_one(f) for f in tier]
        results = await asyncio.gather(*tasks, return_exceptions=False)

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


# ======================================================================
# Golden config templates — verified content from template-repo
# ======================================================================


def _get_golden_vite_config() -> str:
    """Return verified vite.config.ts (from springboot-vue3-mysql template)."""
    return (
        "import { defineConfig } from 'vite'\n"
        "import vue from '@vitejs/plugin-vue'\n"
        "\n"
        "export default defineConfig({\n"
        "  plugins: [vue()],\n"
        "  server: {\n"
        "    host: '0.0.0.0',\n"
        "    port: 5173\n"
        "  },\n"
        "  test: {\n"
        "    environment: 'jsdom',\n"
        "    globals: true\n"
        "  }\n"
        "})\n"
    )


def _get_golden_tsconfig() -> str:
    """Return verified tsconfig.json (from springboot-vue3-mysql template)."""
    return (
        '{\n'
        '  "extends": "@vue/tsconfig/tsconfig.dom.json",\n'
        '  "compilerOptions": {\n'
        '    "tsBuildInfoFile": "./node_modules/.tmp/tsconfig.app.tsbuildinfo",\n'
        '    "types": ["vite/client"]\n'
        '  },\n'
        '  "include": ["src/**/*.ts", "src/**/*.tsx", "src/**/*.vue"]\n'
        '}\n'
    )


def _get_golden_package_json_scripts() -> dict:
    """Return verified scripts block for package.json."""
    return {
        "dev": "vite",
        "build": "vue-tsc -b && vite build",
        "preview": "vite preview",
    }


def _get_golden_package_json(project_name: str = "app") -> str:
    """Return verified package.json (from springboot-vue3-mysql template)."""
    import json as _json
    return _json.dumps({
        "name": f"{project_name}-frontend",
        "private": True,
        "version": "0.1.0",
        "type": "module",
        "scripts": {
            "dev": "vite",
            "build": "vue-tsc -b && vite build",
            "preview": "vite preview",
            "test": "vitest run",
            "test:watch": "vitest",
        },
        "dependencies": {
            "vue": "^3.5.13",
        },
        "devDependencies": {
            "@vitejs/plugin-vue": "^5.2.1",
            "@vue/test-utils": "^2.4.6",
            "@vue/tsconfig": "^0.7.0",
            "jsdom": "^25.0.1",
            "typescript": "^5.8.2",
            "vite": "^5.4.14",
            "vitest": "^2.1.8",
            "vue-tsc": "^2.2.8",
        },
    }, indent=2, ensure_ascii=False) + "\n"


def _get_golden_pom_xml(project_name: str = "app", display_name: str = "Application") -> str:
    """Return verified pom.xml (from springboot-vue3-mysql template)."""
    return f'''<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.4.4</version>
        <relativePath/>
    </parent>

    <groupId>com.smartark.template</groupId>
    <artifactId>{project_name}-backend</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>{display_name} Backend</name>

    <properties>
        <java.version>17</java.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-mysql</artifactId>
        </dependency>
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
'''


def _get_golden_frontend_dockerfile() -> str:
    """Return verified frontend Dockerfile."""
    return (
        "FROM node:20-alpine AS build\n"
        "WORKDIR /app\n"
        "\n"
        "COPY package.json .\n"
        "RUN npm install\n"
        "\n"
        "COPY . .\n"
        "RUN npm run build\n"
        "\n"
        "FROM nginx:1.27-alpine\n"
        "COPY --from=build /app/dist /usr/share/nginx/html\n"
        "\n"
        "EXPOSE 80\n"
    )


def _get_golden_backend_dockerfile() -> str:
    """Return verified backend Dockerfile."""
    return (
        "FROM maven:3.9.9-eclipse-temurin-17 AS build\n"
        "WORKDIR /workspace\n"
        "\n"
        "COPY pom.xml .\n"
        "COPY src ./src\n"
        "\n"
        "RUN mvn -q -DskipTests package\n"
        "\n"
        "FROM eclipse-temurin:17-jre\n"
        "WORKDIR /app\n"
        "\n"
        "COPY --from=build /workspace/target/*.jar app.jar\n"
        "\n"
        "EXPOSE 8080\n"
        "\n"
        'ENTRYPOINT ["java", "-jar", "app.jar"]\n'
    )


def _get_golden_docker_compose(project_name: str = "app") -> str:
    """Return verified docker-compose.yml (from springboot-vue3-mysql template)."""
    return f'''services:
  mysql:
    image: mysql:8.4
    container_name: {project_name}_mysql
    environment:
      MYSQL_DATABASE: ${{MYSQL_DATABASE:-app_db}}
      MYSQL_USER: ${{MYSQL_USER:-app}}
      MYSQL_PASSWORD: ${{MYSQL_PASSWORD:-app123456}}
      MYSQL_ROOT_PASSWORD: ${{MYSQL_ROOT_PASSWORD:-root123456}}
    ports:
      - "${{MYSQL_PORT:-3306}}:3306"
    volumes:
      - mysql_data:/var/lib/mysql
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]
      interval: 10s
      timeout: 5s
      retries: 10

  backend:
    build:
      context: ./backend
    environment:
      SERVER_PORT: ${{BACKEND_PORT:-8080}}
      DB_HOST: mysql
      DB_PORT: 3306
      DB_NAME: ${{MYSQL_DATABASE:-app_db}}
      DB_USER: ${{MYSQL_USER:-app}}
      DB_PASSWORD: ${{MYSQL_PASSWORD:-app123456}}
    depends_on:
      mysql:
        condition: service_healthy
    ports:
      - "${{BACKEND_PORT:-8080}}:8080"

  frontend:
    build:
      context: ./frontend
    depends_on:
      - backend
    ports:
      - "${{FRONTEND_PORT:-5173}}:80"

volumes:
  mysql_data:
'''


def _get_golden_gitignore() -> str:
    """Return a standard .gitignore."""
    return (
        "node_modules/\ndist/\n.vite/\n*.log\n.env\n.env.local\n"
        "target/\n*.class\n*.jar\n.idea/\n*.iml\n.DS_Store\n"
    )


# Golden config file pattern matching
_GOLDEN_CONFIG_PATTERNS = {
    "package.json", "pom.xml", "tsconfig.json", "vite.config.ts",
    "vite.config.js", "dockerfile", "docker-compose.yml",
    "docker-compose.yaml", ".gitignore",
}


def _is_golden_config(path: str) -> bool:
    """Check if a file should use golden template instead of LLM generation."""
    base = path.split("/")[-1].lower()
    return any(p in base for p in _GOLDEN_CONFIG_PATTERNS)


def _slugify(text: str) -> str:
    """Convert text to a URL-safe slug for project names."""
    import re as _re
    slug = _re.sub(r"[^a-z0-9\u4e00-\u9fff]+", "-", text.lower().strip())
    return slug.strip("-")[:30] or "app"


def _materialize_golden_configs(
    file_plan: List[Dict[str, Any]],
    stack_backend: str,
    stack_frontend: str,
    project_name: str,
    display_name: str,
) -> Dict[str, str]:
    """Generate golden config files from hardcoded templates.

    Returns dict of {path: content} for config files that should bypass LLM.
    """
    golden: Dict[str, str] = {}

    for item in file_plan:
        path = item.get("path", "")
        if not _is_golden_config(path):
            continue

        lower = path.lower()
        base = path.split("/")[-1].lower()

        # Match by filename
        if base == "package.json" and "vue" in stack_frontend.lower():
            golden[path] = _get_golden_package_json(project_name)
        elif base == "pom.xml" and "spring" in stack_backend.lower():
            golden[path] = _get_golden_pom_xml(project_name, display_name)
        elif base == "tsconfig.json" and ("vue" in stack_frontend.lower() or "react" in stack_frontend.lower()):
            golden[path] = _get_golden_tsconfig()
        elif base in ("vite.config.ts", "vite.config.js") and "vue" in stack_frontend.lower():
            golden[path] = _get_golden_vite_config()
        elif base == "dockerfile":
            if "frontend" in lower or "web" in lower or "client" in lower:
                golden[path] = _get_golden_frontend_dockerfile()
            elif "backend" in lower or "server" in lower:
                golden[path] = _get_golden_backend_dockerfile()
        elif base in ("docker-compose.yml", "docker-compose.yaml"):
            golden[path] = _get_golden_docker_compose(project_name)
        elif base == ".gitignore":
            golden[path] = _get_golden_gitignore()

    return golden


# ======================================================================
# Auto-heal rule engine — deterministic fixes before LLM
# ======================================================================


import re as _re_module  # module-level re already imported, alias for clarity


async def _auto_heal_build_errors(
    sandbox,
    build_log: str,
    frontend_root: Optional[str],
    backend_root: Optional[str],
) -> tuple:
    """Apply deterministic fixes for common build errors before LLM.

    Returns (any_fixes_applied: bool, description: str).
    """
    fixes = []

    # --- npm / Node.js rules ---
    if frontend_root:
        # Rule 1: ERESOLVE peer dependency conflict
        if "ERESOLVE" in build_log or "peer dep" in build_log.lower():
            await sandbox.execute(
                f"cd {frontend_root} && npm install --legacy-peer-deps 2>&1",
                timeout=120,
            )
            fixes.append("npm: --legacy-peer-deps")

        # Rule 2: Package version not found (404 / ETARGET)
        for m in _re_module.finditer(
            r"(?:404\s+Not Found|No matching version found for)\s*['\"]?([@\w/-]+)@([^\s'\"]+)",
            build_log,
        ):
            pkg = m.group(1)
            await sandbox.execute(
                f"cd {frontend_root} && npm pkg delete dependencies.{pkg} "
                f"&& npm pkg delete devDependencies.{pkg}",
                timeout=20,
            )
            fixes.append(f"npm: removed {pkg} (version not found)")

        # Rule 3: Cannot find module (missing dependency)
        seen_modules: set = set()
        for m in _re_module.finditer(r"Cannot find module '([@\w/-]+)'", build_log):
            mod = m.group(1)
            if mod.startswith(".") or mod.startswith("/") or mod in seen_modules:
                continue
            seen_modules.add(mod)
            await sandbox.execute(
                f"cd {frontend_root} && npm install {mod} --save 2>&1",
                timeout=60,
            )
            fixes.append(f"npm: installed {mod}")

        # Rule 4: vite.config load failure → restore golden
        if "failed to load config from" in build_log.lower():
            golden = _get_golden_vite_config()
            vite_path = f"{frontend_root}/vite.config.ts"
            await sandbox.write(vite_path, golden)
            fixes.append("vite: restored golden vite.config.ts")

        # Rule 5: tsconfig parse error → restore golden
        if (
            "error TS5024" in build_log
            or "error TS6046" in build_log
            or ("tsconfig" in build_log.lower() and "parse error" in build_log.lower())
        ):
            golden = _get_golden_tsconfig()
            await sandbox.write(f"{frontend_root}/tsconfig.json", golden)
            fixes.append("tsconfig: restored golden tsconfig.json")

        # Rule 6: Missing build script
        if "missing script: build" in build_log.lower() or "'build' is not found" in build_log:
            scripts = _get_golden_package_json_scripts()
            for key, val in scripts.items():
                await sandbox.execute(
                    f'cd {frontend_root} && npm pkg set scripts.{key}="{val}"',
                    timeout=10,
                )
            fixes.append("npm: injected missing build/dev scripts")

        # Rule 9 (extended): @types/xxx version not found
        for m in _re_module.finditer(
            r"No matching version found for (@types/[\w-]+)@", build_log,
        ):
            pkg = m.group(1)
            await sandbox.execute(
                f"cd {frontend_root} && npm pkg delete devDependencies.{pkg} "
                f"&& npm pkg delete dependencies.{pkg}",
                timeout=20,
            )
            fixes.append(f"npm: removed {pkg} (version mismatch)")

    # --- Maven / Java rules ---
    if backend_root:
        # Rule 7: POM XML parse error (log only in Phase 1)
        if "Non-parseable POM" in build_log or "Malformed POM" in build_log:
            logger.warning("auto_heal: POM XML parse error detected, manual fix required")
            # Phase 1: don't auto-restore pom.xml (too project-specific)

        # Rule 8: javax → jakarta migration
        if "package javax." in build_log and "does not exist" in build_log:
            error_files = _extract_error_files(build_log)
            for fp in error_files[:10]:
                if not fp.endswith(".java"):
                    continue
                try:
                    content = await sandbox.read(f"/app/{fp}")
                    original = content
                    content = content.replace("import javax.persistence", "import jakarta.persistence")
                    content = content.replace("import javax.validation", "import jakarta.validation")
                    content = content.replace("import javax.servlet", "import jakarta.servlet")
                    content = content.replace("import javax.annotation", "import jakarta.annotation")
                    content = content.replace("import javax.transaction", "import jakarta.transaction")
                    if content != original:
                        await sandbox.write(f"/app/{fp}", content)
                        fixes.append(f"java: javax→jakarta in {fp}")
                except Exception:
                    pass

    return len(fixes) > 0, "; ".join(fixes) if fixes else ""


def _extract_error_files(build_log: str) -> list[str]:
    """Extract file paths mentioned in build error logs."""
    import re

    def _normalize(path: str) -> str:
        p = path.strip().strip("'\"").replace("\\", "/")
        p = re.sub(r"^file://", "", p)
        if p.startswith("/app/"):
            p = p[len("/app/"):]
        return p.lstrip("./")

    files: list[str] = []
    seen: set[str] = set()

    def _add(path: str) -> None:
        normalized = _normalize(path)
        if normalized and normalized not in seen:
            seen.add(normalized)
            files.append(normalized)

    # Java pattern: /path/File.java:[line,col] error
    for m in re.finditer(r"(/\S+\.java):\[?\d+", build_log):
        _add(m.group(1))
    # TypeScript/Vue pattern: file.ts(line,col): error
    for m in re.finditer(r"(\S+\.(?:ts|tsx|vue))\(\d+,\d+\)", build_log):
        _add(m.group(1))
    # Generic: ERROR in ./path/file
    for m in re.finditer(r"ERROR in [./]*(\S+\.(?:ts|tsx|js|jsx|vue|java|py))", build_log):
        _add(m.group(1))
    # Vite/Node style: failed to load config from /app/frontend/vite.config.js
    for m in re.finditer(
        r"failed to load config from\s+(\S+\.(?:js|cjs|mjs|ts|cts|mts))",
        build_log,
        flags=re.IGNORECASE,
    ):
        _add(m.group(1))
    # Generic absolute project file path fallback.
    for m in re.finditer(
        r"(/app/\S+\.(?:ts|tsx|js|jsx|vue|json|mjs|cjs|mts|cts|java|py))",
        build_log,
    ):
        _add(m.group(1))
    return files


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
