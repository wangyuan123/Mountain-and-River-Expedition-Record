"""以原启动参数更新本轮独立验收服务，数据库始终固定为验收库。"""
import json
import os
from pathlib import Path
import shlex
import signal
import subprocess
import time
import urllib.request

out = Path(__file__).resolve().parent
listener = subprocess.run(['lsof', '-nP', '-iTCP:8082', '-sTCP:LISTEN', '-t'], text=True, capture_output=True)
pid = listener.stdout.strip()
if pid:
    assert pid.isdigit()
    previous = shlex.split(subprocess.check_output(['ps', '-p', pid, '-o', 'args='], text=True))
    assert 'com.wargame.WargameApplication' in previous and '--server.port=8082' in previous
args = ['/opt/homebrew/opt/openjdk@17/bin/java', '-Dfile.encoding=UTF-8', '-cp',
        str(out.parents[1] / 'backend/target/classes') + ':' + Path('/private/tmp/wargame-audit-classpath.txt').read_text().strip(),
        'com.wargame.WargameApplication', '--server.port=8082', '--game.scheduling.enabled=false',
        '--game.auth.register-per-ip-per-hour=20']
env = os.environ.copy()
env.update(WARGAME_DB_URL='jdbc:mysql://127.0.0.1:3306/wargame_audit_20261025?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true',
           WARGAME_DB_USER='root', WARGAME_DB_PASSWORD='', LANG='en_US.UTF-8', LC_ALL='en_US.UTF-8')
if pid:
    os.kill(int(pid), signal.SIGTERM)
    for _ in range(60):
        if subprocess.run(['kill', '-0', pid], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
            break
        time.sleep(.25)
    else:
        raise RuntimeError('验收服务未退出')
with (out / 'backend-live-current.log').open('w') as log:
    child = subprocess.Popen(args, env=env, cwd=out.parents[1] / 'backend', stdin=subprocess.DEVNULL,
                             stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
(out / 'backend-live.pid').write_text(str(child.pid))
for _ in range(90):
    assert child.poll() is None, '验收后端启动失败'
    try:
        with urllib.request.urlopen('http://127.0.0.1:8082/api/forum/boards', timeout=2) as response:
            assert isinstance(json.load(response), list)
        print('独立验收后端8082已就绪')
        break
    except Exception:
        time.sleep(.5)
else:
    raise RuntimeError('验收后端就绪超时')
