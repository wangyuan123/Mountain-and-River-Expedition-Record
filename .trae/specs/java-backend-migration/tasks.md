# Tasks

## 阶段一：后端基础设施搭建

- [x] Task 1: 创建 Spring Boot 项目骨架
  - [x] SubTask 1.1: 初始化 Maven 项目，配置 Spring Boot、Spring Web、Spring Data JPA、PostgreSQL Driver、Spring Security、WebSocket 依赖
  - [x] SubTask 1.2: 配置 application.yml（数据库连接、JWT 密钥、服务器端口）
  - [x] SubTask 1.3: 创建分层包结构：controller / service / repository / model / config / dto

- [x] Task 2: 数据库设计与实体定义
  - [x] SubTask 2.1: 设计数据库表结构（player, resources, buildings, army, forts, tech, officers, world, marches, constructions, academy, city_state, wild_tiles, npc_cities, player_cities, bandits）
  - [x] SubTask 2.2: 创建 JPA 实体类，映射数据库表
  - [x] SubTask 2.3: 创建 Repository 接口（CRUD + 自定义查询）
  - [x] SubTask 2.4: 编写 Flyway/Liquibase 数据库迁移脚本

- [x] Task 3: 用户认证系统
  - [x] SubTask 3.1: 实现 JwtUtil（生成/验证 JWT token）
  - [x] SubTask 3.2: 实现 JwtAuthenticationFilter（请求拦截校验 token）
  - [x] SubTask 3.3: 实现 AuthController（register / login / logout 端点）
  - [x] SubTask 3.4: 实现 SecurityConfig（配置路由权限、CORS）

## 阶段二：核心逻辑迁移

- [x] Task 4: 静态数据定义迁移（data.js -> Java 常量）
  - [x] SubTask 4.1: 创建 GameData 类，定义资源、建筑、兵种、科技、城防、野地类型等静态数据
  - [x] SubTask 4.2: 创建军官技能、历史军官等数据定义
  - [x] SubTask 4.3: 编写单元测试验证数据完整性

- [x] Task 5: 游戏状态服务（GameStateService）
  - [x] SubTask 5.1: 实现 getGameState()：从数据库组装完整玩家状态返回给前端
  - [x] SubTask 5.2: 实现世界生成逻辑（genWorld）：寇城、玩家城池、野地、流寇的随机生成
  - [x] SubTask 5.3: 实现旧存档导入功能（JSON -> 数据库一次性迁移）

- [x] Task 6: Tick 引擎迁移（core.js tick -> TickService）
  - [x] SubTask 6.1: 实现资源产出计算（农场/炼钢厂/油田/稀矿场产出，受城市状态加成）
  - [x] SubTask 6.2: 实现粮食消耗、黄金税收、军官忠诚度变化
  - [x] SubTask 6.3: 实现城市状态更新（战争/和平/护盾过期）
  - [x] SubTask 6.4: 实现离线 tick 补算（上限 8 小时）
  - [x] SubTask 6.5: 配置 Spring Scheduled 定时任务（每 5 秒执行）

- [x] Task 7: 行军系统迁移（world.js processMarches -> MarchService）
  - [x] SubTask 7.1: 实现行军创建（dispatch）：校验部队、计算行军时间
  - [x] SubTask 7.2: 实现行军推进：到达后根据 targetKind 分发处理
  - [x] SubTask 7.3: 实现野地征服：调用 BattleService 结算，胜利标记占领
  - [x] SubTask 7.4: 实现野地采集：到达->采集->返程->入库，更新 tile.mined
  - [x] SubTask 7.5: 实现寇城/玩家城池攻击：调用 BattleService 结算
  - [x] SubTask 7.6: 实现侦查行军：生成侦查报告存入数据库
  - [x] SubTask 7.7: 实现复仇行军（NPC 反击）

- [x] Task 8: 战斗系统迁移（battle.js -> BattleService）
  - [x] SubTask 8.1: 实现 Battle.resolveWild：野地守军战斗结算
  - [x] SubTask 8.2: 实现 Battle.startWorldDispatch：完整战斗流程（兵种属性、城防、技能加成）
  - [x] SubTask 8.3: 实现战报生成与存储
  - [x] SubTask 8.4: 实现战斗结果应用（部队损耗、资源掠夺、城市占领）

- [x] Task 9: 建造系统迁移（build.js -> BuildService）
  - [x] SubTask 9.1: 实现建造队列管理（升级、取消）
  - [x] SubTask 9.2: 实现建造完成检查（时间到期->升级等级）
  - [x] SubTask 9.3: 实现资源校验与扣除

- [x] Task 10: 其他子系统迁移
  - [x] SubTask 10.1: TechService：科技升级、科技效果计算
  - [x] SubTask 10.2: OfficerService：军校刷新、招募、任命、赏赐、技能学习
  - [x] SubTask 10.3: ArmyService：征兵、解散、兵种解锁
  - [x] SubTask 10.4: FortService：城防修筑、拆除
  - [x] SubTask 10.5: DepotService：仓库保护量计算
  - [x] SubTask 10.6: WorldService：地图移动、扫描、宣战、附近城池查询

## 阶段三：API 层实现

- [x] Task 11: 游戏操作 API
  - [x] SubTask 11.1: GameController.getState()：GET /api/game/state
  - [x] SubTask 11.2: 建造操作：POST /api/game/build/upgrade, /api/game/build/cancel
  - [x] SubTask 11.3: 征兵操作：POST /api/game/army/recruit, /api/game/army/dismiss
  - [x] SubTask 11.4: 科技操作：POST /api/game/tech/upgrade
  - [x] SubTask 11.5: 军官操作：POST /api/game/officer/recruit, /appoint, /reward, /learn-skill
  - [x] SubTask 11.6: 城防操作：POST /api/game/fort/build, /fort/dismantle
  - [x] SubTask 11.7: 世界操作：POST /api/game/world/move, /world/scan, /world/dispatch, /world/declare-war
  - [x] SubTask 11.8: 野地操作：POST /api/game/wild/scout, /wild/conquer, /wild/gather, /wild/abandon
  - [x] SubTask 11.9: 设置操作：POST /api/game/settings/tax, /settings/reset

- [x] Task 12: WebSocket 实时推送
  - [x] SubTask 12.1: 配置 WebSocketConfig（端点 /ws/game，JWT 握手认证）
  - [x] SubTask 12.2: 实现 GameWebSocketHandler：管理在线玩家 Session
  - [x] SubTask 12.3: 实现 tick 结果推送（资源变化、行军进度）
  - [x] SubTask 12.4: 实现战报推送（攻击/被攻击通知）
  - [x] SubTask 12.5: 实现行军状态变化推送（到达/采集完成/返程）

## 阶段四：前端重构

- [x] Task 13: 前端 API 层重构
  - [x] SubTask 13.1: 重写 api.js，新增所有游戏操作 API 调用方法
  - [x] SubTask 13.2: 实现 ApiClient 类：统一请求/错误处理/loading 状态
  - [x] SubTask 13.3: 移除 G.save() / G.load() / localStorage 存档逻辑

- [x] Task 14: 前端 WebSocket 客户端
  - [x] SubTask 14.1: 实现 WS 连接管理（连接/断线重连/心跳）
  - [x] SubTask 14.2: 实现消息分发器：收到推送 -> 更新本地状态 -> 触发局部刷新
  - [x] SubTask 14.3: 实现行军倒计时（基于后端时间戳，前端仅展示）

- [x] Task 15: 前端各模块重构为 API 驱动
  - [x] SubTask 15.1: core.js -> 移除 tick 计算，改为接收 WS 推送刷新
  - [x] SubTask 15.2: build.js -> 升级/取消改为调用 API
  - [x] SubTask 15.3: army.js -> 征兵/解散改为调用 API
  - [x] SubTask 15.4: tech.js -> 科技升级改为调用 API
  - [x] SubTask 15.5: officer.js -> 军官操作改为调用 API
  - [x] SubTask 15.6: world.js -> 地图操作/出征/宣战改为调用 API
  - [x] SubTask 15.7: battle.js -> 移除前端战斗计算，改为展示后端战报
  - [x] SubTask 15.8: fort.js / depot.js -> 改为调用 API
  - [x] SubTask 15.9: map.js -> 战役地图改为调用 API
  - [x] SubTask 15.10: save.js -> 移除存档逻辑，保留仅旧数据导出功能

- [x] Task 16: 前端渲染优化
  - [x] SubTask 16.1: 实现局部刷新机制（仅更新变化的 UI 区域，避免全量重渲染）
  - [x] SubTask 16.2: 实现加载状态/错误提示统一组件
  - [x] SubTask 16.3: 实现离线提示与断线重连 UI

## 阶段五：测试与部署

- [ ] Task 17: 后端测试
  - [ ] SubTask 17.1: 编写 TickService 单元测试（资源产出/消耗/离线补算）
  - [ ] SubTask 17.2: 编写 BattleService 单元测试（各种战斗场景）
  - [ ] SubTask 17.3: 编写 MarchService 单元测试（行军/采集/征服）
  - [ ] SubTask 17.4: 编写 API 集成测试（认证/操作/权限校验）

- [ ] Task 18: 前后端联调
  - [ ] SubTask 18.1: 联调认证流程（注册/登录/游客模式）
  - [ ] SubTask 18.2: 联调核心玩法（建造/征兵/科技/军官）
  - [ ] SubTask 18.3: 联调世界地图（移动/扫描/出征/野地采集）
  - [ ] SubTask 18.4: 联调 WebSocket 实时推送
  - [ ] SubTask 18.5: 联调战斗系统（攻城/野地/复仇）

- [ ] Task 19: 部署配置
  - [ ] SubTask 19.1: 编写 Dockerfile（Java 后端）
  - [ ] SubTask 19.2: 编写 docker-compose.yml（后端 + PostgreSQL + Nginx）
  - [ ] SubTask 19.3: 配置 Nginx 反向代理（前端静态文件 + API + WebSocket）
  - [ ] SubTask 19.4: 编写旧存档迁移脚本（localStorage JSON -> 数据库）

# Task Dependencies
- [Task 2] depends on [Task 1]
- [Task 3] depends on [Task 1]
- [Task 4] depends on [Task 2]
- [Task 5] depends on [Task 2, Task 4]
- [Task 6] depends on [Task 4, Task 5]
- [Task 7] depends on [Task 5, Task 6]
- [Task 8] depends on [Task 4, Task 5]
- [Task 9] depends on [Task 5]
- [Task 10] depends on [Task 5, Task 6]
- [Task 11] depends on [Task 5, Task 6, Task 7, Task 8, Task 9, Task 10]
- [Task 12] depends on [Task 6, Task 7, Task 8]
- [Task 13] depends on [Task 11]
- [Task 14] depends on [Task 12]
- [Task 15] depends on [Task 13, Task 14]
- [Task 16] depends on [Task 15]
- [Task 17] depends on [Task 6, Task 7, Task 8, Task 11]
- [Task 18] depends on [Task 11, Task 12, Task 15]
- [Task 19] depends on [Task 18]
