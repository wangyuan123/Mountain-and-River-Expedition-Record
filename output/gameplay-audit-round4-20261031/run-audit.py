#!/usr/bin/env python3
"""只在独立 H2 调用真实服务，结果保存到本目录。"""
from pathlib import Path
import json
import os
import subprocess
import tempfile
import xml.etree.ElementTree as ET

output = Path(__file__).resolve().parent
root = output.parent.parent
props = ET.parse(root / 'backend/target/surefire-reports/TEST-com.wargame.MarketServiceTest.xml').getroot().find('properties')
classpath = next(p.get('value') for p in props if p.get('name') == 'java.class.path')
env = dict(os.environ, LC_ALL='en_US.UTF-8', LANG='en_US.UTF-8')
with tempfile.TemporaryDirectory(prefix='gameplay-audit-round4-', dir='/private/tmp') as classes:
    subprocess.run(['javac', '-encoding', 'UTF-8', '-cp', classpath, '-d', classes, str(output / 'GameplayAuditRound4.java')], cwd=root, env=env, check=True)
    result = subprocess.run(['java', '-Dfile.encoding=UTF-8', '-cp', classes + os.pathsep + classpath, 'GameplayAuditRound4'], cwd=root, env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180)
(output / 'reproduction.log').write_text(result.stdout, encoding='utf-8')
result.check_returncode()
payload = next(line.removeprefix('AUDIT_JSON ') for line in result.stdout.splitlines() if line.startswith('AUDIT_JSON '))
findings = json.loads(payload)
(output / 'results.json').write_text(json.dumps(findings, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps(findings, ensure_ascii=False, indent=2))
if any(key.endswith('_probe_error') for key in findings):
    raise SystemExit('Some probes require correction; see reproduction.log')
