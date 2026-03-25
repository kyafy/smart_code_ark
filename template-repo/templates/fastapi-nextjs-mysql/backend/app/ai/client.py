"""Unified LLM client with normal and streaming chat helpers."""

from __future__ import annotations

import json
from typing import Any, AsyncIterator

import httpx

from .config import ai_config


class AiClient:
    """Async LLM client for OpenAI-compatible chat APIs."""

    def __init__(self) -> None:
        self._client: httpx.AsyncClient | None = None

    def _get_client(self) -> httpx.AsyncClient:
        if self._client is None or self._client.is_closed:
            # Reuse one HTTP client instance so generated services avoid
            # reconnect overhead while keeping the helper easy to mock.
            self._client = httpx.AsyncClient(
                timeout=httpx.Timeout(ai_config.timeout_seconds, connect=10.0)
            )
        return self._client

    async def chat(self, system_prompt: str, user_message: str, **overrides: Any) -> str:
        """Send a single system plus user message and return the final text."""
        messages = [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_message},
        ]
        return await self.chat_messages(messages, **overrides)

    async def chat_messages(self, messages: list[dict[str, str]], **overrides: Any) -> str:
        """Send a full message list and return the assistant content."""
        # Keep the payload OpenAI-compatible so generated business code can move
        # between providers without changing call sites.
        response = await self._get_client().post(
            f"{ai_config.base_url.rstrip('/')}/chat/completions",
            json=self._build_body(messages, stream=False, **overrides),
            headers=self._headers(),
        )
        response.raise_for_status()
        return response.json()["choices"][0]["message"]["content"]

    async def chat_stream(
        self, system_prompt: str, user_message: str, **overrides: Any
    ) -> AsyncIterator[str]:
        """Yield text chunks from a streaming completion."""
        messages = [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_message},
        ]
        async for chunk in self.chat_messages_stream(messages, **overrides):
            yield chunk

    async def chat_messages_stream(
        self, messages: list[dict[str, str]], **overrides: Any
    ) -> AsyncIterator[str]:
        """Yield text chunks from a streaming completion for a message list."""
        async with self._get_client().stream(
            "POST",
            f"{ai_config.base_url.rstrip('/')}/chat/completions",
            json=self._build_body(messages, stream=True, **overrides),
            headers=self._headers(),
        ) as response:
            response.raise_for_status()
            async for line in response.aiter_lines():
                # SSE streams mix control frames and content frames. Only the
                # delta text is forwarded to downstream business code.
                if not line.startswith("data: ") or line == "data: [DONE]":
                    continue
                try:
                    chunk = json.loads(line[6:])
                except json.JSONDecodeError:
                    continue
                content = chunk.get("choices", [{}])[0].get("delta", {}).get("content", "")
                if content:
                    yield content

    def _build_body(
        self, messages: list[dict[str, str]], stream: bool, **overrides: Any
    ) -> dict[str, Any]:
        # Centralizing body assembly keeps the public API small while leaving
        # room for future options such as JSON mode or tool calls.
        body: dict[str, Any] = {
            "model": overrides.get("model", ai_config.model),
            "messages": messages,
            "stream": stream,
            "temperature": overrides.get("temperature", ai_config.temperature),
        }
        max_tokens = overrides.get("max_tokens", ai_config.max_tokens)
        if max_tokens > 0:
            body["max_tokens"] = max_tokens
        return body

    def _headers(self) -> dict[str, str]:
        return {
            "Content-Type": "application/json",
            "Authorization": f"Bearer {ai_config.api_key}",
        }


ai_client = AiClient()
