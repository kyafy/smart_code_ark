"""Citation trace middleware — ensures paper sections have adequate references.

Intercepts manuscript section writes and checks that claims are
backed by cited sources, preventing unsupported assertions.
"""

from __future__ import annotations

import logging
import re
from typing import Any, Callable, List

logger = logging.getLogger(__name__)

# Patterns that indicate a claim needing citation
_CLAIM_PATTERNS = [
    re.compile(r"研究表明|研究发现|实验证明|数据显示|调查发现", re.IGNORECASE),
    re.compile(r"studies show|research indicates|evidence suggests|data shows", re.IGNORECASE),
    re.compile(r"根据.{2,20}(的研究|的理论|的方法|的模型)", re.IGNORECASE),
    re.compile(r"(已被证实|广泛认为|普遍认为|公认)", re.IGNORECASE),
    re.compile(r"(提出了|发展了|建立了|构建了).{2,30}(模型|框架|理论|方法)", re.IGNORECASE),
    re.compile(r"\d+%\s*(的|用户|受访者|样本)", re.IGNORECASE),
]

# Patterns that match citation markers
_CITATION_PATTERNS = [
    re.compile(r"\[\d+\]"),                    # [1], [12]
    re.compile(r"\[[\d,\s]+\]"),               # [1,2,3] or [1, 2]
    re.compile(r"\(\w+,?\s*\d{4}\)"),          # (Author, 2024)
    re.compile(r"（\w+[,，]?\s*\d{4}）"),       # Chinese brackets
]


class CitationTraceMiddleware:
    """Middleware that enforces citation coverage in paper writing.

    Checks that sections written to /manuscript/ paths have
    sufficient citation markers relative to the number of
    claims made.

    Integration:
        Implements wrap_tool_call to intercept write_file calls
        targeting manuscript content.
    """

    def __init__(self, min_coverage: float = 0.70) -> None:
        self._min_coverage = min_coverage

    async def wrap_tool_call(
        self,
        request: Any,
        handler: Callable,
    ) -> Any:
        """Intercept manuscript writes and check citation coverage."""
        result = await handler(request)

        tool_name = getattr(request, "tool_name", "") or ""
        if tool_name != "write_file":
            return result

        args = getattr(request, "args", {}) or {}
        path = args.get("path", "")
        content = args.get("content", "")

        # Only check manuscript files
        if "/manuscript/" not in path and "/paper/" not in path:
            return result
        if not content or len(content) < 100:
            return result

        claims = self._extract_claims(content)
        citations = self._extract_citations(content)

        if not claims:
            return result  # no claims to check

        # Check coverage: what fraction of claims have nearby citations
        covered = self._count_covered_claims(content, claims, citations)
        coverage = covered / len(claims) if claims else 1.0

        if coverage < self._min_coverage:
            uncovered = [c for c in claims if not self._has_nearby_citation(content, c)]
            logger.warning(
                "Citation coverage %.0f%% < %.0f%% for %s (%d/%d claims covered)",
                coverage * 100, self._min_coverage * 100, path, covered, len(claims),
            )
            return {
                "rejected": True,
                "message": (
                    f"Citation coverage {coverage:.0%} is below threshold "
                    f"{self._min_coverage:.0%}. "
                    f"{len(claims) - covered} claims lack citations. "
                    f"Uncovered claims:\n"
                    + "\n".join(f"- {c[:80]}" for c in uncovered[:5])
                    + "\nPlease add citation markers [n] near these claims."
                ),
            }

        return result

    def _extract_claims(self, content: str) -> List[str]:
        """Find sentences that make claims requiring citation."""
        claims = []
        sentences = re.split(r"[。.!！?？\n]", content)
        for sent in sentences:
            sent = sent.strip()
            if not sent or len(sent) < 10:
                continue
            if any(p.search(sent) for p in _CLAIM_PATTERNS):
                claims.append(sent)
        return claims

    def _extract_citations(self, content: str) -> List[str]:
        """Find all citation markers in the content."""
        citations = []
        for pattern in _CITATION_PATTERNS:
            citations.extend(pattern.findall(content))
        return citations

    def _count_covered_claims(
        self, content: str, claims: List[str], citations: List[str]
    ) -> int:
        """Count claims that have a citation within proximity."""
        return sum(1 for c in claims if self._has_nearby_citation(content, c))

    def _has_nearby_citation(self, content: str, claim: str) -> bool:
        """Check if a claim has a citation within ~200 chars."""
        idx = content.find(claim[:30])
        if idx < 0:
            return False
        # Look for citation within 200 chars after the claim
        window = content[idx:idx + len(claim) + 200]
        return any(p.search(window) for p in _CITATION_PATTERNS)
