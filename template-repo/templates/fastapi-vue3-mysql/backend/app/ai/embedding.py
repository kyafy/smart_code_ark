"""Embedding helper for vector generation calls."""

from __future__ import annotations

import httpx

from .config import ai_config


class EmbeddingClient:
    """Async embedding client for OpenAI-compatible APIs."""

    def __init__(self) -> None:
        self._client: httpx.AsyncClient | None = None

    def _get_client(self) -> httpx.AsyncClient:
        if self._client is None or self._client.is_closed:
            self._client = httpx.AsyncClient(
                timeout=httpx.Timeout(ai_config.timeout_seconds, connect=10.0)
            )
        return self._client

    async def embed(self, text: str) -> list[float]:
        """Embed a single text string."""
        return (await self.embed_batch([text]))[0]

    async def embed_batch(self, texts: list[str]) -> list[list[float]]:
        """Embed multiple text strings in one request."""
        response = await self._get_client().post(
            f"{ai_config.base_url.rstrip('/')}/embeddings",
            json={"model": ai_config.embedding_model, "input": texts},
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {ai_config.api_key}",
            },
        )
        response.raise_for_status()
        return [item["embedding"] for item in response.json()["data"]]


embedding_client = EmbeddingClient()
