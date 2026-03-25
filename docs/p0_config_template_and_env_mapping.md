# P0 配置模板与环境变量映射

## application.yml 模板片段

```yaml
smartark:
  langchain:
    enabled: ${LANGCHAIN_ENABLED:false}
    sidecar:
      base-url: ${LANGCHAIN_SIDECAR_BASE_URL:http://localhost:18080}
      timeout-ms: ${LANGCHAIN_SIDECAR_TIMEOUT_MS:3000}
      api-version: ${LANGCHAIN_SIDECAR_API_VERSION:v1}
  delivery:
    guard:
      enabled: ${DELIVERY_GUARD_ENABLED:true}
  memory:
    short-term:
      top-k: ${MEMORY_SHORT_TERM_TOP_K:8}
    long-term:
      top-k: ${MEMORY_LONG_TERM_TOP_K:8}
    context:
      max-chars: ${MEMORY_CONTEXT_MAX_CHARS:4000}
  quality-gate:
    enabled: ${QUALITY_GATE_ENABLED:false}
    min-score: ${QUALITY_GATE_MIN_SCORE:0.66}
    auto-fix-enabled: ${QUALITY_GATE_AUTO_FIX_ENABLED:true}
    max-retries: ${QUALITY_GATE_MAX_RETRIES:2}
  preview:
    gateway:
      enabled: ${PREVIEW_GATEWAY_ENABLED:false}
```

## 环境变量映射

| 配置项 | 环境变量 | 默认值 | 说明 |
|---|---|---|---|
| `smartark.langchain.enabled` | `LANGCHAIN_ENABLED` | `false` | LangChain/Sidecar 总开关 |
| `smartark.langchain.sidecar.base-url` | `LANGCHAIN_SIDECAR_BASE_URL` | `http://localhost:18080` | Sidecar 基础地址 |
| `smartark.langchain.sidecar.timeout-ms` | `LANGCHAIN_SIDECAR_TIMEOUT_MS` | `3000` | Sidecar 调用超时（毫秒） |
| `smartark.langchain.sidecar.api-version` | `LANGCHAIN_SIDECAR_API_VERSION` | `v1` | Sidecar 契约版本（通过请求头传递） |
| `smartark.delivery.guard.enabled` | `DELIVERY_GUARD_ENABLED` | `true` | Delivery Guard 开关 |
| `smartark.preview.gateway.enabled` | `PREVIEW_GATEWAY_ENABLED` | `false` | 预览网关开关 |
| `smartark.memory.short-term.top-k` | `MEMORY_SHORT_TERM_TOP_K` | `8` | 每步加载短期记忆条数 |
| `smartark.memory.long-term.top-k` | `MEMORY_LONG_TERM_TOP_K` | `8` | 每步加载长期记忆条数 |
| `smartark.memory.context.max-chars` | `MEMORY_CONTEXT_MAX_CHARS` | `4000` | ContextAssembler 最大拼接长度 |
| `smartark.quality-gate.enabled` | `QUALITY_GATE_ENABLED` | `false` | 质量门控开关（P3） |
| `smartark.quality-gate.min-score` | `QUALITY_GATE_MIN_SCORE` | `0.66` | 质量门最低通过分 |
| `smartark.quality-gate.auto-fix-enabled` | `QUALITY_GATE_AUTO_FIX_ENABLED` | `true` | 质量门失败后是否自动修复一次 |
| `smartark.quality-gate.max-retries` | `QUALITY_GATE_MAX_RETRIES` | `2` | 质量门同步骤最大重试次数 |

## 兼容说明

- 当 `LANGCHAIN_ENABLED=false` 时，主流程不依赖 Sidecar，行为与现网一致。
- 当 `DELIVERY_GUARD_ENABLED=false` 时，`PackageStep` 跳过报告/清单生成，仅执行打包保存产物。
- 当 `PREVIEW_GATEWAY_ENABLED=false` 时，当前版本仅记录开关状态日志，不改变既有预览发布路径。
