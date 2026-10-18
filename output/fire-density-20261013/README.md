# 火力密度实现与验证

日期：2026-10-13。状态：源码已实现，尚未部署、重启或写入真实数据库。本次只实现火力密度，保留工作区其他已有改动。

## 规则

- 科技：`fire_density`，军事类，最高10级，效果标识 `firepower_all`。
- 行动火力倍率：`1 + 0.10 × 等级`。0/1/5/10级为×1.0/×1.1/×1.5/×2.0，同项线性累计，与攻击科技独立相乘；两项满级为×4。
- 全军主动、余伤和反击均生效，所有参战兵种和四类工事、四个攻击领域适用。伤害封顶前应用一次；余伤按增强后的每份伤害消耗额度；反击使用反击方自己的科技。
- 师夷长技参考攻击不包含敌方行动火力，己方有效攻击最终只乘一次己方倍率。
- 首级费用：钢铁300、粮食160、稀矿30，成长1.8，费用和工期公式与军队生命一致。共用全账号单科研队列，取消沿用80%返还。
- 目标等级L前置：本城科研中心max(3,L)、军工厂L、军校和稀矿厂max(1,L−2)；L≥7另需参谋部max(1,L−2)。只认已完成建筑。
- 缺项为0级，不赠送等级、不自动继承生命科技、不自动给NPC补级。冻结战斗不读取账号后来研发的等级，历史战报不重算。

设计见 [战斗科技攻防对应设计](../../docs/COMBAT_TECH_PAIRING_DESIGN_20261012.md)。

## 实现入口

后端科技目录、百分比映射、战斗主动/反击入口、演习快照白名单、输出估值和参考校准科技均已更新。前端科技卡片/确认窗口、兵种详情、战术卡片、演习概览和逐回合事件显示行动火力倍率。武器攻击字段保留原口径，战报原始火力包含行动火力。

战斗版本：`balance-v14-fire-density-20261013`。
演习估值版本：`power-v1-pvp-v1-49108c0a1f003b264998e9fd`。
建筑前置版本：`fire-density-2026-10-13-v1`。

## 定向回归

后端40项通过，见 [干净构建日志](backend-clean-regression.log)：

- `BattleFireDensityTest`：7项；内部矩阵覆盖17种兵种和4类工事、四攻击领域、双方、0/1/5/10级。验证独立倍率、缺省/非法等级、攻击×火力×4、与同级生命抵消、余伤守恒、封顶、装备分组和技能叠加。
- `TechServiceTest`、`PrerequisiteIntegrationTest`：费用/工期、缺省零级、逐级前置、扣费前拒绝、入队及取消返还。
- `PvpRulesTest`、`PvpCalibrationTest`、`PvpPracticeTest`、`ShipyardPvpCompatibilityTest`：新规则、完整实测配置、冻结科技、估值包含火力、生产设施归一化。

前端112项通过，见 [前端日志](frontend-regression.log)，覆盖科技效果和实际倍率、建筑前置、军队、战术冻结来源、演习、历史战报与配装。

执行命令：

```sh
node --test frontend/tests/tech.test.cjs frontend/tests/constants.test.cjs frontend/tests/combat-data.test.cjs frontend/tests/army-collapse.test.cjs frontend/tests/tactical-facing.test.cjs frontend/tests/pvp.test.cjs frontend/tests/prerequisites.test.cjs frontend/tests/reports.test.cjs frontend/tests/unit-equipment.test.cjs

MAVEN_OPTS='-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8' mvn -o -f /tmp/fire-density-pom.xml -Dtest=BattleFireDensityTest,TechServiceTest,PrerequisiteIntegrationTest,PvpRulesTest,PvpCalibrationTest,PvpPracticeTest,ShipyardPvpCompatibilityTest test
```

`/tmp/fire-density-pom.xml` 是 backend/pom.xml 的隔离构建副本：主/测试源码和资源指向当前工作区，构建目录为 `/tmp/fire-density-maven-target`，Surefire工作目录为工作区backend。未清理或使用已被IDE增量编译污染的工作区target。Maven默认编码读取中文路径会失败，因此显式使用UTF-8。没有运行自动格式化或lint修复。

运行/设计前置内容一致，原有250条建筑和151条科技规则原样保留，只增加火力密度10条。发布校准资源与真实输出完全一致。见 [一致性检查](consistency-check.json)。`git diff --check`通过。

## 真实多回合对照

[FireDensityBalanceTool.java](FireDensityBalanceTool.java) 使用实际 `fire_density` 科技键、当前引擎开局距离和24回合终局，输出 [balance-evaluation.json](balance-evaluation.json)。600组配置、8,640场模拟，种子91001～91008。覆盖步兵/装甲、陆空、海空、攻坚及17种全兵种混编，其他科技0/5/10、城墙0/10、配装与技能、同数量/同资源，以及双方和单方火力。工事只在守方参战，其余阵容互换攻守。

| A/B火力等级 | 场数 | A平均资源损失 | B平均资源损失 | 任一方全歼场数 | 双方存活且打满24回合 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 0/0 | 1,728 | 42.89% | 59.05% | 840 | 888 |
| 5/5 | 1,728 | 46.24% | 67.07% | 952 | 776 |
| 10/10 | 1,728 | 48.00% | 73.18% | 1,032 | 696 |
| 10/0 | 1,728 | 31.05% | 80.53% | 1,192 | 536 |
| 0/10 | 1,728 | 67.94% | 44.16% | 984 | 744 |

这是特定实验集合，不对称，不是攻守胜率。资源按四资源总量等权相加；配装组同时带相同技能。结果说明消耗加快、单方研发形成优势，不保证所有对战24回合全歼，也没有测量所有NPC关卡难度或真人配兵分布。每组仅改变火力科技时，开局距离一致；单次矩阵另验证速度和射程不变。JSON保留各兵种幸存数，未单独追踪“从未被攻击”的事件。

复现多回合工具：

```sh
fire_density_cp="/tmp/fire-density-maven-target/classes:$(cat /tmp/fire-density-classpath.txt)"
javac -encoding UTF-8 -cp "$fire_density_cp" -d /tmp/fire-density-audit output/fire-density-20261013/FireDensityBalanceTool.java
java -Xmx512m -Dfile.encoding=UTF-8 -cp "/tmp/fire-density-audit:$fire_density_cp" FireDensityBalanceTool output/fire-density-20261013/balance-evaluation.json
```

`/tmp/fire-density-classpath.txt` 记录当前后端的Maven依赖类路径；临时文件需存在于复现环境。

## 演习校准与限制

[skill-calibration.json](skill-calibration.json)：86组技能配置、564,160场固定种子镜像模拟。最大校准残差0.016026，即1.60个百分点。服务端资源由这份真实输出生成，没有把旧系数只换版本后发布。

[holdout-evaluation.json](holdout-evaluation.json)：54配置、5,184场未参与校准的混编和不同种子，科技3级、配装5%。最大损失差偏离0.087346，即8.73个百分点，出现在陆空“全军冲锋5+破甲打击5+火力压制5”；独立公平性复核不能视为通过。

为区分新科技和既有估值偏差，在临时源码副本中仅恢复本次修改前的 `BattleService`，保留当前兵种属性、其他源码和配置，重算5个偏差较大的组合，共480场独立复核，见 [baseline-holdout-evaluation.json](baseline-holdout-evaluation.json)：

| 战场 / 技能组合 | 无火力密度的当前基线 | 新引擎 |
| --- | ---: | ---: |
| 陆空：冲锋5+破甲5+压制5 | −7.00个百分点 | −8.73个百分点 |
| 陆空：闪电3+坚守3+反击3 | −6.17个百分点 | −7.35个百分点 |
| 陆空：学习5 | −4.72个百分点 | −4.81个百分点 |
| 海空：借甲5+反击5+学习5 | −3.27个百分点 | −4.99个百分点 |
| 海空：反击5 | −2.03个百分点 | −4.22个百分点 |

因此既有匹配公式本身已有偏差，新科技改变消耗节奏后在部分组合上继续放大。另做了按实际有效估值归一化参考兵力的临时试验：陆战改善、海战最差达到8.64个百分点。试验没有接入生产源码；[normalized-trial-holdout.json](normalized-trial-holdout.json)和对应校准仅是探索记录，不属于发布配置。后续需另行改进校准样本及估值泛化，不能靠本次新增科技宣称匹配完全公平。

校准仍沿用标准阵容、两种规模/科技/配装、镜像固定种子及7次二分的方法；校准残差反映训练集合，不保证任意混编的公平结果。`scripts/PvpCalibrationTool.java` 断点续算会剔除旧版本记录；`scripts/PvpHoldoutTool.java` 包含新科技参考等级。

## 额外回归的既有失败

[backend-wide-clean-regression.log](backend-wide-clean-regression.log)：额外64项用例，58通过、6失败、0错误。临时恢复本次修改前的战斗引擎后，见 [backend-baseline-regression.log](backend-baseline-regression.log)，同6项以相同结果失败：

- `BattleDamageTest`、`CommandBonusBattleTest`、`OfficerSkillCycleTest`两项及`TacticalPersistentOrdersTest`：旧速度/位置预期与当前增速配置不符。
- `BattleCompositionTest`：旧175辆火箭车攻坚样本预期32/32胜，当前配置29/32；新旧引擎完全一致。

未修改这些失败用例，也未修改用户作废的速度、距离、工事射程等规则。初次 [backend-regression.log](backend-regression.log) 的额外22错误来自已存在的ECJ增量编译残留，后续隔离干净构建没有这些错误。
