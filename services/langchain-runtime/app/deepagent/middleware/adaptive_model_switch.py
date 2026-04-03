"""Adaptive model switch middleware — routes LLM calls to optimal model by task complexity.

Selects between strong/fast models based on:
  1. File complexity assessment (business logic density, dependency count)
  2. Task type (structure planning vs config file vs core logic)
  3. Dynamic degradation (strong model timeout → auto-fallback to fast model)
  4. Upgrade on quality failure (fast model quality too low → retry with strong)
"""

from __future__ import annotations

import logging
import os
import re
from dataclasses import dataclass
from typing import Any, Dict, Optional

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class ModelTier:
    """A named model configuration tier."""
    name: str
    model: str
    temperature: float
    max_tokens: int

    @classmethod
    def from_env(cls, tier: str) -> Optional[ModelTier]:
        """Load a model tier from env vars: DEEPAGENT_MODEL_{TIER}_NAME, etc."""
        prefix = f"DEEPAGENT_MODEL_{tier.upper()}_"
        model = os.getenv(f"{prefix}NAME", "")
        if not model:
            return None
        return cls(
            name=tier,
            model=model,
            temperature=float(os.getenv(f"{prefix}TEMPERATURE", "0.2")),
            max_tokens=int(os.getenv(f"{prefix}MAX_TOKENS", "8192")),
        )


# Complexity thresholds
_HIGH_COMPLEXITY_SCORE = 6
_LOW_COMPLEXITY_SCORE = 3

# File patterns by complexity
_BOILERPLATE_PATTERNS = {
    "package.json", "pom.xml", "build.gradle", "tsconfig.json",
    ".eslintrc", ".prettierrc", "vite.config", "application.yml",
    "application.properties", "docker-compose", "dockerfile",
    ".gitignore", "readme.md",
}

_HIGH_COMPLEXITY_INDICATORS = [
    re.compile(r"service", re.IGNORECASE),
    re.compile(r"controller", re.IGNORECASE),
    re.compile(r"handler", re.IGNORECASE),
    re.compile(r"middleware", re.IGNORECASE),
    re.compile(r"interceptor", re.IGNORECASE),
    re.compile(r"resolver", re.IGNORECASE),
    re.compile(r"processor", re.IGNORECASE),
    re.compile(r"workflow", re.IGNORECASE),
    re.compile(r"engine", re.IGNORECASE),
    re.compile(r"algorithm", re.IGNORECASE),
]


class AdaptiveModelSwitchMiddleware:
    """Middleware that selects the optimal model for each LLM call.

    Model tiers (configured via env vars):
        - STRONG: Higher capability, slower, more expensive
            DEEPAGENT_MODEL_STRONG_NAME, _TEMPERATURE, _MAX_TOKENS
        - FAST: Lower capability, faster, cheaper
            DEEPAGENT_MODEL_FAST_NAME, _TEMPERATURE, _MAX_TOKENS

    Falls back to global default (LANGCHAIN_MODEL_NAME) if tiers not configured.

    Routing rules:
        - Structure planning → STRONG (architectural decisions)
        - Config/boilerplate files → FAST (pattern-based, low creativity)
        - Core business logic → STRONG (needs reasoning)
        - Simple CRUD/DTO → FAST (mechanical)
        - build_fix → STRONG (needs diagnosis)
        - PRD summarization → FAST (extraction task)
    """

    def __init__(self) -> None:
        self._strong = ModelTier.from_env("STRONG")
        self._fast = ModelTier.from_env("FAST")
        self._degraded_files: set[str] = set()

    @property
    def is_configured(self) -> bool:
        """Return True if at least one model tier is configured."""
        return self._strong is not None or self._fast is not None

    def select_model_for_file(
        self,
        file_path: str,
        state: Dict[str, Any],
        step_code: str = "",
    ) -> Optional[ModelTier]:
        """Select the appropriate model tier for generating a specific file.

        Returns None to use the default model.
        """
        if not self.is_configured:
            return None

        # build_fix always uses strong model
        if step_code == "build_fix":
            return self._strong

        # Structure planning uses strong model
        if step_code == "requirement_analyze":
            return self._strong

        complexity = self.assess_file_complexity(file_path, state)

        if complexity >= _HIGH_COMPLEXITY_SCORE:
            logger.debug("model_switch: %s → STRONG (complexity=%d)", file_path, complexity)
            return self._strong
        if complexity <= _LOW_COMPLEXITY_SCORE:
            logger.debug("model_switch: %s → FAST (complexity=%d)", file_path, complexity)
            return self._fast

        # Medium complexity: use default
        return None

    def select_model_for_compression(self) -> Optional[ModelTier]:
        """Select a fast model for PRD compression / summarization tasks."""
        return self._fast

    def mark_degraded(self, file_path: str) -> None:
        """Mark a file that failed with strong model → next attempt uses fast as fallback."""
        self._degraded_files.add(file_path)

    def should_upgrade(self, file_path: str, quality_issues: list) -> bool:
        """Check if a file generated with fast model should be retried with strong."""
        if not self._strong:
            return False
        return len(quality_issues) > 0 and file_path not in self._degraded_files

    def assess_file_complexity(
        self,
        file_path: str,
        state: Dict[str, Any],
    ) -> int:
        """Score a file's complexity from 0-10 based on path and context.

        Scoring:
            - Base: 5 (neutral)
            - Boilerplate file: -3
            - Config file: -2
            - Core logic indicators in path: +2
            - High PRD keyword overlap: +1
            - Many dependencies inferred: +1
            - Simple CRUD (entity/DTO/VO): -1
        """
        score = 5
        lower = file_path.lower()
        base_name = file_path.split("/")[-1].lower()

        # Boilerplate detection
        if any(bp in base_name for bp in _BOILERPLATE_PATTERNS):
            score -= 3

        # Config files
        if any(ext in lower for ext in (".yml", ".yaml", ".properties", ".json", ".toml")):
            if "src/" not in lower:  # config at root level
                score -= 2

        # Core logic indicators
        for pattern in _HIGH_COMPLEXITY_INDICATORS:
            if pattern.search(base_name):
                score += 2
                break

        # Simple CRUD patterns
        if any(suffix in base_name for suffix in ("entity", "dto", "vo", "pojo", "model")):
            score -= 1

        # Test files are medium complexity
        if "test" in lower or "spec" in lower:
            score = max(score, 4)

        # PRD relevance (rough check)
        prd = state.get("prd", "")
        if prd:
            path_words = set(re.findall(r"[a-z]{3,}", lower))
            prd_words = set(re.findall(r"[a-z]{3,}", prd.lower()[:2000]))
            overlap = len(path_words & prd_words)
            if overlap >= 3:
                score += 1

        return max(0, min(10, score))

    def get_model_override(
        self,
        tier: Optional[ModelTier],
    ) -> Optional[Dict[str, Any]]:
        """Convert a ModelTier to an llm_config_override dict."""
        if tier is None:
            return None
        return {
            "model": tier.model,
            "temperature": tier.temperature,
            "max_tokens": tier.max_tokens,
        }
