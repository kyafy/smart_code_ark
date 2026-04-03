"""Pydantic models for DeepAgent API requests and responses."""

from __future__ import annotations

from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field


# ---------------------------------------------------------------------------
# Codegen request / response
# ---------------------------------------------------------------------------


class StackConfig(BaseModel):
    backend: str = "springboot"
    frontend: str = "vue3"
    db: str = "mysql"


class LLMConfigOverride(BaseModel):
    """Per-request LLM configuration override.

    Java dispatches this when a task needs a non-default model
    (e.g. a large project requiring a higher-context model).
    All fields are optional; unset fields inherit from the runtime environment.
    """
    model: Optional[str] = None
    base_url: Optional[str] = None
    api_key: Optional[str] = None
    temperature: Optional[float] = None
    max_tokens: Optional[int] = None
    codegen_timeout: Optional[int] = None    # seconds per file, overrides DEEPAGENT_LLM_CODEGEN_TIMEOUT
    codegen_concurrency: Optional[int] = None  # overrides DEEPAGENT_LLM_CONCURRENCY


class CodegenRunRequest(BaseModel):
    task_id: str
    project_id: str
    user_id: Optional[int] = None
    instructions: str = ""
    prd: str = ""
    stack: StackConfig = Field(default_factory=StackConfig)
    template_id: Optional[str] = None
    workspace_dir: str = ""
    callback_base_url: str = "http://localhost:8080"
    callback_api_key: str = "smartark-internal"
    sandbox_config: Optional[Dict[str, Any]] = None
    llm_config: Optional[LLMConfigOverride] = None  # Phase 3: per-task model override


class CodegenRunResponse(BaseModel):
    run_id: str
    task_id: str
    status: str = "accepted"


# ---------------------------------------------------------------------------
# Paper request / response
# ---------------------------------------------------------------------------


class PaperRunRequest(BaseModel):
    task_id: str
    session_id: int
    topic: str = ""
    discipline: str = ""
    degree_level: str = ""
    method_preference: str = ""
    callback_base_url: str = "http://localhost:8080"
    callback_api_key: str = "smartark-internal"


class PaperRunResponse(BaseModel):
    run_id: str
    task_id: str
    status: str = "accepted"


# ---------------------------------------------------------------------------
# Callback models (Python → Java)
# ---------------------------------------------------------------------------


class StepUpdatePayload(BaseModel):
    step_code: str
    status: str  # running | finished | failed
    progress: int = 0
    error_code: Optional[str] = None
    error_message: Optional[str] = None
    output_summary: Optional[str] = None
    output: Optional[Dict[str, Any]] = None


class LogPayload(BaseModel):
    level: str = "info"
    content: str = ""


class SandboxReadyPayload(BaseModel):
    host_port: int
    preview_url: str = ""
    container_id: str = ""


class HotfixPayload(BaseModel):
    fix_description: str = ""
    files_changed: List[str] = Field(default_factory=list)


# ---------------------------------------------------------------------------
# Internal state snapshots (for graph checkpointing)
# ---------------------------------------------------------------------------


class FilePlanItem(BaseModel):
    path: str
    group: str = "backend"  # backend | frontend | database | infra | docs
    priority: int = 0


class BuildReport(BaseModel):
    status: str = "unknown"  # passed | failed | timeout
    log: str = ""
    commands: List[Dict[str, Any]] = Field(default_factory=list)


class RagEvidenceItem(BaseModel):
    chunk_uid: str = ""
    doc_uid: str = ""
    paper_id: str = ""
    title: str = ""
    content: str = ""
    url: str = ""
    year: int = 0
    vector_score: float = 0.0
    rerank_score: float = 0.0


class PaperSource(BaseModel):
    paper_id: str = ""
    title: str = ""
    authors: List[str] = Field(default_factory=list)
    year: int = 0
    venue: str = ""
    url: str = ""
    abstract_text: str = ""
    relevance_score: float = 0.0
    source: str = ""  # semantic_scholar | crossref | arxiv
    section_key: str = "global"
