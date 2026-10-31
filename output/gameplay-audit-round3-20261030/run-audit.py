#!/usr/bin/env python3
"""Compile and execute current-service probes against isolated H2 only."""
from pathlib import Path
import json
import os
import subprocess
import tempfile
import xml.etree.ElementTree as ET


def main():
    output = Path(__file__).resolve().parent
    root = output.parent.parent
    report = root / 'backend/target/surefire-reports/TEST-com.wargame.MarketServiceTest.xml'
    properties = ET.parse(report).getroot().find('properties')
    classpath = next(p.get('value') for p in properties if p.get('name') == 'java.class.path')
    env = dict(os.environ, LC_ALL='en_US.UTF-8', LANG='en_US.UTF-8')
    with tempfile.TemporaryDirectory(prefix='gameplay-audit-round3-', dir='/private/tmp') as classes:
        subprocess.run(['javac', '-encoding', 'UTF-8', '-cp', classpath, '-d', classes, str(output / 'GameplayAuditRound3.java')], cwd=root, env=env, check=True)
        completed = subprocess.run(['java', '-Dfile.encoding=UTF-8', '-cp', classes + os.pathsep + classpath, 'GameplayAuditRound3'], cwd=root, env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
    (output / 'reproduction.log').write_text(completed.stdout, encoding='utf-8')
    completed.check_returncode()
    payload = next(line.removeprefix('AUDIT_JSON ') for line in completed.stdout.splitlines() if line.startswith('AUDIT_JSON '))
    results = json.loads(payload)
    assert len(results) == 9 and not any(k.endswith('_probe_error') for k in results), results
    assert results['mail_attachment_integer_overflow']['success'] is True
    assert results['mail_attachment_integer_overflow']['diamondsAfter'] == 1294967296
    for key in ['prisoner_recruitment_retroactive_food', 'mail_army_retroactive_food']:
        assert results[key]['success'] is True and results[key]['foodAfter'] == 9900
    wall = results['wall_downgrade_keeps_overcapacity_forts']
    assert wall['build']['success'] is True and wall['dismantle']['success'] is True
    assert wall['capacityAfter'] == 0 and wall['fortsAfter'] == 3000 and wall['combatForts'] == {'bunker': 3000}
    assert results['cancel_production_leaves_idle_lane']['cancelled'] is True
    assert results['cancel_production_leaves_idle_lane']['idleSeconds'] == 324
    refund = results['construction_refund_changes_with_technology']
    assert refund['started'] is True and refund['cancelled'] is True
    assert refund['paidSteel'] == 120 and refund['expectedHalfRefund'] == 60 and refund['actualSteelRefund'] == 57
    assert results['new_officer_retroactive_salary']['retroactiveSalary'] == 100
    assert results['new_officer_immediate_arrears_penalty']['loyaltyAfter'] == 0
    assert results['tax_change_retroactive_income']['baselineGoldWithoutChange'] == 0
    assert results['tax_change_retroactive_income']['goldAfterSameTimeTick'] > 0
    (output / 'results.json').write_text(json.dumps(results, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(results, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
