# 服务器大区设计与部署

## 当前方案

- 默认大区为 `jiangsu-1`（江苏一区）。当前数据库及其已有账号、世界、城市、军团、聊天、邮件、排行榜和交易数据全部留在此区，不进行数据复制或表结构迁移。
- 一个大区对应一个独立后端实例和一个独立数据库。实例配置 `WARGAME_SERVER_ID`、`WARGAME_SERVER_NAME`、`WARGAME_DB_URL` 和独立的 `WARGAME_JWT_SECRET`。`game_server_identity` 在启动时锁定数据库所属大区；错误实例不能启动。定时任务、WebSocket 广播及全局查询只访问该实例的数据库。
- 前端登录和注册共用大区选择器。`frontend/js/servers.js` 是公开大区目录；江苏一区仍使用 `/api` 和 `/ws`，新增区使用同源网关路径。切区会注销当前浏览器会话并使在途请求失效。
- HTTP 请求带 `X-Game-Server`，注册、登录及注销状态查询的请求体也带 `serverId`。后端实例拒绝错误大区；JWT 包含 `serverId`，HTTP 与 WebSocket 鉴权均验证。历史无大区声明的 JWT 只在江苏一区过渡有效。
- 当前没有跨区账号、跨区迁城或跨区邮件。相同用户名可以在不同数据库分别注册，进度和资产互不共享；玩家登录时需要选对大区。

## 新增大区

以下以 `jiangsu-2`（江苏二区）为例。先部署并验证实例，再公开选项。

1. 新建空数据库和独立后端实例，设置 `WARGAME_SERVER_ID=jiangsu-2`、`WARGAME_SERVER_NAME=江苏二区`、仅指向新区数据库的 `WARGAME_DB_URL`，并使用新区独有的 JWT 密钥。应用启动时 Flyway 初始化表，数据库归属锁定后 `WorldBootstrap` 在空库生成新区世界。不要把江苏一区的玩家数据库复制进新区。
2. 在同一前端域名的网关加入以下路由，并沿用现有代理的转发头、超时及 WebSocket Upgrade 设置。路径的结尾斜杠用于把前缀替换为后端实际的 `/api/`、`/ws/`：

   ```nginx
   location /regions/jiangsu-2/api/ {
       proxy_pass http://backend_jiangsu_2:8080/api/;
       proxy_set_header Host $host;
       proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
       proxy_set_header X-Forwarded-Proto $scheme;
   }

   location /regions/jiangsu-2/ws/ {
       proxy_pass http://backend_jiangsu_2:8080/ws/;
       proxy_http_version 1.1;
       proxy_set_header Upgrade $http_upgrade;
       proxy_set_header Connection "upgrade";
       proxy_set_header Host $host;
       proxy_read_timeout 86400;
   }
   ```

3. 用 `X-Game-Server: jiangsu-2` 请求 `/regions/jiangsu-2/api/auth/server`，确认返回 `jiangsu-2`；错误的大区头应被拒绝。验证新库里只有新区世界、江苏一区现有数据未变。
4. 再把 `{ id: 'jiangsu-2', name: '江苏二区', apiBase: '/regions/jiangsu-2/api', wsBase: '/regions/jiangsu-2/ws' }` 加入 `frontend/js/servers.js` 的 `regions` 数组，更新 `index.html` 的 `servers.js` 版本号并发布前端。
5. 用同名测试账号分别在两区注册，检查城市、军团、聊天、交易及地图互不可见；检查 HTTP 和 WebSocket 的跨区令牌均被拒绝。

一个物理 MySQL 实例可以托管多个数据库，但每个大区必须使用不同的数据库和独立账号权限。不要让两个大区实例连接同一库；当前业务查询默认只按本区玩家和首个 `world_map` 工作，共库会串服。
