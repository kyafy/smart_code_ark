"""Docker-based sandbox backend implementing SandboxBackendProtocol.

Provides isolated code execution inside Docker containers, reusing the
same Docker infrastructure as the existing ContainerRuntimeService (Java).
"""

from __future__ import annotations

import asyncio
import logging
import socket
from dataclasses import dataclass
from typing import Optional

import docker
from docker.errors import APIError, NotFound
from docker.models.containers import Container

from ..config import SandboxConfig

logger = logging.getLogger(__name__)


@dataclass
class ExecuteResponse:
    """Result of a shell command execution inside the sandbox."""

    stdout: str = ""
    exit_code: int = 0
    truncated: bool = False


class DockerSandboxBackend:
    """SandboxBackendProtocol implementation backed by Docker containers.

    Lifecycle:
        sandbox = DockerSandboxBackend(config)
        await sandbox.start(task_id)      # creates & starts container
        result = await sandbox.execute("npm run build")
        await sandbox.write("/app/src/main.ts", content)
        content = await sandbox.read("/app/src/main.ts")
        await sandbox.stop()              # stops & removes container

    The sandbox exposes an internal port (default 5173) mapped to a
    dynamically-allocated host port in the 30000-31000 range, matching
    the existing PreviewGatewayService port convention.
    """

    def __init__(self, config: Optional[SandboxConfig] = None) -> None:
        self._config = config or SandboxConfig()
        self._client: Optional[docker.DockerClient] = None
        self._container: Optional[Container] = None
        self._host_port: Optional[int] = None
        self._task_id: Optional[str] = None

    # ------------------------------------------------------------------
    # Lifecycle
    # ------------------------------------------------------------------

    async def start(self, task_id: str) -> int:
        """Create and start a sandbox container. Returns the host port."""
        self._task_id = task_id
        self._client = docker.DockerClient(base_url=self._config.docker_host)

        container_name = f"{self._config.container_name_prefix}-{task_id[:12]}"

        # Remove stale container with same name if exists
        self._remove_if_exists(container_name)

        # Important: port-availability check must be against the host Docker daemon,
        # not the runtime container namespace. So we allocate by trying to create the
        # container and retrying on host-port collision.
        for port in range(self._config.host_port_start, self._config.host_port_end):
            try:
                logger.info(
                    "Creating sandbox container %s (image=%s, port=%d->%d)",
                    container_name,
                    self._config.base_image,
                    port,
                    self._config.internal_port,
                )
                self._container = self._client.containers.run(
                    image=self._config.base_image,
                    name=container_name,
                    command="tail -f /dev/null",  # keep alive
                    detach=True,
                    ports={f"{self._config.internal_port}/tcp": port},
                    mem_limit=self._config.memory_limit,
                    nano_cpus=int(self._config.cpu_limit * 1e9),
                    working_dir=self._config.workspace_mount,
                    labels={
                        "smartark.sandbox": "true",
                        "smartark.task_id": task_id,
                    },
                )
                self._host_port = port
                logger.info(
                    "Sandbox %s started (container=%s, host_port=%d)",
                    container_name,
                    self._container.short_id,
                    self._host_port,
                )
                return self._host_port
            except APIError as exc:
                msg = str(exc).lower()
                if "port is already allocated" in msg or "bind for 0.0.0.0" in msg:
                    logger.warning("Sandbox port %d already allocated, retry next port", port)
                    # If a failed container with same name was left behind, clean it up.
                    self._remove_if_exists(container_name)
                    continue
                raise

        raise RuntimeError(
            f"No available sandbox port in range {self._config.host_port_start}-{self._config.host_port_end}"
        )

    async def stop(self) -> None:
        """Stop and remove the sandbox container."""
        if self._container is None:
            return
        container_id = self._container.short_id
        try:
            self._container.stop(timeout=10)
            self._container.remove(force=True)
            logger.info("Sandbox container %s removed", container_id)
        except (NotFound, APIError) as exc:
            logger.warning("Error cleaning up sandbox %s: %s", container_id, exc)
        finally:
            self._container = None
            self._host_port = None

    async def keep_alive(self) -> None:
        """Mark the sandbox for preview reuse — skip cleanup."""
        logger.info(
            "Sandbox %s marked for preview reuse (port=%d)",
            self._container.short_id if self._container else "?",
            self._host_port or 0,
        )

    # ------------------------------------------------------------------
    # SandboxBackendProtocol: execute
    # ------------------------------------------------------------------

    async def execute(
        self,
        command: str,
        timeout: Optional[int] = None,
    ) -> ExecuteResponse:
        """Execute a shell command inside the sandbox container."""
        self._ensure_running()
        effective_timeout = timeout or self._config.build_timeout

        logger.debug("sandbox exec: %s (timeout=%ds)", command, effective_timeout)

        try:
            exit_code, output = await asyncio.to_thread(
                self._exec_sync, command, effective_timeout
            )
        except Exception as exc:
            logger.error("sandbox exec failed: %s", exc)
            return ExecuteResponse(stdout=str(exc), exit_code=1)

        stdout = output.decode("utf-8", errors="replace") if isinstance(output, bytes) else str(output)

        # Truncate very large outputs to avoid context explosion
        max_output = 50_000
        truncated = len(stdout) > max_output
        if truncated:
            stdout = stdout[:max_output] + "\n... [output truncated]"

        return ExecuteResponse(
            stdout=stdout,
            exit_code=exit_code,
            truncated=truncated,
        )

    # ------------------------------------------------------------------
    # SandboxBackendProtocol: file operations
    # ------------------------------------------------------------------

    async def read(self, path: str) -> str:
        """Read a file from the sandbox filesystem."""
        result = await self.execute(f"cat '{path}'", timeout=10)
        if result.exit_code != 0:
            raise FileNotFoundError(f"Cannot read {path}: {result.stdout}")
        return result.stdout

    async def write(self, path: str, content: str) -> None:
        """Write content to a file inside the sandbox."""
        # Ensure parent directory exists
        parent = "/".join(path.rsplit("/", 1)[:-1])
        if parent:
            await self.execute(f"mkdir -p '{parent}'", timeout=10)

        # Use heredoc to write content safely
        # Escape single quotes in content for shell safety
        escaped = content.replace("'", "'\\''")
        await self.execute(
            f"cat > '{path}' << 'SANDBOX_EOF'\n{escaped}\nSANDBOX_EOF",
            timeout=30,
        )

    async def ls(self, path: str = ".") -> str:
        """List directory contents."""
        result = await self.execute(f"ls -la '{path}'", timeout=10)
        return result.stdout

    async def file_exists(self, path: str) -> bool:
        """Check if a file exists in the sandbox."""
        result = await self.execute(f"test -f '{path}' && echo yes || echo no", timeout=5)
        return result.stdout.strip() == "yes"

    # ------------------------------------------------------------------
    # Properties
    # ------------------------------------------------------------------

    @property
    def container_id(self) -> Optional[str]:
        return self._container.id if self._container else None

    @property
    def host_port(self) -> Optional[int]:
        return self._host_port

    @property
    def is_running(self) -> bool:
        if self._container is None:
            return False
        try:
            self._container.reload()
            return self._container.status == "running"
        except (NotFound, APIError):
            return False

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _exec_sync(self, command: str, timeout: int) -> tuple:
        """Synchronous exec inside container (called via to_thread)."""
        assert self._container is not None
        exec_result = self._container.exec_run(
            cmd=["sh", "-c", command],
            workdir=self._config.workspace_mount,
            demux=False,
        )
        return exec_result.exit_code, exec_result.output

    def _find_available_port(self) -> int:
        """Find an available host port in the configured range."""
        for port in range(self._config.host_port_start, self._config.host_port_end):
            try:
                with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
                    s.bind(("", port))
                    return port
            except OSError:
                continue
        raise RuntimeError(
            f"No available port in range {self._config.host_port_start}-{self._config.host_port_end}"
        )

    def _remove_if_exists(self, container_name: str) -> None:
        """Remove a container by name if it exists."""
        assert self._client is not None
        try:
            old = self._client.containers.get(container_name)
            old.stop(timeout=5)
            old.remove(force=True)
            logger.info("Removed stale container %s", container_name)
        except NotFound:
            pass
        except APIError as exc:
            logger.warning("Error removing stale container %s: %s", container_name, exc)

    def _ensure_running(self) -> None:
        """Raise if sandbox is not in running state."""
        if not self.is_running:
            raise RuntimeError(
                f"Sandbox container is not running (task={self._task_id})"
            )
