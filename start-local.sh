#!/usr/bin/env bash
set -Eeuo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$PROJECT_DIR/backend"
FRONTEND_DIR="$PROJECT_DIR/frontend"
FRONTEND_PORT=8081
BACKEND_PORT=8080

log() {
  printf '\n==> %s\n' "$*"
}

fail() {
  printf '\n错误：%s\n' "$*" >&2
  exit 1
}

has_java17() {
  command -v java >/dev/null 2>&1 && java -version 2>&1 | head -n 1 | grep -Eq '"17([._]|"|$)'
}

install_homebrew() {
  if command -v brew >/dev/null 2>&1; then
    return
  fi

  log "未检测到 Homebrew，正在安装"
  /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"

  if ! command -v brew >/dev/null 2>&1; then
    if [[ -x /opt/homebrew/bin/brew ]]; then
      eval "$(/opt/homebrew/bin/brew shellenv)"
    elif [[ -x /usr/local/bin/brew ]]; then
      eval "$(/usr/local/bin/brew shellenv)"
    fi
  fi

  command -v brew >/dev/null 2>&1 || fail "Homebrew 安装完成后仍不可用，请重新打开终端再执行本脚本。"
}

configure_java17() {
  if [[ -x /usr/libexec/java_home ]]; then
    JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
    if [[ -n "$JAVA_HOME" ]]; then
      export JAVA_HOME
      export PATH="$JAVA_HOME/bin:$PATH"
    fi
  fi

  if ! has_java17; then
    local brew_prefix
    brew_prefix="$(brew --prefix openjdk@17)"
    export JAVA_HOME="$brew_prefix/libexec/openjdk.jdk/Contents/Home"
    export PATH="$JAVA_HOME/bin:$PATH"
  fi

  has_java17 || fail "无法启用 JDK 17。"
}

ensure_java17() {
  if ! has_java17 && [[ -x /usr/libexec/java_home ]]; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
    [[ -n "$JAVA_HOME" ]] && export PATH="$JAVA_HOME/bin:$PATH"
  fi

  if ! has_java17; then
    log "未检测到 JDK 17，正在安装 openjdk@17"
    brew install openjdk@17
  fi

  configure_java17
  log "JDK 已就绪：$(java -version 2>&1 | head -n 1)"
}

ensure_maven() {
  if ! command -v mvn >/dev/null 2>&1; then
    # 检查本地 tools 目录
    local local_maven="$HOME/tools/apache-maven-3.9.6/bin/mvn"
    if [[ -x "$local_maven" ]]; then
      export PATH="$HOME/tools/apache-maven-3.9.6/bin:$PATH"
    else
      log "未检测到 Maven，正在下载安装到 ~/tools"
      mkdir -p "$HOME/tools"
      curl -fsSL "https://archive.apache.org/dist/maven/maven-3/3.9.6/binaries/apache-maven-3.9.6-bin.tar.gz" -o /tmp/maven.tar.gz
      tar xzf /tmp/maven.tar.gz -C "$HOME/tools"
      rm /tmp/maven.tar.gz
      export PATH="$HOME/tools/apache-maven-3.9.6/bin:$PATH"
    fi
  fi
  command -v mvn >/dev/null 2>&1 || fail "Maven 安装失败。"
  log "Maven 已就绪：$(mvn -version | head -n 1)"
}

ensure_mysql() {
  if ! command -v mysql >/dev/null 2>&1; then
    log "未检测到 MySQL 客户端，正在安装 mysql-client"
    brew install mysql-client
    export PATH="$(brew --prefix mysql-client)/bin:$PATH"
  fi

  if ! mysql -u root -e "SELECT 1" >/dev/null 2>&1; then
    if brew services list 2>/dev/null | grep -q mysql; then
      log "正在启动 MySQL 服务"
      brew services start mysql
    else
      log "未检测到 MySQL 服务，正在安装 mysql"
      brew install mysql
      brew services start mysql
    fi

    local waited=0
    until mysql -u root -e "SELECT 1" >/dev/null 2>&1; do
      if (( waited >= 60 )); then
        fail "MySQL 在 60 秒内未就绪，请手动启动 MySQL 后重试。"
      fi
      sleep 2
      ((waited += 2))
      printf '.'
    done
    printf '\n'
  fi

  log "MySQL 已就绪"
}

ensure_database() {
  log "创建数据库 wargame（如不存在）"
  mysql -u root -e "CREATE DATABASE IF NOT EXISTS wargame DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
}

stop_port_process() {
  local port="$1"
  local pids
  pids="$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)"

  if [[ -n "$pids" ]]; then
    log "清理端口 $port 上的旧进程"
    kill $pids 2>/dev/null || true
    sleep 2
  fi
}

start_backend() {
  log "启动后端（mvn spring-boot:run）"
  cd "$BACKEND_DIR"
  mvn spring-boot:run &
  BACKEND_PID=$!
  log "后端进程 PID: ${BACKEND_PID}，等待启动..."

  local waited=0
  until curl -s "http://localhost:$BACKEND_PORT/api/game/state" >/dev/null 2>&1 || \
        curl -s -o /dev/null -w "%{http_code}" "http://localhost:$BACKEND_PORT/api/game/state" | grep -qE '4[0-9][0-9]'; do
    if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
      fail "后端进程已退出，请检查日志。"
    fi
    if (( waited >= 120 )); then
      fail "后端在 120 秒内未响应，请检查 http://localhost:$BACKEND_PORT"
    fi
    sleep 2
    ((waited += 2))
    printf '.'
  done
  printf '\n'
  log "后端已就绪：http://localhost:$BACKEND_PORT"
}

start_frontend() {
  log "启动前端静态服务器（端口 ${FRONTEND_PORT}）"

  if command -v python3 >/dev/null 2>&1; then
    cd "$FRONTEND_DIR"
    python3 -m http.server "$FRONTEND_PORT" &
    FRONTEND_PID=$!
  elif command -v python >/dev/null 2>&1; then
    cd "$FRONTEND_DIR"
    python -m SimpleHTTPServer "$FRONTEND_PORT" &
    FRONTEND_PID=$!
  elif command -v npx >/dev/null 2>&1; then
    cd "$FRONTEND_DIR"
    npx http-server -p "$FRONTEND_PORT" &
    FRONTEND_PID=$!
  else
    fail "未找到 Python 或 Node.js，无法启动前端服务器。请安装 python3。"
  fi

  log "前端已就绪：http://localhost:$FRONTEND_PORT"
}

cleanup() {
  log "正在关闭服务..."
  [[ -n "${FRONTEND_PID:-}" ]] && kill "$FRONTEND_PID" 2>/dev/null || true
  [[ -n "${BACKEND_PID:-}" ]] && kill "$BACKEND_PID" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

ensure_git_hooks() {
  if [[ -f "$PROJECT_DIR/scripts/install-hooks.sh" ]]; then
    bash "$PROJECT_DIR/scripts/install-hooks.sh" >/dev/null 2>&1 || true
  fi
}

main() {
  [[ -f "$BACKEND_DIR/pom.xml" ]] || fail "未找到 backend/pom.xml。"
  [[ -f "$FRONTEND_DIR/index.html" ]] || fail "未找到 frontend/index.html。"

  install_homebrew
  ensure_java17
  ensure_maven
  ensure_mysql
  ensure_database
  ensure_git_hooks

  stop_port_process "$BACKEND_PORT"
  stop_port_process "$FRONTEND_PORT"
  start_backend
  start_frontend

  printf '\n========================================\n'
  printf '  游戏已启动！\n'
  printf '  前端：http://localhost:%s\n' "$FRONTEND_PORT"
  printf '  后端：http://localhost:%s\n' "$BACKEND_PORT"
  printf '  数据库：MySQL localhost:3306/wargame\n'
  printf '========================================\n'
  printf '\n按 Ctrl+C 停止所有服务。\n'

  wait
}

main "$@"
