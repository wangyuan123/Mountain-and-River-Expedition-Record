# AI 玩家

AI 玩家使用后端规则决策，不需要外部模型、API Key 或浏览器在线。只有同时标记为 Bot、显式配置且启用的账号才会执行。已有模拟账号不会自动参与。

## 当前行为

- 按市政厅等级和正式前置条件建设资源建筑、军工厂、仓库、实验室、雷达及后勤设施；资源紧缺时等待正常产出。
- 轮换研发生产、侦察、军事与后勤科技，生产步兵、侦查车、卡车与装甲车；早期优先补齐侦查条件，保留粮食和平民。
- 仅探索当前城市雷达范围；未知守军先派侦查部队，仅依据本人的有效报告决策，不使用地图中的敌军实时兵力。
- 兵力估算至少三倍优势才攻击；保留驻城比例，并把在途和驻野部队计入已经投入的兵力。
- 占领资源野地后依次采集、收获、返城，物资抵达城市后入库。耗尽并撤军后释放领地名额。
- 可选 PvP：只向其他 Bot 城市侦查、宣战、等待备战期、掠夺。默认关闭，执行时再次检查目标所有者。真人城市不在当前攻击范围。

所有动作调用玩家共用的业务服务，遵循费用、工期、补给、路线、行军上限、野地名额、护盾和战争规则，不额外赠送资源、兵力或加速。

## 配置接口

以下接口均需管理员 JWT，沿用 `/api/admin/**` 权限。请求分服头为 `X-Game-Server`。

`GET /api/admin/bots` 查看配置及最近动作。

`PUT /api/admin/bots/{playerId}` 配置账号：

```json
{
  "convertToBot": true,
  "settings": {
    "enabled": true,
    "pvpEnabled": false,
    "citySlot": 0,
    "intervalSeconds": 45,
    "reservePercent": 35,
    "maxMarches": 2,
    "armyTarget": 500
  }
}
```

首次转换必须显式提交 `convertToBot: true`；尚未初始化的账号使用正式新手初始化生成城市，已有游戏资产不重置。配置写入管理员审计。后续调整可使用 `convertToBot: false`。

暂停：提交同样配置，将 `settings.enabled` 设为 `false`。暂停停止新决策，已有建设、行军和采集遵循原游戏规则继续结算。

`POST /api/admin/bots/{playerId}/step` 立即执行一步；只跳过决策间隔，不能绕过暂停、封禁、全局开关或业务规则。

`GET /api/admin/bots/{playerId}/logs?limit=30` 查看决策、原因及业务结果，最多返回并保留最近 200 条。

## 调度与恢复

环境变量：`WARGAME_BOT_ENABLED` 默认 `true`，`WARGAME_BOT_POLL_INTERVAL` 默认 `5000` 毫秒，`WARGAME_BOT_BATCH_SIZE` 默认 `10`。调度还遵循 `game.scheduling.enabled`。决策间隔使用游戏时钟，停服期间不补跑。

每个账号每次只提交一个动作；账号锁和配置锁防止重复执行，业务结果与下一次执行时间同事务持久化。异常先回滚，再单独记录失败与退避。目标失败后临时冷却，避免连续撞同一个目标。

## 当前限制与验证

当前为可解释的规则策略，兵力估算不是完整战损模拟；只管理配置指定的一座城市，不包含外交聊天、军官养成、海空军发展和复杂战术操控。PvP 使用现有战斗超时推进机制，宣战仍需等待游戏原定备战期。后台已有启停和日志 API，尚未增加管理页面。

定向验证命令（在包含中文的 macOS 路径下显式使用 UTF-8 locale）：

```sh
LC_ALL=en_US.UTF-8 ./mvnw -q -f backend/pom.xml -Dmaven.compiler.useIncrementalCompilation=false -Dtest=BotPolicyTest,BotStoreTest,BotPlannerTest,BotIntegrationTest test
```

测试覆盖留守与兵力上限、侦查证据、强弱判断、Bot 互战及备战、采集与返城入库、真实建造扣费、暂停、防重复执行、配置迁移、失败退避、日志保留，以及从初始经济自主解锁侦查车。
