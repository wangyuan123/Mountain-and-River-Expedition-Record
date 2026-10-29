#!/usr/bin/env python3
"""将本机 SMTP 白名单配置导出为私有包；不导出数据库密码或任何加密密钥。"""
import argparse
import io
import os
from pathlib import Path
import sys
import tarfile
import tempfile

from deploy import DeploymentError, MAIL_IMPORT_FILE, MAIL_KEYS, ROOT, env_text, read_env, validate_mail


def export_mail(root, destination):
    """输出只含 .deploy-mail.env 的 600 权限压缩包，已有文件用原子替换更新。"""
    source = read_env(root / ".env")
    values = {key: source.get(key, "") for key in MAIL_KEYS}
    validate_mail(values)
    destination = Path(destination).absolute()
    if destination.is_symlink() or destination.name in (".env", ".env.example", MAIL_IMPORT_FILE):
        raise DeploymentError("输出必须是独立的私有 .tar.gz 文件，不能覆盖配置文件或符号链接。")
    if not destination.name.endswith(".tar.gz"):
        raise DeploymentError("邮箱配置包文件名必须以 .tar.gz 结尾。")
    destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd, temporary = tempfile.mkstemp(prefix=".mail-export-", dir=destination.parent)
    try:
        with os.fdopen(fd, "wb") as output:
            with tarfile.open(fileobj=output, mode="w:gz") as archive:
                payload = env_text(values).encode("utf-8")
                member = tarfile.TarInfo(MAIL_IMPORT_FILE)
                member.mode = 0o600
                member.size = len(payload)
                archive.addfile(member, io.BytesIO(payload))
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, destination)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    return destination


def main(argv=None):
    parser = argparse.ArgumentParser(description="本机运行：从 .env 导出服务器可自动导入的私有邮箱配置包。")
    parser.add_argument("--output", type=Path, default=ROOT / ".private-deploy/mail-config.tar.gz", help="输出压缩包路径")
    args = parser.parse_args(argv)
    try:
        os.umask(0o077)
        output = export_mail(ROOT, args.output)
        print(f"私有邮箱配置包已生成：{output}")
        print("仅含 SMTP 配置，不含本机数据库密码或加密密钥。上传后解压到服务器项目根目录，运行 sudo ./deploy.sh 即可自动带入。")
        return 0
    except (DeploymentError, OSError, ValueError) as error:
        detail = str(error) if isinstance(error, DeploymentError) else type(error).__name__
        print(f"导出失败：{detail}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
