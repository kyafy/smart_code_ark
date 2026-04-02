"""LangChain tool wrappers for academic paper search via Java API."""

from __future__ import annotations

from typing import Any, Dict, List

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
async def academic_search(
    query: str,
    discipline: str = "",
    limit: int = 20,
) -> List[Dict[str, Any]]:
    """Search academic papers across SemanticScholar, Crossref, and arXiv.

    Args:
        query: Search query combining topic and research question.
        discipline: Academic discipline for source filtering.
        limit: Maximum number of results.

    Returns a list of paper dicts with title, authors, year, abstract, url, etc.
    """
    client = _get_client()
    return await client.academic_search(query=query, discipline=discipline, limit=limit)
