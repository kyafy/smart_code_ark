"""LangChain tool wrappers for code/structure generation.

Prefers direct LLM calls (LLMCodegenClient) when DEEPAGENT_LLM_DIRECT_ENABLED=true,
falls back to the Java model proxy for backward compatibility.
"""

from __future__ import annotations

from typing import Any, Dict

from langchain_core.tools import tool

from .java_api_client import JavaApiClient

# Module-level Java client instance, initialized by the graph builder.
_client: JavaApiClient | None = None


def set_client(client: JavaApiClient) -> None:
    global _client
    _client = client


def _get_client() -> JavaApiClient:
    if _client is None:
        raise RuntimeError("JavaApiClient not initialized — call set_client() first")
    return _client


@tool
async def generate_project_structure(
    prd: str,
    stack_backend: str = "springboot",
    stack_frontend: str = "vue3",
    stack_db: str = "mysql",
    instructions: str = "",
) -> Dict[str, Any]:
    """Generate a project file structure plan from the PRD.

    Returns a dict with 'files' (list of file paths) and 'structure' metadata.
    """
    from .llm_codegen_client import LLMCodegenClient
    llm = LLMCodegenClient.from_env()
    if llm is not None:
        return await llm.generate_project_structure(
            prd=prd,
            stack={"backend": stack_backend, "frontend": stack_frontend, "db": stack_db},
            instructions=instructions,
        )
    return await _get_client().generate_project_structure(
        prd=prd,
        stack={"backend": stack_backend, "frontend": stack_frontend, "db": stack_db},
        instructions=instructions,
    )


@tool
async def generate_file_content(
    file_path: str,
    prd: str,
    tech_stack: str,
    project_structure: str,
    group: str = "backend",
    instructions: str = "",
) -> str:
    """Generate the full source code content for a single file.

    Returns the raw file content string (no markdown fences).
    """
    from .llm_codegen_client import LLMCodegenClient
    llm = LLMCodegenClient.from_env()
    if llm is not None:
        return await llm.generate_file_content(
            file_path=file_path,
            prd=prd,
            tech_stack=tech_stack,
            project_structure=project_structure,
            group=group,
            instructions=instructions,
        )
    return await _get_client().generate_file_content(
        file_path=file_path,
        prd=prd,
        tech_stack=tech_stack,
        project_structure=project_structure,
        group=group,
        instructions=instructions,
    )


@tool
async def resolve_template(
    stack_backend: str = "springboot",
    stack_frontend: str = "vue3",
    stack_db: str = "mysql",
    template_id: str = "",
) -> Dict[str, Any]:
    """Resolve the project template for the given tech stack.

    Returns template metadata including example files and paths.
    """
    return await _get_client().template_resolve(
        stack={"backend": stack_backend, "frontend": stack_frontend, "db": stack_db},
        template_id=template_id,
    )
