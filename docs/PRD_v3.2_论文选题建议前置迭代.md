# Smart Code Ark v3.2（更新版）论文选题建议前置迭代 + 论文溯源

- 文档版本：v3.2.1
- 文档日期：2026-03-23
- 适用系统：`frontend-web`、`services/api-gateway-java`
- 关联现状：后端已具备论文任务链路（`topic_clarify -> academic_retrieve -> rag_index_enrich -> rag_retrieve_rerank -> outline_generate -> outline_expand -> outline_quality_check -> quality_rewrite`）

## 1. 背景与问题
当前 v3.2 原始目标是“选题建议前置”，但现有代码已经具备论文检索与 RAG 证据能力，文档与实现存在不一致：
1. 代码已落地多源学术检索（Semantic Scholar / Crossref / arXiv）与证据持久化。
2. 文档仍将“文献检索后再建题”描述为 Out of Scope。
3. 前端尚未把已有证据链路完整呈现为“论文溯源”。

## 2. 本次更新目标
1. 保持 v3.2 的选题建议前置目标不变。
2. 在同一版本中新增“论文溯源”能力定义，复用现有后端链路与接口。
3. 统一 PRD 与代码现状，减少需求-实现偏差。

## 3. 范围定义

### 3.1 In Scope
1. 选题建议前置（建议、再建议、采纳后进入 outline）。
2. 论文溯源展示：章节引用来源、证据片段、来源平台、原文链接。
3. 复用现有后端接口：
- `POST /api/paper/outline`
- `GET /api/paper/outline/{taskId}`
- `GET /api/paper/manuscript/{taskId}`
- `GET /api/paper/rag/retrieval/{taskId}`
- `GET /api/paper/rag/stats`
- `POST /api/paper/rag/reindex`

### 3.2 Out of Scope
1. 新增复杂导师评分系统。
2. 新增多轮长对话 Agent（超出 v3.2 节奏）。
3. 外部引用格式自动导出（BibTeX/EndNote）高级能力。

## 4. 现有代码链路映射（作为需求基线）
1. `topic_clarify`：细化题目与研究问题。
2. `academic_retrieve`：多源检索并去重，结果持久化到 `paper_sources`。
3. `rag_index_enrich`：将来源文献切分入库，写入 `paper_corpus_docs/chunks`。
4. `rag_retrieve_rerank`：召回与重排证据，写入 `ragEvidenceItems` 上下文。
5. `outline_generate`：生成大纲并结合 source + RAG evidence。
6. `outline_expand`：扩展内容。
7. `outline_quality_check`：质检与覆盖度检查。
8. `quality_rewrite`：按问题回写。

## 5. 论文溯源能力定义（新增）

### 5.1 用户价值
1. 用户可查看“章节/观点”对应的来源文献与证据片段。
2. 用户可追溯来源平台、论文标题、链接、年份，判断可信度。
3. 用户可在结果页直接复核证据，降低“幻觉”感知风险。

### 5.2 溯源展示最小字段
前端应至少展示：
1. `paperId`
2. `title`
3. `url`
4. `year`
5. `chunkUid`
6. `chunkType`
7. `content`（证据片段）
8. `vectorScore` 与 `rerankScore`（若有）

### 5.3 溯源数据来源
1. 大纲接口 `GET /api/paper/outline/{taskId}` 中 `references` / `chapters`（模型输出侧）。
2. RAG 检索接口 `GET /api/paper/rag/retrieval/{taskId}`（系统证据侧）。
3. 以 RAG 证据接口作为“可核验来源”主展示数据。

## 6. 接口契约（v3.2.1）

### 6.1 复用接口：获取溯源证据
- `GET /api/paper/rag/retrieval/{taskId}`

响应示例：
```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "taskId": "t_xxx",
    "evidenceItems": [
      {
        "chunkUid": "chunk_xxx",
        "docUid": "doc_xxx",
        "paperId": "crossref:10.xxxx/xxxx",
        "title": "Sample Paper",
        "content": "Evidence snippet...",
        "url": "https://doi.org/...",
        "year": 2023,
        "vectorScore": 0.82,
        "rerankScore": 0.91,
        "chunkType": "abstract"
      }
    ],
    "totalChunksSearched": 128
  }
}
```

### 6.2 复用接口：大纲与文稿
- `GET /api/paper/outline/{taskId}`
- `GET /api/paper/manuscript/{taskId}`

要求：前端将 `references` 与 `rag/retrieval` 联合展示，优先以 `rag/retrieval` 作为证据卡片。

## 7. 页面与状态机（前端）
状态：
1. `idle`
2. `suggesting`
3. `suggested`
4. `accepted`
5. `submitting_outline`
6. `running_outline`
7. `showing_result`
8. `showing_trace`（新增）
9. `error`

关键流转：
1. `showing_result -> showing_trace`：点击“查看溯源证据”。
2. `showing_trace -> showing_result`：返回大纲/文稿页。
3. `showing_trace` 支持按来源平台、年份、章节关键词筛选。

## 8. 数据模型（与现有实现对齐）
1. `paper_topic_session`：选题会话与研究问题。
2. `paper_sources`：多源检索结果（`source`, `paper_id`, `title`, `url`, `evidence_snippet`, `section_key`）。
3. `paper_corpus_docs`：入库文献元数据（`doc_uid`, `paper_id`, `doi`, `source`, `citation_count`）。
4. `paper_corpus_chunks`：文献切片（`chunk_uid`, `chunk_type`, `content`）。
5. `paper_outline_versions`：大纲/文稿版本。

## 9. 埋点与指标（新增溯源维度）
事件：
1. `paper_trace_view_opened`
2. `paper_trace_item_clicked`
3. `paper_trace_filter_applied`
4. `paper_trace_back_to_outline`

指标：
1. 溯源页打开率（结果页 UV -> 溯源页 UV）
2. 证据点击率（trace item CTR）
3. 证据为空率（`evidenceItems` 为空任务占比）
4. 选题到大纲转化率

## 10. 质量要求与验收标准（DoD）
1. 用户从“无题目”可完成“建议 -> 采纳 -> 大纲生成”。
2. 大纲结果页可进入溯源页并展示可点击证据卡片。
3. 溯源卡片最小字段完整率 >= 95%。
4. `GET /api/paper/rag/retrieval/{taskId}` 在任务完成后可稳定返回结构化证据。
5. 不破坏现有论文接口路径与返回主结构。

## 11. 迭代计划（更新）

### Phase 1（MVP，1周）
1. 选题建议前置页面与流程打通。
2. 复用现有 outline 任务链路。
3. 结果页增加“查看溯源证据”入口。

### Phase 2（增强，1周）
1. 溯源证据筛选（来源/年份/关键词）。
2. 证据卡片与章节联动高亮。
3. 空证据与失败场景兜底提示。

### Phase 3（优化，持续）
1. 引用格式导出能力（后续版本）。
2. 溯源质量评分与推荐证据排序优化。
