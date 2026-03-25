# 智码方舟 Prompt 规范 v1（MVP）

## 1. 目标
- 将中文需求稳定转换为结构化产物
- 控制输出格式，降低自由生成导致的不稳定
- 支持“增量修改”而非每次全量重建

## 2. 总体原则
1. 先澄清后生成：需求不完整时必须追问
2. 结构化优先：输出必须符合JSON Schema
3. 小步可回溯：每阶段产物可版本化
4. 安全约束：拒绝违规内容和危险执行指令

## 3. Prompt 分阶段
### P1 需求澄清 Prompt
- 输入：用户原始需求 + 历史会话
- 输出：澄清问题列表（最多5条，按优先级）
- 约束：问题具体、可回答、避免开放泛问

### P2 需求结构化 Prompt
- 输出 Schema（RequirementSpec）：
```json
{
  "projectName": "",
  "roles": [],
  "modules": [{"name":"","features":[{"name":"","acceptanceCriteria":[]}]}],
  "nonFunctional": []
}
```

### P3 数据库设计 Prompt
- 输入：RequirementSpec
- 输出 Schema（DomainModel）：
```json
{
  "entities":[{"name":"","fields":[{"name":"","type":"","nullable":false}],"relations":[]}]
}
```

### P4 代码生成计划 Prompt
- 输入：RequirementSpec + DomainModel + StackConfig
- 输出：文件清单（GenerationPlan）+ 每文件生成策略（template/llm）

### P5 增量修改 Prompt
- 输入：当前项目快照 + 修改指令
- 输出：PatchPlan（新增/修改/删除文件清单）+ 影响说明

## 4. 模型路由策略（MVP）
- 轻量模型：澄清问句、格式修正、摘要
- 强模型：需求结构化、复杂业务逻辑、补丁生成
- 失败回退：主模型失败时切备用模型并记录日志

## 5. 输出质量门禁
- JSON 必须可解析
- 字段完整性校验（必填项）
- 命名规范校验（驼峰/下划线按语言）
- 若不通过，返回“可修复错误”并自动重试1次

## 6. Prompt 版本与缓存复用规范
- Prompt 资产分为三层：`prompt_templates`（模板定义）→ `prompt_versions`（版本快照）→ `prompt_history`（执行历史）
- 运行时必须显式指定 `template_key + version_no`，禁止使用“最新版本”隐式解析
- 执行前先查缓存：命中 `prompt_cache` 则直接复用结果，未命中再调用模型
- 缓存键建议：`hash(template_key + version_no + model + temperature + input_digest)`
- 缓存策略：默认 TTL 24h，支持按模板单独配置；高风险模板（支付/权限）默认不缓存
- 回滚策略：版本发布后若质量下降，按 `template_key` 快速回滚到上一个稳定版本

## 7. 安全与合规约束
- 不生成恶意代码、漏洞利用代码
- 不输出密钥/凭据示例真实值
- 对“论文代写”请求给出合规提醒，输出仅限技术辅助