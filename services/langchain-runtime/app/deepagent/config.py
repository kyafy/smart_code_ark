"""DeepAgent configuration loaded from environment variables."""

from __future__ import annotations

import os
from dataclasses import dataclass, field


@dataclass(frozen=True)
class LLMConfig:
    """LLM provider configuration."""

    base_url: str = ""
    api_key: str = ""
    model: str = "qwen-plus"
    temperature: float = 0.2
    max_tokens: int = 8192

    @classmethod
    def from_env(cls) -> LLMConfig:
        return cls(
            base_url=os.getenv("LANGCHAIN_MODEL_BASE_URL", ""),
            api_key=os.getenv("LANGCHAIN_MODEL_API_KEY", ""),
            model=os.getenv("LANGCHAIN_MODEL_NAME", "qwen-plus"),
            temperature=float(os.getenv("LANGCHAIN_MODEL_TEMPERATURE", "0.2")),
            max_tokens=int(os.getenv("LANGCHAIN_MODEL_MAX_TOKENS", "8192")),
        )


@dataclass(frozen=True)
class SandboxConfig:
    """Docker sandbox configuration."""

    enabled: bool = True
    base_image: str = "node:20-alpine"
    memory_limit: str = "512m"
    cpu_limit: float = 1.0
    internal_port: int = 5173
    host_port_start: int = 30000
    host_port_end: int = 31000
    workspace_mount: str = "/app"
    docker_host: str = "unix:///var/run/docker.sock"
    container_name_prefix: str = "smartark-sandbox"
    setup_timeout: int = 120
    build_timeout: int = 300
    health_check_timeout: int = 60
    health_check_interval: int = 3

    @classmethod
    def from_env(cls) -> SandboxConfig:
        return cls(
            enabled=os.getenv("DEEPAGENT_SANDBOX_ENABLED", "true").lower() == "true",
            base_image=os.getenv("DEEPAGENT_SANDBOX_IMAGE", "node:20-alpine"),
            memory_limit=os.getenv("DEEPAGENT_SANDBOX_MEMORY", "512m"),
            cpu_limit=float(os.getenv("DEEPAGENT_SANDBOX_CPUS", "1.0")),
            internal_port=int(os.getenv("DEEPAGENT_SANDBOX_PORT", "5173")),
            host_port_start=int(os.getenv("DEEPAGENT_SANDBOX_PORT_START", "30000")),
            host_port_end=int(os.getenv("DEEPAGENT_SANDBOX_PORT_END", "31000")),
            docker_host=os.getenv("DEEPAGENT_DOCKER_HOST", "unix:///var/run/docker.sock"),
            build_timeout=int(os.getenv("DEEPAGENT_BUILD_TIMEOUT", "300")),
            health_check_timeout=int(os.getenv("DEEPAGENT_HEALTH_CHECK_TIMEOUT", "60")),
        )


@dataclass(frozen=True)
class CallbackConfig:
    """Java API Gateway callback configuration."""

    base_url: str = "http://localhost:8080"
    api_key: str = "smartark-internal"
    timeout: int = 30

    @classmethod
    def from_env(cls) -> CallbackConfig:
        return cls(
            base_url=os.getenv("DEEPAGENT_CALLBACK_BASE_URL", "http://localhost:8080"),
            api_key=os.getenv("DEEPAGENT_CALLBACK_API_KEY", "smartark-internal"),
            timeout=int(os.getenv("DEEPAGENT_CALLBACK_TIMEOUT", "30")),
        )


@dataclass(frozen=True)
class DeepAgentConfig:
    """Root configuration aggregating all sub-configs."""

    llm: LLMConfig = field(default_factory=LLMConfig)
    sandbox: SandboxConfig = field(default_factory=SandboxConfig)
    callback: CallbackConfig = field(default_factory=CallbackConfig)
    max_build_fix_rounds: int = 3
    max_quality_rewrite_rounds: int = 2
    quality_score_threshold: float = 0.66
    citation_coverage_threshold: float = 0.70

    @classmethod
    def from_env(cls) -> DeepAgentConfig:
        return cls(
            llm=LLMConfig.from_env(),
            sandbox=SandboxConfig.from_env(),
            callback=CallbackConfig.from_env(),
            max_build_fix_rounds=int(os.getenv("DEEPAGENT_MAX_BUILD_FIX_ROUNDS", "3")),
            max_quality_rewrite_rounds=int(os.getenv("DEEPAGENT_MAX_QUALITY_REWRITE_ROUNDS", "2")),
            quality_score_threshold=float(os.getenv("DEEPAGENT_QUALITY_THRESHOLD", "0.66")),
            citation_coverage_threshold=float(os.getenv("DEEPAGENT_CITATION_THRESHOLD", "0.70")),
        )
