"""生成可审阅的中文结果与精简展示数据，不重新计算战斗。"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
NAMES = {"rocket": "反坦克火箭车", "htank": "重坦", "ltank": "轻坦", "armored": "防空装甲车",
         "assault": "突击炮", "infantry": "步兵", "motor": "摩托兵", "fighter": "战斗机", "bomber": "轰炸机"}
SUMMARY = json.loads((ROOT / "summary.json").read_text(encoding="utf-8"))
REPLAYS = json.loads((ROOT / "representatives.json").read_text(encoding="utf-8"))
INDEX = {(r["scenario"], r["development"], r["version"]): r for r in SUMMARY}


def number(value):
    return f"{value:.2f}".rstrip("0").rstrip(".")


def percent(value):
    return f"{value * 100:.2f}%"


def army(units):
    return "、".join(f"{NAMES.get(key, key)}{value}" for key, value in sorted(units.items())) or "0"


def main():
    lines = ["# 专项倍率调整：真实引擎对照模拟", "",
             "共6400场：25个阵容/预算场景 × 两种成长配置 × 32个固定种子 × 双向攻守 × 新旧倍率。每个表格单元汇总64场。", "",
             "结论：倍率下调延长了交火并增加部分克制方战损，但尚不能保证双方持续消耗。基础等数量时，反坦克火箭车对四种原反装甲目标均零损全歼；反坦克火箭车打100辆突击炮仍仅4回合结束、实际交火2回合。等资源防空车打轰炸机也仍仅5回合结束。", "",
             "混编更接近持久战：基础等资源下，反坦克火箭车支援对装甲突击平均23.59回合，反坦克火箭车与防空混编对航空联队15.81回合；同阵容互战全部达到30回合，双方仍有幸存兵力，按规则由攻方撤退。", "",
             "## 对照条件", "",
             "只把反坦克火箭车对轻坦/重坦/防空装甲车/突击炮由×5.5改为×3、防空装甲车对战斗机/轰炸机由×5改为×2.5。其他规则和属性使用同一份当前源码，包含步兵对地10、攻坚3、射程160。侦查装甲车等其他现行组合保持一致；这不是整个历史版本的比较。", "",
             "双方使用默认前进，无工事、城墙、技能。基础配置无科技和将领属性；成长配置双方攻击、防御、生命、射程及引擎科技5级，军事50、防御属性0。军事加成按引擎原有周期生效。", "",
             "等数量为各100单位，不代表资源等价。等资源预算56000，按粮+钢+油+稀有之和换算并向下取整；加权资源为钢+2×油+4×稀有，仅作敏感性分析，不代表完整经济模型。混编按预算份额分配，各兵种向下取整，实际投入见表。", "",
             "总回合包含接敌阶段；交火跨度=结束回合−首次开火回合+1，可能含停火间隔。资源战损=损失单位的征召原始资源总和/初始投入；不计伤兵回收、招募、行军及科技成本。单兵种场景该比例等于单位损失率。", "",
             "## 汇总", ""]
    for development, dev_label in [("base", "基础"), ("tech5_military50", "科技5 / 军事50")]:
        for budget, budget_label in [("COUNT", "等数量100"), ("COST", "等原始资源56000"),
                                     ("WEIGHTED_COST", "等加权资源56000"), ("MIXED_COST", "混编原始资源56000")]:
            lines += [f"### {dev_label} · {budget_label}", "",
                      "表中 `旧 → 新`；A、B固定为表中阵容身份，与攻守互换无关。全歼胜次数不包含超时战损占优。", "",
                      "| A vs B | A / B 初始数量 | 总回合 | 交火跨度 | A资源战损 | B资源战损 | 新配置 A全歼 / B全歼 / 超时 |",
                      "| --- | --- | --- | --- | --- | --- | --- |"]
            for r in SUMMARY:
                if r["version"] != "new" or r["development"] != development or r["budget"] != budget:
                    continue
                old = INDEX[(r["scenario"], development, "old")]
                lines.append(f"| {r['label']} | {sum(r['a_initial'].values())} / {sum(r['b_initial'].values())} | "
                             f"{number(old['rounds_mean'])} → {number(r['rounds_mean'])} | "
                             f"{number(old['combat_span_mean'])} → {number(r['combat_span_mean'])} | "
                             f"{percent(old['a_raw_loss_fraction_mean'])} → {percent(r['a_raw_loss_fraction_mean'])} | "
                             f"{percent(old['b_raw_loss_fraction_mean'])} → {percent(r['b_raw_loss_fraction_mean'])} | "
                             f"{r['A_eliminates_B']} / {r['B_eliminates_A']} / {r['timeout']} |")
            lines.append("")
    lines += ["## 混编阵容与实际投入", "",
              "| 场景 | A组成 | A原始资源 | B组成 | B原始资源 |", "| --- | --- | --- | --- | --- |"]
    for r in SUMMARY:
        if r["version"] == "new" and r["development"] == "base" and r["budget"] == "MIXED_COST":
            lines.append(f"| {r['label']} | {army(r['a_initial'])} | {r['a_raw_cost']} | {army(r['b_initial'])} | {r['b_raw_cost']} |")
    lines += ["", "预算份额：地面混编A反坦克火箭车40%/重坦30%/步兵30%，B重坦50%/轻坦30%/步兵20%；防空护卫A防空40%/重坦30%/步兵30%；反坦克火箭车防空A反坦克火箭车30%/防空30%/步兵40%；航空B战斗机40%/轰炸机60%；镜像双方反坦克火箭车30%/防空20%/重坦30%/步兵20%。", "",
              "## 一个逐回合例子：100防空车 vs 100轰炸机", "",
              "基础配置，种子0，A为攻方。下表为每回合结束时的幸存数；旧战斗结束后用其最终幸存数对齐显示。", "",
              "| 回合 | 旧：防空车 | 新：防空车 | 旧：轰炸机 | 新：轰炸机 |", "| --- | --- | --- | --- | --- |"]
    old = next(r for r in REPLAYS if r["version"] == "old" and r["scenario"] == "armored-bomber-count"
               and r["development"] == "base" and r["a_role"] == "attacker")
    new = next(r for r in REPLAYS if r["version"] == "new" and r["scenario"] == "armored-bomber-count"
               and r["development"] == "base" and r["a_role"] == "attacker")
    for n in range(max(old["rounds"], new["rounds"]) + 1):
        a, b = old["timeline"][min(n, old["rounds"])], new["timeline"][min(n, new["rounds"])]
        lines.append(f"| {n} | {a['a_count']} | {b['a_count']} | {a['b_count']} | {b['b_count']} |")
    lines += ["", "## 可信度与边界", "",
              "- 新旧倍率由进程启动时逐组合断言确认，游戏源码未被替换。旧倍率战报中的规则版本可能仍显示当前版本，因为 Java 编译期常量会内联；以报告首行的模拟配置为准。",
              "- 200场代表战斗均复跑同种子，并以单回合入口重放；幸存数、结束回合和完整回合文本全部与一次性战斗一致。",
              "- 384对未受影响的步兵/摩托兵对照逐种子、逐攻守完全相同；全部6400场幸存数均在合法范围。",
              "- 32个种子用于展示离散击杀抽样，不是独立的战术或兵力样本；64/64不能推断所有实战都必胜。本次没有技能、玩家中途指令或城防，不能替代这些场景的验证。",
              "- 全量 Maven 编译受当前工作区 `PrisonerService` 调用不存在的 `WorldTerrainService.isCoastalCity` 影响。实验改为隔离编译当前战斗服务及其数据依赖，未修复无关模块；未使用旧的战斗class。源码哈希见metadata.json。", "",
              "## 文件与复现", "",
              "- [原始6400场CSV](runs.csv)：每个种子/攻守结果与各兵种幸存数。",
              "- [聚合CSV](summary.csv) / [聚合JSON](summary.json)：均值、最小值、最大值、胜负/超时次数。",
              "- [逐回合CSV](rounds.csv) / [代表战斗JSON](representatives.json)：种子0双向攻守的逐回合幸存数。",
              "- [元数据与源码哈希](metadata.json)：配置、口径及来源。",
              "- 原始文字战报位于reports/old、reports/new，各100份。", "",
              "从项目根目录运行（JDK、Maven依赖需可用）：", "", "```sh",
              "python3 output/battle-simulation-20261008/run_simulation.py",
              "python3 output/battle-simulation-20261008/summarize.py", "```", ""]
    (ROOT / "README.md").write_text("\n".join(lines), encoding="utf-8")
    # 展示仅嵌入汇总和种子0轨迹；完整抽样留在CSV，避免展示体积过大。
    fields = ["version", "scenario", "label", "budget", "affected", "development", "a_initial", "b_initial", "runs",
              "a_raw_cost", "b_raw_cost", "rounds_mean", "rounds_min", "rounds_max", "first_combat_round_mean", "combat_span_mean",
              "a_raw_loss_fraction_mean", "b_raw_loss_fraction_mean", "A_eliminates_B", "B_eliminates_A", "timeout"]
    replay_fields = ["version", "scenario", "development", "a_role", "rounds", "first_combat_round", "outcome", "a_survivors", "b_survivors"]
    data = {"summary": [{k: r[k] for k in fields} for r in SUMMARY],
            "replays": [dict({k: r[k] for k in replay_fields}, timeline=[{
                k: p[k] for k in ["round", "a_count", "b_count", "a_survivors", "b_survivors", "fired"]
            } for p in r["timeline"]]) for r in REPLAYS]}
    (ROOT / "display-data.json").write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(f"Written README and compact data: {len(data['summary'])} summaries / {len(data['replays'])} replays")


if __name__ == "__main__":
    main()
