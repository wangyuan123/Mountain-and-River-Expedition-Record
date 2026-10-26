"""修复测试夹具造成的三城重叠；默认只规划，--apply 才提交坐标修复。"""
import json
import math
import pathlib
import subprocess
import sys
import uuid

OUT = pathlib.Path(__file__).resolve().parent
EXPECTED = {160: (158, 0, 4, 389), 178: (177, 1, 4, 388), 180: (177, 2, 5, 388)}


class Database:
    """保持同一MySQL会话，让锁定、选址复验和三城更新在一个事务内完成。"""
    def __init__(self):
        self.process = subprocess.Popen(['mysql', '-h', '127.0.0.1', '-u', 'root', '-N', '-B', '--raw', '--unbuffered', 'wargame'],
                                        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, bufsize=1)

    def query(self, statement):
        marker = 'END_' + uuid.uuid4().hex
        self.process.stdin.write(statement.rstrip(';') + "; SELECT '" + marker + "';\n")
        self.process.stdin.flush()
        rows = []
        for line in self.process.stdout:
            line = line.rstrip('\n')
            if line == marker:
                return rows
            rows.append(line)
        raise RuntimeError(self.process.stderr.read())

    def close(self):
        self.process.stdin.close()
        self.process.wait(timeout=10)


def sea(mask, x, y):
    """与WorldTerrainService.sea一致：400格存档镜像到800格地图。"""
    base = math.isqrt(len(mask))
    mx = 2 * base - 1 - x if base <= x < 2 * base else x
    my = 2 * base - 1 - y if base <= y < 2 * base else y
    return mask[my * base + mx] == '1'


def coastal(mask, x, y, size):
    return any(0 <= xx < size and 0 <= yy < size and sea(mask, xx, yy)
               for xx, yy in [(x + i, y - 1) for i in range(2)] + [(x + i, y + 2) for i in range(2)]
               + [(x - 1, y + i) for i in range(2)] + [(x + 2, y + i) for i in range(2)])


def cells(x, y, span):
    return {(xx, yy) for xx in range(x, x + span) for yy in range(y, y + span)}


def snapshot(db):
    world = json.loads(db.query("SELECT JSON_OBJECT('id',id,'size',size,'mask',terrain_data) FROM world_map WHERE id=1")[0])
    cities = [json.loads(row) for row in db.query("SELECT JSON_OBJECT('id',c.id,'owner',c.owner_id,'slot',c.city_slot,'name',c.name,'x',c.x,'y',c.y,'world',c.world_id,'legacyNaval',c.legacy_naval,'surrender',c.surrender_offer_id,'readyAt',c.ready_at,'span',IF(p.id IS NULL,1,2)) FROM player_cities c LEFT JOIN players p ON p.id=c.owner_id WHERE c.world_id=1")]
    features = []
    for table in ['npc_cities', 'bandits', 'wild_tiles']:
        features.extend(tuple(map(int, row.split('\t'))) for row in db.query(f'SELECT x,y FROM {table} WHERE world_id=1'))
    players = [json.loads(row) for row in db.query("SELECT JSON_OBJECT('id',id,'mainX',city_pos_x,'mainY',city_pos_y,'viewX',pos_x,'viewY',pos_y,'activeCity',active_city_id) FROM players WHERE id IN (158,177) ORDER BY id")]
    return world, cities, features, players


def plan(world, cities, features):
    """完整四格陆地、现有实体避让；另留城市间距，保留三座城的沿海使用条件。"""
    occupied = set(features)
    anchors = []
    for city in cities:
        span = city['span']
        x, y = min(city['x'], world['size'] - span), min(city['y'], world['size'] - span)
        occupied.update(cells(x, y, span))
        anchors.append((x, y))
    proposed = []
    for city_id, (owner, slot, old_x, old_y) in EXPECTED.items():
        city = next(city for city in cities if city['id'] == city_id)
        assert (city['owner'], city['slot'], city['x'], city['y']) == (owner, slot, old_x, old_y), city
        assert city['surrender'] is None, '归降中的城市不能移动'
        candidates = []
        for y in range(330, 399):
            for x in range(2, 160):
                footprint = cells(x, y, 2)
                if occupied.intersection(footprint) or any(sea(world['mask'], xx, yy) for xx, yy in footprint):
                    continue
                if not coastal(world['mask'], x, y, world['size']):
                    continue
                if any(max(abs(x - ax), abs(y - ay)) < 5 for ax, ay in anchors):
                    continue
                candidates.append(((x - old_x) ** 2 + (y - old_y) ** 2, x, y))
        assert candidates, '附近没有符合四格和沿海条件的空地'
        _, x, y = min(candidates)
        proposed.append({'cityId': city_id, 'ownerId': owner, 'slot': slot, 'name': city['name'],
                         'from': [old_x, old_y], 'to': [x, y], 'coastal': True})
        occupied.update(cells(x, y, 2))
        anchors.append((x, y))
    return proposed


db = Database()
try:
    db.query('SET SESSION innodb_lock_wait_timeout=10')
    if '--apply' in sys.argv:
        db.query('SET TRANSACTION ISOLATION LEVEL READ COMMITTED; START TRANSACTION')
        db.query('SELECT id FROM players WHERE id IN (158,177) ORDER BY id FOR UPDATE')
        db.query('SELECT id FROM world_map WHERE id=1 FOR UPDATE')
        db.query('SELECT id FROM player_cities WHERE id IN (160,178,180) ORDER BY id FOR UPDATE')
    world, cities, features, players = snapshot(db)
    proposed = plan(world, cities, features)
    # 同正常迁城：无外派、来袭、驻防、返程和活动战斗，才可修复来源坐标。
    assert not db.query("SELECT id FROM marches WHERE (player_id=158 AND city_slot=0) OR (player_id=177 AND city_slot IN (1,2)) OR (target_kind='player' AND target_id IN ('160','178','180') AND COALESCE(returning,0)=0)"), '仍有相关行军'
    assert not db.query("SELECT id FROM battle_sessions WHERE target_kind='player' AND target_id IN ('160','178','180')"), '仍在交战'
    assert not db.query('SELECT id FROM incoming_marches WHERE target_player_id=158'), '仍有主城日寇来袭'
    record = {'database': 'wargame', 'beforeCities': [city for city in cities if city['id'] in EXPECTED],
              'beforePlayers': players, 'moves': proposed, 'applied': False}
    (OUT / 'city-relocation-plan.json').write_text(json.dumps(record, ensure_ascii=False, indent=2))
    print(json.dumps({'moves': proposed, 'mode': 'apply' if '--apply' in sys.argv else 'plan'}, ensure_ascii=False), flush=True)
    if '--apply' in sys.argv:
        for move in proposed:
            x, y = move['to']
            # 锁定目标附近的占用行后复验；边界查询覆盖所有可能与2×2新占地相交的城市。
            for table in ['player_cities', 'npc_cities', 'bandits', 'wild_tiles']:
                margin = 1 if table == 'player_cities' else 0
                nearby = db.query(f'SELECT id FROM {table} WHERE world_id=1 AND x BETWEEN {x-margin} AND {x+1} AND y BETWEEN {y-margin} AND {y+1} FOR UPDATE')
                assert not nearby, (table, nearby)
            changed = db.query(f"UPDATE player_cities SET x={x},y={y},version=version+1 WHERE id={move['cityId']} AND owner_id={move['ownerId']} AND city_slot={move['slot']}; SELECT ROW_COUNT()")
            assert changed == ['1'], changed
            # 主城冗余坐标和当前地图视角按正常迁城同步；分城不覆盖其城主主城。
            owner, city_id, slot = move['ownerId'], move['cityId'], move['slot']
            db.query(f"UPDATE players SET city_pos_x=IF({slot}=0,{x},city_pos_x),city_pos_y=IF({slot}=0,{y},city_pos_y),pos_x=IF(active_city_id={city_id} OR (active_city_id IS NULL AND {slot}=0),{x},pos_x),pos_y=IF(active_city_id={city_id} OR (active_city_id IS NULL AND {slot}=0),{y},pos_y),version=version+1 WHERE id={owner}")
        _, after_cities, after_features, after_players = snapshot(db)
        for move in proposed:
            city = next(c for c in after_cities if c['id'] == move['cityId'])
            assert [city['x'], city['y']] == move['to']
            occupied = cells(city['x'], city['y'], 2)
            assert not occupied.intersection(set(after_features))
            assert all(not occupied.intersection(cells(min(c['x'], world['size']-c['span']), min(c['y'], world['size']-c['span']), c['span']))
                       for c in after_cities if c['id'] != city['id'])
        db.query('COMMIT')
        record.update({'applied': True, 'afterCities': [city for city in after_cities if city['id'] in EXPECTED], 'afterPlayers': after_players,
                       'checks': ['三城完整四格陆地', '同战场沿海落点', '与城市及NPC野地不重叠', '主城坐标与当前视角同步', '未扣道具或改动兵力资源']})
        (OUT / 'city-relocation-result.json').write_text(json.dumps(record, ensure_ascii=False, indent=2))
        print('三城坐标修复已提交', flush=True)
finally:
    # 意外退出时关闭会话由MySQL回滚未提交事务，不留下半迁移。
    db.close()
