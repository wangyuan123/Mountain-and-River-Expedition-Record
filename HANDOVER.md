# 交接文档 — 山河远征初版

> 本文档面向下一个接手对话的 AI / 开发者，请优先阅读本文件再行动。

---

## 1. 项目概览

**项目名**：山河远征 - 初步框架实现
**位置**：`/Users/chenjuan/Documents/游戏/`
**类型**：二战题材战争策略网页游戏

**核心玩法**：玩家建主城、采集资源、升级建筑、招募军官、出征野地/玩家城、防守反击。

---

## 2. 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Java 17 + Spring Boot 3.2.5 + JPA + WebSocket + Spring Security + JWT |
| 数据库 | MySQL 8.0 + Flyway 迁移（V1~V6） |
| 前端 | 原生 JavaScript（无框架，ES6+，IIFE 模块化） |
| 样式 | CSS3，渐变，flex/grid 布局 |
| 安全 | JWT、限流、XSS HTML 实体转义、敏感词过滤 |
| 持久化 | localStorage（前端）+ MySQL（后端） |

---

## 3. 目录结构

```
游戏/
├── backend/                            # Java Spring Boot (端口 8080)
│   ├── src/main/java/com/wargame/
│   │   ├── controller/                # REST 控制器
│   │   │   ├── AuthController         # 注册/登录/guest
│   │   │   ├── GameController         # 游戏状态
│   │   │   ├── BuildController        # 建筑升级
│   │   │   ├── ArmyController         # 军队
│   │   │   ├── FortController         # 城防
│   │   │   ├── OfficerController      # 军官
│   │   │   ├── QuestController        # 主线任务
│   │   │   ├── TechController         # 科技
│   │   │   ├── WildController         # 野地
│   │   │   └── WorldController        # 世界地图
│   │   ├── service/                   # 业务服务
│   │   │   ├── GameStateService       # 状态聚合（前端拉这个）
│   │   │   ├── TickService            # 后端 tick 核心（资源/薪资/tick）
│   │   │   ├── OfficerService         # 军校刷新、招募
│   │   │   ├── BuildService           # 建筑升级
│   │   │   ├── MarchService           # 行军
│   │   │   ├── BattleService          # 战斗
│   │   │   ├── WorldService           # 世界地图
│   │   │   └── quest/                 # 主线任务系统
│   │   ├── model/
│   │   │   ├── entity/                # JPA 实体
│   │   │   ├── dto/                   # DTO
│   │   │   └── constants/             # 静态定义（建筑/科技/单位等）
│   │   ├── repository/                # JPA 仓库
│   │   ├── config/                    # Spring Security/WS/CORS
│   │   └── util/                      # JWT、JSON 工具
│   ├── src/main/resources/
│   │   ├── application.yml            # localhost MySQL 配置
│   │   ├── application-prod.yml       # 生产配置
│   │   └── db/migration/              # Flyway 迁移脚本
│   │       ├── V1__init.sql
│   │       ├── V2__game_tables.sql
│   │       ├── V3__add_construction_slot.sql
│   │       ├── V4__add_player_level_and_diamond.sql
│   │       ├── V5__schema_hardening.sql
│   │       └── V6__quest_system.sql
│   └── pom.xml
│
├── frontend/                           # 前端静态资源 (端口 8081)
│   ├── index.html                     # 唯一 HTML，引入所有 JS
│   ├── css/style.css                  # 全部样式（2554 行）
│   ├── js/                            # 23 个 JS 文件
│   │   ├── api-client.js              # HTTP/WS 客户端基类
│   │   ├── api.js                     # API 封装
│   │   ├── core.js                    # 核心引擎（路由/render）
│   │   ├── main.js                    # 主界面 + 顶部 + 导航（511 行）
│   │   ├── data.js                    # 静态数据
│   │   ├── save.js                    # 存档兼容
│   │   ├── build.js                   # 建筑
│   │   ├── army.js                    # 军队
│   │   ├── fort.js                    # 城防
│   │   ├── officer.js                 # 军官
│   │   ├── tech.js                    # 科技
│   │   ├── map.js                     # 地图
│   │   ├── world.js                   # 世界地图
│   │   ├── battle.js                  # 战斗
│   │   ├── depot.js                   # 仓库（14 种道具）
│   │   ├── shop.js                    # 商城（19 商品）
│   │   ├── recharge.js                # 充值
│   │   ├── mail.js                    # 邮件
│   │   ├── rank.js                    # 排名
│   │   ├── chat.js                    # 世界频道
│   │   ├── task.js                    # 每日签到 + 首页布局
│   │   ├── task-quests.js             # 每日任务系统（311 行）
│   │   ├── main-quest.js              # 主线任务
│   │   ├── ws-client.js               # WebSocket
│   │   └── ws-handlers.js             # WS 消息处理
│   ├── img/                           # 12 个 SVG 资源图标 + settings.svg
│   └── _test_detail.js                # 测试用脚本
│
├── docs/DESIGN.md                     # 设计文档
├── QUEST_SYSTEM.md                    # 主线任务系统文档
├── SECURITY_FIXES.md                  # 安全修复说明
├── docker-compose.yml
├── start-local.sh                     # 本地一键启动
└── HANDOVER.md                        # ← 本文档
```

---

## 4. 已完成的核心模块

| 模块 | 文件 | 状态 |
|---|---|---|
| 用户注册/登录/游客 | [AuthController.java](file:///Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/controller/AuthController.java) + [api.js](file:///Users/chenjuan/Documents/游戏/frontend/js/api.js) | ✅ JWT 鉴权 |
| 资源系统 | [TickService.java](file:///Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/TickService.java) | ✅ 粮/钢/油/稀/金/钻 六资源 |
| 建筑升级 | [BuildService.java](file:///Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/BuildService.java) + [build.js](file:///Users/chenjuan/Documents/游戏/frontend/js/build.js) | ✅ slots + level |
| 军队/征兵 | [ArmyService.java](file:///Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/ArmyService.java) + [army.js](file:///Users/chenjuan/Documents/游戏/frontend/js/army.js) | ✅ |
| 城防工事 | [FortService.java](file:///Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/FortService.java) + [fort.js](file:///Users/chenjuan/Documents/游戏/frontend/js/fort.js) | ✅ |
| 军官系统 | [OfficerService.java](file:///Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/OfficerService.java) + [officer.js](file:///Users/chenjuan/Documents/游戏/frontend/js/officer.js) | ✅ 军校刷新/招募/洗练 |
| 科技 | [TechService.java](file:///Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/TechService.java) + [tech.js](file:///Users/chenjuan/Documents/游戏/frontend/js/tech.js) | ✅ |
| 世界地图/野地 | [WorldService.java](file:///Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/WorldService.java) + [world.js](file:///Users/chenjuan/Documents/游戏/frontend/js/world.js) | ✅ 侦查/采集/出征 |
| 战斗 | [BattleService.java](file:///Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/BattleService.java) + [battle.js](file:///Users/chenjuan/Documents/游戏/frontend/js/battle.js) | ✅ |
| 仓库（14 道具） | [DepotService.java](file:///Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/DepotService.java) + [depot.js](file:///Users/chenjuan/Documents/游戏/frontend/js/depot.js) | ✅ 经验书/技能书/忠诚宝箱/改名卡/征募令/星耀符/资源箱/加速符/护盾/行军令/反侦察符 |
| 商城 | [shop.js](file:///Users/chenjuan/Documents/游戏/frontend/js/shop.js) | ✅ 19 商品，4 大类（军官/资源/功能/礼包） |
| 充值 | [recharge.js](file:///Users/chenjuan/Documents/游戏/frontend/js/recharge.js) | ✅ 3 大类 + 4 渠道 |
| 邮件 | [mail.js](file:///Users/chenjuan/Documents/游戏/frontend/js/mail.js) | ✅ 4 文件夹（收件/发件/系统/未读） |
| 排行榜 | [rank.js](file:///Users/chenjuan/Documents/游戏/frontend/js/rank.js) | ✅ 3 维度（声望/军团/人口） |
| 世界频道 | [chat.js](file:///Users/chenjuan/Documents/游戏/frontend/js/chat.js) | ✅ XSS 防护 + 3s 限速 |
| 主线任务 | [main-quest.js](file:///Users/chenjuan/Documents/游戏/frontend/js/main-quest.js) + [QuestService.java](file:///Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/quest/QuestService.java) | ✅ |
| **每日任务** | [task-quests.js](file:///Users/chenjuan/Documents/游戏/frontend/js/task-quests.js) | ✅ 6 池抽 3 / 事件驱动 / 真实奖励 / 每日刷新 / 手动重抽 1 次 |
| 签到 | [task.js](file:///Users/chenjuan/Documents/游戏/frontend/js/task.js) | ✅ |
| 新手引导 | [main.js showTutorial](file:///Users/chenjuan/Documents/游戏/frontend/js/main.js) | ✅ 居中 modal + 账号独立持久化 |

---

## 5. 启动方式

### 后端
```bash
cd /Users/chenjuan/Documents/游戏/backend
./mvnw spring-boot:run          # 端口 8080
```
依赖 MySQL 8.0（`application.yml` 配 localhost:3306/wargame），Flyway 自动迁移。

### 前端
```bash
cd /Users/chenjuan/Documents/游戏/frontend
python3 -m http.server 8081      # 或任何静态服务器
```
访问 `http://localhost:8081/`

### 一键
```bash
/Users/chenjuan/Documents/游戏/start-local.sh
```

---

## 6. 本对话期间（最近一次）的主要改动汇总

按时间倒序：

1. **首页资源卡样式重做**（[style.css](file:///Users/chenjuan/Documents/游戏/frontend/css/style.css#L484)）
   - 字号缩小、padding 减小、防止内容溢出
   - 添加 `min-width: 0 + overflow: hidden` 防 6 位数数字撑破卡片

2. **今日战果紧凑化**（[style.css](file:///Users/chenjuan/Documents/游戏/frontend/css/style.css#L1392)）
   - 数字 18px → 13px；改为横向布局（数字 + 标签左右并排）

3. **新手引导弹窗**（[main.js](file:///Users/chenjuan/Documents/游戏/frontend/js/main.js#L415-L457) + [style.css](file:///Users/chenjuan/Documents/游戏/frontend/css/style.css#L2385)）
   - 补全 `.modal-mask` / `.modal-card` 居中样式（之前是缺失的，弹窗堆到底部）
   - **真根因修复**：`shouldShowTutorial` / `showTutorial` 之前**从未被调用**，在 boot 中补上 trigger
   - 引导文案修正：
     - "每 5 秒扣一次军饷" → "系统按**每小时**扣除军官薪资总额"（与 TickService 实际逻辑一致）
     - "50G/次" → "**200 黄金/次**"（与 OfficerService.ACADEMY_REFRESH_COST=200 一致）
     - 删除"反作弊提示"段落

4. **设置入口齿轮图标**（[settings.svg](file:///Users/chenjuan/Documents/游戏/frontend/img/settings.svg)）
   - 自制金属色 8 齿齿轮 SVG（银→钢蓝渐变 + 高光弧 + 中心镂空）
   - [main.js](file:///Users/chenjuan/Documents/游戏/frontend/js/main.js#L37) NAV_ITEMS 加 `icon` 字段
   - hover 旋转 45° 动效

5. **localStorage 多账号隔离**（4 个模块统一改造）
   - [recharge.js](file:///Users/chenjuan/Documents/游戏/frontend/js/recharge.js) `wg.recharge.v1` → `wg.recharge.v1.<user>`（含 admin 老数据迁移）
   - [mail.js](file:///Users/chenjuan/Documents/游戏/frontend/js/mail.js) `wg.mail.v1` → `wg.mail.v1.<user>`
   - [task-quests.js](file:///Users/chenjuan/Documents/游戏/frontend/js/task-quests.js) `wg.quests.v1` / `wg.questLastManual` → 带后缀
   - [task.js](file:///Users/chenjuan/Documents/游戏/frontend/js/task.js) `wg.checkin` → `wg.checkin.<user>`
   - [main.js](file:///Users/chenjuan/Documents/游戏/frontend/js/main.js) `wargame.tutorial.dismissed` → 带后缀

6. **每日任务系统完整实现**（[task-quests.js](file:///Users/chenjuan/Documents/游戏/frontend/js/task-quests.js)）
   - 6 池抽 3：login / gather / attack / scout / build / recruit
   - 事件驱动：`Quests.onEvent('login'|'gather'|'attack'|'scout'|'build'|'recruit', amount)`
   - 真实奖励发放（钻石/黄金/经验/物品）
   - 每日 0 点自动刷新（基于日期 key YYYYMMDD）
   - 手动重抽每日 1 次
   - 任务卡 UI：入口卡可折叠展开，展开后含"📋 怎么完成：xxx"说明、"去完成"按钮跳转到对应页面

7. **首页布局重构**（[task.js](file:///Users/chenjuan/Documents/游戏/frontend/js/task.js) + [main.js](file:///Users/chenjuan/Documents/游戏/frontend/js/main.js)）
   - 活动与任务 / 代办事项 / 战情速递 / 今日战果
   - 签到 + 任务 + 限时活动（联盟战已删除）

---

## 7. 重要约定（接手必读）

### 7.1 localStorage key 必须带账号后缀
浏览器 localStorage 是基于 origin 共享的，**所有账号共用同一个浏览器时数据会串号**。
任何新增的 localStorage key 都必须按以下模式：

```js
function _userKey() {
  try { return (G.API && G.API.getUsername && G.API.getUsername()) || 'guest'; }
  catch (e) { return 'guest'; }
}
// 读写时:
localStorage.getItem('wg.xxx.' + _userKey())
```

### 7.2 前端 `G.Task` 命名空间不能整体覆盖
之前踩过的坑：[task.js](file:///Users/chenjuan/Documents/游戏/frontend/js/task.js) 早期用 `G.Task = { ... }` 整体赋值，**覆盖掉了** task-quests.js 已经挂载的 `G.Task.Quests` 和 `G.Task.renderQuestCard`。**正确写法**：

```js
G.Task = G.Task || {};
G.Task.renderActivities = renderActivities;
// 逐个挂载，保留已有内容
```

### 7.3 新增 modal 必须有 `.modal-mask` 居中样式
通用居中弹窗样式在 [style.css#L2385](file:///Users/chenjuan/Documents/游戏/frontend/css/style.css#L2385)，使用 class `modal-mask` + `modal-card` + `modal-title` + `modal-body` + `modal-foot`。

### 7.4 新增后端字段必须写 Flyway 迁移
`application.yml` 配 `ddl-auto: validate`，新增字段必须：
1. 改 [entity](file:///Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/entity/) 对应类
2. 新建 `V{N}__xxx.sql` 在 [migration](file:///Users/chenjuan/Documents/游戏/backend/src/main/resources/db/migration/) 目录

当前最大版本号是 V6。

### 7.5 资源数字使用衬线字体（军事化）
```css
font-family: Georgia, "Times New Roman", "Songti SC", "STSong", serif;
color: #e2c47a; /* 古铜金 */
```

### 7.6 Core.render() 会被周期性调用
WebSocket 推送、计时器 tick、行军倒计时都会触发 `Core.render()` 重写整个 view 容器。
**不要**在 view 元素上加 `animation` 属性（会重播导致闪烁）。

### 7.7 任务事件接入点
要在某操作触发对应每日任务进度，调用：
```js
if (G.Task && G.Task.Quests) G.Task.Quests.onEvent('login'|'gather'|'attack'|'scout'|'build'|'recruit');
```
当前触发点：
- [main.js](file:///Users/chenjuan/Documents/游戏/frontend/js/main.js#L488) `boot` → `onEvent('login')`
- [world.js](file:///Users/chenjuan/Documents/游戏/frontend/js/world.js#L310) 出征 → scout/gather/attack
- [build.js](file:///Users/chenjuan/Documents/游戏/frontend/js/build.js#L63) 建筑升级 → build
- [officer.js](file:///Users/chenjuan/Documents/游戏/frontend/js/officer.js#L44) 招募 → recruit

---

## 8. 关键数值（可调）

| 数值 | 位置 | 当前值 | 说明 |
|---|---|---|---|
| 初始资源 | [TickService.java](file:///Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/TickService.java) | 粮钢油稀 10万/黄金 10万 | 起始平衡 |
| 黄金产出公式 | [TickService.java#L186](file:///Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/TickService.java#L186) | pop × tax × (1+know%) × 2 金/h | 默认 30 金/h |
| 军校刷新费 | [OfficerService.java#L46](file:///Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/OfficerService.java#L46) | **200** 黄金/次 | ⚠️ 本次从 50 改为 200 |
| 军官薪资公式 | [OfficerService.java#L560](file:///Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/OfficerService.java#L560) | star × 10 + base/10 | 5星约 60+ 金/h |
| 每日任务 6 池 | [task-quests.js#L26](file:///Users/chenjuan/Documents/游戏/frontend/js/task-quests.js#L26) | 3 个/日 | 手动重抽 1 次/日 |
| 仓库道具 | [data.js](file:///Users/chenjuan/Documents/游戏/frontend/js/data.js) | 14 种 | 详见 depot.js |
| 商城商品 | [shop.js](file:///Users/chenjuan/Documents/游戏/frontend/js/shop.js) | 19 件 | 4 类 |
| 充值档位 | [recharge.js](file:///Users/chenjuan/Documents/游戏/frontend/js/recharge.js) | 试玩 6 元/元帅 1280 元 | + 2 张月卡 |
| 新玩家资源上限 | [main.js#L420](file:///Users/chenjuan/Documents/游戏/frontend/js/main.js#L420) | 每种 ≤ 12万 | 触发新手指引 |

---

## 9. 已知问题 / 待办

- **数据库连接**：`application.yml` 配的是 localhost MySQL（root/root），开发环境 OK，生产需改 `application-prod.yml` 用环境变量。
- **WebSocket 鉴权**：guest 账号不发 WS（[main.js#L495](file:///Users/chenjuan/Documents/游戏/frontend/js/main.js#L495)），但具体实现可能需要再看 [ws-client.js](file:///Users/chenjuan/Documents/游戏/frontend/js/ws-client.js)。
- **V5 schema hardening**：V5 迁移已存在但用户刚打开，**未仔细审阅**——接手时优先 review 这个迁移文件。
- **V6 quest_system**：主线任务系统的迁移脚本。
- **测试覆盖**：[backend/src/test](file:///Users/chenjuan/Documents/游戏/backend/src/test/) 有 BaseServiceTest / BattleServiceTest / BuildServiceTest / MarchServiceTest / TickServiceTest，但前端无单测。
- **Docker**：[Dockerfile](file:///Users/chenjuan/Documents/游戏/backend/Dockerfile) + [docker-compose.yml](file:///Users/chenjuan/Documents/游戏/docker-compose.yml) 已就绪但未做联调验证。

---

## 10. 接手时建议的第一步

1. **启动一次**：跑后端 + 前端，用 admin 账号登录，确认 5 个 nav 标签都能正常切。
2. **看 main.js boot 流程**（[main.js#L467-L520](file:///Users/chenjuan/Documents/游戏/frontend/js/main.js#L467)）：理解初始化顺序（load state → render nav → render view → 任务/邮件/聊天 seed → WS 连接）。
3. **看 task-quests.js**（311 行）：理解每日任务的事件驱动模型。
4. **看 TickService**：理解后端经济/薪资/tick 周期。
5. **审 V5/V6 迁移文件**：可能 schema 与 entity 不一致。

---

## 11. 文档参考

- [README.md](file:///Users/chenjuan/Documents/游戏/README.md) — 项目总体说明
- [docs/DESIGN.md](file:///Users/chenjuan/Documents/游戏/docs/DESIGN.md) — 设计文档
- [QUEST_SYSTEM.md](file:///Users/chenjuan/Documents/游戏/QUEST_SYSTEM.md) — 主线任务系统
- [SECURITY_FIXES.md](file:///Users/chenjuan/Documents/游戏/SECURITY_FIXES.md) — 安全修复

---

**最后更新**：本对话结束前
**作者备注**：游戏已具备完整的"建城—发展—出战"主循环，资源/经济/任务/付费链路均已跑通。下一步可考虑：联盟系统、跨服战、赛季奖励。
