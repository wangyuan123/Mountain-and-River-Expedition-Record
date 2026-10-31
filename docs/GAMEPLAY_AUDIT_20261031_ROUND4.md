# 游戏功能排查 · 第四轮（2026-10-31）

后续修复记录见 [第四轮玩法修复与验收](GAMEPLAY_AUDIT_20261031_ROUND4_FIXES.md)。本文保留修复前的排查证据和当时验证结果。

本轮按主要功能分组完成源码核查与既有全量回归，没有按发现数量停止。共记录 **23 类缺陷/实现一致性问题（P1：6 类，P2：14 类，P3：3 类），另有 1 项外交规则需设计确认**。其中既有隔离记录、当前源码、当前测试失败分别标注，不能把源码结论都称为线上复现。

当前尚有已确认问题及测试失败，**不具备“整个项目功能没有问题”的结论**。本轮为排查交付，没有修复这 23 类业务问题；前两轮已修复的问题不重复计数，用户明确暂缓的低级 NPC 重复晋升不处理。

本轮没有改动游戏业务代码、样式、线上配置或数据库迁移。验证使用测试配置与隔离数据库，没有连接实际游戏数据库，也没有重新启动正在使用的游戏服务。

## 全量验证结果

| 检查 | 当前结果 | 说明 |
| --- | --- | --- |
| 后端全量 | 2,229 项；2,215 通过，11 失败，3 跳过 | 隔离 H2；失败项逐类分析，不能将旧断言直接计为业务缺陷 |
| 前端全量 | 稳定文件版本下 1,287 项；1,277 通过，10 失败 | 失败集中在旧“讨伐/campaign”按钮断言，与当前征服/掠夺入口不一致 |
| 启动与部署脚本 | 60 项通过 | 沙箱内进程检查受限；获准在沙箱外执行隔离脚本测试后全部通过 |
| 前端业务脚本语法 | 61 个通过 | `node --check`，只读 |
| 首页本地静态引用 | 81 条，未发现缺失 | 包括脚本、样式与静态入口 |
| JS内明确图片路径 | 161 个去重路径，未发现缺失 | 不包括运行时拼接路径及浏览器最终渲染 |
| 接续定向补测 | 3 项；2 通过，1 失败 | 针对全量检查后变更的统帅名读取；失败为旧的用户名回退预期，详见下文 |

排查期间出征模式源码和测试仍在更新，早先前端全量出现过20项失败；不能把不同文件版本的结果相加。最后一次前端检查前记录926个源码/测试文件摘要，检查后未发现摘要变化。后端日志对应其自身执行时的工作区，不代替固定提交版本的发布验收。

截至接续核对记录，上述926个文件有924个摘要保持一致；`GameStateService.java` 的统帅名读取已改为共用 `PlayerNames.commanderName`，`DepotItemBroadcastTest.java` 增补了页头与公告回退名一致的断言。重新阅读相关代码后，本报告所列问题仍成立；额外执行了新注册默认统帅名、状态中的显示名、冷却到期改名发布状态三项检查，没有重跑整套全量测试。新增失败不能直接与先前全量的11项相加后称为一次全量结果。

## 问题清单与修复方向

优先级：P1 为资产、冻结边界等应先处理的问题；P2 为核心规则、结算或配置一致性；P3 为统计、提示及数据清理。金额及库存问题只记录风险、受影响位置与防护建议，不提供异常请求或利用步骤。

### R4-01 · P1 · 阶段礼包的钻石未进入账号共享钱包

证据级别：隔离记录与源码。

在分城开启阶段礼包时，钻石记入分城资源记录，账号消费读取的主城钱包未收到这笔奖励。

建议：五类城市资源仍按受领城发放；钻石统一使用账号钱包，并将背包扣减与整组奖励作为一个事务。

位置：[DepotService.java:687](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/DepotService.java:687)；[CityScope.java:71](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/CityScope.java:71)。

### R4-02 · P1 · 阶段礼包钻石入账缺少余额上限校验

证据级别：隔离记录与源码。

礼包可以完成开启，但钻石余额超出字段容量时会写成异常值，玩家资产与成功提示不一致。

建议：发奖前检查所有余额能否容纳奖励；不满足时保留礼包、余额及领取资格。

位置：[DepotService.java:706](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/DepotService.java:706)。

### R4-03 · P1 · 通用道具入库缺少数量上限校验

证据级别：隔离记录与源码。

额外奖励及通用入库使用普通整数相加，库存到达字段边界时可能变成异常数量。此问题不限于钻石字段。

建议：统一使用经过范围校验的入库方法；礼包附赠、装备返还、VIP奖励等调用方共同遵守整组原子提交。

位置：[DepotService.java:767](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/DepotService.java:767)；[EquipmentService.java:155](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/EquipmentService.java:155)。

### R4-04 · P2 · 同一本经验书从不同入口使用，升级行为不同

证据级别：隔离记录与源码。

仓库入口会自动扣经验升级，军官入口只累积经验并等待手动升级。同一道具的属性点、等级及剩余经验取决于入口。

建议：确定一种培养规则，由两个入口调用同一服务；批量使用和满级书同步处理。

位置：[DepotService.java:423](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/DepotService.java:423)；[OfficerService.java:645](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/OfficerService.java:645)。

### R4-05 · P2 · 洗点重造出生属性，并可能改变主属性方向

证据级别：隔离记录与源码。

洗点没有恢复军官真实初始属性，而是套用按星级推算的模板；五星主属性又按当前最高属性判断。正常培养后洗点可能改变军官本身资质。

建议：保存或可靠还原出生属性与实际分配点；历史军官无出生快照时制定兼容规则，不能声称模板就是原值。

位置：[OfficerService.java:886](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/OfficerService.java:886)。

### R4-06 · P2 · 任命市长会追溯改变未结算时段的资源产出

证据级别：隔离记录与源码。

任命前只结粮账，没有结清全部旧经济区间；下次结算以新市长属性计算此前产出。装备与内政技能的相关变更也应纳入统一边界。

建议：在配置变化前按旧配置结清受影响城市经济，再更新任命、装备或内政技能。

位置：[OfficerService.java:267](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/OfficerService.java:267)；[TickService.java:554](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/TickService.java:554)。

### R4-07 · P2 · 民心恢复对结算频率敏感

证据级别：隔离记录与源码。

相同一小时，单次结算会直接到目标民心，拆成两个半小时却只按每小时10点漂移。离线与持续在线会得到不同结果。

建议：改为统一的按时长漂移规则，并保存必要的小数累计；去除一小时处的突变分支。

位置：[TickService.java:238](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/TickService.java:238)。

### R4-08 · P2 · 普通粮账与驻军粮账在仓库封顶时结果不一致

证据级别：隔离记录与源码。

普通粮账先限制产出再扣兵粮，驻军粮账使用净收支。相同农场、兵力、库存与时长会得到不同粮食结余。

建议：统一净收支与容量处理顺序，保留超额库存和小数，不让是否启用驻军账本改变养兵规则。

位置：[TickService.java:180](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/TickService.java:180)；[GarrisonFoodService.java:135](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/GarrisonFoodService.java:135)。

### R4-09 · P2 · 产量科技完成时可能追溯增加历史产出

证据级别：隔离记录与源码。

科技先完成、经济后结算的路径会用新科技等级计算整个旧区间，使结果取决于结算入口顺序。

建议：按研发实际完成时刻分段结算；账号共享科技应覆盖各受影响城市的旧经济区间。

位置：[TechService.java:311](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/TechService.java:311)；[TickService.java:329](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/TickService.java:329)。

### R4-10 · P2 · 离线期间资源建筑完工后的产出漏算

证据级别：隔离记录与源码。

经济先结算、施工后完工。离线期间已经建好的资源建筑，到下一次结算才开始产生收益，完工后至本次结算之间的产出丢失。

建议：将资源建筑完工视为经济事件，在真实完工时刻切换产能并分段计算。

位置：[TickService.java:329](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/TickService.java:329)；[BuildService.java:644](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/BuildService.java:644)。

### R4-11 · P2 · 护盾到期会丢失此前保护期的生产加成

证据级别：隔离记录与源码。

查询城市状态时先清除已到期护盾，再用当前状态计算整个历史区间；晚结算的玩家少拿护盾有效期间的产出加成。

建议：保留结算所需的状态时间边界，按有效区间计算加成后再清理到期状态。

位置：[TickService.java:630](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/TickService.java:630)；[TickService.java:154](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/TickService.java:154)。

### R4-12 · P1 · VIP全域政务会修改归降冻结城市

证据级别：隔离记录与源码。

免费特赦和黄金安抚遍历城市时没有按城市可写状态筛选，冻结中的民心、民怨及黄金仍可能被改变。

建议：在事务内重新核对每城归属、建设和冻结状态，明确跳过原因；冻结资产应保持不变。

位置：[VipService.java:296](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/VipService.java:296)；[CityScope.java:52](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/CityScope.java:52)。

### R4-13 · P3 · VIP特赦受影响城市数量多算主城

证据级别：隔离记录与源码。

查询结果已经包含主城，返回数量又加一，成功提示会比实际拥有城市多一座。

建议：主城与分城统一去重，统计实际处理的城市；不要同时维护两个主城表示的计数。

位置：[VipService.java:313](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/VipService.java:313)；[VipService.java:339](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/VipService.java:339)。

### R4-14 · P3 · 军团解散后入团申请残留

证据级别：隔离记录与源码。

团长解散最后一人的军团时，只删除成员和军团，待审核申请仍留在表内。

建议：解散事务一并清理申请和其他从属关系；迁移清理历史孤立记录并检查相关外键约束。

位置：[GuildService.java:212](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/GuildService.java:212)。

### R4-15 · P2 · 论坛累计打赏金额使用窄整数统计

证据级别：隔离记录与源码。

资金转移与累计展示采用不同数值容量，累计打赏较大时统计会变成异常值。

建议：累计金额使用与黄金账本一致的宽整数，并同步实体、迁移与返回字段。

位置：[ForumService.java:465](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/ForumService.java:465)。

### R4-16 · P2 · 战报注销脱敏不能正确识别统帅显示名

证据级别：隔离记录与源码。

战报常保存显示名，脱敏却用用户名查注销账号；有显示名的历史参与者无法按约定隐藏。仅按名称匹配也缺少稳定身份。

建议：战报与分享使用稳定玩家ID关联脱敏，兼容历史姓名快照；不要将名字相同当作身份相同。

位置：[ReportPrivacyService.java:16](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/ReportPrivacyService.java:16)；[AccountService.java:178](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/AccountService.java:178)。

### R4-17 · P1 · 注销账号的有效挂单没有结束资产生命周期

证据级别：隔离记录与源码。

注销清理移除卖方资源记录后，活动挂单仍可继续成交；购买扣款可以成功，卖方结算分支却找不到金库。

建议：清理账号时原子关闭活动挂单；交易提交前校验有效卖方与结算账户，确保扣款和结算整体成功或整体失败。

位置：[AccountService.java:178](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/AccountService.java:178)；[MarketService.java:151](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/MarketService.java:151)。

### R4-18 · P2 · 发布的PvP校准配置与当前战斗版本不一致

证据级别：既有全量测试与源码。

当前模型版本与随包校准表不匹配，旧行被加载器跳过，首次遇到相应组合会转入运行时校准；配置验收因此失败。

建议：为当前战斗契约发布真实测得的校准数据，检查版本覆盖率；不要只改版本号或放宽断言。

位置：[PvpPowerModel.java:29](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/pvp/PvpPowerModel.java:29)；[PvpSnapshotService.java:25](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/pvp/PvpSnapshotService.java:25)。

### R4-19 · P2 · 账号单份战争状态无法完整承载多方战争

证据级别：源码确认，未新增运行实验。

宣战重复检查只保护同一对手及被选目标城，账号却只保存一份对手、备战与终战字段；跨对手或跨城市的合法战争流程可能覆盖旧关系。

建议：若每个账号只允许一场战争，应检查双方全部有效战争；若允许多方战争，应独立建模战争关系，统一地图、计谋与出征读取。

位置：[WorldService.java:515](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/WorldService.java:515)；[WorldService.java:545](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/WorldService.java:545)；[MarchService.java:4001](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/MarchService.java:4001)。

### R4-20 · P3 · 军团详情与排行榜的人员统计资格不同

证据级别：源码确认，未新增运行实验。

排行榜排除未初始化、注销与封禁账号，军团详情的声望和人数直接汇总全部成员。同一军团在两个入口可能显示不同值。

建议：共用统计口径；若详情需要显示名册总数，明确区分名册人数与榜单有效人数。

位置：[GuildService.java:265](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/GuildService.java:265)；[LeaderboardService.java:22](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/LeaderboardService.java:22)。

### R4-21 · P2 · 旧粮耗汇总仍采用整数乘加

证据级别：源码确认，未新增运行实验。

普通粮账的每小时粮耗仍使用int乘法与累加，容量与新驻军账本采用的宽类型不一致，大规模存量军队存在计算失真风险。

建议：将粮耗汇总及其调用返回值统一为宽类型，在写回库存前做合法范围处理。

位置：[TickService.java:586](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/TickService.java:586)；[GarrisonFoodService.java:45](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/GarrisonFoodService.java:45)。

### R4-22 · P1 · 普通安抚和主动撤盾未统一遵守城市冻结约束

证据级别：源码确认与既有撤盾记录。

通用请求拦截没有覆盖这些城市写操作，服务也未统一检查冻结状态；与建筑、生产等城市只读规则不一致。

建议：在服务事务中统一校验目标城市归属及可写状态，外层拦截仅作补充，不能作为唯一约束。

位置：[GameStateService.java:583](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/GameStateService.java:583)；[CityService.java:109](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/CityService.java:109)；[CityContextConfig.java:52](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/config/CityContextConfig.java:52)。

### R4-23 · P2 · 军团支援的注销期限判断混用了游戏与真实时间

证据级别：源码确认，未新增运行实验。

账号恢复截止时间按真实时间保存，支援资格却将游戏时间传给deletionDue；停服顺延后两种时钟不一致，过期账号的判断窗口会偏移。

建议：建设与行军使用游戏时间，账号注销资格单独使用真实时间；统一账号状态校验方法，避免同一参数承担两种时钟。

位置：[GuildSupportService.java:56](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/GuildSupportService.java:56)；[AccountService.java:178](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/AccountService.java:178)。

## 需要确定规则的项目

**D1：军团外交是单方立场，还是双方同盟？** 当前一个军团可以直接修改双方共用的友好/敌对关系，另一方不需要接受。实现行为已经明确，但不能擅自将其改成双向邀请。建议友好同盟采用双方同意，敌对立场单独定义。位置见 GuildRelationService.updateRelation。

## 不计为新增缺陷的线索

- 付费普通安抚可以连续使用：当前界面与设计没有建立必须冷却的契约，不凭单个时间字段擅自增加限制。
- 军官行军期间使用满级经验书：已经发生的战斗使用冻结快照，仅发现可以使用道具不足以证明战斗异常。
- 同一目标城市的重复宣战：既有目标城状态检查会拒绝；R4-19针对账号单份战争结构的其他关系路径，不能混为一谈。
- 并发冲突中事务整体回滚，不等于资产重复支出；不将乐观锁冲突直接当作已发生的资产漏洞。
- 旧技能等级1/2断言与当前默认满级规则冲突，属于回归断言维护，不能恢复已经取消的技能升级规则。
- 旧版存档导入代码没有当前HTTP调用入口，仅存在服务类不代表玩家能够导入任意资产。
- 军团详情内部方法的权限元数据值得统一，但当前公开入口只查询本人军团，不将尚无可达路径的假设报成已确认泄露。

## 最近一次后端全量11个失败的分类

| 测试类 | 失败数 | 当前判定 |
| --- | --- | --- |
| PvpCalibrationTest | 1 | 发布校准表和模型版本不一致，见R4-18 |
| ShipyardPvpCompatibilityTest | 1 | 硬编码旧规则摘要；同类校准文件校验仍需配合R4-18处理 |
| ReconArmoredCarTest | 2 | 伤害预期公式仍含旧技能倍率，需要与当前满级规则核对 |
| PvpPracticeTest | 1 | 预期frenzy等级1，当前规范为5 |
| HeadquartersServiceTest | 2 | 预期bulwark等级2，当前规范为5 |
| ShopControllerTest | 3 | 保留了技能升级及初学等级1的断言，与已确认玩法调整冲突 |
| CityBattleDispatchLockTest | 1 | 仍将已取消的直接派兵采集动作纳入统一出征断言，需维护兼容预期 |

这些失败仍然阻止全量校验通过，不能为了出绿灯直接删断言。应分别更新废弃契约、保留真实行为验证，并修复配置问题。

接续补测另发现 `DisplayNameTest.savesGameDisplayNameWithoutChangingLoginUsername` 仍预期空显示名时回退到登录用户名，而当前状态读取共用工具方法，回退值为“统帅＋ID”。新注册默认名与冷却到期改名的两项检查通过。该差异应维护测试夹具和回退契约，不单凭这一旧断言计为新增游戏缺陷，也不能据此恢复与公告不一致的姓名读取。补测使用既有 `test` 配置和隔离H2，日志为 `name-state-recheck.log`。

## 主要功能覆盖矩阵

“已检查”表示本轮检视入口、规则和相关测试；没有表示逐行证明139个服务的全部状态组合都正确。

| 功能组 | 已检查内容 | 本轮结果或边界 |
| --- | --- | --- |
| 本地启动与部署 | 配置隔离、子进程退出、依赖检查、部署脚本回归 | 60项通过；未向真实部署环境发布 |
| 资源与税收 | 自然产出、仓库容量、工资、民心、税率时点 | R4-06至11、21；前两轮修复回归纳入全量 |
| 军官培养与技能 | 招募、任职、洗点、经验、满级技能、图标 | R4-04至06；静态图标路径检查通过 |
| 军官装备 | 十二部位、库存返还、行军换装约束、属性读取 | 入库边界归R4-03；既有装备测试已运行 |
| 施工与科技 | 到期、取消、前置、退款快照、账号科技范围 | R4-09、10；迁移和退款相关测试纳入全量 |
| 部队与人口 | 完工发奖、成本池、取消排队、粮耗 | R4-08、21；未把用户已确认规则还原为旧规则 |
| 城防、伤兵、战俘 | 总容量、降级预留、治疗和招募时点 | 使用既有回归检查；未新增确认问题 |
| 多城与传送 | 城市上下文、资源归属、冻城、建城、迁城 | R4-01、12、13、22 |
| 世界地图与行军 | 行动类型、路线、返程、野地驻守采集 | 相关全量回归；前端10项旧入口断言待同步 |
| 战斗、侦查与联防 | 战斗快照、兵力来源、守城锁、指挥周期 | R4-19、23；后端旧技能断言待维护 |
| PvP演习 | 版本、快照、校准、入队与回合状态 | R4-18；未新增重型全组合校准运行 |
| 交易所 | 数量价格限制、挂单槽位、成交与生命周期 | R4-17；既有数量价格上限保留 |
| 仓库、礼包与VIP | 资源/道具路由、账号钱包、全域政务 | R4-01至04、12、13 |
| 邮件与活动 | 附件原子性、兵力前置、活动期限、分享留存 | 前两轮修复回归纳入全量；未新增确认问题 |
| 军团、好友、聊天与论坛 | 外交、退团、统计、隐私与资金统计 | R4-14至16、20、23；D1需确定规则 |
| 任务、新手、军衔 | 章节解锁、状态事件、完工奖励、礼包资格 | 既有回归；低级NPC晋升按用户决定暂缓 |
| 账号与后台 | 注销生命周期、会话和权限相关源码及既有回归 | R4-16、17、23；未进行攻击性验证 |
| 前端状态与静态资源 | 异步城市上下文、状态合并、JS语法与资源引用 | 语法/路径通过；完整桌面与移动浏览器仍需环境验收 |

## 尚未完成的环境专项

- MySQL真实隔离级别、锁等待与Flyway全历史升级，需独立可丢弃测试库；本轮没有连接游戏库代替验收。
- 后端跳过的3项中，2项MySQL迁移测试因未设置独立测试库地址而跳过，另1项为已被属性重构取代的历史齐射设计快照；不能把前两项视作迁移验收通过。
- 真实浏览器桌面/手机完整点击流程、WebSocket断线恢复、多个账号长时运行和多人压力测试，现有Node/H2检查不能代替。
- 真实SMTP邮件、生产Nginx/HTTPS、容器重启和网络中断，未对使用中的服务执行此类操作。
- 所有缺陷修复后仍需要在固定提交上重跑全量测试，清零失败并审查跳过项，才能进入发布验收。

## 检查记录

同名输出目录中的`backend-final-audit.log`、`frontend-stable-check.log`、`scripts-final-unrestricted.log`保存全量日志；`coverage-inventory.json`保存功能文件清单；`static-validation.json`保存语法/资源检查；`source-snapshot-before-final-checks.json`保存文件版本摘要；`findings-summary.json`保存本报告的问题摘要。

`source-recheck.json`记录接续摘要核对和3项定向补测结果，`name-state-recheck.log`保留该次Maven日志。接续工作只补充报告与验证记录，没有修复上述业务问题。

推荐先处理共享钱包和入库原子性、冻结城市、注销挂单，再统一经济时间线与军官培养规则，最后同步统计和测试契约。尚未修复的具体问题已经列出，不能将“本轮覆盖完成”表述为“项目已无问题”。
