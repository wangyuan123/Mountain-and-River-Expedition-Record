# 1v1匹配演习实施与验收

日期：2026-10-10。依据：[PVP_MATCH_DESIGN_20261010.md](PVP_MATCH_DESIGN_20261010.md)。本次完成本地代码及隔离验收，没有部署，也没有对现有游戏数据库执行迁移。

## 实现对应

| 文档要求 | 正式实现与验证 |
| --- | --- |
| 演习大厅、独立默认战术、首次按可用比例选兵 | `frontend/js/pvp.js`、`PvpPracticeController`、`PvpPracticeService.preset`；来源城市读取默认使用最近演习编队城市，保存会重新校验、返回估值 |
| 冻结驻军、科技、兵种配装、带兵将领装备/技能、司令官属性 | `PvpSnapshotService`的短事务只读复制；合法数量、城市、带兵资格、陆海隔离、战斗兵种与上限校验；司令官技能排除 |
| 不占用、不扣除、不返还真实兵力 | 独立的8张演习表；服务未依赖真实行军创建、真实结算、奖励或任务服务；完整默认演习、认输、回滚及终局重试前后逐行比较真实资料 |
| P/R双窗口与真人随机候选 | `PvpMatchService`的同大区/战场/规则/模型索引候选；双方窗口交集，定级优先、重复对手软惩罚，最合适前5名随机，认领失败重读 |
| 等待计时、取消、租约和双确认 | 服务端时间与队列起点、300秒搜索上限、10秒心跳/30秒租约；15秒接受，20秒加载，统一3秒倒计时；接受者重排新票据，保留起点 |
| 连续确认退出冷却 | 10分钟内3次触发60秒；普通取消不计数；对手提前拒绝时不处罚仍在确认窗口内的另一人 |
| 双边私有草稿、硬截止、恰好一次结算 | 按玩家ID升序锁演习状态再锁会话；递增revision、幂等requestId绑定原操作内容，截止后禁止写入；单方锁定不推进，也不公开对手默认/草稿 |
| 默认、托管、下一回合接管 | 成功保存人工指令优先，否则冻结默认兜底；关闭页面不认输；托管清未锁草稿并锁当前回合，接管不能撤销已经锁定的指令 |
| 两端站位、镜像、24回合对称终局 | 动态距离，服务端全部单位初始x=0或D，B侧显示镜像；全歼/双方全歼/认输/24回合冻结价值战果差≤5个百分点平局；世界超时守方胜规则保留 |
| 结构化技能复盘 | 独立随机引擎记录技能窗口、周期属性、机动速度/闪电突击、先手、射程与交火效果、反击及无效原因；读取已存回合，不按新规则重跑历史 |
| 战报与评级独立 | 参战者限定、最近50份记录、隐藏Elo、最近5场连续同一对手的第6场起不更新评分；VOID不记胜负/评级 |
| WebSocket与恢复 | 提交后推送；只读状态权威恢复，断线3秒队列/2秒战斗轮询，查询期间到达事件完成后补查，心跳也可发现漏推送；旧账号/大区/版本响应不应用 |
| 重启后的过期回合与作废 | 持久化时间轴/草稿/子种子，单次最多恢复4回合，RECOVERING拒绝提交；规则不兼容、加载过期、不可恢复异常或故障超过1小时作废 |

迁移版本在实施时检查为V87，未覆盖V74～V86等已有工作。V87增加队列、截止、战报与幂等请求索引，以及回合/报告/请求唯一约束。测试在H2 MySQL模式执行迁移SQL并检查唯一约束；未执行生产MySQL迁移。

## 自动检查

后端23项演习检查全部通过：PvpPracticeTest 13、PvpRulesTest 3、PvpControllerTest 2，以及PvpMigrationTest、PvpPerformanceTest、PvpEventsTest、PvpMatcherTest、PvpCalibrationTest各1项。

另外81项回归通过：OfficerSkillCycleTest 31、CommandBonusBattleTest 5、TacticalBattleRoundTest 11、AccountDeletionTest 11、AntiAddictionTest 16、ComplianceBoundaryTest 7。真实HTTP检查发现并修复原有所有控制器外围单方账号锁与演习独立快照事务之间的锁冲突；演习写事务仍在操作前后校验账号与游戏许可，跨过许可截止整体回滚。推送测试确认事务提交前和回滚后均不会发送虚假队列事件。

前端70项定向检查通过：pvp、tactical-facing、reports、headquarters、onboarding-connection。覆盖输入保留、版本化保存后排队、自动入场、镜像、私有草稿、锁定、未知结果写请求不自动重试、原请求内容不可变、账号/大区隔离与查询中推送恢复。另进行了JavaScript语法检查和涉及共享文件的只读diff空白检查，未运行自动格式化或eslint --fix。

记录见 [test-summary.json](../output/pvp-validation-20261010/test-summary.json)。复现命令：

```sh
JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8 mvn -q -f backend/pom.xml -Dtest='Pvp*Test,OfficerSkillCycleTest,CommandBonusBattleTest,TacticalBattleRoundTest,AccountDeletionTest,AntiAddictionTest,ComplianceBoundaryTest' test
node --test frontend/tests/pvp.test.cjs frontend/tests/tactical-facing.test.cjs frontend/tests/reports.test.cjs frontend/tests/headquarters.test.cjs frontend/tests/onboarding-connection.test.cjs
```

已有BattleMechanicsTest的8项失败独立保留，涉及旧伤害/基础属性/射程/目标优先断言。已将本次PVP新增部分移除后的引擎单独编译，并使用相同当前项目及测试类复查：仍出现相同8个失败和期望/实际值。没有更改既有战斗行为或放宽断言来让这些检查变绿；不能宣称全项目测试全部通过。

## 校准证据与限制

发布配置为 `backend/src/main/resources/game/pvp-power-calibration-v1.json`，模型版本 `power-v1-pvp-v1-8cc036d5a45e2da5c776e5c1`。覆盖陆空/海空、8种战斗技能1～5级以及每类3种完整技能组合，共86份测量。标准参考阵容、规模100/1000、科技0/5、配装0/10%、种子71001/71002与A/B互换，七步数量折扣二分，共564160场模拟；校准平均战果残差最大约2.492个百分点。无战斗技能的系数1来自没有技能增益；不是全表占位1。未列出的合法组合在生成新快照前整体模拟并保存版本化结果，不在匹配扫描中模拟。

独立评估用未参与校准的混编、科技3、配装5%、目标P=200000、种子83011/83012/83013和镜像，再运行5184场。54种技能配置各96场，平均敌我价值损失差最大绝对值4.912个百分点，平均胜平负得分范围0.417～0.563。A侧胜率40.55%、B侧38.43%、平局21.03%，本样本两侧相差2.12个百分点，不能解释为不存在人口偏差。技能修正仍是匹配估值，未写入实际伤害。

证据：[skill-calibration.json](../output/pvp-validation-20261010/skill-calibration.json)、[holdout-evaluation.json](../output/pvp-validation-20261010/holdout-evaluation.json)。生成脚本：`scripts/PvpCalibrationTool.java`、`scripts/PvpHoldoutTool.java`。校准测试检查全部等级、配置版本、有效样本和实测系数。该集合验证参考表现，无法保证任意克制阵容互战五五开，也未包含真人指挥策略分布。

## 本地并发基准

隔离H2 MySQL模式、关闭世界经济Tick，100个不同账号各1张票据，8个并发入队写者，8个回合写者对每场重复调度两次。所有100人配成50场，未出现双重占用或重复回合，作废率0。最终记录：

| 指标 | 实测 |
| --- | --- |
| 入队写事务时长 | P50 3.18ms / P95 47.44ms / 最大47.65ms |
| 两轮匹配扫描耗时与SQL语句数 | 324.06ms / 1754条，包含验证锁、状态和提交后通知数据读取 |
| 同批有效匹配等待 | P50 221ms / P95 320ms / 最大327ms |
| P差 | P50 0% / P95 2.91% / 最大8.99% |
| R差 | 全部0，同初始1000评分的合成账号 |
| 一批到期回合的实际结算延迟 | P50 46ms / P95 73ms / 最大75ms |
| 作废/重复结果 | 0 / 0 |

证据：[concurrency-benchmark.json](../output/pvp-validation-20261010/concurrency-benchmark.json)。基准将持久化时间轴设为刚到期来测量批恢复，不用缩短正式15秒规则；等待统计为同时到达的合成人口，不能推算真实排队时间。尚未测量生产MySQL执行计划、跨进程集群容量及真实人口等待，发布后需继续观测。

## 真实页面验收

独立127.0.0.1:18080服务使用H2内存库，测试账号主动登录、排队、接受和报告加载。使用正式前端与后端，不使用原型示例对手。保留原有8080/8081服务和用户原型浏览器页。

双方从匹配成功直接进入指挥面板，服务端默认战术持续推进；第一场双方全程无命令，约6分06秒完成24回合并判平局。我方虚拟剩余166、对方132，造成战果86.8%与83.4%，真实驻军仍1000。刷新后的真实主界面显示1000；独立战报显示实际损失0。已检查390×844窄屏复盘。

证据：[完整默认对局JSON](../output/pvp-validation-20261010/real-default-report.json)、[桌面复盘](../output/pvp-validation-20261010/real-report.jpg)、[窄屏复盘](../output/pvp-validation-20261010/real-report-mobile.jpg)。服务器关闭时产生的历史WebSocket连接错误不属于正式运行中的PVP异常；测试完成后仅清理本任务创建的本地测试进程和标签。

最新构建另一次页面操作确认了开局我方x=0、对手x=1000、私有待命草稿保存提示、单方锁定后仍停在同回合且对手继续指挥、进入默认托管和下一回合接管；最终认输记负，实际损失0。390px指挥页面的有效宽度与scrollWidth同为384px，没有页面横向溢出。最新服务运行窗口内浏览器error/warn为0。证据：[锁定指令](../output/pvp-validation-20261010/real-command-lock.jpg)、[窄屏指挥](../output/pvp-validation-20261010/real-command-mobile.jpg)、[认输复盘](../output/pvp-validation-20261010/real-surrender-report.jpg)。
