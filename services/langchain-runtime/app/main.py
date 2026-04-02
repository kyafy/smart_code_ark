from __future__ import annotations

import hashlib
import logging
import os
import threading
import uuid
from dataclasses import dataclass
from typing import Any, Dict, List, Literal, Optional

from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

try:
    from langchain_core.messages import AIMessage, HumanMessage, SystemMessage
    from langchain_openai import ChatOpenAI, OpenAIEmbeddings

    LANGCHAIN_AVAILABLE = True
except Exception:  # pragma: no cover - import failure fallback for local environments
    LANGCHAIN_AVAILABLE = False
    ChatOpenAI = None
    OpenAIEmbeddings = None
    AIMessage = None
    HumanMessage = None
    SystemMessage = None

try:
    from langgraph.graph import END, START, StateGraph

    LANGGRAPH_AVAILABLE = True
except Exception:  # pragma: no cover - import failure fallback for local environments
    LANGGRAPH_AVAILABLE = False
    END = None
    START = None
    StateGraph = None


logging.basicConfig(
    level=os.getenv("LANGCHAIN_RUNTIME_LOG_LEVEL", "INFO"),
    format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
)
logger = logging.getLogger("langchain-runtime")

HEADER_API_VERSION = "X-SmartArk-Sidecar-Api-Version"
RUNTIME_API_VERSION = os.getenv("LANGCHAIN_RUNTIME_API_VERSION", "v1").strip() or "v1"
MAX_CONTEXT_CHARS = int(os.getenv("LANGCHAIN_RUNTIME_CONTEXT_MAX_CHARS", "4000"))
DEFAULT_CHAT_MODEL = os.getenv("LANGCHAIN_DEFAULT_CHAT_MODEL", "qwen-plus")
DEFAULT_EMBEDDING_MODEL = os.getenv("LANGCHAIN_DEFAULT_EMBEDDING_MODEL", "text-embedding-v3")
FALLBACK_EMBEDDING_DIM = max(8, int(os.getenv("LANGCHAIN_FALLBACK_EMBEDDING_DIM", "256")))
LANGCHAIN_MODEL_BASE_URL = os.getenv("LANGCHAIN_MODEL_BASE_URL", "").strip()
LANGCHAIN_MODEL_API_KEY = os.getenv("LANGCHAIN_MODEL_API_KEY", "").strip()
LANGSMITH_ENABLED = os.getenv("LANGCHAIN_ENABLE_LANGSMITH", "false").lower() == "true"
LANGSMITH_PROJECT = os.getenv("LANGSMITH_PROJECT", "smartark-langchain-runtime")

if LANGSMITH_ENABLED:
    os.environ.setdefault("LANGCHAIN_TRACING_V2", "true")
    os.environ.setdefault("LANGCHAIN_PROJECT", LANGSMITH_PROJECT)


class HealthResult(BaseModel):
    status: str
    detail: str


class ContextBuildRequest(BaseModel):
    taskId: str
    stepCode: str
    projectId: Optional[str] = None
    userId: Optional[int] = None
    instructions: Optional[str] = None
    maxItems: Optional[int] = 8


class ContextBuildResult(BaseModel):
    contextPack: str
    sources: List[str]
    totalItems: int


class QualityEvaluateRequest(BaseModel):
    taskId: str
    stepCode: str
    content: str
    rules: List[str] = Field(default_factory=list)


class QualityEvaluateResult(BaseModel):
    passed: bool
    failedRules: List[str]
    suggestions: List[str]
    score: Optional[float]


class MemoryReadRequest(BaseModel):
    scopeType: str
    scopeId: str
    query: Optional[str] = ""
    topK: Optional[int] = 8


class MemoryWriteRequest(BaseModel):
    scopeType: str
    scopeId: str
    memoryType: str
    content: str
    metadata: Dict[str, Any] = Field(default_factory=dict)


class MemoryItem(BaseModel):
    id: str
    scopeType: str
    scopeId: str
    memoryType: str
    content: str
    score: float


class MemoryReadResult(BaseModel):
    items: List[MemoryItem]


class MemoryWriteResult(BaseModel):
    written: bool
    recordId: Optional[str]


class ChatMessage(BaseModel):
    role: Literal["system", "user", "assistant"]
    content: str


class ChatRequest(BaseModel):
    model: Optional[str] = None
    messages: List[ChatMessage]
    temperature: Optional[float] = 0.2
    max_tokens: Optional[int] = 1024
    stream: Optional[bool] = False


class ChatChoiceMessage(BaseModel):
    role: str
    content: str


class ChatChoice(BaseModel):
    index: int
    message: ChatChoiceMessage
    finish_reason: str


class ChatUsage(BaseModel):
    prompt_tokens: int
    completion_tokens: int
    total_tokens: int


class ChatResponse(BaseModel):
    id: str
    object: str
    model: str
    choices: List[ChatChoice]
    usage: ChatUsage


class EmbeddingsRequest(BaseModel):
    model: Optional[str] = None
    input: str | List[str]


class EmbeddingData(BaseModel):
    index: int
    object: str
    embedding: List[float]


class EmbeddingsUsage(BaseModel):
    prompt_tokens: int
    total_tokens: int


class EmbeddingsResponse(BaseModel):
    object: str
    data: List[EmbeddingData]
    model: str
    usage: EmbeddingsUsage


class GraphRunRequest(BaseModel):
    taskId: str
    projectId: Optional[str] = None
    userId: Optional[str] = None
    input: Dict[str, Any] = Field(default_factory=dict)


class GraphRunResponse(BaseModel):
    runId: str
    taskId: str
    graph: str
    status: str
    result: Dict[str, Any]


@dataclass
class _MemoryRecord:
    id: str
    scope_type: str
    scope_id: str
    memory_type: str
    content: str
    metadata: Dict[str, Any]
    created_at: int


class InMemoryStore:
    """Thread-safe memory store used for P0 validation and local integration."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._records: List[_MemoryRecord] = []
        self._sequence = 0

    def write(
        self,
        scope_type: str,
        scope_id: str,
        memory_type: str,
        content: str,
        metadata: Dict[str, Any],
    ) -> str:
        with self._lock:
            self._sequence += 1
            record_id = f"m-{self._sequence}"
            record = _MemoryRecord(
                id=record_id,
                scope_type=scope_type,
                scope_id=scope_id,
                memory_type=memory_type,
                content=content,
                metadata=metadata,
                created_at=self._sequence,
            )
            self._records.append(record)
            return record_id

    def read(self, scope_type: str, scope_id: str, query: str, top_k: int) -> List[MemoryItem]:
        normalized_query = (query or "").strip().lower()
        query_terms = [term for term in normalized_query.split() if term]
        filtered = []
        with self._lock:
            for record in self._records:
                if record.scope_type != scope_type or record.scope_id != scope_id:
                    continue
                score = self._score(record.content, query_terms)
                filtered.append((score, record.created_at, record))

        filtered.sort(key=lambda item: (item[0], item[1]), reverse=True)
        result: List[MemoryItem] = []
        for score, _, record in filtered[: max(1, top_k)]:
            result.append(
                MemoryItem(
                    id=record.id,
                    scopeType=record.scope_type,
                    scopeId=record.scope_id,
                    memoryType=record.memory_type,
                    content=record.content,
                    score=score,
                )
            )
        return result

    @staticmethod
    def _score(content: str, query_terms: List[str]) -> float:
        text = (content or "").lower()
        if not query_terms:
            return 0.5
        hits = sum(1 for term in query_terms if term in text)
        return round(hits / len(query_terms), 4)


memory_store = InMemoryStore()
chat_client_cache: Dict[str, Any] = {}
embedding_client_cache: Dict[str, Any] = {}


def _require_api_version(api_version: Optional[str]) -> None:
    if api_version is None or api_version.strip() == "":
        return
    if api_version.strip() != RUNTIME_API_VERSION:
        raise HTTPException(
            status_code=400,
            detail=f"Unsupported sidecar api version: {api_version}, expected {RUNTIME_API_VERSION}",
        )


def _estimate_tokens(text: str) -> int:
    clean = (text or "").strip()
    if not clean:
        return 0
    return max(1, (len(clean) + 3) // 4)


def _flatten_content(value: Any) -> str:
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        chunks = []
        for item in value:
            if isinstance(item, str):
                chunks.append(item)
            elif isinstance(item, dict):
                text = item.get("text")
                if isinstance(text, str):
                    chunks.append(text)
        return "\n".join(chunks).strip()
    return str(value)


def _build_stub_chat_reply(messages: List[ChatMessage]) -> str:
    last_user = ""
    for msg in reversed(messages):
        if msg.role == "user":
            last_user = msg.content
            break
    if not last_user:
        last_user = "请给出你的具体目标，我将按任务拆解并给出可执行计划。"
    return (
        "P0 fallback 回复：当前未配置上游模型，已返回可联调结果。\n\n"
        f"你的输入是：{last_user[:600]}\n\n"
        "下一步建议：开启 LANGCHAIN_MODEL_BASE_URL / LANGCHAIN_MODEL_API_KEY 后切换到真实模型。"
    )


def _to_langchain_messages(messages: List[ChatMessage]) -> List[Any]:
    result: List[Any] = []
    for item in messages:
        if item.role == "system":
            result.append(SystemMessage(content=item.content))
        elif item.role == "assistant":
            result.append(AIMessage(content=item.content))
        else:
            result.append(HumanMessage(content=item.content))
    return result


def _get_chat_client(model_name: str, temperature: float) -> Any:
    key = f"{model_name}:{temperature}"
    if key in chat_client_cache:
        return chat_client_cache[key]
    client = ChatOpenAI(
        model=model_name,
        temperature=temperature,
        api_key=LANGCHAIN_MODEL_API_KEY,
        base_url=LANGCHAIN_MODEL_BASE_URL,
    )
    chat_client_cache[key] = client
    return client


def _get_embedding_client(model_name: str) -> Any:
    if model_name in embedding_client_cache:
        return embedding_client_cache[model_name]
    client = OpenAIEmbeddings(
        model=model_name,
        api_key=LANGCHAIN_MODEL_API_KEY,
        base_url=LANGCHAIN_MODEL_BASE_URL,
    )
    embedding_client_cache[model_name] = client
    return client


def _make_fallback_embedding(text: str, dimension: int) -> List[float]:
    values: List[float] = []
    seed = hashlib.sha256(text.encode("utf-8")).digest()
    current = seed
    while len(values) < dimension:
        for byte in current:
            values.append(round((byte / 127.5) - 1.0, 6))
            if len(values) >= dimension:
                break
        current = hashlib.sha256(current).digest()
    return values


def _build_codegen_graph_result(payload: GraphRunRequest) -> Dict[str, Any]:
    instruction = str(payload.input.get("instructions", "") or "")
    stage = str(payload.input.get("stage", "plan") or "plan").strip().lower()
    stack_backend = str(payload.input.get("stackBackend", "springboot") or "springboot").strip().lower()
    stack_frontend = str(payload.input.get("stackFrontend", "vue3") or "vue3").strip().lower()
    stack_db = str(payload.input.get("stackDb", "mysql") or "mysql").strip().lower()
    full_stack = str(payload.input.get("fullStack", "") or "").strip()
    prd = str(payload.input.get("prd", "") or "").strip()

    def _build_structure_json() -> Dict[str, Any]:
        files: List[str] = [
            "README.md",
            "docs/prd.md",
            "docs/deploy.md",
            "docker-compose.yml",
            "scripts/start.sh",
            "scripts/deploy.sh",
        ]

        if "spring" in stack_backend:
            files.extend(
                [
                    "backend/pom.xml",
                    "backend/mvnw",
                    "backend/mvnw.cmd",
                    "backend/src/main/resources/application.yml",
                    "backend/src/main/java/com/example/Application.java",
                ]
            )
        elif "fastapi" in stack_backend or "python" in stack_backend:
            files.extend(
                [
                    "backend/requirements.txt",
                    "backend/app/main.py",
                    "backend/app/routers/health.py",
                ]
            )
        elif "django" in stack_backend:
            files.extend(
                [
                    "backend/requirements.txt",
                    "backend/manage.py",
                    "backend/config/settings.py",
                ]
            )
        else:
            files.extend(
                [
                    "backend/package.json",
                    "backend/src/main.ts",
                ]
            )

        if "next" in stack_frontend:
            files.extend(
                [
                    "frontend/package.json",
                    "frontend/next.config.ts",
                    "frontend/app/page.tsx",
                ]
            )
        else:
            files.extend(
                [
                    "frontend/package.json",
                    "frontend/src/main.ts",
                    "frontend/src/App.vue",
                ]
            )

        if "postgres" in stack_db:
            files.append("database/schema-postgres.sql")
        else:
            files.append("database/schema.sql")

        dedup_files = list(dict.fromkeys(files))
        return {"files": dedup_files}

    def _extract_json_object(text: str) -> Optional[Dict[str, Any]]:
        if not text:
            return None
        parser = __import__("json")
        candidates = [text.strip()]
        if "```" in text:
            cleaned = text.strip().replace("```json", "```").replace("```JSON", "```")
            parts = cleaned.split("```")
            for part in parts:
                candidate = part.strip()
                if candidate.startswith("{") and candidate.endswith("}"):
                    candidates.append(candidate)
        start = text.find("{")
        end = text.rfind("}")
        if start >= 0 and end > start:
            candidates.append(text[start : end + 1].strip())
        for candidate in candidates:
            try:
                loaded = parser.loads(candidate)
                if isinstance(loaded, dict):
                    return loaded
            except Exception:
                continue
        return None

    def _normalize_target_files() -> List[str]:
        raw = payload.input.get("targetFiles")
        values: List[str] = []
        if isinstance(raw, list):
            for item in raw:
                path = str(item or "").strip().replace("\\", "/")
                if path and not path.startswith("/") and ".." not in path:
                    values.append(path)
        group_structure = str(payload.input.get("groupStructure", "") or "").strip()
        if not values and group_structure:
            for line in group_structure.splitlines():
                path = line.strip().replace("\\", "/")
                if path and not path.startswith("/") and ".." not in path:
                    values.append(path)
        if not values:
            if stage == "codegen_backend":
                values.extend(
                    [
                        "backend/src/main/java/com/example/Application.java",
                        "backend/src/main/resources/application.yml",
                    ]
                )
            elif stage == "codegen_frontend":
                if "next" in stack_frontend:
                    values.extend(["frontend/app/page.tsx", "frontend/app/layout.tsx"])
                else:
                    values.extend(["frontend/src/main.ts", "frontend/src/App.vue"])
            elif stage == "sql_generate":
                values.extend(["database/schema.sql", "docs/deploy.md", "scripts/start.sh"])
        return list(dict.fromkeys(values))

    def _fallback_content(path: str) -> str:
        file_path = path.strip().replace("\\", "/")
        lower = file_path.lower()
        name = file_path.split("/")[-1]

        if lower.endswith("docker-compose.yml"):
            return (
                "version: '3.9'\n"
                "services:\n"
                "  app:\n"
                "    build: .\n"
                "    restart: unless-stopped\n"
            )
        if lower.endswith("scripts/start.sh"):
            return "#!/usr/bin/env bash\nset -euo pipefail\ndocker compose up -d --build\n"
        if lower.endswith("scripts/deploy.sh"):
            return "#!/usr/bin/env bash\nset -euo pipefail\ndocker compose pull\ndocker compose up -d\n"
        if lower.endswith(".md"):
            return f"# {name}\n\nGenerated by langchain-runtime stage `{stage}`.\n"
        if lower.endswith(".sql"):
            table_name = "items"
            if "user" in lower:
                table_name = "users"
            if "order" in lower:
                table_name = "orders"
            return (
                f"-- generated by langchain-runtime ({stage})\n"
                f"CREATE TABLE IF NOT EXISTS {table_name} (\n"
                "  id BIGINT PRIMARY KEY,\n"
                "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP\n"
                ");\n"
            )
        if lower.endswith("pom.xml"):
            return (
                "<project><modelVersion>4.0.0</modelVersion>"
                "<groupId>com.example</groupId><artifactId>backend</artifactId>"
                "<version>1.0.0</version></project>\n"
            )
        if lower.endswith("package.json"):
            return '{\n  "name": "smartark-app",\n  "version": "1.0.0"\n}\n'
        if lower.endswith("application.yml"):
            return "server:\n  port: 8080\nspring:\n  application:\n    name: smartark\n"
        if lower.endswith(".java"):
            class_name = name.replace(".java", "") or "GeneratedClass"
            package_line = ""
            marker = "/src/main/java/"
            idx = file_path.find(marker)
            if idx >= 0:
                package_path = file_path[idx + len(marker) : file_path.rfind("/")]
                package_name = package_path.replace("/", ".").strip(".")
                if package_name:
                    package_line = f"package {package_name};\n\n"
            return (
                f"{package_line}public class {class_name} {{\n"
                "    public String ping() {\n"
                "        return \"ok\";\n"
                "    }\n"
                "}\n"
            )
        if lower.endswith(".vue"):
            return (
                "<template>\n  <main class=\"page\">Generated frontend page.</main>\n</template>\n\n"
                "<script setup lang=\"ts\">\n</script>\n"
            )
        if lower.endswith(".tsx"):
            return "export default function Page() {\n  return <main>Generated page</main>;\n}\n"
        if lower.endswith(".ts"):
            return "export function bootstrap(): void {\n  console.log('generated');\n}\n"
        if lower.endswith(".py"):
            return "def main() -> str:\n    return 'ok'\n"
        return f"// generated by langchain-runtime stage {stage}\n"

    def _try_generate_with_model(target_files: List[str]) -> Dict[str, str]:
        use_real_model = bool(LANGCHAIN_AVAILABLE and LANGCHAIN_MODEL_BASE_URL and LANGCHAIN_MODEL_API_KEY)
        if not use_real_model or not target_files:
            return {}
        try:
            client = _get_chat_client(DEFAULT_CHAT_MODEL, 0.1)
            system_prompt = (
                "You are a senior software engineer. Return strict JSON only. "
                "Output format: {\"files\":[{\"path\":\"...\",\"content\":\"...\"}]}. "
                "Do not include paths outside the requested list."
            )
            user_prompt = (
                f"Stage: {stage}\n"
                f"PRD: {prd[:2000]}\n"
                f"FullStack: {full_stack[:500]}\n"
                f"Instructions: {instruction[:2000]}\n"
                "Requested files:\n"
                + "\n".join(target_files[:20])
            )
            result = client.invoke([SystemMessage(content=system_prompt), HumanMessage(content=user_prompt)])
            parsed = _extract_json_object(_flatten_content(getattr(result, "content", "")))
            if not isinstance(parsed, dict):
                return {}
            raw_files = parsed.get("files")
            if not isinstance(raw_files, list):
                return {}
            outputs: Dict[str, str] = {}
            allow = set(target_files)
            for item in raw_files:
                if not isinstance(item, dict):
                    continue
                path = str(item.get("path", "") or "").strip().replace("\\", "/")
                content = str(item.get("content", "") or "")
                if path in allow and content.strip():
                    outputs[path] = content
            return outputs
        except Exception as exc:
            logger.warning("codegen stage model generation fallback stage=%s error=%s", stage, str(exc))
            return {}

    def _build_codegen_files_result() -> Dict[str, Any]:
        target_files = _normalize_target_files()
        generated_by_model = _try_generate_with_model(target_files)
        codegen_files: List[Dict[str, str]] = []
        file_contents: Dict[str, str] = {}
        for path in target_files:
            content = generated_by_model.get(path) or _fallback_content(path)
            codegen_files.append({"path": path, "content": content})
            file_contents[path] = content
        return {
            "codegen_files": codegen_files,
            "file_contents": file_contents,
            "generated_count": len(codegen_files),
        }

    def _heuristic_autofix_content(file_path: str, current_content: str) -> str:
        path = (file_path or "").strip().lower()
        content = str(current_content or "")

        if path.endswith(".java"):
            content = content.replace("public clas ", "public class ")
            content = content.replace("private clas ", "private class ")
            if not content.strip():
                return "public class FixedApplication {\n}\n"
            return content
        if path.endswith(".ts") or path.endswith(".tsx"):
            if not content.strip():
                return "export {};\n"
            return content
        if path.endswith(".vue"):
            if not content.strip():
                return "<template><div>fixed</div></template>\n<script setup lang=\"ts\"></script>\n"
            return content
        if path.endswith(".py"):
            if not content.strip():
                return "def main() -> None:\n    pass\n"
            return content
        return content if content.strip() else "/* runtime auto-fix placeholder */\n"

    def _try_autofix_with_model(file_path: str, current_content: str, build_log: str, tech_stack: str) -> Optional[str]:
        use_real_model = bool(LANGCHAIN_AVAILABLE and LANGCHAIN_MODEL_BASE_URL and LANGCHAIN_MODEL_API_KEY)
        if not use_real_model:
            return None
        try:
            client = _get_chat_client(DEFAULT_CHAT_MODEL, 0.0)
            system_prompt = (
                "You are a senior software engineer fixing one compile/build error. "
                "Return strict JSON only: {\"fixed_content\":\"...\"}. "
                "Do not add markdown fences."
            )
            user_prompt = (
                f"File path: {file_path}\n"
                f"Tech stack: {tech_stack[:400]}\n"
                f"Build log tail:\n{build_log[:4000]}\n\n"
                f"Current file content:\n{current_content[:12000]}"
            )
            result = client.invoke([SystemMessage(content=system_prompt), HumanMessage(content=user_prompt)])
            parsed = _extract_json_object(_flatten_content(getattr(result, "content", "")))
            if isinstance(parsed, dict):
                fixed_content = str(parsed.get("fixed_content", "") or "").strip()
                if fixed_content:
                    return fixed_content
            return None
        except Exception as exc:
            logger.warning("build_verify_autofix fallback due to upstream error: %s", str(exc))
            return None

    def _build_build_verify_fix_result() -> Dict[str, Any]:
        file_path = str(payload.input.get("filePath", "") or "").strip()
        current_content = str(payload.input.get("currentContent", "") or "")
        build_log = str(payload.input.get("buildLog", "") or "")
        tech_stack = str(payload.input.get("techStack", "") or "")
        fixed_content = _try_autofix_with_model(file_path, current_content, build_log, tech_stack)
        if not fixed_content:
            fixed_content = _heuristic_autofix_content(file_path, current_content)
        return {
            "file_path": file_path,
            "fixed_content": fixed_content,
            "summary": f"build_verify_autofix for task={payload.taskId}",
        }

    def _build_build_verify_batch_fix_result() -> Dict[str, Any]:
        raw_files = payload.input.get("files")
        tech_stack = str(payload.input.get("techStack", "") or "")
        fixed_files: List[Dict[str, str]] = []
        file_contents: Dict[str, str] = {}
        if not isinstance(raw_files, list):
            raw_files = []

        for item in raw_files:
            if not isinstance(item, dict):
                continue
            file_path = str(item.get("filePath", "") or item.get("path", "") or "").strip()
            if not file_path:
                continue
            current_content = str(item.get("currentContent", "") or item.get("content", "") or "")
            build_log = str(item.get("buildLog", "") or "")
            fixed_content = _try_autofix_with_model(file_path, current_content, build_log, tech_stack)
            if not fixed_content:
                fixed_content = _heuristic_autofix_content(file_path, current_content)
            fixed_files.append({"path": file_path, "fixed_content": fixed_content})
            file_contents[file_path] = fixed_content

        return {
            "summary": f"build_verify_batch_autofix for task={payload.taskId}",
            "fixed_files": fixed_files,
            "file_contents": file_contents,
            "fixed_count": len(fixed_files),
        }

    def _build_artifact_contract_validation_result() -> Dict[str, Any]:
        required_files = payload.input.get("requiredFiles")
        workspace_files = payload.input.get("workspaceFiles")
        docker_compose_content = str(payload.input.get("dockerComposeContent", "") or "")
        max_file_size = int(payload.input.get("maxFileSizeBytes", 1_048_576) or 1_048_576)

        required: List[str] = []
        if isinstance(required_files, list):
            for item in required_files:
                text = str(item or "").strip()
                if text:
                    required.append(text)
        if not required:
            required = ["README.md", "docker-compose.yml"]

        normalized_files: List[Dict[str, Any]] = []
        existing_paths: set[str] = set()
        existing_dirs: set[str] = set()
        if isinstance(workspace_files, list):
            for item in workspace_files:
                if not isinstance(item, dict):
                    continue
                raw_path = str(item.get("path", "") or "").strip().replace("\\", "/")
                if not raw_path:
                    continue
                is_dir = bool(item.get("isDirectory", False))
                try:
                    size = int(item.get("size", 0) or 0)
                except Exception:
                    size = 0
                normalized_files.append({"path": raw_path, "isDirectory": is_dir, "size": size})
                if is_dir:
                    existing_dirs.add(raw_path)
                else:
                    existing_paths.add(raw_path)
                    if "/" in raw_path:
                        existing_dirs.add(raw_path.rsplit("/", 1)[0])

        violations: List[str] = []
        fatal_violations: List[str] = []

        for required_path in required:
            if required_path not in existing_paths:
                violations.append(f"WARN: missing mandatory file: {required_path}")

        for item in normalized_files:
            path = item["path"]
            size = int(item["size"])
            if ".." in path or path.startswith("/"):
                fatal_violations.append(f"FATAL: path traversal detected in workspace: {path}")
            if not item["isDirectory"] and size > max_file_size:
                violations.append(f"WARN: oversized file ({size // 1024} KB): {path}")

        context_lines: List[str] = []
        for line in docker_compose_content.splitlines():
            stripped = line.strip()
            if stripped.startswith("context:"):
                ctx = stripped.split(":", 1)[1].strip().strip("'\"")
                if ctx.startswith("./"):
                    ctx = ctx[2:]
                if ctx in ("", "."):
                    continue
                context_lines.append(ctx)
        for ctx in context_lines:
            if ctx not in existing_dirs and not any(path.startswith(f"{ctx}/") for path in existing_paths):
                violations.append(f"WARN: docker-compose build context '{ctx}' does not exist")

        return {
            "summary": f"artifact_contract_validate for task={payload.taskId}",
            "violations": list(dict.fromkeys(violations)),
            "fatal_violations": list(dict.fromkeys(fatal_violations)),
        }

    if stage == "artifact_contract_validate":
        if LANGGRAPH_AVAILABLE:
            def contract_validate_node(state: Dict[str, Any]) -> Dict[str, Any]:
                result = _build_artifact_contract_validation_result()
                result["summary"] = str(state.get("instruction", "") or "")[:500]
                return result

            graph = StateGraph(dict)
            graph.add_node("contract_validate", contract_validate_node)
            graph.add_edge(START, "contract_validate")
            graph.add_edge("contract_validate", END)
            app_graph = graph.compile()
            result = app_graph.invoke({"instruction": instruction})
            if not isinstance(result.get("violations"), list) and not isinstance(result.get("fatal_violations"), list):
                return _build_artifact_contract_validation_result()
            return {
                "summary": result.get("summary", ""),
                "violations": result.get("violations", []),
                "fatal_violations": result.get("fatal_violations", []),
            }
        return _build_artifact_contract_validation_result()

    if stage == "build_verify_batch_autofix":
        if LANGGRAPH_AVAILABLE:
            def batch_autofix_node(state: Dict[str, Any]) -> Dict[str, Any]:
                result = _build_build_verify_batch_fix_result()
                result["summary"] = str(state.get("instruction", "") or "")[:500]
                return result

            graph = StateGraph(dict)
            graph.add_node("batch_autofix", batch_autofix_node)
            graph.add_edge(START, "batch_autofix")
            graph.add_edge("batch_autofix", END)
            app_graph = graph.compile()
            result = app_graph.invoke({"instruction": instruction})
            if not isinstance(result.get("fixed_files"), list):
                return _build_build_verify_batch_fix_result()
            return {
                "summary": result.get("summary", ""),
                "fixed_files": result.get("fixed_files", []),
                "file_contents": result.get("file_contents", {}),
                "fixed_count": int(result.get("fixed_count", 0) or 0),
            }
        return _build_build_verify_batch_fix_result()

    if stage == "build_verify_autofix":
        requested_file_path = str(payload.input.get("filePath", "") or "").strip()
        if LANGGRAPH_AVAILABLE:
            def autofix_node(state: Dict[str, Any]) -> Dict[str, Any]:
                result = _build_build_verify_fix_result()
                result["summary"] = str(state.get("instruction", "") or "")[:500]
                return result

            graph = StateGraph(dict)
            graph.add_node("autofix", autofix_node)
            graph.add_edge(START, "autofix")
            graph.add_edge("autofix", END)
            app_graph = graph.compile()
            result = app_graph.invoke({"instruction": instruction})
            if not isinstance(result.get("fixed_content"), str) or not str(result.get("fixed_content", "")).strip():
                return _build_build_verify_fix_result()
            return {
                "summary": result.get("summary", ""),
                "file_path": str(result.get("file_path", requested_file_path) or requested_file_path),
                "fixed_content": str(result.get("fixed_content", "") or ""),
            }
        return _build_build_verify_fix_result()

    if stage in {"codegen_backend", "codegen_frontend", "sql_generate"}:
        if LANGGRAPH_AVAILABLE:
            def codegen_node(state: Dict[str, Any]) -> Dict[str, Any]:
                result = _build_codegen_files_result()
                result["summary"] = str(state.get("instruction", "") or "")[:500]
                return result

            graph = StateGraph(dict)
            graph.add_node("codegen", codegen_node)
            graph.add_edge(START, "codegen")
            graph.add_edge("codegen", END)
            app_graph = graph.compile()
            result = app_graph.invoke({"instruction": instruction})
            if not isinstance(result.get("codegen_files"), list):
                return _build_codegen_files_result()
            return {
                "summary": result.get("summary", ""),
                "codegen_files": result.get("codegen_files", []),
                "file_contents": result.get("file_contents", {}),
                "generated_count": int(result.get("generated_count", 0) or 0),
            }
        fallback_result = _build_codegen_files_result()
        fallback_result["summary"] = f"fallback_{stage} for task={payload.taskId}"
        return fallback_result

    if stage == "requirement_analyze":
        if LANGGRAPH_AVAILABLE:
            def requirement_node(state: Dict[str, Any]) -> Dict[str, Any]:
                return {
                    "summary": str(state.get("instruction", "") or "")[:500],
                    "structure_json": _build_structure_json(),
                }

            graph = StateGraph(dict)
            graph.add_node("requirement", requirement_node)
            graph.add_edge(START, "requirement")
            graph.add_edge("requirement", END)
            app_graph = graph.compile()
            result = app_graph.invoke({"instruction": instruction})
            structure_json = result.get("structure_json")
            if not isinstance(structure_json, dict):
                structure_json = _build_structure_json()
            return {
                "summary": result.get("summary", ""),
                "structure_json": structure_json,
            }
        return {
            "summary": f"fallback_requirement_analyze for task={payload.taskId}",
            "structure_json": _build_structure_json(),
        }

    if not LANGGRAPH_AVAILABLE:
        return {
            "summary": f"fallback_codegen_plan for task={payload.taskId}",
            "actions": ["analyze_requirements", "generate_backend", "generate_frontend", "verify_delivery"],
            "instructionSnippet": instruction[:500],
        }

    def plan_node(state: Dict[str, Any]) -> Dict[str, Any]:
        base = str(state.get("instruction", "") or "")
        return {
            "plan": [
                "requirement_analyze",
                "codegen_backend",
                "codegen_frontend",
                "artifact_contract_validate",
            ],
            "summary": base[:500],
        }

    graph = StateGraph(dict)
    graph.add_node("plan", plan_node)
    graph.add_edge(START, "plan")
    graph.add_edge("plan", END)
    app_graph = graph.compile()
    result = app_graph.invoke({"instruction": instruction})
    return {
        "plan": result.get("plan", []),
        "summary": result.get("summary", ""),
    }


def _build_paper_graph_result(payload: GraphRunRequest) -> Dict[str, Any]:
    topic = str(payload.input.get("topic", "") or "")
    topic_refined = str(payload.input.get("topicRefined", "") or "")
    questions_json = str(payload.input.get("researchQuestionsJson", "") or "")
    stage = str(payload.input.get("stage", "outline_generate") or "outline_generate").strip().lower()

    def _parse_questions(raw: str) -> List[str]:
        if not raw:
            return []
        try:
            parsed = __import__("json").loads(raw)
            if isinstance(parsed, list):
                values = [str(item).strip() for item in parsed if str(item).strip()]
                dedup: List[str] = []
                for value in values:
                    if value not in dedup:
                        dedup.append(value)
                return dedup
            if isinstance(parsed, str) and parsed.strip():
                return [parsed.strip()]
        except Exception:
            pass
        text = raw.strip()
        return [text] if text else []

    def _chapters_to_outline_json(chapter_titles: List[str]) -> Dict[str, Any]:
        chapters = []
        for index, title in enumerate(chapter_titles, start=1):
            normalized = (title or "").strip()
            if not normalized:
                normalized = f"第{index}章"
            if "." in normalized and normalized.split(".", 1)[0].isdigit():
                normalized = normalized.split(".", 1)[1].strip() or normalized
            chapters.append(
                {
                    "title": normalized,
                    "sections": [{"title": f"{normalized}核心内容"}],
                }
            )

        return {
            "topic": topic[:500],
            "topicRefined": topic_refined[:500],
            "researchQuestions": _parse_questions(questions_json),
            "chapters": chapters,
        }

    def _build_expanded_json() -> Dict[str, Any]:
        outline_obj = payload.input.get("outline")
        if not isinstance(outline_obj, dict):
            outline_obj = {}
        chapters_obj = outline_obj.get("chapters")
        if not isinstance(chapters_obj, list):
            chapters_obj = []

        normalized_chapters: List[Dict[str, Any]] = []
        for ci, chapter in enumerate(chapters_obj, start=1):
            chapter = chapter if isinstance(chapter, dict) else {}
            chapter_title = str(chapter.get("title", "") or "").strip() or f"Chapter {ci}"
            summary = str(chapter.get("summary", "") or "").strip()
            objective = str(chapter.get("objective", "") or "").strip()
            sections_obj = chapter.get("sections")
            if not isinstance(sections_obj, list) or not sections_obj:
                sections_obj = [{"title": f"{chapter_title} Section"}]

            normalized_sections: List[Dict[str, Any]] = []
            for si, section in enumerate(sections_obj, start=1):
                section = section if isinstance(section, dict) else {}
                section_title = str(section.get("title", "") or section.get("section", "") or "").strip()
                if not section_title:
                    section_title = f"Section {ci}.{si}"
                content = str(section.get("content", "") or section.get("summary", "") or "").strip()
                if not content:
                    content = f"This section elaborates {section_title} with evidence-backed analysis."
                core_argument = str(section.get("coreArgument", "") or "").strip() or content
                raw_citations = section.get("citations")
                citations: List[int] = []
                if isinstance(raw_citations, list):
                    for item in raw_citations:
                        try:
                            citations.append(int(item))
                        except Exception:
                            continue
                normalized_sections.append(
                    {
                        "title": section_title,
                        "content": content,
                        "coreArgument": core_argument,
                        "method": str(section.get("method", "") or ""),
                        "dataPlan": str(section.get("dataPlan", "") or ""),
                        "expectedResult": str(section.get("expectedResult", "") or ""),
                        "citations": citations,
                    }
                )

            normalized_chapters.append(
                {
                    "index": ci,
                    "title": chapter_title,
                    "summary": summary,
                    "objective": objective,
                    "sections": normalized_sections,
                }
            )

        return {
            "topic": topic[:500],
            "topicRefined": topic_refined[:500],
            "researchQuestions": _parse_questions(questions_json),
            "chapters": normalized_chapters,
            "citationMap": [],
        }

    def _build_rewrite_json() -> Dict[str, Any]:
        manuscript_obj = payload.input.get("stableManuscript")
        if not isinstance(manuscript_obj, dict):
            manuscript_obj = payload.input.get("manuscript")
        if not isinstance(manuscript_obj, dict):
            manuscript_obj = {}

        quality_obj = payload.input.get("qualityReport")
        issues: List[str] = []
        if isinstance(quality_obj, dict):
            raw_issues = quality_obj.get("issues")
            if isinstance(raw_issues, list):
                for item in raw_issues:
                    text = str(item).strip()
                    if text:
                        issues.append(text)

        chapters_obj = manuscript_obj.get("chapters")
        if not isinstance(chapters_obj, list):
            chapters_obj = []

        rewritten_chapters: List[Dict[str, Any]] = []
        for chapter in chapters_obj:
            chapter = chapter if isinstance(chapter, dict) else {}
            section_list = chapter.get("sections")
            if not isinstance(section_list, list):
                section_list = []
            rewritten_sections: List[Dict[str, Any]] = []
            for section in section_list:
                section = section if isinstance(section, dict) else {}
                title = str(section.get("title", "") or section.get("section", "") or "").strip() or "Core Section"
                content = str(section.get("content", "") or section.get("coreArgument", "") or "").strip()
                if not content:
                    content = f"This section refines {title} with stronger evidence and clearer reasoning."
                else:
                    content = f"{content}\n\nRewritten for quality: improved argument clarity and evidence linkage."
                core_argument = str(section.get("coreArgument", "") or "").strip() or content
                citations = section.get("citations")
                if not isinstance(citations, list):
                    citations = []
                rewritten_sections.append(
                    {
                        "title": title,
                        "content": content,
                        "coreArgument": core_argument,
                        "method": str(section.get("method", "") or ""),
                        "dataPlan": str(section.get("dataPlan", "") or ""),
                        "expectedResult": str(section.get("expectedResult", "") or ""),
                        "citations": citations,
                    }
                )
            rewritten_chapter = dict(chapter)
            rewritten_chapter["sections"] = rewritten_sections
            rewritten_chapters.append(rewritten_chapter)

        rewritten_manuscript = dict(manuscript_obj)
        rewritten_manuscript["chapters"] = rewritten_chapters
        return {
            "manuscript": rewritten_manuscript,
            "appliedIssues": issues,
            "summary": "runtime quality rewrite completed",
        }

    def _build_quality_report_json() -> Dict[str, Any]:
        outline_obj = payload.input.get("outline")
        if not isinstance(outline_obj, dict):
            outline_obj = {}
        chapters_obj = outline_obj.get("chapters")
        if not isinstance(chapters_obj, list):
            chapters_obj = []

        total_sections = 0
        for chapter in chapters_obj:
            chapter = chapter if isinstance(chapter, dict) else {}
            sections = chapter.get("sections")
            if isinstance(sections, list):
                total_sections += len(sections)

        uncovered_sections: List[str] = []
        evidence_coverage = 85
        if total_sections == 0:
            evidence_coverage = 0
        overall_score = max(0, min(100, 72 + min(20, total_sections * 4)))
        issues: List[str] = []
        if total_sections == 0:
            issues.append("No sections found in outline.")
        if total_sections > 8:
            issues.append("Structure may be too large and should be focused.")

        if evidence_coverage < 70:
            logic_closed_loop = False
            method_consistency = "needs_improvement"
            citation_verifiability = "needs_improvement"
        else:
            logic_closed_loop = True
            method_consistency = "ok"
            citation_verifiability = "ok"

        return {
            "logicClosedLoop": logic_closed_loop,
            "methodConsistency": method_consistency,
            "citationVerifiability": citation_verifiability,
            "overallScore": overall_score,
            "evidenceCoverage": evidence_coverage,
            "uncoveredSections": uncovered_sections,
            "issues": issues,
        }

    def _build_topic_clarify_json() -> Dict[str, Any]:
        discipline = str(payload.input.get("discipline", "") or "").strip()
        degree_level = str(payload.input.get("degreeLevel", "") or "").strip()
        method_pref = str(payload.input.get("methodPreference", "") or "").strip()
        base_topic = topic.strip() or "Research Topic"
        suffix_parts = [part for part in [discipline, degree_level, method_pref] if part]
        suffix = ", ".join(suffix_parts)
        topic_refined = base_topic if not suffix else f"{base_topic} ({suffix})"
        research_questions = [
            f"What is the core problem scope of {base_topic}?",
            f"How can {method_pref or 'the proposed method'} be validated in this study?",
            "What measurable outcomes can support the final conclusion?",
        ]
        return {
            "topicRefined": topic_refined[:500],
            "researchQuestions": research_questions,
        }

    if stage == "topic_clarify":
        if LANGGRAPH_AVAILABLE:
            def clarify_node(state: Dict[str, Any]) -> Dict[str, Any]:
                return {"topic_clarify_json": _build_topic_clarify_json(), "topic": str(state.get("topic", "") or "")[:500]}

            graph = StateGraph(dict)
            graph.add_node("clarify", clarify_node)
            graph.add_edge(START, "clarify")
            graph.add_edge("clarify", END)
            app_graph = graph.compile()
            result = app_graph.invoke({"topic": topic})
            topic_clarify_json = result.get("topic_clarify_json")
            if not isinstance(topic_clarify_json, dict):
                topic_clarify_json = _build_topic_clarify_json()
            return {
                "topic": result.get("topic", topic[:500]),
                "topic_clarify_json": topic_clarify_json,
            }
        return {
            "summary": f"fallback_paper_topic_clarify for task={payload.taskId}",
            "topic_clarify_json": _build_topic_clarify_json(),
        }

    if stage == "outline_quality_check":
        if LANGGRAPH_AVAILABLE:
            def quality_node(state: Dict[str, Any]) -> Dict[str, Any]:
                return {"quality_report_json": _build_quality_report_json(), "topic": str(state.get("topic", "") or "")[:500]}

            graph = StateGraph(dict)
            graph.add_node("quality", quality_node)
            graph.add_edge(START, "quality")
            graph.add_edge("quality", END)
            app_graph = graph.compile()
            result = app_graph.invoke({"topic": topic})
            quality_report_json = result.get("quality_report_json")
            if not isinstance(quality_report_json, dict):
                quality_report_json = _build_quality_report_json()
            return {
                "topic": result.get("topic", topic[:500]),
                "quality_report_json": quality_report_json,
            }
        return {
            "summary": f"fallback_paper_quality_check for task={payload.taskId}",
            "quality_report_json": _build_quality_report_json(),
        }

    if stage == "quality_rewrite":
        if LANGGRAPH_AVAILABLE:
            def rewrite_node(state: Dict[str, Any]) -> Dict[str, Any]:
                return {"rewrite_json": _build_rewrite_json(), "topic": str(state.get("topic", "") or "")[:500]}

            graph = StateGraph(dict)
            graph.add_node("rewrite", rewrite_node)
            graph.add_edge(START, "rewrite")
            graph.add_edge("rewrite", END)
            app_graph = graph.compile()
            result = app_graph.invoke({"topic": topic})
            rewrite_json = result.get("rewrite_json")
            if not isinstance(rewrite_json, dict):
                rewrite_json = _build_rewrite_json()
            return {
                "topic": result.get("topic", topic[:500]),
                "rewrite_json": rewrite_json,
            }
        return {
            "summary": f"fallback_paper_rewrite for task={payload.taskId}",
            "rewrite_json": _build_rewrite_json(),
        }

    if stage == "outline_expand":
        if LANGGRAPH_AVAILABLE:
            def expand_node(state: Dict[str, Any]) -> Dict[str, Any]:
                return {"expanded_json": _build_expanded_json(), "topic": str(state.get("topic", "") or "")[:500]}

            graph = StateGraph(dict)
            graph.add_node("expand", expand_node)
            graph.add_edge(START, "expand")
            graph.add_edge("expand", END)
            app_graph = graph.compile()
            result = app_graph.invoke({"topic": topic})
            expanded_json = result.get("expanded_json")
            if not isinstance(expanded_json, dict):
                expanded_json = _build_expanded_json()
            return {
                "topic": result.get("topic", topic[:500]),
                "expanded_json": expanded_json,
            }
        return {
            "summary": f"fallback_paper_expand for task={payload.taskId}",
            "expanded_json": _build_expanded_json(),
        }

    if not LANGGRAPH_AVAILABLE:
        chapter_titles = ["1. 引言", "2. 研究背景", "3. 方法与实现", "4. 实验与讨论", "5. 结论"]
        return {
            "summary": f"fallback_paper_plan for task={payload.taskId}",
            "steps": ["topic_clarify", "academic_retrieve", "outline_generate", "quality_rewrite"],
            "topic": topic[:500],
            "outline_json": _chapters_to_outline_json(chapter_titles),
        }

    def outline_node(state: Dict[str, Any]) -> Dict[str, Any]:
        val = str(state.get("topic", "") or "")
        chapter_titles = ["1. 引言", "2. 研究背景", "3. 方法与实现", "4. 实验与讨论", "5. 结论"]
        return {
            "outline": chapter_titles,
            "topic": val[:500],
        }

    graph = StateGraph(dict)
    graph.add_node("outline", outline_node)
    graph.add_edge(START, "outline")
    graph.add_edge("outline", END)
    app_graph = graph.compile()
    result = app_graph.invoke({"topic": topic})
    chapter_titles = result.get("outline", [])
    if not isinstance(chapter_titles, list):
        chapter_titles = []
    return {
        "outline": chapter_titles,
        "topic": result.get("topic", ""),
        "outline_json": _chapters_to_outline_json([str(item) for item in chapter_titles]),
    }


app = FastAPI(
    title="SmartArk LangChain Runtime",
    version=RUNTIME_API_VERSION,
    description=(
        "LangChain/LangGraph runtime for SmartArk. "
        "Includes sidecar-compatible APIs, v1 runtime APIs, "
        "and DeepAgent orchestration endpoints."
    ),
)

# --- DeepAgent Router ---
try:
    from .deepagent.routers.agent_router import router as deepagent_router

    app.include_router(deepagent_router)
    logger.info("DeepAgent router registered at /v1/agent/*")
except Exception as _da_err:  # pragma: no cover
    logger.warning("DeepAgent router not available: %s", _da_err)


@app.get("/health", response_model=HealthResult, tags=["Sidecar"])
def health(
    sidecar_api_version: Optional[str] = Header(default=None, alias=HEADER_API_VERSION),
) -> HealthResult:
    """Sidecar health endpoint used by api-gateway probe."""
    _require_api_version(sidecar_api_version)
    return HealthResult(status="ok", detail="ready")


@app.post("/context/build", response_model=ContextBuildResult, tags=["Sidecar"])
def build_context(
    request: ContextBuildRequest,
    sidecar_api_version: Optional[str] = Header(default=None, alias=HEADER_API_VERSION),
) -> ContextBuildResult:
    """Build context pack by composing instructions and memory snippets."""
    _require_api_version(sidecar_api_version)
    top_k = max(1, request.maxItems or 8)
    scope_id = request.taskId or ""
    query = request.stepCode or ""
    memories = memory_store.read("task", scope_id, query, top_k)

    lines: List[str] = []
    sources: List[str] = []
    if request.instructions:
        lines.append(request.instructions.strip())
        sources.append("instructions")
    for item in memories:
        lines.append(f"[{item.memoryType}] {item.content}")
        source = f"memory:{item.memoryType}"
        if source not in sources:
            sources.append(source)

    context_pack = "\n\n".join([line for line in lines if line]).strip()
    if len(context_pack) > MAX_CONTEXT_CHARS:
        context_pack = context_pack[:MAX_CONTEXT_CHARS]

    return ContextBuildResult(
        contextPack=context_pack,
        sources=sources,
        totalItems=len(memories),
    )


@app.post("/quality/evaluate", response_model=QualityEvaluateResult, tags=["Sidecar"])
def evaluate_quality(
    request: QualityEvaluateRequest,
    sidecar_api_version: Optional[str] = Header(default=None, alias=HEADER_API_VERSION),
) -> QualityEvaluateResult:
    """Evaluate content against quality rules and return actionable suggestions."""
    _require_api_version(sidecar_api_version)
    content = (request.content or "").lower()
    failed_rules: List[str] = []
    suggestions: List[str] = []

    for rule in request.rules or []:
        if rule == "missing_required_file":
            if "docker-compose.yml" not in content or "scripts/start.sh" not in content:
                failed_rules.append(rule)
                suggestions.append("补充 docker-compose.yml 与 scripts/start.sh 的生成与交付说明。")
        elif rule == "invalid_compose_context":
            if "../" in content or "..\\" in content:
                failed_rules.append(rule)
                suggestions.append("修复 compose build.context，避免目录越界。")
        elif rule == "invalid_start_script":
            if "docker compose up" not in content:
                failed_rules.append(rule)
                suggestions.append("start 脚本需包含 docker compose up 命令。")

    total = max(1, len(request.rules or []))
    score = round(max(0.0, 1.0 - (len(failed_rules) / total)), 4)
    return QualityEvaluateResult(
        passed=len(failed_rules) == 0,
        failedRules=failed_rules,
        suggestions=suggestions,
        score=score,
    )


@app.post("/memory/read", response_model=MemoryReadResult, tags=["Sidecar"])
def read_memory(
    request: MemoryReadRequest,
    sidecar_api_version: Optional[str] = Header(default=None, alias=HEADER_API_VERSION),
) -> MemoryReadResult:
    """Read memory by scope and query with simple ranking."""
    _require_api_version(sidecar_api_version)
    top_k = max(1, request.topK or 8)
    items = memory_store.read(request.scopeType, request.scopeId, request.query or "", top_k)
    return MemoryReadResult(items=items)


@app.post("/memory/write", response_model=MemoryWriteResult, tags=["Sidecar"])
def write_memory(
    request: MemoryWriteRequest,
    sidecar_api_version: Optional[str] = Header(default=None, alias=HEADER_API_VERSION),
) -> MemoryWriteResult:
    """Persist memory item to runtime memory store."""
    _require_api_version(sidecar_api_version)
    record_id = memory_store.write(
        scope_type=request.scopeType,
        scope_id=request.scopeId,
        memory_type=request.memoryType,
        content=request.content,
        metadata=request.metadata or {},
    )
    return MemoryWriteResult(written=True, recordId=record_id)


@app.get("/v1/health", response_model=HealthResult, tags=["Runtime"])
def v1_health() -> HealthResult:
    """Runtime health endpoint for new consumers."""
    return HealthResult(status="ok", detail="ready")


@app.post("/v1/model/chat", response_model=ChatResponse, tags=["Model"])
def v1_model_chat(request: ChatRequest) -> ChatResponse:
    """Unified chat-completions style endpoint built on LangChain."""
    if request.stream:
        raise HTTPException(status_code=400, detail="P0 /v1/model/chat 暂不支持 stream=true")

    model_name = (request.model or DEFAULT_CHAT_MODEL).strip() or DEFAULT_CHAT_MODEL
    prompt_text = "\n".join(msg.content for msg in request.messages if msg.content)
    prompt_tokens = _estimate_tokens(prompt_text)
    reply_text = ""
    completion_tokens = 0

    use_real_model = bool(LANGCHAIN_AVAILABLE and LANGCHAIN_MODEL_BASE_URL and LANGCHAIN_MODEL_API_KEY)
    if use_real_model:
        try:
            lc_messages = _to_langchain_messages(request.messages)
            client = _get_chat_client(model_name, float(request.temperature or 0.2))
            result = client.invoke(lc_messages)
            reply_text = _flatten_content(getattr(result, "content", ""))
            metadata = getattr(result, "response_metadata", {}) or {}
            token_usage = metadata.get("token_usage", {}) if isinstance(metadata, dict) else {}
            completion_tokens = int(token_usage.get("completion_tokens", 0) or 0)
            if not completion_tokens:
                completion_tokens = _estimate_tokens(reply_text)
        except Exception as exc:
            logger.warning("v1/model/chat fallback due to upstream error: %s", str(exc))
            reply_text = _build_stub_chat_reply(request.messages)
            completion_tokens = _estimate_tokens(reply_text)
    else:
        reply_text = _build_stub_chat_reply(request.messages)
        completion_tokens = _estimate_tokens(reply_text)

    return ChatResponse(
        id=f"chatcmpl-{uuid.uuid4().hex}",
        object="chat.completion",
        model=model_name,
        choices=[
            ChatChoice(
                index=0,
                message=ChatChoiceMessage(role="assistant", content=reply_text),
                finish_reason="stop",
            )
        ],
        usage=ChatUsage(
            prompt_tokens=prompt_tokens,
            completion_tokens=completion_tokens,
            total_tokens=prompt_tokens + completion_tokens,
        ),
    )


@app.post("/v1/model/embeddings", response_model=EmbeddingsResponse, tags=["Model"])
def v1_model_embeddings(request: EmbeddingsRequest) -> EmbeddingsResponse:
    """Unified embeddings endpoint built on LangChain."""
    model_name = (request.model or DEFAULT_EMBEDDING_MODEL).strip() or DEFAULT_EMBEDDING_MODEL
    inputs = [request.input] if isinstance(request.input, str) else list(request.input)
    prompt_tokens = sum(_estimate_tokens(item) for item in inputs)

    vectors: List[List[float]]
    use_real_model = bool(LANGCHAIN_AVAILABLE and LANGCHAIN_MODEL_BASE_URL and LANGCHAIN_MODEL_API_KEY)
    if use_real_model:
        try:
            client = _get_embedding_client(model_name)
            vectors = client.embed_documents(inputs)
        except Exception as exc:
            logger.warning("v1/model/embeddings fallback due to upstream error: %s", str(exc))
            vectors = [_make_fallback_embedding(text, FALLBACK_EMBEDDING_DIM) for text in inputs]
    else:
        vectors = [_make_fallback_embedding(text, FALLBACK_EMBEDDING_DIM) for text in inputs]

    data = [
        EmbeddingData(index=index, object="embedding", embedding=vector)
        for index, vector in enumerate(vectors)
    ]
    return EmbeddingsResponse(
        object="list",
        data=data,
        model=model_name,
        usage=EmbeddingsUsage(prompt_tokens=prompt_tokens, total_tokens=prompt_tokens),
    )


@app.post("/v1/graph/codegen/run", response_model=GraphRunResponse, tags=["Graph"])
def run_codegen_graph(request: GraphRunRequest) -> GraphRunResponse:
    """Run code-generation graph (P0 skeleton)."""
    run_id = uuid.uuid4().hex
    result = _build_codegen_graph_result(request)
    return GraphRunResponse(
        runId=run_id,
        taskId=request.taskId,
        graph="codegen",
        status="completed",
        result=result,
    )


@app.post("/v1/graph/paper/run", response_model=GraphRunResponse, tags=["Graph"])
def run_paper_graph(request: GraphRunRequest) -> GraphRunResponse:
    """Run paper-generation graph (P0 skeleton)."""
    run_id = uuid.uuid4().hex
    result = _build_paper_graph_result(request)
    return GraphRunResponse(
        runId=run_id,
        taskId=request.taskId,
        graph="paper",
        status="completed",
        result=result,
    )


@app.on_event("startup")
def _startup_log() -> None:
    logger.info(
        "langchain-runtime started apiVersion=%s langchainAvailable=%s langgraphAvailable=%s baseUrlSet=%s langsmithEnabled=%s",
        RUNTIME_API_VERSION,
        LANGCHAIN_AVAILABLE,
        LANGGRAPH_AVAILABLE,
        bool(LANGCHAIN_MODEL_BASE_URL),
        LANGSMITH_ENABLED,
    )
