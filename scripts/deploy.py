#!/usr/bin/env python3
"""部署单个游戏大区；原凭证不可自动轮换，只有完整验收通过才报告成功。"""
import argparse
import base64
import fcntl
import gzip
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timezone


ROOT = Path(__file__).resolve().parent.parent
SECRET_KEYS = ("WARGAME_DB_PASSWORD", "WARGAME_JWT_SECRET",
               "WARGAME_EMAIL_DATA_KEY", "WARGAME_EMAIL_HMAC_KEY")
MAIL_IMPORT_FILE = ".deploy-mail.env"
MAIL_KEYS = ("WARGAME_EMAIL_ENABLED", "WARGAME_MAIL_HOST", "WARGAME_MAIL_PORT",
             "WARGAME_MAIL_SSL", "WARGAME_MAIL_USERNAME", "WARGAME_MAIL_PASSWORD", "WARGAME_MAIL_FROM")
MAIL_REQUIRED = ("WARGAME_MAIL_USERNAME", "WARGAME_MAIL_PASSWORD", "WARGAME_MAIL_FROM")


class DeploymentError(Exception):
    """对用户可见的错误仅描述检查项，不带命令输出中的凭证。"""


def read_env(path):
    """读取 Compose dotenv 的单行子集；拒绝歧义、重复键和隐式变量插值。"""
    if not path.exists():
        return {}
    if path.is_symlink() or not path.is_file():
        raise DeploymentError(".env 必须是普通文件，不能是符号链接。")
    values = {}
    for number, line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        match = re.fullmatch(r"(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)", stripped)
        if not match or match[1] in values:
            raise DeploymentError(f".env 第 {number} 行无效或键重复；请使用单行 KEY=value。")
        key, raw = match.groups()
        if raw.startswith("'"):
            quoted = re.fullmatch(r"'((?:\\'|[^'])*)'\s*(?:#.*)?", raw)
            if not quoted:
                raise DeploymentError(f".env 第 {number} 行单引号未闭合；不支持多行值。")
            value = quoted[1].replace("\\'", "'")
        elif raw.startswith('"'):
            try:
                value, end = json.JSONDecoder().raw_decode(raw)
                if not isinstance(value, str) or raw[end:].strip() and not raw[end:].strip().startswith("#"):
                    raise ValueError()
            except ValueError:
                raise DeploymentError(f".env 第 {number} 行双引号格式无效。") from None
            if "$" in value:
                raise DeploymentError(f".env 第 {number} 行含变量插值；含 $ 的值请改用单引号。")
        else:
            value = re.split(r"\s+#", raw, maxsplit=1)[0].strip()
            if "$" in value:
                raise DeploymentError(f".env 第 {number} 行含变量插值；含 $ 的值请改用单引号。")
        if "\n" in value or "\r" in value or "\x00" in value:
            raise DeploymentError(f".env 第 {number} 行不能包含换行或空字符。")
        values[key] = value
    return values


def env_text(values):
    return "".join(key + "='" + value.replace("'", "\\'") + "'\n" for key, value in values.items())


def private_write(path, content):
    """先写私有临时文件再原子替换，避免中断留下半份凭证。"""
    fd, name = tempfile.mkstemp(prefix=".deploy-write-", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(content)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def validate_mail(values):
    """邮箱导入文件只允许 SMTP 设置，不能携带本机数据库或加密密钥。"""
    if set(values) != set(MAIL_KEYS) or not all(values.get(key, "").strip() for key in MAIL_KEYS):
        raise DeploymentError("邮箱导入文件需完整包含七项 SMTP 配置，且不能包含数据库或其他配置。")
    if values["WARGAME_EMAIL_ENABLED"] != "true" or values["WARGAME_MAIL_SSL"] not in ("true", "false"):
        raise DeploymentError("邮箱导入文件的 enabled/ssl 必须是有效布尔值，enabled 应为 true。")
    if not values["WARGAME_MAIL_PORT"].isdigit() or not 1 <= int(values["WARGAME_MAIL_PORT"]) <= 65535:
        raise DeploymentError("邮箱导入文件的 SMTP 端口无效。")
    for key in ("WARGAME_MAIL_USERNAME", "WARGAME_MAIL_FROM"):
        if not re.fullmatch(r"[^\s<>@]+@[^\s<>@]+\.[^\s<>@]+", values[key]):
            raise DeploymentError("邮箱导入文件中的账号或发件地址无效。")


def import_mail(root, source, existing_env):
    """仅在邮箱配置尚未完整时导入；已有完整配置保持原样，冲突时停止。"""
    if all(source.get(key, "").strip() for key in MAIL_REQUIRED):
        return source, []
    path = root / MAIL_IMPORT_FILE
    if not path.exists() and not path.is_symlink():
        return source, []
    if path.is_symlink() or not path.is_file() or path.stat().st_mode & 0o077:
        raise DeploymentError(".deploy-mail.env 必须是权限 600 的普通文件，请先检查并执行 chmod 600 .deploy-mail.env。")
    incoming = read_env(path)
    validate_mail(incoming)
    # 若已有账号/邮件服务器与导入文件不同，不自动拼接两套配置。
    if existing_env and any(source.get(key) and source[key] != incoming[key] for key in MAIL_KEYS):
        raise DeploymentError("现有邮箱配置与导入文件冲突；请人工核对 .env，脚本不会覆盖原设置。")
    result = dict(source)
    changed = [key for key in MAIL_KEYS if result.get(key) != incoming[key]]
    result.update(incoming)
    return result, changed


def weak(key, value):
    if key == "WARGAME_DB_PASSWORD":
        return len(value) < 12 or value.lower() in {"please-change-me", "password123456", "admin123456"}
    if key == "WARGAME_JWT_SECRET":
        return len(value.encode()) < 32 or any(word in value.lower() for word in ("replace-me", "dev-only", "change-this"))
    if not value:
        return True
    try:
        return len(base64.b64decode(value, validate=True)) != 32
    except ValueError:
        return True


def candidate_env(original):
    """新实例可补全凭证；是否允许落盘必须等持久卷检查后决定。"""
    result = dict(original)
    changed = []
    for key in SECRET_KEYS:
        if weak(key, result.get(key, "")):
            changed.append(key)
            result[key] = secrets.token_hex(24) if key == "WARGAME_DB_PASSWORD" else base64.b64encode(secrets.token_bytes(48 if key == "WARGAME_JWT_SECRET" else 32)).decode()
    return result, changed


def escape_compose(value):
    """config 已展开变量，重新交给 Compose 前转义 $，避免凭证被二次插值。"""
    if isinstance(value, str):
        return value.replace("$", "$$")
    if isinstance(value, list):
        return [escape_compose(item) for item in value]
    if isinstance(value, dict):
        return {key: escape_compose(item) for key, item in value.items()}
    return value


class Deployment:
    def __init__(self, root, args):
        self.root, self.args = root, args
        self.project = args.project_name or re.sub(r"^[^a-z0-9]+", "", re.sub(r"[^a-z0-9_-]", "", root.name.lower()))
        if not re.fullmatch(r"[a-z0-9][a-z0-9_-]*", self.project):
            raise DeploymentError("项目名必须为小写字母、数字、连字符或下划线，且以字母或数字开头。")
        self.command = []
        self.config = {}
        self.values = {}

    def run(self, command, label, timeout=120, stdout=subprocess.PIPE):
        try:
            # 只有不带凭证的镜像构建允许实时输出；配置/启动错误输出可能包含秘密。
            result = subprocess.run(command, cwd=self.root, stdout=stdout, stderr=None if stdout is None else subprocess.PIPE, timeout=timeout)
        except FileNotFoundError:
            raise DeploymentError(f"{label}失败：未找到所需命令，请先安装 Docker Engine 与 Compose V2。") from None
        except (OSError, subprocess.TimeoutExpired):
            raise DeploymentError(f"{label}失败或超时；请确认 Docker 已启动且当前用户有权限。") from None
        if result.returncode:
            # Compose 的报错可能展开密码，不能把 stderr 原样打印到终端或 CI。
            raise DeploymentError(f"{label}失败（退出码 {result.returncode}）；未继续部署。")
        return result.stdout.decode("utf-8") if stdout == subprocess.PIPE else ""

    def compose(self, *arguments, label="Compose 操作", timeout=120, stdout=subprocess.PIPE):
        return self.run([*self.command, *arguments], label, timeout, stdout)

    def inventory(self, *arguments):
        return [line.strip() for line in self.run(["docker", *arguments], "Docker 状态查询").splitlines() if line.strip()]

    def prepare(self, temporary):
        """固定配置输入并检查真实持久卷，再允许首次生成 .env。"""
        if any(key.startswith(("WARGAME_", "COMPOSE_")) or key == "MYSQL_DATABASE" for key in os.environ):
            raise DeploymentError("检测到 WARGAME_* / COMPOSE_* / MYSQL_DATABASE 环境变量。请取消导出并写入 .env，项目名使用 --project-name，避免覆盖文件配置。")
        for filename in ("docker-compose.yml", ".env.example", "backend/Dockerfile", "nginx.conf", "frontend/index.html"):
            if not (self.root / filename).is_file():
                raise DeploymentError(f"缺少部署文件：{filename}")
        self.run(["docker", "info"], "Docker 运行时检查")
        self.run(["docker", "compose", "version"], "Docker Compose V2 检查")
        path = self.root / ".env"
        original = read_env(path)
        if self.args.check and path.exists() and path.stat().st_mode & 0o077:
            raise DeploymentError(".env 对其他用户可读写，请先执行 chmod 600 .env。")
        source = original if path.exists() else read_env(self.root / ".env.example")
        source, mail_changed = import_mail(self.root, source, path.exists())
        if any(key.startswith("COMPOSE_") for key in source):
            raise DeploymentError(".env 不能定义 COMPOSE_*；请使用脚本参数指定项目名，部署配置固定由脚本选择。")
        values, changed = candidate_env(source)
        if values["WARGAME_EMAIL_DATA_KEY"] == values["WARGAME_EMAIL_HMAC_KEY"]:
            raise DeploymentError("邮箱加密密钥与 HMAC 密钥必须分别独立生成。已有数据请恢复原密钥，不能直接替换。")
        candidate = temporary / "candidate.env"
        private_write(candidate, env_text(values))
        base = ["docker", "compose", "--project-directory", str(self.root), "--project-name", self.project, "--env-file", str(candidate)]
        self.command = [*base, "-f", str(self.root / "docker-compose.yml")]
        try:
            config = json.loads(self.compose("config", "--format", "json", label="Compose 配置解析"))
        except (ValueError, TypeError):
            raise DeploymentError("Compose 配置解析没有返回有效 JSON。") from None
        services = config.get("services", {})
        if not all(name in services for name in ("db", "backend", "nginx")):
            raise DeploymentError("Compose 必须定义 db、backend、nginx 服务。")
        mounts = [mount for mount in services["db"].get("volumes", []) if mount.get("target") == "/var/lib/mysql"]
        if len(mounts) != 1 or mounts[0].get("type") != "volume":
            raise DeploymentError("MySQL /var/lib/mysql 必须挂载单个命名卷。")
        volume_key = mounts[0]["source"]
        volume_name = config.get("volumes", {}).get(volume_key, {}).get("name")
        if not volume_name:
            raise DeploymentError("无法确认 MySQL 持久卷名称。")
        volumes = self.inventory("volume", "ls", "--format", "{{.Name}}")
        containers = self.inventory("ps", "-a", "--filter", f"label=com.docker.compose.project={self.project}", "--format", "{{.ID}}")
        self.existing = volume_name in volumes or bool(containers)
        related = self.inventory("volume", "ls", "--filter", f"label=com.docker.compose.volume={volume_key}", "--format", "{{.Name}}")
        if not self.existing and related and not self.args.new_instance:
            raise DeploymentError("发现其他项目的游戏数据卷。请用 --project-name 指定原项目；确需新开独立大区时使用 --new-instance。")
        if self.existing and (not path.exists() or changed):
            raise DeploymentError("检测到已有数据库/容器，但 .env 缺失或凭证不完整、不合规。请恢复原配置；数据库密码必须在 MySQL 内人工轮换，禁止自动生成替代原密钥。")
        if self.args.check and (not path.exists() or changed or mail_changed):
            raise DeploymentError("首次部署尚未准备凭证，请先运行 ./deploy.sh --init-env。")
        self.values = values
        # 仅收敛本次部署的有效配置，不用正则改写用户的 Compose 源文件。
        for name in ("db", "backend"):
            service = services[name]
            if service.get("network_mode") or service.get("privileged"):
                raise DeploymentError(f"{name} 禁止使用 network_mode 或 privileged 绕过网络隔离。")
            service.pop("ports", None)
        db_env = services["db"].get("environment", {})
        backend_env = services["backend"].get("environment", {})
        if db_env.get("MYSQL_ROOT_PASSWORD") != values["WARGAME_DB_PASSWORD"] or backend_env.get("WARGAME_DB_PASSWORD") != values["WARGAME_DB_PASSWORD"]:
            raise DeploymentError("Compose 中数据库与后端密码没有使用同一份 .env 凭证。")
        if backend_env.get("WARGAME_DB_USER") != "root" or backend_env.get("SPRING_PROFILES_ACTIVE") != "prod":
            raise DeploymentError("当前部署要求 backend 使用 prod profile 与 root 数据库账号。")
        database = db_env.get("MYSQL_DATABASE", "")
        if not backend_env.get("WARGAME_DB_URL", "").startswith(f"jdbc:mysql://db:3306/{database}?"):
            raise DeploymentError("后端必须通过 db:3306 连接同一个 MySQL 数据库。")
        for key, value in values.items():
            if key.startswith("WARGAME_") and key in backend_env and key not in ("WARGAME_DB_USER", "WARGAME_DB_URL") and backend_env[key] != value:
                raise DeploymentError(f"Compose 解析后的 {key} 与 .env 不一致。")
        for key in SECRET_KEYS[1:]:
            if backend_env.get(key) != values[key]:
                raise DeploymentError(f"Compose 未向后端传入正确的 {key}。")
        if not re.fullmatch(r"[A-Za-z0-9_-]+", db_env.get("MYSQL_DATABASE", "")):
            raise DeploymentError("MYSQL_DATABASE 只能包含字母、数字、下划线和连字符。")
        self.config = config
        compiled = temporary / "compose.json"
        private_write(compiled, json.dumps(escape_compose(config), ensure_ascii=False))
        self.command = [*base, "-f", str(compiled)]
        resolved = json.loads(self.compose("config", "--format", "json", label="最终部署配置复核"))
        if resolved != config:
            raise DeploymentError("Compose 配置再次解析后发生变化，已停止部署。")
        if not self.args.check:
            if changed or mail_changed or not path.exists():
                text = path.read_text(encoding="utf-8-sig") if path.exists() else (self.root / ".env.example").read_text(encoding="utf-8")
                for key in changed + mail_changed:
                    pattern = re.compile(r"^(?:export\s+)?" + re.escape(key) + r"\s*=.*$", re.MULTILINE)
                    replacement = env_text({key: values[key]}).rstrip("\n")
                    if pattern.search(text):
                        text = pattern.sub(lambda match: replacement, text)
                    else:
                        text = text.rstrip("\n") + "\n" + replacement + "\n"
                private_write(path, text)
                if mail_changed:
                    print("已自动带入邮箱配置；数据库密码与加密密钥保持独立，不输出授权码。", flush=True)
            path.chmod(0o600)
            # 私有凭证使用 umask=077，但 Nginx worker 仍需遍历静态头像目录。
            for relative in ("frontend/img", "frontend/img/avatars", "frontend/img/avatars/custom"):
                directory = self.root / relative
                if directory.is_symlink():
                    raise DeploymentError("头像持久化目录不能是符号链接。")
                if not directory.exists():
                    directory.mkdir(mode=0o755)
                    directory.chmod(0o755)
            avatar_directory = self.root / "frontend/img/avatars/custom"
            avatar_directory.chmod(0o755)
            if not os.access(avatar_directory, os.W_OK):
                raise DeploymentError("头像持久化目录不可写。")
        print(f"配置检查通过：项目 {self.project}，{'已有数据升级' if self.existing else '首次部署'}；MySQL 与后端不发布宿主机端口。", flush=True)
        mail_keys = ("WARGAME_MAIL_USERNAME", "WARGAME_MAIL_PASSWORD", "WARGAME_MAIL_FROM")
        if values.get("WARGAME_EMAIL_ENABLED", "true").lower() != "true" or not all(values.get(key) for key in mail_keys):
            print("提示：发信配置不完整或邮箱功能关闭，新用户注册与邮箱找回暂不可用。", flush=True)

    def state(self, service):
        ids = [line.strip() for line in self.compose("ps", "-a", "-q", service, label=f"{service} 容器查询").splitlines() if line.strip()]
        if len(ids) != 1:
            raise DeploymentError(f"{service} 必须恰好运行一个容器。")
        try:
            return json.loads(self.run(["docker", "inspect", ids[0]], f"{service} 容器状态检查"))[0]
        except (ValueError, IndexError, TypeError):
            raise DeploymentError(f"{service} 状态数据无效。") from None

    def wait_database(self):
        deadline = time.monotonic() + self.args.timeout
        while time.monotonic() < deadline:
            state = self.state("db")["State"]
            health = state.get("Health", {}).get("Status")
            if state.get("Running") and health == "healthy":
                # mysqladmin ping 成功不等于密码正确，必须实际执行 SQL。
                try:
                    output = self.compose("exec", "-T", "db", "sh", "-c", 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -h127.0.0.1 -uroot --database="$MYSQL_DATABASE" --batch --skip-column-names -e "SELECT 1"', label="数据库密码认证")
                except DeploymentError:
                    # 官方镜像的临时初始化进程只开放 socket；等待正式 TCP 服务。
                    time.sleep(2)
                    continue
                if output.strip() != "1":
                    raise DeploymentError("数据库认证查询返回值异常。")
                return
            if health == "unhealthy" or state.get("Status") in ("exited", "dead"):
                raise DeploymentError("MySQL 未通过健康检查，已停止后端升级。")
            time.sleep(2)
        raise DeploymentError("等待 MySQL 就绪/密码认证超时，已停止后端升级。请核对原数据库密码。")

    def backup(self):
        """迁移前保存一致性 SQL、原凭证和头像；备份失败不能继续升级。"""
        folder = self.root / "backups/deploy" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
        folder.mkdir(parents=True, mode=0o700)
        folder.chmod(0o700)
        raw = folder / "database.sql.partial"
        try:
            with raw.open("xb") as handle:
                raw.chmod(0o600)
                self.compose("exec", "-T", "db", "sh", "-c", 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --routines --triggers --events --no-tablespaces --databases "$MYSQL_DATABASE"', label="数据库升级前备份", timeout=max(300, self.args.timeout), stdout=handle)
            if raw.stat().st_size == 0:
                raise DeploymentError("数据库备份为空。")
            with raw.open("rb") as source, gzip.open(folder / "database.sql.gz", "wb") as target:
                shutil.copyfileobj(source, target)
            shutil.copyfile(self.root / ".env", folder / ".env")
            (folder / ".env").chmod(0o600)
            shutil.copytree(self.root / "frontend/img/avatars/custom", folder / "avatars")
            private_write(folder / "COMPLETE", "数据库、.env 与头像备份完成；恢复时必须配套使用原密钥。\n")
        finally:
            raw.unlink(missing_ok=True)
        print(f"升级前备份完成：{folder.relative_to(self.root)}", flush=True)

    def verify(self):
        """验收实际容器绑定和网关 API；查询失败不能当作未暴露。"""
        deadline = time.monotonic() + self.args.timeout
        while time.monotonic() < deadline:
            states = {service: self.state(service) for service in ("db", "backend", "nginx")}
            for service in ("db", "backend"):
                if any(states[service].get("NetworkSettings", {}).get("Ports", {}).values()):
                    raise DeploymentError(f"{service} 实际发布了宿主机端口，部署验收失败。")
            if states["db"]["State"].get("Health", {}).get("Status") != "healthy":
                raise DeploymentError("部署期间 MySQL 失去健康状态。")
            if any(not item["State"].get("Running") for item in states.values()):
                raise DeploymentError("存在已退出的服务容器，部署验收失败。")
            try:
                payload = json.loads(self.compose("exec", "-T", "nginx", "wget", "-q", "-T", "5", "-O", "-", "http://127.0.0.1/api/auth/server", label="网关 API 验收", timeout=10))
            except (DeploymentError, ValueError):
                time.sleep(2)
                continue
            if not isinstance(payload, dict):
                raise DeploymentError("网关 API 返回的数据格式无效。")
            expected = self.config["services"]["backend"]["environment"].get("WARGAME_SERVER_ID", "jiangsu-1")
            if payload.get("id") != expected:
                raise DeploymentError("网关 API 返回的大区与配置不符。")
            self.compose("exec", "-T", "nginx", "wget", "-q", "-T", "5", "-O", "/dev/null", "http://127.0.0.1/", label="游戏首页验收", timeout=10)
            if not payload.get("accountSecurity", {}).get("mailAvailable", False):
                print("提示：服务可访问，但邮件能力未启用；请完善 SMTP 配置后重新部署。", flush=True)
            return
        raise DeploymentError("网关 API 验收超时；后端可能未完成启动，请检查 backend/nginx 日志。")

    def deploy(self):
        print("构建后端镜像；构建失败时保留当前运行容器。", flush=True)
        self.compose("build", "backend", label="后端镜像构建", timeout=3600, stdout=None)
        self.compose("up", "-d", "db", label="MySQL 启动")
        self.wait_database()
        if self.existing:
            self.backup()
        self.compose("up", "-d", "--no-deps", "backend", label="后端部署")
        # Nginx 静态解析 upstream，后端容器换 IP 后必须重新解析。
        self.compose("up", "-d", "--no-deps", "--force-recreate", "nginx", label="Nginx 网关更新")
        self.verify()
        print("部署成功：数据库认证、容器端口隔离、网关 API 与游戏首页均已通过验收。", flush=True)
        print(f"查看日志：docker compose -p {self.project} -f docker-compose.yml logs --tail=100 backend nginx", flush=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description="山河远征录一键部署（Python 3.9+、Docker Compose V2）。升级期间会短暂停服，不自动回滚数据库。")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true", help="只检查配置与数据卷，不修改 .env 或启动服务")
    mode.add_argument("--init-env", action="store_true", help="仅准备首次部署凭证与头像目录，不启动服务")
    parser.add_argument("--project-name", help="原 Compose 项目名；已有数据库升级时必须保持一致")
    parser.add_argument("--new-instance", action="store_true", help="明确允许在已有其他大区时创建独立新数据卷")
    parser.add_argument("--timeout", type=int, default=300, help="数据库及 API 分别等待的秒数（默认 300）")
    args = parser.parse_args(argv)
    if args.timeout < 1:
        parser.error("--timeout 必须大于 0")
    if sys.version_info < (3, 9):
        print("部署失败：需要 Python 3.9+。", file=sys.stderr)
        return 1
    try:
        # 保护新建备份与私有配置；即使宿主机默认 umask=022 也不能放宽。
        os.umask(0o077)
        # 同一目录只允许一个部署进程，避免并发生成不同凭证或同时迁移。
        with (ROOT / ".deploy.lock").open("a") as lock:
            os.chmod(ROOT / ".deploy.lock", 0o600)
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise DeploymentError("已有部署/预检进程正在运行。") from None
            with tempfile.TemporaryDirectory(prefix="wargame-deploy-") as directory:
                deployment = Deployment(ROOT, args)
                deployment.prepare(Path(directory))
                if args.check:
                    print("预检通过；未启动服务。")
                elif args.init_env:
                    if all(deployment.values.get(key, "").strip() for key in MAIL_REQUIRED):
                        print("凭证与邮箱配置已准备，.env 权限为 600；运行 ./deploy.sh 即可部署。")
                    else:
                        print("凭证已准备并设为 600 权限；请完善 .env 中 SMTP 配置，再运行 ./deploy.sh。")
                else:
                    deployment.deploy()
        return 0
    except (DeploymentError, OSError, ValueError, KeyError, TypeError) as error:
        # 系统异常仅打印类型，避免异常消息带入文件内容或敏感参数。
        detail = str(error) if isinstance(error, DeploymentError) else type(error).__name__
        print(f"部署失败：{detail}", file=sys.stderr)
        print("未删除任何数据卷，未自动回滚数据库；请排查后重试。", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("部署已中断；未删除数据卷，正在运行的容器保持当前状态。", file=sys.stderr)
        return 130


if __name__ == "__main__":
    sys.exit(main())
