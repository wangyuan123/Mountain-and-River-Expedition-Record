# 账号限时动员验收（2026-10-28）

规则与数据设计见 [战争动员令设计](WAR_MOBILIZATION_ORDERS_DESIGN_20261027.md)。本次交付为代码及自动化验收；运行中的服务需加载新版本后才生效。

## 最终行为

- 三档带兵上限增幅为 10%、25%、50%，每张持续 24 小时；同档使用多张累加时间，异档分别计时、只取最高增幅。
- 仓库点击道具先显示数量、加成、持续时间、结束时间、当前城市容量变化及生效范围，确认后扣库存并立即启用。出征不再选择或消耗动员令。
- 覆盖账号所有城市的新行军和本城防守，按各城市自己的统帅技能计算；迎战不再额外增加半份军衔基础兵力。
- 默认防守数量与本轮动员防守数量分别存储，全部动员到期后直接恢复默认配置；自动选兵与零兵力配置可准确还原。
- 已出征队伍和已开始的战斗沿用既有兵力快照；后续出征和开战按当前有效状态选兵。
- 指挥官档案、战术配置、仓库展示分钟级倒计时、当前最高加成及北京时间结束时间。多档并存可查看被覆盖档位。
- 账号锁、原子库存扣减、使用回执防止并发和重试重复扣卡。过期或跨周期防守草稿拒绝保存；跨设备状态不同步时校准账号后重新加载战术。

## 自动化验证

| 范围 | 结果 |
| --- | --- |
| 前端完整测试 | 1227 通过，0 失败 |
| 后端本次相关回归 | 65 通过，0 失败 |
| 后端完整测试首轮 | 1858 项，2 失败，3 跳过；两项失败均与 C 编码环境有关 |
| 上述两项的测试类在 UTF-8 环境复测 | 14 通过，0 失败 |
| 差异空白检查 | `git diff --check` 通过 |

后端相关回归包含动员持续与续期、独立档位降档、全部城市容量、库存不足与非法数量、幂等重试、并发争抢最后一张、事务失败回滚、默认防守还原、旧草稿拒绝、已出征兵力保留、出征旧参数拒绝、战术锁定、迎战、战力估算和对战接口等已有用例。前端增加了跨设备启用后校准战术、校准过程中切换账号不接受旧响应的回归用例。

复现命令（不执行自动格式化）：

```sh
node --test frontend/tests/*.test.cjs

LC_ALL=en_US.UTF-8 MAVEN_OPTS='-Dfile.encoding=UTF-8' mvn -f backend/pom.xml \
  '-Dtest=MobilizationOrderTest,MobilizationOrderConcurrencyTest,MobilizationMigrationTest,BattleActionPreferencesTest,BattleDefaultsControllerTest,BattleDefaultsLockIntegrationTest,SortieBattleTest,LeaderboardServiceTest,PlayerPowerServiceTest,PvpSnapshotTest,PvpControllerTest,MayorSkillServiceTest' \
  '-DargLine=-Dfile.encoding=UTF-8' test

LC_ALL=en_US.UTF-8 MAVEN_OPTS='-Dfile.encoding=UTF-8' mvn -f backend/pom.xml \
  '-Dtest=NewPlayerBriefingMigrationTest,JointDefenseBattleTest' \
  '-DargLine=-Dfile.encoding=UTF-8' test
```

## 版本加载

后端 Flyway 新增 `V138__account_mobilization.sql`，为账号增加动员到期时间、动员防守数量、动员周期，并新增幂等回执表。迁移保留原库存和默认防守配置，旧账号初始无动员效果。迁移脚本已通过 H2 MySQL 兼容模式测试，尚未在本次验收中连接实际 MySQL 执行。

发布时先启动包含 V138 的后端，再加载新版前端。涉及动员的静态脚本已更新版本参数，旧页面提交按次出征动员参数会收到刷新提示。
