"""Memory bridge middleware — connects Java memory services to the Python pipeline.

Loads short-term (checkpoint) and long-term (pattern library) memories before
each node, injects assembled context into LLM prompts, and persists
checkpoint + fix patterns after node completion.
"""

from __future__ import annotations

import logging
from typing import Any, Dict, List, Optional

from ..tools.java_api_client import JavaApiClient

logger = logging.getLogger(__name__)

# Sequence counter per task (module-level, reset on process restart)
_step_sequences: Dict[str, int] = {}


def _next_sequence(task_id: str) -> int:
    seq = _step_sequences.get(task_id, 0) + 1
    _step_sequences[task_id] = seq
    return seq


def _stack_signature(state: Dict[str, Any]) -> str:
    """Build a stable signature from state for long-term memory grouping.

    For codegen tasks: groups by stack (backend/frontend/db).
    For paper tasks: groups by discipline.
    """
    # Paper task detection: has discipline but no stack_backend
    if state.get("discipline") and not state.get("stack_backend"):
        discipline = state.get("discipline", "default")
        degree = state.get("degree_level", "")
        sig = f"paper__{discipline.lower().replace(' ', '-')}"
        if degree:
            sig += f"__{degree.lower()}"
        return sig

    # Codegen task: group by stack
    parts = [
        state.get("stack_backend", ""),
        state.get("stack_frontend", ""),
        state.get("stack_db", ""),
    ]
    return "__".join(p.strip().lower().replace(" ", "-") for p in parts if p.strip())


class MemoryBridgeMiddleware:
    """Middleware that bridges Java memory services with the Python DeepAgent pipeline.

    Before node execution:
        1. Load short-term memories (recent checkpoints for this task)
        2. Load long-term memories (cross-task patterns for this stack)
        3. Assemble unified context pack via Java ContextAssembler
        4. Inject memory_context into state for prompt augmentation

    After node execution:
        5. Save checkpoint (step output summary)
        6. For build_fix success: save fix pattern to long-term memory

    For build_fix nodes:
        7. Check long-term memory for matching error patterns
        8. Prepend proven fix hints to instructions
    """

    def __init__(
        self,
        short_term_limit: int = 8,
        long_term_limit: int = 8,
        context_max_chars: int = 4000,
    ) -> None:
        self._short_term_limit = short_term_limit
        self._long_term_limit = long_term_limit
        self._context_max_chars = context_max_chars

    async def before_node(
        self,
        state: Dict[str, Any],
        step_code: str,
    ) -> Dict[str, Any]:
        """Load memories and assemble context before node execution.

        Returns a partial state dict to merge into the current state.
        """
        client = JavaApiClient.from_state(state)
        task_id = state.get("task_id", "")
        project_id = state.get("project_id", "")
        user_id = state.get("user_id", 0)
        stack_sig = _stack_signature(state)

        # Load memories concurrently
        import asyncio
        short_task = client.load_checkpoint(task_id, limit=self._short_term_limit)
        long_task = client.load_longterm_memory(
            project_id, user_id, stack_sig, limit=self._long_term_limit,
        )
        short_memories, long_memories = await asyncio.gather(short_task, long_task)

        # Assemble context via Java ContextAssembler
        assembled = await client.assemble_context(
            step_code=step_code,
            prd=state.get("prd", ""),
            base_instructions=state.get("instructions", ""),
            short_term_memories=short_memories,
            long_term_memories=long_memories,
            max_chars=self._context_max_chars,
        )

        memory_context = assembled.get("context_pack", "")
        if assembled.get("truncated"):
            logger.info(
                "memory_bridge[%s/%s]: context truncated to %d chars",
                task_id, step_code, self._context_max_chars,
            )

        logger.info(
            "memory_bridge[%s/%s]: loaded %d short-term, %d long-term memories",
            task_id, step_code, len(short_memories), len(long_memories),
        )

        return {
            "short_term_memories": short_memories,
            "long_term_memories": long_memories,
            "memory_context": memory_context,
        }

    async def after_node(
        self,
        state: Dict[str, Any],
        step_code: str,
        output_summary: str = "",
        failure_reason: str = "",
        fixed_actions: Optional[List[str]] = None,
    ) -> None:
        """Persist checkpoint and optionally long-term patterns after node execution."""
        client = JavaApiClient.from_state(state)
        task_id = state.get("task_id", "")
        seq = _next_sequence(task_id)

        # Save checkpoint
        await client.save_checkpoint(
            task_id=task_id,
            step_code=step_code,
            sequence=seq,
            prompt_summary=f"Step {step_code} executed",
            output_summary=output_summary[:500],
            failure_reason=failure_reason[:500],
            fixed_actions=fixed_actions,
        )

    async def save_fix_pattern(
        self,
        state: Dict[str, Any],
        error_pattern: str,
        fix_action: str,
        file_path: str,
        success: bool = True,
    ) -> None:
        """Save a build fix pattern to long-term memory for future reuse."""
        client = JavaApiClient.from_state(state)
        project_id = state.get("project_id", "")
        user_id = state.get("user_id", 0)
        stack_sig = _stack_signature(state)

        memory_type = "success_pattern" if success else "failure_pattern"
        content = (
            f"[{memory_type}] file={file_path}\n"
            f"error: {error_pattern[:300]}\n"
            f"fix: {fix_action[:300]}"
        )

        await client.save_longterm_memory(
            project_id=project_id,
            user_id=user_id,
            stack_signature=stack_sig,
            memory_type=memory_type,
            content=content,
            metadata={
                "file_path": file_path,
                "round": state.get("build_fix_round", 0),
            },
        )
        logger.info("memory_bridge: saved %s for %s", memory_type, file_path)

    def build_fix_hint(self, state: Dict[str, Any], error_text: str) -> str:
        """Search long-term memories for matching fix patterns and return hints.

        Returns a hint string to prepend to fix instructions, or empty string.
        """
        long_memories = state.get("long_term_memories", [])
        if not long_memories:
            return ""

        # Also check fix_history from current task
        fix_history = state.get("fix_history", [])
        hints = []

        # Search long-term memories for similar error patterns
        error_keywords = set(error_text.lower().split()[:20])
        for memory in long_memories:
            if not memory or "[success_pattern]" not in memory.lower():
                continue
            memory_words = set(memory.lower().split()[:30])
            overlap = len(error_keywords & memory_words)
            if overlap >= 3:
                hints.append(memory)

        # Search current task fix history for context
        for record in fix_history[-5:]:
            hints.append(
                f"[previous fix round {record.get('round', '?')}] "
                f"file={record.get('file', '?')}: {record.get('fix_summary', '')[:200]}"
            )

        if not hints:
            return ""

        return (
            "\n--- 历史修复经验 (仅供参考) ---\n"
            + "\n".join(hints[:5])
            + "\n--- 请结合当前错误进行修复 ---\n"
        )
