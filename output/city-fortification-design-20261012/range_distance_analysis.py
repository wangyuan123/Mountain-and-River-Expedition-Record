"""读取当前属性，计算城防射程提案的动态距离与无伤亡射程进入回合；不改变游戏规则。"""

import json
import re
from fractions import Fraction
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
OUT = Path(__file__).resolve().parent


def definitions(filename, constructor, numeric_count):
    source = (ROOT / "backend/src/main/java/com/wargame/model/constants" / filename).read_text()
    rows = {}
    for match in re.finditer(r'new ' + constructor + r'\("([^\"]+)",\s*"[^\"]+",\s*"[^\"]+",\s*([\d.,\s]+)', source):
        values = [Fraction(value.strip()) for value in match[2].split(",") if value.strip()]
        assert len(values) >= numeric_count
        rows[match[1]] = values
    return rows


UNITS = definitions("UnitDef.java", "UnitDef", 8)
FORTS = definitions("FortDef.java", "FortDef", 8)


def stats(unit, weapon_tech, engine_tech, wall_level, proposal):
    if unit in FORTS:
        # FortDef中的数值顺序是四项攻击、防御、生命、射程、速度。
        values = FORTS[unit]
        step = int(values[7] * 50)
        range_value = values[6] * (1 + Fraction(weapon_tech, 10))
        if proposal:
            range_value *= 1 + Fraction(wall_level, 100)
    else:
        values = UNITS[unit]
        speed_mul = 1 if unit in ("infantry", "special") else 1 + Fraction(engine_tech, 20)
        step = int(values[6] * 50 * speed_mul)
        range_value = values[7] * (1 + Fraction(weapon_tech, 10))
    return step, int(range_value)


def ceil_ratio(numerator, denominator):
    return max(1, (max(0, numerator) + denominator - 1) // denominator)


scenarios = []
for attacker in ("infantry", "rocket", "htank", "special", "bomber"):
    for fort in FORTS:
        for tech in (0, 10):
            for wall in (1, 5, 10):
                comparison = {}
                for proposal, label in ((False, "current"), (True, "proposed")):
                    step, attack_range = stats(attacker, tech, tech, 0, proposal)
                    _, fort_range = stats(fort, tech, tech, wall, proposal)
                    # 开局只代入原有兵种/工事的科技射程，城防建筑额外射程不再拉长距离。
                    _, opening_fort_range = stats(fort, tech, tech, wall, False)
                    base_distance = 3 * step + max(attack_range, opening_fort_range) + 100
                    # 仅在征服战且有实际参战工事时，基础距离统一增加 500 点。
                    distance = base_distance + (500 if proposal else 0)
                    comparison[label] = {
                        "attackerStep": step,
                        "attackerRange": attack_range,
                        "fortRange": fort_range,
                        "baseInitialDistance": base_distance,
                        "cityFortDistanceExtra": 500 if proposal else 0,
                        "initialDistance": distance,
                        "firstFortShotRoundIgnoringLoss": ceil_ratio(distance - fort_range, step),
                        "firstAttackerShotRoundIgnoringLoss": ceil_ratio(distance - attack_range, step),
                    }
                scenarios.append({"attacker": attacker, "fort": fort, "techLevel": tech,
                                  "wallLevel": wall, **comparison})

# D0 的原公式保证未叠加城防射程时最早第 4 回合交火；+500 后叠加满级工事
# 射程可能让远程工事提前开火，因此不能再把 proposed 阶段也断言为 >= 4。
assert all(row["current"]["firstFortShotRoundIgnoringLoss"] >= 4
           and row["current"]["firstAttackerShotRoundIgnoringLoss"] >= 4
           for row in scenarios)
result = {
    "status": "design-only",
    "source": "current UnitDef / FortDef; shared capacity is implemented at 3000 per level; wall range bonus remains design-only",
    "formula": "D0=max_pairs(3*(stepA+stepB)+max(techRangeA,techRangeB))+100; D=D0+500 only for city defense with active forts; wall extra range excluded from D0",
    "cityFortDistanceExtra": 500,
    "distanceExtraScope": "city defense with at least one active fort; all other battles use D0",
    "fortRangePerLevel": 0.01,
    "capacityByWallLevel": [{"level": level, "sharedCapacity": 3000 * level,
                            "rangeBonusPercent": level} for level in range(11)],
    "limitations": "single advancing attacker against a stationary fort; no casualties or command changes; theoretical range-entry round is not a battle result",
    "scenarios": scenarios,
}
(OUT / "range-distance-analysis.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
for row in scenarios:
    if row["wallLevel"] == 10 and row["techLevel"] == 0 and row["attacker"] in ("infantry", "rocket"):
        print(row["attacker"], row["fort"], row["current"], "->", row["proposed"])
