# Jeecg Template Importer (Skeleton)

## Purpose

Use this importer to consolidate Jeecg online templates into Smart Code Ark `template-repo` without manually copying directories.

- Source: `JeecgBoot/.../jeecg/code-template-online`
- Target: `template-repo/templates/jeecg-*`
- Metadata target: `template-repo/catalog.json` + per-template `template.json`

## Files

- Script: `template-repo/scripts/import-jeecg-templates.mjs`
- Profile map: `template-repo/scripts/jeecg-import-map.json`
- Report: `template-repo/scripts/jeecg-import-report.json`

## Quick Start

Dry-run (no file changes):

```bash
node template-repo/scripts/import-jeecg-templates.mjs
```

Apply import:

```bash
node template-repo/scripts/import-jeecg-templates.mjs --apply
```

Apply selected profiles only:

```bash
node template-repo/scripts/import-jeecg-templates.mjs --apply --include=default-one,jvxe-onetomany
```

Overwrite existing imported keys:

```bash
node template-repo/scripts/import-jeecg-templates.mjs --apply --overwrite=true
```

## Key Behaviors

1. Profiles are controlled by `jeecg-import-map.json`.
2. Imported template key format defaults to `jeecg-<profile-id>`.
3. Each imported template stores Jeecg files under `upstream/`.
4. `common/` templates are copied into each imported template under `upstream/common`.
5. Existing `catalog.json` key collisions are reported as conflicts unless `--overwrite=true`.

## Mapping Strategy

The importer does not rewrite Jeecg placeholders yet. It provides a safe consolidation skeleton:

1. Normalize template metadata into Smart Code Ark schema.
2. Keep upstream artifacts unchanged in `upstream/`.
3. Enable next-step custom rendering adapters in `internal_service` providers.

## Next Step Recommendations

1. Expand placeholder mapping coverage by template family.
2. Add CI validation for imported template keys and metadata completeness.
3. Add regression sample cases per Jeecg profile.

## Current Mapping Baseline

- `local_template` provider is available in api-gateway internal codegen service.
- Placeholder mapping baseline is documented in:
  - `template-repo/docs/jeecg-placeholder-mapping-v1.md`
