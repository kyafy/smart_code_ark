#!/usr/bin/env bash
# =============================================================================
# apply.sh — 一键接入 jeecg Code 功能
# 用法：
#   bash apply.sh <jeecg-boot-starter-dir> <jeecgboot-dir>
#
# 示例：
#   bash apply.sh ~/work/jeecg-boot-starter ~/work/JeecgBoot
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

STARTER_DIR="${1:-}"
JEECGBOOT_DIR="${2:-}"

# ---------- 参数校验 ----------
if [[ -z "$STARTER_DIR" || -z "$JEECGBOOT_DIR" ]]; then
  echo "用法: bash apply.sh <jeecg-boot-starter-dir> <jeecgboot-dir>"
  exit 1
fi

if [[ ! -d "$STARTER_DIR/.git" ]]; then
  echo "错误: $STARTER_DIR 不是 git 仓库"
  exit 1
fi

if [[ ! -d "$JEECGBOOT_DIR/.git" ]]; then
  echo "错误: $JEECGBOOT_DIR 不是 git 仓库"
  exit 1
fi

echo ""
echo "============================================================"
echo " [1/2] 应用 jeecg-boot-starter-codegen 模块"
echo "============================================================"
cd "$STARTER_DIR"
git apply --check "$SCRIPT_DIR/01-jeecg-boot-starter-codegen.patch" 2>&1 || {
  echo ""
  echo "  --check 失败，尝试 --reject 模式继续..."
  git apply --reject "$SCRIPT_DIR/01-jeecg-boot-starter-codegen.patch" || true
}
git apply "$SCRIPT_DIR/01-jeecg-boot-starter-codegen.patch"
echo "  [OK] jeecg-boot-starter-codegen 已应用"

echo ""
echo "============================================================"
echo " [2/2] 应用 JeecgBoot 独立运行模式改造"
echo "============================================================"
cd "$JEECGBOOT_DIR"
git apply --check "$SCRIPT_DIR/02-jeecgboot-standalone.patch" 2>&1 || {
  echo ""
  echo "  --check 失败，尝试 --reject 模式继续..."
  git apply --reject "$SCRIPT_DIR/02-jeecgboot-standalone.patch" || true
}
git apply "$SCRIPT_DIR/02-jeecgboot-standalone.patch"
echo "  [OK] JeecgBoot standalone 已应用"

echo ""
echo "============================================================"
echo " 全部完成！后续步骤："
echo ""
echo "  1. 在 jeecg-boot-starter 目录执行: mvn install -DskipTests"
echo "  2. 在 JeecgBoot/jeecg-boot 的 application-dev.yml 中"
echo "     配置 jeecg.codegen.client 属性（见 README.md）"
echo "  3. 启动 JeecgBoot，smart_code_ark 即可通过内部端点调用代码生成"
echo "============================================================"
