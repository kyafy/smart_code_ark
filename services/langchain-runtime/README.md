# langchain-runtime (DeepAgent)

SmartArk 的智能运行层 — 基于 LangChain/LangGraph 的异步图执行引擎，承载代码生成和学术论文生成两条完整链路。

## 架构概览

```
app/deepagent/
├── graphs/                  # LangGraph 图定义
│   ├── codegen_graph.py     # 代码生成图（12 个节点）
│   └── paper_graph.py       # 论文生成图（8 个节点）
├── nodes/                   # 节点函数实现
│   ├── codegen_nodes.py     # 代码生成节点（含 compile_check、build_fix）
│   ├── paper_nodes.py       # 论文生成节点（含并发扩写、质量重写）
│   ├── plan_validate_node.py
│   └── artifact_contract_validate_node.py
├── middleware/               # 中间件栈（8 个）
│   ├── smart_retry.py       # 语义重试 + 指数退避
│   ├── dynamic_prompt.py    # 动态提示增强（技术栈/学科/渐进式修复）
│   ├── context_compression.py # 上下文压缩（PRD/证据/依赖）
│   ├── memory_bridge.py     # 短期/长期记忆桥接
│   ├── adaptive_model_switch.py # 按复杂度选模型（STRONG/FAST）
│   ├── code_quality.py      # 代码质量校验
│   ├── citation_trace.py    # 引文覆盖率校验
│   └── adaptive_rerank.py   # 学科自适应 RAG 重排序
├── sandbox/                 # Docker 沙箱后端
├── state/                   # TypedDict 状态 Schema
├── tools/                   # LLM 客户端 & Java API 客户端
├── routers/                 # FastAPI 路由
└── config.py                # 环境变量配置
```

## 代码生成链路

```
requirement_analyze → plan_validate
    → [sql_generate, codegen_backend, codegen_frontend]  (并发)
    → artifact_contract_validate → sandbox_init
    → compile_check → build_verify → [build_fix loop] → smoke_test → preview_deploy
```

- **compile_check**: 全量构建前运行 `tsc --noEmit` / `mvn compile`，提前修复语法/类型错误（最多 2 轮，独立于 build_fix 配额）
- **build_fix**: 从编译日志提取出错文件 → LLM 重新生成 → 写回沙箱 → 重新构建（渐进式修复策略）
- **smoke_test**: 启动 dev server，轮询 HTTP 健康检查

## 论文生成链路

```
topic_clarify → academic_retrieve → rag_index_enrich → rag_retrieve_rerank
    → outline_generate → outline_expand → outline_quality_check
    → [quality_rewrite loop] → END
```

- **LLM 直连**: 直接调用上游 LLM API，绕过 Java 模型网关
- **章内并发**: 同一章节的 section 使用 `asyncio.gather` 并发扩写
- **引文校验**: SmartRetry 内嵌 CitationTrace 质量回调，生成时即时校验

## 已提供接口

| 接口 | 方法 | 说明 |
|------|------|------|
| `GET /v1/health` | GET | 健康检查 |
| `POST /v1/model/chat` | POST | LLM 聊天代理 |
| `POST /v1/model/embeddings` | POST | 向量化代理 |
| `POST /v1/graph/codegen/run` | POST | 代码生成图执行 |
| `POST /v1/graph/paper/run` | POST | 论文生成图执行 |
| `GET /v1/agent/status/{run_id}` | GET | 任务状态查询 |
| `GET /health` | GET | Sidecar 兼容健康检查 |

## 启动方式

### 本地（Python）

```bash
cd services/langchain-runtime
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt

LANGCHAIN_MODEL_BASE_URL="https://dashscope.aliyuncs.com/compatible-mode/v1" \
LANGCHAIN_MODEL_API_KEY="sk-xxx" \
LANGCHAIN_MODEL_NAME="qwen-plus" \
uvicorn app.main:app --host 0.0.0.0 --port 18080
```

### Docker

```bash
docker compose up -d langchain-runtime
```

## 环境变量

### 必须

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `LANGCHAIN_MODEL_BASE_URL` | - | 上游 LLM 地址（OpenAI 兼容） |
| `LANGCHAIN_MODEL_API_KEY` | - | 上游 LLM Key |
| `LANGCHAIN_MODEL_NAME` | `qwen-plus` | 模型名称 |

### 可选

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `DEEPAGENT_LLM_DIRECT_ENABLED` | `true` | LLM 直连开关 |
| `DEEPAGENT_LLM_PAPER_EXPAND_TIMEOUT` | `180` | 论文扩写超时（秒） |
| `DEEPAGENT_LLM_PAPER_RETRY_MAX` | `3` | 论文 LLM 重试次数 |
| `DEEPAGENT_MAX_COMPILE_CHECK_ROUNDS` | `2` | 编译检查修复轮次 |
| `DEEPAGENT_COMPILE_CHECK_TIMEOUT` | `60` | 编译命令超时（秒） |
| `DEEPAGENT_MAX_BUILD_FIX_ROUNDS` | `3` | build_fix 轮次（简单任务） |
| `DEEPAGENT_MAX_BUILD_FIX_ROUNDS_COMPLEX` | `5` | build_fix 轮次（复杂任务） |
| `DEEPAGENT_COMPLEXITY_FILE_THRESHOLD` | `50` | 复杂任务文件数阈值 |
| `DEEPAGENT_MODEL_STRONG_NAME` | - | 强模型（不配则不启用切换） |
| `DEEPAGENT_MODEL_FAST_NAME` | - | 快模型（不配则不启用切换） |
| `DEEPAGENT_NODE_MODEL_{NODE}` | - | Per-Node 模型覆盖 |
| `DEEPAGENT_CALLBACK_BASE_URL` | `http://localhost:8080` | Java 网关回调地址 |
| `DEEPAGENT_CALLBACK_API_KEY` | `smartark-internal` | 内部 API Token |
| `LANGCHAIN_ENABLE_LANGSMITH` | `false` | LangSmith tracing |
| `LANGSMITH_API_KEY` | - | LangSmith key |
| `LANGSMITH_PROJECT` | - | LangSmith project |
