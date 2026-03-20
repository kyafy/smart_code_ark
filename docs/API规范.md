# Smart Code Ark API 规范

更新时间：2026-03-20

说明：本文档基于当前 Controller、Service、DTO 与前端接口调用方式整理。

## 1. 统一约定

### 1.1 通用响应结构

除文件下载与 SSE 外，接口统一返回：

```json
{
  "code": 0,
  "message": "ok",
  "data": {}
}
```

### 1.2 认证方式

```http
Authorization: Bearer <token>
```

### 1.3 错误码

1. `0` 成功
2. `1001` 参数校验失败
3. `1002` 未登录/鉴权失败
4. `1003` 无权限
5. `1004` 资源不存在
6. `1005` 状态冲突
7. `1006` 频率限制
8. `2001` 额度不足
9. `3001` 模型服务异常
10. `3002` 任务失败
11. `3003` 任务超时
12. `3004` 任务模型错误
13. `3005` 任务 IO 错误
14. `3006` 任务参数错误
15. `3007` 任务取消
16. `9000` 内部错误

### 1.4 聊天删除相关错误映射（前后端约定）

1. `403 / code=1003`：无权限访问该会话
2. `404 / code=1004`：会话不存在（包含会话已被软删除）
3. `500 / code=9000`：系统内部错误

说明：

1. 前端统一使用后端 `message` 展示提示文案。
2. 聊天会话删除后，对列表查询不可见；对详情/消息查询按 `404` 处理。

## 2. 路由版本说明

1. 认证接口支持 `/api/auth` 与 `/api/v1/auth`
2. 聊天接口支持 `/api/chat` 与 `/api/v1/chat`
3. 项目接口支持 `/api/projects` 与 `/api/v1/projects`
4. 任务接口当前挂载在 `/api`
5. 计费接口当前挂载在 `/api/billing`
6. 论文接口当前挂载在 `/api/paper`

## 3. 认证接口

### `POST /api/auth/register`

请求体：

```json
{
  "username": "string",
  "password": "string",
  "phone": "string, optional"
}
```

响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "userId": 1
  }
}
```

### `POST /api/auth/login`

请求体：

```json
{
  "username": "string",
  "password": "string"
}
```

响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "token": "jwt-token",
    "userId": 1
  }
}
```

### `POST /api/auth/sms/send`

请求体：

```json
{
  "phone": "13800000000",
  "scene": "login"
}
```

响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "requestId": "sms_req_1742450000000",
    "expireIn": 300
  }
}
```

规则：

1. 验证码有效期 5 分钟
2. 同手机号同场景冷却 60 秒
3. 单手机号每日最多 10 次
4. 单 IP 每小时最多 100 次
5. 单验证码最多失败 5 次

### `POST /api/auth/login/sms`

请求体：

```json
{
  "phone": "13800000000",
  "captcha": "123456"
}
```

响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "token": "jwt-token",
    "userId": 1
  }
}
```

说明：手机号未注册时会自动创建账户。

## 4. 需求会话接口

### `POST /api/chat/start`

请求体：

```json
{
  "title": "项目标题",
  "projectType": "web",
  "description": "可选描述"
}
```

响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "sessionId": "string",
    "stage": "active"
  }
}
```

### `POST /api/chat`

SSE 请求体：

```json
{
  "sessionId": "string",
  "message": "用户消息"
}
```

事件：

1. `delta`，增量文本
2. `result`，最终结构化结果
3. `error`，错误事件，返回可识别错误信息

`result` 数据：

```json
{
  "sessionId": "string",
  "messages": [
    { "role": "user", "content": "..." },
    { "role": "assistant", "content": "..." }
  ],
  "extractedRequirements": {},
  "createdAt": "2026-03-20T10:00:00",
  "updatedAt": "2026-03-20T10:01:00"
}
```

`error` 数据：

```json
{
  "code": "504",
  "message": "对话请求超时"
}
```

### `GET /api/chat/sessions`

响应 `data`：

```json
[
  {
    "sessionId": "string",
    "title": "string",
    "projectType": "web",
    "status": "active",
    "updatedAt": "2026-03-20T10:01:00"
  }
]
```

### `GET /api/chat/sessions/{sessionId}/messages`

响应 `data`：

```json
{
  "sessionId": "string",
  "messages": [
    { "role": "user", "content": "..." },
    { "role": "assistant", "content": "..." }
  ],
  "extractedRequirements": {},
  "createdAt": "2026-03-20T10:00:00",
  "updatedAt": "2026-03-20T10:01:00"
}
```

说明：

1. 当会话已删除或不存在时，返回 `404 / code=1004`。

### `DELETE /api/chat/sessions/{sessionId}`

响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": true
}
```

说明：

1. 采用软删除：`status=deleted` 且写入 `deleted_at`。
2. 重复删除幂等返回成功。
3. 越权删除返回 `403 / code=1003`。

## 5. 项目接口

### `POST /api/projects/confirm`

请求体：

```json
{
  "sessionId": "string",
  "stack": {
    "backend": "springboot",
    "frontend": "vue3",
    "db": "mysql"
  },
  "description": "可选",
  "prd": "可选"
}
```

响应 `data`：

```json
{
  "projectId": "string",
  "status": "confirmed"
}
```

### `GET /api/projects`

响应 `data`：

```json
[
  {
    "id": "string",
    "title": "string",
    "description": "string",
    "status": "confirmed",
    "updatedAt": "2026-03-20T10:01:00"
  }
]
```

### `GET /api/projects/{id}`

响应 `data`：

```json
{
  "id": "string",
  "title": "string",
  "description": "string",
  "projectType": "web",
  "status": "confirmed",
  "stack": {
    "backend": "springboot",
    "frontend": "vue3",
    "db": "mysql"
  },
  "requirementSpec": "{\"title\":\"...\"}",
  "createdAt": "2026-03-20T10:00:00",
  "updatedAt": "2026-03-20T10:01:00",
  "tasks": [],
  "messages": []
}
```

## 6. 任务接口

### `POST /api/generate`

请求体：

```json
{
  "projectId": "string",
  "instructions": "可选补充指令"
}
```

响应 `data`：

```json
{
  "taskId": "string",
  "status": "queued"
}
```

说明：创建任务时会扣减 10 quota。

### `GET /api/task/{taskId}/status`

响应 `data`：

```json
{
  "status": "queued",
  "progress": 0,
  "step": "requirement_analyze",
  "current_step": "requirement_analyze",
  "projectId": "string",
  "errorCode": null,
  "errorMessage": null,
  "startedAt": "2026-03-20T10:00:00",
  "finishedAt": null
}
```

### `GET /api/task/{taskId}/preview`

响应 `data`：

```json
{
  "previewUrl": "http://localhost:5173/preview/{taskId}"
}
```

### `POST /api/task/{taskId}/modify`

请求体：

```json
{
  "changeInstructions": "修改说明"
}
```

响应 `data`：

```json
{
  "taskId": "string",
  "status": "queued"
}
```

### `POST /api/task/{taskId}/cancel`

响应 `data`：

```json
{
  "taskId": "string",
  "status": "cancelled"
}
```

### `POST /api/task/{taskId}/retry/{stepCode}`

响应 `data`：

```json
{
  "taskId": "string",
  "status": "running"
}
```

### `GET /api/task/{taskId}/download`

响应：`application/zip`

### `GET /api/task/{taskId}/logs`

响应 `data`：

```json
[
  {
    "id": 1,
    "level": "info",
    "content": "Task started",
    "ts": 1742450000000
  }
]
```

## 7. 论文接口

### `POST /api/paper/outline`

请求体：

```json
{
  "topic": "选题",
  "discipline": "学科",
  "degreeLevel": "本科/硕士/博士",
  "methodPreference": "可选"
}
```

响应 `data`：

```json
{
  "taskId": "string",
  "status": "queued"
}
```

### `GET /api/paper/outline/{taskId}`

响应 `data`：

```json
{
  "taskId": "string",
  "citationStyle": "GB/T 7714",
  "topic": "原始主题",
  "topicRefined": "澄清后的主题",
  "researchQuestions": [],
  "chapters": [],
  "qualityReport": {},
  "references": []
}
```

## 8. 计费接口

### `GET /api/billing/balance`

响应 `data`：

```json
{
  "balance": 0,
  "quota": 100
}
```

### `GET /api/billing/records`

响应 `data`：

```json
[
  {
    "id": 1,
    "projectId": "string",
    "taskId": "string",
    "changeAmount": -10,
    "currency": "QUOTA",
    "reason": "generate",
    "balanceAfter": 90,
    "createdAt": "2026-03-20T10:00:00"
  }
]
```

### `POST /api/billing/recharge/orders`

请求：

```json
{
  "amount": 9.9,
  "quota": 100,
  "payChannel": "mock"
}
```

响应 `data`：

```json
{
  "orderId": "string",
  "status": "pending",
  "amount": 9.9,
  "quota": 100,
  "payChannel": "mock",
  "paymentNo": null,
  "paidAt": null,
  "createdAt": "2026-03-20T10:00:00",
  "updatedAt": "2026-03-20T10:00:00"
}
```

### `GET /api/billing/recharge/orders/{orderId}`

说明：

1. 仅允许查询当前登录用户自己的订单，越权返回 `403`

### `POST /api/billing/recharge/callback`

请求：

```json
{
  "orderId": "string",
  "paymentNo": "pay_202603200001",
  "signature": "orderId|paymentNo|smartark-recharge-secret",
  "paidAmount": 9.9,
  "payChannel": "mock"
}
```

说明：

1. 回调接口默认跳过用户鉴权，依赖验签控制安全。
2. MVP 验签开关：`smartark.billing.recharge.callback-mock-sign-enabled`（默认 `true`）。
3. 验签失败返回 `403`，不入账。
4. 同一 `orderId` 或已处理的 `paymentNo` 重复回调按幂等处理，不重复加积分。

## 9. 当前口径差异提示

1. 前端任务接口现已统一为 `/api/task/...`
2. 前端 `ChatStartResult.stage` 类型仍然写成 `requirement | string`，后端实际返回 `active`
3. 认证/聊天/项目支持 `/api/v1`，任务/论文/计费当前不支持 `/api/v1`
