"""对当前战斗引擎进行倍率对照；不修改游戏源码或已编译的游戏类。"""
from __future__ import annotations

import csv
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

OUTPUT = Path(__file__).resolve().parent
PROJECT = OUTPUT.parents[1]
BACKEND = PROJECT / "backend"
CONSTANTS = ["BattleRules", "UnitDef", "GameData", "GameConstants", "ResourceDef", "BuildingDef", "TechDef",
             "FortDef", "WildTypeDef", "ItemDef", "OfficerSkillDef", "FactionDef", "StarColor", "HistoricalOfficers",
             "WorldConfig", "JapaneseUnitDef"]
ENGINE_SOURCES = [BACKEND / "src/main/java/com/wargame/service/BattleService.java"] + [
    BACKEND / f"src/main/java/com/wargame/model/constants/{name}.java" for name in CONSTANTS
] + [BACKEND / f"src/main/java/com/wargame/model/dto/{name}.java" for name in ["BattleResult", "BattleRoundState"]]
SOURCES = ENGINE_SOURCES + [OUTPUT / "BattleSimulation.java"]


def hashes():
    return {str(path.relative_to(PROJECT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in SOURCES}


def write_json(name, data):
    (OUTPUT / name).write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_csv(name, rows):
    with (OUTPUT / name).open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows({k: json.dumps(v, ensure_ascii=False, sort_keys=True) if isinstance(v, (dict, list)) else v
                         for k, v in row.items()} for row in rows)


def main():
    before = hashes()
    # 直接编译当前战斗源码及数据依赖，避免无关业务模块的编译问题影响实验。
    # 只解析项目依赖，不触发全量编译，也不使用旧的 BattleService.class。
    with tempfile.TemporaryDirectory(prefix="battle-dependencies-") as temp:
        classpath_file = Path(temp) / "classpath.txt"
        subprocess.run(["./mvnw", "-q", "dependency:build-classpath", f"-Dmdep.outputFile={classpath_file}"],
                       cwd=BACKEND, env=dict(os.environ, LC_ALL="en_US.UTF-8", LANG="en_US.UTF-8"), check=True)
        dependencies = classpath_file.read_text(encoding="utf-8").strip()
    rules = (BACKEND / "src/main/java/com/wargame/model/constants/BattleRules.java").read_text(encoding="utf-8")
    baseline_rules = rules
    # 只改原调整的六个组合；其他新增组合（例如侦查装甲车）保持当前倍率。
    for target, current, previous in [(id, "3.0", "5.5") for id in ["ltank", "htank", "armored", "assault"]] + [
            (id, "2.5", "5.0") for id in ["fighter", "bomber"]]:
        expected = f'"{target}", {current}'
        if baseline_rules.count(expected) != 1:
            raise RuntimeError(f"Multiplier entry changed: {expected}; baseline must be reviewed")
        baseline_rules = baseline_rules.replace(expected, f'"{target}", {previous}')
    with tempfile.TemporaryDirectory(prefix="battle-effects-") as temp:
        task = Path(temp)
        runner, old, engine = task / "runner", task / "old", task / "engine"
        runner.mkdir(); old.mkdir(); engine.mkdir()
        subprocess.run(["javac", "-encoding", "UTF-8", "-cp", dependencies,
                        "-sourcepath", str(BACKEND / "src/main/java"), "-d", str(engine)]
                       + [str(path) for path in ENGINE_SOURCES], check=True)
        classpath = os.pathsep.join([str(engine), str(BACKEND / "src/main/resources"), dependencies])
        old_source = task / "BattleRules.java"
        old_source.write_text(baseline_rules, encoding="utf-8")
        subprocess.run(["javac", "-encoding", "UTF-8", "-cp", classpath, "-d", str(old), str(old_source)], check=True)
        subprocess.run(["javac", "-encoding", "UTF-8", "-cp", classpath, "-d", str(runner), str(OUTPUT / "BattleSimulation.java")], check=True)
        for version in ["old", "new"]:
            cp = os.pathsep.join(([str(old)] if version == "old" else []) + [str(runner), classpath])
            subprocess.run(["java", "-Xmx512m", "-cp", cp, "BattleSimulation", version, str(OUTPUT)], check=True)
    rows, summaries, representatives = [], [], []
    for version in ["old", "new"]:
        for target, name in [(rows, "runs"), (summaries, "summary"), (representatives, "representatives")]:
            target.extend(json.loads((OUTPUT / f"{version}-{name}.json").read_text(encoding="utf-8")))
    # 未受倍率影响的对照应逐种子、逐攻守完全一致。
    controls = {(r["version"], r["scenario"], r["development"], r["seed"], r["a_role"]): r
                for r in rows if r["scenario"].startswith("infantry-motor-")}
    for key, row in controls.items():
        if key[0] == "old":
            other = controls[("new",) + key[1:]]
            assert {k: v for k, v in row.items() if k != "version"} == {k: v for k, v in other.items() if k != "version"}
    assert before == hashes(), "Sources changed while simulation was running"
    write_csv("runs.csv", rows)
    write_csv("summary.csv", summaries)
    write_json("summary.json", summaries)
    write_json("representatives.json", representatives)
    rounds = []
    for representative in representatives:
        for point in representative["timeline"]:
            rounds.append({k: representative[k] for k in ["version", "scenario", "development", "seed", "a_role"]} | point)
    write_csv("rounds.csv", rounds)
    units = json.loads((OUTPUT / "new-representatives.json").read_text(encoding="utf-8"))
    write_json("metadata.json", {
        "engine": "BattleService.startWorldDispatch; representative parity against resolveWorldRound",
        "balance_version": "balance-v11-attrition-counters-20261008",
        "baseline": {"rocket_armor": 5.5, "armored_air": 5.0},
        "adjusted": {"rocket_armor": 3.0, "armored_air": 2.5},
        "seeds": list(range(32)), "directions": ["A attacker", "A defender"],
        "battles": len(rows), "groups_per_version": len(summaries) // 2,
        "representative_replay_and_parity_checks": len(representatives),
        "control_paired_checks": len(controls) // 2,
        "source_sha256": before,
        "environment": {"java": subprocess.check_output(["java", "-version"], stderr=subprocess.STDOUT, text=True).strip()},
        "assumptions": ["player vs player", "default advance", "no forts/walls/skills",
                        "base: no technology or commander attributes", "developed: symmetric tech5 and military50, commander defense0",
                        "COUNT: 100 each; does not imply equal resource investment",
                        "COST: 56000/(food+steel+oil+rare), rounded down",
                        "WEIGHTED_COST: 56000/(steel+2*oil+4*rare), sensitivity assumption only",
                        "MIXED_COST: 56000 allocated by documented unit shares, each component rounded down",
                        "lost units = initial minus survivors; wounded recovery and recruitment after battle excluded",
                        "30 rounds with both sides alive = attacker retreat, not an annihilation victory",
                        "combat span = end round-first firing round+1; may include pauses in firing",
                        "same current stats in both versions, including infantry10/3/160; not a historic build comparison"],
        "representative_runs_per_version": len(units),
    })
    print(f"Validated {len(rows)} battles; exported CSV/JSON/reports to {OUTPUT}")


if __name__ == "__main__":
    main()
