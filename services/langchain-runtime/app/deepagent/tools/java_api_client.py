"""HTTP client for calling Java API Gateway internal endpoints.

All callbacks from the DeepAgent Python service to the Java API Gateway
go through this client.  It handles authentication (X-Internal-Token),
timeouts, and error mapping.
"""

from __future__ import annotations

import logging
from typing import Any, Dict, Optional

import httpx

from ..config import CallbackConfig

logger = logging.getLogger(__name__)


class JavaApiClient:
    """Async HTTP client for Java API Gateway internal endpoints."""

    def __init__(self, config: Optional[CallbackConfig] = None) -> None:
        self._config = config or CallbackConfig()
        self._client: Optional[httpx.AsyncClient] = None

    async def _ensure_client(self) -> httpx.AsyncClient:
        if self._client is None or self._client.is_closed:
            self._client = httpx.AsyncClient(
                base_url=self._config.base_url,
                timeout=self._config.timeout,
                headers={
                    "X-Internal-Token": self._config.api_key,
                    "Content-Type": "application/json",
                },
            )
        return self._client

    async def close(self) -> None:
        if self._client and not self._client.is_closed:
            await self._client.aclose()

    # ------------------------------------------------------------------
    # Task step updates
    # ------------------------------------------------------------------

    async def update_step(
        self,
        task_id: str,
        step_code: str,
        status: str,
        progress: int = 0,
        output_summary: str = "",
        output: Optional[Dict[str, Any]] = None,
        error_code: Optional[str] = None,
        error_message: Optional[str] = None,
    ) -> None:
        """POST /api/internal/task/{taskId}/step-update"""
        client = await self._ensure_client()
        payload: Dict[str, Any] = {
            "step_code": step_code,
            "status": status,
            "progress": progress,
            "output_summary": output_summary,
        }
        if output:
            payload["output"] = output
        if error_code:
            payload["error_code"] = error_code
        if error_message:
            payload["error_message"] = error_message

        resp = await client.post(
            f"/api/internal/task/{task_id}/step-update",
            json=payload,
        )
        resp.raise_for_status()
        logger.debug("Step update sent: task=%s step=%s status=%s", task_id, step_code, status)

    async def log(self, task_id: str, level: str, content: str) -> None:
        """POST /api/internal/task/{taskId}/log"""
        client = await self._ensure_client()
        resp = await client.post(
            f"/api/internal/task/{task_id}/log",
            json={"level": level, "content": content},
        )
        resp.raise_for_status()

    # ------------------------------------------------------------------
    # Preview callbacks
    # ------------------------------------------------------------------

    async def notify_sandbox_ready(
        self,
        task_id: str,
        host_port: int,
        container_id: str = "",
    ) -> str:
        """POST /api/internal/preview/{taskId}/sandbox-ready

        Returns the preview URL assigned by Java gateway.
        """
        client = await self._ensure_client()
        resp = await client.post(
            f"/api/internal/preview/{task_id}/sandbox-ready",
            json={
                "host_port": host_port,
                "container_id": container_id,
            },
        )
        resp.raise_for_status()
        data = resp.json()
        return data.get("preview_url", f"/p/{task_id}/")

    async def notify_hotfix(
        self,
        task_id: str,
        fix_description: str,
        files_changed: list[str] | None = None,
    ) -> None:
        """POST /api/internal/preview/{taskId}/hotfix"""
        client = await self._ensure_client()
        resp = await client.post(
            f"/api/internal/preview/{task_id}/hotfix",
            json={
                "fix_description": fix_description,
                "files_changed": files_changed or [],
            },
        )
        resp.raise_for_status()

    # ------------------------------------------------------------------
    # Model / generation delegation
    # ------------------------------------------------------------------

    async def generate_project_structure(
        self, prd: str, stack: Dict[str, str], instructions: str = ""
    ) -> Dict[str, Any]:
        """POST /api/internal/model/structure"""
        client = await self._ensure_client()
        resp = await client.post(
            "/api/internal/model/structure",
            json={"prd": prd, "stack": stack, "instructions": instructions},
        )
        resp.raise_for_status()
        return resp.json()

    async def generate_file_content(
        self,
        file_path: str,
        prd: str,
        tech_stack: str,
        project_structure: str,
        group: str = "backend",
        instructions: str = "",
    ) -> str:
        """POST /api/internal/model/generate-file"""
        client = await self._ensure_client()
        resp = await client.post(
            "/api/internal/model/generate-file",
            json={
                "file_path": file_path,
                "prd": prd,
                "tech_stack": tech_stack,
                "project_structure": project_structure,
                "group": group,
                "instructions": instructions,
            },
        )
        resp.raise_for_status()
        return resp.json().get("content", "")

    # ------------------------------------------------------------------
    # Academic / RAG
    # ------------------------------------------------------------------

    async def academic_search(
        self,
        query: str,
        discipline: str = "",
        limit: int = 20,
    ) -> list[Dict[str, Any]]:
        """POST /api/internal/academic/search"""
        client = await self._ensure_client()
        resp = await client.post(
            "/api/internal/academic/search",
            json={"query": query, "discipline": discipline, "limit": limit},
        )
        resp.raise_for_status()
        return resp.json().get("results", [])

    async def rag_index(
        self,
        session_id: int,
        sources: list[Dict[str, Any]],
        discipline: str = "",
    ) -> Dict[str, Any]:
        """POST /api/internal/rag/index"""
        client = await self._ensure_client()
        resp = await client.post(
            "/api/internal/rag/index",
            json={
                "session_id": session_id,
                "sources": sources,
                "discipline": discipline,
            },
        )
        resp.raise_for_status()
        return resp.json()

    async def rag_retrieve(
        self,
        session_id: int,
        query: str,
        discipline: str = "",
        top_k: int = 30,
    ) -> list[Dict[str, Any]]:
        """POST /api/internal/rag/retrieve"""
        client = await self._ensure_client()
        resp = await client.post(
            "/api/internal/rag/retrieve",
            json={
                "session_id": session_id,
                "query": query,
                "discipline": discipline,
                "top_k": top_k,
            },
        )
        resp.raise_for_status()
        return resp.json().get("evidence", [])

    async def quality_evaluate(
        self,
        task_id: str,
        workspace_dir: str = "",
    ) -> Dict[str, Any]:
        """POST /api/internal/quality/evaluate"""
        client = await self._ensure_client()
        resp = await client.post(
            "/api/internal/quality/evaluate",
            json={"task_id": task_id, "workspace_dir": workspace_dir},
        )
        resp.raise_for_status()
        return resp.json()

    # ------------------------------------------------------------------
    # Template
    # ------------------------------------------------------------------

    async def template_resolve(
        self, stack: Dict[str, str], template_id: str = ""
    ) -> Dict[str, Any]:
        """POST /api/internal/template/resolve"""
        client = await self._ensure_client()
        resp = await client.post(
            "/api/internal/template/resolve",
            json={"stack": stack, "template_id": template_id},
        )
        resp.raise_for_status()
        return resp.json()

    # ------------------------------------------------------------------
    # Paper persistence
    # ------------------------------------------------------------------

    async def persist_paper_outline(
        self,
        session_id: int,
        outline_json: Dict[str, Any],
        citation_style: str = "GB/T 7714",
        chapter_evidence_map: Optional[Dict[str, Any]] = None,
    ) -> None:
        """POST /api/internal/paper/{sessionId}/outline"""
        client = await self._ensure_client()
        resp = await client.post(
            f"/api/internal/paper/{session_id}/outline",
            json={
                "outline_json": outline_json,
                "citation_style": citation_style,
                "chapter_evidence_map": chapter_evidence_map or {},
            },
        )
        resp.raise_for_status()

    async def persist_paper_manuscript(
        self,
        session_id: int,
        manuscript_json: Dict[str, Any],
        quality_report: Optional[Dict[str, Any]] = None,
        quality_score: float = 0.0,
    ) -> None:
        """POST /api/internal/paper/{sessionId}/manuscript"""
        client = await self._ensure_client()
        resp = await client.post(
            f"/api/internal/paper/{session_id}/manuscript",
            json={
                "manuscript_json": manuscript_json,
                "quality_report": quality_report or {},
                "quality_score": quality_score,
            },
        )
        resp.raise_for_status()
