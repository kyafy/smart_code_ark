"""Adaptive reranking middleware — adjusts RAG rerank weights by discipline.

Intercepts rag_retrieve tool calls and injects discipline-appropriate
reranking weights, replacing the fixed 0.70/0.15/0.15 formula.
"""

from __future__ import annotations

import logging
import re
from typing import Any, Callable, Dict

logger = logging.getLogger(__name__)

# Discipline classification keywords
_HUMANITIES_KEYWORDS = {
    "文学", "历史", "哲学", "法学", "语言", "艺术", "教育", "社会学",
    "心理学", "政治", "传播", "新闻", "管理",
    "literature", "history", "philosophy", "law", "linguistics",
    "art", "education", "sociology", "psychology", "politics",
    "communication", "journalism", "management",
}

_STEM_KEYWORDS = {
    "计算机", "软件", "人工智能", "机器学习", "深度学习", "数据科学",
    "物理", "化学", "生物", "数学", "统计", "工程", "电子", "通信",
    "cs", "ai", "computer", "machine learning", "deep learning",
    "physics", "chemistry", "biology", "math", "statistics",
    "engineering", "electronics", "data science",
}

_MEDICAL_KEYWORDS = {
    "医学", "临床", "药学", "护理", "公共卫生", "生物医学",
    "medicine", "clinical", "pharmacy", "nursing", "biomedical",
    "public health", "epidemiology",
}

# Reranking weight profiles per discipline category
_WEIGHT_PROFILES: Dict[str, Dict[str, float]] = {
    "humanities": {"vector": 0.80, "citation": 0.05, "recency": 0.15},
    "stem": {"vector": 0.60, "citation": 0.15, "recency": 0.25},
    "medical": {"vector": 0.55, "citation": 0.20, "recency": 0.25},
    "default": {"vector": 0.70, "citation": 0.15, "recency": 0.15},
}


class AdaptiveRerankMiddleware:
    """Middleware that adjusts RAG reranking weights based on discipline.

    Integration:
        Implements wrap_tool_call to intercept rag_retrieve calls
        and inject discipline-appropriate reranking weights.

    Weight profiles:
        - Humanities: High vector similarity (0.80), low citation weight (0.05)
          because humanities papers rarely have high citation counts.
        - STEM: Balanced with higher recency weight (0.25) because
          STEM fields evolve quickly.
        - Medical: High citation + recency weights (0.20 + 0.25)
          because clinical evidence matters most.
        - Default: Original weights (0.70 / 0.15 / 0.15).
    """

    async def wrap_tool_call(
        self,
        request: Any,
        handler: Callable,
    ) -> Any:
        """Inject adaptive weights into rag_retrieve calls."""
        tool_name = getattr(request, "tool_name", "") or ""

        if tool_name != "rag_retrieve":
            return await handler(request)

        args = getattr(request, "args", {}) or {}
        discipline = args.get("discipline", "")

        # Classify discipline and inject weights
        category = classify_discipline(discipline)
        weights = _WEIGHT_PROFILES[category]

        # Inject weights into the request args
        if hasattr(request, "args") and isinstance(request.args, dict):
            request.args["weights"] = weights

        logger.info(
            "AdaptiveRerank: discipline=%s → category=%s, weights=%s",
            discipline, category, weights,
        )

        return await handler(request)


def classify_discipline(discipline: str) -> str:
    """Classify a discipline string into a category."""
    if not discipline:
        return "default"

    lower = discipline.lower()

    if any(kw in lower for kw in _MEDICAL_KEYWORDS):
        return "medical"
    if any(kw in lower for kw in _HUMANITIES_KEYWORDS):
        return "humanities"
    if any(kw in lower for kw in _STEM_KEYWORDS):
        return "stem"

    return "default"
