"""Cooperative cancellation for DeepAgent pipeline runs.

Uses a global registry of CancellationToken instances keyed by run_id.
Nodes call ``check_cancelled(state)`` at entry points and inside loops;
if cancellation has been requested the helper raises ``TaskCancelled``
which propagates up to the graph runner for clean shutdown.
"""

from __future__ import annotations

import asyncio
import logging
from typing import Dict, Optional

logger = logging.getLogger(__name__)


class TaskCancelled(Exception):
    """Raised when a pipeline run is cancelled by user."""


class CancellationToken:
    """Thread-safe / async-safe cancellation flag for a single pipeline run."""

    def __init__(self, run_id: str) -> None:
        self.run_id = run_id
        self._event = asyncio.Event()

    def cancel(self) -> None:
        self._event.set()
        logger.info("Cancellation requested for run %s", self.run_id)

    @property
    def is_cancelled(self) -> bool:
        return self._event.is_set()

    def check(self) -> None:
        """Raise ``TaskCancelled`` if cancellation was requested."""
        if self.is_cancelled:
            raise TaskCancelled(f"Run {self.run_id} cancelled by user")


# ---------------------------------------------------------------------------
# Global registry (same pattern as sandbox_factory._active_sandboxes)
# ---------------------------------------------------------------------------

_tokens: Dict[str, CancellationToken] = {}


def register_token(run_id: str) -> CancellationToken:
    """Create and register a cancellation token for *run_id*."""
    token = CancellationToken(run_id)
    _tokens[run_id] = token
    return token


def cancel_by_run_id(run_id: str) -> bool:
    """Cancel the run identified by *run_id*.  Returns True if found."""
    token = _tokens.get(run_id)
    if token is not None:
        token.cancel()
        return True
    return False


def cancel_by_task_id(task_id: str, run_status: Dict[str, Dict]) -> bool:
    """Find the active run for *task_id* and cancel it.

    *run_status* is the in-memory ``_run_status`` dict from agent_router.
    """
    for rid, info in run_status.items():
        if info.get("task_id") == task_id and info.get("status") in ("accepted", "running"):
            return cancel_by_run_id(rid)
    return False


def get_token(run_id: str) -> Optional[CancellationToken]:
    """Return the token for *run_id*, or None."""
    return _tokens.get(run_id)


def remove_token(run_id: str) -> None:
    """Unregister the token for *run_id* (idempotent)."""
    _tokens.pop(run_id, None)


# ---------------------------------------------------------------------------
# Convenience helper for nodes
# ---------------------------------------------------------------------------


def check_cancelled(state: Dict) -> None:
    """Raise ``TaskCancelled`` if the run owning *state* has been cancelled.

    Safe to call even when no token is registered (e.g. unit tests).
    """
    token = get_token(state.get("run_id", ""))
    if token is not None:
        token.check()
