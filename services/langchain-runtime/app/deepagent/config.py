"""DeepAgent configuration loaded from environment variables."""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from typing import Dict, Optional


@dataclass(frozen=True)
class LLMConfig:
    """LLM provider configuration."""

    base_url: str = ""
    api_key: str = ""
    model: str = "qwen-plus"
    temperature: float = 0.2
    max_tokens: int = 8192
    # Phase 1: direct codegen settings
    direct_enabled: bool = True    # DEEPAGENT_LLM_DIRECT_ENABLED
    codegen_timeout: int = 90      # DEEPAGENT_LLM_CODEGEN_TIMEOUT (seconds per file)
    codegen_concurrency: int = 5   # DEEPAGENT_LLM_CONCURRENCY (max parallel files)
    paper_expand_timeout: int = 180  # DEEPAGENT_LLM_PAPER_EXPAND_TIMEOUT (seconds per section)
    paper_retry_max: int = 3       # DEEPAGENT_LLM_PAPER_RETRY_MAX

    @classmethod
    def from_env(cls) -> LLMConfig:
        return cls(
            base_url=os.getenv("LANGCHAIN_MODEL_BASE_URL", ""),
            api_key=os.getenv("LANGCHAIN_MODEL_API_KEY", ""),
            model=os.getenv("LANGCHAIN_MODEL_NAME", "qwen-plus"),
            temperature=float(os.getenv("LANGCHAIN_MODEL_TEMPERATURE", "0.2")),
            max_tokens=int(os.getenv("LANGCHAIN_MODEL_MAX_TOKENS", "8192")),
            direct_enabled=os.getenv("DEEPAGENT_LLM_DIRECT_ENABLED", "true").lower() == "true",
            codegen_timeout=int(os.getenv("DEEPAGENT_LLM_CODEGEN_TIMEOUT", "90")),
            codegen_concurrency=int(os.getenv("DEEPAGENT_LLM_CONCURRENCY", "5")),
            paper_expand_timeout=int(os.getenv("DEEPAGENT_LLM_PAPER_EXPAND_TIMEOUT", "180")),
            paper_retry_max=int(os.getenv("DEEPAGENT_LLM_PAPER_RETRY_MAX", "3")),
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
class NodeModelConfig:
    """Per-node LLM model overrides via DEEPAGENT_NODE_MODEL_{NODE_NAME} env vars.

    Example:
        DEEPAGENT_NODE_MODEL_BUILD_FIX=qwen-max
        DEEPAGENT_NODE_MODEL_REQUIREMENT_ANALYZE=qwen-max
    """

    overrides: Dict[str, str] = field(default_factory=dict)

    @classmethod
    def from_env(cls) -> NodeModelConfig:
        prefix = "DEEPAGENT_NODE_MODEL_"
        overrides: Dict[str, str] = {}
        for key, val in os.environ.items():
            if key.startswith(prefix) and val.strip():
                node_name = key[len(prefix):].lower()
                overrides[node_name] = val.strip()
        return cls(overrides=overrides)

    def get_model(self, node_name: str) -> Optional[str]:
        """Return the model override for a node, or None to use the default."""
        return self.overrides.get(node_name)


@dataclass(frozen=True)
class DeepAgentConfig:
    """Root configuration aggregating all sub-configs."""

    llm: LLMConfig = field(default_factory=LLMConfig)
    sandbox: SandboxConfig = field(default_factory=SandboxConfig)
    callback: CallbackConfig = field(default_factory=CallbackConfig)
    # --- simple task fix limits (file_count <= complexity_file_threshold) ---
    max_build_fix_rounds: int = 3
    max_smoke_fix_rounds: int = 2
    max_total_fix_rounds: int = 4       # combined cap for build_fix + smoke_fix
    # --- complex task fix limits (file_count > complexity_file_threshold) ---
    max_build_fix_rounds_complex: int = 5
    max_smoke_fix_rounds_complex: int = 3
    max_total_fix_rounds_complex: int = 7
    # --- threshold to distinguish simple vs complex ---
    complexity_file_threshold: int = 50  # file_plan count above this = complex
    max_quality_rewrite_rounds: int = 2
    quality_score_threshold: float = 0.66
    citation_coverage_threshold: float = 0.70
    # --- memory settings (Phase 4) ---
    memory_short_term_limit: int = 8   # max checkpoint entries to load
    memory_long_term_limit: int = 8    # max long-term patterns to load
    memory_context_max_chars: int = 4000  # max assembled context size
    # --- compile check settings (Phase 5) ---
    max_compile_check_rounds: int = 2     # DEEPAGENT_MAX_COMPILE_CHECK_ROUNDS
    compile_check_timeout: int = 60       # DEEPAGENT_COMPILE_CHECK_TIMEOUT (seconds)
    # --- auto-heal & golden config (Phase 6) ---
    auto_heal_enabled: bool = True      # DEEPAGENT_AUTO_HEAL_ENABLED
    golden_config_enabled: bool = True  # DEEPAGENT_GOLDEN_CONFIG_ENABLED
    # --- fixture test mode (Phase 6) ---
    fixture_mode: bool = False          # DEEPAGENT_FIXTURE_MODE
    fixture_dir: str = ""               # DEEPAGENT_FIXTURE_DIR
    save_fixture: bool = False          # DEEPAGENT_SAVE_FIXTURE
    # --- smart retry settings (Phase 4) ---
    smart_retry_max_retries: int = 2
    smart_retry_backoff_base: float = 1.0

    def fix_limits(self, file_count: int) -> tuple:
        """Return (max_build, max_smoke, max_total) based on task complexity.

        If file_count > complexity_file_threshold, use the complex tier;
        otherwise use the simple tier.
        """
        if file_count > self.complexity_file_threshold:
            return (self.max_build_fix_rounds_complex,
                    self.max_smoke_fix_rounds_complex,
                    self.max_total_fix_rounds_complex)
        return (self.max_build_fix_rounds,
                self.max_smoke_fix_rounds,
                self.max_total_fix_rounds)

    @classmethod
    def from_env(cls) -> DeepAgentConfig:
        return cls(
            llm=LLMConfig.from_env(),
            sandbox=SandboxConfig.from_env(),
            callback=CallbackConfig.from_env(),
            max_build_fix_rounds=int(os.getenv("DEEPAGENT_MAX_BUILD_FIX_ROUNDS", "3")),
            max_smoke_fix_rounds=int(os.getenv("DEEPAGENT_MAX_SMOKE_FIX_ROUNDS", "2")),
            max_total_fix_rounds=int(os.getenv("DEEPAGENT_MAX_TOTAL_FIX_ROUNDS", "4")),
            max_build_fix_rounds_complex=int(os.getenv("DEEPAGENT_MAX_BUILD_FIX_ROUNDS_COMPLEX", "5")),
            max_smoke_fix_rounds_complex=int(os.getenv("DEEPAGENT_MAX_SMOKE_FIX_ROUNDS_COMPLEX", "3")),
            max_total_fix_rounds_complex=int(os.getenv("DEEPAGENT_MAX_TOTAL_FIX_ROUNDS_COMPLEX", "7")),
            complexity_file_threshold=int(os.getenv("DEEPAGENT_COMPLEXITY_FILE_THRESHOLD", "50")),
            max_quality_rewrite_rounds=int(os.getenv("DEEPAGENT_MAX_QUALITY_REWRITE_ROUNDS", "2")),
            quality_score_threshold=float(os.getenv("DEEPAGENT_QUALITY_THRESHOLD", "0.66")),
            citation_coverage_threshold=float(os.getenv("DEEPAGENT_CITATION_THRESHOLD", "0.70")),
            memory_short_term_limit=int(os.getenv("DEEPAGENT_MEMORY_SHORT_TERM_LIMIT", "8")),
            memory_long_term_limit=int(os.getenv("DEEPAGENT_MEMORY_LONG_TERM_LIMIT", "8")),
            memory_context_max_chars=int(os.getenv("DEEPAGENT_MEMORY_CONTEXT_MAX_CHARS", "4000")),
            max_compile_check_rounds=int(os.getenv("DEEPAGENT_MAX_COMPILE_CHECK_ROUNDS", "2")),
            compile_check_timeout=int(os.getenv("DEEPAGENT_COMPILE_CHECK_TIMEOUT", "60")),
            auto_heal_enabled=os.getenv("DEEPAGENT_AUTO_HEAL_ENABLED", "true").lower() == "true",
            golden_config_enabled=os.getenv("DEEPAGENT_GOLDEN_CONFIG_ENABLED", "true").lower() == "true",
            fixture_mode=os.getenv("DEEPAGENT_FIXTURE_MODE", "false").lower() == "true",
            fixture_dir=os.getenv("DEEPAGENT_FIXTURE_DIR", ""),
            save_fixture=os.getenv("DEEPAGENT_SAVE_FIXTURE", "false").lower() == "true",
            smart_retry_max_retries=int(os.getenv("DEEPAGENT_SMART_RETRY_MAX", "2")),
            smart_retry_backoff_base=float(os.getenv("DEEPAGENT_SMART_RETRY_BACKOFF", "1.0")),
        )
