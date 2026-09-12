# 项目维护交接

更新于 2026-09-12。技术栈、启动与测试以 README 为准；玩法和数据流以 docs/DESIGN.md 为准。不要将旧版本交接记录当作当前实现。

## 当前架构

- 后端：Java 17 / Spring Boot / JPA / MySQL / Flyway / JWT / WebSocket。
- 前端：原生 JavaScript，按 HTML 中的顺序加载，各模块挂在 `Game` 下。
- 游戏状态以服务端为准。`save.js` 保留兼容入口，不再负责本地游戏存档。
- 军团、共享世界、排行榜、邮件和聊天已存在，不是待实现的单机扩展。
- 模拟充值按用户要求保留，本轮不修改其开放范围。

## 维护约定

1. 新增账号相关 localStorage 键必须带账号后缀；登录凭据沿用现有统一客户端管理。
2. 扩展 `G.Task` 时逐个挂载字段，不要覆盖已有 `Quests` 和渲染方法。
3. 新模态框复用 `modal-mask` / `modal-card` 等样式；颜色使用现有主题变量。
4. 新增实体字段必须添加新的 Flyway 迁移，不要改已应用的迁移。当前迁移到 V28，后续版本先检查目录。
5. Core.render 会因实时消息重写页面；保持写信、回复和输入焦点保护，不在整个 view 上放反复播放的动画。
6. 任务奖励和进度由服务端判定；前端事件用于刷新展示，不得直接发放权威资源。
7. 修改资源、部队、库存、队列和任务数据必须保留事务及继承的版本字段。发生并发冲突应回滚，不能捕获后继续提交部分结果。
8. 成功通知通过 WebSocketPushService 发送，确保事务提交后才推送。不可绕开它提前宣布战斗胜利或奖励到账。
9. 禁止自动重试写请求。结果不明时应刷新确认，不能把网络失败等同于服务端未执行。
10. 普通状态返回局部地图。全图查询必须是地图交互明确需要，不能在通用状态接口中重新加入全世界扫描。

## 关键模块

- TickScheduler：到期玩家 ID 分页、单轮预算与游标延续。
- TickService：单玩家事务结算，按实际经过时间补算。
- VersionedEntity：资源、部队、队列等可变记录的乐观锁。
- GameStateService：账号状态聚合和初始化。
- WorldViewService：视野读取、城市所属人批量解析；WorldViewController 提供区域接口。
- MarchService：行军与战斗生命周期；MarchTargetService：目标查找与情报数据访问。
- main-view.js：展示；player-profile.js：资料交互；main.js：登录与启动。
- world-view.js：地图请求合并与旧响应丢弃；ws-client.js：事件分发。

## 测试与发布

修改完成后运行前端测试和后端测试。后端普通测试使用 H2，并关闭定时调度，避免测试数据被后台线程抢先处理。数据库迁移使用显式指定的独立 MySQL 测试库验证，不能指向游戏库。

`.github/workflows/verify.yml` 使用 Java 17、Node 22、MySQL 8.4 执行检查。构建产物、日志、编辑器缓存和环境密钥由 `.gitignore` 排除，不能重新提交 backend/target。

多人规模和浏览器完整交互仍需按部署环境进行验收与压测；批处理预算可通过 `game.tick-batch-size`、`game.tick-max-batches` 调整。
