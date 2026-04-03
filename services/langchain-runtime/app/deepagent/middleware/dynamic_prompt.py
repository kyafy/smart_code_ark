"""Dynamic prompt middleware — runtime prompt augmentation and hot-reload.

Enhances static prompts with:
  1. Tech-stack-specific instructions (Spring Boot, React, MyBatis, etc.)
  2. Fix-round progressive prompts (escalation by round number)
  3. Long-term memory feedback loop (inject high-frequency error avoidance tips)
  4. Quality-score-driven prompt strengthening/relaxation
  5. Hot-reload of prompt templates from Java prompt_versions table
"""

from __future__ import annotations

import logging
import time
from typing import Any, Dict, List, Optional

logger = logging.getLogger(__name__)

# TTL for cached prompt templates (seconds)
_TEMPLATE_CACHE_TTL = 300

# ----- Tech-stack-specific instruction fragments -----

_STACK_INSTRUCTIONS: Dict[str, str] = {
    "springboot": (
        "遵循 Spring Boot 分层架构：Controller → Service → Repository。\n"
        "使用 @RestController/@Service/@Repository 注解，"
        "确保 DTO 和 Entity 分离，Controller 只做参数校验和响应封装。\n"
        "数据库操作使用 Spring Data JPA 或 MyBatis，避免在 Controller 中直接写 SQL。"
    ),
    "vue3": (
        "使用 Vue 3 Composition API（setup + ref/reactive），不要使用 Options API。\n"
        "组件拆分粒度适中，使用 defineProps/defineEmits 约定接口。\n"
        "路由使用 vue-router 4.x，状态管理使用 Pinia。\n"
        "样式使用 scoped CSS 或 CSS Modules。"
    ),
    "react": (
        "使用 React 函数组件 + Hooks（useState, useEffect, useCallback）。\n"
        "避免 class 组件。使用 TypeScript 类型标注 props 和 state。\n"
        "状态管理优先使用 React Context + useReducer，复杂场景用 Zustand/Redux Toolkit。"
    ),
    "mybatis": (
        "使用 MyBatis Mapper 接口 + XML 映射文件。\n"
        "Mapper 接口方法名与 XML 中 id 一一对应。\n"
        "复杂查询使用 <where>/<if> 动态 SQL，避免字符串拼接。\n"
        "使用 @Param 注解标注多参数方法。"
    ),
    "mysql": (
        "DDL 使用 InnoDB 引擎，字符集 utf8mb4。\n"
        "主键使用 BIGINT AUTO_INCREMENT，时间字段使用 DATETIME。\n"
        "为常用查询字段建立索引，外键关系在应用层维护。"
    ),
    "postgresql": (
        "DDL 使用合适的 PostgreSQL 类型（SERIAL, TIMESTAMPTZ, JSONB 等）。\n"
        "利用 PostgreSQL 特性：JSONB 字段、数组类型、部分索引。"
    ),
    "typescript": (
        "所有文件使用严格 TypeScript（strict: true）。\n"
        "为 API 请求/响应定义接口类型，避免 any。\n"
        "使用 type 和 interface 区分：type 用于联合类型，interface 用于对象结构。"
    ),
}

# ----- Common error avoidance tips (populated from long-term memory analysis) -----

_DEFAULT_AVOIDANCE_TIPS: Dict[str, List[str]] = {
    "springboot": [
        "不要在 Entity 上同时使用 @Data 和手写 getter/setter，会导致 equals/hashCode 冲突",
        "Spring Boot 3.x 中 javax.* 已迁移到 jakarta.*，不要使用旧的 javax 包名",
        "确保 @Autowired 的 Bean 类有 @Component/@Service/@Repository 注解",
    ],
    "vue3": [
        "Vite 项目不支持 require()，使用 import 代替",
        "Vue 3 中不要使用 this.$refs，改用 ref() + template ref",
        "<script setup> 中不需要显式 return，直接声明的变量/函数自动暴露到模板",
    ],
    "react": [
        "useEffect 依赖数组不要遗漏依赖，否则会导致过期闭包",
        "不要在循环/条件中调用 Hooks",
    ],
}


class DynamicPromptMiddleware:
    """Middleware that dynamically augments LLM prompts based on context.

    Usage:
        mw = DynamicPromptMiddleware()
        enhanced_instructions = mw.enhance_instructions(
            base_instructions="...",
            state=state,
            file_path="src/main/java/UserService.java",
            step_code="codegen_backend",
        )
    """

    def __init__(self, java_client_factory=None) -> None:
        self._template_cache: Dict[str, Any] = {}
        self._template_cache_time: float = 0
        self._java_client_factory = java_client_factory

    def enhance_instructions(
        self,
        base_instructions: str,
        state: Dict[str, Any],
        file_path: str = "",
        step_code: str = "",
    ) -> str:
        """Enhance base instructions with dynamic context-aware additions.

        Layers (applied in order):
            1. Tech-stack-specific instructions
            2. Error avoidance tips from long-term memory
            3. Quality-score-driven constraints
            4. Fix-round progressive instructions
            5. Memory context injection
        """
        parts = [base_instructions]

        # Layer 1: tech-stack specialization
        stack_hints = self._get_stack_instructions(state, file_path)
        if stack_hints:
            parts.append(f"\n\n--- 技术栈规范 ---\n{stack_hints}")

        # Layer 2: error avoidance tips
        avoidance = self._get_avoidance_tips(state)
        if avoidance:
            parts.append(f"\n\n--- 常见错误提示 (请避免) ---\n{avoidance}")

        # Layer 3: quality-score-driven constraints
        quality_hint = self._quality_driven_hint(state)
        if quality_hint:
            parts.append(quality_hint)

        # Layer 4: fix-round progressive instructions
        fix_hint = self._fix_round_hint(state, step_code)
        if fix_hint:
            parts.append(fix_hint)

        # Layer 5: memory context (from MemoryBridge)
        memory_ctx = state.get("memory_context", "")
        if memory_ctx:
            parts.append(f"\n\n--- 上下文记忆 ---\n{memory_ctx[:1500]}")

        return "\n".join(parts)

    def enhance_system_prompt(
        self,
        base_system: str,
        state: Dict[str, Any],
        file_path: str = "",
    ) -> str:
        """Enhance the system prompt with stack-specific role and constraints."""
        stack_role = self._get_stack_role(state, file_path)
        if stack_role:
            return f"{base_system}\n\n{stack_role}"
        return base_system

    def _get_stack_instructions(self, state: Dict[str, Any], file_path: str) -> str:
        """Match stack config and file path to relevant instructions."""
        hints = []

        backend = (state.get("stack_backend", "") or "").lower()
        frontend = (state.get("stack_frontend", "") or "").lower()
        db = (state.get("stack_db", "") or "").lower()
        lower_path = file_path.lower()

        # Match by file type and stack
        if _is_backend_file(lower_path):
            for key in ("springboot", "spring boot", "spring-boot"):
                if key in backend:
                    hints.append(_STACK_INSTRUCTIONS.get("springboot", ""))
                    break
            if "mybatis" in backend:
                hints.append(_STACK_INSTRUCTIONS.get("mybatis", ""))

        if _is_frontend_file(lower_path):
            if "vue" in frontend:
                hints.append(_STACK_INSTRUCTIONS.get("vue3", ""))
            elif "react" in frontend:
                hints.append(_STACK_INSTRUCTIONS.get("react", ""))
            if "typescript" in frontend or lower_path.endswith((".ts", ".tsx")):
                hints.append(_STACK_INSTRUCTIONS.get("typescript", ""))

        if _is_db_file(lower_path):
            if "mysql" in db:
                hints.append(_STACK_INSTRUCTIONS.get("mysql", ""))
            elif "postgres" in db:
                hints.append(_STACK_INSTRUCTIONS.get("postgresql", ""))

        return "\n".join(h for h in hints if h)

    def _get_avoidance_tips(self, state: Dict[str, Any]) -> str:
        """Extract avoidance tips from long-term memory + defaults."""
        tips = []

        backend = (state.get("stack_backend", "") or "").lower()
        frontend = (state.get("stack_frontend", "") or "").lower()

        # Default tips by stack
        if "spring" in backend:
            tips.extend(_DEFAULT_AVOIDANCE_TIPS.get("springboot", []))
        if "vue" in frontend:
            tips.extend(_DEFAULT_AVOIDANCE_TIPS.get("vue3", []))
        elif "react" in frontend:
            tips.extend(_DEFAULT_AVOIDANCE_TIPS.get("react", []))

        # Extract failure patterns from long-term memories
        for memory in state.get("long_term_memories", []):
            if "[failure_pattern]" in memory.lower():
                # Extract the error description
                lines = memory.strip().split("\n")
                for line in lines:
                    if line.startswith("error:"):
                        tips.append(f"历史失败: {line[6:].strip()[:150]}")
                        break

        if not tips:
            return ""

        return "\n".join(f"- {tip}" for tip in tips[:8])

    def _quality_driven_hint(self, state: Dict[str, Any]) -> str:
        """Add constraints or relax based on recent quality score."""
        score = state.get("quality_score", 0.0)
        if not score:
            return ""

        if score < 0.6:
            return (
                "\n\n--- 质量强化要求 ---\n"
                "前一阶段代码质量评分较低，请特别注意：\n"
                "1. 每个函数/方法必须包含完整的业务逻辑实现\n"
                "2. 不允许出现 TODO、pass、NotImplementedError 等占位符\n"
                "3. 接口层必须有参数校验和错误处理\n"
                "4. 数据层必须有完整的字段定义和约束"
            )
        if score > 0.85:
            return (
                "\n\n[质量评分良好，可适当关注代码可维护性和扩展性]"
            )
        return ""

    def _fix_round_hint(self, state: Dict[str, Any], step_code: str) -> str:
        """Generate progressive fix instructions based on round number."""
        if step_code != "build_fix":
            return ""

        build_round = state.get("build_fix_round", 0)
        smoke_round = state.get("smoke_fix_round", 0)
        total_round = build_round + smoke_round

        if total_round <= 0:
            return ""

        if total_round == 1:
            return (
                "\n\n--- 修复策略 (第2轮) ---\n"
                "上一轮修复未能完全解决问题。请：\n"
                "1. 仔细分析错误根因，不要只修改报错行\n"
                "2. 检查 import/依赖是否缺失或版本不匹配\n"
                "3. 确认类型定义和接口签名是否与其他文件一致"
            )
        if total_round == 2:
            return (
                "\n\n--- 修复策略 (第3轮) ---\n"
                "已经过2轮修复仍未解决。请采取更彻底的方案：\n"
                "1. 考虑完整重写此文件\n"
                "2. 移除所有不确定的第三方依赖引用\n"
                "3. 简化实现逻辑，优先保证编译通过"
            )
        return (
            "\n\n--- 修复策略 (最终轮) ---\n"
            "这是最后的修复机会。请：\n"
            "1. 使用最简实现确保编译通过\n"
            "2. 移除所有非必要功能\n"
            "3. 确保基本框架代码能成功构建"
        )

    def _get_stack_role(self, state: Dict[str, Any], file_path: str) -> str:
        """Generate a stack-specific role enhancement for system prompt."""
        lower_path = file_path.lower()
        backend = (state.get("stack_backend", "") or "").lower()

        if _is_backend_file(lower_path) and "spring" in backend:
            return "你精通 Spring Boot / Spring MVC / MyBatis 等 Java 后端技术栈。"
        if _is_frontend_file(lower_path):
            frontend = (state.get("stack_frontend", "") or "").lower()
            if "vue" in frontend:
                return "你精通 Vue 3 Composition API、Vite、Pinia 等前端技术栈。"
            if "react" in frontend:
                return "你精通 React 18、TypeScript、React Router 等前端技术栈。"
        return ""


    # ==================================================================
    # Paper-specific prompt enhancement
    # ==================================================================

    def enhance_paper_instructions(
        self,
        base_instructions: str,
        state: Dict[str, Any],
        step_code: str = "",
        chapter_title: str = "",
        section_title: str = "",
    ) -> str:
        """Enhance instructions for paper generation nodes.

        Layers:
            1. Discipline-specific structure norms
            2. Degree-level depth calibration
            3. Chapter/section writing requirements
            4. Quality-rewrite targeted directives
            5. Memory context injection
        """
        parts = [base_instructions]

        # Layer 1: discipline norms
        discipline = (state.get("discipline", "") or "").lower()
        disc_prompt = _get_discipline_prompt(discipline)
        if disc_prompt:
            parts.append(f"\n\n--- 学科写作规范 ---\n{disc_prompt}")

        # Layer 2: degree-level calibration
        degree = (state.get("degree_level", "") or "").lower()
        deg_prompt = _DEGREE_PROMPTS.get(degree, "")
        if deg_prompt:
            parts.append(f"\n\n--- 学位要求 ---\n{deg_prompt}")

        # Layer 3: chapter/section writing guidance
        section_guide = _get_section_guide(chapter_title, section_title, step_code)
        if section_guide:
            parts.append(f"\n\n--- 章节写作要求 ---\n{section_guide}")

        # Layer 4: quality-rewrite directives
        if step_code == "quality_rewrite":
            rewrite_hint = self._paper_rewrite_hint(state)
            if rewrite_hint:
                parts.append(rewrite_hint)

        # Layer 5: quality-score-driven
        quality_hint = self._paper_quality_hint(state)
        if quality_hint:
            parts.append(quality_hint)

        # Layer 6: memory context
        memory_ctx = state.get("memory_context", "")
        if memory_ctx:
            parts.append(f"\n\n--- 上下文记忆 ---\n{memory_ctx[:1500]}")

        return "\n".join(parts)

    def get_paper_system_prompt(
        self,
        step_code: str,
        state: Dict[str, Any],
    ) -> str:
        """Return the system prompt for a paper generation node."""
        discipline = state.get("discipline", "")
        degree = state.get("degree_level", "")

        base = _PAPER_SYSTEM_PROMPTS.get(step_code, _PAPER_SYSTEM_PROMPTS["default"])
        role_suffix = ""
        if discipline:
            role_suffix += f"你擅长{discipline}领域的学术研究。"
        if degree:
            role_suffix += f"目标是{degree}学位论文水平。"
        return f"{base}\n{role_suffix}" if role_suffix else base

    def _paper_rewrite_hint(self, state: Dict[str, Any]) -> str:
        """Generate targeted rewrite directives from quality issues."""
        issues = state.get("quality_issues", [])
        uncovered = state.get("uncovered_sections", [])
        rewrite_round = state.get("rewrite_round", 0)

        if not issues and not uncovered:
            return ""

        parts = [f"\n\n--- 修改要求 (第{rewrite_round + 1}轮) ---"]

        if uncovered:
            parts.append(f"以下章节缺少文献引用支撑，请补充引用标记 [n]：")
            for s in uncovered[:5]:
                parts.append(f"  - {s}")

        issue_types = set()
        for issue in issues:
            if "内容过短" in issue:
                issue_types.add("content_short")
            if "缺少引文" in issue:
                issue_types.add("missing_citation")

        if "content_short" in issue_types:
            parts.append("有章节内容过短，请扩展论述，每节至少 300 字。")
        if "missing_citation" in issue_types:
            parts.append("请确保每个论述性段落都有对应的文献引用 [n]。")

        if rewrite_round >= 1:
            parts.append("这是最后一轮修改机会，请重点确保引用覆盖率达标。")

        return "\n".join(parts)

    def _paper_quality_hint(self, state: Dict[str, Any]) -> str:
        score = state.get("quality_score", 0.0)
        if not score:
            return ""
        if score < 60:
            return (
                "\n\n--- 质量强化 ---\n"
                "当前质量评分较低，请特别注意：\n"
                "1. 每个论述性断言必须有引用支撑 [n]\n"
                "2. 段落间需要有逻辑过渡\n"
                "3. 核心论点要有数据/文献佐证\n"
                "4. 每节不少于 300 字"
            )
        return ""


# ======================================================================
# Paper-specific prompt constants
# ======================================================================

_PAPER_SYSTEM_PROMPTS: Dict[str, str] = {
    "topic_clarify": (
        "你是一位资深学术导师。请根据用户给出的论文题目、学科方向、学位层次和方法偏好，"
        "精炼论文主题并生成 3-5 个具体的研究问题。"
        "研究问题应具备：明确性、可研究性、学术价值，且相互之间有逻辑递进关系。"
    ),
    "outline_generate": (
        "你是一位论文结构设计专家。请根据精炼后的主题、研究问题和检索到的学术证据，"
        "设计论文的完整章节大纲。\n"
        "要求：\n"
        "1. 输出合法 JSON，格式为 {\"chapters\": [{\"index\": 1, \"title\": \"...\", \"sections\": [{\"title\": \"...\"}]}]}\n"
        "2. 章节数量 4-7 章，每章 2-5 节\n"
        "3. 章节结构要符合学科规范\n"
        "4. 章节标题要具体、有学术指向性，避免泛化"
    ),
    "outline_expand": (
        "你是一位学术写作专家。请根据大纲、研究问题和学术证据，"
        "为指定章节撰写完整内容。\n"
        "要求：\n"
        "1. 论述严谨、逻辑清晰、学术用语规范\n"
        "2. 每个论述性断言必须用 [n] 标注引用来源编号\n"
        "3. 段落间有自然的逻辑过渡\n"
        "4. 内容不少于 300 字\n"
        "5. 直接输出章节正文，不要输出 Markdown 标题"
    ),
    "quality_rewrite": (
        "你是一位论文修改专家。请根据质量检查反馈，修改指定章节。\n"
        "重点：补充缺失的引用标记 [n]，扩展过短的段落，增强论证逻辑。\n"
        "直接输出修改后的完整章节正文。"
    ),
    "default": "你是一位学术写作助手，擅长论文选题、文献综述、研究设计和学术写作。",
}

_DISCIPLINE_PROMPTS: Dict[str, str] = {
    "humanities": (
        "人文社科论文要求：\n"
        "- 论点鲜明，论据充分，逻辑严密\n"
        "- 注重文献综述的系统性和批判性\n"
        "- 引用需标注页码，使用规范的引文格式\n"
        "- 研究方法以定性分析、文本分析、比较研究为主"
    ),
    "stem": (
        "理工科论文要求：\n"
        "- 描述注重可复现性，实验参数需明确标注\n"
        "- 数据需标注单位和误差范围\n"
        "- 公式需编号，变量需定义\n"
        "- 图表需有清晰的标题和标注\n"
        "- 研究方法以实验验证、数学建模、仿真分析为主"
    ),
    "medical": (
        "医学论文要求：\n"
        "- 遵循 CONSORT/PRISMA/STROBE 等报告规范\n"
        "- 临床数据需注明伦理审批号\n"
        "- 统计方法需明确 (p值、置信区间、效应量)\n"
        "- 注重循证医学证据等级\n"
        "- 讨论部分需与同类研究对比"
    ),
}

_DEGREE_PROMPTS: Dict[str, str] = {
    "bachelor": (
        "本科毕业论文要求：\n"
        "- 语言平实，侧重综述和基础分析\n"
        "- 篇幅 8000-15000 字\n"
        "- 引用文献 15-30 篇\n"
        "- 研究问题聚焦、方法简洁"
    ),
    "master": (
        "硕士学位论文要求：\n"
        "- 要求一定理论深度和方法创新\n"
        "- 篇幅 20000-40000 字\n"
        "- 引用文献 40-80 篇，含近3年文献\n"
        "- 需有独立的研究设计和数据分析"
    ),
    "phd": (
        "博士学位论文要求：\n"
        "- 要求原创性贡献和前沿突破\n"
        "- 篇幅 50000+ 字\n"
        "- 引用文献 100+ 篇，含国际前沿成果\n"
        "- 需体现系统的研究方法论和创新点"
    ),
}

# Discipline keyword mapping for classification
_DISCIPLINE_KEYWORDS: Dict[str, List[str]] = {
    "humanities": [
        "文学", "历史", "哲学", "法学", "语言", "艺术", "教育", "社会学",
        "心理学", "政治", "传播", "新闻", "管理", "经济",
        "literature", "history", "philosophy", "law", "education",
        "sociology", "psychology", "management", "economics",
    ],
    "stem": [
        "计算机", "软件", "人工智能", "机器学习", "深度学习", "数据",
        "物理", "化学", "生物", "数学", "统计", "工程", "电子", "通信",
        "cs", "ai", "computer", "engineering", "physics", "math",
    ],
    "medical": [
        "医学", "临床", "药学", "护理", "公共卫生", "生物医学",
        "medicine", "clinical", "pharmacy", "nursing", "biomedical",
    ],
}

# Chapter-level writing guidance
_CHAPTER_GUIDES: Dict[str, str] = {
    "绪论": (
        "绪论章节要求：\n"
        "1. 研究背景：从宏观到微观，层层递进引出研究问题\n"
        "2. 研究意义：理论意义和实践意义分别阐述\n"
        "3. 研究现状：按主题分类综述，指出研究空白\n"
        "4. 研究内容：明确本文解决什么问题，用什么方法"
    ),
    "相关理论": (
        "理论基础章节要求：\n"
        "1. 界定核心概念的学术定义\n"
        "2. 梳理相关理论的发展脉络\n"
        "3. 评述不同理论流派的优劣\n"
        "4. 阐明本研究采用的理论框架及其适用性"
    ),
    "研究方法": (
        "研究方法章节要求：\n"
        "1. 明确研究设计类型（实验/调查/案例/仿真等）\n"
        "2. 详细描述数据来源、样本选择、工具选型\n"
        "3. 阐述具体的实施步骤，保证可复现性\n"
        "4. 说明数据分析方法和评价指标"
    ),
    "实验结果": (
        "实验/结果章节要求：\n"
        "1. 客观呈现实验数据和结果\n"
        "2. 使用表格和图表辅助说明\n"
        "3. 对比分析不同方案/方法的效果\n"
        "4. 讨论结果的意义和局限性"
    ),
    "总结": (
        "总结章节要求：\n"
        "1. 回顾研究目标，逐一总结完成情况\n"
        "2. 提炼研究的主要贡献和创新点\n"
        "3. 客观评价研究的不足和局限\n"
        "4. 展望未来研究方向"
    ),
}


def _get_discipline_prompt(discipline: str) -> str:
    """Match discipline string to discipline-specific prompt."""
    if not discipline:
        return ""
    lower = discipline.lower()
    for category, keywords in _DISCIPLINE_KEYWORDS.items():
        if any(kw in lower for kw in keywords):
            return _DISCIPLINE_PROMPTS.get(category, "")
    return ""


def _get_section_guide(chapter_title: str, section_title: str, step_code: str) -> str:
    """Get writing guidance for a specific chapter/section."""
    if step_code not in ("outline_expand", "quality_rewrite"):
        return ""
    if not chapter_title:
        return ""

    lower = chapter_title.lower()
    for key, guide in _CHAPTER_GUIDES.items():
        if key in lower:
            return guide

    return ""


def _is_backend_file(path: str) -> bool:
    return any(ext in path for ext in (".java", ".py", ".go", ".rs")) or "backend/" in path


def _is_frontend_file(path: str) -> bool:
    return any(ext in path for ext in (".vue", ".tsx", ".jsx", ".ts", ".js", ".css", ".scss")) or "frontend/" in path


def _is_db_file(path: str) -> bool:
    return any(ext in path for ext in (".sql",)) or any(d in path for d in ("migration", "schema", "flyway"))
