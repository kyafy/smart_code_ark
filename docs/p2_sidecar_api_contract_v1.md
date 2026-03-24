# Sidecar API 契约 v1

## 版本化约定

- Header：`X-SmartArk-Sidecar-Api-Version: v1`
- 当前 Gateway 默认值：`v1`
- 向后兼容策略：新增字段仅追加，不删除 v1 既有字段。

## 1) Health

- `GET /health`
- Response:

```json
{
  "status": "ok",
  "detail": "ready"
}
```

## 2) Context Build

- `POST /context/build`
- Request:

```json
{
  "taskId": "t1",
  "stepCode": "codegen_backend",
  "projectId": "p1",
  "userId": 1,
  "instructions": "xxx",
  "maxItems": 8
}
```

- Response:

```json
{
  "contextPack": "assembled context text",
  "sources": ["short_term_memory", "long_term_memory"],
  "totalItems": 6
}
```

## 3) Quality Evaluate

- `POST /quality/evaluate`
- Request:

```json
{
  "taskId": "t1",
  "stepCode": "package",
  "content": "artifact summary",
  "rules": ["missing_required_file", "invalid_compose_context"]
}
```

- Response:

```json
{
  "passed": true,
  "failedRules": [],
  "suggestions": [],
  "score": 0.98
}
```

## 4) Memory Read

- `POST /memory/read`
- Request:

```json
{
  "scopeType": "project_user_stack",
  "scopeId": "projectA:1001:springboot+vue3+mysql",
  "query": "codegen_backend",
  "topK": 8
}
```

- Response:

```json
{
  "items": [
    {
      "id": "m-1",
      "scopeType": "project_user_stack",
      "scopeId": "projectA:1001:springboot+vue3+mysql",
      "memoryType": "success_pattern",
      "content": "success pattern summary",
      "score": 0.87
    }
  ]
}
```

## 5) Memory Write

- `POST /memory/write`
- Request:

```json
{
  "scopeType": "project_user_stack",
  "scopeId": "projectA:1001:springboot+vue3+mysql",
  "memoryType": "failure_pattern",
  "content": "failure summary",
  "metadata": {
    "taskId": "t1",
    "stepCode": "codegen_backend"
  }
}
```

- Response:

```json
{
  "written": true,
  "recordId": "m-2"
}
```
