# Smart Code Ark v3.3 论文 RAG 增强迭代需求文档（Qdrant 版）

- 文档版本：v3.3
- 文档日期：2026-03-21
- 适用范围：论文大纲生成链路（`paper_outline`）
- 技术栈决策：向量检索默认采用 `Qdrant`
- 关联模块：`AcademicRetrieveStep`、`ModelService`、`PaperController`、`TaskService`

## 1. 背景与问题

当前论文生成链路已具备“主题澄清 -> 学术检索 -> 大纲生成 -> 质检”基础能力，但内容质量仍存在：

1. 内容来源单一（当前检索源偏少），导致证据覆盖不足。
2. 生成结果依赖单轮检索，章节内容容易泛化。
3. 引文与论点关联弱，用户感知“有框架但不够厚”。
4. 对“无明确选题”用户的后续证据补强能力不足。

## 2. 目标

通过引入 RAG（Retrieval-Augmented Generation）+ Qdrant 向量检索，提升论文生成的证据密度、结构深度与可追溯性。

核心目标：
1. 每个章节有可验证证据支撑，不再依赖纯模型补全。
2. 检索来源更全面，支持中英文文献混合召回。
3. 生成结果可解释：论点来自哪些来源、为何被选中。

## 3. 范围定义

## 3.1 In Scope（v3.3）

1. 新增论文场景 RAG 检索层（外部文献优先）。
2. 建立 Qdrant 向量索引与文献元数据流程（增量更新）。
3. 引入混合检索（关键词 + 向量）与重排（rerank）。
4. 大纲生成强制绑定证据引用。
5. 质检新增“证据覆盖率与引用可验证性”评分。

## 3.2 Out of Scope（v3.3 之后）

1. 用户私有知识库（PDF 上传、团队知识库）
2. 全量自动事实核查与反向检索
3. 复杂多代理协同推理链

## 4. 当前链路与新增 RAG 融合点

现有链路：
1. `topic_clarify`
2. `academic_retrieve`
3. `outline_generate`
4. `outline_quality_check`

新增后链路（建议）：
1. `topic_clarify`
2. `academic_retrieve`（多源原始召回）
3. `rag_index_enrich`（Qdrant 向量入库/更新）
4. `rag_retrieve_rerank`（按 RQ 检索 + 重排）
5. `outline_generate`（使用 RAG 上下文包）
6. `outline_quality_check`（增加证据覆盖质检）

## 5. RAG 技术方案

## 5.1 架构概览

1. 数据采集层：Semantic Scholar + 补充源（OpenAlex/Crossref/中文源）
2. 索引层：MySQL 元数据表 + Qdrant 向量集合
3. 检索层：关键词检索（BM25）+ 向量检索（ANN）+ rerank
4. 生成层：将 TopK 证据片段拼装为上下文，喂给大模型生成章节

## 5.2 检索策略

1. Query 构建：
2. 输入来源：`topicRefined + researchQuestions + discipline + methodPreference`
3. 召回策略：
4. 关键词召回（高精度） + 向量召回（高覆盖）
5. 重排策略：
6. 综合分 = 语义相关性 + 年份衰减 + 引文质量 + 去重惩罚
7. 输出策略：
8. 每个研究问题至少分配 N 条证据（建议 N>=3）

## 5.3 Qdrant 集合规范（新增）

集合名建议：`paper_chunks`

1. `id`：`chunk_uid`（建议 `doc_uid#chunk_no`）
2. `vector`：embedding 向量（固定维度）
3. `payload`：
4. `doc_uid`、`source`、`title`、`year`、`discipline`
5. `doi`、`url`、`language`、`chunk_type`
6. `citation_count`、`token_count`
7. 距离度量：`Cosine`
8. 默认召回：`topK=40`，重排后保留 `topN=12`

## 5.4 生成约束（Prompt 侧）

1. 每个二级/三级小节必须包含证据映射。
2. 每个核心论点给出证据 ID 列表（`doc_uid/chunk_uid`）。
3. 不允许输出“无证据主张”段落（质检阶段拦截）。

## 6. 数据模型设计（MySQL）

## 6.1 文献主表（建议）

表：`paper_corpus_docs`
1. `id` BIGINT PK
2. `doc_uid` VARCHAR(128) UNIQUE
3. `source` VARCHAR(32) // semantic_scholar/openalex/crossref/cnki
4. `title` TEXT
5. `abstract_text` MEDIUMTEXT
6. `keywords_json` JSON
7. `authors_json` JSON
8. `year` INT
9. `venue` VARCHAR(256)
10. `doi` VARCHAR(128)
11. `url` VARCHAR(512)
12. `citation_count` INT
13. `language` VARCHAR(16)
14. `created_at` DATETIME
15. `updated_at` DATETIME

## 6.2 分片表（建议）

表：`paper_corpus_chunks`
1. `id` BIGINT PK
2. `chunk_uid` VARCHAR(160) UNIQUE
3. `doc_uid` VARCHAR(128)
4. `chunk_no` INT
5. `chunk_text` MEDIUMTEXT
6. `chunk_type` VARCHAR(32) // abstract/intro/conclusion/keyword_expansion
7. `token_count` INT
8. `created_at` DATETIME

说明：
1. 向量本体不落 MySQL，向量存储在 Qdrant。
2. MySQL 保存可审计元数据与可追溯关系。

## 6.3 检索结果快照（可选）

表：`paper_rag_retrieval_snapshots`
1. `id` BIGINT PK
2. `task_id` VARCHAR(64)
3. `query_json` JSON
4. `hits_json` JSON
5. `created_at` DATETIME

## 7. 接口与服务改造

## 7.1 后端服务新增

1. `PaperRagIngestService`：文献标准化、分片、embedding、Qdrant upsert
2. `PaperRagRetrieveService`：混合检索、重排、上下文组装
3. `PaperRagQualityService`：证据覆盖率校验
4. `QdrantClientService`：封装 collection/create/upsert/search/delete

## 7.2 链路改造点

1. `AcademicRetrieveStep`：
2. 从单源检索升级为多源聚合召回
3. 召回结果入 `paper_sources` + `paper_corpus_docs/chunks`
4. `OutlineGenerateStep`：
5. 调用 `PaperRagRetrieveService` 获取结构化上下文
6. 使用上下文生成大纲并附证据映射
7. `OutlineQualityCheckStep`：
8. 新增证据覆盖与可验证性评分

## 7.3 新增接口（内部或管理）

1. `POST /api/paper/rag/reindex`：重建索引（管理）
2. `GET /api/paper/rag/retrieval/{taskId}`：查看检索命中（调试）
3. `GET /api/paper/rag/stats`：RAG 命中与覆盖统计

## 8. 质量标准与验收指标

## 8.1 质量指标

1. 证据覆盖率：章节中带引用证据的小节占比 >= 90%
2. 引用可验证率：可访问 URL/DOI 的证据占比 >= 85%
3. 内容充实度：用户主观评分提升（基线 +20%）
4. 生成稳定性：失败率不高于现有链路 +3%

## 8.2 性能指标

1. RAG 检索耗时 P95 <= 2.5s
2. 总体大纲生成耗时 P95 增量 <= 30%
3. 向量入库失败重试成功率 >= 99%

## 9. 迭代计划（可执行 Phase）

## 9.1 Phase 1：RAG MVP（1-2 周）

1. 建立文献主表 + 分片表
2. 接入 Qdrant（集合初始化、upsert、query）
3. 完成 `retrieve + rerank + context pack`
4. 大纲生成增加证据约束

验收：
1. 每小节可看到证据映射
2. 内容明显比基线更充实

## 9.2 Phase 2：多源增强（1 周）

1. 扩展第 2/第 3 文献源
2. 去重融合（DOI/title 相似度）
3. 质量评分中纳入来源质量与年份分层

验收：
1. 同主题下文献覆盖提升
2. 中英文文献比例可配置

## 9.3 Phase 3：质量闭环（持续）

1. 引入反馈回路：用户对建议与大纲评分
2. 低评分样本回灌优化 rerank 与 prompt
3. 建立 RAG 监控面板（命中率、覆盖率、耗时）

## 10. 风险与应对

1. 风险：召回噪声高导致“更多但更乱”
2. 应对：必须加 rerank 与最小证据门槛
3. 风险：Qdrant 运维与容量增长带来成本上升
4. 应对：先单实例 + 分层保留，增长后再做分片/集群
5. 风险：模型编造引用
6. 应对：输出阶段强制证据 ID 映射，质检拦截无证据主张

## 11. DoD（完成定义）

1. 无明确题目的用户经建议后进入大纲链路，输出有证据支撑
2. 大纲输出中每个核心章节可追溯证据来源
3. RAG 相关指标可观测（命中率、覆盖率、耗时、失败率）
4. 不破坏现有论文生成主链路稳定性

## 12. 文档迭代确认

已完成更新：
1. 向量库技术栈已从“可选 pgvector/Qdrant/Milvus”收敛为“Qdrant 默认方案”。
2. 数据模型已调整为“Qdrant 存向量 + MySQL 存元数据”。
3. Phase 计划与服务拆分已同步按 Qdrant 改写。

建议下一轮补充（v3.3.1）：
1. Qdrant 运行参数基线（内存、HNSW 参数、快照策略）。
2. Embedding 模型版本与维度冻结策略。
3. 召回/重排离线评估集与基线分数标准。

