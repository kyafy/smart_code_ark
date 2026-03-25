#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 2 ]; then
  echo "usage: create-project.sh <template> <target-path> [project-name]" >&2
  exit 1
fi

TEMPLATE="$1"
TARGET_PATH="$2"
PROJECT_NAME="${3:-smart-template-app}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
TEMPLATE_DIR="$REPO_DIR/templates/$TEMPLATE"

if [ ! -d "$TEMPLATE_DIR" ]; then
  echo "template not found: $TEMPLATE" >&2
  exit 1
fi

if [ -e "$TARGET_PATH" ]; then
  echo "target path already exists: $TARGET_PATH" >&2
  exit 1
fi

mkdir -p "$TARGET_PATH"
cp -R "$TEMPLATE_DIR"/. "$TARGET_PATH"

DISPLAY_NAME="$(printf '%s' "$PROJECT_NAME" | sed -E 's/[-_]+/ /g')"
if [ -z "$DISPLAY_NAME" ]; then
  DISPLAY_NAME="Smart Template App"
fi

find "$TARGET_PATH" -type f | while read -r file; do
  case "$file" in
    *.md|*.json|*.ts|*.tsx|*.js|*.jsx|*.css|*.scss|*.sass|*.less|*.vue|*.html|*.yml|*.yaml|*.properties|*.sql|*.java|*.xml|*.sh|*.ps1|*.gitignore|*.example|*.env)
      sed -i.bak \
        -e "s/__PROJECT_NAME__/$PROJECT_NAME/g" \
        -e "s/__DISPLAY_NAME__/$DISPLAY_NAME/g" \
        "$file"
      rm -f "$file.bak"
      ;;
  esac
done

echo "Project created successfully."
echo "Template   : $TEMPLATE"
echo "TargetPath : $TARGET_PATH"
echo "ProjectName: $PROJECT_NAME"
