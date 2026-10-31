#!/usr/bin/env python3
"""编译隔离的正常玩法检查程序，固定使用独立 H2，不启动 HTTP 或外发通知。"""
from pathlib import Path
import json
import os
import subprocess
import tempfile
import xml.etree.ElementTree as ET

output = Path(__file__).resolve().parent
root = output.parent.parent
properties = ET.parse(root / 'backend/target/surefire-reports/TEST-com.wargame.MarketServiceTest.xml').getroot().find('properties')
classpath = next(p.get('value') for p in properties if p.get('name') == 'java.class.path')
environment = dict(os.environ, LC_ALL='en_US.UTF-8', LANG='en_US.UTF-8')
with tempfile.TemporaryDirectory(prefix='gameplay-round5-', dir='/private/tmp') as classes:
    subprocess.run(['javac', '-encoding', 'UTF-8', '-cp', classpath, '-d', classes, str(output / 'GameplayAuditRound5.java')],
                   cwd=root, env=environment, check=True)
    result = subprocess.run(['java', '-Dfile.encoding=UTF-8', '-cp', classes + os.pathsep + classpath, 'GameplayAuditRound5'],
                            cwd=root, env=environment, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180)
(output / 'observations.log').write_text(result.stdout, encoding='utf-8')
result.check_returncode()
payload = next(line.removeprefix('AUDIT_JSON ') for line in result.stdout.splitlines() if line.startswith('AUDIT_JSON '))
observations = json.loads(payload)
(output / 'observations.json').write_text(json.dumps(observations, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps(observations, ensure_ascii=False, indent=2))
if any(key.endswith('_probe_error') for key in observations):
    raise SystemExit('检查程序存在执行错误，请查看 observations.log')
