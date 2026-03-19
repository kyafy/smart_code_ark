# Agent 模块 3.4（生成质量）实施计划

## 1. Summary

本计划覆盖 [# Agent 模块重构计划.md:L43-L46](file:///e:/fuyao/project/smart_code_ark/#%20Agent%20%E6%A8%A1%E5%9D%97%E9%87%8D%E6%9E%84%E8%AE%A1%E5%88%92.md#L43-L46) 的三项目标：

1. `changeInstructions` 进入执行上下文并稳定参与后续提示词；
2. 文件生成从“关键词 contains 过滤”升级为“结构化文件清单 + 标签分组”；
3. Prompt 从 `ModelService` 硬编码迁移为“可配置模板（可版本化）”。

总体策略：先做**兼容式落地**（不破坏现有 `/api/generate`、`/api/task/*`），以最小改造接入模板解析与结构化清单；再补观测与灰度切换。

## 2. Current State Analysis

* `changeInstructions` 已从 `modify` 链路注入模型调用，但只作为拼接文本，无策略控制：\
  [TaskService.java](file:///e:/fuyao/project/smart_code_ark/services/api-gateway-java/src/main/java/com/smartark/gateway/service/TaskService.java)、[AgentOrchestrator.java](file:///e:/fuyao/project/smart_code_ark/services/api-gateway-java/src/main/java/com/smartark/gateway/agent/AgentOrchestrator.java)、[ModelService.java](file:///e:/fuyao/project/smart_code_ark/services/api-gateway-java/src/main/java/com/smartark/gateway/service/ModelService.java)。

* 文件清单目前是 `List<String>`，执行分发依赖 `contains("backend"/"frontend")` 与后缀判断，易误判：\
  [RequirementAnalyzeStep.java](file:///e:/fuyao/project/smart_code_ark/services/api-gateway-java/src/main/java/com/smartark/gateway/agent/step/RequirementAnalyzeStep.java)、[AbstractCodegenStep.java](file:///e:/fuyao/project/smart_code_ark/services/api-gateway-java/src/main/java/com/smartark/gateway/agent/step/AbstractCodegenStep.java)。

* 数据库已具备 `prompt_templates/prompt_versions/prompt_history/prompt_cache`，但运行时未接入：\
  [V1\_\_init.sql](file:///e:/fuyao/project/smart_code_ark/services/api-gateway-java/src/main/resources/db/migration/V1__init.sql#L132-L201)。

* `GenerateRequest` 仅含 `projectId`，`generate` 场景无初始 instructions：\
  [GenerateRequest.java](file:///e:/fuyao/project/smart_code_ark/services/api-gateway-java/src/main/java/com/smartark/gateway/dto/GenerateRequest.java)。

## 3. Proposed Changes

### A. changeInstructions 注入策略化（保持兼容）

1. 更新 [TaskService.java](file:///e:/fuyao/project/smart_code_ark/services/api-gateway-java/src/main/java/com/smartark/gateway/service/TaskService.java)

* 在 `generate` 场景支持可选 `instructions`（DTO 可选字段，默认 `null`）。

* `modify` 继续沿用 `changeInstructions`，统一写入 `tasks.instructions`。

1. 更新 [GenerateRequest.java](file:///e:/fuyao/project/smart_code_ark/services/api-gateway-java/src/main/java/com/smartark/gateway/dto/GenerateRequest.java)

* 从 `record GenerateRequest(String projectId)` 升级为 `record GenerateRequest(String projectId, String instructions)`。

* 兼容策略：前端不传时为 `null`，后端按当前逻辑运行。

1. 更新 [AgentExecutionContext.java](file:///e:/fuyao/project/smart_code_ark/services/api-gateway-java/src/main/java/com/smartark/gateway/agent/AgentExecutionContext.java)

* 增加 `normalizedInstructions`（去空白/长度裁剪后的稳定版本），供模板渲染统一使用。

### B. 结构化文件清单 + 标签分组

1. 新增 DTO：`FilePlanItem`

* 文件路径：`services/api-gateway-java/src/main/java/com/smartark/gateway/agent/model/FilePlanItem.java`

* 字段：`path`, `group`, `priority`, `reason`。

* `group` 规范值：`backend|frontend|database|infra|docs`。

1. 更新 [RequirementAnalyzeStep.java](file:///e:/fuyao/project/smart_code_ark/services/api-gateway-java/src/main/java/com/smartark/gateway/agent/step/RequirementAnalyzeStep.java)

* 优先解析模型返回的结构化数组（`FilePlanItem[]`）。

* 兼容旧返回：若模型仍返回 `List<String>`，在 Step 内回退映射为 `FilePlanItem`。

* 将结构化结果保存到 `AgentExecutionContext`（新增 `filePlan` 字段）。

1. 更新 [AbstractCodegenStep.java](file:///e:/fuyao/project/smart_code_ark/services/api-gateway-java/src/main/java/com/smartark/gateway/agent/step/AbstractCodegenStep.java)

* 删除 `contains(keyword)` 逻辑，改为按 `group` 精确过滤。

* 增加路径规范化与安全校验（拒绝 `..`、绝对路径、空路径）。

* 执行顺序按 `priority` 升序（默认 50）。

1. 更新具体 Step

* [CodegenBackendStep.java](file:///e:/fuyao/project/smart_code_ark/services/api-gateway-java/src/main/java/com/smartark/gateway/agent/step/CodegenBackendStep.java) 只处理 `backend`。

* [CodegenFrontendStep.java](file:///e:/fuyao/project/smart_code_ark/services/api-gateway-java/src/main/java/com/smartark/gateway/agent/step/CodegenFrontendStep.java) 只处理 `frontend`。

* [SqlGenerateStep.java](file:///e:/fuyao/project/smart_code_ark/services/api-gateway-java/src/main/java/com/smartark/gateway/agent/step/SqlGenerateStep.java) 处理 `database/infra/docs`（可配）。

### C. Prompt 模板化（可版本化）接入运行时

1. 新增运行时组件

* `PromptResolver`：按 `template_key + version` 解析模板（默认取模板默认版本）。

* `PromptRenderer`：对 `{{prd}}/{{stack}}/{{filePath}}/{{instructions}}` 做变量渲染。

* 路径：`services/api-gateway-java/src/main/java/com/smartark/gateway/prompt/`

1. 新增仓储

* `PromptTemplateRepository`、`PromptVersionRepository`、`PromptHistoryRepository`（如不存在则补齐）。

* 路径：`services/api-gateway-java/src/main/java/com/smartark/gateway/db/repo/`

1. 更新 [ModelService.java](file:///e:/fuyao/project/smart_code_ark/services/api-gateway-java/src/main/java/com/smartark/gateway/service/ModelService.java)

* `generateProjectStructure` 与 `generateFileContent` 改为：

  1. 从模板表加载 system/user 模板；
  2. 使用 `PromptRenderer` 渲染；
  3. 调模型；
  4. 写 `prompt_history`（token、状态、错误码、延迟）。

* 失败回退：模板不可用时回退到现有硬编码 prompt（保证不中断）。

1. 补充初始化模板迁移

* 新增 `V5__seed_prompt_templates.sql`：

  * 初始化 `project_structure_plan`、`file_content_generate` 两类模板和 `version_no=1`。

  * 状态设为 `published`，便于默认读取。

### D. 前端最小改动

1. 更新 [endpoints.ts](file:///e:/fuyao/project/smart_code_ark/frontend-web/src/api/endpoints.ts) 与相关类型

* `taskApi.generate` 支持可选 `instructions` 字段（保持原调用兼容）。

* 不强制修改现有页面流程，后续可在“高级选项”中暴露。

## 4. Assumptions & Decisions

* 本次不改变核心任务状态机，只提升生成质量路径。

* `generate` 的 `instructions` 设为可选，保持后向兼容。

* 先接入模板表与版本读取，不做后台管理界面（仅 DB + 代码接入）。

* 结构化清单采用“新格式优先、旧格式回退”，保证线上平滑切换。

* Prompt 渲染失败不阻断任务，回退硬编码提示词并记录告警日志。

## 5. Verification Steps

1. **编译检查**

* 后端：`mvn -DskipTests compile`

* 前端：`npm run build`

1. **指令注入验证**

* 调用 `/api/generate` 传可选 `instructions` 与 `/modify` 传 `changeInstructions`。

* 验证 `tasks.instructions` 落库，生成内容包含指令影响。

1. **结构化清单验证**

* 在 `RequirementAnalyzeStep` 打印结构化计划，确认 `group/priority` 生效。

* 验证 backend/frontend/database 文件由对应 Step 精确消费。

1. **模板化验证**

* 在 DB 修改模板文案（同版本或升版本）后重新生成任务。

* 验证输出随模板变化，且 `prompt_history` 记录成功。

1. **回退验证**

* 人工制造模板缺失/渲染异常，验证自动回退到硬编码 prompt 且任务可完成。

1. **回归验证**

* `/api/generate`、`/api/task/{id}/status`、`/api/task/{id}/logs`、`/api/task/{id}/download` 正常。

