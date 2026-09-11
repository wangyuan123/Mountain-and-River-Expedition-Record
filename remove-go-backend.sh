#!/usr/bin/env bash
set -Eeuo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GO_SERVER_DIR="$PROJECT_DIR/server"
COMPOSE_FILE="$PROJECT_DIR/docker-compose.yml"

log() {
  printf '\n==> %s\n' "$*"
}

fail() {
  printf '\n错误：%s\n' "$*" >&2
  exit 1
}

main() {
  [[ -d "$GO_SERVER_DIR" ]] || fail "未找到 server 目录，无需清理。"
  [[ -f "$COMPOSE_FILE" ]] || fail "未找到 docker-compose.yml。"

  log "将删除旧 Go + SQLite 后端目录：$GO_SERVER_DIR"
  rm -rf "$GO_SERVER_DIR"

  if grep -Eqi '(^|[[:space:]/])server([/:[:space:]]|$)|golang|wargame-server' "$COMPOSE_FILE"; then
    fail "docker-compose.yml 仍包含 Go 后端引用，请手动检查：$COMPOSE_FILE"
  fi

  log "Docker Compose 中未发现 Go 后端配置，无需修改。"
  log "清理完成：已保留 Java + MySQL + Nginx 配置。"
}

main "$@"
