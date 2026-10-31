"""隔离依赖和网络，验证本地启动脚本的配置、就绪检查与进程退出。"""
import json
import os
from pathlib import Path
import shlex
import signal
import subprocess
import sys
import tempfile
import time
import unittest


SOURCE = Path(__file__).resolve().parents[2]
FAKE_COMMAND = r'''#!PYTHON
import json, os, signal, subprocess, sys, time
from pathlib import Path
root = Path(os.environ['FIXTURE_ROOT'])
scenario = json.loads((root / 'scenario.json').read_text())
name = Path(sys.argv[0]).name
args = sys.argv[1:]
if name == 'brew':
    sys.exit(0)
if name == 'mysql':
    with (root / 'mysql.jsonl').open('a') as log:
        log.write(json.dumps({'args':args, 'password':os.environ.get('MYSQL_PWD', '')}) + '\n')
    sys.exit(0 if os.environ.get('MYSQL_PWD', '') == scenario.get('password', '') else 1)
if name == 'lsof':
    if any(arg in ('-iTCP:3306', '-iTCP:3307') for arg in args):
        sys.exit(0)
    sys.exit(1)
if name == 'curl':
    with (root / 'curl.jsonl').open('a') as log:
        log.write(json.dumps(args) + '\n')
    if scenario.get('http_status', 200) != 200:
        # curl 默认把 HTTP 500 当成功；--fail 才会返回非零。
        sys.exit(22 if '--fail' in args or '-f' in args else 0)
    print('200', end='')
    sys.exit(0)
if name == 'mvn':
    (root / 'backend-env.json').write_text(json.dumps({'password':os.environ.get('WARGAME_DB_PASSWORD', ''),
        'mail_user':os.environ.get('WARGAME_MAIL_USERNAME', '')}))
    child = subprocess.Popen([sys.executable, '-c',
        "import signal,time,sys; from pathlib import Path; signal.signal(signal.SIGTERM, signal.SIG_IGN); Path(sys.argv[1]).touch(); time.sleep(120)", str(root / 'child.ready')])
    (root / 'child.pid').write_text(str(child.pid))
    if scenario.get('backend_exit'):
        time.sleep(0.5)
        sys.exit(1)
if name in ('mvn', 'python3'):
    (root / (name + '.pid')).write_text(str(os.getpid()))
    while True:
        time.sleep(0.1)
'''


class StartLocalTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='wargame-start-test-')
        self.root = Path(self.temp.name)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        for name in ('backend', 'frontend'):
            (self.root / name).mkdir()
        (self.root / 'backend/pom.xml').touch()
        (self.root / 'frontend/index.html').touch()
        for name in ('brew', 'mysql', 'lsof', 'curl', 'mvn', 'python3'):
            command = self.bin / name
            command.write_text(FAKE_COMMAND.replace('#!PYTHON', '#!' + sys.executable))
            command.chmod(0o755)
        self.scenario = {}
        self.process = None
        self.log = None

    def tearDown(self):
        if self.process and self.process.poll() is None:
            os.killpg(self.process.pid, signal.SIGKILL)
            self.process.wait(timeout=5)
        # 即使旧脚本未清理子进程，失败用例也不能污染下一次验证。
        for name in ('mvn.pid', 'python3.pid', 'child.pid'):
            path = self.root / name
            if path.exists():
                try:
                    os.kill(int(path.read_text()), signal.SIGKILL)
                except ProcessLookupError:
                    pass
        if self.log:
            self.log.close()
        self.temp.cleanup()

    def start(self):
        (self.root / 'scenario.json').write_text(json.dumps(self.scenario))
        wrapper = '\n'.join([
            'source ' + shlex.quote(str(SOURCE / 'start-local.sh')),
            'PROJECT_DIR=' + shlex.quote(str(self.root)),
            'BACKEND_DIR="$PROJECT_DIR/backend"',
            'FRONTEND_DIR="$PROJECT_DIR/frontend"',
            'install_homebrew() { :; }',
            'ensure_java17() { :; }',
            'ensure_maven() { :; }',
            'ensure_git_hooks() { :; }',
            'get_local_ip() { :; }',
            'main',
        ])
        env = {key:value for key,value in os.environ.items()
               if not key.startswith(('WARGAME_', 'SPRING_', 'MYSQL_'))}
        env.update(PATH=str(self.bin) + os.pathsep + os.environ['PATH'], FIXTURE_ROOT=str(self.root))
        self.log = (self.root / 'run.log').open('w')
        self.process = subprocess.Popen(['/bin/bash', '-c', wrapper], env=env,
                                        stdout=self.log, stderr=subprocess.STDOUT, start_new_session=True)

    def wait_for(self, text, timeout=8):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            output = (self.root / 'run.log').read_text()
            if text in output:
                return output
            if self.process.poll() is not None:
                break
            time.sleep(0.05)
        self.fail('未出现预期输出 ' + repr(text) + '\n' + (self.root / 'run.log').read_text())

    def is_running(self, pid):
        result = subprocess.run(['ps', '-p', str(pid), '-o', 'stat='], capture_output=True, text=True)
        return result.returncode == 0 and not result.stdout.strip().startswith('Z')

    def test_local_env_loads_without_sourcing_deployment_env(self):
        deployment = 'WARGAME_DB_PASSWORD=deployment-password\nWARGAME_MAIL_USERNAME=deployment-mail\ntouch "$PROJECT_DIR/deployment-env-loaded"\n'
        (self.root / '.env').write_text(deployment)
        (self.root / '.env.local').write_text('WARGAME_DB_PASSWORD=\n')
        self.start()
        self.wait_for('游戏已启动')
        calls = [json.loads(line) for line in (self.root / 'mysql.jsonl').read_text().splitlines()]
        self.assertTrue(calls)
        self.assertTrue(all(call['password'] == '' for call in calls))
        backend_env = json.loads((self.root / 'backend-env.json').read_text())
        self.assertEqual(backend_env['password'], '')
        self.assertEqual(backend_env['mail_user'], '')
        self.assertFalse((self.root / 'deployment-env-loaded').exists())
        self.assertEqual((self.root / '.env').read_text(), deployment)

    def test_missing_local_env_uses_defaults_without_reading_deployment_env(self):
        (self.root / '.env').write_text('WARGAME_DB_PASSWORD=deployment-password\nexit 7\n')
        self.start()
        self.wait_for('游戏已启动')
        self.assertEqual(json.loads((self.root / 'backend-env.json').read_text())['password'], '')
        self.assertIn('未找到 .env.local', (self.root / 'run.log').read_text())

    def test_configured_database_credentials_are_checked_before_maven(self):
        self.scenario['password'] = 'correct-password'
        (self.root / '.env.local').write_text('WARGAME_DB_PASSWORD=wrong-password\n')
        self.start()
        self.assertEqual(self.process.wait(timeout=8), 1)
        self.assertFalse((self.root / 'mvn.pid').exists())
        self.assertIn('.env.local', (self.root / 'run.log').read_text())

    def test_mysql_uses_the_same_tcp_target_as_backend(self):
        self.scenario['password'] = 'correct-password'
        (self.root / '.env.local').write_text(
            "WARGAME_DB_URL='jdbc:mysql://127.0.0.1:3307/custom_game?useSSL=false'\n"
            'WARGAME_DB_USER=developer\nWARGAME_DB_PASSWORD=correct-password\n')
        self.start()
        self.wait_for('游戏已启动')
        calls = [json.loads(line) for line in (self.root / 'mysql.jsonl').read_text().splitlines()]
        self.assertTrue(all(call['password'] == 'correct-password' for call in calls))
        self.assertIn('--host=127.0.0.1', calls[0]['args'])
        self.assertIn('--port=3307', calls[0]['args'])
        self.assertIn('--user=developer', calls[0]['args'])
        self.assertTrue(any('custom_game' in arg for call in calls for arg in call['args']))

    def test_term_exits_and_kills_maven_descendants(self):
        self.assert_shutdown(signal.SIGTERM, 143)

    def test_int_exits_and_kills_maven_descendants(self):
        self.assert_shutdown(signal.SIGINT, 130)

    def assert_shutdown(self, stop_signal, expected_code):
        self.start()
        self.wait_for('游戏已启动')
        self.wait_for_child()
        self.wait_for_file('python3.pid')
        self.process.send_signal(stop_signal)
        self.assertEqual(self.process.wait(timeout=10), expected_code)
        for name in ('mvn.pid', 'python3.pid', 'child.pid'):
            self.assertFalse(self.is_running(int((self.root / name).read_text())), name)
        self.assertEqual((self.root / 'run.log').read_text().count('正在关闭服务'), 1)

    def test_stubborn_old_listener_is_killed(self):
        program = (
            'import signal,socket,time; '
            'signal.signal(signal.SIGTERM, signal.SIG_IGN); '
            'server=socket.socket(); server.bind(("127.0.0.1",0)); server.listen(); '
            'print(server.getsockname()[1], flush=True); time.sleep(120)')
        listener = subprocess.Popen([sys.executable, '-c', program], stdout=subprocess.PIPE, text=True)
        try:
            port = listener.stdout.readline().strip()
            wrapper = 'source ' + shlex.quote(str(SOURCE / 'start-local.sh')) + '\nstop_port_process "$1"'
            result = subprocess.run(['/bin/bash', '-c', wrapper, 'listener-test', port],
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertEqual(listener.wait(timeout=3), -signal.SIGKILL)
        finally:
            if listener.poll() is None:
                listener.kill()
                listener.wait(timeout=3)
            listener.stdout.close()

    def wait_for_child(self):
        self.wait_for_file('child.ready')

    def wait_for_file(self, name):
        deadline = time.monotonic() + 3
        while not (self.root / name).exists() and time.monotonic() < deadline:
            time.sleep(0.05)
        self.assertTrue((self.root / name).exists(), name)

    def test_http_500_is_not_ready_and_probes_have_timeouts(self):
        self.scenario['http_status'] = 500
        self.start()
        self.wait_for('后端进程 PID')
        self.wait_for_child()
        deadline = time.monotonic() + 2
        while not (self.root / 'curl.jsonl').exists() and time.monotonic() < deadline:
            time.sleep(0.05)
        calls = [json.loads(line) for line in (self.root / 'curl.jsonl').read_text().splitlines()]
        self.assertNotIn('游戏已启动', (self.root / 'run.log').read_text())
        self.assertTrue(all('--max-time' in call and '--connect-timeout' in call for call in calls))

    def test_backend_exit_after_startup_stops_frontend(self):
        self.scenario['backend_exit'] = True
        self.start()
        self.wait_for('游戏已启动')
        self.assertEqual(self.process.wait(timeout=10), 1)
        self.assertFalse(self.is_running(int((self.root / 'python3.pid').read_text())))

    def test_chinese_messages_work_in_utf8_locales_on_system_bash(self):
        # macOS Bash 3.2 在 UTF-8 locale 下会把紧邻变量的中文首字节误识别为变量名。
        setup = '\n'.join([
            'source ' + shlex.quote(str(SOURCE / 'start-local.sh')),
            "WARGAME_DB_URL='jdbc:mysql://127.0.0.1:3306/wargame'",
            'WARGAME_DB_USER=root',
            'WARGAME_DB_PASSWORD=',
            'configure_database',
            'mysql_local() { :; }',
            'curl() { return 22; }',
        ])
        cases = [
            ('ensure_database', 0, '创建数据库 wargame（如不存在）'),
            ('database_connection_error', 1, '3306（用户 root）'),
            ('wait_for_http 999999 http://127.0.0.1:1 0 后端', 1, '后端进程已退出'),
            ('wait_for_http $$ http://127.0.0.1:1 0 后端', 1, '后端在 0 秒内未就绪'),
        ]
        for locale in ('en_US.UTF-8', 'zh_CN.UTF-8'):
            for command, expected_code, message in cases:
                with self.subTest(locale=locale, command=command):
                    result = subprocess.run(['/bin/bash', '-c', setup + '\n' + command],
                                            env=dict(os.environ, LC_ALL=locale),
                                            capture_output=True, text=True, errors='replace', timeout=5)
                    output = result.stdout + result.stderr
                    self.assertEqual(result.returncode, expected_code, output)
                    self.assertIn(message, output)
                    self.assertNotIn('unbound variable', output)


if __name__ == '__main__':
    unittest.main()
