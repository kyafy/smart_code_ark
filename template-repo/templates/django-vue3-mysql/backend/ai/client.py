"""Unified LLM client with normal and streaming chat helpers."""

from __future__ import annotations

import json
from typing import Any, Iterator

import httpx

from .config import ai_config


class AiClient:
    """Synchronous LLM client for OpenAI-compatible chat APIs."""

    def __init__(self) -> None:
        # Django templates default to sync views, so the shared AI helper stays
        # synchronous to keep generated controller code straightforward.
        self._client = httpx.Client(timeout=httpx.Timeout(ai_config.timeout_seconds, connect=10.0))

    def chat(self, system_prompt: str, user_message: str, **overrides: Any) -> str:
        """Send a single system plus user message and return the final text."""
        messages = [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_message},
        ]
        return self.chat_messages(messages, **overrides)

    def chat_messages(self, messages: list[dict[str, str]], **overrides: Any) -> str:
        """Send a full message list and return the assistant content."""
        # Keep the payload OpenAI-compatible so provider swaps only require
        # configuration changes, not business-code changes.
        response = self._client.post(
            f"{ai_config.base_url.rstrip('/')}/chat/completions",
            json=self._build_body(messages, stream=False, **overrides),
            headers=self._headers(),
        )
        response.raise_for_status()
        return response.json()["choices"][0]["message"]["content"]

    def chat_stream(self, system_prompt: str, user_message: str, **overrides: Any) -> Iterator[str]:
        """Yield SSE chunks from a single system plus user message."""
        messages = [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_message},
        ]
        return self.chat_messages_stream(messages, **overrides)

    def chat_messages_stream(
        self, messages: list[dict[str, str]], **overrides: Any
    ) -> Iterator[str]:
        """Yield SSE chunks from a message-list completion."""
        with self._client.stream(
            "POST",
            f"{ai_config.base_url.rstrip('/')}/chat/completions",
            json=self._build_body(messages, stream=True, **overrides),
            headers=self._headers(),
        ) as response:
            response.raise_for_status()
            for line in response.iter_lines():
                # Repackage upstream SSE chunks into SSE payloads that Django can
                # forward with StreamingHttpResponse and minimal glue code.
                if not line.startswith("data: ") or line == "data: [DONE]":
                    continue
                try:
                    chunk = json.loads(line[6:])
                except json.JSONDecodeError:
                    continue
                content = chunk.get("choices", [{}])[0].get("delta", {}).get("content", "")
                if content:
                    yield f"data: {json.dumps({'content': content})}\n\n"

    def _build_body(
        self, messages: list[dict[str, str]], stream: bool, **overrides: Any
    ) -> dict[str, Any]:
        # Centralized body assembly keeps this client easy to extend with future
        # options like JSON mode or tool invocation.
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
