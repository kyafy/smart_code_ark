# Jeecg Placeholder Mapping v1

## Scope

This document defines the first-pass mapping from Jeecg placeholder variables to Smart Code Ark internal render variables.

- Provider: `local_template` (`LocalTemplateRuleProvider`)
- Trigger engine: `jeecg_rule | internal_service | hybrid` (when provider order includes `local_template`)
- Purpose: make imported Jeecg templates runnable in the current internal codegen chain without introducing full Freemarker execution.

## Rendering Model

`local_template` performs three rewrite stages on files materialized into task workspace:

1. Path placeholder rewrite: `${...}` in relative file paths.
2. Extension rewrite:
   - `.javai -> .java`
   - `.vuei -> .vue`
   - `.tsi -> .ts`
   - `.jsi -> .js`
   - `.pyi -> .py`
3. Content placeholder rewrite:
   - Replace known variables.
   - Keep unknown placeholders unchanged (for example `${po.fieldName}` remains as-is).
   - Replace `__PROJECT_NAME__` and `__DISPLAY_NAME__`.

## Variable Sources

Primary input comes from task `requirementJson`:

- root fields: `title`, `projectType`
- Jeecg hint node: `jeecg.*` (for example `moduleName`, `entityName`, `tableName`, `bussiPackage`, `entityPackage`)

Fallback source:

- `task.projectId`

## v1 Mapping Table

| Internal variable | Source priority | Default |
| --- | --- | --- |
| `projectName` | `title` -> `task.projectId` | `smartark-project` |
| `displayName` | `title` -> `projectName` | `SmartArk Project` |
| `moduleName` | `jeecg.moduleName` -> `projectType` | `demo` |
| `entityName` | `jeecg.entityName` -> `PascalCase(moduleName) + Entity` | `DemoEntity` |
| `tableName` | `jeecg.tableName` -> `snake_case(entityName)` | `smartark_demo` |
| `bussiPackage` | `jeecg.bussiPackage` -> `jeecg.packageName` | `com.smartark.generated` |
| `packageName` | same as `bussiPackage` | `com.smartark.generated` |
| `entityPackage` | `jeecg.entityPackage` | `demo` |
| `entityPackagePath` | `entityPackage` with `.` -> `/` | `demo` |

Normalization rules:

- `projectName`: slug style (`[a-z0-9-]`)
- `moduleName`: slug style + `-` replaced by `_`
- path vars for package-like fields use `/` separators.

## Request Parameters Role in This Chain

These fields are still passed through for upstream Jeecg invocation (`jeecg` provider), and also provide context for local mapping:

- `jeecg.code` or `jeecg.formId` (recommended to provide at least one)
- optional: `jeecg.tableName`, `jeecg.moduleName`, `jeecg.bussiPackage`, `jeecg.entityPackage`, `jeecg.entityName`

Roles:

- Local render (`local_template`): supplies naming/package variables for path/content rewrite.
- Upstream render (`jeecg` sidecar): used as form/code identity and generate parameters.

## Recommended Provider Order

For merged local + Jeecg template library:

- `CODEGEN_INTERNAL_PROVIDER_ORDER=local_template,jeecg`
- `CODEGEN_INTERNAL_HYBRID_PROVIDER_ORDER=local_template,jeecg`
- `CODEGEN_INTERNAL_STRICT_PROVIDER_ORDER=local_template,jeecg`

This yields:

1. Prefer local deterministic rewrite.
2. Fallback to Jeecg sidecar/upstream when local rewrite cannot complete.
