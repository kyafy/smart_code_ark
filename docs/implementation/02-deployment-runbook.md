# 基于 JeecgBoot 的一键部署实施手册

版本: V1.0  
日期: 2026-04-01

## 1. 手册目标

本手册用于指导团队按固定步骤落地“项目生成 + 一键部署”。

执行结果:

1. 能发起一个生成任务。
2. 自动完成构建和部署。
3. 失败时可自动或手动回滚。

## 2. 目录约定

在仓库根目录下新增:

```text
ops/
  ├─ scripts/
  │   ├─ gen-project.sh
  │   ├─ build-artifacts.sh
  │   ├─ build-image.sh
  │   ├─ deploy-compose.sh
  │   ├─ deploy-k8s.sh
  │   ├─ health-check.sh
  │   └─ rollback.sh
  ├─ compose/
  │   ├─ docker-compose.tpl.yml
  │   └─ .env.tpl
  └─ k8s/
      ├─ deployment.tpl.yaml
      ├─ service.tpl.yaml
      └─ ingress.tpl.yaml
```

## 3. 前置条件

### 3.1 基础环境

1. JDK 17+
2. Maven 3.9+
3. Node.js 20+
4. pnpm 9+
5. Docker 24+
6. Docker Compose v2

### 3.2 服务依赖

1. MySQL 8
2. Redis 7
3. 镜像仓库（Harbor 或 Docker Registry）

### 3.3 权限要求

1. Git 仓库读写权限
2. 目标服务器部署权限
3. 镜像仓库推送权限

## 4. 环境变量

创建 `ops/.env`:

```env
PROJECT_CODE=demo-order
DEPLOY_ENV=test
GEN_OUTPUT_ROOT=/data/gen-output
REGISTRY_HOST=registry.example.com
REGISTRY_NAMESPACE=jeecg
IMAGE_TAG=latest
BACKEND_PORT=8080
FRONTEND_PORT=80
DB_URL=jdbc:mysql://mysql:3306/jeecg?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai
DB_USERNAME=jeecg
DB_PASSWORD=change_me
REDIS_HOST=redis
REDIS_PORT=6379
```

## 5. 一键执行主流程

### 5.1 Linux/Mac

```bash
bash ops/scripts/gen-project.sh
bash ops/scripts/build-artifacts.sh
bash ops/scripts/build-image.sh
bash ops/scripts/deploy-compose.sh
bash ops/scripts/health-check.sh
```

### 5.2 Windows PowerShell

```powershell
bash ops/scripts/gen-project.sh
bash ops/scripts/build-artifacts.sh
bash ops/scripts/build-image.sh
bash ops/scripts/deploy-compose.sh
bash ops/scripts/health-check.sh
```

## 6. 脚本模板

### 6.1 gen-project.sh

```bash
#!/usr/bin/env bash
set -euo pipefail

source ops/.env

echo "[1/5] start generate: ${PROJECT_CODE}"
# 这里改成你最终落地的 API 地址
curl -sS -X POST "http://localhost:8080/api/gen/projects/${PROJECT_CODE}/tasks/generate" \
  -H "Content-Type: application/json" \
  -d @ops/payload/generate.json

echo "generate task submitted"
```

### 6.2 build-artifacts.sh

```bash
#!/usr/bin/env bash
set -euo pipefail

echo "[2/5] build backend"
cd jeecg-boot
mvn -T 1C -DskipTests clean package

cd ../jeecgboot-vue3
echo "[3/5] build frontend"
pnpm install --frozen-lockfile
pnpm run build
```

### 6.3 build-image.sh

```bash
#!/usr/bin/env bash
set -euo pipefail

source ops/.env

echo "[4/5] build and push image"
docker build -t ${REGISTRY_HOST}/${REGISTRY_NAMESPACE}/${PROJECT_CODE}-backend:${IMAGE_TAG} -f jeecg-boot/Dockerfile jeecg-boot
docker push ${REGISTRY_HOST}/${REGISTRY_NAMESPACE}/${PROJECT_CODE}-backend:${IMAGE_TAG}

docker build -t ${REGISTRY_HOST}/${REGISTRY_NAMESPACE}/${PROJECT_CODE}-frontend:${IMAGE_TAG} -f jeecgboot-vue3/Dockerfile jeecgboot-vue3
docker push ${REGISTRY_HOST}/${REGISTRY_NAMESPACE}/${PROJECT_CODE}-frontend:${IMAGE_TAG}
```

### 6.4 deploy-compose.sh

```bash
#!/usr/bin/env bash
set -euo pipefail

source ops/.env

echo "[5/5] deploy compose"
export REGISTRY_HOST REGISTRY_NAMESPACE PROJECT_CODE IMAGE_TAG BACKEND_PORT FRONTEND_PORT

envsubst < ops/compose/docker-compose.tpl.yml > ops/compose/docker-compose.yml

docker compose -f ops/compose/docker-compose.yml --env-file ops/.env up -d
```

### 6.5 health-check.sh

```bash
#!/usr/bin/env bash
set -euo pipefail

source ops/.env

echo "health checking..."
for i in {1..30}; do
  if curl -fsS "http://localhost:${BACKEND_PORT}/actuator/health" | grep -q '"UP"'; then
    echo "backend health ok"
    exit 0
  fi
  sleep 5
done

echo "health check failed"
exit 1
```

### 6.6 rollback.sh

```bash
#!/usr/bin/env bash
set -euo pipefail

source ops/.env

if [[ -z "${ROLLBACK_TAG:-}" ]]; then
  echo "ROLLBACK_TAG is required"
  exit 1
fi

export IMAGE_TAG=${ROLLBACK_TAG}
envsubst < ops/compose/docker-compose.tpl.yml > ops/compose/docker-compose.yml
docker compose -f ops/compose/docker-compose.yml --env-file ops/.env up -d

echo "rollback done: ${ROLLBACK_TAG}"
```

## 7. Compose 模板

`ops/compose/docker-compose.tpl.yml` 示例:

```yaml
services:
  backend:
    image: ${REGISTRY_HOST}/${REGISTRY_NAMESPACE}/${PROJECT_CODE}-backend:${IMAGE_TAG}
    container_name: ${PROJECT_CODE}-backend
    restart: always
    environment:
      - SPRING_PROFILES_ACTIVE=${DEPLOY_ENV}
      - DB_URL=${DB_URL}
      - DB_USERNAME=${DB_USERNAME}
      - DB_PASSWORD=${DB_PASSWORD}
      - REDIS_HOST=${REDIS_HOST}
      - REDIS_PORT=${REDIS_PORT}
    ports:
      - "${BACKEND_PORT}:8080"

  frontend:
    image: ${REGISTRY_HOST}/${REGISTRY_NAMESPACE}/${PROJECT_CODE}-frontend:${IMAGE_TAG}
    container_name: ${PROJECT_CODE}-frontend
    restart: always
    ports:
      - "${FRONTEND_PORT}:80"
    depends_on:
      - backend
```

## 8. CI/CD 流程样例（GitHub Actions）

`.github/workflows/gen-deploy.yml`:

```yaml
name: gen-deploy

on:
  workflow_dispatch:
    inputs:
      projectCode:
        description: project code
        required: true
      env:
        description: deploy env
        required: true
        default: test

jobs:
  build-and-deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - uses: pnpm/action-setup@v4
        with:
          version: 9

      - name: Build Backend
        run: |
          cd jeecg-boot
          mvn -DskipTests clean package

      - name: Build Frontend
        run: |
          cd jeecgboot-vue3
          pnpm install --frozen-lockfile
          pnpm run build

      - name: Docker Login
        run: echo "${{ secrets.REGISTRY_PASSWORD }}" | docker login ${{ secrets.REGISTRY_HOST }} -u ${{ secrets.REGISTRY_USER }} --password-stdin

      - name: Build & Push
        run: bash ops/scripts/build-image.sh

      - name: Deploy
        run: bash ops/scripts/deploy-compose.sh

      - name: Health Check
        run: bash ops/scripts/health-check.sh
```

## 9. 运维操作手册

### 9.1 查看发布状态

```bash
docker compose -f ops/compose/docker-compose.yml ps
docker logs ${PROJECT_CODE}-backend --tail 200
```

### 9.2 执行回滚

```bash
export ROLLBACK_TAG=v20260401_1200
bash ops/scripts/rollback.sh
```

### 9.3 清理旧镜像

```bash
docker image prune -f
```

## 10. 故障处理

1. 生成失败: 检查模板路径、数据库连接、表结构权限。
2. 构建失败: 检查 Maven 私服、pnpm lock、JDK 版本。
3. 镜像推送失败: 检查 registry 证书、网络、登录态。
4. 部署成功但健康失败: 检查 `SPRING_PROFILES_ACTIVE` 和数据库配置。

## 11. 发布门禁

上线前必须满足:

1. 生成任务成功且日志完整。
2. 构建产物可复现。
3. 健康检查连续 3 次通过。
4. 回滚脚本演练通过。

## 12. 验收记录模板

```text
项目编码:
发布版本:
部署环境:
执行人:
开始时间:
结束时间:
结果: SUCCESS/FAILED/ROLLED_BACK
问题记录:
结论:
```
