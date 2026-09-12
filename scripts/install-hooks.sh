#!/usr/bin/env bash
# 安装并配置 Git 提交校验钩子

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "==> 正在配置 Git 钩子..."

# 赋予执行权限
chmod +x "$ROOT_DIR"/scripts/*.sh 2>/dev/null || true
chmod +x "$ROOT_DIR"/.githooks/* 2>/dev/null || true
[[ -f "$ROOT_DIR/backend/mvnw" ]] && chmod +x "$ROOT_DIR/backend/mvnw" || true

# 配置 Git 使用 .githooks 目录
git config core.hooksPath .githooks

# 同时在 .git/hooks 中同步一份作为双重保障
if [[ -d "$ROOT_DIR/.git/hooks" ]]; then
  mkdir -p "$ROOT_DIR/.git/hooks"
  cp "$ROOT_DIR/.githooks/pre-commit" "$ROOT_DIR/.git/hooks/pre-commit"
  chmod +x "$ROOT_DIR/.git/hooks/pre-commit"
  if [[ -f "$ROOT_DIR/.githooks/pre-push" ]]; then
    cp "$ROOT_DIR/.githooks/pre-push" "$ROOT_DIR/.git/hooks/pre-push"
    chmod +x "$ROOT_DIR/.git/hooks/pre-push"
  fi
fi

echo "==> Git 提交/推送校验钩子已成功配置！(core.hooksPath = .githooks)"
echo "    - 提交后端代码时自动执行编译校验 (支持 FAST_COMMIT=1)"
echo "    - 提交前端代码时自动执行前端回归测试"
echo "    - 推送代码时自动执行全量回归校验"
