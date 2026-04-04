# Smart Code Ark (慧码方舟)

Smart Code Ark 是一个基于 AI 的毕业设计项目生成平台，旨在通过对话式交互辅助用户完成从需求梳理、技术选型到代码生成的全过程。同时支持学术论文智能生成，覆盖选题精炼、文献检索、大纲设计到全文扩写的完整链路。

## 项目简介

本项目利用大模型能力，帮助学生快速构建毕业设计项目。用户只需输入简单的毕设题目，系统即可自动进行需求分析、生成 PRD 文档、设计数据库模型，并最终生成完整的前后端代码。论文生成模块则支持从研究主题出发，自动完成文献检索、大纲规划、章节扩写和质量审查。

## 技术栈

### 前端 (Frontend)
- **框架**: Vue 3 + TypeScript
- **构建工具**: Vite
- **UI 组件库**: Element Plus
- **样式**: Tailwind CSS
- **状态管理**: Pinia
- **路由**: Vue Router

### 后端 (Backend)
- **核心框架**: Spring Boot 3
- **构建工具**: Maven
- **数据库**: MySQL 8.0
- **ORM**: Spring Data JPA
- **API 风格**: REST + SSE（Chat 流式）
- **AI 集成**: ModelService 对接 OpenAI 兼容接口（支持 DashScope Compatible Mode）

### 智能运行层 (DeepAgent)
- **运行时**: Python 3.11+ / LangChain / LangGraph
- **图引擎**: LangGraph StateGraph（异步并发、条件路由、修复循环）
- **LLM 接入**: ChatOpenAI（OpenAI 兼容协议，直连上游 LLM，绕过 Java 网关）
- **沙箱**: Docker 容器隔离（编译、构建、预览）
- **向量存储**: Qdrant（论文 RAG 链路）

### 基础设施
- **容器化**: Docker & Docker Compose
- **数据库迁移**: Flyway
- **缓存/队列**: Redis

## 目录结构

```
smart_code_ark/
├── frontend-web/                        # 前端项目源码
├── services/
│   ├── api-gateway-java/                # 后端 API 网关服务 (Spring Boot)
│   └── langchain-runtime/               # 智能运行层 (Python/LangGraph)
│       └── app/deepagent/
│           ├── graphs/                  # 图定义（codegen_graph, paper_graph）
│           ├── nodes/                   # 节点函数（codegen_nodes, paper_nodes）
│           ├── middleware/              # 中间件栈（8 个中间件）
│           ├── sandbox/                 # Docker 沙箱后端
│           ├── state/                   # 状态 Schema
│           ├── tools/                   # LLM 客户端 & Java API 客户端
│           └── config.py                # 环境变量配置
├── docker-compose.yml                   # 容器编排配置
└── scripts/                             # 辅助脚本
```

## 系统架构

### 代码生成链路 (Codegen Pipeline)

```
requirement_analyze → plan_validate
    ┌──────┴─────────┬──────────────────┐
sql_generate   codegen_backend   codegen_frontend   (并发生成)
    └──────┬─────────┴──────────────────┘
artifact_contract_validate
    ↓
sandbox_init → compile_check → build_verify
                                    ↓ (条件路由)
                                build_fix ←→ build_verify (循环, 最多 3-5 轮)
                                    ↓
                                smoke_test
                                    ↓ (条件路由)
                                build_fix ←→ smoke_test (循环, 最多 2-3 轮)
                                    ↓
                                preview_deploy → END
```

**关键特性**:
- **并发代码生成**: 前端、后端、SQL 三组文件并发生成，通过 LangGraph Send API 实现
- **编译检查前置** (Phase 5): `compile_check` 节点在全量构建前运行 `tsc --noEmit` / `mvn compile`，提前发现语法/类型错误并就地修复，不消耗 build_fix 配额
- **沙箱隔离**: Docker 容器内执行构建、运行和预览，互不干扰
- **渐进式修复**: build_fix 按轮次升级修复策略（简单修正 → 换思路 → 完整重写）

### 论文生成链路 (Paper Pipeline)

```
topic_clarify → academic_retrieve → rag_index_enrich → rag_retrieve_rerank
    → outline_generate → outline_expand → outline_quality_check
                                              ↓ (条件路由)
                                         quality_rewrite ←→ quality_check (最多 2 轮)
                                              ↓
                                             END
```

**关键特性**:
- **LLM 直连**: 论文链路直接调用上游 LLM API（绕过 Java 模型网关），减少网络跳数，解决超时问题
- **章内并发扩写**: 同一章节的多个 section 使用 `asyncio.gather` 并发调用 LLM
- **学术检索 + RAG**: 通过 SemanticScholar/Crossref/arXiv 检索文献，Qdrant 向量索引，学科自适应重排序
- **引文质量闭环**: CitationTrace 中间件在生成时即时校验引文覆盖率，不达标则自动重试

### 中间件栈

两条链路共享统一的中间件架构：

| 中间件 | 代码生成 | 论文生成 | 职责 |
|--------|:-------:|:-------:|------|
| **SmartRetry** | ✅ | ✅ | 语义重试（空内容/过短/质量不达标），指数退避 |
| **DynamicPrompt** | ✅ | ✅ | 动态提示增强（技术栈规范/学科规范/渐进式修复策略） |
| **ContextCompression** | ✅ | ✅ | 上下文压缩（PRD/证据/跨文件依赖的语义截断） |
| **MemoryBridge** | ✅ | ✅ | 短期记忆（checkpoint）+ 长期记忆（修复模式持久化） |
| **AdaptiveModelSwitch** | ✅ | ✅ | 按任务复杂度动态选择 STRONG/FAST 模型 |
| **CodeQuality** | ✅ | - | 代码质量校验（占位符比例/业务逻辑检测） |
| **CitationTrace** | - | ✅ | 引文覆盖率校验（断言-引用匹配） |
| **AdaptiveRerank** | - | ✅ | 学科自适应 RAG 重排序权重 |

## 环境变量配置

### 必须配置

#### 数据库与基础设施
| 变量 | 默认值 | 说明 |
|------|--------|------|
| `DB_HOST` | `localhost` | MySQL 主机 |
| `DB_PORT` | `3306` | MySQL 端口 |
| `DB_NAME` | `smartark` | 数据库名 |
| `DB_USER` | `smartark` | 数据库用户 |
| `DB_PASSWORD` | `smartark` | 数据库密码 |
| `REDIS_HOST` | `localhost` | Redis 主机 |
| `REDIS_PORT` | `6379` | Redis 端口 |

#### Java 侧模型配置
| 变量 | 默认值 | 说明 |
|------|--------|------|
| `MODEL_BASE_URL` | - | OpenAI 兼容接口地址（如 DashScope） |
| `MODEL_API_KEY` | - | 模型 API Key |
| `CHAT_MODEL` | `Qwen3.5-Plus` | 对话模型 |
| `MODEL_MOCK_ENABLED` | `false` | Mock 模式 |

#### Python 侧 LLM 直连（DeepAgent）
| 变量 | 默认值 | 说明 |
|------|--------|------|
| `LANGCHAIN_MODEL_BASE_URL` | - | 上游 LLM 地址（直连，不经过 Java 网关） |
| `LANGCHAIN_MODEL_API_KEY` | - | 上游 LLM Key |
| `LANGCHAIN_MODEL_NAME` | `qwen-plus` | 模型名称 |

### 可选配置

#### 路由开关
| 变量 | 默认值 | 说明 |
|------|--------|------|
| `DEEPAGENT_LLM_DIRECT_ENABLED` | `true` | Python 侧 LLM 直连开关 |
| `LANGCHAIN_RUNTIME_PAPER_GRAPH_ENABLED` | `true` | Java 论文步骤优先走 Python 路径 |
| `LANGCHAIN_RUNTIME_CODEGEN_GRAPH_ENABLED` | `false` | Java 代码生成步骤优先走 Python 路径 |

#### 编译检查 (compile_check)
| 变量 | 默认值 | 说明 |
|------|--------|------|
| `DEEPAGENT_MAX_COMPILE_CHECK_ROUNDS` | `2` | 编译检查最大修复轮次 |
| `DEEPAGENT_COMPILE_CHECK_TIMEOUT` | `60` | 单次编译命令超时（秒） |

#### 论文链路
| 变量 | 默认值 | 说明 |
|------|--------|------|
| `DEEPAGENT_LLM_PAPER_EXPAND_TIMEOUT` | `180` | 论文扩写单节点超时（秒） |
| `DEEPAGENT_LLM_PAPER_RETRY_MAX` | `3` | 论文 LLM 最大重试次数 |

#### 模型动态切换
| 变量 | 默认值 | 说明 |
|------|--------|------|
| `DEEPAGENT_MODEL_STRONG_NAME` | - | 强模型名称（如 `qwen-max`），不配则不启用 |
| `DEEPAGENT_MODEL_FAST_NAME` | - | 快模型名称（如 `qwen-turbo`），不配则不启用 |

#### 构建修复限制
| 变量 | 默认值 | 说明 |
|------|--------|------|
| `DEEPAGENT_MAX_BUILD_FIX_ROUNDS` | `3` | 简单任务 build_fix 最大轮次 |
| `DEEPAGENT_MAX_SMOKE_FIX_ROUNDS` | `2` | 简单任务 smoke_fix 最大轮次 |
| `DEEPAGENT_MAX_BUILD_FIX_ROUNDS_COMPLEX` | `5` | 复杂任务 build_fix 最大轮次 |
| `DEEPAGENT_COMPLEXITY_FILE_THRESHOLD` | `50` | 文件数超过此阈值视为复杂任务 |

#### Per-Node 模型覆盖
| 变量 | 示例 | 说明 |
|------|------|------|
| `DEEPAGENT_NODE_MODEL_{NODE_NAME}` | `DEEPAGENT_NODE_MODEL_OUTLINE_GENERATE=qwen-max` | 为特定节点指定模型 |

## 快速开始

### 前置要求
- Java 17+
- Node.js 18+
- Python 3.11+
- Docker & Docker Compose
- Maven 3.8+

### 一键启动（推荐）

```bash
cp .env.example .env
bash scripts/dev-up.sh
```

停止服务：

```bash
bash scripts/dev-down.sh
```

### 手动启动

#### 1. 启动基础设施 (MySQL + Redis)

```bash
docker-compose up -d
```

#### 2. 启动后端服务

```bash
cd services/api-gateway-java
MODEL_BASE_URL="https://dashscope.aliyuncs.com/compatible-mode" \
MODEL_API_KEY="YOUR_MODEL_KEY" \
mvn spring-boot:run -DskipTests
```

后端服务运行在 `http://localhost:8080`

#### 3. 启动智能运行层

```bash
cd services/langchain-runtime
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
LANGCHAIN_MODEL_BASE_URL="https://dashscope.aliyuncs.com/compatible-mode/v1" \
LANGCHAIN_MODEL_API_KEY="YOUR_MODEL_KEY" \
LANGCHAIN_MODEL_NAME="qwen-plus" \
uvicorn app.main:app --host 0.0.0.0 --port 18080
```

智能运行层运行在 `http://localhost:18080`

#### 4. 启动前端服务

```bash
cd frontend-web
npm install
npm run dev
```

前端服务默认运行在 `http://localhost:5173`

### 实时查看后端日志

```bash
tail -f .logs/backend.log
# 只看错误/异常
egrep -n "ERROR|Exception|Caused by|status=5[0-9]{2}" .logs/backend.log | tail -n 120
```

## 功能特性

- **智能对话**: 通过 AI 聊天界面引导用户完善需求
- **自动化 PRD**: 自动生成包含项目概览、功能列表、页面结构的 PRD 文档
- **一键代码生成**: 确认需求后，自动规划文件结构、并发生成前后端代码、沙箱编译验证
- **编译检查前置**: 生成后立即运行 TypeScript/Java 编译检查，提前修复语法错误
- **渐进式修复**: 多轮 build_fix 配合记忆系统，修复策略按轮次自动升级
- **实时预览**: Docker 沙箱内启动开发服务器，在线预览生成效果
- **源码下载**: 支持下载生成的项目源码包
- **学术论文生成**: 选题精炼 → 文献检索 → RAG 索引 → 大纲设计 → 章节并发扩写 → 质量审查/重写
- **引文质量保障**: 自动检测论述性断言的引文覆盖率，不达标则自动重试和修改

## API 接口

### 智能运行层 (langchain-runtime)

| 接口 | 方法 | 说明 |
|------|------|------|
| `/v1/health` | GET | 健康检查 |
| `/v1/model/chat` | POST | LLM 聊天 |
| `/v1/model/embeddings` | POST | 向量化 |
| `/v1/graph/codegen/run` | POST | 代码生成图执行 |
| `/v1/graph/paper/run` | POST | 论文生成图执行 |
| `/v1/agent/status/{run_id}` | GET | 查询任务状态 |

### Java API 网关

| 接口 | 方法 | 说明 |
|------|------|------|
| `/actuator/health` | GET | 健康检查 |
| `/api/chat/stream` | POST (SSE) | 流式对话 |
| `/api/internal/task/{taskId}/step-update` | POST | 步骤状态回调 |
| `/api/internal/academic/search` | POST | 学术检索 |
| `/api/internal/rag/index` | POST | RAG 索引 |
| `/api/internal/rag/retrieve` | POST | RAG 检索 |

## Smoke Tests

```bash
# 论文图 smoke test
powershell -ExecutionPolicy Bypass -File scripts/paper-runtime-graph-smoke.ps1

# 代码生成图 smoke test
powershell -ExecutionPolicy Bypass -File scripts/codegen-runtime-graph-smoke.ps1
```

## 贡献指南

欢迎提交 Issue 和 Pull Request 来改进本项目。

## 许可证

MIT License
