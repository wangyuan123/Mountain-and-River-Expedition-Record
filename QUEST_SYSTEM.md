# 主线任务 + 新手引导系统说明

## 系统目标

按"先理解后战斗"的二战题材节奏，把玩家从注册到中期发展拆成 2 套并行引导系统：

| 系统                        | 作用                                          | 触发                      | 持久化                 |
| --------------------------- | --------------------------------------------- | ------------------------- | ---------------------- |
| **新手引导** (Newbie Guide) | 浮窗式分步提示，告诉玩家"现在该去哪儿"        | 玩家主动点"下一步/已了解" | 服务端 `player_guide`  |
| **主线任务** (Main Quest)   | 章节化任务树，完成后给资源/道具奖励，助理前期 | 业务事件自动触发          | 服务端 `player_quests` |

---

## 一、新手引导步骤 (8 步)

| 顺序 | ID               | 标题               | 跳转目标          |
| ---- | ---------------- | ------------------ | ----------------- |
| 0    | `g_welcome`      | 欢迎来到山河远征录 | -                 |
| 1    | `g_open_res`     | 先看看资源区       | `buildRes` ([1])  |
| 2    | `g_upgrade_farm` | 把农场升到 2 级    | `buildRes`        |
| 3    | `g_open_army`    | 再去军事区看看     | `buildArmy` ([2]) |
| 4    | `g_recruit_army` | 训练 20 个步兵     | `buildArmy`       |
| 5    | `g_open_map`     | 打开世界地图       | `world` ([6])     |
| 6    | `g_academy`      | 去军官学院看看     | `home`            |
| 99   | `g_done`         | 新手训练营 · 毕业  | -                 |

引导浮窗常驻屏幕底部，进度可见。点"跳过"会一次性结束所有步骤（写库 `skipped`）。

---

## 二、主线任务（4 章 20 个任务）

### 第一章 · 开荒奠基

| ID     | 标题     | 触发事件                | 目标     | 奖励      |
| ------ | -------- | ----------------------- | -------- | --------- |
| `q1_1` | 建造民居 | BUILD_UPGRADE_DONE/民居 | 升到 Lv2 | 金 200    |
| `q1_2` | 扩建农场 | BUILD_COUNT/农场        | 数量 2   | 钢 5k     |
| `q1_3` | 炼油起步 | BUILD_UPGRADE_DONE/炼油 | Lv2      | 金 200    |
| `q1_4` | 招兵买马 | ARMY_RECRUIT/infantry   | 30 个    | 金 300    |
| `q1_5` | 统帅初现 | OFFICER_RECRUIT         | 1 人     | 技能书 ×1 |
| `q1_6` | 委以重任 | OFFICER_APPOINT         | 1 次     | 金 200    |

### 第二章 · 站稳脚跟

| ID     | 标题     | 触发                   | 目标 | 奖励               |
| ------ | -------- | ---------------------- | ---- | ------------------ |
| `q2_1` | 扩建民居 | BUILD_LEVEL_SUM/民居   | 5    | 金 500             |
| `q2_2` | 炮兵连   | ARMY_RECRUIT/artillery | 20   | 金 400             |
| `q2_3` | 首次侦察 | MAP_SCAN               | 1 次 | 金 300             |
| `q2_4` | 肃清流寇 | BANDIT_DEFEAT          | 1 个 | 金 500+技能书      |
| `q2_5` | 占领矿区 | WILD_CLAIM             | 1 块 | 粮 5k+钢 2k+金 500 |

### 第三章 · 开疆扩土

| ID     | 标题     | 触发                        | 目标  | 奖励          |
| ------ | -------- | --------------------------- | ----- | ------------- |
| `q3_1` | 稀矿起步 | BUILD_UPGRADE_DONE/raremine | Lv2   | 金 600        |
| `q3_2` | 油田上马 | BUILD_UPGRADE_DONE/oilfield | Lv2   | 稀 500+金 600 |
| `q3_3` | 兵强马壮 | ARMY_TOTAL                  | 100   | 金 800        |
| `q3_4` | 城防初具 | BUILD_UPGRADE_DONE/wall     | Lv2   | 金 500        |
| `q3_5` | 名将加盟 | OFFICER_RECRUIT_STAR        | ≥3 星 | 金 1500+书 ×2 |

### 第四章 · 阵营争锋

| ID     | 标题       | 触发                    | 目标 | 奖励                      |
| ------ | ---------- | ----------------------- | ---- | ------------------------- |
| `q4_1` | 高级兵工厂 | BUILD_LEVEL_SUM/factory | Lv4  | 金 1000                   |
| `q4_2` | 雄狮百万   | ARMY_TOTAL              | 300  | 金 1500+经验书            |
| `q4_3` | 正式宣战   | WAR_DECLARE             | 1 次 | 金 2000+技能书 ×2+护盾 ×1 |
| `q4_4` | 首战告捷   | PLAYER_WIN              | 1 次 | 全资源包+改名卡           |

---

## 三、事件源 (Event Sources)

后端各 Service 在完成业务后调用 `questService.onEvent(...)`：

| 事件 ID                | 来源                                                                                                                              | 触发时机                               |
| ---------------------- | --------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------- |
| `BUILD_UPGRADE_DONE`   | [BuildService.completeUpgrade](file:///Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/BuildService.java) | 建筑施工完成                           |
| `BUILD_LEVEL_SUM`      | 同上                                                                                                                              | 重算等级和（支持"民居总等级 5"类目标） |
| `BUILD_COUNT`          | 同上                                                                                                                              | 重算建筑数量（"再建 1 座农场"类目标）  |
| `ARMY_RECRUIT`         | [ArmyService.recruit](file:///Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/ArmyService.java)           | 招兵成功                               |
| `ARMY_TOTAL`           | 同上                                                                                                                              | 重算总兵力                             |
| `OFFICER_RECRUIT`      | [OfficerService.recruit](file:///Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/OfficerService.java)     | 招募军官                               |
| `OFFICER_RECRUIT_STAR` | 同上                                                                                                                              | 招募时上报星级（取玩家最高）           |
| `OFFICER_APPOINT`      | [OfficerService.appoint](file:///Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/OfficerService.java)     | 任命为市长/指挥官                      |
| `MAP_SCAN`             | [WorldService.scan](file:///Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/WorldService.java)            | 雷达扫描完成                           |
| `WAR_DECLARE`          | [WorldService.declareWar](file:///Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/WorldService.java)      | 对玩家主城宣战                         |
| `BANDIT_DEFEAT`        | [MarchService.processMarches](file:///Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/MarchService.java)  | 击败流寇                               |
| `WILD_CLAIM`           | 同上                                                                                                                              | 占领野地                               |
| `PLAYER_WIN`           | 同上                                                                                                                              | 攻入玩家主城（攻方参战即触发，含胜负） |

事件钩子都包在 `try { ... } catch (Exception ignored) {}` 里，**任务系统任何故障都不能阻塞主线玩法**。

---

## 四、数据库

迁移 [V6\_\_quest_system.sql](file:///Users/chenjuan/Documents/游戏/backend/src/main/resources/db/migration/V6__quest_system.sql) 新增 2 张表：

```sql
player_quests  (id, player_id, quest_id, status, progress, target_value, completed_at, claimed_at)
  UNIQUE (player_id, quest_id)

player_guide   (player_id, step_id, status, completed_at)
  PRIMARY KEY (player_id, step_id)
```

`status` 枚举：

- `in_progress` — 进行中
- `completed` — 已完成待领取
- `claimed` — 已领取
- `locked` — 前置任务未完成（保留状态，目前用顺序队列替代）
- `active` / `done` / `skipped` — 仅用于 guide 表

---

## 五、REST 接口

| 方法 | 路径                            | 说明                               |
| ---- | ------------------------------- | ---------------------------------- |
| GET  | `/api/game/quest/list`          | 拉取玩家全部任务状态 (懒初始化)    |
| POST | `/api/game/quest/claim`         | 领取奖励，body=`{questId: "q1_3"}` |
| GET  | `/api/game/quest/guide`         | 拉取当前引导步骤                   |
| POST | `/api/game/quest/guide/advance` | 推进到下一步                       |
| POST | `/api/game/quest/guide/skip`    | 跳过整个训练营                     |

所有接口均要求 JWT 认证（沿用 `JwtAuthenticationFilter`）。

---

## 六、前端

| 文件                                                                             | 作用                              |
| -------------------------------------------------------------------------------- | --------------------------------- |
| [main-quest.js](file:///Users/chenjuan/Documents/游戏/frontend/js/main-quest.js) | 模块主体，暴露 `Game.MainQuest`   |
| [api.js 增补](file:///Users/chenjuan/Documents/游戏/frontend/js/api.js)          | 5 个 API 调用方法                 |
| [main.js 增补](file:///Users/chenjuan/Documents/游戏/frontend/js/main.js)        | 导航项 [7] 任务 + 红点 + 启动钩子 |
| [index.html 增补](file:///Users/chenjuan/Documents/游戏/frontend/index.html)     | 加载 `main-quest.js`              |
| [style.css 增补](file:///Users/chenjuan/Documents/游戏/frontend/css/style.css)   | 任务面板 / 红点 / 引导浮窗样式    |

### UI 行为

- **底部浮窗** (`#guideBubble`)：常驻屏幕底部 80px 高度，4/8 步骤可见。
- **任务页面** (`route: mainQuest`)：4 个章节面板，每个面板含进度条 + 子任务列表。
- **红点提示**：当任一任务 `completed` 可领取时，导航 [7] 出现红色 `!` 脉冲红点。
- **领取奖励**：点击 "领取奖励" 按钮后，前端调 `/claim`，成功后再 `loadQuests` 刷新。

### 旧引导弹窗已替换

之前 [SECURITY_FIXES.md](file:///Users/chenjuan/Documents/游戏/SECURITY_FIXES.md) 中添加的 `Main.showTutorial` / `Main.shouldShowTutorial` 逻辑已弃用，统一由 `MainQuest.init()` 接管。

---

## 七、设计决策

1. **服务端权威**：所有进度都从业务事件钩子累加，前端无法伪造（关掉 localStorage 也无法 hack）。
2. **事件类型 + targetKey 模式**：`ARMY_RECRUIT` 事件带 `targetKey=infantry`，任务 `q1_4` 过滤后只累加步兵；同事件也可被 `q2_2` 用来累加炮兵。
3. **复合任务用 `targetKey=null`**：例如 `ARMY_TOTAL` 触发时直接 `set` 当前总兵力（不是累加），因为总兵力是单值指标。
4. **自动解锁下一任务**：当前任务 `completed` 时，链式 `ensureRow` 下一任务的行（status=in_progress）。这样前端无需关注依赖关系。
5. **钩子容错**：所有 `onEvent` 都 try-catch，避免任务系统拖垮战斗 / 招兵 / 建造。
6. **奖励走 `PlayerItemRepository.tryConsume` 反向**：领取时用 +delta 直接给物品，不需要单独 `addItem` 路径，逻辑统一。
7. **不替换原有 task-quests.js**：原"每日任务 / 活跃度"系统保留为独立体系；新系统是"长期主线"。

---

## 八、扩展指引

- 新增任务：在 [QuestCatalog.java](file:///Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/quest/QuestCatalog.java) 的 `CHAPTERS` 列表里追加一个 `quest(...)` 即可。
- 新增事件类型：在 `onEvent` 内的 `switch` 中加分支 + 任何 Service 里调用 `onEvent(playerId, "MY_EVENT", key, delta)`。
- 调整奖励：直接改 `Reward(...)` 的参数即可，无需改 SQL。
- 国际化：未来若做多语言，把 title/desc 抽到 i18n 资源文件即可。
