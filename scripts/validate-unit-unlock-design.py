"""Check unit gates against the runtime configuration, catalogs and dependencies."""

import hashlib
import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DESIGN = ROOT / "docs/balance/unit-unlock-design-20261020.json"
RULES = ROOT / "backend/src/main/resources/game/tech-building-prerequisites.json"
UNIT_RULES = ROOT / "backend/src/main/resources/game/unit-unlock-prerequisites.json"
UNIT_SOURCE = ROOT / "backend/src/main/java/com/wargame/model/constants/UnitDef.java"
TECH_SOURCE = ROOT / "backend/src/main/java/com/wargame/model/constants/TechDef.java"
BUILDING_SOURCE = ROOT / "backend/src/main/java/com/wargame/model/constants/BuildingDef.java"
RECON_SOURCE = ROOT / "backend/src/main/java/com/wargame/model/constants/ReconRules.java"
TIMING_SOURCE = ROOT / "backend/src/main/java/com/wargame/model/constants/TechnologyRules.java"
REPORT = ROOT / "docs/balance/unit-unlock-validation-20261020.json"


def main():
    design = json.loads(DESIGN.read_text())
    rules = json.loads(RULES.read_text())
    runtime = json.loads(UNIT_RULES.read_text())
    assert design["sourcePrerequisiteVersion"] == rules["version"]
    assert design["status"] == "implemented_runtime_verified"
    assert runtime["version"] == design["version"]

    # The shared prerequisite amendment now lives in the runtime rules.
    amendments = design.get("sharedPrerequisiteAmendments", [])
    for amendment in amendments:
        assert amendment["operation"] == "remove_requirement"
        row = next(row for row in rules[amendment["kind"]][amendment["id"]]["levels"]
                   if row["targetLevel"] == amendment["targetLevel"])
        assert amendment["requirement"] not in row["requires"], "Runtime prerequisite amendment is missing"

    source = UNIT_SOURCE.read_text()
    catalog = dict(re.findall(
        r'm\.put\("([a-z_]+)", new UnitDef\(.*?"(factory|lightfactory|heavyfactory|airport|shipyard)"',
        source, re.S,
    ))
    units = {unit["id"]: unit for unit in design["units"]}
    assert len(units) == len(design["units"]) == len(catalog) == 17
    assert set(units) == set(catalog)
    runtime_units = {unit["id"]: unit for unit in runtime["units"]}
    assert set(runtime_units) == set(units), "Runtime unit unlock configuration is incomplete"
    for unit_id, unit in units.items():
        configured = runtime_units[unit_id]
        assert configured["productionBuilding"] == unit["productionBuilding"]
        assert configured["buildings"] == unit["buildings"], f"Runtime building gates differ: {unit_id}"
        assert configured["technologies"] == unit["technologies"], f"Runtime technology gates differ: {unit_id}"
        assert configured.get("coastalOrLegacyNavalRequired", False) == unit.get("coastalOrLegacyNavalRequired", False), (
            f"Runtime geography gate differs: {unit_id}"
        )

    unit_definitions = dict(re.findall(
        r'm\.put\("([a-z_]+)", new UnitDef\((.*?)\)\);', source, re.S,
    ))
    technology_effects = dict(re.findall(
        r'm\.put\("([a-z_]+)", new TechDef\(.*?"([a-z_]+)"\)\);',
        TECH_SOURCE.read_text(), re.S,
    ))
    assert set(technology_effects) == set(rules["technologies"])
    recon_units = set(re.findall(
        r'"([a-z_]+)"', re.search(r'UNITS = Set\.of\(([^)]+)\)', RECON_SOURCE.read_text()).group(1),
    ))

    # Gate relevance follows actual gameplay effects, not a thematic label:
    # only ReconRules units use recon; each speed effect stays in its branch.
    # The two ground-artillery support gates are explicit design additions.
    support_buildings = {"air": {"apron"}, "sea": {"port"}, "land": set()}
    speed_effects = {"land": "spd_arm", "air": "spd_air", "sea": "spd_nav"}
    for unit_id, unit in units.items():
        definition = unit_definitions[unit_id]
        assert re.search(r'"(land|air|sea)"', definition).group(1) == unit["branch"]
        allowed_buildings = {unit["productionBuilding"]} | support_buildings[unit["branch"]]
        if unit_id in {"assault", "rocket"}:
            allowed_buildings.add("heavyfactory")
        assert set(unit["buildings"]) <= allowed_buildings, f"Unrelated building gate: {unit_id}"
        assert set(unit["buildingReasons"]) == set(unit["buildings"])
        assert set(unit["technologyReasons"]) == set(unit["technologies"])
        assert all(unit["buildingReasons"].values()) and all(unit["technologyReasons"].values())

        allowed_effects = {"train", "food_save", speed_effects[unit["branch"]]}
        # A carrier is a naval unit with an explicit carrier-aviation readiness
        # gate; air_engine remains a subsystem prerequisite and does not grant
        # its aircraft movement bonus to the carrier hull.
        if unit_id == "carrier":
            allowed_effects.add("spd_air")
        if unit_id in recon_units:
            allowed_effects.add("recon")
        if not re.search(r'"(?:land|air|sea)",\s*true', definition):
            allowed_effects.update({"atk_all", "def_all", "hp_all", "firepower_all", "range_all"})
        load = re.search(r'"(?:land|air|sea)",\s*(?:true|false),\s*(\d+)', definition)
        if load and int(load.group(1)) > 0:
            allowed_effects.add("load")
        for technology in unit["technologies"]:
            assert technology_effects[technology] in allowed_effects, (
                f"Unrelated technology gate: {unit_id} / {technology}"
            )

    timing_source = TIMING_SOURCE.read_text()
    assert design["technologyRulesVersion"] in timing_source
    curves = {
        name: [int(value.strip()) for value in values.split(",")]
        for name, values in re.findall(
            r'private static final int\[\] (\w+)_SECONDS = \{([^}]+)\}', timing_source
        )
    }
    assert all(len(curve) == 10 for curve in curves.values())

    # Every lower level is a dependency too: taking only a target row can miss
    # city-hall preparation required at an earlier upgrade.
    dependencies = {}
    for kind in ("buildings", "technologies"):
        for key, definition in rules[kind].items():
            for row in definition["levels"]:
                level = row["targetLevel"]
                required = [("buildings", entry["building"], entry["level"])
                            for entry in row["requires"]]
                if level > 1:
                    required.append((kind, key, level - 1))
                dependencies[(kind, key, level)] = required

    for unit in units.values():
        assert unit["productionBuilding"] == catalog[unit["id"]]
        assert unit["productionBuilding"] in unit["buildings"]
        if unit["branch"] == "sea":
            assert unit["coastalOrLegacyNavalRequired"]
            assert "port" in unit["buildings"] and "shipyard" in unit["buildings"]
        required = [("buildings", key, level) for key, level in unit["buildings"].items()]
        required += [("technologies", key, level) for key, level in unit["technologies"].items()]
        dependencies[("units", unit["id"], 1)] = required

    for required in dependencies.values():
        assert all(node in dependencies for node in required)

    visited = set()
    visiting = set()

    def visit(node):
        assert node not in visiting, f"Dependency cycle at {node}"
        if node in visited:
            return
        visiting.add(node)
        for required in dependencies[node]:
            visit(required)
        visiting.remove(node)
        visited.add(node)

    for node in dependencies:
        visit(node)

    def closure(node):
        result = set()

        def collect(current):
            if current in result:
                return
            result.add(current)
            for required in dependencies[current]:
                collect(required)

        collect(node)
        return result

    def required_buildings(nodes):
        result = {}
        for kind, key, level in nodes:
            if kind == "buildings":
                result[key] = max(result.get(key, 0), level)
        return dict(sorted(result.items()))

    def seconds(technology, level):
        curve = curves.get({"arm_engine": "ARM_ENGINE", "air_engine": "AIR_ENGINE",
                            "nav_engine": "NAV_ENGINE"}.get(technology, "NORMAL"))
        return sum(curve[:level])

    rows = []
    for unit in units.values():
        nodes = closure(("units", unit["id"], 1))
        all_buildings = required_buildings(nodes)
        # The local manufacturing floor excludes research buildings, because
        # research may already have been completed in another owned city.
        local_nodes = set()
        for key, level in unit["buildings"].items():
            local_nodes.update(closure(("buildings", key, level)))
        local_buildings = required_buildings(local_nodes)
        local_floor = local_buildings.get("command", 0)
        assert local_floor == unit["minimumProductionCityHall"] == unit["stage"]
        coastal_nodes = [node for node in nodes if node[0] == "buildings"
                         and rules["buildings"][node[1]]["levels"][node[2] - 1].get("coastalOnly")]
        if unit["branch"] != "sea":
            assert not coastal_nodes, f"Land/air gate unexpectedly needs a coastal city: {unit['id']}"
        for key, level in unit["technologies"].items():
            assert type(level) is int and 1 <= level <= len(rules["technologies"][key]["levels"])
        research = sum(seconds(key, level) for key, level in unit["technologies"].items())
        rows.append({
            "id": unit["id"], "name": unit["name"], "branch": unit["branch"],
            "localCityHallFloor": local_floor,
            "sameCityResearchPathCityHallFloor": all_buildings.get("command", 0),
            "researchBaseWorkSecondsFromZero": research,
            "researchBaseWorkHoursFromZero": round(research / 3600, 3),
            "localBuildingClosure": local_buildings,
            "sameCityResearchBuildingClosure": all_buildings,
            "reachableInInlandOnlyBuildingModel": not bool(coastal_nodes),
        })

    by_id = {row["id"]: row for row in rows}
    progressions = [
        ["recon_car", "armored", "ltank", "assault", "htank", "rocket", "special"],
        ["scout", "transport", "fighter", "bomber"],
        ["destroyer", "sub", "battleship", "carrier"],
    ]
    for progression in progressions:
        for lower, higher in zip(progression, progression[1:]):
            # Capital ships share the level-10 building ceiling; technology
            # requirements still distinguish their research investment.
            assert by_id[lower]["localCityHallFloor"] <= by_id[higher]["localCityHallFloor"]
            assert by_id[lower]["researchBaseWorkSecondsFromZero"] < by_id[higher]["researchBaseWorkSecondsFromZero"]

    # At the first naval tier, research work is higher than both the land and
    # air combat platform, in addition to the two coastal naval facilities.
    assert by_id["destroyer"]["researchBaseWorkSecondsFromZero"] > max(
        by_id[key]["researchBaseWorkSecondsFromZero"] for key in ("htank", "fighter")
    )
    # Late-game land units sit after the first two naval tiers. Compare capital
    # ships with elite land units, not early submarines with late artillery.
    assert by_id["battleship"]["researchBaseWorkSecondsFromZero"] > by_id["special"]["researchBaseWorkSecondsFromZero"]

    late_game_levels = {
        key: list(units[key]["buildings"].values()) + list(units[key]["technologies"].values())
        for key in ("rocket", "special", "battleship", "carrier")
    }
    assert all(7 <= level <= 9 for level in late_game_levels["rocket"])
    assert set(late_game_levels["special"]) == {9, 10}
    assert set(late_game_levels["battleship"]) == {9, 10}
    assert late_game_levels["battleship"].count(10) > len(late_game_levels["battleship"]) / 2
    assert set(late_game_levels["carrier"]) == {10}
    assert set(units["battleship"]["technologies"]) <= set(units["carrier"]["technologies"])
    assert set(units["carrier"]["technologies"]) == set(units["battleship"]["technologies"]) | {"air_engine"}
    assert all(level == 10 for level in units["carrier"]["technologies"].values())

    report = {
        "version": design["version"],
        "status": "design_static_checks_passed",
        "sourceFingerprints": {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
                               for path in (DESIGN, RULES, UNIT_RULES, UNIT_SOURCE, TECH_SOURCE, BUILDING_SOURCE,
                                            RECON_SOURCE, TIMING_SOURCE, Path(__file__).resolve())},
        "checkedBuildingLevelNodes": sum(node[0] == "buildings" for node in dependencies),
        "checkedTechnologyLevelNodes": sum(node[0] == "technologies" for node in dependencies),
        "checkedUnitNodes": len(units),
        "dependencyCycles": 0,
        "checkedDirectRequirementRelevance": True,
        "checkedDirectRequirementCount": sum(len(unit["buildings"]) + len(unit["technologies"])
                                             for unit in units.values()),
        "reconEligibleUnits": sorted(recon_units),
        "relevanceModel": "TechDef effects and ReconRules dispatch eligibility; branch-specific movement with explicit carrier-aviation subsystem prerequisite; production facilities plus direct air/naval support and explicitly proposed ground-artillery heavy-industry support; every direct gate has a reason",
        "progressionsWithNondecreasingCityHallAndStrictlyRisingBaseResearchWork": progressions,
        "lateGameDirectRequirementLevels": {
            key: {"minimum": min(levels), "maximum": max(levels),
                  "level10Count": levels.count(10), "totalCount": len(levels)}
            for key, levels in late_game_levels.items()
        },
        "implementedSharedPrerequisiteAmendments": amendments,
        "model": "runtime prerequisite and unit unlock configurations compared with design; one coastal city for the complete graph; inland-only reachability for land/air gates; one completed instance of each required building; sequential upgrade prerequisites",
        "timingMeaning": "sum of required technology base durations from level zero; excludes construction, resources, optional research, speed bonuses, existing progress and future parallel queues; not a calendar estimate",
        "limitations": ["No economic simulation or runtime behavior test",
                         "No exhaustive city-slot layout or quest reward simulation",
                         "Reachability assumes required resource payments are affordable",
                         "Actual multi-city research can relocate research-building requirements"],
        "units": rows,
    }
    REPORT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(f"Passed: {len(dependencies)} dependency nodes; 17 units; no cycles; direct gate relevance checked.")
    for row in rows:
        print(f"{row['name']}: city hall {row['localCityHallFloor']}; base research work {row['researchBaseWorkHoursFromZero']} h")


if __name__ == "__main__":
    main()
