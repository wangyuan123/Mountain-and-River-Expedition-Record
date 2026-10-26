"""空白独立库的真实 HTTP 协防验收；仅操控本脚本新建的合成测试账号。"""
import json
import pathlib
import subprocess
import sys
import time
import urllib.error
import urllib.request

OUT = pathlib.Path(__file__).resolve().parent
BASE = 'http://127.0.0.1:8082/api'
DB = 'wargame_joint_acceptance_20261024'
TOKENS = {}

def sql(query):
    return subprocess.check_output(['mysql', '-h', '127.0.0.1', '-u', 'root', '-N', '-B', '--raw', DB, '-e', query], text=True).strip()

def quote(value):
    return "'" + str(value).replace('\\', '\\\\').replace("'", "''") + "'"

def save(name, data):
    (OUT / (name + '.json')).write_text(json.dumps(data, ensure_ascii=False, indent=2))

def api(player, path, data=None, expected=200):
    headers = {'Content-Type': 'application/json'}
    if player is not None:
        headers['Authorization'] = 'Bearer ' + TOKENS[player]
    req = urllib.request.Request(BASE + path, headers=headers, data=None if data is None else json.dumps(data).encode())
    try:
        with urllib.request.urlopen(req, timeout=120) as response:
            status, result = response.status, json.load(response)
    except urllib.error.HTTPError as error:
        status = error.code
        result = json.loads(error.read())
    assert status == expected, (path, status, result)
    return result

def now():
    return int(sql('SELECT game_time FROM game_clock WHERE singleton_id=1'))

def dispatch(player, city, action, army, officer=None):
    api(player, '/game/world/dispatch', {'targetKind': 'player', 'targetId': city, 'action': action, 'army': army,
        'commanderId': officer, 'carryRes': {}, 'abandonNewbieProtection': True})
    return int(sql(f'SELECT MAX(id) FROM marches WHERE player_id={player}'))

def arrive(player, march):
    # 只缩短合成账号行军等待；不改游戏时钟、不改战斗数据。
    moment = now()
    sql(f'UPDATE marches SET start_at={moment-20000},arrive_at={moment-1},base_duration_ms=20000,version=version+1 WHERE id={march}')
    api(player, '/game/state')
    action, owner, target = sql(f'SELECT action,support_owner_id,target_id FROM marches WHERE id={march}').split('\t')
    if action == 'reinforce':
        # 关闭周期调度时，通过接收设置的正式时间轴结算入口处理已到期支援。
        api(int(owner), '/game/cities/garrison', {'cityId':int(target), 'allowed':True})

if '--resume' not in sys.argv:
    players = []
    for name in ['joint_attack', 'joint_defend', 'joint_support']:
        existing = sql(f'SELECT id FROM players WHERE username={quote(name)}')
        if existing:
            result = api(None, '/auth/login', {'username': name, 'password': 'Synthetic-Joint-Only-20261024!', 'serverId': 'jiangsu-1'})
            result['playerId'] = int(existing)
        else:
            result = api(None, '/auth/register', {'username': name, 'password': 'Synthetic-Joint-Only-20261024!',
                'agreementVersion': '2026-09-28-v1', 'serverId': 'jiangsu-1'})
        player = int(result['playerId']); TOKENS[player] = result['token']; players.append(player)
        api(player, '/game/state')
    a, d, s = players
    ids = ','.join(map(str, players))
    cities = {int(row.split('\t')[0]): int(row.split('\t')[1]) for row in sql(f'SELECT owner_id,id FROM player_cities WHERE owner_id IN ({ids}) AND city_slot=0').splitlines()}
    world = int(sql(f'SELECT world_id FROM player_cities WHERE id={cities[a]}'))
    # 单一平原验收地图，坐标仅涉及本库的新建账号。
    # 地图全局边长为四象限，持久地形只保存一个象限。
    size = int(sql(f'SELECT size FROM world_map WHERE id={world}')) // 2
    sql(f"UPDATE world_map SET terrain_data=REPEAT('0',{size*size}) WHERE id={world};")
    for player, x in [(a,10),(d,20),(s,30)]:
        sql(f"UPDATE players SET military_rank=17,newbie_protection_ended=1,sortie_army='{{\"htank\":1}}',city_pos_x={x},city_pos_y=10,pos_x={x},pos_y=10,version=version+1 WHERE id={player}; UPDATE player_cities SET x={x},y=10,version=version+1 WHERE id={cities[player]}; UPDATE resources SET food=1000000000,steel=1000000000,oil=1000000000,rare=1000000000,gold=1000000000,version=version+1 WHERE player_id={player}; UPDATE army_units SET count=0,version=version+1 WHERE player_id={player};")
    for player, unit, count in [(a,'infantry',600000),(d,'htank',200000),(s,'htank',1000000)]:
        sql(f"INSERT INTO army_units(player_id,city_slot,type,count,version) VALUES({player},0,{quote(unit)},{count},0) ON DUPLICATE KEY UPDATE count={count},version=version+1")
    for player, name, mil, defense in [(a,'测试攻将',100,100),(d,'测试守将',0,100),(s,'援军甲',0,0),(s,'援军乙',200,100)]:
        role = 'commander' if player == d else 'idle'
        sql(f"INSERT INTO officers(player_id,city_slot,name,star,level,military,defense,logistics,knowledge,loyalty,role,skills) VALUES({player},0,{quote(name)},5,100,{mil},{defense},100,100,100,{quote(role)},'[]')")
    officers = {name: int(oid) for oid,name in [r.split('\t') for r in sql("SELECT id,name FROM officers WHERE name IN ('测试攻将','测试守将','援军甲','援军乙')").splitlines()]}
    sql(f"INSERT INTO guilds(name,leader_player_id,created_at) VALUES('联合防御验收',{d},{now()}) ON DUPLICATE KEY UPDATE name=VALUES(name); SET @g=(SELECT id FROM guilds WHERE name='联合防御验收'); INSERT IGNORE INTO guild_members(guild_id,player_id,role,joined_at) VALUES(@g,{d},'leader',1),(@g,{s},'member',1); UPDATE players SET war_against_id={d},war_at=1,war_end_at={now()+3600000},version=version+1 WHERE id={a};")
    # 来源科技故意不同：协防场上必须只使用接收方公共科技。
    sql(f"INSERT INTO technologies(player_id,type,level,version) VALUES({s},'attack_tech',10,0) ON DUPLICATE KEY UPDATE level=10,version=version+1")
    fixture = {'players': players, 'cities': cities, 'officers': officers, 'world': world}
    save('fixture', fixture)
    closed = api(d, '/game/cities')
    save('01-default-reception', closed)
    api(d, '/game/cities/garrison', {'cityId':cities[d], 'allowed':True})
    supports = []
    for name in ['援军甲','援军乙']:
        march = dispatch(s,cities[d],'reinforce',{'htank':500000},officers[name]); arrive(s,march); supports.append(march)
else:
    fixture = json.loads((OUT / 'fixture.json').read_text())
    players = fixture['players']; a, d, s = players
    cities = {int(k):v for k,v in fixture['cities'].items()}; officers = fixture['officers']
    for player, name in zip(players, ['joint_attack', 'joint_defend', 'joint_support']):
        result = api(None, '/auth/login', {'username': name, 'password': 'Synthetic-Joint-Only-20261024!', 'serverId': 'jiangsu-1'})
        TOKENS[player] = result['token']
    supports = list(map(int, sql(f"SELECT id FROM marches WHERE player_id={s} AND action='reinforce' AND returning=0 ORDER BY id").splitlines()))
    assert len(supports) == 2
    # 接续已经派出的两支部队；不删除行军、不重建夹具、不重置库存。
    api(d, '/game/cities/garrison', {'cityId':cities[d], 'allowed':True})
assert int(sql(f'SELECT COUNT(*) FROM marches WHERE id IN ({supports[0]},{supports[1]}) AND stationed=1')) == 2
attack = dispatch(a,cities[d],'plunder',{'infantry':600000},officers['测试攻将']); arrive(a,attack)
view = api(a, f'/game/world/battle?marchId={attack}'); save('02-attack-view',view)
assert view['rulesVersion'] == 'joint-defense-v1-20261024'
assert sum(view['defenderArmy'].values()) == 1000001, view['defenderArmy']
assert view['defenderCommandBonus'] == {} and view['defenderTech'] == {}
assert 'equipmentSources' not in view and 'jointDefense' not in view
own = api(d, f'/game/world/battle?marchId={attack}'); save('03-defend-view',own)
assert len(own['unitContexts']) == 3
api(d, '/game/cities/garrison', {'cityId':cities[d], 'allowed':False})
blocked = api(s,'/game/world/cancel-march',{'marchId':supports[0]},expected=400); save('04-locked-recall',blocked)
last = view
started = time.monotonic()
for step in range(40):
    result = api(a,f'/game/world/battle/command?marchId={attack}',{'orders':{},'round':last['round']})
    save(f'round-{step+1:02}',{k:v for k,v in result.items() if k!='state'})
    last = result
    if result.get('finished'): break
assert last.get('finished'), '战斗未结束'
rows = last['formationContributions']
assert sum(row['initial'] for row in rows if row['kind']!='attack') == 1000001
assert all(row['initial']==row['survivors']+row['losses'] for row in rows)
assert all(row['wounded']<=row['losses'] for row in rows)
ally = [row for row in rows if row['kind']=='support']
assert len(ally)==2 and all(row['damage']>0 for row in ally), ally
assert ally[1]['damage'] > ally[0]['damage'], ally
assert int(sql(f'SELECT count FROM army_units WHERE player_id={d} AND type=\'htank\'')) >= 199999
reports = {player: api(player,'/game/reports') for player in players}
for player, result in reports.items(): save(f'05-reports-{player}',result)
summary = {'rulesVersion':view['rulesVersion'],'defenderInitial':1000001,'localSortie':1,'garrisonInitial':1000000,
    'rounds':last['round'],'elapsedSeconds':round(time.monotonic()-started,3),'attackerWin':last['result']['win'],
    'contributions': rows,'checks':['全部驻军超过本城上限参战','同兵种保留两名援军军官','来源科技不泄露','交战驻军不能撤回',
        '关闭接收不排除已驻军','两支援军分别造成伤害','城内未出战库存保留','三方生成战报','兵力与伤兵守恒']}
save('summary',summary)
print(json.dumps(summary,ensure_ascii=False))
