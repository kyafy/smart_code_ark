# JeecgBoot 代码生成链路迁移落地评估与实施计划（smart_code_ark）

版本: v1.0  
日期: 2026-04-02

## 1. 结论总览（先说结论）

1. JeecgBoot 模板可以迁移到当前项目，但不能直接“无损替换”现有 `template-repo`。建议改为“并行模板源 + 渐进替换”。
2. JeecgBoot 真正代码生成逻辑可以迁移到当前链路，但不建议直接硬塞进现有 LLM 步骤，建议做 `CodegenEngine SPI` + `Jeecg Adapter/Sidecar`。
3. 当前代码具备“构建验证 + 运行烟测 + 预览发布”能力，但还不具备“生产级自动构建镜像并部署到目标环境（含镜像推送、环境发布、回滚）”能力。

## 2. 现状证据（smart_code_ark）

### 2.1 当前模板机制是“静态模板仓 + 占位符替换 + LLM补全”

证据:

1. `TemplateRepoService` 仅做模板文件枚举、复制和占位符替换，核心替换仅 `__PROJECT_NAME__`、`__DISPLAY_NAME__`。  
   文件: `services/api-gateway-java/src/main/java/com/smartark/gateway/service/TemplateRepoService.java`
2. `RequirementAnalyzeStep` 先用 LLM 规划文件结构，再合并模板文件，并 materialize 模板到工作区。  
   文件: `services/api-gateway-java/src/main/java/com/smartark/gateway/agent/step/RequirementAnalyzeStep.java`
3. `AbstractCodegenStep` 逐文件调用 `ModelService.generateFileContent*` 生成内容；模板仅提供示例代码上下文。  
   文件: `services/api-gateway-java/src/main/java/com/smartark/gateway/agent/step/AbstractCodegenStep.java`
4. `ModelService` 的生成核心是提示词模板 + 大模型调用，不是规则模板引擎。  
   文件: `services/api-gateway-java/src/main/java/com/smartark/gateway/service/ModelService.java`

### 2.2 当前任务链路没有“镜像推送 + 目标环境部署”步骤

证据:

1. 任务步骤为 `requirement_analyze -> codegen_* -> sql_generate -> artifact_contract_validate -> build_verify -> runtime_smoke_test -> package`。  
   文件: `services/api-gateway-java/src/main/java/com/smartark/gateway/service/TaskService.java`
2. `BuildVerifyService` 做的是构建命令探测与执行（maven/gradle/npm/docker compose config），不是镜像构建推送发布。  
   文件: `services/api-gateway-java/src/main/java/com/smartark/gateway/service/BuildVerifyService.java`
3. `RuntimeSmokeTestService` 通过 `ContainerRuntimeService` 起容器做前端烟测。  
   文件: `services/api-gateway-java/src/main/java/com/smartark/gateway/service/RuntimeSmokeTestService.java`
4. `PreviewDeployService` 是“预览部署”，可回退到静态 URL，目标是预览链路而非生产发布链路。  
   文件: `services/api-gateway-java/src/main/java/com/smartark/gateway/service/PreviewDeployService.java`
5. `ContainerRuntimeService` 仅在 `smartark.preview.enabled=true` 时生效，定位就是预览容器运行时。  
   文件: `services/api-gateway-java/src/main/java/com/smartark/gateway/service/ContainerRuntimeService.java`

## 3. 你的三个问题逐条回答

### 3.1 JeecgBoot 模板是否可迁移并替换 template-repo？

结论: **可迁移，不能一步到位完全替换**。

原因:

1. 当前 `template-repo` 是“可直接运行的项目骨架模板”，Jeecg 模板是“代码片段/文件规则模板（如 .javai/.vuei/.ftl）”。两者粒度不同。
2. 现有 TemplateRepoService 依赖 `template.json` 元数据和示例文件映射；Jeecg 模板体系需要更复杂的数据模型（表结构、字段、主键策略、一对多关系、风格参数）。

建议迁移方式:

1. 保留 `template-repo` 作为“项目脚手架层”。
2. 新增 `jeecg-template-pack` 作为“业务代码模板层”。
3. 生成时先落脚手架，再用 Jeecg 引擎补齐 CRUD/页面/SQL。

### 3.2 Jeecg 真正代码生成逻辑是否可迁移到当前链路？

结论: **可迁移，建议通过引擎适配层，不建议直接替换现有 LLM 逻辑**。

建议架构:

1. 定义 `CodegenEngine` 接口:
   1. `generateStructure(...)`
   2. `generateBackend(...)`
   3. `generateFrontend(...)`
   4. `generateSql(...)`
2. 实现两套引擎:
   1. `LlmCodegenEngine`（保留现有能力）
   2. `JeecgCodegenEngine`（调用 Jeecg codegenerate）
3. 在任务参数中加入 `codegenEngine=llm|jeecg|hybrid`。
4. `hybrid` 模式优先规则生成（Jeecg），缺失文件再走 LLM。

落地选型建议:

1. **优先 Sidecar 方案**: 单独 Java 进程承载 Jeecg 依赖与 DB 元数据读取，通过 HTTP/CLI 调用。
2. 次选嵌入式方案: 直接在 `api-gateway-java` 加依赖，风险是依赖冲突、升级困难、上下文耦合高。

### 3.3 自动构建镜像并部署到目标环境，当前代码能实现吗？

结论: **当前只能部分实现，不足以覆盖生产级一键部署**。

已具备:

1. 构建验证（build_verify）。
2. runtime smoke test。
3. preview 自动发布（面向预览环境）。
4. 产物打包下载（zip）。

未具备:

1. 镜像构建与标签策略（版本号、commit sha）。
2. 镜像推送到 registry。
3. 目标环境部署编排（Compose/K8s 正式环境）。
4. 发布审批、发布记录、失败自动回滚。

## 4. 分阶段实施方案（可执行）

### P0（1 周）: 可并行接入，不影响现网

1. 新增配置项:
   1. `smartark.codegen.engine.default=llm`
   2. `smartark.codegen.jeecg.enabled=false`
   3. `smartark.codegen.jeecg.endpoint=http://jeecg-codegen-sidecar:18080`
2. 定义 `CodegenEngine SPI` 与路由器（默认仍走 llm）。
3. 保持现有 API 不变，仅在 options 增加可选 `codegenEngine`。

验收:

1. 不改前端即可继续跑通当前链路。
2. 配置切换不影响已有任务。

### P1（1~2 周）: Jeecg 引擎接入（最小可用）

1. 新建 `jeecg-codegen-sidecar`（可独立仓或 services 下子模块）。
2. 提供接口:
   1. `/generate/single-table`
   2. `/generate/one-to-many`
3. `api-gateway-java` 在 `codegen_backend/codegen_frontend/sql_generate` 中调用 sidecar。
4. 失败兜底到 LLM 生成。

验收:

1. 指定 `codegenEngine=jeecg` 可生成至少 SpringBoot+Vue3+MySQL 的标准 CRUD。
2. 失败时链路自动回退 `hybrid`。

### P2（1 周）: 模板治理替换

1. `template-repo` 保留“脚手架模板”。
2. 新增 `template-packs/jeecg/*` 存放迁移后的 Jeecg 模板资源和版本。
3. 引入模板版本字段 `templatePackVersion`，支持灰度。

验收:

1. 同一业务可对比 old/new 模板输出。
2. 模板回滚不影响主流程。

### P3（1~2 周）: 一键部署闭环

1. 新增步骤:
   1. `image_build`
   2. `image_push`
   3. `deploy_target`
   4. `deploy_verify`
   5. `deploy_rollback`
2. 新增部署服务 `DeployOrchestratorService`:
   1. Compose 执行器
   2. K8s 执行器（可后置）
3. 新增发布记录表与发布 API。

验收:

1. 可从任务直接发布到 test 环境。
2. 发布失败可自动回滚。

## 5. 关键风险与规避

1. 依赖冲突风险（Jeecg + 现有 Spring 栈）:
   1. 规避: sidecar 隔离。
2. 模板语义不一致（脚手架模板 vs 代码片段模板）:
   1. 规避: 双层模板架构，不做“一刀切替换”。
3. 部署安全风险（误发布生产）:
   1. 规避: 环境白名单 + 发布审批 + 回滚阈值。

## 6. 推荐决策

1. 决策 A: 采用 `hybrid` 引擎路线（Jeecg 为主，LLM 兜底）。
2. 决策 B: 模板层不直接替换，改为“template-repo + jeecg-template-pack”双轨。
3. 决策 C: 先打通 test 环境一键部署，再扩展到 prod。

## 7. 下一步实施清单（可直接开工）

1. 新增 `CodegenEngine` 接口与 `LlmCodegenEngine` 包装。
2. 新增 `GenerateOptions.codegenEngine` 字段（可选）。
3. 新增 `JeecgCodegenClient`（HTTP 调 sidecar，先打桩）。
4. 在 `TaskService` 增加新步骤预留（image_build/image_push/deploy_target）。
5. 新增 `ops/release` 脚本模板（build/push/deploy/rollback）。
