"""FastAPI router for DeepAgent endpoints.

Exposes:
    POST /v1/agent/codegen/run  — Start code generation pipeline
    POST /v1/agent/paper/run    — Start paper generation pipeline
    GET  /v1/agent/status/{run_id} — Check run status (future)
"""

from __future__ import annotations

import asyncio
import logging
import uuid
from typing import Dict

from fastapi import APIRouter, BackgroundTasks, HTTPException

from ..cancellation import (
    TaskCancelled,
    cancel_by_task_id,
    check_cancelled,
    register_token,
    remove_token,
)
from ..config import DeepAgentConfig
from ..graphs.codegen_graph import build_codegen_graph
from ..graphs.paper_graph import build_paper_graph
from ..sandbox.sandbox_factory import destroy_sandbox
from ..schemas import CodegenRunRequest, CodegenRunResponse, PaperRunRequest, PaperRunResponse

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/v1/agent", tags=["deepagent"])

# In-memory run status tracker
_run_status: Dict[str, Dict] = {}


# ------------------------------------------------------------------
# Code generation
# ------------------------------------------------------------------


@router.post("/codegen/run", response_model=CodegenRunResponse)
async def run_codegen(
    request: CodegenRunRequest,
    background_tasks: BackgroundTasks,
) -> CodegenRunResponse:
    """Start an asynchronous code generation pipeline."""
    run_id = str(uuid.uuid4())

    _run_status[run_id] = {
        "task_id": request.task_id,
        "status": "accepted",
        "type": "codegen",
    }

    background_tasks.add_task(_execute_codegen, run_id, request)

    return CodegenRunResponse(
        run_id=run_id,
        task_id=request.task_id,
        status="accepted",
    )


async def _execute_codegen(run_id: str, request: CodegenRunRequest) -> None:
    """Background task executing the codegen graph."""
    task_id = request.task_id
    token = register_token(run_id)

    try:
        _run_status[run_id]["status"] = "running"
        logger.info("Starting codegen pipeline for task %s (run %s)", task_id, run_id)

        graph = build_codegen_graph()

        initial_state = {
            "task_id": task_id,
            "run_id": run_id,          # Phase 2: propagated to all nodes for metrics & retry tracing
            "project_id": request.project_id,
            "user_id": request.user_id or 0,
            "instructions": request.instructions,
            "prd": request.prd,
            "stack_backend": request.stack.backend,
            "stack_frontend": request.stack.frontend,
            "stack_db": request.stack.db,
            "template_key": request.template_id,
            "workspace_dir": request.workspace_dir,
            "generated_files": {},
            "file_plan": [],
            "file_list": [],
            "build_status": "pending",
            "build_log": "",
            "build_fix_round": 0,
            "smoke_status": "pending",
            "smoke_log": "",
            "smoke_fix_round": 0,
            "preview_url": "",
            "preview_status": "pending",
            "quality_score": 0.0,
            "quality_issues": [],
            "contract_violations": [],
            "current_step": "",
            "error": None,
            "callback_base_url": request.callback_base_url,
            "callback_api_key": request.callback_api_key,
            # Phase 3: per-task LLM override (None when not provided by dispatcher)
            "llm_config_override": request.llm_config.model_dump(exclude_none=True) if request.llm_config else None,
        }

        result = await graph.ainvoke(initial_state)

        _run_status[run_id]["status"] = "completed"
        _run_status[run_id]["result"] = {
            "preview_url": result.get("preview_url", ""),
            "build_status": result.get("build_status", ""),
            "smoke_status": result.get("smoke_status", ""),
            "files_generated": len(result.get("generated_files", {})),
        }
        logger.info("Codegen pipeline completed for task %s", task_id)

    except TaskCancelled:
        logger.info("Codegen pipeline cancelled for task %s (run %s)", task_id, run_id)
        _run_status[run_id]["status"] = "cancelled"
        try:
            await destroy_sandbox(task_id)
        except Exception:
            pass

    except Exception as exc:
        logger.exception("Codegen pipeline failed for task %s: %s", task_id, exc)
        _run_status[run_id]["status"] = "failed"
        _run_status[run_id]["error"] = str(exc)

        # Cleanup sandbox on failure
        try:
            await destroy_sandbox(task_id)
        except Exception:
            pass

    finally:
        remove_token(run_id)


# ------------------------------------------------------------------
# Cancellation
# ------------------------------------------------------------------


@router.post("/codegen/cancel/{task_id}")
async def cancel_codegen(task_id: str) -> Dict:
    """Cancel a running codegen pipeline by task_id.

    Called by the Java API Gateway when the user clicks "Cancel".
    Sets the cancellation flag so the next node check-point raises
    ``TaskCancelled`` for a graceful exit.
    """
    found = cancel_by_task_id(task_id, _run_status)
    if not found:
        raise HTTPException(status_code=404, detail=f"No active run for task {task_id}")
    return {"task_id": task_id, "status": "cancelling"}


# ------------------------------------------------------------------
# Paper generation
# ------------------------------------------------------------------


@router.post("/paper/run", response_model=PaperRunResponse)
async def run_paper(
    request: PaperRunRequest,
    background_tasks: BackgroundTasks,
) -> PaperRunResponse:
    """Start an asynchronous paper generation pipeline."""
    run_id = str(uuid.uuid4())

    _run_status[run_id] = {
        "task_id": request.task_id,
        "status": "accepted",
        "type": "paper",
    }

    background_tasks.add_task(_execute_paper, run_id, request)

    return PaperRunResponse(
        run_id=run_id,
        task_id=request.task_id,
        status="accepted",
    )


async def _execute_paper(run_id: str, request: PaperRunRequest) -> None:
    """Background task executing the paper graph."""
    task_id = request.task_id

    try:
        _run_status[run_id]["status"] = "running"
        logger.info("Starting paper pipeline for task %s (run %s)", task_id, run_id)

        graph = build_paper_graph()

        initial_state = {
            "task_id": task_id,
            "session_id": request.session_id,
            "topic": request.topic,
            "discipline": request.discipline,
            "degree_level": request.degree_level,
            "method_preference": request.method_preference,
            "topic_refined": "",
            "research_questions": [],
            "retrieved_sources": [],
            "source_count": 0,
            "rag_chunk_count": 0,
            "rag_evidence": [],
            "outline_draft": {},
            "expanded_outline": {},
            "chapter_evidence_map": {},
            "citation_style": "GB/T 7714",
            "quality_report": {},
            "quality_score": 0.0,
            "quality_issues": [],
            "uncovered_sections": [],
            "rewrite_round": 0,
            "manuscript": {},
            "current_step": "",
            "error": None,
            "callback_base_url": request.callback_base_url,
            "callback_api_key": request.callback_api_key,
        }

        result = await graph.ainvoke(initial_state)

        _run_status[run_id]["status"] = "completed"
        _run_status[run_id]["result"] = {
            "quality_score": result.get("quality_score", 0),
            "source_count": result.get("source_count", 0),
            "rewrite_rounds": result.get("rewrite_round", 0),
        }
        logger.info("Paper pipeline completed for task %s", task_id)

    except Exception as exc:
        logger.exception("Paper pipeline failed for task %s: %s", task_id, exc)
        _run_status[run_id]["status"] = "failed"
        _run_status[run_id]["error"] = str(exc)


# ------------------------------------------------------------------
# Status check
# ------------------------------------------------------------------


@router.get("/status/{run_id}")
async def get_run_status(run_id: str) -> Dict:
    """Check the status of a pipeline run."""
    status = _run_status.get(run_id)
    if status is None:
        raise HTTPException(status_code=404, detail=f"Run {run_id} not found")
    return status
