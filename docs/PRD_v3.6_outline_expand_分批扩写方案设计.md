# PRD v3.6：Outline Expand 分批扩写方案设计

## 1. 背景与问题

当前 `outline_expand` 一次性将以下信息拼装后请求大模型：

- 全量 `outlineJson`
- 全量 `sources`
- 全量 `ragEvidence`
- 论文主题与研究问题

在任务规模较大时会出现：

- 单次请求 token 输入过大，推理耗时显著上升（60s~120s 级别）
- 请求慢时任务表现为长时间停留在 `outline_expand`
- 模型在长上下文下稳定性下降，出现占位文本、结构漂移

当前链路位置：

- 步骤入口：[OutlineExpandStep](file:///Users/fu/FuYao/vibeCoding/trace/smart_code_ark/services/api-gateway-java/src/main/java/com/smartark/gateway/agent/step/OutlineExpandStep.java)
- 模型调用：[ModelService.expandPaperOutline](file:///Users/fu/FuYao/vibeCoding/trace/smart_code_ark/services/api-gateway-java/src/main/java/com/smartark/gateway/service/ModelService.java#L672-L709)

## 2. 目标

- 将 `outline_expand` 从“单次大请求”改为“分批扩写 + 合并”
- 降低单次请求 token 峰值与超长阻塞概率
- 保持或提升正文质量与可溯源一致性
- 保持现有任务协议兼容（`task_steps`、`paper_outline_versions` 不破坏）

## 3. 非目标

- 不改动前端页面协议
- 不引入新的外部中间件
- 不在本期重构 `outline_generate / quality_rewrite` 主流程

## 4. 设计原则

- 小批次：按章节/分组扩写，控制每次上下文尺寸
- 可恢复：任一批次失败可重试，不影响已成功批次
- 可观测：每个批次打印输入规模、耗时、产物摘要
- 可回滚：通过配置开关可退回旧单次模式

## 5. 方案总览

### 5.1 核心思路

将 `outline_expand` 拆为四段：

1. `BatchPlan`：按章节切分大纲（每批 N 章）
2. `BatchExpand`：逐批调用模型扩写（可并发，受限）
3. `BatchMerge`：合并章节、重建 `citationMap` 索引
4. `QualityGate`：对合并结果执行门禁校验

### 5.2 批次策略

- 默认按章节切分，`batchChapterSize=2`
- 若章节很短，可升至 `3`
- 若章节很长（按字符估算阈值），降至 `1`

### 5.3 每批输入

- 全局上下文（主题、学科、研究问题）
- 本批章节 outline（不是全量）
- 与本批章节相关的 evidence（裁剪后）
- citations 约束说明（禁止编造索引）

## 6. 关键模块设计

### 6.1 OutlineExpandStep（编排）

新增逻辑：

- 计算章节批次计划
- 循环执行批次扩写
- 批次级重试（例如每批最多 2 次）
- 合并后统一质量门禁

保留逻辑：

- 最终写入 `paper_outline_versions.manuscript_json`
- 更新 `chapter_evidence_map_json`

### 6.2 ModelService（模型调用）

新增方法建议：

- `expandPaperOutlineBatch(...)`
  - 输入：全局上下文 + `batchOutlineJson` + `batchEvidence`
  - 输出：仅本批 chapters + 本批 citationMap

保留方法：

- `expandPaperOutline(...)` 作为兼容入口；可在开关关闭时走旧模式

### 6.3 Evidence 裁剪

新增 `EvidenceSelector`：

- 基于章节标题/section/subsection 文本与 evidence 标题做轻量匹配
- 每章保留 Top-K evidence（建议 `K=8`）
- 无命中时回退到全局 top 小集合（如 5 条）

### 6.4 合并与索引重排

新增 `CitationMapMerger`：

- 将各批 `citationMap` 合并并去重（按 `chunkUid`）
- 重排为全局连续 `citationIndex`
- 回写每个 section 的 citations 为新索引

## 7. 质量门禁（升级后）

门禁粒度：

- 批次级：结构校验（chapters/sections/citations）
- 全局级：正文质量校验（非全占位、核心字段非空）

失败策略：

- 批次失败：仅重试该批
- 全局门禁失败：触发步骤失败并输出精确原因

## 8. 配置设计

在 `application.yml` 增加：

- `smartark.paper.expand.batch-enabled`（默认 `true`）
- `smartark.paper.expand.batch-chapter-size`（默认 `2`）
- `smartark.paper.expand.batch-max-retries`（默认 `2`）
- `smartark.paper.expand.batch-max-workers`（默认 `2`）
- `smartark.paper.expand.chapter-evidence-topk`（默认 `8`）
- `smartark.model.request-timeout-ms`（默认 `45000`）

## 9. 可观测性与日志

新增日志字段：

- 批次计划：总章节、批次数、每批章节范围
- 批次执行：输入 token 估计、evidence 数、耗时、重试次数
- 批次产物：章节数、section 数、占位比
- 合并结果：总章节、总 citations、去重率

## 10. 数据兼容

- 不新增核心业务表
- 仍使用 `paper_outline_versions.manuscript_json/chapter_evidence_map_json`
- 如需审计可选新增 `paper_expand_batch_history`（后续迭代）

## 11. 风险与对策

- 分批后跨章一致性下降  
  - 对策：每批保留全局主题、研究问题、术语约束
- citationMap 重排错误  
  - 对策：合并后执行索引一致性检查
- 并发导致限流  
  - 对策：`batch-max-workers` 缺省小值 + 降级串行

## 12. 分阶段实施

### Phase 1（最小可用）

- 实现按章节分批串行扩写
- 实现批次级重试 + 全局合并
- 保持现有门禁

### Phase 2（稳定性）

- 增加 evidence 裁剪
- 增加请求超时与慢调用告警
- 增加更细日志

### Phase 3（性能）

- 可控并发扩写
- 批次动态大小（基于输入长度）

## 13. 验收标准

- 单任务 `outline_expand` 平均耗时下降（目标 30%+）
- 卡死率下降（长时间 running 无日志）
- 正文占位失败率下降
- 任务成功率提升且无前端协议变更

## 14. 参考实现思路（业界）

长文抽取/生成普遍采用：

- 分块
- 并行
- 多轮抽取/合并

该模式与 LangExtract 的长文策略方向一致，可作为本方案方法论参考。
