"""AI configuration loaded from environment variables."""

from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class AiConfig:
    """Configuration for LLM and embedding requests."""

    base_url: str = os.getenv("AI_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1")
    api_key: str = os.getenv("AI_API_KEY", "")
    model: str = os.getenv("AI_MODEL", "qwen-plus")
    embedding_model: str = os.getenv("AI_EMBEDDING_MODEL", "text-embedding-v3")
    timeout_seconds: float = float(os.getenv("AI_TIMEOUT_SECONDS", "60"))
    max_tokens: int = int(os.getenv("AI_MAX_TOKENS", "4096"))
    temperature: float = float(os.getenv("AI_TEMPERATURE", "0.7"))


ai_config = AiConfig()
