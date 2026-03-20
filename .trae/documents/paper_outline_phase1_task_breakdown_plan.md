# 论文框架生成 Phase 1 任务拆解计划

## 1. Summary

目标：基于现有 Java Agent 架构，落地“输入主题 -> 生成三级论文大纲 + 每节证据线索 + 引文 ID/URL”的最小可用闭环。

本计划按已确认决策执行：

1. 主模型固定为 `Qwen-plus`。
2. 新增独立任务入口（不复用 `/api/generate`）。
3. 每个小节默认保留 3 条证据线索。
4. 参考文献格式优先 `GB/T 7714`。

***

## 2. Current State Analysis

### 2.1 已有能力（可复用）

1. **可恢复 Agent 编排主干已具备**

   * 统一调度与状态流转：`AgentOrchestrator.run`

   * Step 注册方式：`Map<String, AgentStep>`

   * 重试/取消/错误分类能力已存在

   * 文件：`services/api-gateway-java/src/main/java/com/smartark/gateway/agent/AgentOrchestrator.java`

2. **Step 协议稳定**

   * 接口：`AgentStep#getStepCode / execute`

   * 执行上下文：`AgentExecutionContext`（可扩展新增论文场景字段）

   * 文件：

     * `services/api-gateway-java/src/main/java/com/smartark/gateway/agent/AgentStep.java`

     * `services/api-gateway-java/src/main/java/com/smartark/gateway/agent/AgentExecutionContext.java`

3. **任务生命周期与状态查询链路可复用**

   * 任务创建、初始化 step、异步执行入口：`TaskService#createAndStartTask`

   * 状态查询：`/api/task/{taskId}/status`

   * 文件：

     * `services/api-gateway-java/src/main/java/com/smartark/gateway/service/TaskService.java`

     * `services/api-gateway-java/src/main/java/com/smartark/gateway/controller/TaskController.java`

4. **模型接入为 OpenAI-compatible，可配置模型名**

   * 配置项：`smartark.model.code-model`

   * 当前默认 `qwen-plus`

   * 文件：

     * `services/api-gateway-java/src/main/resources/application.yml`

     * `services/api-gateway-java/src/main/java/com/smartark/gateway/service/ModelService.java`

5. **Prompt 模板化与历史追踪基础已存在**

   * 表结构：`prompt_templates / prompt_versions / prompt_history`

   * 文件：

     * `services/api-gateway-java/src/main/resources/db/migration/V1__init.sql`

     * `services/api-gateway-java/src/main/resources/db/migration/V5__seed_prompt_templates.sql`

### 2.2 当前缺口（本期必须补齐）

1. 尚无“论文框架”独立 API 与任务类型。
2. 尚无学术检索接入（Semantic Scholar API client）。
3. 尚无论文会话/证据/大纲版本存储表。
4. 尚无面向论文流程的 4 个 Step（主题澄清 -> 检索 -> 大纲 -> 质检）。
5. 尚无论文结果查询 DTO（章节层级+证据线索+引用字段）。

***

## 3. Proposed Changes（任务拆解）

### A. 数据层（Migration + Entity + Repository）

1. **新增迁移脚本** **`V6__paper_outline_phase1.sql`**

   * 位置：`services/api-gateway-java/src/main/resources/db/migration/`

   * 新增表：

     * `paper_topic_session`

     * `paper_sources`

     * `paper_outline_versions`

   * 关键字段建议：

     * `paper_topic_session`：`id, task_id, project_id, user_id, topic, discipline, degree_level, method_preference, status, created_at, updated_at`

     * `paper_sources`：`id, session_id, section_key, paper_id, title, authors_json, year, venue, url, abstract_text, evidence_snippet, relevance_score, created_at`

     * `paper_outline_versions`：`id, session_id, version_no, outline_json, quality_report_json, citation_style, created_at`

   * 约束/索引：

     * `task_id`、`session_id`、`project_id+created_at` 等检索索引

     * `version_no` 唯一约束（按 session 维度）

2. **新增 JPA 实体与仓储**

   * `db/entity/PaperTopicSessionEntity.java`

   * `db/entity/PaperSourceEntity.java`

   * `db/entity/PaperOutlineVersionEntity.java`

   * `db/repo/PaperTopicSessionRepository.java`

   * `db/repo/PaperSourceRepository.java`

   * `db/repo/PaperOutlineVersionRepository.java`

### B. API 与 DTO（新增论文独立入口）

1. **新增 Controller**

   * `controller/PaperController.java`

   * 路由建议：

     * `POST /api/paper/outline`：提交主题并创建论文框架任务

     * `GET /api/paper/outline/{taskId}`：查询最终论文框架结果（三级结构+证据）

2. **新增 DTO**

   * `dto/PaperOutlineGenerateRequest.java`

     * 字段：`topic, discipline, degreeLevel, methodPreference`

   * `dto/PaperOutlineGenerateResult.java`

     * 字段：`taskId, status`

   * `dto/PaperOutlineResult.java`

     * 字段：`topicRefined, researchQuestions, chapters, qualityChecks, references`

   * `dto/PaperOutlineResult` 内部结构包含：

     * 章-节-小节三级结构

     * 每小节 `evidence[]`（默认 3 条）

     * 每条证据至少含 `paperId, title, url`

### C. 服务编排（TaskService + Orchestrator 扩展）

1. **TaskService 扩展论文任务创建**

   * 文件：`service/TaskService.java`

   * 新增方法：

     * `createPaperOutlineTask(...)`（或同等语义）

   * 初始化 4 个 step：

     * `topic_clarify`

     * `academic_retrieve`

     * `outline_generate`

     * `outline_quality_check`

   * `task_type` 使用独立值：`paper_outline`

2. **AgentExecutionContext 扩展论文上下文字段**

   * 文件：`agent/AgentExecutionContext.java`

   * 增加：

     * `paperSessionId`

     * `topic`

     * `discipline`

     * `degreeLevel`

     * `methodPreference`

     * `retrievedSources`（可选结构化对象列表）

     * `outlineDraft`、`qualityReport`

3. **AgentOrchestrator 兼容新 Step**

   * 文件：`agent/AgentOrchestrator.java`

   * 通过现有 `stepMap` 自动接入新 `AgentStep` 实现

   * 保持现有重试与取消机制不变，按配置控制是否对检索/质检 step 重试

### D. 论文专用 Step（4 个）

新增文件（`agent/step/`）：

1. `TopicClarifyStep.java`

   * 输入：topic + 学科/层次/方法偏好

   * 输出：细化题目、研究边界、研究问题列表

   * 落库：`paper_topic_session`

2. `AcademicRetrieveStep.java`

   * 调用 Semantic Scholar API

   * 产出：候选文献列表（结构化），并为每个小节候选准备证据片段

   * 落库：`paper_sources`

3. `OutlineGenerateStep.java`

   * 基于“研究问题 + 检索结果”生成三级大纲

   * 附带每小节 3 条证据线索（paperId/title/url）

   * 落库：`paper_outline_versions.outline_json`

4. `OutlineQualityCheckStep.java`

   * 校验逻辑闭环、问题-方法一致性、引用可核验性

   * 输出质检报告

   * 落库：`paper_outline_versions.quality_report_json`

### E. 外部检索与模型调用能力

1. **新增 Semantic Scholar 客户端服务**

   * `service/SemanticScholarService.java`

   * 能力：

     * 按主题关键词检索

     * 基础字段标准化（ID、标题、作者、年份、URL、摘要）

   * 配置项新增（`application.yml`）：

     * `smartark.paper.semantic-scholar.base-url`

     * `smartark.paper.semantic-scholar.api-key`

     * `smartark.paper.semantic-scholar.timeout-ms`

2. **ModelService 新增论文场景方法**

   * 文件：`service/ModelService.java`

   * 新增方法（命名示例）：

     * `clarifyPaperTopic(...)`

     * `generatePaperOutline(...)`

     * `qualityCheckPaperOutline(...)`

   * 使用 `qwen-plus` 作为主模型（继承当前 `code-model` 或新增 paper-model 配置）

### F. Prompt 模板（论文场景）

1. **新增迁移脚本** **`V7__seed_paper_prompt_templates.sql`**

   * 插入模板：

     * `paper_topic_clarify`

     * `paper_outline_generate`

     * `paper_outline_quality_check`

   * 默认版本为 1，状态 `published`

   * 输出尽量结构化 JSON，便于 Step 解析与落库

### G. 查询与前端联动（本期最小）

1. **后端结果查询接口返回统一结构**

   * `GET /api/paper/outline/{taskId}` 返回 `PaperOutlineResult`

   * 不改动现有 `/api/task/*` 路由与语义

2. **前端本期最小改动建议**

   * 先用现有任务进度页轮询 `/api/task/{taskId}/status`（复用）

   * 新增一个结果展示页读取 `PaperOutlineResult`（可后续单独实施）

***

## 4. Assumptions & Decisions

1. 本期只覆盖“框架生成”，不自动产出整篇正文。
2. 检索源先固定 Semantic Scholar，不接入 Scite/Elicit。
3. 每小节证据线索固定 3 条，可在后续版本参数化。
4. 引文样式按 GB/T 7714 输出，但保留原始元数据字段以便二次格式化。
5. 论文任务与代码生成任务并存，互不影响现有 `/api/generate` 链路。
6. 保持当前单体部署，不新增 Python 子服务。

***

## 5. Verification（验收与测试）

### 5.1 数据与迁移验证

1. 启动后 Flyway 成功执行 V6/V7。
2. 三张新表结构、索引、唯一约束符合预期。
3. 新增实体 CRUD 冒烟通过。

### 5.2 编排链路验证

1. 调用 `POST /api/paper/outline` 成功创建任务并初始化 4 个 step。
2. `/api/task/{taskId}/status` 可观察 step 进度流转。
3. 失败场景触发重试与错误落库，取消场景可中断任务。

### 5.3 结果正确性验证

1. `GET /api/paper/outline/{taskId}` 返回三级结构（章/节/小节）。
2. 每小节包含 3 条证据线索，且至少含 `paperId/title/url`。
3. 质检报告字段完整（问题-方法一致性、证据可核验性等）。

### 5.4 回归验证

1. `/api/generate`、`/api/task/{id}/status`、`/api/task/{id}/download` 功能不受影响。
2. 现有代码生成 step（`requirement_analyze/codegen_backend/codegen_frontend/sql_generate/package`）可正常执行。

***

## 6. 执行顺序（可直接开工）

1. 先做 Migration（V6）+ Entity/Repository。
2. 再做 API/DTO（PaperController + 请求响应结构）。
3. 扩展 TaskService 创建 `paper_outline` 任务与 4 个 step。
4. 实现 SemanticScholarService + 4 个论文 Step。
5. 增加论文 Prompt 模板迁移（V7）并接入 ModelService。
6. 打通结果查询接口并完成集成测试与回归测试。

