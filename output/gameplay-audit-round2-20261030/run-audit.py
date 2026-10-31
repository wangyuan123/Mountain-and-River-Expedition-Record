#!/usr/bin/env python3
"""Replay the seven observed defects against an isolated H2 database."""
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
    with tempfile.TemporaryDirectory(prefix='gameplay-audit-', dir='/private/tmp') as classes:
        subprocess.run(['javac', '-encoding', 'UTF-8', '-cp', classpath, '-d', classes,
                        str(output / 'GameplayAudit20261030.java')], cwd=root, env=env, check=True)
        completed = subprocess.run(['java', '-Dfile.encoding=UTF-8', '-cp', classes + os.pathsep + classpath,
                                    'GameplayAudit20261030'], cwd=root, env=env, text=True,
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=90)
    (output / 'reproduction.log').write_text(completed.stdout, encoding='utf-8')
    completed.check_returncode()
    payload = next(line.removeprefix('AUDIT_JSON ') for line in completed.stdout.splitlines()
                   if line.startswith('AUDIT_JSON '))
    results = json.loads(payload)
    (output / 'results.json').write_text(json.dumps(results, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    assert len(results) == 7 and not any(k.endswith('_probe_error') for k in results), results
    assert results['impossible_building_quests']['upgradeAt10']['success'] is False
    assert len(results['impossible_building_quests']['unreachable']) == 3
    assert results['locked_chapter_claim']['displayedChapterStatus'] == 'locked'
    assert results['locked_chapter_claim']['claim']['success'] is True
    assert results['building_level_counts_operations']['houseLevels'] == [1, 1]
    assert results['building_level_counts_operations']['questStatus'] == 'completed'
    surrender = results['surrender_scheduler_transaction']
    assert surrender['afterScheduler'] == 'PENDING' and surrender['afterTransactionalProxy'] == 'EXPIRED'
    assert surrender['acceptedAfterScheduler'] == 'ACCEPTED' and surrender['overdueCityStillFrozen'] is True
    assert results['market_cross_city_slot_race']['activeOrders'] == 4
    assert results['healing_retroactive_food']['foodAfter'] == 9900
    assert results['tax_salary_tick_frequency']['oneHourlySettlement'] == 19999900
    assert results['tax_salary_tick_frequency']['twoHalfHourlySettlements'] == 19999950
    print('Confirmed 7 existing defects in isolated H2; see results.json and reproduction.log.')


if __name__ == '__main__':
    main()
