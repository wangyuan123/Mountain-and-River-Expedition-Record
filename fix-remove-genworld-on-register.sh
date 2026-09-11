#!/usr/bin/env bash
# ============================================================================
# Fix script: 移除 initializeNewPlayer 中的 genWorld(null) 调用
# Issue:      新玩家注册会触发全服地图重置（删除所有 bandits / npc_cities
#             / player_cities / wild_tiles）
# Severity:   P0 - 任何新玩家注册都会清空所有老玩家的世界
# ============================================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
TARGET_FILE="${ROOT_DIR}/backend/src/main/java/com/wargame/service/GameStateService.java"
BACKUP_FILE="${TARGET_FILE}.bak.$(date +%Y%m%d%H%M%S)"

echo "==> [1/4] 备份原文件"
cp "${TARGET_FILE}" "${BACKUP_FILE}"
echo "    备份: ${BACKUP_FILE}"

echo "==> [2/4] 检查目标文件"
if [[ ! -f "${TARGET_FILE}" ]]; then
  echo "ERROR: 找不到 ${TARGET_FILE}" >&2
  exit 1
fi

# 已经修复过就跳过
if ! grep -q "genWorld(null);" "${TARGET_FILE}"; then
  echo "    检测到 genWorld(null) 已被移除，跳过补丁"
else
  echo "==> [3/4] 应用补丁（移除 genWorld(null) 并写入注释）"
  python3 - "${TARGET_FILE}" <<'PY'
import re, sys, pathlib
p = pathlib.Path(sys.argv[1])
src = p.read_text(encoding="utf-8")
old = "        // Generate world\n        genWorld(null);\n"
new = (
    "        // NOTE: genWorld() is intentionally NOT called here.\n"
    "        // Previously each new player triggered `genWorld(null)` which:\n"
    "        //   1) created a brand-new isolated WorldMap (orphaning the shared one)\n"
    "        //   2) wiped every existing bandit / npc_city / player_city / wild_tile\n"
    "        // World initialization must only run ONCE at server bootstrap\n"
    "        // (see WorldBootstrap / DataInitializer), not on every signup.\n"
)
if old not in src:
    print("ERROR: 找不到待替换片段，请手动检查", file=sys.stderr)
    sys.exit(2)
p.write_text(src.replace(old, new, 1), encoding="utf-8")
print("    补丁已应用")
PY
fi

echo "==> [4/4] 验证"
if grep -q "genWorld(null);" "${TARGET_FILE}"; then
  echo "ERROR: 补丁未生效" >&2
  exit 3
fi
echo "    OK - 'genWorld(null);' 已不再出现"

echo
echo "=== 下一步建议 ==="
echo "1) 清理因旧 bug 产生的孤儿世界 (见 V3__cleanup_orphan_worlds.sql)"
echo "2) 重启后端服务让改动生效"
echo "3) 用新账号走一遍注册流程，确认世界不再被清空"
