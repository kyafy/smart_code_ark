"""Sandbox lifecycle management — create, reuse, and destroy sandboxes."""

from __future__ import annotations

import logging
from typing import Dict, Optional

from ..config import SandboxConfig
from .docker_sandbox import DockerSandboxBackend

logger = logging.getLogger(__name__)

# Global registry of active sandboxes keyed by task_id.
_active_sandboxes: Dict[str, DockerSandboxBackend] = {}


async def create_sandbox(
    task_id: str,
    config: Optional[SandboxConfig] = None,
) -> DockerSandboxBackend:
    """Create (or reuse) a sandbox for the given task.

    If a sandbox already exists for this task and is still running,
    it is returned as-is.  Otherwise a new one is created.
    """
    existing = _active_sandboxes.get(task_id)
    if existing is not None and existing.is_running:
        logger.info("Reusing existing sandbox for task %s", task_id)
        return existing

    sandbox = DockerSandboxBackend(config)
    host_port = await sandbox.start(task_id)
    _active_sandboxes[task_id] = sandbox
    logger.info("Created sandbox for task %s on port %d", task_id, host_port)
    return sandbox


async def destroy_sandbox(task_id: str) -> None:
    """Stop and remove the sandbox for the given task."""
    sandbox = _active_sandboxes.pop(task_id, None)
    if sandbox is None:
        logger.debug("No sandbox found for task %s", task_id)
        return
    await sandbox.stop()
    logger.info("Destroyed sandbox for task %s", task_id)


async def get_sandbox(task_id: str) -> Optional[DockerSandboxBackend]:
    """Retrieve an active sandbox by task_id, or None."""
    sandbox = _active_sandboxes.get(task_id)
    if sandbox is not None and not sandbox.is_running:
        _active_sandboxes.pop(task_id, None)
        return None
    return sandbox


async def mark_for_preview(task_id: str) -> Optional[int]:
    """Mark a sandbox for preview reuse (skip cleanup). Returns host port."""
    sandbox = _active_sandboxes.get(task_id)
    if sandbox is None or not sandbox.is_running:
        return None
    await sandbox.keep_alive()
    return sandbox.host_port
