# Golden 配置模板 + Auto-Heal 规则引擎设计方案

## Context

当前代码生成链路存在三个核心问题：

1. **配置文件被 LLM 污染**：`package.json`、`pom.xml`、`vite.config.ts` 等配置文件与业务代码使用相同 LLM prompt 生成，LLM 经常引入不存在的依赖版本、错误的 JSON 结构、无效的 plugin 配置
2. **build_fix 多轮修复同一问题**：非业务错误（依赖缺失、版本冲突、配置格式错误）被反复交给 LLM 处理，LLM 不擅长精确修复这类确定性问题
3. **测试反馈周期长**：每次调试都走完整 LLM 链路，等待时间不可控

**目标**：
- 配置文件从"LLM 生成"改为"Golden 模板 + 变量替换"，杜绝配置污染
- build_fix 前置规则引擎，确定性错误直接修复，不消耗 LLM 调用
- 提供 Fixture 测试模式，跳过 LLM 生成阶段

---

## Part 1: Golden 配置模板

### 核心思路

将文件分为两类，使用不同的生成策略：

```
file_plan 中的文件
    ├─ Golden 配置文件 → 直接从 template-repo 模板复制 + 变量替换（不经过 LLM）
    └─ 业务代码文件 → LLM 生成（当前路径不变）
```

### Golden 文件清单

基于 `template-repo/templates/springboot-vue3-mysql/` 已有模板：

| 文件 | 来源 | 变量替换 |
|------|------|---------|
| `frontend/package.json` | 模板 | `__PROJECT_NAME__` |
| `frontend/vite.config.ts` | 模板 | 无 |
| `frontend/tsconfig.json` | 模板 | 无 |
| `backend/pom.xml` | 模板 | `__PROJECT_NAME__`, `__DISPLAY_NAME__` |
| `backend/Dockerfile` | 模板 | 无 |
| `frontend/Dockerfile` | 模板 | 无 |
| `docker-compose.yml` | 模板 | `__PROJECT_NAME__` |
| `backend/src/main/resources/application.yml` | 生成 | Golden 骨架 + LLM 填充业务部分 |

### Step 1.1: 新增 `_GOLDEN_CONFIG_FILES` 集合

**修改**: `services/langchain-runtime/app/deepagent/nodes/codegen_nodes.py`

```python
# Golden config files — bypass LLM, use template directly
_GOLDEN_CONFIG_PATTERNS = {
    "package.json", "pom.xml", "tsconfig.json", "vite.config.ts",
    "vite.config.js", "dockerfile", "docker-compose.yml",
    "docker-compose.yaml", ".gitignore", ".env", ".env.example",
    "next.config.ts", "next.config.js", "prisma/schema.prisma",
}

def _is_golden_config(path: str) -> bool:
    """Check if a file should use golden template instead of LLM generation."""
    base = path.split("/")[-1].lower()
    return any(p in base for p in _GOLDEN_CONFIG_PATTERNS)
```

### Step 1.2: 修改文件分类

**修改**: `_classify_file_group()` 新增 `config` 分组

```python
def _classify_file_group(path: str) -> str:
    lower = path.lower()
    # NEW: config files get their own group
    if _is_golden_config(path):
        return "config"
    if any(ext in lower for ext in (".sql", "schema", "migration", "flyway")):
        return "database"
    # ... (existing logic)
```

### Step 1.3: 新增 `_materialize_golden_configs()` 函数

**修改**: `services/langchain-runtime/app/deepagent/nodes/codegen_nodes.py`

在 `requirement_analyze()` 返回之前，对 config 组文件注入模板内容：

```python
async def _materialize_golden_configs(
    state: Dict[str, Any],
    file_plan: List[Dict[str, Any]],
    client: JavaApiClient,
) -> Dict[str, str]:
    """Load golden config files from template-repo via Java API.
    
    Returns dict of {path: content} for config files.
    These bypass LLM generation entirely.
    """
    config_files = [f for f in file_plan if f.get("group") == "config"]
    if not config_files:
        return {}

    # Resolve template for current stack
    stack = {
        "backend": state.get("stack_backend", "springboot"),
        "frontend": state.get("stack_frontend", "vue3"),
        "db": state.get("stack_db", "mysql"),
    }
    template_info = await client.template_resolve(stack=stack, template_id=state.get("template_key", ""))
    template_key = template_info.get("template_key", "")
    if not template_key:
        return {}

    # Load template files for matching config paths
    golden = {}
    template_files = await client.template_list_files(template_key)
    
    project_name = _slugify_project_name(state.get("prd", "project")[:50])
    
    for cf in config_files:
        path = cf["path"]
        # Find matching template file
        template_path = _find_template_match(path, template_files)
        if template_path:
            content = await client.template_read_file(template_key, template_path)
            # Apply variable replacements
            content = content.replace("__PROJECT_NAME__", project_name)
            content = content.replace("__DISPLAY_NAME__", state.get("prd", "Project")[:30])
            golden[path] = content

    return golden
```

### Step 1.4: 在代码生成节点中跳过 Golden 文件

**修改**: `_generate_files_concurrent()` 和 `_generate_files_by_groups()`

```python
# 在 _generate_files_by_groups 中:
if groups == {"config"}:
    # Config files already materialized from golden templates
    return {"generated_files": {}}

# 在 _generate_files_concurrent 中, 每个文件生成前:
if path in state.get("generated_files", {}):
    # Already populated (golden config or cache hit), skip LLM
    continue
```

### Step 1.5: Java 侧新增模板文件读取 API

**修改**: `InternalAgentController.java`

```java
// 列出模板中的文件
POST /api/internal/template/{templateKey}/files
Response: { files: ["frontend/package.json", "backend/pom.xml", ...] }

// 读取模板文件内容
POST /api/internal/template/{templateKey}/read
Request: { path: "frontend/package.json" }
Response: { content: "..." }
```

**修改**: `java_api_client.py` 新增对应方法

---

## Part 2: Auto-Heal 规则引擎

### 核心思路

在 `sandbox_build_fix()` 的 LLM 修复之前，先跑一遍确定性规则修复：

```
build_log 错误
    ↓
Phase 1: Auto-Heal 规则引擎（无 LLM 调用）
    → 模式匹配 → 确定性修复 → 重新构建
    ↓ (仍有错误)
Phase 2: LLM 修复（现有路径）
    → 仅处理业务逻辑错误
```

### Step 2.1: 新增 `_auto_heal_build_errors()` 函数

**修改**: `services/langchain-runtime/app/deepagent/nodes/codegen_nodes.py`

```python
async def _auto_heal_build_errors(
    sandbox, 
    build_log: str,
    frontend_root: Optional[str],
    backend_root: Optional[str],
) -> tuple[bool, str]:
    """Apply deterministic fixes for common build errors.
    
    Returns (any_fixes_applied, description_of_fixes).
    """
    fixes_applied = []
    
    # --- npm / Node.js rules ---
    if frontend_root:
        # Rule 1: ERESOLVE peer dependency conflict
        if "ERESOLVE" in build_log or "peer dep" in build_log.lower():
            await sandbox.execute(
                f"cd {frontend_root} && npm install --legacy-peer-deps 2>&1",
                timeout=120,
            )
            fixes_applied.append("npm: applied --legacy-peer-deps")

        # Rule 2: Package version not found (ERR! 404 / ETARGET)
        for pattern in [
            r"404\s+Not Found.*['\"]([@\w/-]+)@([^\s'\"]+)",
            r"No matching version found for\s+([@\w/-]+)@([^\s]+)",
        ]:
            for match in re.finditer(pattern, build_log):
                pkg = match.group(1)
                await sandbox.execute(
                    f"cd {frontend_root} && npm pkg delete dependencies.{pkg} && npm pkg delete devDependencies.{pkg}",
                    timeout=20,
                )
                fixes_applied.append(f"npm: removed unavailable package {pkg}")

        # Rule 3: Cannot find module (missing dependency)
        for match in re.finditer(
            r"Cannot find module '([@\w/-]+)'", build_log
        ):
            module_name = match.group(1)
            if not module_name.startswith(".") and not module_name.startswith("/"):
                await sandbox.execute(
                    f"cd {frontend_root} && npm install {module_name} --save 2>&1",
                    timeout=60,
                )
                fixes_applied.append(f"npm: installed missing {module_name}")

        # Rule 4: vite.config.ts syntax error → restore golden template
        if "failed to load config from" in build_log.lower():
            golden = _get_golden_vite_config()
            if golden:
                await sandbox.write(f"{frontend_root}/vite.config.ts", golden)
                fixes_applied.append("vite: restored golden vite.config.ts")

        # Rule 5: tsconfig.json parse error → restore golden template
        if "error TS5024" in build_log or "tsconfig.json" in build_log.lower() and "error" in build_log.lower():
            golden = _get_golden_tsconfig()
            if golden:
                await sandbox.write(f"{frontend_root}/tsconfig.json", golden)
                fixes_applied.append("tsconfig: restored golden tsconfig.json")

        # Rule 6: Missing build script
        if "'build' is not found" in build_log or "missing script: build" in build_log.lower():
            await sandbox.execute(
                f'cd {frontend_root} && npm pkg set scripts.build="vue-tsc -b && vite build"',
                timeout=10,
            )
            fixes_applied.append("npm: added missing build script")

    # --- Maven / Java rules ---
    if backend_root:
        # Rule 7: POM XML parse error → restore golden pom.xml
        if "Non-parseable POM" in build_log or "Malformed POM" in build_log:
            golden = _get_golden_pom()
            if golden:
                await sandbox.write(f"{backend_root}/pom.xml", golden)
                fixes_applied.append("maven: restored golden pom.xml")

        # Rule 8: Missing dependency → try adding to pom
        for match in re.finditer(
            r"package ([\w.]+) does not exist", build_log
        ):
            pkg = match.group(1)
            if "lombok" in pkg.lower():
                await _inject_maven_dependency(sandbox, backend_root,
                    "org.projectlombok", "lombok", scope="provided")
                fixes_applied.append(f"maven: added lombok dependency")

        # Rule 9: javax → jakarta migration
        if "package javax." in build_log and "does not exist" in build_log:
            # Replace javax.* imports with jakarta.* in affected files
            error_files = _extract_error_files(build_log)
            for fp in error_files[:5]:
                try:
                    content = await sandbox.read(f"/app/{fp}")
                    if "import javax." in content:
                        content = content.replace("import javax.persistence", "import jakarta.persistence")
                        content = content.replace("import javax.validation", "import jakarta.validation")
                        content = content.replace("import javax.servlet", "import jakarta.servlet")
                        await sandbox.write(f"/app/{fp}", content)
                        fixes_applied.append(f"java: javax→jakarta migration in {fp}")
                except Exception:
                    pass

    return len(fixes_applied) > 0, "; ".join(fixes_applied)
```

### Step 2.2: Golden 配置恢复函数

```python
def _get_golden_vite_config() -> str:
    """Return the golden vite.config.ts content."""
    return '''import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    host: '0.0.0.0',
    port: 5173
  },
  test: {
    environment: 'jsdom',
    globals: true
  }
})
'''

def _get_golden_tsconfig() -> str:
    """Return the golden tsconfig.json content."""
    return '''{
  "extends": "@vue/tsconfig/tsconfig.dom.json",
  "compilerOptions": {
    "tsBuildInfoFile": "./node_modules/.tmp/tsconfig.app.tsbuildinfo",
    "types": ["vite/client"]
  },
  "include": ["src/**/*.ts", "src/**/*.tsx", "src/**/*.vue"]
}
'''

def _get_golden_pom() -> Optional[str]:
    """Return None — pom.xml needs project-specific content, 
    can't blindly restore. Only restore if parse error."""
    return None  # Phase 1: skip pom golden restore
```

### Step 2.3: 集成到 `sandbox_build_fix()`

**修改**: `sandbox_build_fix()` 函数，在 LLM 修复之前插入

```python
async def sandbox_build_fix(state):
    # ... existing code ...
    
    # Phase 6: Auto-heal before LLM fix
    frontend_root, backend_root = await _detect_project_roots(sandbox, state)
    healed, heal_desc = await _auto_heal_build_errors(
        sandbox, build_log, frontend_root, backend_root
    )
    if healed:
        await client.log(task_id, "info", f"Auto-heal applied: {heal_desc}")
        # Re-run build to check if auto-heal resolved the issue
        verify_result = await sandbox_build_verify(state)
        if verify_result.get("build_status") == "passed":
            # Auto-heal fixed everything — skip LLM fix entirely
            return {
                **verify_result,
                "build_fix_round": round_num,
                "fix_history": fix_history,
                "current_step": "build_fix",
            }
        # Auto-heal partially helped, continue with LLM fix on remaining errors
        build_log = verify_result.get("build_log", build_log)
    
    # ... existing LLM fix code ...
```

### Step 2.4: 同样集成到 `sandbox_compile_check()`

在 `compile_check` 内部 fix 循环中也加入 auto-heal。

---

## Part 3: Fixture 测试模式

### 核心思路

提供环境变量开关，允许跳过 LLM 生成阶段，直接使用预存的文件快照进行沙箱测试。

### Step 3.1: 配置项

```python
# config.py
fixture_mode: bool = False          # DEEPAGENT_FIXTURE_MODE
fixture_dir: str = ""               # DEEPAGENT_FIXTURE_DIR (JSON 文件路径)
```

### Step 3.2: 修改 `requirement_analyze`

```python
if cfg.fixture_mode and cfg.fixture_dir:
    # Load pre-saved generated_files from fixture
    fixture_path = Path(cfg.fixture_dir) / "generated_files.json"
    if fixture_path.exists():
        with open(fixture_path) as f:
            fixture_data = json.load(f)
        return {
            "file_plan": fixture_data.get("file_plan", []),
            "file_list": fixture_data.get("file_list", []),
            "generated_files": fixture_data.get("generated_files", {}),
            "current_step": "requirement_analyze",
        }
```

### Step 3.3: 成功后自动保存 Fixture

在 `sandbox_preview_deploy()` 成功后：

```python
if os.getenv("DEEPAGENT_SAVE_FIXTURE", "false").lower() == "true":
    fixture_dir = Path(os.getenv("DEEPAGENT_FIXTURE_DIR", "/tmp/smartark/fixtures"))
    fixture_dir.mkdir(parents=True, exist_ok=True)
    fixture = {
        "file_plan": state.get("file_plan", []),
        "file_list": state.get("file_list", []),
        "generated_files": state.get("generated_files", {}),
        "stack": {
            "backend": state.get("stack_backend"),
            "frontend": state.get("stack_frontend"),
            "db": state.get("stack_db"),
        },
    }
    with open(fixture_dir / f"{task_id}.json", "w") as f:
        json.dump(fixture, f, ensure_ascii=False)
```

---

## 修改文件清单

| 文件 | 操作 | Part | 说明 |
|------|------|------|------|
| `codegen_nodes.py` | 修改 | 1,2,3 | Golden 分类 + auto-heal + fixture |
| `java_api_client.py` | 修改 | 1 | 模板文件读取 API |
| `InternalAgentController.java` | 修改 | 1 | 模板读取端点 |
| `config.py` | 修改 | 3 | fixture 配置项 |
| `codegen_state.py` | 修改 | - | 可选：新增 auto_heal_applied 字段 |

## 实施优先级

| 优先级 | 模块 | 预期收益 | 复杂度 |
|--------|------|---------|--------|
| **P0** | Auto-Heal 规则引擎（Part 2） | 减少 50%+ 无效 LLM fix 轮次 | 低（纯 Python 逻辑） |
| **P0** | Golden vite.config/tsconfig 恢复 | 消灭最常见配置污染 | 低（硬编码模板） |
| **P1** | Golden 配置模板系统（Part 1） | 杜绝配置文件 LLM 生成 | 中（需 Java API） |
| **P2** | Fixture 测试模式（Part 3） | 加速开发迭代 | 低 |

建议先实施 **P0（Auto-Heal + Golden 恢复）**，这部分不需要 Java 侧改动，纯 Python 即可完成，效果立竿见影。

---

## 预估收益

| 场景 | 当前 | 实施后 | 改善 |
|------|------|--------|------|
| vite.config 被污染 | 2-3 轮 LLM fix（大概率失败） | 1 次 golden 恢复（确定性成功） | **100% 修复率** |
| npm 依赖版本不存在 | 1-2 轮 LLM fix（随机修改） | 规则删除 + 重装（确定性） | **省 2 轮 fix** |
| javax→jakarta 迁移问题 | LLM 可能修也可能不修 | 正则替换（确定性） | **100% 修复率** |
| tsconfig 格式错误 | LLM 重写（可能引入新错误） | golden 恢复 | **100% 修复率** |
| 测试 build_fix 逻辑 | 每次 10-30 分钟 | fixture 模式 <2 分钟 | **节省 80% 时间** |

## Verification

1. **Python 语法**：`py_compile` 所有修改文件
2. **单元测试**：mock sandbox.execute + build_log，验证 auto-heal 规则命中
3. **集成测试**：构造一个 vite.config 被污染的项目，验证 auto-heal 恢复后 build 通过
4. **回归测试**：正常项目（无配置错误），验证 auto-heal 不干扰正常流程
