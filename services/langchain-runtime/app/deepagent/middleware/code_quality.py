"""Code quality middleware — intercepts write_file calls to validate generated code.

Prevents writing empty-shell / placeholder / TODO-only code by checking
for business-logic indicators before allowing the file to be persisted.
"""

from __future__ import annotations

import logging
import re
from typing import Any, Callable, Dict, Optional

logger = logging.getLogger(__name__)

# File extensions that trigger quality checks
_CODE_EXTENSIONS = (".java", ".ts", ".tsx", ".vue", ".py", ".js", ".jsx")

# Patterns indicating empty-shell / placeholder code
_PLACEHOLDER_PATTERNS = [
    re.compile(r"//\s*TODO", re.IGNORECASE),
    re.compile(r"#\s*TODO", re.IGNORECASE),
    re.compile(r"pass\s*$", re.MULTILINE),
    re.compile(r"throw\s+new\s+UnsupportedOperationException", re.IGNORECASE),
    re.compile(r"NotImplementedError"),
    re.compile(r"raise\s+NotImplementedError"),
]

# Minimum non-blank lines for a file to be considered substantive
_MIN_SUBSTANTIVE_LINES = 5


class CodeQualityMiddleware:
    """Middleware that validates code quality on write_file tool calls.

    Integration with DeepAgent:
        This middleware is designed to be used as a custom middleware
        in the DeepAgent middleware stack.  It implements wrap_tool_call
        to intercept file write operations.

    Quality checks:
        1. File must have at least MIN_SUBSTANTIVE_LINES non-blank lines
        2. Placeholder ratio (TODO/pass/NotImplemented) must be < 30%
        3. Code files must contain at least one business-logic indicator
           (function def, class def, import, variable assignment, etc.)
    """

    def __init__(self, prd_keywords: Optional[list[str]] = None) -> None:
        self._prd_keywords = prd_keywords or []

    async def wrap_tool_call(
        self,
        request: Any,
        handler: Callable,
    ) -> Any:
        """Intercept write_file calls and validate code quality."""
        result = await handler(request)

        # Only check write_file for code files
        tool_name = getattr(request, "tool_name", "") or ""
        if tool_name != "write_file":
            return result

        args = getattr(request, "args", {}) or {}
        path = args.get("path", "")
        content = args.get("content", "")

        if not any(path.endswith(ext) for ext in _CODE_EXTENSIONS):
            return result

        issues = self._check_quality(path, content)
        if issues:
            issue_text = "; ".join(issues)
            logger.warning("Code quality check failed for %s: %s", path, issue_text)
            # Return rejection message so the agent can fix and retry
            return _make_rejection(
                f"Quality check failed for {path}: {issue_text}. "
                f"Please revise the code to include substantive business logic."
            )

        return result

    def _check_quality(self, path: str, content: str) -> list[str]:
        """Run quality checks and return list of issues (empty = pass)."""
        issues = []

        lines = content.split("\n")
        non_blank = [l for l in lines if l.strip()]

        # Check 1: minimum substantive lines
        if len(non_blank) < _MIN_SUBSTANTIVE_LINES:
            issues.append(
                f"Only {len(non_blank)} non-blank lines (minimum {_MIN_SUBSTANTIVE_LINES})"
            )

        # Check 2: placeholder ratio
        placeholder_count = sum(
            1 for line in non_blank
            if any(p.search(line) for p in _PLACEHOLDER_PATTERNS)
        )
        if non_blank:
            ratio = placeholder_count / len(non_blank)
            if ratio > 0.3:
                issues.append(
                    f"Placeholder ratio {ratio:.0%} exceeds 30% "
                    f"({placeholder_count} TODO/pass/NotImplemented lines)"
                )

        # Check 3: must contain business logic indicators
        has_logic = any([
            re.search(r"(function|def|class|interface|export|import|const|let|var|public|private)\s", content),
            re.search(r"(if|for|while|switch|return|yield|await)\s", content),
            re.search(r"(@Controller|@Service|@Repository|@Component|@RestController)", content),
            re.search(r"(router\.|app\.|express\(|createApp|defineComponent)", content),
        ])
        if not has_logic and len(non_blank) > 3:
            issues.append("No business logic indicators found (no functions, classes, or control flow)")

        # Check 4: PRD keyword coverage (if keywords provided)
        if self._prd_keywords and len(non_blank) > 10:
            found = sum(1 for kw in self._prd_keywords if kw.lower() in content.lower())
            if found == 0 and len(self._prd_keywords) > 0:
                issues.append(
                    f"None of the PRD keywords found in code: {self._prd_keywords[:5]}"
                )

        return issues


def _make_rejection(message: str) -> Any:
    """Create a tool result indicating rejection."""
    # In DeepAgent, tool results are typically ToolMessage objects.
    # For now, return a dict that the node can interpret.
    return {"rejected": True, "message": message}
