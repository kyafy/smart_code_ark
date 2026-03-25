# Qdrant 接入技术方案

- 文档版本：v1.0
- 文档日期：2026-03-21
- 关联文档：`PRD_v3.4_论文智能生成融合需求文档.md`
- 适用范围：论文业务 RAG 链路中的向量检索层

---

## 1. 基于现有技术栈的 Qdrant 接入流程

### 1.1 现有技术栈概览

| 层级 | 技术 | 版本 |
|------|------|------|
| 前端 | Vue 3 + TypeScript + Element Plus | Vue 3.4.15 |
| 后端 | Spring Boot 3 + Java 17 + Maven | Spring Boot 3.4.4 |
| 数据库 | MySQL 8.0 | 8.0 |
| 缓存 | Redis 7.4 | 7.4 |
| 容器化 | Docker + Docker Compose | - |
| LLM | OpenAI 兼容接口（DashScope / Qwen） | - |
| 构建 | Maven 3.8+ | - |

### 1.2 接入流程（分步）

#### Step 1：基础设施层 — 部署 Qdrant 实例

在现有 `docker-compose.yml` 中新增 Qdrant 服务：

```yaml
services:
  # ... 现有 mysql、redis、api-gateway ...

  qdrant:
    image: qdrant/qdrant:latest
    container_name: smart-code-ark-qdrant
    ports:
      - "6333:6333"   # HTTP REST API
      - "6334:6334"   # gRPC API
    volumes:
      - qdrant_storage:/qdrant/storage
    environment:
      - QDRANT__SERVICE__GRPC_PORT=6334
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:6333/healthz"]
      interval: 30s
      timeout: 10s
      retries: 3

volumes:
  qdrant_storage:
```

#### Step 2：后端依赖层 — 引入 Java Client

在 `pom.xml` 中添加 Qdrant Java 客户端依赖：

```xml
<dependency>
    <groupId>io.qdrant</groupId>
    <artifactId>client</artifactId>
    <version>1.12.0</version>
</dependency>
```

> Qdrant Java Client 基于 gRPC 通信（端口 6334），性能优于 REST。

#### Step 3：配置层 — 添加应用配置

在 `application.yml`（或环境变量）中新增：

```yaml
qdrant:
  host: ${QDRANT_HOST:localhost}
  port: ${QDRANT_GRPC_PORT:6334}
  # api-key: ${QDRANT_API_KEY:}  # 生产环境启用
  collection:
    paper-chunks: paper_chunks
  embedding:
    model: ${EMBEDDING_MODEL:text-embedding-v3}
    dimension: ${EMBEDDING_DIMENSION:1024}
```

#### Step 4：客户端初始化 — Spring Bean 注册

```java
@Configuration
public class QdrantConfig {

    @Value("${qdrant.host}")
    private String host;

    @Value("${qdrant.port}")
    private int port;

    @Bean
    public QdrantClient qdrantClient() {
        QdrantGrpcClient grpcClient = QdrantGrpcClient.newBuilder(host, port, false).build();
        return new QdrantClient(grpcClient);
    }
}
```

#### Step 5：Collection 初始化 — 启动时自动创建

```java
@Component
@RequiredArgsConstructor
public class QdrantCollectionInitializer implements ApplicationRunner {

    private final QdrantClient qdrantClient;

    @Value("${qdrant.embedding.dimension}")
    private int dimension;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String collectionName = "paper_chunks";
        // 检查集合是否已存在
        boolean exists = qdrantClient.collectionExistsAsync(collectionName).get();
        if (!exists) {
            qdrantClient.createCollectionAsync(collectionName,
                VectorParams.newBuilder()
                    .setSize(dimension)
                    .setDistance(Distance.Cosine)
                    .setHnswConfig(HnswConfigDiff.newBuilder()
                        .setM(16)
                        .setEfConstruct(200)
                        .build())
                    .setOnDisk(true)
                    .build()
            ).get();
            // 创建 payload 索引（用于过滤检索）
            qdrantClient.createPayloadIndexAsync(collectionName, "discipline",
                PayloadSchemaType.Keyword, null, null, null, null).get();
            qdrantClient.createPayloadIndexAsync(collectionName, "year",
                PayloadSchemaType.Integer, null, null, null, null).get();
            qdrantClient.createPayloadIndexAsync(collectionName, "source",
                PayloadSchemaType.Keyword, null, null, null, null).get();
        }
    }
}
```

#### Step 6：Embedding 服务 — 调用向量化接口

复用现有 LLM 的 OpenAI 兼容接口来生成 Embedding：

```java
@Service
public class EmbeddingService {

    @Value("${model.base-url}")
    private String baseUrl;

    @Value("${qdrant.embedding.model}")
    private String embeddingModel;

    private final RestTemplate restTemplate;

    /**
     * 将文本转为向量
     */
    public float[] embed(String text) {
        // 调用 OpenAI 兼容的 /v1/embeddings 接口
        Map<String, Object> request = Map.of(
            "model", embeddingModel,
            "input", text
        );
        ResponseEntity<Map> response = restTemplate.postForEntity(
            baseUrl + "/v1/embeddings", request, Map.class);
        // 解析返回的 embedding 向量
        List<Map> data = (List<Map>) response.getBody().get("data");
        List<Double> embedding = (List<Double>) data.get(0).get("embedding");
        float[] vector = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) {
            vector[i] = embedding.get(i).floatValue();
        }
        return vector;
    }
}
```

#### Step 7：核心 RAG 服务 — Upsert 与 Search

```java
@Service
@RequiredArgsConstructor
public class QdrantRagService {

    private final QdrantClient qdrantClient;
    private final EmbeddingService embeddingService;

    private static final String COLLECTION = "paper_chunks";

    /**
     * 语料入库：chunk 文本 -> embedding -> upsert 到 Qdrant
     */
    public void upsertChunk(String chunkUid, String text, Map<String, Object> payload) {
        float[] vector = embeddingService.embed(text);
        // 维度校验
        if (vector.length != expectedDimension) {
            throw new IllegalStateException("Embedding 维度不匹配");
        }
        PointStruct point = PointStruct.newBuilder()
            .setId(PointId.newBuilder().setUuid(chunkUid).build())
            .setVectors(Vectors.newBuilder()
                .setVector(Vector.newBuilder().addAllData(floatsToList(vector)).build())
                .build())
            .putAllPayload(toQdrantPayload(payload))
            .build();
        qdrantClient.upsertAsync(COLLECTION, List.of(point), null).get();
    }

    /**
     * 相似度检索：query -> embedding -> search -> 返回 top-K 结果
     */
    public List<ScoredPoint> search(String queryText, int topK, Filter filter) {
        float[] queryVector = embeddingService.embed(queryText);
        return qdrantClient.queryAsync(
            QueryPoints.newBuilder()
                .setCollectionName(COLLECTION)
                .setQuery(Query.newBuilder()
                    .setNearest(NearestQuery.newBuilder()
                        .setVector(Vector.newBuilder().addAllData(floatsToList(queryVector)).build())
                        .build())
                    .build())
                .setLimit(topK)
                .setFilter(filter)
                .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build())
                .build()
        ).get();
    }
}
```

#### Step 8：接入大纲生成链路

在现有 Agent 编排中，插入 RAG 步骤：

```
TopicClarifyStep -> AcademicRetrieveStep -> [RagIndexEnrichStep] -> [RagRetrieveRerankStep] -> OutlineGenerateStep -> OutlineQualityCheckStep
```

新增两个 Step：
- **RagIndexEnrichStep**：将 `AcademicRetrieveStep` 返回的文献分块后，调用 `QdrantRagService.upsertChunk()` 入库
- **RagRetrieveRerankStep**：根据题目/研究问题，调用 `QdrantRagService.search()` 获取相关证据，执行 rerank 后注入上下文

---

## 2. Qdrant 文件结构与相关依赖

### 2.1 后端新增文件结构

```
services/api-gateway-java/src/main/java/.../
├── config/
│   └── QdrantConfig.java              # Qdrant 客户端 Bean 配置
├── init/
│   └── QdrantCollectionInitializer.java # Collection 自动初始化
├── service/
│   ├── EmbeddingService.java           # 文本 -> 向量 转换
│   ├── QdrantRagService.java           # 向量 upsert / search 核心逻辑
│   └── RerankService.java             # 检索结果重排序
├── agent/step/
│   ├── RagIndexEnrichStep.java         # 语料分块入库步骤
│   └── RagRetrieveRerankStep.java      # 向量检索 + rerank 步骤
├── model/
│   ├── ChunkDocument.java              # 分块文档实体
│   └── RetrievalResult.java            # 检索结果封装
└── controller/
    └── RagController.java              # RAG 管理接口（reindex / stats）
```

### 2.2 依赖清单

#### Maven 依赖（pom.xml）

| 依赖 | GroupId | ArtifactId | 版本 | 用途 |
|------|---------|------------|------|------|
| Qdrant Java Client | `io.qdrant` | `client` | `1.12.0` | gRPC 通信，向量 CRUD |
| gRPC Netty | `io.grpc` | `grpc-netty-shaded` | `1.62.2` | gRPC 传输层（客户端自带，通常无需额外添加） |

> 注意：Qdrant Java Client 内部依赖 gRPC 和 Protobuf，Maven 会自动传递引入。

#### Docker 镜像

| 服务 | 镜像 | 端口 |
|------|------|------|
| Qdrant | `qdrant/qdrant:latest` | 6333 (HTTP), 6334 (gRPC) |

#### Embedding 模型（复用 LLM 接口）

| 模型 | 维度 | 提供方 |
|------|------|--------|
| `text-embedding-v3` | 1024 | DashScope |
| `text-embedding-3-small` | 1536 | OpenAI |

> PRD 要求锁定单一模型和维度，入库前做维度校验。

### 2.3 Qdrant 内部存储结构

```
qdrant_storage/                    # Docker volume 挂载点
├── collections/
│   └── paper_chunks/             # 集合目录
│       ├── 0/                    # 分片 0（MVP 单分片）
│       │   ├── segments/         # 数据段
│       │   │   ├── <segment_id>/
│       │   │   │   ├── vector_storage/   # 向量数据
│       │   │   │   ├── payload_storage/  # payload 数据
│       │   │   │   └── id_tracker/       # ID 映射
│       │   └── wal/              # Write-Ahead Log
│       └── config.json           # 集合配置
├── snapshots/                    # 快照存储
└── .lock                         # 实例锁文件
```

---

## 3. Qdrant 工作流程

### 3.1 整体数据流

```
┌─────────────────────────────────────────────────────────────────┐
│                        论文生成链路                               │
│                                                                  │
│  用户输入题目/研究问题                                             │
│       │                                                          │
│       ▼                                                          │
│  ┌──────────────┐     ┌──────────────┐     ┌──────────────┐     │
│  │TopicClarify  │────▶│AcademicRetrieve────▶│RagIndexEnrich│     │
│  │  题目澄清    │     │ 学术文献召回   │     │ 分块向量入库  │     │
│  └──────────────┘     └──────────────┘     └──────┬───────┘     │
│                                                    │             │
│                                                    ▼             │
│  ┌──────────────┐     ┌──────────────┐     ┌──────────────┐     │
│  │OutlineQuality│◀────│OutlineGenerate│◀────│RagRetrieve   │     │
│  │  质检校验     │     │  大纲生成     │     │  向量检索+重排│     │
│  └──────────────┘     └──────────────┘     └──────────────┘     │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 3.2 语料入库流程（RagIndexEnrich）

```
文献原文
   │
   ▼
① 文本分块（Chunking）
   - 按段落/固定 token 数切分（建议 512 tokens，重叠 64 tokens）
   - 记录 chunk_no、chunk_type（abstract/body/conclusion）
   │
   ▼
② 写入 MySQL（paper_corpus_docs + paper_corpus_chunks）
   - 持久化原文、元数据、分块信息
   │
   ▼
③ 调用 Embedding API
   - 将 chunk_text 发送至 /v1/embeddings
   - 返回固定维度向量（如 1024 维）
   - 执行维度校验
   │
   ▼
④ Upsert 到 Qdrant（paper_chunks 集合）
   - id = chunk_uid
   - vector = embedding 向量
   - payload = {doc_uid, source, title, year, discipline, doi, url, language, chunk_type, citation_count}
   │
   ▼
⑤ Qdrant 内部处理
   - 写入 WAL（Write-Ahead Log）确保持久化
   - 数据进入 Segment
   - 后台 Optimizer 自动构建/更新 HNSW 索引
   - 当点数超过 indexing_threshold(20000) 时触发索引构建
```

### 3.3 向量检索流程（RagRetrieveRerank）

```
用户研究问题 / 大纲章节标题
   │
   ▼
① Query Embedding
   - 调用同一 Embedding 模型生成查询向量
   │
   ▼
② Qdrant 近似最近邻搜索（ANN）
   - HNSW 图遍历，hnsw_ef=64（默认）或 128（高质量模式）
   - 可附加 payload 过滤条件（discipline、year 范围等）
   - 返回 Top-K 候选（K=20，满足 Recall@20 评测）
   │
   ▼
③ Rerank 重排序
   - 使用 Cross-Encoder 或 LLM 对候选结果精排
   - 综合相关性分数 + 引用数 + 时效性
   - 输出 Top-N 最终证据（N=5~10）
   │
   ▼
④ 证据注入
   - 将检索到的 chunk_text + 元数据注入 Prompt
   - 格式：[来源][标题][年份] + 正文片段
   - 建立章节 -> 证据映射关系
   │
   ▼
⑤ 传递给 OutlineGenerateStep
   - LLM 基于原始 Prompt + RAG 证据上下文生成大纲
   - 每个章节标注引用来源
```

### 3.4 Qdrant 内部搜索机制

```
查询向量 q
   │
   ▼
HNSW 索引层级遍历
   │
   ├── Layer N（最顶层，节点最少）
   │   └── 找到入口点的近邻
   │
   ├── Layer N-1
   │   └── 在上层结果基础上扩展搜索
   │
   ├── ...
   │
   └── Layer 0（最底层，包含所有节点）
       └── 精细搜索，收集 ef 个候选
   │
   ▼
距离计算（Cosine Similarity）
   │
   ▼
Payload 过滤（如果有 Filter 条件）
   │
   ▼
返回 Top-K 结果（按相似度降序）
```

---

## 4. 最小 MVP 的必要条件

### 4.1 MVP 目标

> 用户输入论文题目 → 系统检索相关学术文献片段 → 生成带证据引用的论文大纲

### 4.2 基础设施必要条件

| 条件 | 具体要求 | 状态 |
|------|----------|------|
| Qdrant 实例 | Docker 单节点，单分片，无需集群 | 待部署 |
| MySQL 表 | `paper_corpus_docs` + `paper_corpus_chunks` | 待建表（Flyway） |
| Embedding API | DashScope 或 OpenAI 兼容接口可用 | 已有（复用 LLM 接口） |
| Docker Compose | 新增 qdrant 服务定义 | 待添加 |

### 4.3 代码必要条件

| 模块 | 文件 | 优先级 |
|------|------|--------|
| Qdrant 配置 | `QdrantConfig.java` | P0 |
| Collection 初始化 | `QdrantCollectionInitializer.java` | P0 |
| Embedding 服务 | `EmbeddingService.java` | P0 |
| 向量 CRUD | `QdrantRagService.java`（upsert + search） | P0 |
| 分块逻辑 | 文本切分工具（固定窗口 + 重叠） | P0 |
| 链路步骤 | `RagIndexEnrichStep.java` | P0 |
| 链路步骤 | `RagRetrieveRerankStep.java` | P0 |
| Rerank | `RerankService.java`（MVP 可用简单分数加权） | P1 |
| 管理接口 | `RagController.java`（reindex/stats） | P2 |

### 4.4 配置必要条件

```yaml
# application.yml 新增最小配置
qdrant:
  host: localhost
  port: 6334

embedding:
  model: text-embedding-v3     # 锁定模型
  dimension: 1024              # 锁定维度，禁止混用

rag:
  chunk-size: 512              # token 数
  chunk-overlap: 64            # 重叠 token 数
  search-top-k: 20             # 召回数量
  rerank-top-n: 10             # 重排后保留数量
  hnsw-ef: 64                  # 搜索精度参数
```

### 4.5 Flyway 迁移脚本（新增）

```sql
-- V11__paper_rag_corpus.sql

CREATE TABLE paper_corpus_docs (
    doc_uid       VARCHAR(64) PRIMARY KEY,
    source        VARCHAR(32) NOT NULL COMMENT '来源: semantic_scholar/arxiv/...',
    title         TEXT NOT NULL,
    abstract_text TEXT,
    keywords_json JSON,
    authors_json  JSON,
    year          INT,
    venue         VARCHAR(256),
    doi           VARCHAR(256),
    url           VARCHAR(512),
    citation_count INT DEFAULT 0,
    language      VARCHAR(16) DEFAULT 'en',
    created_at    DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE paper_corpus_chunks (
    chunk_uid   VARCHAR(64) PRIMARY KEY,
    doc_uid     VARCHAR(64) NOT NULL,
    chunk_no    INT NOT NULL,
    chunk_text  TEXT NOT NULL,
    chunk_type  VARCHAR(32) COMMENT 'abstract/body/conclusion',
    token_count INT,
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_doc_uid (doc_uid),
    FOREIGN KEY (doc_uid) REFERENCES paper_corpus_docs(doc_uid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 4.6 MVP Checklist

- [ ] `docker-compose.yml` 添加 Qdrant 服务
- [ ] `pom.xml` 添加 `io.qdrant:client` 依赖
- [ ] `application.yml` 添加 Qdrant + Embedding 配置
- [ ] 实现 `QdrantConfig` — 客户端 Bean
- [ ] 实现 `QdrantCollectionInitializer` — 自动建集合 + payload 索引
- [ ] 实现 `EmbeddingService` — 文本向量化（复用 OpenAI 兼容接口）
- [ ] 实现 `QdrantRagService` — upsert + search + 维度校验
- [ ] 实现文本分块工具 — 固定窗口 + 重叠切分
- [ ] 实现 `RagIndexEnrichStep` — 嵌入 Agent 链路
- [ ] 实现 `RagRetrieveRerankStep` — 嵌入 Agent 链路
- [ ] Flyway V11 — 建 `paper_corpus_docs` + `paper_corpus_chunks` 表
- [ ] 验证：启动后 Qdrant collection 自动创建
- [ ] 验证：文献分块后成功 upsert 到 Qdrant
- [ ] 验证：输入查询文本返回相关 Top-K 结果

### 4.7 MVP 验收标准（对齐 PRD v3.4）

| 指标 | 阈值 |
|------|------|
| Recall@20 | >= 0.65 |
| nDCG@10 | >= 0.55 |
| 证据覆盖率 | >= 0.90 |
| RAG 检索 P95 | <= 2.5s |
| 引用可验证率 | >= 85% |

---

## 附录 A：Qdrant 关键概念速查

| 概念 | 说明 |
|------|------|
| Collection | 向量集合，类似数据库中的"表" |
| Point | 数据单元 = ID + Vector + Payload |
| Vector | 浮点数组，由 Embedding 模型生成 |
| Payload | 附加的 JSON 元数据，支持过滤查询 |
| HNSW | 近似最近邻搜索算法，Qdrant 默认索引 |
| Distance | 相似度度量：Cosine / Dot / Euclid |
| Segment | 内部存储单元，自动优化管理 |
| WAL | 预写日志，保证数据持久化 |
| Snapshot | 集合快照，用于备份与恢复 |

## 附录 B：Qdrant Java Client 关键 API

```java
// 创建集合
client.createCollectionAsync(name, vectorParams);

// 检查集合是否存在
client.collectionExistsAsync(name);

// 插入/更新点
client.upsertAsync(collectionName, points, null);

// 相似度搜索
client.queryAsync(QueryPoints.newBuilder()...build());

// 创建 payload 索引
client.createPayloadIndexAsync(collectionName, field, type, ...);

// 删除点
client.deleteAsync(collectionName, pointsSelector);

// 获取集合信息
client.getCollectionInfoAsync(collectionName);

// 创建快照
client.createSnapshotAsync(collectionName);
```
