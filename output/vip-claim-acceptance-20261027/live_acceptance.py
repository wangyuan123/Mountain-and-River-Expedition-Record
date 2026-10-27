"""只读核对浏览器 VIP 领取结果；不重置领取资格，不修改账号或资产。"""
import datetime
import importlib.util
import json
import pathlib
import sys
from collections import Counter
from zoneinfo import ZoneInfo

OUT = pathlib.Path(__file__).resolve().parent
ROOT = OUT.parents[1]
spec = importlib.util.spec_from_file_location(
    "fixture", ROOT / "output/conquest-effects-playtest-20261024/live_test.py")
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)
PLAYER = 5


def snapshot(label):
    """仅保存奖励相关字段；鉴权凭据保持在内存中。"""
    status = fixture.api(PLAYER, "/game/vip/status")
    assert "vipLevel" in status, status
    state = fixture.api(PLAYER, "/game/state")
    assert state.get("player", {}).get("id") == PLAYER
    daily = json.loads(fixture.sql(
        "SELECT JSON_OBJECT('date', COALESCE(last_vip_daily_reward_date, ''), "
        "'stored', stored_daily_salaries_count) "
        f"FROM players WHERE id={PLAYER}"))
    last_date, stored = daily["date"], daily["stored"]
    data = {
        "vip": status,
        "serverTime": state["serverTime"],
        "lastDailyDate": last_date,
        "storedDays": int(stored),
        "items": state.get("items", {}),
        "resources": state["resources"],
    }
    (OUT / (label + ".json")).write_text(json.dumps(data, ensure_ascii=False, indent=2))
    print(json.dumps({"snapshot": label, "dailyClaimed": status["dailyClaimed"],
                      "claimedPacks": status["claimedPackLevels"],
                      "claimedMilestones": status["claimedMilestones"],
                      "lastDailyDate": last_date, "storedDays": int(stored)}, ensure_ascii=False))
    return data


def verify():
    before = json.loads((OUT / "before.json").read_text())
    after = snapshot("after")
    old, new = before["vip"], after["vip"]
    packs = set(new["claimedPackLevels"]) - set(old["claimedPackLevels"])
    milestones = set(new["claimedMilestones"]) - set(old["claimedMilestones"])
    assert packs == set(range(1, old["vipLevel"] + 1)) - set(old["claimedPackLevels"])
    assert milestones == {150000}
    assert not old["dailyClaimed"] and new["dailyClaimed"]
    today = datetime.datetime.fromtimestamp(after["serverTime"] / 1000, ZoneInfo("Asia/Shanghai")).date()
    assert after["lastDailyDate"] == str(today)
    definition = next(level for level in old["levels"] if level["level"] == old["vipLevel"])
    days = 1
    if before["lastDailyDate"]:
        missed = (today - datetime.date.fromisoformat(before["lastDailyDate"])).days - 1
        days += min(max(0, missed) + before["storedDays"], definition["storedDailySalariesDays"])
    expected = Counter()
    for level in old["levels"]:
        if level["level"] in packs:
            expected.update(level["levelPackRewards"])
    for milestone in old["milestones"]:
        if milestone["milestoneCents"] in milestones:
            expected.update(milestone["rewards"])
    expected.update({key: count * days for key, count in definition["dailyRewards"].items()})
    resources = {"gold", "food", "steel", "oil", "rare"}
    item_deltas = {}
    for key, count in expected.items():
        if key not in resources:
            delta = after["items"].get(key, 0) - before["items"].get(key, 0)
            assert delta == count, (key, delta, count)
            item_deltas[key] = delta
    # 该试玩账号有大军持续粮耗和军官俸禄，资源差额需扣除验收期间的正常消耗。
    for key in {"gold", "steel", "oil", "rare"}:
        delta = after["resources"][key] - before["resources"][key]
        tolerance = 1000 if key == "gold" else 0
        assert expected[key] - tolerance <= delta <= expected[key], (key, delta, expected[key])
    result = {"passed": True, "newPackLevels": sorted(packs), "newMilestones": sorted(milestones),
              "dailyDays": days, "verifiedItemDeltas": item_deltas,
              "expectedResourcesBeforeUpkeep": {key: expected[key] for key in resources}}
    (OUT / "verification.json").write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print(json.dumps(result, ensure_ascii=False, indent=2))


def verify_repeated_claims():
    """已受领奖励再次请求必须拒绝；四次拒绝不能增加背包道具。"""
    before = json.loads((OUT / "after.json").read_text())
    rejected = []
    for action, body in [
        ("daily-salary/claim", {"targetCitySlot": 0}),
        ("level-pack/claim", {"targetLevel": 10, "targetCitySlot": 0}),
        ("level-pack/claim-all", {"targetCitySlot": 0}),
        ("milestone/claim", {"milestoneCents": 150000, "targetCitySlot": 0}),
    ]:
        response = fixture.api(PLAYER, "/game/vip/" + action, body)
        assert response.get("httpStatus") == 400, (action, response)
        rejected.append({"action": action, "status": 400, "body": json.loads(response["body"])})
    after = snapshot("after-duplicate-attempts")
    assert before["items"] == after["items"]
    assert before["vip"]["claimedPackLevels"] == after["vip"]["claimedPackLevels"]
    assert before["vip"]["claimedMilestones"] == after["vip"]["claimedMilestones"]
    (OUT / "duplicate-claims.json").write_text(json.dumps(rejected, ensure_ascii=False, indent=2))
    print(json.dumps({"passed": True, "rejected": rejected, "itemsUnchanged": True}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    if sys.argv[1] == "verify":
        verify()
    elif sys.argv[1] == "duplicates":
        verify_repeated_claims()
    else:
        snapshot(sys.argv[1])
