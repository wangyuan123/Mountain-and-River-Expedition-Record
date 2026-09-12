#!/usr/bin/env bash
# 本地工程完整校验脚本（对标 CI verify.yml）

set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "=========================================="
echo "      项目代码完整校验 (Verify Game)      "
echo "=========================================="

# 1. 前端测试
echo ""
echo "==> [1/2] 正在运行前端回归测试..."
if command -v node >/dev/null 2>&1; then
  node --test "$ROOT_DIR"/frontend/tests/*.test.cjs
  echo "==> 前端测试通过 ✓"
else
  echo "[警告] 未安装 Node.js，跳过前端测试。"
fi

# 2. 后端校验
echo ""
echo "==> [2/2] 正在运行后端编译与单元测试..."
source "$ROOT_DIR/scripts/env-check.sh"
"$MVN_CMD" -f "$ROOT_DIR/backend/pom.xml" -B test
echo "==> 后端校验通过 ✓"

echo ""
echo "=========================================="
echo "       所有工程校验通过，状态健康！✓       "
echo "=========================================="
