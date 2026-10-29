"""通过假的 Docker CLI 验证部署边界，不依赖 Docker、不访问真实数据库。"""
import argparse
import base64
import contextlib
import fcntl
import gzip
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


SOURCE = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("game_deployment", SOURCE / "scripts/deploy.py")
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)

# 假 CLI 保留操作顺序，模拟 Compose 已解析的配置和 Docker inspect 的结构。
FAKE_DOCKER = r'''#!PYTHON
import json, os, sys
from pathlib import Path
root = Path.cwd()
args = sys.argv[1:]
scenario = json.loads((root / 'scenario.json').read_text())
with (root / 'calls.jsonl').open('a') as log:
    log.write(json.dumps(args) + '\n')
def output(value):
    print(json.dumps(value) if isinstance(value, (dict,list)) else value)
def stop():
    print('simulated failure with sensitive stderr', file=sys.stderr)
    sys.exit(2)
def unescape(value):
    if isinstance(value,str): return value.replace('$$','$')
    if isinstance(value,list): return [unescape(item) for item in value]
    if isinstance(value,dict): return {key:unescape(item) for key,item in value.items()}
    return value
if args == ['info']: sys.exit(0)
if args == ['compose','version']:
    if scenario.get('no_compose'): stop()
    output('Docker Compose version v2.mock'); sys.exit(0)
if args[:2] == ['volume','ls']:
    if scenario.get('inventory_failure'): stop()
    output('\n'.join(scenario.get('volumes',[]))); sys.exit(0)
if args[:2] == ['ps','-a']:
    output('old-db' if scenario.get('containers') else ''); sys.exit(0)
if args[0] == 'inspect':
    service = args[1]
    if scenario.get('inspect_failure'): stop()
    health = scenario.get('health','healthy')
    state = {'Running':True,'Status':'running'}
    if service == 'db': state['Health'] = {'Status':health}
    ports = {'3306/tcp':None} if service == 'db' else {}
    if scenario.get('exposed') and service == 'db': ports['3306/tcp']=[{'HostIp':'0.0.0.0','HostPort':'3306'}]
    output([{'State':state,'NetworkSettings':{'Ports':ports}}]); sys.exit(0)
if args[0] != 'compose': stop()
project = args[args.index('--project-name')+1]
file = Path(args[args.index('-f')+1])
rest = args[args.index('-f')+2:]
if rest == ['config','--format','json']:
    if file.suffix == '.json':
        output(unescape(json.loads(file.read_text()))); sys.exit(0)
    envfile = Path(args[args.index('--env-file')+1])
    values = {}
    for line in envfile.read_text().splitlines():
        key,value=line.split('=',1)
        values[key]=value[1:-1].replace("\\'", "'")
    db = values.get('MYSQL_DATABASE','wargame')
    backend = dict(values)
    backend.update({'WARGAME_DB_USER':'root','WARGAME_DB_URL':'jdbc:mysql://db:3306/'+db+'?useSSL=false','SPRING_PROFILES_ACTIVE':'prod'})
    config = {'name':project,'services':{
      'db':{'environment':{'MYSQL_ROOT_PASSWORD':values['WARGAME_DB_PASSWORD'],'MYSQL_DATABASE':db},'volumes':[{'type':'volume','source':'mysqldata','target':'/var/lib/mysql'}],'ports':[{'target':3306,'published':'3306'}]},
      'backend':{'environment':backend,'ports':[{'target':8080,'published':'8080'}]},
      'nginx':{'ports':[{'target':80,'published':'80'}]}},'volumes':{'mysqldata':{'name':project+'_mysqldata'}}}
    output(config); sys.exit(0)
if rest == ['build','backend']:
    if scenario.get('build_failure'): stop()
    sys.exit(0)
if rest[0] == 'up': sys.exit(0)
if rest[:3] == ['ps','-a','-q']: output(rest[3]); sys.exit(0)
if rest[:3] == ['exec','-T','db']:
    if 'mysqldump' in rest[-1]:
        if scenario.get('backup_failure'): stop()
        output('-- valid SQL dump\nCREATE DATABASE wargame;'); sys.exit(0)
    if scenario.get('auth_failure'): stop()
    output('1'); sys.exit(0)
if rest[:3] == ['exec','-T','nginx']:
    if rest[-1].endswith('/api/auth/server'):
        if scenario.get('api_failure'): stop()
        output({'id':scenario.get('server_id','jiangsu-1'),'accountSecurity':{'mailAvailable':True}}); sys.exit(0)
    if scenario.get('frontend_failure'): stop()
    sys.exit(0)
stop()
'''


class DeploymentTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="wargame-deploy-test-")
        self.root = Path(self.temporary.name) / "game"
        self.root.mkdir()
        for name in ("backend", "frontend", "bin"):
            (self.root / name).mkdir()
        for name in ("docker-compose.yml", "nginx.conf", "backend/Dockerfile", "frontend/index.html"):
            (self.root / name).write_text("fixture\n")
        shutil.copyfile(SOURCE / ".env.example", self.root / ".env.example")
        fake = self.root / "bin/docker"
        fake.write_text(FAKE_DOCKER.replace("#!PYTHON", "#!" + sys.executable))
        fake.chmod(0o755)
        self.scenario = {}
        clean = {key:value for key,value in os.environ.items() if not key.startswith(("WARGAME_", "COMPOSE_")) and key != "MYSQL_DATABASE"}
        clean["PATH"] = str(self.root / "bin") + os.pathsep + os.environ["PATH"]
        self.environment = patch.dict(os.environ, clean, clear=True)
        self.environment.start()
        self.args = argparse.Namespace(project_name="game", check=False, init_env=False, new_instance=False, timeout=1)

    def tearDown(self):
        self.environment.stop()
        self.temporary.cleanup()

    def valid_env(self, **updates):
        values = {
            "WARGAME_DB_PASSWORD":"existing-strong-password",
            "WARGAME_JWT_SECRET":"existing-signing-key-" + "x"*40,
            "WARGAME_EMAIL_DATA_KEY":base64.b64encode(b"d"*32).decode(),
            "WARGAME_EMAIL_HMAC_KEY":base64.b64encode(b"h"*32).decode(),
            "WARGAME_SERVER_ID":"jiangsu-1",
        }
        values.update(updates)
        (self.root / ".env").write_text(deploy.env_text(values))
        return (self.root / ".env").read_bytes()

    def execute(self, perform=True):
        (self.root / "scenario.json").write_text(json.dumps(self.scenario))
        runner = deploy.Deployment(self.root, self.args)
        with tempfile.TemporaryDirectory(prefix="wargame-config-test-") as temporary, contextlib.redirect_stdout(io.StringIO()):
            runner.prepare(Path(temporary))
            if perform:
                runner.deploy()
        return runner

    def calls(self):
        return [json.loads(line) for line in (self.root / "calls.jsonl").read_text().splitlines()]

    def has_up(self, service):
        return any("up" in call and call[-1] == service for call in self.calls())

    def test_fresh_deploy_generates_private_credentials_and_gateway(self):
        self.execute()
        values = deploy.read_env(self.root / ".env")
        self.assertTrue(all(not deploy.weak(key, values[key]) for key in deploy.SECRET_KEYS))
        self.assertEqual(0o600, stat.S_IMODE((self.root / ".env").stat().st_mode))
        self.assertEqual(0o755, stat.S_IMODE((self.root / "frontend/img/avatars/custom").stat().st_mode))
        self.assertTrue(self.has_up("backend"))
        self.assertTrue(any("--force-recreate" in call and call[-1] == "nginx" for call in self.calls()))
        self.assertFalse((self.root / "backups").exists())

    def test_upgrade_preserves_credentials_and_backs_up_before_backend(self):
        original = self.valid_env()
        self.scenario["volumes"] = ["game_mysqldata"]
        self.execute()
        self.assertEqual(original, (self.root / ".env").read_bytes())
        folder = next((self.root / "backups/deploy").iterdir())
        self.assertTrue((folder / "COMPLETE").exists())
        with gzip.open(folder / "database.sql.gz", "rt") as handle:
            self.assertIn("CREATE DATABASE", handle.read())
        self.assertEqual(original, (folder / ".env").read_bytes())
        calls = self.calls()
        dump = next(i for i,call in enumerate(calls) if any("mysqldump" in item for item in call))
        backend = next(i for i,call in enumerate(calls) if "up" in call and call[-1] == "backend")
        self.assertLess(dump, backend)

    def test_existing_volume_without_env_fails_without_writing(self):
        self.scenario["volumes"] = ["game_mysqldata"]
        with self.assertRaisesRegex(deploy.DeploymentError, "已有数据库"):
            self.execute()
        self.assertFalse((self.root / ".env").exists())
        self.assertFalse(self.has_up("db"))

    def test_existing_weak_db_or_missing_encryption_key_never_rotates(self):
        for updates in ({"WARGAME_DB_PASSWORD":"root"}, {"WARGAME_EMAIL_DATA_KEY":""}):
            with self.subTest(updates=list(updates)):
                before = self.valid_env(**updates)
                self.scenario["volumes"] = ["game_mysqldata"]
                with self.assertRaises(deploy.DeploymentError):
                    self.execute()
                self.assertEqual(before, (self.root / ".env").read_bytes())

    def test_other_project_data_requires_explicit_new_instance(self):
        self.scenario["volumes"] = ["original_mysqldata"]
        with self.assertRaisesRegex(deploy.DeploymentError, "其他项目"):
            self.execute()
        self.assertFalse((self.root / ".env").exists())
        self.args.new_instance = True
        self.execute(perform=False)

    def test_check_is_read_only_and_init_does_not_start_containers(self):
        original = self.valid_env()
        (self.root / ".env").chmod(0o644)
        self.args.check = True
        with self.assertRaisesRegex(deploy.DeploymentError, "chmod 600"):
            self.execute(perform=False)
        self.assertEqual(original, (self.root / ".env").read_bytes())
        self.assertEqual(0o644, stat.S_IMODE((self.root / ".env").stat().st_mode))
        self.assertFalse(self.has_up("db"))
        self.assertFalse((self.root / "frontend/img").exists())
        (self.root / ".env").chmod(0o600)
        self.execute(perform=False)

    def test_init_env_cli_only_prepares_files(self):
        (self.root / "scenario.json").write_text("{}")
        with patch.object(deploy, "ROOT", self.root), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, deploy.main(["--init-env", "--project-name", "game"]))
        self.assertTrue((self.root / ".env").exists())
        self.assertFalse(self.has_up("db"))

    def test_missing_compose_cannot_modify_configuration(self):
        self.scenario["no_compose"] = True
        with self.assertRaisesRegex(deploy.DeploymentError, "Compose V2"):
            self.execute()
        self.assertFalse((self.root / ".env").exists())

    def test_main_returns_nonzero_without_false_success(self):
        (self.root / "scenario.json").write_text(json.dumps({"health":"unhealthy"}))
        output, errors = io.StringIO(), io.StringIO()
        with patch.object(deploy, "ROOT", self.root), contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
            self.assertEqual(1, deploy.main(["--project-name", "game"]))
        self.assertNotIn("部署成功", output.getvalue())
        self.assertIn("部署失败", errors.getvalue())

    def test_check_without_credentials_needs_init(self):
        self.args.check = True
        with self.assertRaisesRegex(deploy.DeploymentError, "init-env"):
            self.execute(perform=False)
        self.assertFalse((self.root / ".env").exists())

    def test_unhealthy_is_not_healthy(self):
        self.scenario["health"] = "unhealthy"
        with self.assertRaisesRegex(deploy.DeploymentError, "健康检查"):
            self.execute()
        self.assertFalse(self.has_up("backend"))

    def test_backup_failure_stops_before_backend_replacement(self):
        self.valid_env()
        self.scenario.update(volumes=["game_mysqldata"], backup_failure=True)
        with self.assertRaisesRegex(deploy.DeploymentError, "备份"):
            self.execute()
        self.assertFalse(self.has_up("backend"))
        self.assertFalse(list((self.root / "backups").rglob("COMPLETE")))

    def test_build_failure_does_not_restart_database(self):
        self.scenario["build_failure"] = True
        with self.assertRaisesRegex(deploy.DeploymentError, "镜像构建"):
            self.execute()
        self.assertFalse(self.has_up("db"))

    def test_authentication_failure_stops_before_backend(self):
        self.scenario["auth_failure"] = True
        with patch.object(deploy.time, "sleep"), self.assertRaisesRegex(deploy.DeploymentError, "认证超时"):
            self.execute()
        self.assertFalse(self.has_up("backend"))

    def test_api_timeout_never_reports_success(self):
        self.scenario["api_failure"] = True
        with patch.object(deploy.time, "sleep"), self.assertRaisesRegex(deploy.DeploymentError, "API 验收超时"):
            self.execute()

    def test_actual_exposed_port_rejects_deployment(self):
        self.scenario["exposed"] = True
        with self.assertRaisesRegex(deploy.DeploymentError, "实际发布"):
            self.execute()

    def test_inspect_and_volume_query_errors_are_not_success(self):
        for flag in ("inspect_failure", "inventory_failure"):
            with self.subTest(flag=flag):
                self.scenario = {flag:True}
                with self.assertRaises(deploy.DeploymentError):
                    self.execute()

    def test_wrong_region_and_frontend_failure_reject_deployment(self):
        for scenario in ({"server_id":"wrong-region"}, {"frontend_failure":True}):
            with self.subTest(scenario=scenario):
                self.scenario = scenario
                with self.assertRaises(deploy.DeploymentError):
                    self.execute()

    def test_exported_env_cannot_override_validated_credentials(self):
        with patch.dict(os.environ, {"WARGAME_DB_PASSWORD":"root"}), self.assertRaisesRegex(deploy.DeploymentError, "环境变量"):
            self.execute()

    def test_dotenv_special_characters_duplicates_and_interpolation(self):
        path = self.root / ".env"
        values = {"PASSWORD":"literal$secret#with space", "NAME":"游戏大区"}
        path.write_text(deploy.env_text(values))
        self.assertEqual(values, deploy.read_env(path))
        for invalid in ("KEY=one\nKEY=two\n", "KEY=${FROM_SHELL}\n", 'KEY="line\\nnext"\n'):
            path.write_text(invalid)
            with self.assertRaises(deploy.DeploymentError):
                deploy.read_env(path)

    def test_literal_dollar_survives_compose_roundtrip(self):
        self.valid_env(WARGAME_DB_PASSWORD="literal$existing-strong-password")
        runner = self.execute(perform=False)
        self.assertEqual("literal$existing-strong-password", runner.config["services"]["db"]["environment"]["MYSQL_ROOT_PASSWORD"])

    def test_concurrent_deployment_is_rejected(self):
        with (self.root / ".deploy.lock").open("a") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            with patch.object(deploy, "ROOT", self.root), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(1, deploy.main(["--check"]))
        self.assertFalse((self.root / "calls.jsonl").exists())

    def test_shell_entrypoint_and_legacy_preflight(self):
        (self.root / "scripts").mkdir()
        for name in ("deploy.sh", "scripts/deploy.py", "scripts/security-preflight.py"):
            shutil.copyfile(SOURCE / name, self.root / name)
        (self.root / "scenario.json").write_text("{}")
        result = subprocess.run(["/bin/bash", str(self.root / "deploy.sh"), "--init-env"], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        before = (self.root / ".env").read_bytes()
        result = subprocess.run([sys.executable, str(self.root / "scripts/security-preflight.py")], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(before, (self.root / ".env").read_bytes())
        self.assertFalse(self.has_up("db"))

    def test_shell_without_python_stops_instead_of_fallback(self):
        directory = self.root / "no-python"
        directory.mkdir()
        (directory / "dirname").symlink_to(shutil.which("dirname"))
        result = subprocess.run(["/bin/bash", str(SOURCE / "deploy.sh")], env={"PATH":str(directory)}, capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("需要 Python", result.stderr)

    def mail_values(self):
        return {
            "WARGAME_EMAIL_ENABLED":"true", "WARGAME_MAIL_HOST":"smtp.qq.com",
            "WARGAME_MAIL_PORT":"465", "WARGAME_MAIL_SSL":"true",
            "WARGAME_MAIL_USERNAME":"fixture@qq.com", "WARGAME_MAIL_PASSWORD":"fixture-mail-authorization",
            "WARGAME_MAIL_FROM":"fixture@qq.com",
        }

    def mail_seed(self, **updates):
        values = self.mail_values()
        values.update(updates)
        deploy.private_write(self.root / deploy.MAIL_IMPORT_FILE, deploy.env_text(values))

    def test_mail_auto_import_on_new_server_uses_independent_secrets(self):
        self.mail_seed()
        self.execute(perform=False)
        result = deploy.read_env(self.root / ".env")
        for key, value in self.mail_values().items():
            self.assertEqual(value, result[key])
        self.assertTrue(all(not deploy.weak(key, result[key]) for key in deploy.SECRET_KEYS))
        self.assertEqual(0o600, stat.S_IMODE((self.root / ".env").stat().st_mode))

    def test_mail_import_to_initialized_env_preserves_all_existing_secrets(self):
        self.valid_env()
        before = deploy.read_env(self.root / ".env")
        self.mail_seed()
        self.scenario["volumes"] = ["game_mysqldata"]
        self.execute(perform=False)
        result = deploy.read_env(self.root / ".env")
        self.assertEqual(self.mail_values()["WARGAME_MAIL_PASSWORD"], result["WARGAME_MAIL_PASSWORD"])
        for key in deploy.SECRET_KEYS:
            self.assertEqual(before[key], result[key])

    def test_mail_seed_does_not_replace_existing_complete_mail_configuration(self):
        before = self.valid_env(**self.mail_values())
        self.mail_seed(WARGAME_MAIL_PASSWORD="different-authorization")
        self.execute(perform=False)
        self.assertEqual(before, (self.root / ".env").read_bytes())

    def test_mail_partial_configuration_conflict_never_writes(self):
        before = self.valid_env(WARGAME_MAIL_USERNAME="another@qq.com")
        self.mail_seed()
        with self.assertRaisesRegex(deploy.DeploymentError, "冲突"):
            self.execute(perform=False)
        self.assertEqual(before, (self.root / ".env").read_bytes())

    def test_mail_seed_cannot_override_database_secrets(self):
        self.mail_seed(WARGAME_DB_PASSWORD="unexpected-database-password")
        with self.assertRaisesRegex(deploy.DeploymentError, "七项 SMTP"):
            self.execute(perform=False)
        self.assertFalse((self.root / ".env").exists())

    def test_mail_seed_requires_private_regular_file(self):
        self.mail_seed()
        path = self.root / deploy.MAIL_IMPORT_FILE
        path.chmod(0o644)
        with self.assertRaisesRegex(deploy.DeploymentError, "600"):
            self.execute(perform=False)
        path.unlink()
        path.symlink_to(self.root / ".env.example")
        with self.assertRaisesRegex(deploy.DeploymentError, "普通文件"):
            self.execute(perform=False)

    def test_mail_check_never_silently_persists_import(self):
        self.valid_env()
        (self.root / ".env").chmod(0o600)
        before = (self.root / ".env").read_bytes()
        self.mail_seed()
        self.args.check = True
        with self.assertRaisesRegex(deploy.DeploymentError, "init-env"):
            self.execute(perform=False)
        self.assertEqual(before, (self.root / ".env").read_bytes())

    def test_mail_seed_cannot_restore_missing_server_env(self):
        self.mail_seed()
        self.scenario["volumes"] = ["game_mysqldata"]
        with self.assertRaisesRegex(deploy.DeploymentError, "已有数据库"):
            self.execute(perform=False)
        self.assertFalse((self.root / ".env").exists())

    def test_mail_export_whitelist_archive_permissions_and_roundtrip(self):
        import tarfile
        exporter_spec = importlib.util.spec_from_file_location("mail_export", SOURCE / "scripts/export-mail-config.py")
        exporter = importlib.util.module_from_spec(exporter_spec)
        with patch.dict(sys.modules, {"deploy":deploy}):
            exporter_spec.loader.exec_module(exporter)
        self.valid_env(**self.mail_values())
        before = deploy.read_env(self.root / ".env")
        destination = self.root / ".private-deploy/mail-config.tar.gz"
        exporter.export_mail(self.root, destination)
        self.assertEqual(0o600, stat.S_IMODE(destination.stat().st_mode))
        with tarfile.open(destination, "r:gz") as archive:
            members = archive.getmembers()
            self.assertEqual([deploy.MAIL_IMPORT_FILE], [member.name for member in members])
            self.assertEqual(0o600, members[0].mode)
            payload = archive.extractfile(members[0]).read().decode()
        for key in deploy.SECRET_KEYS:
            self.assertNotIn(key + "=", payload)
            self.assertNotIn(before[key], payload)
        deploy.private_write(self.root / deploy.MAIL_IMPORT_FILE, payload)
        (self.root / ".env").unlink()
        self.execute(perform=False)
        after = deploy.read_env(self.root / ".env")
        for key, value in self.mail_values().items():
            self.assertEqual(value, after[key])
        for key in deploy.SECRET_KEYS:
            self.assertNotEqual(before[key], after[key])

    def test_mail_export_missing_configuration_does_not_leave_partial_archive(self):
        exporter_spec = importlib.util.spec_from_file_location("mail_export", SOURCE / "scripts/export-mail-config.py")
        exporter = importlib.util.module_from_spec(exporter_spec)
        with patch.dict(sys.modules, {"deploy":deploy}):
            exporter_spec.loader.exec_module(exporter)
        destination = self.root / ".private-deploy/mail-config.tar.gz"
        with self.assertRaises(deploy.DeploymentError):
            exporter.export_mail(self.root, destination)
        self.assertFalse(destination.exists())


if __name__ == "__main__":
    unittest.main()
