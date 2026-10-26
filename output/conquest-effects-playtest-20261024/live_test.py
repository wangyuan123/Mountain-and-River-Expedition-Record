"""本机授权实战验收：只调整列出的测试账号，记录真实接口和数据库结果。"""
import base64
import hashlib
import hmac
import json
import pathlib
import subprocess
import time
import urllib.error
import urllib.request
import uuid

OUT = pathlib.Path(__file__).resolve().parent
BASE = 'http://localhost:8080/api'

def sql(query):
    return subprocess.check_output(['mysql', '-h', '127.0.0.1', '-u', 'root', '-N', '-B', '--raw', 'wargame', '-e', query], text=True).strip()

def quote(value):
    return "'" + str(value).replace('\\', '\\\\').replace("'", "''") + "'"

def save(name, data):
    (OUT / (name + '.json')).write_text(json.dumps(data, ensure_ascii=False, indent=2))

def token(player):
    username, version = sql(f'SELECT username,auth_version FROM players WHERE id={int(player)}').split('\t')
    b64 = lambda data: base64.urlsafe_b64encode(data).rstrip(b'=')
    now = int(time.time())
    header = b64(json.dumps({'alg': 'HS384', 'typ': 'JWT'}).encode())
    body = b64(json.dumps({'sub': username, 'playerId': player, 'authVersion': int(version), 'serverId': 'jiangsu-1', 'jti': str(uuid.uuid4()), 'iat': now, 'exp': now + 3600}).encode())
    unsigned = header + b'.' + body
    signature = b64(hmac.new(b'dev-only-do-not-use-in-prod-please-override-me-now!!', unsigned, hashlib.sha384).digest())
    return (unsigned + b'.' + signature).decode()

def api(player, path, data=None, city=None):
    headers = {'Authorization': 'Bearer ' + token(player), 'Content-Type': 'application/json'}
    if city is not None:
        headers['X-City-Id'] = str(city)
    req = urllib.request.Request(BASE + path, headers=headers, data=None if data is None else json.dumps(data).encode())
    try:
        with urllib.request.urlopen(req, timeout=90) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        return {'httpStatus': error.code, 'body': error.read().decode()}

def info():
    state = api(177, '/game/state')
    save('00-initial-state', state)
    print(json.dumps({'stateKeys': list(state), 'city': state.get('city'), 'marches': state.get('marches'), 'officers': state.get('officers')}, ensure_ascii=False))
    print(sql('SELECT id,player_id,status FROM city_state WHERE player_id IN (158,176,177); SELECT * FROM guild_members WHERE player_id IN (158,176,177); SELECT id,size,LENGTH(terrain_data) FROM world_map;'))
    terrain = sql('SELECT terrain_data FROM world_map WHERE id=1')
    span = int(len(terrain) ** .5)
    free = []
    occupied = {tuple(map(int, r.split('\t'))) for r in sql('SELECT x,y FROM player_cities').splitlines()}
    for y in range(384, 390):
        for x in range(2, 9):
            if terrain[y * span + x] == '0' and (x, y) not in occupied:
                free.append([x,y])
    save('00-free-nearby-land', free)
    print('free nearby land', free[:12])

def setup():
    tables = {'players': 'id IN (158,176,177)', 'player_cities': 'owner_id IN (158,176,177)',
              'officers': 'player_id IN (158,176,177)', 'army_units': 'player_id IN (158,176,177)',
              'resources': 'player_id IN (158,176,177)', 'guild_members': 'player_id IN (158,176,177)',
              'player_items': 'player_id IN (158,176,177)', 'officer_equipment': 'player_id IN (158,176,177)',
              'officer_equipment_progress': 'player_id IN (158,176,177)', 'city_state': 'player_id IN (158,176,177)'}
    for table, where in tables.items():
        # 不保存登录口令；只备份测试实际会改动的业务字段。
        columns = '*' if table != 'players' else 'id,city_pos_x,city_pos_y,pos_x,pos_y,military_rank,morale,tax,war_at,war_end_at,war_against_id,newbie_protection_ended,sortie_army'
        (OUT / ('before-' + table + '.tsv')).write_text(sql(f'SELECT {columns} FROM {table} WHERE {where}'))
    now = int(time.time()*1000)
    sql(f"START TRANSACTION; UPDATE players SET city_pos_x=4,city_pos_y=388,pos_x=4,pos_y=388,military_rank=17,newbie_protection_ended=1,version=version+1 WHERE id=176; UPDATE player_cities SET x=4,y=388,version=version+1 WHERE id=178; UPDATE players SET city_pos_x=4,city_pos_y=389,pos_x=4,pos_y=389,military_rank=17,newbie_protection_ended=1,version=version+1 WHERE id=158; UPDATE player_cities SET x=4,y=389,version=version+1 WHERE id=160; UPDATE resources SET food=1000000000,oil=1000000000,gold=1000000000,steel=1000000000,rare=1000000000,version=version+1 WHERE player_id IN (158,176,177); INSERT INTO guilds(name,leader_player_id,created_at) VALUES('驻防验收1024',176,{now}); SET @g=LAST_INSERT_ID(); INSERT INTO guild_members(guild_id,player_id,role,joined_at) VALUES(@g,176,'leader',{now}),(@g,158,'member',{now}); UPDATE army_units SET count=0,version=version+1 WHERE player_id IN (158,176); COMMIT;")
    for player, unit, count in [(176,'infantry',1000),(158,'infantry',20000),(158,'htank',10000)]:
        sql(f"INSERT INTO army_units(player_id,city_slot,type,count,version) VALUES({player},0,{quote(unit)},{count},0) ON DUPLICATE KEY UPDATE count={count},version=version+1")
    sql("INSERT INTO officers(player_id,city_slot,name,star,level,military,defense,logistics,knowledge,loyalty,role,skills) VALUES(176,0,'验收守将_1024',5,100,100,100,100,100,100,'commander','[{\"id\":\"bulwark\",\"lv\":3}]')")
    officer = int(sql("SELECT id FROM officers WHERE player_id=176 AND name='验收守将_1024'"))
    cfg = json.loads((OUT.parents[1]/'backend/src/main/resources/officer-equipment.json').read_text())
    for player, branch in [(177,'military'),(176,'defense')]:
        for slot in cfg['slots']:
            key='marshal_'+branch+'_'+slot['key']
            sql(f"INSERT INTO player_items(player_id,item_key,count,updated_at) VALUES({player},{quote(key)},1,{now}) ON DUPLICATE KEY UPDATE count=count+1,version=version+1")
    fixture={'attacker':177,'defender':176,'supporter':158,'attackerCity':179,'defenderCity':178,'supportCity':160,'attackerOfficer':50,'defenderOfficer':officer}
    save('fixture',fixture)
    for player,oid,branch in [(177,50,'military'),(176,officer,'defense')]:
        result=api(player,'/game/officer-equipment/equip-set',{'officerId':oid,'setKey':'marshal_'+branch})
        save(f'01-equip-{player}',result)
        print(player,'equip',result.get('success'),result.get('message'))
    sql(f"INSERT INTO officer_equipment_progress(player_id,progress_key,amount) VALUES(176,'prep/{officer}/weapon',5);")
    print('fixture',fixture)

def attributes():
    f=json.loads((OUT/'fixture.json').read_text())
    for player,oid in [(177,50),(176,f['defenderOfficer'])]:
        data=api(player,'/game/officer/attributes',{'officerId':oid})
        save(f'02-attributes-{player}',data)
        print(player,{k:data.get(k) for k in ['success','military','defense','logistics','knowledge','preparation','bonuses']})

def game_now():
    return int(api(177, '/game/state')['serverTime'])

def dispatch(player,action,army,commander=None,target=178,x=4,y=388,origin=None):
    data=api(player,'/game/world/dispatch',{'targetKind':'player','targetId':target,'action':action,'army':army,'commanderId':commander,'abandonNewbieProtection':True,'targetX':x,'targetY':y},city=origin)
    save(f'dispatch-{action}-{int(time.time()*1000)}',data)
    if not data.get('success'):
        raise RuntimeError(data)
    march=int(sql(f'SELECT MAX(id) FROM marches WHERE player_id={player}'))
    print('dispatch',player,action,march)
    return march

def arrive(player,march):
    now=game_now()
    was_returning=sql(f'SELECT returning FROM marches WHERE id={march}') == '1'
    sql(f'UPDATE marches SET arrive_at={now-1},start_at={now-60001},version=version+1 WHERE id={march} AND battle_id IS NULL')
    api(player,'/game/state')
    for attempt in range(50):
        row=sql(f'SELECT COALESCE(battle_id,0),stationed,returning FROM marches WHERE id={march}')
        if not row or (not was_returning and row != '0\t0\t0'):
            return row
        time.sleep(.5)
    raise RuntimeError('arrival did not process '+str(march))

def battle(march,label):
    rounds=[]
    for i in range(70):
        state=api(177,f'/game/world/battle?marchId={march}')
        rounds.append(state)
        if state.get('finished') or state.get('ended') or state.get('httpStatus'):
            break
        response=api(177,f'/game/world/battle/command?marchId={march}',{})
        rounds.append(response)
        if response.get('finished') or response.get('ended') or response.get('httpStatus'):
            break
        if not sql(f'SELECT id FROM battle_sessions WHERE march_id={march}'):
            break
    save(label,rounds)
    print(label,'steps',len(rounds),'last keys',list(rounds[-1]))
    reports=json.loads(sql(f"SELECT JSON_ARRAYAGG(JSON_OBJECT('id',id,'playerId',player_id,'data',CAST(data AS JSON))) FROM scout_reports WHERE player_id IN (177,176) AND type='battle' AND id>(SELECT COALESCE(MAX(id),0)-4 FROM scout_reports)"))
    save(label+'-reports',reports)
    return rounds[-1]

def prepare_battle(own=1000):
    now=game_now()
    sql(f"UPDATE army_units SET count={own},version=version+1 WHERE player_id=176 AND type='infantry' AND city_slot=0; UPDATE players SET morale=70,tax=30,last_tick={now},war_at={now-1000},war_end_at={now+172800000},war_against_id=176,version=version+1 WHERE id=177; UPDATE players SET morale=70,tax=30,last_tick={now},version=version+1 WHERE id=176; UPDATE resources SET food=1000000000,oil=1000000000,version=version+1 WHERE player_id IN (158,176,177);")

def run_comparisons():
    f=json.loads((OUT/'fixture.json').read_text())
    print('Use existing formal declaration; advance this test pair only.')
    # 相同双方基础属性和兵力，仅依次改变套装、技能；不修改战斗内数值或结果。
    for label,gear,skills in [('04-no-gear-no-skill',False,[]),('05-gear-no-skill',True,[]),('06-gear-and-skill',True,[{'id':'frenzy','lv':5},{'id':'pierce','lv':5}])]:
        if (OUT/(label+'.json')).exists():
            continue
        prepare_battle(20000)
        if not gear:
            result=api(177,'/game/officer-equipment/unequip-all',{'officerId':50})
        else:
            result=api(177,'/game/officer-equipment/equip-set',{'officerId':50,'setKey':'marshal_military'})
        assert result.get('success') or (not gear and result.get('message') == '该装备未穿戴'),result.get('message')
        sql(f"UPDATE officers SET skills={quote(json.dumps(skills))},role='idle',version=version+1 WHERE id=50")
        march=dispatch(177,'plunder',{'infantry':20000},50)
        arrive(177,march)
        snapshot=sql(f'SELECT JSON_OBJECT("attackerMil",attacker_commander_mil,"attackerDef",attacker_commander_def,"attackerSkills",attacker_skills,"defenderMil",defender_commander_mil,"defenderDef",defender_commander_def,"defenderSkills",defender_skills,"initialAttacker",initial_attacker,"initialDefender",initial_defender) FROM battle_sessions WHERE march_id={march}')
        save(label+'-snapshot',json.loads(snapshot))
        battle(march,label)
        if sql(f'SELECT id FROM marches WHERE id={march}'):
            arrive(177,march)
    print('comparisons completed')

def resume_comparisons():
    existing=sql("SELECT id FROM marches WHERE player_id=177 AND action='plunder' ORDER BY id DESC LIMIT 1")
    if existing:
        march=int(existing)
        snapshot=sql(f'SELECT JSON_OBJECT("attackerMil",attacker_commander_mil,"attackerDef",attacker_commander_def,"attackerSkills",attacker_skills,"defenderMil",defender_commander_mil,"defenderDef",defender_commander_def,"defenderSkills",defender_skills,"initialAttacker",initial_attacker,"initialDefender",initial_defender) FROM battle_sessions WHERE march_id={march}')
        if snapshot:
            save('04-no-gear-no-skill-snapshot',json.loads(snapshot))
        if not (OUT/'04-no-gear-no-skill.json').exists():
            battle(march,'04-no-gear-no-skill')
        if sql(f'SELECT id FROM marches WHERE id={march}'):
            arrive(177,march)
    run_comparisons()

def run_garrison():
    prepare_battle(1000)
    no_support=dispatch(177,'plunder',{'htank':1000})
    arrive(177,no_support)
    battle(no_support,'07-without-garrison')
    if sql(f'SELECT id FROM marches WHERE id={no_support}'):
        arrive(177,no_support)
    prepare_battle(1000)
    reinforcement=dispatch(158,'reinforce',{'htank':10000})
    print('reinforcement arrival',arrive(158,reinforcement))
    save('08-received-garrison',api(176,'/game/state'))
    attack=dispatch(177,'plunder',{'htank':1000})
    arrive(177,attack)
    snap=sql(f'SELECT JSON_OBJECT("initialDefender",initial_defender,"support",defender_support,"sources",defender_equipment_sources) FROM battle_sessions WHERE march_id={attack}')
    save('08-garrison-battle-snapshot',json.loads(snap))
    save('08-recall-during-battle',api(158,'/game/world/cancel-march',{'marchId':reinforcement}))
    battle(attack,'08-with-garrison')
    save('08-garrison-post-battle',{'march':sql(f'SELECT id,army,stationed,returning FROM marches WHERE id={reinforcement}'),'supporter':api(158,'/game/state'),'receiver':api(176,'/game/state')})
    if sql(f'SELECT id FROM marches WHERE id={attack}'):
        arrive(177,attack)
    recall=api(158,'/game/world/cancel-march',{'marchId':reinforcement})
    save('09-recall-after-battle',recall)
    print('recall after battle',recall.get('success'),recall.get('message'))
    arrive(158,reinforcement)
    save('09-garrison-returned',api(158,'/game/state'))

def run_conquest():
    f=json.loads((OUT/'fixture.json').read_text())
    prepare_battle(1000)
    now=game_now()
    sql(f"UPDATE players SET morale=0,tax=100,last_tick={now},version=version+1 WHERE id=176; UPDATE officers SET loyalty=0,role='commander',version=version+1 WHERE id={f['defenderOfficer']};")
    save('10-pre-capture-attributes',api(176,'/game/officer/attributes',{'officerId':f['defenderOfficer']}))
    march=dispatch(177,'conquer',{'htank':100000},50)
    arrive(177,march)
    battle(march,'10-conquest-capture')
    save('10-post-capture-attributes',api(177,'/game/officer/attributes',{'officerId':f['defenderOfficer']}))
    save('10-city-overview',api(177,'/game/cities'))
    save('10-original-owner-access',api(176,'/game/state',city=178))
    save('10-capture-db',{'city':sql('SELECT id,owner_id,city_slot,name,x,y FROM player_cities WHERE id=178'),'officer':sql(f"SELECT id,player_id,city_slot,name,role,loyalty,skills FROM officers WHERE id={f['defenderOfficer']}"),'preparation':sql(f"SELECT player_id,progress_key,amount FROM officer_equipment_progress WHERE progress_key LIKE 'prep/{f['defenderOfficer']}/%'")})
    if sql(f'SELECT id FROM marches WHERE id={march}'):
        arrive(177,march)

def run_fixed_validation():
    recovered=api(177,'/game/officer/attributes',{'officerId':51})
    save('11-recovered-captive-attributes',recovered)
    assert recovered['preparation'] == {'weapon':5} and recovered['defense'] == 277,recovered
    print('historical captive recovered',recovered['defense'],recovered['preparation'])
    target=int(sql('SELECT id FROM player_cities WHERE owner_id=176 AND city_slot=0'))
    assert not sql('SELECT id FROM player_cities WHERE x=5 AND y=388')
    save('11-supporter-name-before',sql('SELECT id,display_name,display_name_key FROM players WHERE id=158'))
    now=game_now()
    sql(f"UPDATE player_cities SET x=5,y=388,version=version+1 WHERE id={target}; UPDATE players SET city_pos_x=5,city_pos_y=388,pos_x=5,pos_y=388,version=version+1 WHERE id=176; UPDATE players SET display_name='驻防验收统帅',display_name_key='驻防验收统帅',version=version+1 WHERE id=158; INSERT INTO officers(player_id,city_slot,name,star,level,military,defense,logistics,knowledge,loyalty,role,skills) VALUES(176,0,'复验守将_1024',5,100,100,100,100,100,100,'commander','[{{\"id\":\"bulwark\",\"lv\":3}}]');")
    oid=int(sql("SELECT id FROM officers WHERE player_id=176 AND name='复验守将_1024'"))
    cfg=json.loads((OUT.parents[1]/'backend/src/main/resources/officer-equipment.json').read_text())
    for slot in cfg['slots']:
        sql(f"INSERT INTO player_items(player_id,item_key,count,updated_at) VALUES(176,{quote('marshal_defense_'+slot['key'])},1,{now}) ON DUPLICATE KEY UPDATE count=count+1,version=version+1")
    result=api(176,'/game/officer-equipment/equip-set',{'officerId':oid,'setKey':'marshal_defense'})
    assert result.get('success'),result.get('message')
    sql(f"INSERT INTO officer_equipment_progress(player_id,progress_key,amount) VALUES(176,'prep/{oid}/weapon',5)")
    prepare_battle(1000)
    reinforcement=dispatch(158,'reinforce',{'htank':100},target=target,x=5,y=388)
    arrive(158,reinforcement)
    received=api(176,'/game/state')
    save('12-fixed-received-garrison',received)
    names=[m.get('senderName') for m in received['world']['reinforcements']]
    print('garrison sender names',names)
    assert '驻防验收统帅' in names,names
    now=game_now()
    sql(f"UPDATE players SET morale=0,tax=100,last_tick={now},version=version+1 WHERE id=176; UPDATE officers SET loyalty=0,version=version+1 WHERE id={oid}")
    before=api(176,'/game/officer/attributes',{'officerId':oid})
    save('12-fixed-pre-capture-attributes',before)
    attack=dispatch(177,'conquer',{'htank':100000},50,target=target,x=5,y=388)
    arrive(177,attack)
    battle(attack,'12-fixed-conquest')
    after=api(177,'/game/officer/attributes',{'officerId':oid})
    save('12-fixed-post-capture-attributes',after)
    assert before['defense'] == after['defense'] == 277,(before,after)
    assert after['preparation'] == {'weapon':5}
    assert api(176,'/game/officer/attributes',{'officerId':oid}).get('success') is False
    save('12-fixed-owner-access',api(176,'/game/state',city=target))
    if sql(f'SELECT id FROM marches WHERE id={attack}'):
        arrive(177,attack)
    save('12-fixed-final-city',api(177,'/game/state',city=target))
    print('fixed conquest',attack,'city',target,'captive',oid,'defense preserved',after['defense'])

if __name__ == '__main__':
    import sys
    globals()[sys.argv[1] if len(sys.argv) > 1 else 'info']()
