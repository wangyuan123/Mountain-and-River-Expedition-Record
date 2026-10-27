"""本机排行榜只读验收；复用授权试玩账号，不修改游戏资产或账号凭据。"""
import importlib.util
import json
import pathlib
import time
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = pathlib.Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location(
    "local_fixture", ROOT / "output/conquest-effects-playtest-20261024/live_test.py")
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)
token = fixture.token(177)

# 以数据库公开字段作为独立对照，避免只验证接口返回的数据内部是否自洽。
eligible = """game_initialized = 1 AND account_status = 'ACTIVE'
    AND COALESCE(disabled, 0) = 0
    AND (UPPER(COALESCE(ban_status, 'NORMAL')) <> 'BANNED'
         OR (banned_until > 0 AND banned_until <= UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000))"""
expected_players = [json.loads(line) for line in fixture.sql("""
    SELECT JSON_OBJECT('id', id, 'prestige', COALESCE(prestige, 0),
        'militaryRank', COALESCE(military_rank, 1)) FROM players WHERE """ + eligible).splitlines()]
expected_guilds = [json.loads(line) for line in fixture.sql("""
    SELECT JSON_OBJECT('id', g.id, 'prestige', SUM(p.prestige), 'members', COUNT(p.id))
    FROM guilds g JOIN guild_members m ON m.guild_id = g.id
    JOIN (SELECT id, COALESCE(prestige, 0) AS prestige FROM players WHERE """ + eligible + """) p
    ON p.id = m.player_id GROUP BY g.id""").splitlines()]
my_guild = fixture.sql("SELECT guild_id FROM guild_members WHERE player_id = 177")


def request(path, authenticated=True):
    headers = {"Authorization": "Bearer " + token} if authenticated else {}
    req = urllib.request.Request("http://localhost:8080/api" + path, headers=headers)
    start = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=60) as response:
            status, data = response.status, json.load(response)
    except urllib.error.HTTPError as error:
        status, data = error.code, json.loads(error.read())
    return status, data, round((time.monotonic() - start) * 1000, 1)


results = []
for kind, metric in [("players", "prestige"), ("players", "militaryRank"),
                     ("players", "formationPower"), ("players", "overallPower"),
                     ("guilds", "prestige"), ("guilds", "members")]:
    path = "/game/leaderboard?type=" + kind + "&metric=" + metric
    status, first, elapsed = request(path)
    assert status == 200, (kind, metric, status, first)
    entries, timings = list(first["entries"]), [elapsed]
    for page in range(2, first["totalPages"] + 1):
        code, data, elapsed = request(path + "&page=" + str(page))
        assert code == 200, (metric, code, data)
        assert data["mine"] == first["mine"], ("self rank changed", metric)
        entries.extend(data["entries"])
        timings.append(elapsed)
    assert len(entries) == first["total"]
    expected = expected_players if kind == "players" else expected_guilds
    assert {item["id"] for item in entries} == {item["id"] for item in expected}
    for expected_item in expected:
        actual = next(item for item in entries if item["id"] == expected_item["id"])
        assert all(actual[field] == value for field, value in expected_item.items()), (actual, expected_item)
    mine_id = 177 if kind == "players" else (int(my_guild) if my_guild else None)
    assert first["mine"] == next((item for item in entries if item["id"] == mine_id), None)
    assert [item["rank"] for item in entries] == list(range(1, len(entries) + 1))
    assert len({item["id"] for item in entries}) == len(entries)
    secondary = (("militaryRank" if metric == "prestige" else "prestige")
                 if kind == "players" else ("members" if metric == "prestige" else "prestige"))
    def key(item):
        if metric in ("formationPower", "overallPower"):
            return -item[metric], item["id"]
        return -item[metric], -item[secondary], item["id"]
    assert sorted(entries, key=key) == entries
    forbidden = {"cityX", "cityY", "cityPosX", "cityPosY", "posX", "posY",
                 "army", "resources", "passwordHash", "username", "email"}
    assert all(not (forbidden & set(item)) for item in entries)
    for page, expected in [(-2, 1), (0, 1), (2147483647, first["totalPages"])]:
        code, data, _ = request(path + "&page=" + str(page))
        assert code == 200 and data["page"] == expected
    record = {"type": kind, "metric": metric, "status": status, "total": first["total"],
              "pages": first["totalPages"], "mine": first["mine"], "pageMs": timings}
    (OUT / (kind + "-" + metric + ".json")).write_text(json.dumps(
        {"firstPage": first, "allEntries": entries}, ensure_ascii=False, indent=2))
    if metric in ("formationPower", "overallPower"):
        peer = next(item for item in entries if item["id"] != 177)
        code, profile, _ = request("/game/friends/" + str(peer["id"]))
        assert code == 200, profile
        assert profile["formationPower"] == peer["formationPower"], (peer, profile)
        assert profile["overallPower"] == peer["overallPower"], (peer, profile)
        assert profile.get("cityX") is None and profile.get("cityY") is None
        record.update(profileMatches=True, coordinatesHidden=True)
    results.append(record)
for query in ["type=bogus", "type=players&metric=members", "type=guilds&metric=militaryRank",
              "page=abc", "page=2147483648"]:
    status, data, elapsed = request("/game/leaderboard?" + query)
    results.append({"query": query, "status": status, "body": data, "ms": elapsed})
status, _, _ = request("/game/leaderboard", False)
results.append({"noAuthentication": True, "status": status})
(OUT / "live-results.json").write_text(json.dumps(results, ensure_ascii=False, indent=2))
print(json.dumps(results, ensure_ascii=False, indent=2))
assert all(item["status"] == 400 for item in results if "query" in item)
assert results[-1]["status"] == 401
