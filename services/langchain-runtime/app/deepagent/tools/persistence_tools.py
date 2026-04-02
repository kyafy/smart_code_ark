"""LangChain tool wrappers for persisting state back to Java API."""

from __future__ import annotations

from typing import Any, Dict, List, Optional

from langchain_core.tools import tool

from .java_api_client import JavaApiClient

_client: JavaApiClient | None = None


def set_client(client: JavaApiClient) -> None:
    global _client
    _client = client


def _get_client() -> JavaApiClient:
    if _client is None:
        raise RuntimeError("JavaApiClient not initialized")
    return _client


@tool
async def persist_step_result(
    task_id: str,
    step_code: str,
    status: str,
    progress: int = 0,
    output_summary: str = "",
    output: Optional[Dict[str, Any]] = None,
) -> str:
    """Report step progress/result to the Java API Gateway.

    Args:
        task_id: Task identifier.
        step_code: Step code (e.g. 'codegen_backend', 'outline_generate').
        status: One of 'running', 'finished', 'failed'.
        progress: Percentage progress (0-100).
        output_summary: Human-readable summary of results.
        output: Optional structured output data.

    Returns confirmation message.
    """
    client = _get_client()
    await client.update_step(
        task_id=task_id,
        step_code=step_code,
        status=status,
        progress=progress,
        output_summary=output_summary,
        output=output,
    )
    return f"Step {step_code} updated to {status}"


@tool
async def register_preview(
    task_id: str,
    host_port: int,
    container_id: str = "",
) -> str:
    """Register a sandbox as the preview environment for a task.

    Notifies the Java gateway to register a route so the frontend
    can access the preview at /p/{task_id}/.

    Returns the preview URL.
    """
    client = _get_client()
    url = await client.notify_sandbox_ready(
        task_id=task_id,
        host_port=host_port,
        container_id=container_id,
    )
    return url


@tool
async def persist_paper_outline(
    session_id: int,
    outline_json: Dict[str, Any],
    citation_style: str = "GB/T 7714",
    chapter_evidence_map: Optional[Dict[str, Any]] = None,
) -> str:
    """Persist a paper outline version to the database.

    Args:
        session_id: Paper topic session ID.
        outline_json: Full outline structure with chapters and sections.
        citation_style: Citation format standard.
        chapter_evidence_map: Mapping of chapters to evidence chunks.

    Returns confirmation message.
    """
    client = _get_client()
    await client.persist_paper_outline(
        session_id=session_id,
        outline_json=outline_json,
        citation_style=citation_style,
        chapter_evidence_map=chapter_evidence_map,
    )
    return f"Outline saved for session {session_id}"


@tool
async def persist_paper_manuscript(
    session_id: int,
    manuscript_json: Dict[str, Any],
    quality_report: Optional[Dict[str, Any]] = None,
    quality_score: float = 0.0,
) -> str:
    """Persist a paper manuscript and quality report to the database.

    Args:
        session_id: Paper topic session ID.
        manuscript_json: Full manuscript with expanded chapter content.
        quality_report: Quality check results.
        quality_score: Overall quality score (0-100).

    Returns confirmation message.
    """
    client = _get_client()
    await client.persist_paper_manuscript(
        session_id=session_id,
        manuscript_json=manuscript_json,
        quality_report=quality_report,
        quality_score=quality_score,
    )
    return f"Manuscript saved for session {session_id}"
