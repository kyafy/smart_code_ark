# Smart Code Ark v3.4 论文智能生成融合需求文档（选题建议前置 + RAG/Qdrant）

- 文档版本：v3.4
- 文档日期：2026-03-21
- 融合来源：`v3.2`（选题建议前置）+ `v3.3`（RAG 增强）
- 适用范围：论文业务全链路（选题建议 -> 大纲生成 -> 质检）
- 技术栈决策：向量检索默认采用 `Qdrant`

## 1. 背景与目标

## 1.1 现状问题

1. 论文入口要求用户直接给出明确选题，门槛高。
2. 论文内容来源偏单一，证据覆盖不足，生成内容偏薄。
3. 引文可追溯性弱，质量评价主要停留在描述性。

## 1.2 融合目标

1. 前置“选题建议”降低使用门槛，让无明确题目的用户也可启动链路。
2. 引入 RAG + Qdrant 提升证据密度与内容深度。
3. 输出可追溯的章节证据映射，构建质量闭环。

## 2. 产品范围

## 2.1 In Scope

1. 新增论文页“AI 选题建议”流程（suggest/refine/accept）。
2. 保留“已有题目直接生成”流程。
3. 引入多源文献召回 + 向量检索 + 重排（rerank）。
4. 大纲生成强制证据映射，质检增加证据覆盖与可验证性。
5. 监控选题转化与 RAG 效果指标。

## 2.2 Out of Scope

1. 用户私有知识库上传（PDF/笔记库）
2. 全自动事实核查引擎
3. 复杂多 Agent 协作

## 3. 融合业务流程

1. 用户进入论文页，选择“我有题目”或“AI 帮我选题”。
2. 走 AI 选题时，输入想法与约束，系统返回 3-5 条建议。
3. 用户可采纳、微调再生成或重新生成。
4. 采纳后触发论文大纲任务。
5. 大纲任务链路升级为：
6. `topic_clarify -> academic_retrieve -> rag_index_enrich -> rag_retrieve_rerank -> outline_generate -> outline_quality_check`

## 4. 页面状态机

## 4.1 选题建议状态机

1. `idle`
2. `suggesting`
3. `suggested`
4. `refining`
5. `accepted`
6. `submitting_outline`
7. `done`
8. `error`

流转：
1. `idle -> suggesting -> suggested`
2. `suggested -> refining -> suggested`
3. `suggested -> accepted -> submitting_outline -> done`
4. 失败态回退前一可重试状态

## 4.2 大纲生成状态机（新增 RAG 节点）

1. `topic_clarify`
2. `academic_retrieve`
3. `rag_index_enrich`
4. `rag_retrieve_rerank`
5. `outline_generate`
6. `outline_quality_check`

## 5. 接口契约（融合版）

## 5.1 选题建议接口

1. `POST /api/paper/topic/suggest`
2. `POST /api/paper/topic/suggest/refine`
3. `POST /api/paper/topic/suggest/accept`（可选，做采纳持久化）

## 5.2 大纲与结果接口

1. `POST /api/paper/outline`（复用）
2. `GET /api/paper/outline/{taskId}`（复用）

## 5.3 RAG 管理接口（内部）

1. `POST /api/paper/rag/reindex`
2. `GET /api/paper/rag/retrieval/{taskId}`
3. `GET /api/paper/rag/stats`

## 6. 数据模型（融合版）

## 6.1 选题建议侧

表：`paper_topic_suggestions`
1. `suggestion_id`、`user_id`、`idea`
2. `discipline`、`degree_level`、`method_preference`
3. `constraints_text`、`items_json`
4. `status`（generated/accepted/discarded）
5. `created_at`、`updated_at`

## 6.2 RAG 语料侧（MySQL）

表：`paper_corpus_docs`
1. `doc_uid`、`source`、`title`、`abstract_text`
2. `keywords_json`、`authors_json`、`year`、`venue`
3. `doi`、`url`、`citation_count`、`language`

表：`paper_corpus_chunks`
1. `chunk_uid`、`doc_uid`、`chunk_no`
2. `chunk_text`、`chunk_type`、`token_count`

表：`paper_rag_retrieval_snapshots`（可选）
1. `task_id`、`query_json`、`hits_json`

## 6.3 向量库侧（Qdrant）

集合：`paper_chunks`
1. `id`：`chunk_uid`
2. `vector`：embedding 向量
3. `payload`：`doc_uid/source/title/year/discipline/doi/url/language/chunk_type/citation_count`

## 7. 三个工程细节（补充项）

## 7.1 Qdrant 参数基线（MVP）

Collection 建议基线：
1. Distance：`Cosine`
2. HNSW：`m=16`，`ef_construct=200`
3. Search：`hnsw_ef=64`（默认），高质量模式可升到 `128`
4. Optimizer：`indexing_threshold=20000`
5. Payload：`on_disk_payload=true`（降低内存压力）
6. Shard：MVP 单分片，副本 `replication_factor=1`（生产按可用性升级）
7. 快照：每日快照 + 保留 7 天，重大重建前强制快照

运行阈值建议：
1. Qdrant 内存使用 > 80% 告警
2. 检索 P95 > 2.5s 告警
3. Upsert 失败率 > 1% 告警

## 7.2 Embedding 维度冻结策略

1. v3.4 锁定单一 embedding 模型与维度（例如 1024/1536 之一），禁止混用。
2. 新增配置项：
3. `paper.rag.embedding.model=xxx`
4. `paper.rag.embedding.dimension=xxxx`
5. 入库前做维度校验，不匹配则拒绝写入并打审计日志。
6. 模型升级策略：
7. 新建并行集合（如 `paper_chunks_v2`），双写 + 灰度读。
8. 回归通过后切流，旧集合延迟下线。

## 7.3 离线评测基线（必须）

评测集构建：
1. 从历史任务中抽取 200-500 条“题目/研究问题/高质量证据”样本。
2. 分学科、学位层级、方法偏好分桶采样。

评测指标：
1. Recall@20
2. MRR@10
3. nDCG@10
4. EvidenceCoverage（章节证据覆盖率）

MVP 基线阈值（首版）：
1. Recall@20 >= 0.65
2. nDCG@10 >= 0.55
3. 证据覆盖率 >= 0.90

上线门槛：
1. 新策略离线指标不低于基线
2. 线上灰度 1 周后，采纳率/满意度不下降

## 8. 质量与验收指标

1. 建议触发率、采纳率、建议到大纲转化率
2. 引用可验证率 >= 85%
3. 大纲证据覆盖率 >= 90%
4. 大纲生成失败率不高于现网 +3%
5. RAG 检索 P95 <= 2.5s

## 9. 迭代计划（可执行）

## 9.1 Phase 1（1-2 周）

1. 上线选题建议前置流程（suggest + accept）
2. 接入 Qdrant 与基础 RAG 检索
3. 大纲生成接入证据映射

## 9.2 Phase 2（1 周）

1. 上线 refine 能力与建议历史
2. 多源召回 + 去重融合
3. 质检加入证据覆盖评分

## 9.3 Phase 3（持续）

1. 离线评测集持续扩充
2. rerank/prompt 迭代优化
3. 看板与告警完善（命中率、覆盖率、耗时）

## 10. 风险与应对

1. 召回噪声上升导致内容变乱
2. 通过 rerank + 证据门槛 + 质检拦截控制
3. Qdrant 容量增长带来成本压力
4. 通过分层保留与快照治理控制成本
5. 模型升级造成维度不兼容
6. 通过维度冻结 + 双集合迁移规避

## 11. DoD（完成定义）

1. 用户可从“无题目”完成建议采纳并触发论文生成
2. 生成结果具备可追溯证据映射
3. Qdrant、Embedding、离线评测三项工程基线落地
4. 不破坏现有论文主链路稳定性

