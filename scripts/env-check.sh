#!/usr/bin/env bash
# 环境检测与配置辅助脚本：确保 Java 17 和 Maven / mvnw 可用

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# 1. 检测与配置 Java 17
setup_java17() {
  local java_home=""
  if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
    if "${JAVA_HOME}/bin/java" -version 2>&1 | head -n 1 | grep -Eq '"17([._]|"|$)'; then
      return 0
    fi
  fi

  if [[ -x /usr/libexec/java_home ]]; then
    java_home="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
  fi

  if [[ -z "$java_home" ]] && command -v brew >/dev/null 2>&1; then
    local brew_prefix
    brew_prefix="$(brew --prefix openjdk@17 2>/dev/null || true)"
    if [[ -n "$brew_prefix" && -d "$brew_prefix" ]]; then
      java_home="$brew_prefix/libexec/openjdk.jdk/Contents/Home"
    fi
  fi

  if [[ -n "$java_home" && -d "$java_home" ]]; then
    export JAVA_HOME="$java_home"
    export PATH="$JAVA_HOME/bin:$PATH"
  fi

  if ! command -v java >/dev/null 2>&1 || ! java -version 2>&1 | head -n 1 | grep -Eq '"17([._]|"|$)'; then
    echo "[错误] 未检测到 JDK 17 环境，请确保已安装 JDK 17。" >&2
    return 1
  fi
  return 0
}

# 2. 定位 Maven 可执行文件
setup_maven() {
  if [[ -x "$ROOT_DIR/backend/mvnw" ]]; then
    export MVN_CMD="$ROOT_DIR/backend/mvnw"
  elif command -v mvn >/dev/null 2>&1; then
    export MVN_CMD="mvn"
  elif [[ -x "$HOME/tools/apache-maven-3.9.6/bin/mvn" ]]; then
    export MVN_CMD="$HOME/tools/apache-maven-3.9.6/bin/mvn"
    export PATH="$HOME/tools/apache-maven-3.9.6/bin:$PATH"
  elif [[ -x "/opt/homebrew/bin/mvn" ]]; then
    export MVN_CMD="/opt/homebrew/bin/mvn"
  elif [[ -x "/usr/local/bin/mvn" ]]; then
    export MVN_CMD="/usr/local/bin/mvn"
  else
    echo "[错误] 未检测到 Maven 或 backend/mvnw，请确保 Maven 可用。" >&2
    return 1
  fi
  return 0
}

setup_java17
setup_maven
