# 战场开局距离排查与验证

日期：2026-10-12。规则版本：`balance-v12-opening-distance-20261012`。

## 原因与修改

原公式 `max(1000, round(3 × (双方最高速度之和) × 50 + 全场最长射程))` 存在三个边界问题：

- 步兵互战被1000最低宽度拉长：第四回合距离200，大于射程160，第五回合才交火。
- 纯反坦克火箭车互战在第三回合恰好到达1600射程，第三回合就能开火，缺少完整的三个安全指挥回合。
- 混编把不同兵种的最高速度和最长射程叠加：反坦克火箭车与战斗机互战初始宽度4000，第四回合仍无射程内目标。

新公式按真实存活攻守兵种组合取最大值：`D = max(3 × (stepA + stepB) + max(rangeA, rangeB)) + 100`。步长与机动阶段一致，逐兵种取常驻速度乘50后的整数值，含装备、科技、司令官，排除尚未交战的周期闪电突击。安全间隔按用户要求留100点，取消1000最低宽度。

| 编成 | 初始距离 | 第三回合后距离 | 第四回合后距离 | 首次交火 |
| --- | --- | --- | --- | --- |
| 步兵互战 | 860 | 260 | 60 | 第4回合 |
| 反坦克火箭车互战 | 2600 | 1700 | 1400 | 第4回合 |
| 反坦克火箭车/战斗机混编互战 | 3350 | 按兵种位置计算 | 按兵种位置计算 | 第4回合 |
| 步兵攻单独碉堡 | 1450 | 1150 | 1050 | 第4回合碉堡先开火 |

规则保证新战场前三回合进不了射程；待命、后退、后勤默认不前进或实际步长不足100时，首次交火仍可能晚于第四回合。进行中的会话继续使用已保存距离，历史战报不重算。源码修改需后端重启后对新战场生效；本次未重启或部署。

## 验证

13个定向测试类共132项通过，具体结果在同目录各测试类的文本报告和 [summary.json](summary.json)。新增开局测试覆盖全部17种部队和4类工事中有机动能力的425种攻守组合、混编、速度小数取整、科技与司令官、待命、自动战报。真实会话测试验证每回合清除持久化缓存并重新加载后，前三回合兵力不变、第四回合首次交战；技能周期、持续指令、离线续算与演习结算回归均通过。

执行命令（仅编译和测试，不执行自动格式化）：

```sh
MAVEN_OPTS='-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8' mvn -o -f backend/pom.xml -Dtest=BattleOpeningDistanceTest,TacticalBattleRoundTest,BattleServiceTest,CommandBonusBattleTest,OfficerSkillCycleTest,BattleDamageTest,BattleCompositionTest,PvpRulesTest,PvpCalibrationTest,TacticalPersistentOrdersTest,OfficerSkillPersistenceTest,PvpPracticeTest,TacticalBattleSchedulerTest test
```

新增持久化测试中的JSON解析方法名修正后，重新执行以上完整范围，132项全部通过；成功日志见 [backend-regression.log](backend-regression.log)。

另运行的`BattleMechanicsTest`有8项既存失败，涉及旧的步兵攻击、射程和索敌预期。已在隔离临时副本恢复修改前的距离公式及规则版本，复现完全相同的8项失败，不属于本次距离变更；证据见 [preexisting-failures.log](preexisting-failures.log)。未修改这些旧测试。

## 演习校准

战斗规则版本参与演习实力模型哈希，更新规则后旧校准配置会被启动校验拒绝，因此已用新引擎重新测量并更新发布配置，没有套用旧系数或仅替换版本标签。

模型版本：`power-v1-pvp-v1-ee6a1fd2c9278b0c82eec5b7`。陆空/海空8种技能1～5级及3类技能组合，共86份配置、564160场固定种子镜像模拟；最大校准残差约2.359个百分点。独立种子与未参与校准的混编复核5184场，最大平均价值损失差约3.684个百分点。A侧胜率36.84%、B侧42.40%、平局20.76%，仅作为合成阵容测试证据，不代表真人匹配公平性或策略分布。

证据：[skill-calibration.json](skill-calibration.json)、[holdout-evaluation.json](holdout-evaluation.json)。生成程序沿用`scripts/PvpCalibrationTool.java`和`scripts/PvpHoldoutTool.java`，只在隔离Java运行环境执行数值模拟，未操作真实游戏数据库。
