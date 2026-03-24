# Smart Code Ark Template Repo v1

这是当前仓库内的第一版模板仓库，用来沉淀“接近官方初始化方式、可直接继续开发”的项目骨架。

## 目标

- 提供 3 套主流技术栈模板
- 每套模板都包含可运行的基础代码
- 统一模板元数据，便于后续接入项目生成器
- 通过复制脚本快速创建新项目，而不是直接改动模板源码

## 模板列表

- `springboot-vue3-mysql`: Spring Boot 3 + Vue 3 + MySQL
- `nextjs-mysql`: Next.js + React + MySQL
- `fastapi-vue3-mysql`: FastAPI + Vue 3 + MySQL
- `fastapi-nextjs-mysql`: FastAPI + Next.js + MySQL
- `django-vue3-mysql`: Django + Vue 3 + MySQL
- `uniapp-springboot-api`: UniApp + Spring Boot API

## 目录结构

```text
template-repo/
├─ catalog.json
├─ scripts/
│  ├─ create-project.ps1
│  └─ create-project.sh
└─ templates/
   ├─ springboot-vue3-mysql/
   ├─ nextjs-mysql/
   └─ uniapp-springboot-api/
```

## 快速使用

PowerShell:

```powershell
./template-repo/scripts/create-project.ps1 `
  -Template springboot-vue3-mysql `
  -TargetPath E:\workspace\demo-app `
  -ProjectName demo-app
```

Bash:

```bash
bash template-repo/scripts/create-project.sh \
  springboot-vue3-mysql \
  /tmp/demo-app \
  demo-app
```

## 当前版本说明

v1 先解决“模板仓库骨架”和“第一批可直接改造成业务项目的基础代码”。

后续建议继续追加：

- 登录/权限特性包
- OpenAPI/Swagger 特性包
- Docker 一键部署特性包
- CI/CD 模板
- 多租户与文件上传模块
