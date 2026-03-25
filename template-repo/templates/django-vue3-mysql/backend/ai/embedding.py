"""Embedding helper for vector generation calls."""

from __future__ import annotations

import httpx

from .config import ai_config


class EmbeddingClient:
    """Synchronous embedding client for OpenAI-compatible APIs."""

    def __init__(self) -> None:
        self._client = httpx.Client(timeout=httpx.Timeout(ai_config.timeout_seconds, connect=10.0))

    def embed(self, text: str) -> list[float]:
        """Embed a single text string."""
        return self.embed_batch([text])[0]

    def embed_batch(self, texts: list[str]) -> list[list[float]]:
        """Embed multiple text strings in one request."""
        response = self._client.post(
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
