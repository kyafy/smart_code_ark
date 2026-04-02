"""LangChain tool wrappers for quality evaluation via Java API."""

from __future__ import annotations

from typing import Any, Dict

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
async def quality_evaluate(
    task_id: str,
    workspace_dir: str = "",
) -> Dict[str, Any]:
    """Evaluate the quality of generated artifacts against quality gates.

    Returns a quality report with score (0-1), passed checks, and failed rules.
    Covers structure gate, semantic gate, and build gate.
    """
    client = _get_client()
    return await client.quality_evaluate(task_id=task_id, workspace_dir=workspace_dir)
