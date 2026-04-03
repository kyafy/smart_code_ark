"""Direct LLM client for code generation — bypasses Java model proxy.

Prompts are ported verbatim from Java ModelService.java (lines 366-398)
to ensure output quality parity with the legacy Java path.
"""

from __future__ import annotations

import asyncio
import json
import logging
import re
from typing import Any, Dict, List, Optional

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Prompt constants (ported from ModelService.java:366-398)
# ---------------------------------------------------------------------------

_STRUCTURE_SYSTEM = (
    "你是一个资深架构师。请根据以下PRD和技术栈，规划项目的完整文件结构。\n"
    "技术栈：后端 {stack_backend}，前端 {stack_frontend}，数据库 {stack_db}。\n"
    "请输出一个JSON数组，包含所有需要生成的文件路径（相对路径）。\n"
    '例如：["README.md", "backend/pom.xml", "frontend/package.json", ...]\n'
    "请确保结构合理，包含必要的配置文件、代码文件和部署文件（Dockerfile, docker-compose.yml）。\n"
    "只输出JSON数组，不要包含Markdown标记或其他文字。"
)

_STRUCTURE_USER = "PRD内容：\n{prd}\n\n额外指令：\n{instructions}"

_FILE_SYSTEM = (
    "你是经验丰富的全栈工程师。请根据 PRD、技术栈和目标文件路径生成完整文件内容。\n"
    "文件路径：{file_path}\n"
    "技术栈：{tech_stack}\n"
    "要求：\n"
    "1. 输出完整可运行实现，不要只写骨架、TODO、占位符或伪代码。\n"
    "2. 把 PRD 中的业务对象、字段、流程和约束真正落实到代码里。\n"
    "3. 代码要优先保证可读性：命名清晰、层次分明、控制流程直观。\n"
    "4. 只在复杂逻辑、边界条件、关键业务规则前添加必要注释，避免逐行废话式注释。\n"
    "5. 如果是接口层，要体现参数校验、错误处理和返回结构；如果是数据层，要体现字段、约束和必要关系。\n"
    "6. 直接输出文件内容，不要输出 Markdown 代码块，除非目标文件本身就是 Markdown。\n"
    "项目文件结构（{current_group}组）：\n{project_structure}\n"
    "如果目标文件是代码文件，请确保结果可以直接编译或运行。"
)

_FILE_USER = (
    "PRD 内容：\n{prd}\n\n"
    "额外指令：\n{instructions}\n\n"
    "输出要求：\n"
    "1. 至少体现 2 个明确的业务字段、业务规则或业务状态。\n"
    "2. 对不直观的实现补充简洁注释，帮助后续维护者快速理解意图。\n"
    "3. 对简单赋值、简单 getter/setter、显而易见的框架样板不要滥加注释。\n"
    "4. 如果是接口层，补齐参数校验、错误分支和合理返回。\n"
    "5. 如果是数据层，补齐实体字段、约束、关系或迁移语义。\n"
    "6. 不要输出空文件或只有脚手架意味的模板代码。"
)


def _strip_markdown_fence(text: str) -> str:
    """Remove ```lang ... ``` fences if the model added them despite instructions."""
    text = text.strip()
    m = re.match(r"^```[^\n]*\n([\s\S]*?)```\s*$", text)
    if m:
        return m.group(1)
    return text


class LLMCodegenClient:
    """Calls the LLM directly for code generation, replacing the Java model proxy.

    Uses the same prompts as Java ModelService so generation quality is
    identical.  The key difference is that multiple files are generated
    concurrently (bounded by a semaphore) instead of sequentially through
    an HTTP round-trip to Java.
    """

    def __init__(self, base_url: str, api_key: str, model: str,
                 temperature: float = 0.2, max_tokens: int = 8192,
                 codegen_timeout: int = 90, codegen_concurrency: int = 5) -> None:
        self._base_url = base_url
        self._api_key = api_key
        self._model = model
        self._temperature = temperature
        self._max_tokens = max_tokens
        self._timeout = codegen_timeout
        self._sem = asyncio.Semaphore(codegen_concurrency)
        self._llm: Any = None  # lazy-init ChatOpenAI

    def _get_llm(self) -> Any:
        if self._llm is None:
            from langchain_openai import ChatOpenAI
            self._llm = ChatOpenAI(
                base_url=self._base_url,
                api_key=self._api_key,
                model=self._model,
                temperature=self._temperature,
                max_tokens=self._max_tokens,
            )
        return self._llm

    async def generate_project_structure(
        self,
        prd: str,
        stack: Dict[str, str],
        instructions: str = "",
    ) -> Dict[str, Any]:
        """Generate project file list via direct LLM call.

        Returns {"files": [list of relative paths]}.
        """
        from langchain_core.messages import HumanMessage, SystemMessage

        system_content = _STRUCTURE_SYSTEM.format(
            stack_backend=stack.get("backend", "springboot"),
            stack_frontend=stack.get("frontend", "vue3"),
            stack_db=stack.get("db", "mysql"),
        )
        user_content = _STRUCTURE_USER.format(
            prd=prd[:4000],
            instructions=instructions[:1000],
        )

        llm = self._get_llm()
        async with self._sem:
            response = await asyncio.wait_for(
                llm.ainvoke([SystemMessage(content=system_content), HumanMessage(content=user_content)]),
                timeout=self._timeout,
            )

        raw = response.content.strip() if hasattr(response, "content") else str(response)
        raw = _strip_markdown_fence(raw)

        try:
            files = json.loads(raw)
            if isinstance(files, list):
                return {"files": [str(f) for f in files]}
        except (json.JSONDecodeError, ValueError):
            logger.warning("LLMCodegenClient: failed to parse structure JSON, raw=%s", raw[:200])

        return {"files": []}

    async def generate_file_content(
        self,
        file_path: str,
        prd: str,
        tech_stack: str,
        project_structure: str,
        group: str = "backend",
        instructions: str = "",
    ) -> str:
        """Generate a single file's content via direct LLM call.

        Wrapped in a semaphore so callers can fire many coroutines concurrently
        without overwhelming the LLM API with too many simultaneous requests.
        """
        from langchain_core.messages import HumanMessage, SystemMessage

        system_content = _FILE_SYSTEM.format(
            file_path=file_path,
            tech_stack=tech_stack,
            current_group=group,
            project_structure=project_structure[:2000],
        )
        user_content = _FILE_USER.format(
            prd=prd[:3000],
            instructions=instructions[:1000],
        )

        llm = self._get_llm()
        async with self._sem:
            response = await asyncio.wait_for(
                llm.ainvoke([SystemMessage(content=system_content), HumanMessage(content=user_content)]),
                timeout=self._timeout,
            )

        raw = response.content.strip() if hasattr(response, "content") else str(response)
        return _strip_markdown_fence(raw)

    @classmethod
    def from_env(cls) -> Optional["LLMCodegenClient"]:
        """Build from environment variables.  Returns None if not configured."""
        from ..config import LLMConfig
        cfg = LLMConfig.from_env()
        if not cfg.base_url or not cfg.direct_enabled:
            return None
        return cls(
            base_url=cfg.base_url,
            api_key=cfg.api_key,
            model=cfg.model,
            temperature=cfg.temperature,
            max_tokens=cfg.max_tokens,
            codegen_timeout=cfg.codegen_timeout,
            codegen_concurrency=cfg.codegen_concurrency,
        )

    @classmethod
    def from_state(cls, state: Dict[str, Any]) -> Optional["LLMCodegenClient"]:
        """Build from graph state, applying per-task llm_config_override on top of env.

        Returns None when direct LLM is disabled or not configured.
        """
        from ..config import LLMConfig
        cfg = LLMConfig.from_env()
        if not cfg.base_url or not cfg.direct_enabled:
            return None

        override: Dict[str, Any] = state.get("llm_config_override") or {}

        return cls(
            base_url=str(override.get("base_url") or cfg.base_url),
            api_key=str(override.get("api_key") or cfg.api_key),
            model=str(override.get("model") or cfg.model),
            temperature=float(override.get("temperature") if override.get("temperature") is not None else cfg.temperature),
            max_tokens=int(override.get("max_tokens") or cfg.max_tokens),
            codegen_timeout=int(override.get("codegen_timeout") or cfg.codegen_timeout),
            codegen_concurrency=int(override.get("codegen_concurrency") or cfg.codegen_concurrency),
        )
