# langchain-runtime

`langchain-runtime` 是 SmartArk 的 LangChain/LangGraph 运行时服务（P0 基线版），目标是先提供可联调、可灰度、可扩展的统一智能运行层。

## 已提供接口

- Sidecar 兼容接口（与 Java `LangchainSidecarClient` 对齐）
  - `GET /health`
  - `POST /context/build`
  - `POST /quality/evaluate`
  - `POST /memory/read`
  - `POST /memory/write`
- 新版统一接口（P0）
  - `GET /v1/health`
  - `POST /v1/model/chat`
  - `POST /v1/model/embeddings`
  - `POST /v1/graph/codegen/run`
  - `POST /v1/graph/paper/run`
    - `result.outline_json` 会返回可直接用于论文大纲落库的结构化 JSON（含 `chapters[].sections[]`）

## 启动方式

### 本地（Python）

```bash
cd services/langchain-runtime
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 18080
```

### Docker

```bash
docker compose up -d langchain-runtime
```

## 关键环境变量

- `LANGCHAIN_RUNTIME_API_VERSION`：Sidecar 契约版本，默认 `v1`
- `LANGCHAIN_MODEL_BASE_URL`：上游 OpenAI 兼容地址（可空，空则 fallback）
- `LANGCHAIN_MODEL_API_KEY`：上游密钥（可空，空则 fallback）
- `LANGCHAIN_DEFAULT_CHAT_MODEL`：默认聊天模型名
- `LANGCHAIN_DEFAULT_EMBEDDING_MODEL`：默认向量模型名
- `LANGCHAIN_ENABLE_LANGSMITH`：是否开启 LangSmith tracing（`true/false`）
- `LANGSMITH_API_KEY`：LangSmith key
- `LANGSMITH_PROJECT`：LangSmith project 名称

## 说明

- P0 版本重点是“接口稳定 + 端到端联调可用 + 观测入口就绪”。
- 当未配置上游模型时，`/v1/model/*` 会返回 fallback 结果，用于联调与回归测试，不会阻塞主链路开发。
