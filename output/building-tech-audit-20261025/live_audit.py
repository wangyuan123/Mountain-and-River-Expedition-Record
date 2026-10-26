"""通过正式HTTP接口验收，辅助SQL仅用于本轮独立库的合成账号夹具。"""
import json
from pathlib import Path
import re
import subprocess
import time
import urllib.error
import urllib.request

OUT = Path(__file__).resolve().parent
ROOT = OUT.parents[1]
DB = 'wargame_audit_20261025'
BASE = 'http://127.0.0.1:8082/api'
PASSWORD = 'Synthetic-Audit-20261025!'
TOKENS = {}
CHECKS = []

def sql(query):
    return subprocess.check_output(['mysql', '-h', '127.0.0.1', '-u', 'root', '-N', '-B', '--raw', DB, '-e', query], text=True).strip()

def quote(value):
    return "'" + str(value).replace('\\', '\\\\').replace("'", "''") + "'"

def save(name, value):
    (OUT / (name + '.json')).write_text(json.dumps(value, ensure_ascii=False, indent=2))

def api(player, path, data=None, expected=200):
    headers = {'Content-Type': 'application/json'}
    if player is not None:
        headers['Authorization'] = 'Bearer ' + TOKENS[player]
    request = urllib.request.Request(BASE + path, headers=headers, data=None if data is None else json.dumps(data).encode())
    try:
        with urllib.request.urlopen(request, timeout=90) as response:
            status, result = response.status, json.load(response)
    except urllib.error.HTTPError as error:
        status, result = error.code, json.loads(error.read())
    assert status == expected, (path, status, result)
    return result

def check(name, condition):
    assert condition, name
    CHECKS.append(name)
    save('live-progress', CHECKS)
    print(name, flush=True)

def now():
    return int(sql('SELECT game_time FROM game_clock WHERE singleton_id=1'))

def put_building(player, key, level):
    sql(f'UPDATE buildings SET level={level},version=version+1 WHERE player_id={player} AND city_slot=0 AND type={quote(key)}')

def put_tech(player, key, level):
    sql(f'INSERT INTO technologies(player_id,type,level,version) VALUES({player},{quote(key)},{level},0) ON DUPLICATE KEY UPDATE level={level},version=version+1')

def relocate(player, city, x, y):
    site = api(player, f'/game/cities/site?x={x}&y={y}')
    assert site['valid'], site
    sql(f'UPDATE player_cities SET x={x},y={y},legacy_naval=0,version=version+1 WHERE id={city}; '
        f'UPDATE players SET city_pos_x={x},city_pos_y={y},pos_x={x},pos_y={y},version=version+1 WHERE id={player}')
    return site

def dispatch(player, target, action='plunder', army=None):
    result = api(player, '/game/world/dispatch', {'targetKind': 'player', 'targetId': target, 'action': action,
        'army': army or {'infantry': 100}, 'carryRes': {}, 'abandonNewbieProtection': True})
    assert result['success'], result
    return int(sql(f'SELECT MAX(id) FROM marches WHERE player_id={player}'))

def radar(player):
    return api(player, '/game/world/radar')

def wait_contacts(player, count):
    for _ in range(30):
        value = radar(player)
        if len(value['contacts']) == count:
            return value
        time.sleep(.2)
    raise AssertionError(('雷达记录数量未符合预期', count, value))

players = []
for name in ['audit_observer', 'audit_ally', 'audit_attacker']:
    existing = sql(f'SELECT id FROM players WHERE username={quote(name)}')
    result = api(None, '/auth/login' if existing else '/auth/register', {'username': name, 'password': PASSWORD,
        'agreementVersion': '2026-09-28-v1', 'serverId': 'jiangsu-1'})
    player = int(existing or result['playerId'])
    TOKENS[player] = result['token']
    api(player, '/game/state')
    players.append(player)
observer, ally, attacker = players
ids = ','.join(map(str, players))
cities = {int(row.split('\t')[0]): int(row.split('\t')[1]) for row in sql(
    f'SELECT owner_id,id FROM player_cities WHERE owner_id IN ({ids}) AND city_slot=0').splitlines()}
sql(f'UPDATE players SET military_rank=17,newbie_protection_ended=1,version=version+1 WHERE id IN ({ids}); '
    f'UPDATE resources SET food=1000000000,steel=1000000000,oil=1000000000,rare=1000000000,gold=1000000000,version=version+1 WHERE player_id IN ({ids}); '
    f'UPDATE city_state SET shield_until=0,peace_until=0,version=version+1 WHERE player_id IN ({ids})')

# 每处先经过正式四格选址校验，保留世界地形和原有据点。
placed = False
for y in range(100, 200, 10):
    points = [(observer, 100, y+4), (ally, 120, y), (attacker, 80, y)]
    if all(api(player, f'/game/cities/site?x={x}&y={cy}')['valid'] for player, x, cy in points):
        for player, x, cy in points:
            relocate(player, cities[player], x, cy)
        placed = True
        break
assert placed
save('fixture', {'database': DB, 'players': players, 'cities': cities, 'coordinates': points})
check('三个测试城市使用合法且互不重叠的2×2陆地', True)

rules = json.loads((ROOT / 'backend/src/main/resources/game/tech-building-prerequisites.json').read_text())
for player in players:
    for key in rules['buildings']:
        sql(f'INSERT INTO buildings(player_id,city_slot,type,slot,level,version) VALUES({player},0,{quote(key)},0,10,0) '
            f'ON DUPLICATE KEY UPDATE level=10,version=version+1')
    sql(f"INSERT INTO army_units(player_id,city_slot,type,count,version) VALUES({player},0,'infantry',10000,0) ON DUPLICATE KEY UPDATE count=10000,version=version+1")

sql(f"INSERT INTO guilds(name,leader_player_id,created_at) VALUES('建筑科技联防验收',{observer},{now()}); "
    "SET @audit_g=(SELECT id FROM guilds WHERE name='建筑科技联防验收'); "
    f"INSERT INTO guild_members(guild_id,player_id,role,joined_at) VALUES(@audit_g,{observer},'leader',1),(@audit_g,{ally},'member',1)")
declared = api(attacker, '/game/world/declare-war', {'targetCityId': cities[ally], 'abandonNewbieProtection': True})
assert declared['success'], declared
sql(f'UPDATE players SET war_at={now()-1},war_end_at={now()+172800000},version=version+1 WHERE id={attacker}')

put_building(observer, 'guild_radar', 1)
off = radar(observer)
check('联防雷达默认关闭且一级覆盖半径为1格', not off['cities'][0]['enabled'] and off['cities'][0]['radius'] == 1)
march = dispatch(attacker, cities[ally])
time.sleep(.5)
check('关闭联防雷达时真实攻击不生成区域军情', not radar(observer)['contacts'])
api(observer, '/game/world/radar/setting', {'cityId': cities[observer], 'enabled': True})
check('一级联防雷达不发现四格以外的真实攻击路线', not radar(observer)['contacts'])
put_building(observer, 'guild_radar', 10)
api(observer, '/game/world/radar/setting', {'cityId': cities[observer], 'enabled': True})
live = wait_contacts(observer, 1)
save('01-radar-level0-intelligence', live)
entry = live['contacts'][0]
check('十级联防雷达能发现完整路线，部队尚未到达', entry['marchId'] == march and now() < int(sql(f'SELECT arrive_at FROM marches WHERE id={march}')))
check('零级预警科技不泄露行动、来源或精确兵力', all(key not in entry for key in ['action', 'fromName', 'fromX', 'army', 'totalTroops', 'tech']))
put_tech(observer, 'recon_radar', 7)
entry = radar(observer)['contacts'][0]
check('七级预警科技解锁精确编成', entry.get('army') == {'infantry': 100})
closed = api(observer, '/game/world/radar/setting', {'cityId': cities[observer], 'enabled': False})
save('02-radar-closed-history', closed)
check('关闭监测后取消实时条目并保留采样历史', not closed['contacts'] and len(closed['history']) == 1)
api(attacker, '/game/world/cancel-march', {'marchId': march})
api(observer, '/game/world/radar/setting', {'cityId': cities[observer], 'enabled': True})
march = dispatch(attacker, cities[ally])
live = wait_contacts(observer, 1)
save('03-radar-real-dispatch-event', live)
check('新出征事务提交后自动产生预警，无需定时扫描', live['contacts'][0]['marchId'] == march)
api(attacker, '/game/world/cancel-march', {'marchId': march})
wait_contacts(observer, 0)

# 科研逐项通过真实准入、付费、建队列与退费流程；效果数值由对应集成测试验证。
research = []
for key in rules['technologies']:
    put_tech(observer, key, 0)
    result = api(observer, '/game/tech/upgrade', {'tech': key})
    assert result['success'], (key, result.get('message'))
    research.append({key: {k: result.get(k) for k in ['durationSeconds', 'cost', 'targetLevel']}})
    assert api(observer, '/game/tech/cancel', {'queueId': result['queueId']})['success']
save('04-every-technology', research)
check(f'全部{len(research)}项科技可按正式规则研究并取消退费', True)

# 内陆军港按规则锁定；沿海位置通过服务端校验后再迁移进行建筑全目录验收。
put_building(observer, 'port', 0)
denied = api(observer, '/game/build/upgrade', {'building': 'port', 'slot': 0})
check('内陆军港按沿海建设限制拒绝开工', not denied['success'] and ('沿海' in denied['message'] or '海岸' in denied['message']))
put_building(observer, 'port', 10)
world = int(sql(f'SELECT world_id FROM player_cities WHERE id={cities[observer]}'))
mask = sql(f'SELECT terrain_data FROM world_map WHERE id={world}')
size = round(len(mask) ** .5)
coast = None
for cy in range(20, size-20):
    if coast:
        break
    for x in range(200, size-2):
        if any(mask[(cy+dy)*size+x+dx] != '0' for dy in range(2) for dx in range(2)):
            continue
        if not any(mask[yy*size+xx] == '1' for yy in range(cy-1, cy+3) for xx in range(x-1, x+3)):
            continue
        site = api(observer, f'/game/cities/site?x={x}&y={cy}')
        if site['valid'] and site['coastal']:
            coast = (x, cy)
            break
assert coast
relocate(observer, cities[observer], *coast)
save('coastal-city', {'player': observer, 'city': cities[observer], 'coordinates': coast})
buildings = []
for key in rules['buildings']:
    put_building(observer, key, 9)
    result = api(observer, '/game/build/upgrade', {'building': key, 'slot': 0})
    assert result['success'], (key, result.get('message'))
    buildings.append({key: result.get('message')})
    assert api(observer, '/game/build/cancel', {'building': key, 'slot': 0})['success']
    put_building(observer, key, 10)
save('05-every-building', buildings)
check(f'全部{len(buildings)}类建筑可实际升级并取消，军港造船厂在合法沿海验收', True)

market = []
for level in range(1, 11):
    put_building(observer, 'exchange', level)
    value = api(observer, '/game/market/exchange', {'fromRes': 'steel', 'toRes': 'oil', 'amount': 100})
    gain = value['exchange']['gain']
    assert gain == 50 + 4 * level, (level, value['exchange'])
    market.append({'level': level, 'gainPer100': gain})
save('06-market-exchange-every-level', market)
check('中转站1到10级兑换实得与标称百分比完全一致', True)
put_building(observer, 'exchange', 10)

scan = api(observer, '/game/world/scan', {})
put_tech(observer, 'recon_level', 10)
scan_after = api(observer, '/game/world/scan', {})
check('城防扫描半径由建筑决定，侦察科技不冒充额外视野', scan['radius'] == scan_after['radius'] == 13)
save('summary', {'database': DB, 'checks': CHECKS, 'buildingCount': len(buildings), 'technologyCount': len(research),
                 'players': players, 'coastalPosition': coast})
