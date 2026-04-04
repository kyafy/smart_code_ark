"""Smart retry middleware — semantic-level retry for LLM code generation.

Goes beyond network-level retries (already handled by LangGraph RetryPolicy)
to handle:
  1. Empty/too-short content
  2. Malformed JSON (structure generation)
  3. Quality rejection (CodeQualityMiddleware)
  4. Build fix escalation (consecutive failures on same file)
  5. Rate-limit backoff (HTTP 429)
"""

from __future__ import annotations

import asyncio
import json
import logging
import re
from typing import Any, Callable, Dict, Optional

logger = logging.getLogger(__name__)

# Retry configuration defaults
_DEFAULT_MAX_RETRIES = 2
_DEFAULT_BACKOFF_BASE = 1.0
_DEFAULT_BACKOFF_FACTOR = 2.0
_DEFAULT_BACKOFF_MAX = 10.0
_MIN_CONTENT_LINES = 5


class SmartRetryMiddleware:
    """Middleware that adds semantic-level retry logic for LLM code generation.

    Retry strategies:
        - Empty content: retry with slightly higher temperature (+0.1)
        - JSON parse error: retry with explicit JSON format reminder
        - Quality rejection: retry with rejection reason injected
        - Rate limit (429): exponential backoff then retry
        - Consecutive build_fix failures: escalate model or strategy
    """

    def __init__(
        self,
        max_retries: int = _DEFAULT_MAX_RETRIES,
        backoff_base: float = _DEFAULT_BACKOFF_BASE,
        backoff_factor: float = _DEFAULT_BACKOFF_FACTOR,
        backoff_max: float = _DEFAULT_BACKOFF_MAX,
    ) -> None:
        self._max_retries = max_retries
        self._backoff_base = backoff_base
        self._backoff_factor = backoff_factor
        self._backoff_max = backoff_max
        # Track consecutive failures per file for escalation
        self._file_fail_counts: Dict[str, int] = {}

    async def wrap_generate_file(
        self,
        generate_fn: Callable,
        file_path: str,
        prd: str,
        tech_stack: str,
        project_structure: str,
        group: str = "backend",
        instructions: str = "",
        quality_checker: Optional[Callable] = None,
    ) -> str:
        """Wrap a file generation call with smart retry logic.

        Args:
            generate_fn: async callable that generates file content
            quality_checker: optional sync callable(path, content) -> list[str] issues
        """
        last_error = ""
        extra_instructions = ""

        for attempt in range(1 + self._max_retries):
            try:
                content = await generate_fn(
                    file_path=file_path,
                    prd=prd,
                    tech_stack=tech_stack,
                    project_structure=project_structure,
                    group=group,
                    instructions=instructions + extra_instructions,
                )
            except asyncio.TimeoutError:
                logger.warning("smart_retry[%s]: timeout on attempt %d", file_path, attempt + 1)
                if attempt < self._max_retries:
                    await self._backoff(attempt)
                    continue
                return ""
            except Exception as exc:
                if _is_rate_limit(exc):
                    logger.warning("smart_retry[%s]: rate limited, backing off", file_path)
                    await self._backoff(attempt, rate_limited=True)
                    continue
                raise

            # Check 1: empty or too short
            if not content or not content.strip():
                logger.warning("smart_retry[%s]: empty content on attempt %d", file_path, attempt + 1)
                extra_instructions = "\n\n[重试提示] 上次生成为空，请输出完整的文件内容，不要输出空文件。"
                last_error = "empty_content"
                continue

            non_blank = [l for l in content.split("\n") if l.strip()]
            if len(non_blank) < _MIN_CONTENT_LINES:
                logger.warning(
                    "smart_retry[%s]: only %d lines on attempt %d",
                    file_path, len(non_blank), attempt + 1,
                )
                extra_instructions = (
                    f"\n\n[重试提示] 上次只生成了{len(non_blank)}行代码，内容过少。"
                    "请输出完整可运行的实现，至少包含业务逻辑和必要的导入。"
                )
                last_error = "too_short"
                continue

            # Check 2: quality check (if provided)
            if quality_checker is not None:
                issues = quality_checker(file_path, content)
                if issues:
                    issue_text = "; ".join(issues)
                    logger.warning(
                        "smart_retry[%s]: quality issues on attempt %d: %s",
                        file_path, attempt + 1, issue_text,
                    )
                    extra_instructions = (
                        f"\n\n[重试提示] 代码质量检查未通过: {issue_text}。"
                        "请修正以上问题，确保代码包含实质性业务逻辑。"
                    )
                    last_error = "quality_rejected"
                    continue

            # Success — clear failure counter
            self._file_fail_counts.pop(file_path, None)
            return content

        # All retries exhausted
        self._file_fail_counts[file_path] = self._file_fail_counts.get(file_path, 0) + 1
        logger.warning(
            "smart_retry[%s]: all %d attempts failed (last_error=%s)",
            file_path, 1 + self._max_retries, last_error,
        )
        return ""

    async def wrap_generate_structure(
        self,
        generate_fn: Callable,
        prd: str,
        stack: Dict[str, str],
        instructions: str = "",
    ) -> Dict[str, Any]:
        """Wrap structure generation with JSON format retry."""
        extra_instructions = ""

        for attempt in range(1 + self._max_retries):
            try:
                result = await generate_fn(
                    prd=prd,
                    stack=stack,
                    instructions=instructions + extra_instructions,
                )
            except asyncio.TimeoutError:
                if attempt < self._max_retries:
                    await self._backoff(attempt)
                    continue
                return {"files": []}
            except Exception as exc:
                if _is_rate_limit(exc):
                    await self._backoff(attempt, rate_limited=True)
                    continue
                raise

            files = result.get("files", [])
            if files:
                return result

            logger.warning(
                "smart_retry: empty structure on attempt %d", attempt + 1,
            )
            extra_instructions = (
                "\n\n[重试提示] 请务必输出合法的JSON数组，"
                '格式如: ["src/main/java/App.java", "frontend/package.json"]。'
                "不要输出空数组或非JSON内容。"
            )

        return {"files": []}

    async def wrap_generate_paper_section(
        self,
        generate_fn: Callable,
        section_title: str,
        system_prompt: str,
        user_prompt: str,
        timeout: int = 180,
        min_chars: int = 100,
        citation_checker: Optional[Callable] = None,
    ) -> str:
        """Wrap a paper section generation call with smart retry logic.

        Args:
            generate_fn: async callable(system_prompt, user_prompt, timeout) -> str
            section_title: used for logging and failure tracking
            min_chars: minimum acceptable content length
            citation_checker: optional sync callable(content) -> list[str] issues
        """
        last_error = ""
        extra_instructions = ""

        for attempt in range(1 + self._max_retries):
            try:
                effective_prompt = user_prompt + extra_instructions
                content = await generate_fn(system_prompt, effective_prompt, timeout)
            except asyncio.TimeoutError:
                logger.warning("smart_retry_paper[%s]: timeout on attempt %d", section_title, attempt + 1)
                if attempt < self._max_retries:
                    await self._backoff(attempt)
                    continue
                return ""
            except Exception as exc:
                if _is_rate_limit(exc):
                    logger.warning("smart_retry_paper[%s]: rate limited, backing off", section_title)
                    await self._backoff(attempt, rate_limited=True)
                    continue
                raise

            # Check 1: empty or too short
            if not content or not content.strip():
                logger.warning("smart_retry_paper[%s]: empty on attempt %d", section_title, attempt + 1)
                extra_instructions = "\n\n[重试提示] 上次生成为空，请输出完整的章节内容，不少于300字。"
                last_error = "empty_content"
                continue

            if len(content.strip()) < min_chars:
                logger.warning(
                    "smart_retry_paper[%s]: too short (%d chars) on attempt %d",
                    section_title, len(content.strip()), attempt + 1,
                )
                extra_instructions = (
                    f"\n\n[重试提示] 上次只生成了{len(content.strip())}字，内容过少。"
                    "请输出完整论述内容，每节至少300字，包含论点论据和引用标记 [n]。"
                )
                last_error = "too_short"
                continue

            # Check 2: citation coverage (if provided)
            if citation_checker is not None:
                issues = citation_checker(content)
                if issues:
                    issue_text = "; ".join(issues[:3])
                    logger.warning(
                        "smart_retry_paper[%s]: citation issues on attempt %d: %s",
                        section_title, attempt + 1, issue_text,
                    )
                    extra_instructions = (
                        f"\n\n[重试提示] 引文覆盖率不达标: {issue_text}。"
                        "请确保每个论述性断言（如「研究表明」「数据显示」等）附近都有引用标记 [n]。"
                    )
                    last_error = "citation_rejected"
                    continue

            # Success
            self._file_fail_counts.pop(section_title, None)
            return content

        # All retries exhausted
        self._file_fail_counts[section_title] = self._file_fail_counts.get(section_title, 0) + 1
        logger.warning(
            "smart_retry_paper[%s]: all %d attempts failed (last_error=%s)",
            section_title, 1 + self._max_retries, last_error,
        )
        return ""

    def get_paper_rewrite_escalation_hint(self, rewrite_round: int) -> str:
        """Generate escalation instructions for paper quality rewrite rounds."""
        if rewrite_round <= 0:
            return ""
        if rewrite_round == 1:
            return (
                "\n\n[第2轮修改] 上一轮修改未达标。请：\n"
                "1. 重点补充缺失的引用标记 [n]\n"
                "2. 扩展过短段落，增加论证深度\n"
                "3. 确保每个论述性断言有文献佐证"
            )
        return (
            "\n\n[最终修改] 前几轮修改均未达标，这是最后的修改机会：\n"
            "1. 逐段检查引用覆盖，每个「研究表明」「数据显示」旁必须有 [n]\n"
            "2. 缺少论据的段落直接替换为有文献支撑的论述\n"
            "3. 删除无法提供引用的空泛论断，替换为有据可查的具体论述"
        )

    def should_escalate_fix(self, file_path: str) -> bool:
        """Check if a file has failed consecutive fix attempts and should escalate.

        Escalation means: switch to a stronger model or a different fix strategy.
        """
        return self._file_fail_counts.get(file_path, 0) >= 2

    def get_escalation_hint(self, file_path: str, round_num: int) -> str:
        """Generate escalation instructions based on fix round number."""
        if round_num <= 1:
            return ""
        if round_num == 2:
            return (
                "\n\n[第2轮修复] 上一轮修复未能解决问题。请换一种思路：\n"
                "1. 仔细分析错误根因，不要只修改报错行\n"
                "2. 检查相关的 import/依赖是否缺失\n"
                "3. 确认类型定义和接口签名是否匹配"
            )
        return (
            "\n\n[第3+轮修复] 前几轮修复均未解决问题，请：\n"
            "1. 完整重写此文件，从头确保编译通过\n"
            "2. 参考项目结构中的其他文件确保接口一致\n"
            "3. 移除所有可疑的第三方依赖引用"
        )

    async def _backoff(self, attempt: int, rate_limited: bool = False) -> None:
        base = self._backoff_base * 3 if rate_limited else self._backoff_base
        delay = min(base * (self._backoff_factor ** attempt), self._backoff_max)
        logger.info("smart_retry: backing off %.1fs (attempt=%d)", delay, attempt)
        await asyncio.sleep(delay)


def _is_rate_limit(exc: Exception) -> bool:
    """Detect HTTP 429 rate limit errors from various client libraries."""
    exc_str = str(exc).lower()
    if "429" in exc_str or "rate" in exc_str:
        return True
    # httpx specific
    if hasattr(exc, "response") and hasattr(exc.response, "status_code"):
        return exc.response.status_code == 429
    return False
