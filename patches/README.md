# jeecg Code 一键接入 Patch 包

## 目录结构

```
patches/
├── 01-jeecg-boot-starter-codegen.patch   # Starter 侧：新增 codegen 客户端模块
├── 02-jeecgboot-standalone.patch         # JeecgBoot 侧：独立运行模式改造
├── apply.sh                              # 一键应用脚本
└── README.md
```

---

## Patch 说明

### 01 — jeecg-boot-starter-codegen

针对仓库 **jeecg-boot-starter**（`springboot3` 分支）的改动：

| 变更 | 说明 |
|------|------|
| `pom.xml` 修改 | 在 `<modules>` 中注册 `jeecg-boot-starter-codegen` |
| `jeecg-boot-starter-codegen/pom.xml` 新增 | 模块 POM，依赖 `spring-boot-starter-web` + `jeecg-boot-common` |
| `CodegenClientProperties.java` 新增 | `@ConfigurationProperties(prefix="jeecg.codegen.client")` 配置类 |
| `JeecgCodegenClient.java` 新增 | HTTP 客户端，调用 `/internal/codegen/render`，支持 HMAC-SHA256 签名 |
| `CodegenClientAutoConfiguration.java` 新增 | Spring Boot 自动装配，`enabled=true` 时生效 |
| `AutoConfiguration.imports` 新增 | Spring Boot 3 SPI 注册文件 |

### 02 — JeecgBoot standalone

针对仓库 **JeecgBoot**（`main` 分支）的改动：

| 文件 | 变更说明 |
|------|---------|
| `jeecg-boot/pom.xml` | 移除 `SpringCloud` profile，去掉微服务模块依赖 |
| `application-dev.yml` | 数据库名 `jeecg-boot` → `jeecgai` |
| `application-prod.yml` | 数据库名 `jeecg-boot` → `jeecgai` |
| `application-docker.yml` | 数据库名 `jeecg-boot` → `jeecgai` |

---

## 快速应用

```bash
bash patches/apply.sh  /path/to/jeecg-boot-starter  /path/to/JeecgBoot
```

脚本会依次执行 `git apply`，失败时自动降级到 `--reject` 模式输出 `.rej` 文件供手动处理。

---

## 应用后配置

在 JeecgBoot 的 `application-dev.yml`（或其他环境配置）中追加：

```yaml
jeecg:
  codegen:
    client:
      enabled: true
      base-url: http://localhost:9999   # smart_code_ark sidecar 地址
      render-path: /internal/codegen/render
      timeout-ms: 30000
      app-id: smart_code_ark
      sign-secret: ${CODEGEN_SIGN_SECRET:}  # 留空则跳过签名验证
```

配置完成后执行：

```bash
# 1. 安装 starter
cd /path/to/jeecg-boot-starter
mvn install -DskipTests

# 2. 启动 JeecgBoot
cd /path/to/JeecgBoot/jeecg-boot
mvn spring-boot:run -pl jeecg-module-system/jeecg-system-start
```

---

## 签名协议

请求头由 `JeecgCodegenClient` 自动注入：

| Header | 说明 |
|--------|------|
| `X-SmartArk-AppId` | 应用标识（`app-id`） |
| `X-SmartArk-Timestamp` | Unix 毫秒时间戳 |
| `X-SmartArk-Nonce` | 随机 UUID（无 `-`） |
| `X-SmartArk-Body-SHA256` | 请求体 SHA-256 十六进制 |
| `X-SmartArk-Signature` | `HmacSHA256(secret, "POST\n{path}\n{ts}\n{nonce}\n{bodySha}")` |
| `X-SmartArk-Sign-Version` | `v1` |

`sign-secret` 为空时跳过签名头，适合内网开发环境。
