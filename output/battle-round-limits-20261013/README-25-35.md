# 世界战斗回合上限：掠夺25、征服35

日期：2026-10-13。根据最新要求，将掠夺上限从28改为25，征服从38改为35；源码与设计文档已同步，尚未部署或重启服务。

| 类型 | 最高回合 | 满回合结算 |
| --- | ---: | --- |
| 掠夺 `plunder` | 25 | 第25回合完成交火后，未全歼则攻方撤退 |
| 征服 `conquer` | 35 | 第35回合完成交火后，未全歼则攻方撤退 |

服务端 `BattleService.maxRoundForAction` 为统一来源，自动城市/野地战、手动指挥、离线超时推进和战术接口共用该上限。客户端默认值同步为25/35，显示优先采用服务端返回的maxRound；缺字段时按类型回退。旧tactical_plunder按掠夺处理，缺类型按征服处理。

末回合全歼敌军仍判胜，任一方提前全灭仍立即结束。进行中会话按保存的action读取当前上限，回合数、坐标和科技快照不重置；历史已结束战报不重新结算。独立演习与没有出征类型的resolveWild基础模拟继续使用24回合。

设计文档：`docs/DESIGN.md`、`docs/BATTLE_COMMAND_AND_DISTANCE_DESIGN.md`。此前28/38回合的测试记录和最近两场战损模拟保留为当时参数下的历史证据，本次未改写其数据。

## 验证结果

16项后端定向测试通过，见 [backend-regression-25-35.log](backend-regression-25-35.log)：

- `BattleRoundLimitTest`：8项，覆盖25/35回合边界、上限前不结束、自动结算同上限、末回合全歼优先、旧类型兼容、默认征服及演习24回合边界。
- `TacticalBattleLimitIntegrationTest`：6项，覆盖真实自动野地出征、手动与离线超时推进、刷新恢复后的maxRound、终局撤退与战报回合数。
- 默认征服第35回合终局和超时调度回归：各1项。

75项前端定向测试全部通过，见 [frontend-verification-25-35.log](frontend-verification-25-35.log)。覆盖战术上限展示、旧视图回退、服务端值优先、刷新、战报与演习页面。针对本次修改的`git diff --check`通过。

实际命令：

```sh
MAVEN_OPTS='-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8' mvn -o -f /tmp/fire-density-pom.xml '-Dtest=BattleRoundLimitTest,TacticalBattleLimitIntegrationTest,TacticalBattleRoundTest#finishesDefaultConquestAtThirtyFifthRoundWhenBothSidesSurvive,TacticalBattleSchedulerTest' test

node --test frontend/tests/tactical-facing.test.cjs frontend/tests/constants.test.cjs frontend/tests/pvp.test.cjs frontend/tests/reports.test.cjs
```

使用临时隔离POM，源码与资源指向工作区，构建输出在`/tmp/fire-density-maven-target`；后端集成测试使用H2，没有修改真实数据库。仅进行定向验证，没有自动格式化或lint修复。
