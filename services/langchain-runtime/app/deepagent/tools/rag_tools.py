"""LangChain tool wrappers for RAG indexing and retrieval via Java API."""

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
async def rag_index_sources(
    session_id: int,
    sources: List[Dict[str, Any]],
    discipline: str = "",
) -> Dict[str, Any]:
    """Index retrieved academic papers into the Qdrant vector store for RAG.

    Args:
        session_id: Paper topic session ID.
        sources: List of paper source dicts to index.
        discipline: Academic discipline for indexing metadata.

    Returns indexing stats with chunk_count and doc_count.
    """
    client = _get_client()
    return await client.rag_index(
        session_id=session_id, sources=sources, discipline=discipline
    )


@tool
async def rag_retrieve(
    session_id: int,
    query: str,
    discipline: str = "",
    top_k: int = 30,
) -> List[Dict[str, Any]]:
    """Retrieve and rerank evidence chunks from the vector store.

    Args:
        session_id: Paper topic session ID.
        query: Combined topic + research questions as search query.
        discipline: Discipline filter for vector search.
        top_k: Number of top evidence chunks to return.

    Returns ranked evidence items with content, scores, and source metadata.
    """
    client = _get_client()
    return await client.rag_retrieve(
        session_id=session_id, query=query, discipline=discipline, top_k=top_k
    )
