# P3 Quality Gate v1

## Goal

- Add quality gate checks after `artifact_contract_validate` without changing existing API behavior.
- Block final packaging when quality gate fails.
- Keep all behavior behind configuration switches for safe rollback.

## Trigger And Switches

- Trigger step: `artifact_contract_validate`
- Main switch: `smartark.quality-gate.enabled`
- Auto-fix switch: `smartark.quality-gate.auto-fix-enabled`
- Retry policy: `smartark.quality-gate.max-retries`
- Threshold: `smartark.quality-gate.min-score`

## Gate Dimensions

1. Structure gate:
- `docker-compose.yml` exists
- `scripts/start.sh` exists
- `docs/deploy.md` exists
- path safety check (`..` is forbidden)

2. Semantic gate:
- `scripts/start.sh` contains `docker compose up --build -d` or `docker compose up -d`
- `docs/deploy.md` contains docker compose instructions

3. Build gate:
- each compose service `build.context` points to an existing directory

## Auto-fix And Retry Policy

- On first failure, auto-fix runs once when `auto-fix-enabled=true`.
- Auto-fix scope:
- generate or repair `scripts/start.sh`
- generate or repair `docs/deploy.md`
- generate `docker-compose.yml` when missing/invalid
- repair invalid compose `build.context`
- After auto-fix, quality gate retries in-process up to `max-retries`.
- If still not passed (or score below threshold), task fails with validation error.

## Observability

- `quality_gate event=finish ... attempt=<n>/<max> passed=<bool> score=<double> failedRules=<list> durationMs=<ms>`
- `quality_gate event=autofix ... fixedActions=<list>`
- `quality_gate event=retry ... nextAttempt=<n>`

## Artifact

- report file: `quality_gate_report.json` under task workspace root
