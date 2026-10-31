# 第四轮玩法修复与验收（2026-10-31）

对应 [第四轮排查报告](GAMEPLAY_AUDIT_20261031_ROUND4.md) 的23类问题及D1外交规则。低级NPC重复晋升仍按用户要求暂缓。本次在现有工作区继续修复，没有连接实际游戏数据库或发布服务。

## 逐项修复

| 编号 | 修复后的行为 | 主要实现 |
| --- | --- | --- |
| R4-01 | 阶段礼包的城市物资留在受领城，钻石进入账号主城钱包 | DepotService、V150 |
| R4-02 | 钻石入账校验余额范围，整包发奖在同一事务中提交 | DepotService |
| R4-03 | 礼包、装备返还、VIP、任务及签到道具共用容量校验；入库失败回滚领取状态和奖励 | InventoryService、EquipmentService、VipService、QuestService |
| R4-04 | 仓库和军官页使用同一经验书服务；定额书累积经验、手动升级，满级书直接升满 | DepotService、OfficerService |
| R4-05 | 记录真实属性点分配，洗点只退已记录的加点，保留军官资质 | Officer、OfficerService、V150、军官详情提示 |
| R4-06 | 任职、加点、技能和装备变化前按旧配置结清本城经济 | OfficerService、EquipmentService、TickService |
| R4-07 | 民心、民怨按连续时长变化并保存小数；安抚、税率变化和城市移交同步处理小数 | TickService、CityEconomy、CityConquestService、V150 |
| R4-08 | 普通粮账与驻军粮账共用连续净收支及仓库容量算法 | TickService、GarrisonFoodService |
| R4-09 | 共享科技在实际完工时刻先结清各受影响城市的旧产出 | GarrisonFoodTimeline、TechService |
| R4-10 | 资源建筑在实际完工时刻切换产能，结算离线完工后的收益 | GarrisonFoodTimeline、BuildService |
| R4-11 | 护盾到期先结清有效保护区间，再清理状态 | GarrisonFoodTimeline、TickService |
| R4-12 | VIP全域政务跳过归降冻结及建设中的城市，返回跳过原因 | VipService |
| R4-13 | 主城按槽位去重，统计实际完成政务的城市数 | VipService |
| R4-14 | 解散军团清理申请和外交关系，迁移清理历史孤立记录 | GuildService、V150 |
| R4-15 | 论坛累计打赏改为宽整数，历史总额从打赏明细重建 | ForumTopic、ForumDtos、ForumService、V150 |
| R4-16 | 新战报保存玩家ID，分享及论坛按稳定身份脱敏；旧名称只在无歧义时兼容 | MarchService、ReportSummary、ReportPrivacyService |
| R4-17 | 注销清理原子关闭活动挂单；交易先确认卖方及结算账本有效，再整体结算 | AccountService、MarketService、V150 |
| R4-18 | 使用当前战斗引擎重新生成166条校准数据，覆盖两类战场、全部战斗技能等级及组合 | PvpPowerModel、PvpCalibrationTool、校准JSON |
| R4-19 | 每对账号独立保存战争及加速状态，地图、详情、出征和计谋统一按双方关系读取 | PersonalWar、PersonalWarService、WorldService、MarchService、V151 |
| R4-20 | 军团声望和榜单有效人数共用资格规则；详情另外保留完整编制名册 | LeaderboardEligibility、GuildService、guild.js |
| R4-21 | 普通部队每小时粮耗使用宽整数汇总，调用方同步调整 | TickService、GameStateService、BotPolicy及相应测试 |
| R4-22 | 普通安抚及主动撤盾在服务事务内检查城市可写状态 | GameStateService、CityService、CityContextConfig |
| R4-23 | 军团支援的注销期限使用真实时间；行军和建设继续使用游戏时间 | GuildSupportService |
| D1 | 建立友好需邀请并由对方现任管理者接受，任一方管理者可解除 | GuildRelationService、GuildController、api.js、guild.js、V150 |

外交邀请可接受、拒绝或撤回。未接受时保留原关系，拒绝或撤回也不改变原关系；接受后才发布结盟公告。敌对声明沿用原规则。两军团按固定顺序加锁，外交记录仍按唯一军团对保存。

经济时间轴继续兼容尚未生成主城实体记录的旧账号，仍结算其经济、施工和研究，不查询不存在的城市驻军及城市战斗。

## 数据兼容与上线影响

- **V150**：汇总分城遗留钻石到主城钱包；新增属性分配和民心、民怨小数字段；扩容论坛统计；清理孤立军团记录及已注销卖方挂单。迁移先检查历史钱包是否可以归并，异常数据须核账。
- **历史友好关系**没有双方同意记录，V150将其恢复中立，双方须重新邀请确认；不会根据当前入团信息猜测历史邀请方。敌对关系保留。
- **历史军官**无法可靠逆推出旧加点方向，保留现有属性作为基准并在详情明确提示；升级后记录的新加点可以退还，没有可退点时不扣洗点费用。
- **V151**：独立保存仍有完整身份的历史战争，合并同一双方的镜像快照；已经被旧单份字段覆盖且没有可靠身份的历史战争不凭名字重建。
- 城市接管和主城升迁保留已结算经济及小数，不回退到开战时的库存；军事资产清理及账号共享钱包仍按现有接管规则处理。

## 验证

本轮使用隔离H2测试配置和邮件替身。回归覆盖正常道具使用、属性分配与洗点、分城奖励、离线建筑和科技事件、护盾收益、全域特赦、外交邀请、独立战争、历史数据迁移及既有玩法流程。没有修改测试断言来恢复已取消的技能升级规则。

PvP校准共166条，每条至少6400场真实引擎对照战斗，采用五个固定种子和攻守互换；最大绝对残差约0.012612，满足原有小于0.03的验收条件。实际测得的条件技能零增益可以保留系数1。

| 最终检查 | 结果 |
| --- | --- |
| 后端业务回归 | 270个测试类、2192项：2189通过、0失败、0错误、3按既有条件跳过 |
| 前端整套回归 | 1289项全部通过，0失败、0跳过 |
| 新增本轮正常业务流程 | GameplayAuditRound4FixesTest的12项全部通过 |
| 新增历史迁移验证 | GameplayConsistencyMigrationTest的2项全部通过 |
| 校准配置验收 | PvpCalibrationTest通过，全部166条满足原有覆盖率、样本量和残差要求 |
| 源码与测试摘要复核 | 记录的1084个文件在结束核对时均保持一致 |

后端执行命令为：

```sh
LC_ALL=en_US.UTF-8 ./mvnw -q -f backend/pom.xml -Dmaven.compiler.useIncrementalCompilation=false '-Dtest=*Test,!**/security/**,!**/config/**,!AdminSecurityIntegrationTest,!AccountEmailIntegrationTest' test
```

此命令覆盖业务回归，未运行独立安全目录、配置专项及上述两个账号安全/邮件集成类，不能将本次结果称为所有后端测试均已运行。前端命令为 `node --test frontend/tests/*.test.cjs`。

3项跳过分别是：2项MySqlMigrationTest因未提供隔离MySQL测试地址而跳过，1项AttackDesignTest为已被新版属性规则替代的历史设计快照。新增V150/V151已通过隔离H2迁移检查，尚未验证实际MySQL迁移执行。

验收日志、校准原始记录、结构化结果和源码摘要存于 `output/gameplay-audit-round4-fixes-20261031/`。通过回归表示本轮检查范围内未留下失败项，不构成对所有状态组合没有缺陷的保证。
