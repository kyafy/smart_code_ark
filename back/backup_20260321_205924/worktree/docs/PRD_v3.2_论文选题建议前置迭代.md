# Smart Code Ark v3.2 论文选题建议前置迭代需求文档

- 文档版本：v3.2
- 文档日期：2026-03-21
- 适用系统：`frontend-web`、`services/api-gateway-java`
- 关联现状：后端已有 `POST /api/paper/outline` 与论文大纲生成链路，前端暂无独立论文生成页面

## 1. 背景与问题

当前论文生成链路要求用户直接输入明确选题后触发大纲任务。  
问题在于：大量用户只有方向想法，没有可执行题目，导致进入门槛高、转化低。

## 2. 目标

在论文生成前增加“选题建议”步骤，形成闭环：
1. 用户输入想法（方向/兴趣/约束）
2. AI 输出多个可执行选题建议
3. 用户选择并微调题目
4. 进入现有 `paper_outline` 任务链路

## 3. 范围定义

## 3.1 In Scope

1. 新增论文生成页面（含选题建议步骤）
2. 新增选题建议接口（建议 + 再生成 + 采纳）
3. 与现有 `POST /api/paper/outline` 无缝衔接
4. 记录建议历史，支持回看

## 3.2 Out of Scope

1. 自动文献检索后再建议题目（本期不做）
2. 选题建议多轮长对话 Agent 化
3. 复杂导师评价系统

## 4. 用户场景

1. 用户没有具体选题，只知道“研究方向 + 学科 + 学位层级”
2. 用户已有粗题目，希望 AI 细化与提升可行性
3. 用户在候选题中比较后采纳其一并进入大纲生成

## 5. 产品流程

1. 进入论文页：显示两种入口
2. 入口A：我已有题目（直接走 outline）
3. 入口B：我需要 AI 帮我选题（走建议流程）
4. 建议流程输入：研究兴趣、学科、学位层级、方法偏好、应用场景、限制条件
5. 系统返回 3-5 条建议，每条含结构化说明
6. 用户操作：采纳 / 微调再生成 / 全量重生成
7. 采纳后将选题填回“论文生成表单”，用户确认触发 outline

## 6. 页面状态机（前端）

状态：
1. `idle`（初始）
2. `suggesting`（生成建议中）
3. `suggested`（建议可选择）
4. `refining`（微调再生成中）
5. `accepted`（已采纳待提交）
6. `submitting_outline`（触发大纲任务中）
7. `done`（进入任务进度页）
8. `error`（异常）

状态流转：
1. `idle -> suggesting -> suggested`
2. `suggested -> refining -> suggested`
3. `suggested -> accepted -> submitting_outline -> done`
4. 任意执行态失败 -> `error`（支持重试回到前一态）

## 7. 接口契约（新增/复用）

## 7.1 新增：生成选题建议

- `POST /api/paper/topic/suggest`

请求体：
```json
{
  "idea": "我想做高校学生学习行为分析",
  "discipline": "教育学",
  "degreeLevel": "硕士",
  "methodPreference": "实证研究",
  "constraints": "数据易获取，周期3个月",
  "count": 5
}
```

响应体：
```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "suggestionId": "ts_xxx",
    "items": [
      {
        "title": "基于学习平台日志的本科生自主学习行为影响因素研究",
        "topicRefined": "......",
        "researchQuestions": ["RQ1...", "RQ2..."],
        "innovationPoints": ["..."],
        "feasibility": "high",
        "expectedDataSources": ["校内平台日志", "问卷"],
        "riskNotes": ["样本偏差风险"]
      }
    ]
  }
}
```

## 7.2 新增：微调再建议

- `POST /api/paper/topic/suggest/refine`

请求体：
```json
{
  "suggestionId": "ts_xxx",
  "selectedTitle": "基于学习平台日志的本科生自主学习行为影响因素研究",
  "refineInstructions": "更偏向职业院校场景，减少问卷依赖"
}
```

响应：同 `suggest`，返回新一轮候选项。

## 7.3 新增：采纳建议（可选接口）

- `POST /api/paper/topic/suggest/accept`

说明：若希望服务端保存“采纳记录”，则增加该接口；  
若不强制持久化，可由前端直接把采纳结果填入 outline 请求体。

## 7.4 复用：触发论文大纲

- 继续使用 `POST /api/paper/outline`
- 采纳后填入：
1. `topic`：采纳题目
2. `discipline`、`degreeLevel`、`methodPreference`：沿用用户输入

## 8. 数据模型建议（新增）

新增表：`paper_topic_suggestions`
1. `id` BIGINT PK
2. `suggestion_id` VARCHAR(64) UNIQUE
3. `user_id` BIGINT
4. `idea` TEXT
5. `discipline` VARCHAR(64)
6. `degree_level` VARCHAR(32)
7. `method_preference` VARCHAR(64)
8. `constraints_text` VARCHAR(512)
9. `items_json` JSON
10. `status` VARCHAR(32)  // generated / accepted / discarded
11. `created_at` DATETIME
12. `updated_at` DATETIME

可选新增表：`paper_topic_suggestion_rounds`（多轮微调历史）

## 9. 质量与体验要求

1. 建议生成时长：P50 <= 8s，P95 <= 15s
2. 建议至少 3 条、最多 5 条
3. 每条建议必须包含：题目、研究问题、可行性说明
4. 异常可恢复：支持“一键重试”
5. 空输入校验：`idea` 必填，长度限制 10-500 字

## 10. 埋点与指标

事件埋点：
1. `paper_topic_suggest_requested`
2. `paper_topic_suggest_succeeded`
3. `paper_topic_refine_requested`
4. `paper_topic_accepted`
5. `paper_outline_started_from_suggestion`

指标：
1. 建议触发率（论文页访问后触发建议的比例）
2. 采纳率（建议后采纳比例）
3. 建议到大纲转化率
4. 失败率与重试率

## 11. 迭代计划（可执行 Phase）

## 11.1 Phase 1（MVP，1 周）

1. 新增论文页 UI 与“AI选题建议”入口
2. 新增 `POST /api/paper/topic/suggest`
3. 支持建议展示、采纳并触发 `/api/paper/outline`
4. 基础埋点与日志

验收：
1. 用户可从“无题目”走通到任务启动
2. 接口稳定返回结构化候选题

## 11.2 Phase 2（增强，1 周）

1. 新增“微调再建议”能力
2. 建议历史持久化
3. 增加失败重试与更清晰错误态

验收：
1. 支持至少 2 轮微调建议
2. 历史建议可回看

## 11.3 Phase 3（优化，持续）

1. 引入质量评分（创新性/可行性/工作量）
2. 提供“导师风格”建议模板（学术/应用/工程）
3. A/B 测试建议模板，优化采纳率

## 12. 风险与应对

1. 风险：建议过泛不落地
2. 应对：强制输出研究问题与数据来源，约束提示词
3. 风险：生成时延偏高
4. 应对：异步加载 + 骨架 + 重试
5. 风险：用户选择困难
6. 应对：默认按“可行性优先”排序 + 推荐标签

## 13. 验收标准（DoD）

1. 无具体题目的用户可完成“建议 -> 采纳 -> 生成大纲”闭环
2. 前后端接口与状态机一致，异常分支可恢复
3. 建议链路埋点完整，可统计转化
4. 不影响现有“已有题目直接生成”链路

