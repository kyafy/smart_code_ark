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


class JavaApiError(RuntimeError):
    """Raised when Java internal API returns business failure payload."""

    def __init__(self, path: str, code: int, message: str, payload: Any = None) -> None:
        super().__init__(f"java internal api failed: path={path}, code={code}, message={message}")
        self.path = path
        self.code = code
        self.message = message
        self.payload = payload


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

    async def _post_json(self, path: str, payload: Dict[str, Any]) -> Any:
        client = await self._ensure_client()
        resp = await client.post(path, json=payload)
        resp.raise_for_status()
        return self._unwrap_payload(path, resp)

    def _unwrap_payload(self, path: str, resp: httpx.Response) -> Any:
        if not resp.content:
            return None
        try:
            payload = resp.json()
        except ValueError:
            text = (resp.text or "").strip()
            if text:
                logger.debug("Non-JSON response from Java API: path=%s, body=%s", path, text[:200])
                return text
            return None

        # Compatible with Java ApiResponse envelope:
        # {"code": 0, "message": "ok", "data": {...}}
        if isinstance(payload, dict) and "code" in payload and "message" in payload:
            code_raw = payload.get("code")
            try:
                code = int(code_raw)
            except Exception:
                code = -1
            message = str(payload.get("message") or "")
            if code != 0:
                raise JavaApiError(path=path, code=code, message=message or "unknown", payload=payload)
            return payload.get("data")

        # Bare JSON payload mode.
        return payload

    @staticmethod
    def _ensure_dict(value: Any) -> Dict[str, Any]:
        if isinstance(value, dict):
            return value
        return {}

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

        await self._post_json(f"/api/internal/task/{task_id}/step-update", payload)
        logger.debug("Step update sent: task=%s step=%s status=%s", task_id, step_code, status)

    async def log(self, task_id: str, level: str, content: str) -> None:
        """POST /api/internal/task/{taskId}/log"""
        await self._post_json(
            f"/api/internal/task/{task_id}/log",
            {"level": level, "content": content},
        )

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
        data = await self._post_json(
            f"/api/internal/preview/{task_id}/sandbox-ready",
            {
                "host_port": host_port,
                "container_id": container_id,
            },
        )
        payload = self._ensure_dict(data)
        return str(payload.get("preview_url") or f"/p/{task_id}/")

    async def notify_hotfix(
        self,
        task_id: str,
        fix_description: str,
        files_changed: list[str] | None = None,
    ) -> None:
        """POST /api/internal/preview/{taskId}/hotfix"""
        await self._post_json(
            f"/api/internal/preview/{task_id}/hotfix",
            {
                "fix_description": fix_description,
                "files_changed": files_changed or [],
            },
        )

    # ------------------------------------------------------------------
    # Model / generation delegation
    # ------------------------------------------------------------------

    async def generate_project_structure(
        self, prd: str, stack: Dict[str, str], instructions: str = ""
    ) -> Dict[str, Any]:
        """POST /api/internal/model/structure"""
        data = await self._post_json(
            "/api/internal/model/structure",
            {"prd": prd, "stack": stack, "instructions": instructions},
        )
        if isinstance(data, list):
            return {"files": data}
        return self._ensure_dict(data)

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
        data = await self._post_json(
            "/api/internal/model/generate-file",
            {
                "file_path": file_path,
                "prd": prd,
                "tech_stack": tech_stack,
                "project_structure": project_structure,
                "group": group,
                "instructions": instructions,
            },
        )
        if isinstance(data, str):
            return data
        payload = self._ensure_dict(data)
        return str(payload.get("content") or "")

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
        data = await self._post_json(
            "/api/internal/academic/search",
            {"query": query, "discipline": discipline, "limit": limit},
        )
        if isinstance(data, list):
            return [item for item in data if isinstance(item, dict)]
        payload = self._ensure_dict(data)
        results = payload.get("results", [])
        if isinstance(results, list):
            return [item for item in results if isinstance(item, dict)]
        return []

    async def rag_index(
        self,
        session_id: int,
        sources: list[Dict[str, Any]],
        discipline: str = "",
    ) -> Dict[str, Any]:
        """POST /api/internal/rag/index"""
        data = await self._post_json(
            "/api/internal/rag/index",
            {
                "session_id": session_id,
                "sources": sources,
                "discipline": discipline,
            },
        )
        return self._ensure_dict(data)

    async def rag_retrieve(
        self,
        session_id: int,
        query: str,
        discipline: str = "",
        top_k: int = 30,
    ) -> list[Dict[str, Any]]:
        """POST /api/internal/rag/retrieve"""
        data = await self._post_json(
            "/api/internal/rag/retrieve",
            {
                "session_id": session_id,
                "query": query,
                "discipline": discipline,
                "top_k": top_k,
            },
        )
        if isinstance(data, list):
            return [item for item in data if isinstance(item, dict)]
        payload = self._ensure_dict(data)
        evidence = payload.get("evidence", [])
        if isinstance(evidence, list):
            return [item for item in evidence if isinstance(item, dict)]
        return []

    async def quality_evaluate(
        self,
        task_id: str,
        workspace_dir: str = "",
    ) -> Dict[str, Any]:
        """POST /api/internal/quality/evaluate"""
        data = await self._post_json(
            "/api/internal/quality/evaluate",
            {"task_id": task_id, "workspace_dir": workspace_dir},
        )
        return self._ensure_dict(data)

    # ------------------------------------------------------------------
    # Template
    # ------------------------------------------------------------------

    async def template_resolve(
        self, stack: Dict[str, str], template_id: str = ""
    ) -> Dict[str, Any]:
        """POST /api/internal/template/resolve"""
        data = await self._post_json(
            "/api/internal/template/resolve",
            {"stack": stack, "template_id": template_id},
        )
        return self._ensure_dict(data)

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
        await self._post_json(
            f"/api/internal/paper/{session_id}/outline",
            {
                "outline_json": outline_json,
                "citation_style": citation_style,
                "chapter_evidence_map": chapter_evidence_map or {},
            },
        )

    async def persist_paper_manuscript(
        self,
        session_id: int,
        manuscript_json: Dict[str, Any],
        quality_report: Optional[Dict[str, Any]] = None,
        quality_score: float = 0.0,
    ) -> None:
        """POST /api/internal/paper/{sessionId}/manuscript"""
        await self._post_json(
            f"/api/internal/paper/{session_id}/manuscript",
            {
                "manuscript_json": manuscript_json,
                "quality_report": quality_report or {},
                "quality_score": quality_score,
            },
        )
