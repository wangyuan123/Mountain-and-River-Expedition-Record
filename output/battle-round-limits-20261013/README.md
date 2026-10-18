# 世界战斗回合上限调整

日期：2026-10-13。源码已完成，尚未部署或重启服务。

| 类型 | 最高回合 |
| --- | ---: |
| 掠夺 `plunder` | 28 |
| 征服 `conquer` | 38 |

自动城市/野地结算、逐回合指挥和离线超时推进共用服务端 `BattleService.maxRoundForAction`。自动野地战原先固定传入conquer，现按行军保存的真实action传入；手动战从BattleSession.action读取。战术接口的maxRound和客户端展示同步更新，客户端优先采用服务端上限。

最后一回合完整执行机动和交火后再判超时；全歼敌军仍判胜，任一方提前全灭仍立即结束。进行中会话按已有类型延长上限，回合数和战斗快照不重置；类型缺失按征服处理，旧tactical_plunder标识按掠夺处理。已结束战报不重新结算。

独立演习继续24回合，演习规则摘要和实测校准保持有效。resolveWild是没有出征类型的基础模拟入口，继续24回合；正式自动野地出征已使用带action的startWorldDispatch。此次不改变机动、射程、伤害或科研规则。

## 验证

42项后端定向测试通过，见 [backend-regression.log](backend-regression.log)：

- `BattleRoundLimitTest`：8项，覆盖24回合后继续、上限前一回合不结束、28/38回合撤退、自动与逐回合上限一致、最后一回合胜利优先、演习仍为24回合以及历史类型兼容。
- `TacticalBattleLimitIntegrationTest`：6项真实出征测试，覆盖自动野地plunder/conquer、手动执行和离线超时逐回合推进、数据库刷新恢复、正确终局撤退及战报记录。
- 更新的默认征服38回合用例通过；调度器、火力密度、演习规则/快照/发布校准与生产设施兼容回归通过。

75项前端定向测试通过，覆盖战术上限显示、缺字段/旧类型回退、服务端值优先、刷新、战报与演习页面。`git diff --check`通过。只运行定向验证，没有自动格式化或lint修复。

实际执行：

```sh
MAVEN_OPTS='-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8' mvn -o -f /tmp/fire-density-pom.xml '-Dtest=BattleRoundLimitTest,TacticalBattleLimitIntegrationTest,TacticalBattleRoundTest#finishesDefaultConquestAtThirtyEighthRoundWhenBothSidesSurvive,BattleFireDensityTest,PvpRulesTest,PvpCalibrationTest,PvpPracticeTest,ShipyardPvpCompatibilityTest,TacticalBattleSchedulerTest' test

node --test frontend/tests/tactical-facing.test.cjs frontend/tests/constants.test.cjs frontend/tests/pvp.test.cjs frontend/tests/reports.test.cjs
```

Maven沿用上一任务的临时隔离POM：源码及资源指向工作区，构建输出在/tmp/fire-density-maven-target，测试工作目录指向backend，避免使用工作区已有的IDE增量编译残留。没有删除工作区target或修改真实数据库；并未宣称全仓库测试通过。

最新设计见 [战术指挥规范](../../docs/BATTLE_COMMAND_AND_DISTANCE_DESIGN.md#6-掠夺与征服的回合上限2026-10-13)。此前火力密度的24回合离线模拟属于当时规则下的历史对照，本次未重算或改写那些结果。
