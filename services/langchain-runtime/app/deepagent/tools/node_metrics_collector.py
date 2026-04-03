"""Node-level metrics collection for the DeepAgent pipeline.

Each node wraps its core logic with NodeMetricsCollector, which measures
wall-clock duration, counts LLM calls / tokens / subtask results, then
posts the metrics to Java via post_node_metrics() (non-fatal on failure).

Metrics schema follows docs/node-observability-spec.md.
"""

from __future__ import annotations

import logging
import time
from contextlib import asynccontextmanager
from typing import Any, Dict, List, Optional

logger = logging.getLogger(__name__)


class NodeMetricsCollector:
    """Collects per-node execution metrics and pushes them to Java.

    Usage::

        async with NodeMetricsCollector(state, "codegen_backend") as m:
            for path in files:
                content = await generate(path)
                m.record_subtask(path, success=bool(content))
                m.record_model_call(prompt_tokens=..., completion_tokens=...)
    """

    def __init__(self, state: Dict[str, Any], node: str) -> None:
        self._task_id: str = state.get("task_id", "")
        self._run_id: str = state.get("run_id", "")
        self._node = node
        self._callback_base_url: str = state.get("callback_base_url", "")
        self._callback_api_key: str = state.get("callback_api_key", "")

        self._start_time: float = 0.0
        self._model_calls: int = 0
        self._prompt_tokens: int = 0
        self._completion_tokens: int = 0
        self._subtasks_total: int = 0
        self._subtasks_finished: int = 0
        self._degrade: bool = False
        self._error_code: str = ""
        self._error_message: str = ""
        self._status: str = "finished"

    # ------------------------------------------------------------------
    # Public recording methods (call from inside the node)
    # ------------------------------------------------------------------

    def record_model_call(self, prompt_tokens: int = 0, completion_tokens: int = 0) -> None:
        self._model_calls += 1
        self._prompt_tokens += prompt_tokens
        self._completion_tokens += completion_tokens

    def record_subtask(self, path: str, success: bool) -> None:
        self._subtasks_total += 1
        if success:
            self._subtasks_finished += 1

    def mark_degrade(self, reason: str = "") -> None:
        self._degrade = True
        if reason:
            logger.warning("Node %s degraded: %s", self._node, reason)

    def mark_failed(self, error_code: str = "", error_message: str = "") -> None:
        self._status = "failed"
        self._error_code = error_code
        self._error_message = error_message

    # ------------------------------------------------------------------
    # Context-manager protocol
    # ------------------------------------------------------------------

    async def __aenter__(self) -> "NodeMetricsCollector":
        self._start_time = time.monotonic()
        return self

    async def __aexit__(self, exc_type, exc_val, exc_tb) -> bool:
        duration_ms = int((time.monotonic() - self._start_time) * 1000)
        if exc_type is not None:
            self._status = "failed"
            self._error_message = str(exc_val)[:255]

        metrics: Dict[str, Any] = {
            "task_id": self._task_id,
            "run_id": self._run_id,
            "node": self._node,
            "status": self._status,
            "duration_ms": duration_ms,
            "model_calls": self._model_calls,
            "tokens": {
                "prompt": self._prompt_tokens,
                "completion": self._completion_tokens,
                "total": self._prompt_tokens + self._completion_tokens,
            },
            "subtasks_total": self._subtasks_total,
            "subtasks_finished": self._subtasks_finished,
            "degrade": self._degrade,
            "error_code": self._error_code,
            "error_message": self._error_message,
        }

        await self._post_metrics(metrics)
        return False  # do not suppress exceptions

    async def _post_metrics(self, metrics: Dict[str, Any]) -> None:
        if not self._task_id:
            return
        try:
            from ..config import CallbackConfig
            from ..tools.java_api_client import JavaApiClient
            import os

            config = CallbackConfig(
                base_url=self._callback_base_url or "http://localhost:8080",
                api_key=self._callback_api_key or "smartark-internal",
                timeout=int(os.getenv("DEEPAGENT_CALLBACK_TIMEOUT", "30")),
            )
            client = JavaApiClient(config)
            await client.post_node_metrics(self._task_id, metrics)
            await client.close()
        except Exception as exc:
            logger.warning("NodeMetricsCollector: failed to post metrics for %s/%s: %s",
                           self._task_id, self._node, exc)
