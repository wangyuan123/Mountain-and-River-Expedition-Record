#!/usr/bin/env bash
# 一键部署入口；凭证、备份与验收规则集中维护，避免不安全的降级分支。
set -Eeuo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if ! command -v python3 >/dev/null 2>&1; then
  printf '%s\n' '部署失败：需要 Python 3.9+。Ubuntu/Debian 请先安装 python3。' >&2
  exit 1
fi
exec python3 "$SCRIPT_DIR/scripts/deploy.py" "$@"
