# 逻辑层迁移至 Java 后端 Spec

## Why
当前游戏逻辑全部运行在前端 JS 中，玩家可通过修改 localStorage 篡改资源、兵力、战斗结果等核心数据。将逻辑层迁移至 Java 后端后，所有游戏计算在服务器执行，客户端仅负责渲染和发送操作请求，从根本上解决防篡改问题，同时为多人对战奠定基础。

## What Changes
- 新建 Java Spring Boot 后端项目，承载全部游戏逻辑
- 将 JS 中的 `data.js`（数据定义）、`core.js`（tick/产出/消耗）、`battle.js`（战斗结算）、`save.js`（世界生成/存档结构）、`world.js`（行军/野地/地图逻辑）、`build.js`（建造）、`tech.js`（科技）、`officer.js`（军官）、`army.js`（征兵）、`fort.js`（城防）、`depot.js`（仓库）的逻辑迁移至 Java 服务层
- 数据库持久化替代 localStorage（PostgreSQL）
- REST API 替代前端直接状态读写，所有操作需经后端校验
- WebSocket 推送实时更新（行军进度、tick 结果、战争状态变化）
- 前端 JS 重构为纯渲染层：调用 API 获取状态 -> 渲染 -> 用户操作 -> 调用 API -> 接收推送刷新
- **BREAKING**: 前端不再持有完整 state 对象，不再有 `G.save()`/`G.load()` 本地存档
- **BREAKING**: 存档格式从单个 JSON blob 变为数据库结构化存储
- **BREAKING**: tick 从前端 `setInterval(5000)` 迁移至后端定时任务

## Impact
- Affected code: 全部 14 个 JS 文件、index.html、api.js
- New code: Java 后端项目（Spring Boot + MyBatis/JPA + PostgreSQL + WebSocket）
- 现有 `api.js` 的 `/register`、`/login`、`/save` 端点将被替换为完整的游戏 API

## ADDED Requirements

### Requirement: Java 后端项目骨架
系统 SHALL 提供 Spring Boot 后端项目，包含分层架构（Controller / Service / Repository / Model）。

#### Scenario: 项目启动
- **WHEN** 开发者运行 `mvn spring-boot:run`
- **THEN** 后端启动，监听 8080 端口，数据库连接就绪

### Requirement: 数据模型定义
系统 SHALL 在 Java 中定义与当前 JS `data.js` 和 `save.js` 对应的数据模型。

#### Scenario: 数据模型映射
- **WHEN** 后端启动
- **THEN** 以下实体已定义并通过 JPA 映射到数据库表：
  - Player（玩家信息：用户名、阵营、城市名、坐标）
  - Resources（资源：粮/钢/油/稀/金）
  - Buildings（建筑：类型->等级）
  - Army（部队：兵种->数量）
  - Forts（城防：类型->数量）
  - Tech（科技：类型->等级）
  - Officers（军官：属性、技能、忠诚、角色）
  - World（世界：地图大小、寇城、玩家城池、野地、流寇）
  - Marches（行军：出发/到达时间、目标、部队、状态）
  - Constructions（建造队列）
  - Academy（军校刷新列表）
  - CityState（城市状态：战争/和平/护盾）

### Requirement: 游戏 API 端点
系统 SHALL 提供 RESTful API 供前端调用，所有操作在后端校验后执行。

#### Scenario: 获取游戏状态
- **WHEN** 前端发送 `GET /api/game/state`
- **THEN** 返回当前玩家完整游戏状态（资源、建筑、部队、世界等）

#### Scenario: 执行建筑升级
- **WHEN** 前端发送 `POST /api/game/build/upgrade` with `{ building: 'farm' }`
- **THEN** 后端校验资源是否足够、建筑等级上限，扣除资源，加入建造队列，返回更新后的状态

#### Scenario: 执行出征
- **WHEN** 前端发送 `POST /api/game/world/dispatch` with `{ targetIdx, action, army, commanderId, carryRes }`
- **THEN** 后端校验部队数量、资源携带量，创建行军记录，返回更新后的状态

#### Scenario: 无效操作拒绝
- **WHEN** 前端发送资源不足的建造请求
- **THEN** 后端返回 `400 Bad Request`，状态不变，前端提示错误信息

### Requirement: 后端 Tick 引擎
系统 SHALL 在后端运行定时 tick，处理资源产出、消耗、行军推进、建造完成等。

#### Scenario: 定时 tick 执行
- **WHEN** 每 5 秒触发一次后端 tick
- **THEN** 系统计算资源产出/消耗、推进行军、检查建造完成、更新军官忠诚度
- **AND** 通过 WebSocket 推送变化的状态增量给在线玩家

#### Scenario: 离线 tick 补算
- **WHEN** 玩家重新登录
- **THEN** 后端根据 `lastTick` 与当前时间差补算离线期间资源产出（上限 8 小时）

### Requirement: 战斗结算服务
系统 SHALL 在后端执行所有战斗结算，前端无法干预结果。

#### Scenario: 行军到达触发战斗
- **WHEN** 行军到达目标
- **THEN** 后端调用 BattleService 结算战斗，更新双方部队/资源/状态
- **AND** 通过 WebSocket 推送战报给攻击方

#### Scenario: 野地征服
- **WHEN** 征服行军到达野地并击败守军
- **THEN** 后端标记野地为该玩家占领，返回存活部队

### Requirement: WebSocket 实时推送
系统 SHALL 通过 WebSocket 向在线玩家推送实时更新。

#### Scenario: 行军状态变化推送
- **WHEN** 行军到达/采集完成/返程到达
- **THEN** 后端通过 WebSocket 推送事件，前端更新对应 UI

#### Scenario: 被攻击通知
- **WHEN** 其他玩家的攻击行军到达
- **THEN** 后端结算后通过 WebSocket 推送被攻击战报

### Requirement: 前端重构为纯渲染层
前端 JS SHALL 移除所有游戏逻辑计算，仅保留 API 调用和渲染。

#### Scenario: 前端获取状态并渲染
- **WHEN** 页面加载
- **THEN** 前端调用 `GET /api/game/state` 获取状态，渲染 UI
- **AND** 不再有 `G.save()` / `G.load()` / localStorage 存档

#### Scenario: 用户操作走 API
- **WHEN** 用户点击"升级建筑"
- **THEN** 前端调用 `POST /api/game/build/upgrade`，根据响应更新 UI

#### Scenario: WebSocket 驱动刷新
- **WHEN** 前端收到 WebSocket 推送的状态更新
- **THEN** 前端合并更新到当前状态并重新渲染受影响区域

## MODIFIED Requirements

### Requirement: 用户认证
现有 `api.js` 的 register/login 仅管理 token，迁移后认证由后端 Spring Security 处理，token 关联数据库用户。

### Requirement: 存档系统
**现有**: localStorage 存储 JSON blob，可选云同步
**迁移后**: PostgreSQL 结构化存储，前端无本地存档

### Requirement: 世界地图
**现有**: 世界数据在 localStorage 中，前端直接读写
**迁移后**: 世界数据在数据库中，前端通过 API 获取可见范围内的信息

## REMOVED Requirements

### Requirement: 前端本地存档
**Reason**: 所有状态由后端管理，localStorage 仅存 auth token
**Migration**: 首次登录时后端从旧 JSON 导入数据到数据库

### Requirement: 前端 tick 计算
**Reason**: tick 逻辑迁移至后端，防止篡改
**Migration**: 前端 `setInterval(5000)` 改为从 WebSocket 接收更新或轮询 API
