# 混编接触间距与战报修复验证

日期：2026-10-12。战斗规则：`balance-v13-contact-movement-20261012`。

## 原因与修复

双方原先用各兵种理论速度分摊接敌距离；混编前排被另一敌军挡住后，实际位移小于理论分配，后续单位因此留下1～2点甚至更大的间隔。整数分摊还能使剩余位移反复取整为0。机动日志又把任何零位移写成“已贴脸”，而伤害加成检查当前攻击目标距离严格为0，造成展示与伤害预期不符。

现在双方先按原有速度分摊机动，再依据统一坐标快照找到已有真实接触点。只有前进单位可以消耗本回合剩余步长，继续抵达前进方向上最近敌军所在的接触点。单位不会超过实际速度预算，也不能越过最近存活敌军；待命、后退、默认不前进的后勤及零速工事不会被拉到前排。已有空军坐标交错的旧会话也可以补齐坦克的接触间距。

双方机动全部结束后才生成日志，距敌/距我表示最近存活敌军距离。零位移只在距离0时显示“已接触前排”，否则显示“未能推进”。每次主动攻击及余伤都显示当前目标距离；重坦实际接触目标时显示“贴脸×1.5”。真正相距1或2点的待命后排不会获得加成，伤害公式仍严格检查距离0。

## 回归

新增7项真实单回合回归，先用旧引擎复现3项明确失败，见[修复前日志](repro-before.log)。最终14个定向后端测试类共139项通过，前端战报测试43项通过。160组固定随机混编检查涵盖部队、工事、科技、装备、司令官、闪电突击生效/冷却、各类机动指令和反向Map遍历，验证速度预算、战场边界、前排阻挡与遍历顺序一致性。

覆盖现有距离1无贴脸加成、余伤逐目标重新判断、反击不享受贴脸倍率、单对双方速度分摊、前三回合安全距离、持续指令、装备分组、持久化重载、离线续算和演习结算。结果见[汇总](summary.json)、[后端日志](backend-regression.log)、[前端日志](frontend-reports.log)。只运行编译、测试与只读差异检查，未执行自动格式化。

```sh
MAVEN_OPTS='-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8' mvn -o -f backend/pom.xml -Dtest=BattleContactMovementTest,BattleOpeningDistanceTest,TacticalBattleRoundTest,BattleServiceTest,CommandBonusBattleTest,OfficerSkillCycleTest,BattleDamageTest,BattleCompositionTest,PvpRulesTest,PvpCalibrationTest,TacticalPersistentOrdersTest,OfficerSkillPersistenceTest,PvpPracticeTest,TacticalBattleSchedulerTest test
node --test frontend/tests/reports.test.cjs
```

## 两场战报重放

分别用修改前源码副本和最终引擎，固定历史距离6725/6950、科技、军官、城墙及逐回合指令，按种子0～31重放。没有改写历史战报。种子0的末回合中，第一场双方重坦实际坐标均为2416，第二场攻方重坦已接触其当前攻击的战列舰，日志均显示目标距离0和贴脸×1.5。

| 战报 | 旧引擎攻方平均剩余 | 新引擎攻方平均剩余 | 旧引擎守方平均剩余 | 新引擎守方平均剩余 | 新引擎全歼胜利 |
| --- | ---: | ---: | ---: | ---: | --- |
| 3682 | 674624.0 | 651701.6 | 689567.3 | 692135.3 | 0/32 |
| 3684 | 616228.5 | 606128.8 | 657644.6 | 684397.6 | 0/32 |

接触补齐也作用于守方轻坦、重坦及特种兵，同时改变后续索敌和余伤目标，因此整体战果并非单纯给攻方加50%伤害。这两场依然未能在24回合内全歼；其他伤害节奏与胜利条件问题不属于本次修复。模拟随机种子与原战斗随机流不同，结果用于比较引擎行为。

源码：[BattleContactReplay.java](BattleContactReplay.java)，输入沿用[上一轮分析](../battle-analysis-20261012/README.md)。旧/新引擎输出：[修复前](replay-before.log)、[修复后](replay-after.log)。

## 演习校准与生效

战斗规则版本参与演习实力模型哈希。已用最终引擎重新测量陆空/海空8种技能1～5级及3类技能组合：86组、564160场固定种子镜像模拟；11组系数相对旧规则发生变化，最大校准残差2.468个百分点。独立种子及未参与校准的混编复核5184场，最大平均价值损失差2.607个百分点。模型版本为`power-v1-pvp-v1-96817f2e5f611b061b686103`，发布资源与[校准证据](skill-calibration.json)完全一致，未只替换旧系数的版本标签。

生成程序沿用`scripts/PvpCalibrationTool.java`与`scripts/PvpHoldoutTool.java`。计算在隔离Java进程中执行，不操作真实游戏数据库；临时加载资源仅用于允许校准类初始化，计算系数使用最终战斗引擎的新对照实验。独立复核见[holdout-evaluation.json](holdout-evaluation.json)。

本次已修改源码和构建资源，未重启或部署后端。重启后，新战场以及进行中战场的后续回合使用修复后的机动与日志规则；已有历史战报保留原始结果。
