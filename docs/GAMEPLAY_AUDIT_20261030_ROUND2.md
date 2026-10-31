# 游戏合理性排查 · 第二轮（2026-10-30）

## 修复状态（2026-10-30）

用户确认修复后，本报告列出的 7 项问题均已完成代码修复。下方原始复现结果保留作修复前证据，原审查程序是缺陷复现工具，不是修复后的验收入口。

| 问题 | 当前处理 |
| --- | --- |
| 归降到期不移交 | 定时入口对每份邀请使用独立新事务；失败记录邀请 ID 和异常，单份失败回滚不影响其他邀请；过期、移交和重复执行均验证 |
| 主线建筑目标不可达 | 第六章市政厅目标改为主城 8 级，第七章市政厅与城墙满级改为 10 级；升级与任务共用建筑等级上限；主城核心满级仅查主城，内陆主城不要求不可新建的军港 |
| 锁章提前领奖 | 服务端单任务、章节礼包均要求全部前序章节礼包已领取；单任务要求直接前置任务已完成或已领取；相关入口共用账号锁 |
| 建筑等级按次数累计 | 建筑、科技等级、兵力等状态事件读取真实快照；侦察和战斗等次数事件继续累计；未领取的错误建筑进度重算，旧目标值自动同步 |
| 跨城挂单超额 | 上架先持有卖方账号写锁，再锁定当前读活动挂单；槽位继续按账号共用、由发起城市交易所等级决定；单笔数量与单价上限保留 |
| 治疗后追扣旧粮耗 | 账号锁内先结清旧兵力粮账，未启用账本的城市从旧经济时刻接管，再扣治疗费并归队；覆盖主/分城、黄金/钻石、同一时刻多次治疗 |
| 黄金封顶结算频率差异 | 税收与工资按持续净收支结算；超额库存先停税扣薪，降至自然产出上限后恢复税收；保留小数累计和游戏时间欠薪处罚 |

### 存量兼容与验证边界

- 任务 ID、奖励数额、已领取记录不变，不追回或重发奖励。兵力、战斗等已经达成的历史目标保留；原有章节迁入新任务的完成资格也保留，但不能绕过前序章节解锁或错误建筑进度校验。
- 旧的 15/20 级未领取目标在任务查询、事件更新或领奖时自动同步，不需要人工清空任务表。
- 按用户此前决定，反复击败低级 NPC 晋升仍暂不处理。没有运行自动格式化或修改界面样式。
- 后端 16 个相关测试类共 185 项通过，0 失败、0 错误，其中新增第二轮回归 17 项。前端相关 39 项通过。修改文件的 git diff --check 通过。
- 测试使用隔离 H2 内存库及测试配置，未连接本地游戏库或线上数据库；尚未做 MySQL 专项压力测试或运行中游戏服部署验收。需要后端重新构建并重启后加载修复；存量到期邀请将由恢复后的定时任务继续处理。
- 验证结果位于 output/gameplay-audit-round2-20261030/fix-validation-results.json；后端与前端日志分别为同目录的 fix-backend-validation.log、fix-frontend-validation.log。
- 后端验收入口为 GameplayAuditFixesTest、QuestStageSystemTest、QuestDailyCheckinTest、MarketServiceTest、WoundedServiceTest、MultiCityTest、TickServiceTest、OfficerSalaryArrearsTest、GameRuleAdjustmentsTest、LandBanditQuestTest、TechnologyReworkIntegrationTest、UnitUnlockIntegrationTest、BuildServiceTest、ArmyRecruitmentFoodTest、JointDefenseConcurrencyTest、JointDefenseBattleTest。使用既有 Maven wrapper，并设置 LC_ALL=en_US.UTF-8 与 -Dmaven.compiler.useIncrementalCompilation=false。

## 原始排查记录（修复前）

本轮按当前工作区源码继续排查，聚焦任务、归降、交易所与经济结算。已在独立 H2 内存库调用真实 Spring 服务验证 7 项问题；未连接本地或线上游戏数据库，未修改游戏业务代码、样式或数值。沿用用户此前决定，暂不处理反复击败低级 NPC 晋升的问题。

## 结果与优先级

### 1. P1：归降到期无法自动完成，城市可能持续冻结

- 位置：`backend/src/main/java/com/wargame/service/CitySurrenderService.java:144-163`；`backend/src/main/java/com/wargame/repository/CitySurrenderOfferRepository.java:15-17`。
- 原因：定时入口 `settleDue()` 没有事务，直接调用同一实例的 `expire()` / `complete()`，绕过 Spring 事务代理；随后执行悲观锁查询，抛出“Query requires transaction be in progress”。异常被空 catch 吞掉。
- 实测：已过期邀请经 `settleDue()` 后仍为 `PENDING`；经外部事务代理调用 `expire()` 后正确变为 `EXPIRED`。已接受且移交期限已过的邀请经 `settleDue()` 后仍为 `ACCEPTED`，城市仍为冻结状态。
- 影响：玩家接受归降后，期限到了仍不能经营城市；过期邀请也可能占着流程，错误没有日志。
- 建议：每份邀请通过独立事务执行过期/移交；记录带邀请 ID 的失败日志，单份异常不阻塞其他邀请。补充由无外层事务调用定时入口的测试。

### 2. P1：主线仍要求 15/20 级建筑，超过当前 10 级上限

- 位置：`backend/src/main/java/com/wargame/service/BuildService.java:196-205`；`backend/src/main/java/com/wargame/service/quest/QuestCatalog.java:107,119-120`；`backend/src/main/java/com/wargame/service/quest/QuestService.java:144-156`。
- 实测：市政厅 10 级后升级返回“已达当前市政厅上限”。任务 `q6_5` 却要求市政厅 15 级，`q7_4` 要求市政厅 20 级，`q7_5` 要求城墙 20 级。
- 源码补充：`q8_1` 的“主城核心满级”判定仍硬编码全部核心建筑达到 20 级，且查询未限定主城。
- 影响：正常升级无法完成这些任务，后续章节和阶段奖励受阻。下述“累计建造次数”漏洞可能错误绕过部分任务，但不能使任务定义合理。
- 建议：根据现有 10 级上限重新设计后期目标；满级判定复用当前建筑规则，并按文案限定主城。保持任务 ID 和已领取记录，单独处理存量目标值。

### 3. P1：锁定章节可直接领取单项任务奖励

- 位置：`backend/src/main/java/com/wargame/service/quest/QuestService.java:81-90,231-244,313-339`。
- 原因：事件会提前给所有章节写进度；`claim()` 只判断任务自身是否 `completed`，不核验所属章节是否开放，也未核验任务前置条件。
- 实测：第一章尚未通关，第二章列表显示 `locked`；通过正常建筑事件钩子累计第二章 `q2_1` 的完成状态后，直接调用领奖服务返回 `success=true`，实际发放钢铁 3000、石油 3000、黄金 400。
- 影响：界面锁定只限制展示，玩家可以直接请求领奖接口提前获取后期资源。
- 建议：允许保留提前达成的进度，但在服务端领奖时校验章节开放和选定的任务前置规则；阶段领奖也应统一复核章节资格。

### 4. P2：建筑等级任务实际按建造次数完成

- 位置：`backend/src/main/java/com/wargame/service/BuildService.java:760-772`；`backend/src/main/java/com/wargame/service/quest/QuestService.java:231-244`。
- 原因：每次建筑竣工向 `BUILD_UPGRADE_DONE` 增加 1，任务却把目标值解释为建筑等级。查询界面时又只向上补进度，不能纠正已错误完成的状态。
- 实测：通过真实施工完成服务建造两座一级民居，库存等级为 `[1,1]`，“民居升至 2 级”任务进度变成 2，状态为 `completed`。
- 影响：玩家可以靠低级建筑、反复拆建提前完成高等级任务；升级一座建筑和分散建造多座建筑不再等价于文案承诺。
- 建议：等级、总兵力、科技等级等状态目标走快照判定；只有明确的次数目标累计事件。修复时保留已发奖励，避免重发。

### 5. P2：交易所槽位有限制，但跨城并发可超过限制

- 位置：`backend/src/main/java/com/wargame/service/MarketService.java:113-120`。
- 原因：活动挂单数按账号统计，但检查发生在城市资源锁之前；不同城市各锁各的资源行，缺少账号级的串行校验。
- 实测：两个一级交易所，槽位上限均为 3；账号已有 2 单，从两个城市同时上架，两次请求都成功，活动单数变成 4。测试使用两个真实事务及不同城市资源行，不模拟交易所服务。
- 影响：多城玩家可以超过既定挂单数量规则。
- 建议：在统计和创建之前持有账号级锁，以当前读复核数量；明确产品口径是全账号槽位还是每城槽位，再统一列表与提示。
- 保持此前结论：单笔数量上限 100,000,000、单价上限 1,000,000 已存在。本项是并发绕过，不是缺少上限。

### 6. P2：伤兵刚治疗归队，就被追扣此前的养兵粮耗

- 位置：`backend/src/main/java/com/wargame/service/WoundedService.java:196-218`；`backend/src/main/java/com/wargame/service/GarrisonFoodService.java:125-158`。
- 原因：治疗先增加现役兵力，没有先把本城旧兵力的粮账结算到治疗时刻。下次粮账用新增兵力结算整个未结算区间。
- 实测：已启用驻军粮账的城市，此前一小时没有现役部队，粮食 10,000；当前治疗 100 名步兵，再结算上一小时，粮食变成 9,900。100 名步兵被计入了尚未归队时的耗粮。
- 影响：治疗归队立即多扣粮；延迟结算、调度积压时误差更大，接近断粮的城市会受到额外影响。
- 建议：按统一玩家锁顺序，在兵力变化前结清旧粮账，再扣治疗费和归队；补充同一时刻重复调用与分城治疗验证。

### 7. P2：黄金封顶附近，最终余额取决于结算频率

- 位置：`backend/src/main/java/com/wargame/service/TickService.java:267-285`。
- 原因：先把整段税收截到黄金产出上限，再扣整段工资。即使持续税收足以支付工资，结算区间越长，丢弃的税收越多。
- 实测：初始黄金 20,000,000，民居 5 级、平民 6,000、税率 50%、军官工资 100/小时，总游戏时间相同。一小时结算一次余额为 19,999,900；半小时结算两次余额为 19,999,950。
- 影响：同样的城市和经过时间，因为调度频率、请求时机不同而损失不同黄金；频繁刷新可能改变经营结果。
- 建议：按持续净收支及触及上限的事件时刻处理税收/工资，确保切分同一时间区间不改变结果，兼顾库存高于自然产出上限的情况。

## 复现与验证

- 使用当前工作区编译的 Java 17 类、现有项目依赖，以及 Spring TestContext。
- 使用独立 `jdbc:h2:mem:gameplay_audit_20261030`，Flyway 关闭，生产数据库未使用。游戏时钟通过已有 `GameClock.use` 固定。
- 项目中文路径下 Maven 默认增量编译出现状态文件编码错误；使用以下命令成功编译，未改构建配置：

```sh
LC_ALL=en_US.UTF-8 ./mvnw -q -f backend/pom.xml -Dmaven.compiler.useIncrementalCompilation=false -DskipTests test-compile
```

- 复现程序：`output/gameplay-audit-round2-20261030/GameplayAudit20261030.java`。
- 执行入口：`output/gameplay-audit-round2-20261030/run-audit.py`。需要已编译项目及现有 Surefire 报告提供依赖路径；程序重新创建内存库，不启动 HTTP 服务。
- 原始输出：`output/gameplay-audit-round2-20261030/reproduction.log`。结构化结果：`output/gameplay-audit-round2-20261030/results.json`。
- 7 个检查均得到预期的缺陷现象，程序退出码 0；不代表修复后测试已通过。H2 复现确认当前服务逻辑，尚未对 MySQL 隔离级别进行专项压力测试。
- 本轮仅定位问题并记录建议；没有调整游戏玩法、奖励、等级上限或生产数据。建议修复顺序：归降卡城 → 任务可达性与领奖校验 → 交易所并发 → 粮食和黄金时间一致性。
