# 造船厂与海军制造链

## 建筑职责与入口

新增 `shipyard`（造船厂），置于军事建筑页，在军港后展示。每城限一栋，占用一个军事建筑卡槽，最高 Lv.10。首次建造消耗钢铁 400、石油 180、稀矿 60；后续成本乘以 `1.7^当前等级`，沿用建筑科技折扣、施工时间、取消退款及拆除回收规则。

原 `port` 保留存档标识，展示名称由“军港船坞”调整为“军港”，负责舰队停泊、补给及现有海上出征条件。造船厂负责舰船制造，等级决定生产速度和并行数。造船厂图标复用现有透明船坞模型 `frontend/img/buildings/garden/port.webp`；军港与造船厂的名称、职责、等级及操作入口独立。

新建造船厂沿用军港的沿海城准入，已有 `legacyNaval` 城市保留既有海军资格。

## 升级联动

以下前置均取本城已经完成的最高单栋等级，不能跨城借用，不能累加多栋等级，在建升级不能提前满足条件；拆除施工按目标等级扣减可用前置。

| 目标 | 必要建筑前置 |
| --- | --- |
| 军港 Lv.1 | 保留原有：市政厅 Lv.5、重装战车厂 Lv.1、战地兵站 Lv.1 |
| 军港 Lv.N（N≥2） | 原有前置，加造船厂 Lv.(N−1) |
| 造船厂 Lv.N | 市政厅 Lv.max(5,N)、军港 Lv.N、重装战车厂 Lv.max(1,N−1)、炼钢厂 Lv.ceil(N/2) |
| 舰船动力科技 Lv.N | 原有前置，加造船厂 Lv.N |

推进顺序为军港 Lv.1 → 造船厂 Lv.1 → 军港 Lv.2 → 造船厂 Lv.2，依次直到 Lv.10。交替升级既约束舰船工业与驻泊能力，又避免相同等级互为前置的死锁。内陆城的市政厅升级不新增造船厂条件。

运行规则与设计配置同步更新为 `shipyard-2026-10-12-v1`。已有建筑/科技工程继续使用创建时的前置快照；新工程采用新版本。

## 征召与拆除

驱逐舰、潜艇、战列舰、航母均要求本城已完成且未完全拆除的造船厂 Lv.1 和军港 Lv.1。没有新增舰种等级分层；平民、资源、训练科技与现有生产数量规则继续适用。

新舰船队列归属于造船厂，其生产速度与并行数按造船厂计算。前端常驻显示两项建筑的实际/要求等级与缺项，提供前往建造入口，直接调用征召同样进行校验；后端在扣资源和人口前再次校验。缺项时已有舰船仍保留解散入口。陆军、空军继续使用各自原有制造设施。

海军俘虏的黄金招募同样要求本城双建筑，并保留沿海/旧城资格校验，缺项时不扣黄金、不消耗俘虏；释放不要求制造设施。

建造、拆除和征召共用玩家锁。正在生产舰船时不能完全拆除军港或造船厂，取消或完成全部舰船队列后恢复拆除；降到 Lv.1 以上仍允许，已付费队列的生产速度不重新计算。施工或研发任务所依赖的建筑等级，继续由前置快照保护。

## 既有数据与演习兼容

无需数据库结构迁移，不自动赠送或回填造船厂。既有舰队保留；原 `buildingType=port` 的已付费生产队列继续完工，并继续占用本城造船通道，避免新旧设施各产生一套并行能力；新征召需要补建造船厂。原军港等级保留，后续升级采用新前置。

演习版本原先把单位的生产建筑一并纳入摘要。将海军制造设施切换到造船厂时，摘要中的生产建筑字段保留历史军港占位，其余单位、战斗、成本、人口、军官技能及配装参数仍参与版本计算。因此保留已发布的 `pvp-v1-8cc036d5a45e2da5c776e5c1` 和既有实测实力校准，不作废进行中的演习。

## 验证

- 后端相关测试 89 项通过，其中新增 18 项造船厂业务测试和 1 项演习版本兼容测试。覆盖四种舰船、同城双建筑、沿海/旧城准入、施工中状态、扣款前拒绝、生产速度与并行数、拆除保护、研发联动、历史队列和海军俘虏招募。
- 前端相关测试 59 项通过，其中新增 7 项；完整建筑前置图可从零推进至全部 Lv.10，未产生升级环路。
- 前端全量测试 667 项：662 项通过，4 项 `home-module-order.test.cjs` 因既有首页测试缺少模块配置失败，另 1 项 `onboarding-polling.test.cjs` 仍断言旧的“资源建筑”文案，而页面已使用“后勤建筑”；涉及的 `main-view.js`、`onboarding.js` 本次均未修改。
- 已更新正式入口的脚本缓存版本。验收页面与截图保存于 `output/shipyard-validation-20261012/`。

执行命令：

```sh
MAVEN_OPTS='-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8' mvn -f backend/pom.xml -Dtest=ShipyardPvpCompatibilityTest,ShipyardServiceTest,PrerequisiteIntegrationTest,ArmyRecruitmentFoodTest,BuildServiceTest,PrisonerServiceTest,OceanTerrainTest,FreeArmySpeedUpTest,PvpRulesTest,PvpCalibrationTest test
node --test frontend/tests/shipyard.test.cjs frontend/tests/prerequisites.test.cjs frontend/tests/build.test.cjs frontend/tests/army-collapse.test.cjs frontend/tests/combat-data.test.cjs frontend/tests/tech.test.cjs frontend/tests/prisoners.test.cjs
node --test frontend/tests/*.test.cjs
```
