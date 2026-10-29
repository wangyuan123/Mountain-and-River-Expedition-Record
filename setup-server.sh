#!/usr/bin/env bash
# 独立服务器环境初始化；只依赖系统 Bash/apt，可在上传完整项目之前执行。
set -Eeuo pipefail

SETUP_OS_RELEASE=/etc/os-release
SETUP_SYSTEMD_DIR=/run/systemd/system
SETUP_SOURCE_DIR=/etc/apt/sources.list.d
SETUP_MAIN_SOURCE=/etc/apt/sources.list
SETUP_KEYRING_DIR=/etc/apt/keyrings
SETUP_SWAPFILE=/swapfile
SETUP_FSTAB=/etc/fstab
SETUP_WORK_DIR=''
SETUP_SWAP_GB=0
SETUP_SWAP_NEEDED=false

log() { printf '[环境配置] %s\n' "$*"; }
fail() { printf '[环境配置失败] %s\n' "$*" >&2; exit 1; }

usage() {
  cat <<'HELP'
用法：sudo bash setup-server.sh [--swap-gb 2] [--check]

支持 Ubuntu 22.04 / Debian 12，amd64 / arm64，要求 systemd。
默认安装 Python 3、Git、Nano、Docker Engine 与 Compose V2 并启动 Docker。
  --swap-gb N  无现有交换分区时创建 N GB Swap（1~16），持久化到 /etc/fstab
  --check      只检查环境，不安装软件、不启动服务、不修改 Swap
  --help       查看说明

不更改 SSH、防火墙、用户组、游戏配置或数据库，不启动游戏容器。
HELP
}

# 限定经过部署约定的发行版，防止给 Debian 写入 Ubuntu 软件源。
detect_platform() {
  [[ "$(uname -s)" == Linux ]] || fail '仅支持 Linux 云服务器，请勿在本机 Mac/Windows 执行。'
  [[ -r "$SETUP_OS_RELEASE" ]] || fail '无法识别操作系统。'
  local ID='' VERSION_ID='' VERSION_CODENAME=''
  . "$SETUP_OS_RELEASE"
  case "$ID:$VERSION_ID:$VERSION_CODENAME" in
    ubuntu:22.04:jammy|debian:12:bookworm) ;;
    *) fail '目前仅支持 Ubuntu 22.04 (jammy) 或 Debian 12 (bookworm)。' ;;
  esac
  SETUP_DISTRO="$ID"
  SETUP_SUITE="$VERSION_CODENAME"
  SETUP_ARCH="$(dpkg --print-architecture)"
  case "$SETUP_ARCH" in amd64|arm64) ;; *) fail '目前仅支持 amd64 / arm64 架构。' ;; esac
  [[ -d "$SETUP_SYSTEMD_DIR" ]] || fail '需要由 systemd 启动的服务器，不支持容器内或 WSL 初始化。'
  log "系统：$SETUP_DISTRO $VERSION_ID，架构：$SETUP_ARCH"
}

# 有完整可用的 Docker 时保留原安装来源；不自动卸载旧包或迁移已有引擎。
docker_install_needed() {
  if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
    return 1
  fi
  return 0
}

check_package_conflicts() {
  local package status
  for package in docker.io docker-doc docker-compose docker-compose-v2 podman-docker containerd runc; do
    status="$(dpkg-query -W -f='${Status}' "$package" 2>/dev/null || true)"
    [[ "$status" != 'install ok installed' ]] || fail "检测到已有 $package，且 Docker/Compose 不完整。请先按 Docker 官方说明处理冲突；脚本不会自动卸载。"
  done
}

check_repository_conflicts() {
  local file
  # 只管理自己的软件源文件，其他文件已有 Docker 源时交由管理员核对签名/发行版。
  for file in "$SETUP_MAIN_SOURCE" "$SETUP_SOURCE_DIR"/*.list "$SETUP_SOURCE_DIR"/*.sources; do
    [[ -f "$file" && "$file" != "$SETUP_SOURCE_DIR/wargame-docker.sources" ]] || continue
    if grep -Eq '^[[:space:]]*[^#].*download\.docker\.com' "$file"; then
      fail "检测到已有 Docker 软件源 $file。请先核对/合并来源，避免重复配置或 Signed-By 冲突。"
    fi
  done
}

# 创建 Swap 前先排除旧文件、已有 Swap、非支持文件系统与磁盘不足。
plan_swap() {
  SETUP_SWAP_NEEDED=false
  (( SETUP_SWAP_GB > 0 )) || return 0
  local active filesystem available required
  active="$(swapon --noheadings --show=NAME)"
  if [[ -n "$active" ]]; then
    log '已有启用的 Swap，保留现状，不新增、不改写 /etc/fstab。'
    return 0
  fi
  [[ ! -e "$SETUP_SWAPFILE" && ! -L "$SETUP_SWAPFILE" ]] || fail '已有 /swapfile，脚本不会覆盖；请先人工检查。'
  [[ -f "$SETUP_FSTAB" && -r "$SETUP_FSTAB" && ! -L "$SETUP_FSTAB" ]] || fail '无法安全读取 /etc/fstab，请先人工检查。'
  if awk -v path="$SETUP_SWAPFILE" '$1 == path { found=1 } END { exit !found }' "$SETUP_FSTAB"; then
    fail '/etc/fstab 已有 /swapfile 配置但当前未启用，请先人工检查。'
  fi
  filesystem="$(findmnt -n -o FSTYPE --target /)"
  case "$filesystem" in ext4|xfs) ;; *) fail '自动 Swap 仅支持 ext4/xfs；其他文件系统需人工配置。' ;; esac
  available="$(df -Pk / | awk 'NR==2 {print $4}')"
  [[ "$available" =~ ^[0-9]+$ ]] || fail '无法确定根分区剩余空间。'
  required=$(( (SETUP_SWAP_GB + 2) * 1024 * 1024 ))
  (( available >= required )) || fail '创建 Swap 后至少需保留 2GB 空间，请先扩容或清理非数据库文件。'
  SETUP_SWAP_NEEDED=true
}

install_runtime() {
  local need_docker=false
  if docker_install_needed; then
    need_docker=true
    check_package_conflicts
    check_repository_conflicts
  fi
  log '安装基础工具：Python 3、Git、Nano、curl、证书和系统工具。'
  apt-get update
  DEBIAN_FRONTEND=noninteractive apt-get install -y ca-certificates curl git python3 nano util-linux
  if [[ "$need_docker" == true ]]; then
    install -m 0755 -d "$SETUP_KEYRING_DIR" "$SETUP_SOURCE_DIR"
    curl --fail --silent --show-error --location --proto '=https' --tlsv1.2 \
      --retry 3 --connect-timeout 15 --max-time 120 \
      "https://download.docker.com/linux/$SETUP_DISTRO/gpg" -o "$SETUP_WORK_DIR/docker.asc"
    [[ -s "$SETUP_WORK_DIR/docker.asc" ]] || fail '下载的 Docker 签名密钥为空。'
    install -m 0644 "$SETUP_WORK_DIR/docker.asc" "$SETUP_KEYRING_DIR/wargame-docker.asc"
    cat > "$SETUP_WORK_DIR/docker.sources" <<EOF
Types: deb
URIs: https://download.docker.com/linux/$SETUP_DISTRO
Suites: $SETUP_SUITE
Components: stable
Architectures: $SETUP_ARCH
Signed-By: $SETUP_KEYRING_DIR/wargame-docker.asc
EOF
    install -m 0644 "$SETUP_WORK_DIR/docker.sources" "$SETUP_SOURCE_DIR/wargame-docker.sources"
    apt-get update
    DEBIAN_FRONTEND=noninteractive apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  else
    log '已有 Docker CLI 与 Compose V2，保留原安装，不替换 Docker 软件包。'
  fi
  # enable --now 不重启已运行的服务，避免打断已有容器。
  systemctl enable --now docker
}

configure_swap() {
  [[ "$SETUP_SWAP_NEEDED" == true ]] || return 0
  log "创建 ${SETUP_SWAP_GB}GB Swap，期间可能需要等待磁盘写入。"
  # noclobber 防止检查后出现同名文件时覆盖；dd 确保文件实际分配而非稀疏文件。
  (umask 077; set -o noclobber; : > "$SETUP_SWAPFILE")
  chmod 600 "$SETUP_SWAPFILE"
  if ! dd if=/dev/zero of="$SETUP_SWAPFILE" bs=1M count="$((SETUP_SWAP_GB * 1024))" conv=fsync status=progress; then
    rm -f -- "$SETUP_SWAPFILE"
    fail 'Swap 文件写入失败，已清理此次新建文件。'
  fi
  if ! mkswap "$SETUP_SWAPFILE" || ! swapon "$SETUP_SWAPFILE"; then
    rm -f -- "$SETUP_SWAPFILE"
    fail 'Swap 初始化/启用失败，已清理此次新建文件。'
  fi
  # 先保存原 fstab；若持久化失败，保留已启用 Swap，避免自动 swapoff 造成 OOM。
  local backup
  backup="${SETUP_FSTAB}.wargame-backup.$(date -u +%Y%m%dT%H%M%S).$$"
  cp -p -- "$SETUP_FSTAB" "$backup"
  printf '\n%s none swap sw 0 0\n' "$SETUP_SWAPFILE" >> "$SETUP_FSTAB"
  log "Swap 已启用并配置开机加载；原 fstab 备份：$backup"
}

verify_runtime() {
  local required
  for required in python3 git nano curl docker; do
    command -v "$required" >/dev/null 2>&1 || fail "缺少命令：$required"
  done
  python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 9) else 1)' || fail 'Python 版本需要 3.9+。'
  docker info >/dev/null || fail 'Docker 守护进程不可访问，请检查 docker 服务日志。'
  docker compose version || fail 'Docker Compose V2 不可用。'
  python3 --version
  git --version
  free -h
  df -h /
  swapon --show
}

cleanup_setup() {
  [[ -z "$SETUP_WORK_DIR" ]] || rm -rf -- "$SETUP_WORK_DIR"
}

main() {
  local check=false
  while (( $# > 0 )); do
    case "$1" in
      --help|-h) usage; return 0 ;;
      --check) check=true; shift ;;
      --swap-gb)
        [[ $# -ge 2 && "$2" =~ ^([1-9]|1[0-6])$ ]] || fail '--swap-gb 需要 1~16 的整数。'
        SETUP_SWAP_GB="$2"; shift 2 ;;
      *) fail "未知参数：$1；使用 --help 查看说明。" ;;
    esac
  done
  [[ "$check" != true || "$SETUP_SWAP_GB" == 0 ]] || fail '--check 与 --swap-gb 不能同时使用。'
  [[ "$EUID" == 0 ]] || fail '请使用 sudo bash setup-server.sh 执行。'
  detect_platform
  if [[ "$check" == true ]]; then
    verify_runtime
    log '环境检查通过，未修改系统。'
    return 0
  fi
  command -v flock >/dev/null 2>&1 || fail '系统缺少 flock，请先安装 util-linux。'
  exec 9>/run/lock/wargame-server-setup.lock
  flock -n 9 || fail '已有环境配置进程正在运行。'
  SETUP_WORK_DIR="$(mktemp -d /tmp/wargame-server-setup.XXXXXX)"
  trap cleanup_setup EXIT
  trap 'printf "[环境配置失败] 执行中断（行 %s），请检查上方错误，修复后可重试。\n" "$LINENO" >&2' ERR
  plan_swap
  install_runtime
  configure_swap
  verify_runtime
  log '环境配置完成。上传项目后，在项目根目录运行 sudo ./deploy.sh --init-env。'
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
