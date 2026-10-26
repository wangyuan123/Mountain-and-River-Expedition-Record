"""保留当前本地服务的应用配置，以正确的UTF-8绝对类路径加载已验收修复。"""
import json
import os
from pathlib import Path
import re
import shlex
import signal
import subprocess
import time
import urllib.request

out = Path(__file__).resolve().parent
root = out.parents[1]
pid = subprocess.check_output(['lsof', '-nP', '-iTCP:8080', '-sTCP:LISTEN', '-t'], text=True).strip()
assert pid.isdigit()
raw = subprocess.check_output(['ps', '-p', pid, '-o', 'args='], text=True)
assert 'com.wargame.WargameApplication' in raw
environment = subprocess.check_output(['ps', 'eww', '-p', pid, '-o', 'command='], text=True)
env = os.environ.copy()
# 应用配置只在进程内交接，不写入验收文件或终端，避免输出JWT和数据库凭据。
for key, value in re.findall(r'(?:^|\s)((?:WARGAME_|SPRING_)[A-Z0-9_]+)=([^\s]*)', environment):
    env[key] = value
env.update(LANG='en_US.UTF-8', LC_ALL='en_US.UTF-8')
url = env.get('WARGAME_DB_URL', 'jdbc:mysql://localhost:3306/wargame?')
assert re.search(r'/wargame(?:\?|$)', url), '当前进程的业务库与验收记录不符，停止重启'
args = ['/opt/homebrew/opt/openjdk@17/bin/java', '-Dfile.encoding=UTF-8', '-cp',
        str(root / 'backend/target/classes') + ':' + Path('/private/tmp/wargame-audit-classpath.txt').read_text().strip(),
        'com.wargame.WargameApplication']
args.extend(arg for arg in shlex.split(raw) if arg.startswith('--'))
os.kill(int(pid), signal.SIGTERM)
for _ in range(60):
    if subprocess.run(['kill', '-0', pid], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
        break
    time.sleep(.25)
else:
    raise RuntimeError('原游戏服务未退出')
with (out / 'backend-current.log').open('w') as log:
    child = subprocess.Popen(args, env=env, cwd=root / 'backend', stdin=subprocess.DEVNULL,
                             stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
(out / 'backend-current.pid').write_text(str(child.pid))
for _ in range(90):
    assert child.poll() is None, '本地后端启动失败，请检查验收日志'
    try:
        with urllib.request.urlopen('http://127.0.0.1:8080/api/forum/boards', timeout=2) as response:
            assert isinstance(json.load(response), list)
        print('当前8080游戏后端已更新并就绪')
        break
    except Exception:
        time.sleep(.5)
else:
    raise RuntimeError('本地后端就绪超时')
