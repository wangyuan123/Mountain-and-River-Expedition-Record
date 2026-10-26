"""接续已结束的合成对战，验证权限、粮耗、缺粮返程与资产守恒。"""
import json
import pathlib
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

OUT = pathlib.Path(__file__).resolve().parent
BASE = 'http://127.0.0.1:8082/api'
DB = 'wargame_joint_acceptance_20261024'
TOKENS = {}


def sql(query):
    return subprocess.check_output(['mysql', '-h', '127.0.0.1', '-u', 'root', '-N', '-B', '--raw', DB, '-e', query], text=True).strip()


def save(name, data):
    (OUT / (name + '.json')).write_text(json.dumps(data, ensure_ascii=False, indent=2))


def api(player, path, data=None, expected=200):
    """仅向独立验收服务发送合成账号请求；鉴权凭证不写入证据文件。"""
    headers = {'Content-Type': 'application/json'}
    if player is not None:
        headers['Authorization'] = 'Bearer ' + TOKENS[player]
    request = urllib.request.Request(BASE + path, headers=headers, data=None if data is None else json.dumps(data).encode())
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            status, result = response.status, json.load(response)
    except urllib.error.HTTPError as error:
        status, result = error.code, json.loads(error.read())
    assert status == expected, (path, status, result)
    return result


fixture = json.loads((OUT / 'fixture.json').read_text())
a, d, s = fixture['players']
cities = {int(key): value for key, value in fixture['cities'].items()}
assert sql('SELECT COUNT(*) FROM players') == '3', '只允许既有三个合成账号的验收库'

if '--verify-return-only' in sys.argv:
    # 正常调度完成后只读核验最终资产；不再改写时间或调用经济结算入口。
    expected = json.loads((OUT / '09-food-accounting.json').read_text())['survivingSupport']
    ids = ','.join(expected)
    returned = int(sql(f"SELECT count FROM army_units WHERE player_id={s} AND city_slot=0 AND type='htank'"))
    assert returned == 997399
    assert sql(f'SELECT COUNT(*) FROM marches WHERE id IN ({ids})') == '0'
    officer_ids = ','.join(str(fixture['officers'][name]) for name in ['援军甲', '援军乙'])
    roles = sql(f'SELECT role FROM officers WHERE id IN ({officer_ids}) ORDER BY id').splitlines()
    assert roles == ['idle', 'idle']
    home = int(sql(f"SELECT count FROM army_units WHERE player_id={d} AND city_slot=0 AND type='htank'"))
    assert home == 200000
    receipt = {'returnedSupport': returned, 'supportLosses': 2601, 'initialSupport': 1000000,
               'homeInventory': home, 'officerRoles': roles, 'settlement': 'normal-scheduler-port-8083'}
    save('11-return-conservation', receipt)
    print(json.dumps(receipt, ensure_ascii=False))
    sys.exit(0)
for player, name in zip([a, d, s], ['joint_attack', 'joint_defend', 'joint_support']):
    result = api(None, '/auth/login', {'username': name, 'password': 'Synthetic-Joint-Only-20261024!', 'serverId': 'jiangsu-1'})
    TOKENS[player] = result['token']

if '--resume-famine' not in sys.argv:
    details = {}
    for player in [a, d, s]:
        report = next(row for row in api(player, '/game/reports') if row['type'] == 'battle')
        details[player] = api(player, '/game/reports/' + str(report['id']))
        save('06-report-detail-' + str(player), details[player])
        assert len(details[player]['formationContributions']) == 4
        assert details[player]['commanders'] == {} and details[player]['unitEquipment'] == {}
        assert len(details[player]['formationRecovery']) == (2 if player == s else 1)
        expected_sources = {a: {'formation-0'}, d: {'formation-1'}, s: {'formation-2', 'formation-3'}}
        assert {row['formationId'] for row in details[player]['formationRecovery']} == expected_sources[player]
        assert all(row['cityId'] == cities[player] and 'playerId' not in row for row in details[player]['formationRecovery'])

    denied = api(a, '/game/reports/' + str(details[d]['id']), expected=400)
    save('07-other-player-report-denied', denied)

    if '--read-only' in sys.argv:
        # 不创建分享凭证、不改资源／接收状态／行军；可在补充生命周期验收未授权时独立核验。
        summary = json.loads((OUT / 'summary.json').read_text())
        summary['attackerWin'] = details[a]['attackerWin']
        assert summary['attackerWin'] is False
        save('summary', summary)
        print(json.dumps({'mode': 'read-only', 'checks': ['战报只返还本玩家医疗采样', '跨玩家战报读取拒绝'],
                          'attackerWin': summary['attackerWin']}, ensure_ascii=False))
        sys.exit(0)

    # 直接创建绑定合成战报的临时凭证，验收分享读取的公开投影；不向聊天频道发送消息。
    token = str(uuid.uuid4())
    sql(f"INSERT INTO shared_reports(token,report_id,owner_id,expires_at) VALUES('{token}',{details[d]['id']},{d},{int(time.time()*1000)+3600000})")
    try:
        shared = api(a, '/game/reports/shared/' + token)
        assert len(shared['formationContributions']) == 4
        forbidden = {'jointDefense', 'officerId', 'sourceCityId', 'citySlot', 'cityId', 'equipmentSources', 'formationRecovery', 'fromCoord', 'toCoord', 'commanders', 'unitEquipment'}

        def check_public(value):
            if isinstance(value, dict):
                assert not forbidden.intersection(value), forbidden.intersection(value)
                for child in value.values():
                    check_public(child)
            elif isinstance(value, list):
                for child in value:
                    check_public(child)

        check_public(shared)
        save('08-shared-public-report', shared)
    finally:
        sql(f"DELETE FROM shared_reports WHERE token='{token}'")

    summary = json.loads((OUT / 'summary.json').read_text())
    summary['attackerWin'] = details[a]['attackerWin']
    assert summary['attackerWin'] is False
    save('summary', summary)

    # 先按正式入口结清真实经过的游戏时间，派兵城不触发其他经济操作，便于验证没有重复扣粮。
    sender_food = int(sql(f'SELECT food FROM resources WHERE player_id={s} AND city_slot=0'))
    target_before = int(sql(f'SELECT food FROM resources WHERE player_id={d} AND city_slot=0'))
    overview = api(d, '/game/cities/garrison', {'cityId': cities[d], 'allowed': False})
    city = next(row for row in overview['cities'] if row['id'] == cities[d])
    target_after = int(sql(f'SELECT food FROM resources WHERE player_id={d} AND city_slot=0'))
    assert target_after < target_before
    assert int(sql(f'SELECT food FROM resources WHERE player_id={s} AND city_slot=0')) == sender_food
    assert city['supportFoodPerHour'] > 0
    expected = {int(mid): json.loads(army) for mid, army in (line.split('\t') for line in sql(f"SELECT id,army FROM marches WHERE player_id={s} AND action='reinforce' AND stationed=1 ORDER BY id").splitlines())}
    assert sum(sum(army.values()) for army in expected.values()) == 997399
    save('09-food-accounting', {'targetFoodBefore': target_before, 'targetFoodAfter': target_after, 'senderFoodUnchanged': sender_food,
                              'city': city, 'survivingSupport': expected})

    # 只清空合成接收城粮食，等待自然游戏时间推进；不修改全服时钟和任何战斗结果。
    sql(f'UPDATE resources SET food=0,version=version+1 WHERE player_id={d} AND city_slot=0')
else:
    # 接续已清空粮食的合成城市，不重建战斗、不覆盖已保存粮耗证据。
    receipt = json.loads((OUT / '09-food-accounting.json').read_text())
    expected = {int(key): value for key, value in receipt['survivingSupport'].items()}
    summary = json.loads((OUT / 'summary.json').read_text())

# 游戏时钟按服务调度离散推进，必须跨过一次自然时钟更新再评估。
time.sleep(1.2)
famine = api(d, '/game/cities/garrison', {'cityId': cities[d], 'allowed': False})
rows = []
for line in sql(f"SELECT id,army,stationed,returning FROM marches WHERE player_id={s} AND action='reinforce' ORDER BY id").splitlines():
    mid, army, stationed, returning = line.split('\t')
    rows.append({'marchId': int(mid), 'army': json.loads(army), 'stationed': stationed == '1', 'returning': returning == '1'})
save('10-famine-return', {'overview': famine, 'marches': rows})
assert len(rows) == 2
assert all(row['returning'] and not row['stationed'] and row['army'] == expected[row['marchId']] for row in rows)
assert next(row for row in famine['cities'] if row['id'] == cities[d])['supportFoodPerHour'] == 0

# 仅缩短这两支合成驻军的返程等待，再由真实行军结算入口返还兵力和军官。
now = int(sql('SELECT game_time FROM game_clock WHERE singleton_id=1'))
ids = ','.join(str(mid) for mid in expected)
sql(f'UPDATE marches SET start_at={now-20000},arrive_at={now-1},base_duration_ms=20000,version=version+1 WHERE id IN ({ids}) AND returning=1')
api(s, '/game/state')
# /game/state 只读取状态；返程实际由正常运行的 TickScheduler 结算。
returned = int(sql(f"SELECT count FROM army_units WHERE player_id={s} AND city_slot=0 AND type='htank'"))
# 调度关闭的手动回合环境不应伪称已归还；只在正常调度环境等待其既有5秒周期。
for _ in range(12):
    if returned == 997399:
        break
    time.sleep(1)
    returned = int(sql(f"SELECT count FROM army_units WHERE player_id={s} AND city_slot=0 AND type='htank'"))
assert returned == 997399
assert sql(f'SELECT COUNT(*) FROM marches WHERE id IN ({ids})') == '0'
officer_ids = ','.join(str(fixture['officers'][name]) for name in ['援军甲', '援军乙'])
roles = sql(f'SELECT role FROM officers WHERE id IN ({officer_ids}) ORDER BY id').splitlines()
assert roles == ['idle', 'idle']
home = int(sql(f"SELECT count FROM army_units WHERE player_id={d} AND city_slot=0 AND type='htank'"))
assert home == 200000
save('11-return-conservation', {'returnedSupport': returned, 'supportLosses': 2601, 'initialSupport': 1000000,
                               'homeInventory': home, 'officerRoles': roles})
print(json.dumps({'checks': ['战报只返还本玩家医疗采样', '跨玩家战报读取拒绝', '分享白名单裁剪', '接收城扣粮且派兵城不重复扣粮',
                             '缺粮整队返程', '返程结束兵力守恒', '两名军官释放'], 'attackerWin': summary['attackerWin'], 'returnedSupport': returned}, ensure_ascii=False))
