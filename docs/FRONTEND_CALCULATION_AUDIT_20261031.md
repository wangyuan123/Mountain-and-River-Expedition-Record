# 前端业务计算与后端权威边界检查

> 本文保留修复前的审计证据；后续迁移已完成，当前实现与验证结果见 [服务端核心规则迁移记录](SERVER_GAMEPLAY_AUTHORITY_20261031.md)。

检查日期：2026-10-31。范围：当前工作区 `frontend/js` 全部 61 个生产脚本、主页面脚本入口，以及涉及资产、军官、建造、科研、行军、战斗、交易和奖励的后端请求 DTO、控制器与服务。第三方绘图库、图片生成脚本、测试与预览页面不作为游戏结算入口。

## 结论

当前尚不满足“所有核心业务计算都在后端执行”的严格要求。前端仍有大量费用、属性、产能、资格和效果预览公式，也保留了直接改写本地资产的旧代码。

已追踪的正式结算链路有后端计算实现，操作请求主要提交对象 ID、数量和操作意图。此次源码核查未发现这些链路把前端计算出的费用、经验、伤害、胜负或奖励直接作为持久化结果；不能将“存在前端公式”直接认定为“已能修改服务器资产”。这也不是全部接口已通过防脚本攻击验收的证明。

重复计算已经造成四处可确认的前后端偏差，见下文。按严格要求，应把业务预览也交给后端，前端只显示结果并提交意图。

## 仍在前端执行的业务计算

下面每行是一组业务能力，表内位置给出代表入口；同一公式在详情、卡片与确认弹窗可能多次出现。

| 能力 | 前端计算与位置 | 后端当前执行位置 | 严格要求下的处理 |
| --- | --- | --- | --- |
| 城市资源经济 | [core.js:215](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/core.js:215) 的生产容量、库存上限、仓库保护量、产量曲线、科技／市长倍率、粮耗、黄金收支；`resourceNetRate` 等存在缺字段时自行计算的回退 | `TickService.resourceRates`、经济结算、`GarrisonFoodService`；状态已有 `resourceRates`、`foodSupply` | 直接消费服务器速率和容量；缺字段显示待同步，移除公式回退 |
| 人口、税收与安抚 | [main-view.js:819](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/main-view.js:819) 的税率预览、目标民心、有效人口容量；929 行安抚黄金费用；966、1021 行人口令新增人口 | `GameStateService.setTax/appease`、`TickService`、`PopulationEconomy`、`DepotService.usePopulationOrder` | 后端返回所选税率、安抚和人口令的预览结果 |
| 建筑升级与拆除 | [build.js:13](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/build.js:13) 的专属工期曲线、费用、等级上限、科技折扣；659、1075 行拆除返还；多处建筑收益与容量预览；固定施工队上限 | `BuildService.upgrade/dismantle/calcBuildCost/calcBuildTime/completeUpgrade`、`VipService` | 增加只读报价，升级／拆除共用后端规则，返回费用、工期、返还、资格与限制 |
| 科技研究 | [tech.js:16](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/tech.js:16) 的成长费用、黄金曲线、工期、市长加速、当前与下一级效果；`constants.js` 的 `researchAccess` | `TechService.startResearch/calcTechCost/calcTechDuration`、`TechnologyRules`、`PrerequisiteService` | 服务端返回研究与等级效果预览、可研究资格 |
| 招兵 | [army.js:49](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/army.js:49) 的总费用、人口消耗、最大可招数量；93 行每栋训练通道、产能、平均速度；760 行基础生产时间 | `ArmyService.recruit`、`UnitUnlockService`、`ArmyPopulationService` | 后端批量返回各兵种报价、最大数量、有效训练速度、排队与交付时间 |
| 城防 | [fort.js:27](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/fort.js:27) 的容量；页面内费用与城墙加固属性；[main-view.js:298](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/main-view.js:298) 再算墙体生命、防御 | `FortService.build/dismantle/admissionCap`、战斗墙体规则 | 返回当前／施工期间有效容量、造价和战斗属性 |
| 伤兵治疗 | [wounded.js:146](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/wounded.js:146) 的整笔黄金／钻石费用、VIP 折扣取整，以及二分最大可治疗数 | `WoundedService.goldCost/diamondCost/heal`；请求只传批次、数量、币种 | 治疗预览和最大可支付数量由后端计算 |
| 军官经验与培养 | [save.js:10](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/save.js:10) 的升级经验曲线；[officer.js:1228](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/officer.js:1228) 使用经验书后的等级／剩余经验模拟；1497 行可分配属性空间 | `OfficerExperienceRules`、`OfficerService.useExpBook/levelUp/assignAttr` | 军官状态返回升级所需经验、允许加点范围，经验书预览由后端执行 |
| 军官招募、赏赐与学习资格 | [officer.js:118](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/officer.js:118) 招募费用；2207 行赏赐费用；227、857 行技能学习资格 | `OfficerService.recruit/reward/learnSkill`、`DepotService` 技能书路径 | 返回招募／赏赐报价和每项技能的可学习状态、原因 |
| 军官装备与套装 | [constants.js:958](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/constants.js:958) 生成装备属性；985 行 `bonuses` 累计共鸣；1003 行 `preview` 计算换装净增益 | `EquipmentService.calculateAttributes/equipLoadout`、`OfficerEquipmentDef`；现有军官状态已含有效属性和套装信息 | 装备目录由服务端下发，换装方案提交后获取服务端预览 |
| 岗位、科技与技能倍率 | [core.js:490](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/core.js:490) 的攻、防、生命、机动、负重、医疗、内政技能倍率；[play-guide.js:123](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/play-guide.js:123) 再计算任命效果 | `CommandBonus`、`BattleService`、`BattleSkillEffects`、经济／科研服务 | 返回当前有效属性、岗位任命效果、技能效果描述，删除客户端业务倍率表 |
| 带兵上限、动员与出征队伍上限 | [core.js:568](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/core.js:568) 军衔、统帅技能与动员倍率；593 行同时行军上限；[mobilization.js:18](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/mobilization.js:18) 有效档位与150行附近续时预览 | `ArmyService.armyCapAt/ordinaryArmyCap`、`MarchService.getMaxMarches`、`MobilizationService` | 返回上限及构成、当前有效动员效果、使用数量对应的续时预览 |
| 行军与拦截 | [world.js:976](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/world.js:976) 的负重、最慢速度、时间；1050 行相遇拦截点；1122 行往返油耗；1248、1328 行正式页面仍调用本地公式 | `MarchService.previewRoute/createDispatch/previewInterception/calcMarchFuel`、`MarchRouteService`、`InterceptionPlanner` | 已有 `/api/game/world/route` 返回 `seconds/oil/cargoLimit`，应完整使用；移除本地业务计算与离线结算分支 |
| 野地采集 | [constants.js:316](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/constants.js:316) 的累计采量、剩余矿量、多个任务汇总，按时间线推算采集数量；[world-map.js:2206](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/world-map.js:2206) 用兵力／科技重建采集负重 | `MarchService.startWildGather/stopGatherMarch`、采集结算与收获限制 | 数量和负重只显示服务器快照；采集进度条可依据服务器起止时间插值，不将插值当成已获得资源 |
| 交易与商城 | [market.js:175](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/market.js:175) 的兑换收益；215 行附近税率、兑换率、挂单槽位；商城本地商品价格和总价 | `MarketService.createOrder/buyOrder/systemExchange`、`ShopController.buy/ShopItemPrices` | 商品目录、有效交易限制、报价由服务端下发；正式交易仍从数据库重算 |
| 限时道具效果 | [play-guide.js:53](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/play-guide.js:53) 的护盾叠加、行军加速同档刷新／高档覆盖、结束时间和可用资格；`item-buffs.js` 再选择最高效果 | `DepotService.useShield/useMarchOrd`、`ItemBuffService`；单支行军计谋已有服务器预览 | 道具使用前由后端返回效果预览；前端只显示结束时间与倒计时 |
| 战斗有效属性与技能窗口 | [battle.js:2709](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/battle.js:2709) 有效射程；2727 行下一回合速度和接战技能周期；[play-guide.js:181](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/play-guide.js:181) 再判断窗口 | `BattleService.resolveWorldRound/resolvePracticeRound`、`BattleSkillEffects`；PvP 有服务端实力和属性模型 | 战场返回每单位的有效射程、下一回合机动和技能窗口，前端仅据数值绘图 |
| 战损归因与历史战报重建 | [battle.js:2584](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/battle.js:2584) 与3100行附近按伤害比例分摊击毁数；1068 行根据日志和幸存者推算初始兵力、战损 | 结算及战报由 `BattleService`、`MarchService` 持久化；事件有 `targetLosses` 时前端优先使用 | 新战报下发完整归因；历史缺失值标记未记录，避免客户端推算被误认为真实战果 |
| 粮食支撑时间 | [play-guide.js:149](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/play-guide.js:149) 从库存和净粮耗计算耗尽时间 | `GarrisonFoodService` 已提供权威粮账，耗尽时间目前是前端估算 | 严格规则下由后端给出 `estimatedDepletedAt` 与估算范围 |
| 建造／研究／军事操作资格 | [prerequisites.js:12](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/prerequisites.js:12) 从建筑和待拆除队列重新判断前置；`world.js` 检查忙碌军官、兵力、保护期等；`chat.js`、`pvp.js` 判断声望门槛 | 建造、研究、出征、军团、聊天、PvP 均有相应服务端资格检查；兵种准入已采用 `unitUnlocks` | 向 `unitUnlocks` 模式统一：下发 `allowed`、限制和拒绝原因；前端禁用按钮只作交互反馈 |

另有 [data.js:248](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/data.js:248) 的 NPC 兵力、奖励倍率派生和旧战役奖励静态表，属于客户端重复规则定义；正式战斗奖励来源是后端。商品、科技、技能和装备的数值目录也应由服务端统一下发，文案、图标映射可保留在前端。

## 应移除的本地状态改写残留

| 位置 | 行为 | 可达性与影响 |
| --- | --- | --- |
| [core.js:207](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/core.js:207) `enterWarState` | 本地写入战争状态和截止时间 | 生产脚本中未找到调用点；不是服务器宣战入口 |
| [core.js:600](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/core.js:600) `addOfficerExp` | 本地加经验、循环升级并增加属性点 | 生产脚本中未找到调用点；与正式军官接口并存，容易被后续代码误用 |
| [core.js:631](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/core.js:631) `payCost` | 本地扣资源 | 生产脚本中未找到调用点；不能产生服务器扣款 |
| [core.js:699](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/core.js:699) `addPrestige` | 按费用计算并本地增加声望 | 生产脚本中未找到调用点；不能产生服务器奖励 |
| [save.js:20](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/save.js:20) `growAttr` | 客户端随机属性成长 | 生产脚本中未找到调用点；正式随机生成在服务端 |
| [world.js:1478](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/world.js:1478) 拦截离线回退 | 本地扣油、扣兵并创建行军 | 仅缺少 `G.API.worldDispatch` 时走入；正常联网路径会先返回。删除该回退，API 缺失时明确报错 |

这些残留可以伪造当前浏览器画面，但本轮未找到把该本地状态回写服务器的链路。应删除而不是补做一套客户端校验。

## 已确认的四处规则偏差

1. **训练速度高估**：10 栋 Lv.10 工厂、无训练科技，共30条原始通道。前端将通道封顶20后作为平均速度分母，算出 `2.25`；后端用原始通道30作为分母，算出 `1.5`。证据：`army.js:106` 与 `ArmyService.java:148` 附近。影响生产能力展示，实际队列以后端为准。
2. **建造预览漏算 VIP 加速**：VIP2、无科技／市长加速，新建农田前端预览30秒；后端按5%加速并上取整得到29秒。证据：`build.js:42`、`BuildService.java:945`、`VipConfig.java:72`。
3. **施工队上限拦住合法操作**：前端所有账号固定最多3项施工；后端 VIP1 起允许5项。存在3项施工时，前端确认流程会提前阻断 VIP 的第4、5项工程。证据：`build.js:12/483`、`BuildService.java:145`、`VipConfig.java:63`。
4. **拆除返还预览少算**：Lv.1 农田、建筑科技10级时，前端先将80钢铁基础费用打五折，再计算30%返还，显示12；后端按80的基础费用返还24。证据：`build.js:655`、`BuildService.java:708`。这是两套公式的口径冲突，本轮只确认差异，没有改变现有返还规则。

## 已核对的服务器权威链路

- **费用与资产**：建造、研究、招兵、城防、治疗和商城请求均不需要上传客户端总价；相应后端从定义和持久化状态计算并扣款。市场挂单单价是玩家的合法选择，后端检查范围、计算总价；购买只提交订单 ID。
- **行军**：[DispatchRequest.java:12](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/model/dto/DispatchRequest.java:12) 接收部队、目标、将领、携带物资和行动选择。`MarchService.createDispatch` 查询兵力、带兵上限、目标、路线、速度、负重和油耗；拦截点在后端重新规划，不以客户端提交坐标作为权威结果。
- **战斗**：世界战斗指令提交动作、集火目标与回合号；PvP 提交指令、回合和版本。伤害、幸存者、胜负与结算由服务器战斗引擎计算，未找到上传本地胜负／伤害作为结算结果的普通玩家接口。
- **随机与培养**：军校候选、技能随机抽取、赏赐随机增益、经验与属性分配均由 `OfficerService`／`DepotService` 执行；前端只提交目标与使用意图。
- **任务与晋升**：任务请求只传任务／阶段 ID，进度在后端从业务事件或当前状态计算；笔试上传答案，评分与晋升资格在后端执行。`task-quests.js` 的 `onEvent` 是空桩，不能上传事件刷进度。
- **状态持久化**：`save.js` 的 `G.save` 是空操作，加载读取后端。源码中的 `ImportService.importFromJson` 仍能导入资产，但生产代码未找到调用方或对外控制器映射，不能把它当成已存在的公开导入漏洞；建议后续删除无用服务，防止重新暴露。
- **充值**：旧 `ShopRechargeRequest` 虽保留 `rmb/diamond` 字段，[ShopController.java:57](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/controller/ShopController.java:57) 直接拒绝充值请求，未执行这些字段的到账计算。
- **账号与城市身份**：控制器从当前认证身份获得玩家 ID；`CityContextConfig` 对 `X-City-Id` 进行所有权校验。客户端按钮权限和本地角色值不构成服务端授权依据。

## 迁移原则与验收范围

1. 优先移除本地结算残留；为建造、科研、招兵、治疗、军官培养和道具使用补全只读预览。已有行军预览、兵种解锁快照、粮账和经济速率应复用，避免另建一套公式。
2. 预览和执行调用相同后端规则函数。预览至少包含当前城市、规则版本、输入摘要、费用／收益／工期、可执行状态、拒绝原因和相关限制。执行时重新读取数据库、重新计算并在事务内校验；不能因为客户端提交了报价就直接信任其金额或资格。
3. 数量与价格上限、余额、对象所有权、过期状态、业务冷却、幂等、并发和溢出仍须在后端执行。前端禁用按钮、输入框 `max`、确认弹窗和节流无法阻止脚本直接调用 API。仓库／交易中的玩家输入数量和挂单价格本身无需移除。
4. 前端可保留数字与单位格式化、百分比进度条、基于服务器截止时间的倒计时、已确定路线上的图标动画、坐标投影、地图绘制、筛选排序与表单草稿。它们不决定资产、资格、伤害、收益或生效时间。
5. 迁移后的验证应包括：篡改本地 `Game.state` 和目录无效；伪造费用／奖励／伤害字段无效；负数、小数、极大数量被拒绝；跨账号／跨城对象被拒绝；并发提交与同请求重放不重复发奖；预览后换装、换将、科技变化和队列变化时正确重新报价或拒绝。限流只能限制自动化频率，核心逻辑后移不能单独阻止所有合法操作自动化。

## 本轮证据与限制

- [probe.cjs](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/output/frontend-calculation-audit-20261031/probe.cjs) 在隔离 Node VM 中加载当前前端源码，临时暴露两个私有函数用于检查；不改写生产脚本、不访问 API 或数据库。
- [probe-results.json](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/output/frontend-calculation-audit-20261031/probe-results.json) 保存四个前端实际输出、按后端当前源码／配置推导的对照值，以及全部61个生产脚本的 SHA-256 清单。运行命令：`node output/frontend-calculation-audit-20261031/probe.cjs`，执行成功，全部差异断言通过。
- 后端对照值来自本次源码核查，并非运行中的游戏接口响应。本轮没有迁移生产业务逻辑，没有发起真实资产操作、重启服务或修改配置；也未执行完整接口渗透、并发与全量回归测试。
