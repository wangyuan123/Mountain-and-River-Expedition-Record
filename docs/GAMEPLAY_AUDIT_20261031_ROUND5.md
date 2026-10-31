# 游戏合理性排查 · 第五轮（2026-10-31）

修复状态：本报告的 11 项已完成修复及定向验证，见[第五、六轮修复记录](GAMEPLAY_AUDIT_20261031_ROUND5_6_FIXES.md)。以下保留排查时的现象与历史验证记录。

接续第四轮修复，记录 **11类问题：8类有隔离运行或前端计算证据，3类为源码确认**。商城入库属于第四轮容量校验的遗漏；粮账上限属于已修复算法在长时间未结算场景下的延展问题，不把既有修复整体判为失效。

本轮只排查，未修改业务代码、界面样式、数据库迁移或实际游戏数据。新增文件仅为本报告及隔离检查记录。测试没有启动HTTP服务；数据源固定为独立H2内存库，WebSocket推送使用替身。用户暂缓的重复击败低级NPC晋升继续保留。

## 问题清单

### R5-01 · P1 · 定期库存损耗仍修改归降冻结城市

**证据：隔离运行及源码。** 库存巡检只跳过尚未建好的分城，没有跳过归降冻结城市。冻结城市的粮食、钢铁、石油、稀矿和黄金仍可能被扣减，并生成损耗战报，违反接受归降后城内资产冻结的约定。

隔离合成城市已冻结，粮食从1,000,000,100变为1,000,000,050，生成1份损耗报告。此处库存超过10亿符合巡检的正常业务范围，不是整数字段溢出实验。

建议：巡检在事务内按城市冻结状态筛选；冻结期间不扣库存、不推进该城资源的损耗阶段。冻结结束后的阶段规则需要保持清晰，避免移交后立即补扣冻结期间的损耗。

位置：[ResourceOverflowService.java:64](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/ResourceOverflowService.java:64)、[PlayerCity.java:112](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/model/entity/PlayerCity.java:112)。

### R5-02 · P1 · 即时资源入账没有划清旧经济区间

**证据：隔离运行及源码。** 资源箱在结清旧经济区间前增加当前库存，随后经济结算会把刚收到的资源当成此前一直存在的余额。工资扣除、欠薪起点和忠诚度处罚会随服务调用顺序改变。签到、市场收支等入口也没有统一的经济变更边界，应一起核对，而不是只补资源箱。

两份相同合成账号在同一游戏时刻收到同样黄金奖励：旧区间先结算的结果为黄金50,000、忠诚度0；尚未结清旧区间的结果为黄金49,900、忠诚度80。该差异来自历史收支被当前余额重算。本例使用一小时未结算区间放大误差，日常短间隔调度下影响较小。

建议：库存收支前按旧余额结清相应城市至事件时点，再提交本次收支；对军官工资、兵粮和自然产出统一执行。自动奖励发生在经济事件内部时，应沿用事件时间并避免递归结算。

位置：[DepotService.java:200](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/DepotService.java:200)、[TickService.java:284](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/TickService.java:284)、[QuestService.java:675](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/quest/QuestService.java:675)、[MarketService.java:151](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/MarketService.java:151)。

### R5-03 · P2 · 城市名额预留没有贯穿所有扩城入口

**证据：源码确认，未运行超额接收实验。** 归降接受阶段计算“已有城市＋已接受待移交城市”，建城和战斗接管却只计算实存城市；到期和平移交又没有统一复核名额。这些入口对同一军衔上限使用不同口径，已有预留可能失去约束。

建议：统一城市名额服务，所有新增城市入口都考虑已接受的预留，并在账号锁内使用相同口径；移交前复核并定义历史异常记录的处理方式，不让城市永久冻结或无说明地丢失接收资格。

位置：[CitySurrenderService.java:205](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/CitySurrenderService.java:205)、[CityService.java:162](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/CityService.java:162)、[CityConquestService.java:46](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/CityConquestService.java:46)、[CitySurrenderService.java:189](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/CitySurrenderService.java:189)。

### R5-04 · P2 · 时间轴完成了建筑和科研，但完工结果没有传入推送

**证据：隔离运行及前后端源码。** Tick开头调用时间轴时丢弃其返回的完工集合；后面再次查询时队列已经删除，最终推送缺少完成通知及更新后的科技表。

隔离场景中建筑、科技均实际完成至1级，但唯一Tick推送不含`completedBuilds`、`completedResearch`或`tech`。前端建筑页依赖完成标记重绘卡片，科研页也依赖这些字段提示和同步；在后续完整状态刷新前，显示可能停留在旧状态。

建议：按城市保留时间轴返回的完工集合，随对应Tick一起发布；同时覆盖某城市完成共享科技时，其他城市客户端的科技状态同步。

位置：[TickService.java:106](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/TickService.java:106)、[TickService.java:335](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/TickService.java:335)、[GarrisonFoodTimeline.java:31](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/GarrisonFoodTimeline.java:31)、[build.js:1985](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/build.js:1985)、[ws-handlers.js:137](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/ws-handlers.js:137)。

### R5-05 · P2 · 人口与税收仍依赖结算频率

**证据：隔离运行及源码。** 民心已改为连续漂移，但人口使用区间末民心计算整段增长，税收又使用区间末人口计算整段收入。人口增长、逃亡和税收没有同步积分。

相同5级民居、30%税率、70民心、初始0人口，经过同样一小时：一次结算和每分钟结算的最终人口都是180，黄金却分别为108和55。前者把区间结束才增长出来的人口用于整个小时的税收。

建议：将人口变化与税收按统一时间轴积分，正确处理人口达到容量、增长转逃亡和民心变化的边界；用等时长不同切分验证，而不是只比较民心。

位置：[TickService.java:257](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/TickService.java:257)、[TickService.java:284](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/TickService.java:284)。

### R5-06 · P2 · 普通粮账和已启用驻军粮账使用不同的补结时长

**证据：隔离运行及源码。** 经济窗口只裁剪城市`lastTick`，驻军粮账仍从原`garrisonFoodAt`结算全部时长。即使外来驻军已经离开，只要城市保留了粮账起点，两个城市就可能得到不同结果。

相同100步兵、初始10,000粮食、无农场，24小时游戏时间未结算，配置窗口8小时：普通城市剩9,200粮食，已启用驻军粮账城市剩7,600。这是长时间未结算的兼容场景，不表示日常离线24小时必然触发；游戏时钟在停服期间暂停。

建议：先统一产品规则——收入与耗粮各自是否受补结窗口限制，再让两套粮账使用相同边界，并保持驻军实际抵达、遣返及战损的时间准确。

位置：[TickService.java:127](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/TickService.java:127)、[GarrisonFoodService.java:132](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/GarrisonFoodService.java:132)。

### R5-07 · P2 · 解散部队或取消征兵可能截掉已有的超容人口

**证据：解散隔离运行；取消入口源码同样成立。** 人口返还把“已有平民＋返还人口”整体限制到当前民居容量。民居降级后尚未自然逃亡的超容人口，会被一次返还操作立即删掉。

隔离城市原有1,500平民、容量1,200，解散1名步兵后人口变成1,200，已有300人直接消失。取消生产的返还代码也采用相同处理。

建议：只限制新增返还部分，保留操作前已有平民；超容人口仍由既定自然逃亡规则处理。主城、分城和取消生产应共用同一返还方法。

位置：[ArmyService.java:296](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/ArmyService.java:296)、[ArmyService.java:353](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/ArmyService.java:353)。

### R5-08 · P2 · 建筑完工声望没有使用开工成本快照

**证据：隔离运行及源码。** 取消施工已使用成本快照，完工声望却仍按当前建筑降费科技重算。相同已付款订单，施工中研究降费科技后，最终建筑声望反而减少。

两个正常提交的民居升级订单均实扣钢铁911、粮食455；不发生研发变化时获得13声望，施工中建筑加速科技完成后获得12声望。初始声望均为100，最终分别113、112；本例研发科技不额外增加声望。

建议：建筑完工声望按实扣成本或开工奖励快照兑现。历史队列使用现有成本快照兼容，避免未来调价继续改变旧订单奖励。

位置：[BuildService.java:285](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/BuildService.java:285)、[BuildService.java:780](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/BuildService.java:780)。

### R5-09 · P2 · 升星在结清经济后仍保存旧军官实体

**证据：源码确认，未新增运行实验。** 升星先读取军官，再扣道具；扣道具查询会清空JPA持久化上下文。随后经济服务可能保存更新后的军官忠诚度，但升星最后合并的是先前游离的旧实体，存在覆盖刚结算字段的路径。

建议：在读取军官和扣道具前先完成旧经济结算；持久化上下文被清空后重新读取军官，只更新本次升星字段。检查其他道具流程是否保留同类旧实体。

位置：[DepotService.java:506](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/DepotService.java:506)、[PlayerItemRepository.java:27](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/repository/PlayerItemRepository.java:27)。

### R5-10 · P2 · 商城仍绕过统一道具入库容量校验

**证据：源码确认，未执行越界购买。** 第四轮新增了统一入库服务，但商城购买仍自行累加库存，没有复用容量检查。购买次数上限限制的是单次购买，不能代替累计库存范围检查。

建议：商城通过统一入库服务完成商品入仓，并让扣钻与入库失败整体回滚；保留新手礼包和晋升凭证已有的独立资格校验。本项是旧修复的遗漏，不作为全新类别重复评价此前所有奖励入口。

位置：[ShopController.java:151](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/controller/ShopController.java:151)、[InventoryService.java:21](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/InventoryService.java:21)。

### R5-11 · P2 · 资源净变化显示与实际结算不一致

**证据：真实前端函数的隔离计算、后端结算函数及源码。** 黄金在自然产出上限时，前端显示暂停全部税收并扣工资；后端已经按连续净收支处理，可以用同期税收支付工资。新手保护或来袭状态的生产倍率也由前端自行推断，和后端读取的城市状态不同。

合成状态为黄金20,000,000、每小时税收600、工资100：前端显示每小时净变化-100，后端一小时后的余额仍为20,000,000，实际净变化0。以基础钢铁产能1,000计算，前端在新手保护时显示1,100、来袭时显示700；后端状态读取没有把这两种状态直接映射为同样倍率。倍率差异为源码对照，未另外启动完整来袭战斗。

建议：服务端返回本城实际生产率、耗粮及工资净收支，前端共用这些字段展示；先明确新手保护和来袭状态是否应改变产量，不在客户端单独决定经济规则。

位置：[core.js:169](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/core.js:169)、[core.js:393](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/core.js:393)、[TickService.java:505](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/TickService.java:505)、[TickService.java:673](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/backend/src/main/java/com/wargame/service/TickService.java:673)。

## 验证结果与范围

| 检查 | 本轮结果 |
| --- | --- |
| 后端源码及测试编译 | 通过 |
| 8个后端定向测试类 | 113项通过，0失败、0错误、0跳过 |
| 前端既有整套回归 | 1,289项通过，0失败、0跳过 |
| 独立H2正常玩法观察 | 8项全部执行成功，记录上述实际返回值；不是缺陷修复验收 |
| 前端资源展示函数观察 | 成功执行，结果保存在resource-view.json |

后端定向回归覆盖TickServiceTest、EconomyAccrualTest、GameplayAuditRound4FixesTest、OfficerSalaryArrearsTest、BuildServiceTest、ProductionAccelerationTest、GuildRelationServiceTest、TechServiceTest。第四轮正常流程中的双方同意建立友好、任一方解除及独立多方战争检查继续通过。

执行命令：

```sh
LC_ALL=en_US.UTF-8 ./mvnw -q -f backend/pom.xml -Dmaven.compiler.useIncrementalCompilation=false -DskipTests test-compile
LC_ALL=en_US.UTF-8 ./mvnw -q -f backend/pom.xml -Dmaven.compiler.useIncrementalCompilation=false -Dtest=TickServiceTest,EconomyAccrualTest,GameplayAuditRound4FixesTest,OfficerSalaryArrearsTest,BuildServiceTest,ProductionAccelerationTest,GuildRelationServiceTest,TechServiceTest test
node --test frontend/tests/*.test.cjs
python3 output/gameplay-audit-round5-20261031/run-audit.py
node output/gameplay-audit-round5-20261031/resource-view.cjs
```

检查从第四轮验收记录接续，重点核对了经济时间轴、建设及生产、军官培养、资源与奖励入库、多城与归降、外交与个人战争、迁城、任务、伤兵战俘和前端状态同步。没有逐条穷举全部战斗组合，没有运行本轮后端全量、真实MySQL迁移或线上部署验收。现有回归通过仍不足以证明整个项目没有问题，本报告保留明确未修复项。

记录目录：[第五轮检查记录](/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/output/gameplay-audit-round5-20261031)。其中observations.json是隔离观察结果，validation.json是既有测试汇总；源码摘要仅用于结束阶段的一致性复核，不代表整轮检查固定在同一个提交上。
