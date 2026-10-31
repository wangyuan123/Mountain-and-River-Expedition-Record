# 后台凭据与统一请求防护

## 管理员登录与会话

后台每次请求按 JWT 的管理员 ID 查询数据库，再校验用户名、启用状态、当前角色及 `auth_version`。只认可 `ADMIN` 和 `SUPER_ADMIN`，权限取数据库当前值。旧的无版本凭据拒绝访问，升级后管理员需要重新登录，玩家凭据不受这项迁移影响。

`admin_users.auth_version` 同时作为 JPA 乐观锁。改密码、停用/启用、修改角色等实体更新都会递增版本。这样恢复账号或恢复原角色也不会复活旧会话。改密成功后须重新登录。直接通过 SQL 运维更新密码、状态或权限时，必须在同一条更新中执行 `auth_version = auth_version + 1`；不要绕过版本更新。

`POST /api/admin/auth/logout` 撤销当前管理员的**全部后台会话**，撤销结果持久化，不因重启丢失。客户端应调用此接口后清除本地凭据；只清 localStorage 无法通知服务端撤销会话。当前仓库提供后台 API，独立管理端应接入这个退出接口及改密后的重新登录提示。

后台 JWT 默认有效期为 2 小时，由 `WARGAME_ADMIN_JWT_EXPIRATION` 配置，允许 5 分钟至 8 小时（毫秒）。后台响应禁止缓存。不存在、密码不匹配、已禁用的账号统一返回 401 和 `AUTHENTICATION_FAILED`，不公开具体账号状态；不存在的账号也执行一次 BCrypt 校验。

新管理员密码为 12—64 个字符，且不超过 72 个 UTF-8 字节，不允许与旧密码相同。已有自定义密码不被部署自动重置。

## 停用历史固定口令与初始化

迁移 `V142__admin_credential_version.sql` 只停用 V65 植入的 `admin` 固定密码哈希，保留已经自行改密的管理员。登录及 JWT 校验也拒绝该已公开的固定哈希。不要修改已应用的 V65 迁移。

首次初始化，或替换被停用的历史固定凭据时，在私有环境配置中设置：

```dotenv
WARGAME_ADMIN_BOOTSTRAP_USERNAME=admin
WARGAME_ADMIN_BOOTSTRAP_PASSWORD=请替换为符合长度要求的独立随机密码
```

重启后台后，仅当用户名不存在，或对应账号仍使用历史固定哈希时才写入新密码。留空不会创建可用默认密码；已有正常管理员不会被这个配置重置，包括已被管理员主动禁用的自定义账号。初始化完成后从环境配置中删除初始化密码。不要提交真实口令。

## 统一限流

所有 `/api/**` 请求共享以下分钟预算，切换 URL、查询参数、城市或 JWT 不产生新配额：

| 维度 | 默认上限 | 环境变量 |
| --- | ---: | --- |
| 来源 IP 全部 API 请求 | 1200/分钟 | `WARGAME_RATE_IP_PER_MINUTE` |
| 来源 IP 写请求 | 300/分钟 | `WARGAME_RATE_IP_WRITES_PER_MINUTE` |
| 已登录账号全部 API 请求 | 600/分钟 | `WARGAME_RATE_ACCOUNT_PER_MINUTE` |
| 已登录账号写请求 | 120/分钟 | `WARGAME_RATE_ACCOUNT_WRITES_PER_MINUTE` |
| WebSocket 握手来源 IP | 60/分钟 | `WARGAME_RATE_WS_PER_IP_PER_MINUTE` |
| 后台登录来源 IP | 20/分钟 | `WARGAME_RATE_ADMIN_LOGIN_PER_IP_PER_MINUTE` |
| 后台登录账号 | 10/15 分钟 | `WARGAME_RATE_ADMIN_LOGIN_PER_ACCOUNT_PER_QUARTER_HOUR` |

IP 配额在 JWT 解析和数据库查询之前检查，包含未登录、错误凭据和业务失败请求。鉴权后再叠加账号配额，换 IP 也无法重置。所有配额要求大于零；登录限制统计全部尝试，成功后不清空配额。管理员登录账号键去首尾空格并统一大小写。管理员和玩家 ID 使用独立命名空间。

GET/HEAD 只计总请求预算，其余非 OPTIONS 方法额外计写预算。CORS 预检和静态资源不计入 API 预算。注册、玩家登录、验证码、聊天、论坛等既有业务限额继续叠加生效。

统一超限返回 HTTP 429、`code: RATE_LIMITED`、正文 `retryAfter` 秒数及 `Retry-After` 响应头；跨域客户端可读取该头。被拦截的写请求不执行游戏操作。当前沿用有容量上限的单进程固定窗口限流器，重启会重置限流窗口；多实例共享一个大区时须改用 Redis 等共享存储。凭据撤销版本独立持久化，不受限流窗口重置影响。

## 可信代理

注册、登录、邮箱、账号恢复、后台审计及统一限流共用 `ClientIpResolver`。默认只使用 TCP 对端地址，忽略转发头。通过 `WARGAME_TRUSTED_PROXIES` 显式指定受控网关 IP/CIDR，逗号分隔，支持 IPv4/IPv6，禁止主机名和 `/0`。旧变量 `WARGAME_MAIL_TRUSTED_PROXIES` 仅作兼容，建议迁移到新变量；新配置对所有来源判断生效。

只有 TCP 对端在白名单中，才解析 `X-Forwarded-For`，从右向左越过可信代理，遇到第一跳不可信地址停止，忽略它左侧可伪造的前缀。缺少 XFF 时兼容可信代理覆盖的 `X-Real-IP`。重复头、非 IP、过长或过多跳数的链回退 TCP 对端。数字 IP 统一规范化，不进行 DNS 查询；`Forwarded` 不作为限流或审计依据。

公网边界 Nginx 对 API 和 WebSocket 均覆盖 XFF、X-Real-IP，并清除 `Forwarded`。应用设置 `server.forward-headers-strategy: none`，防止容器提前改写 TCP 对端。不要另外启用无白名单的 ForwardedHeaderFilter/RemoteIpValve。

代理部署需填写网关**实际地址或仅包含受控网关的专用网段**，容器重建改址时同步配置；不能信任包含玩家来源的网段。未配置时多个玩家经过同一网关会共享该网关的 IP 配额。Docker 直接启动时后端端口仅绑定本机回环，部署脚本仍会移除后端公开端口。网关前还有 CDN/负载均衡时，应在最外层边界清洗来源头，逐跳配置可信代理后再保留真实代理链；当前仓库的单层网关配置始终以相邻 TCP 来源作为客户端。

## 验证

新增回归覆盖：可信/不可信代理与畸形链、IPv6 规范化、IP/账号/读写配额、跨 URL/IP/令牌绕过、固定窗口恢复、后台改密/退出/禁用恢复/权限变更/删除后的凭据拒绝、历史凭据拒绝、玩家越权和迁移保留自定义凭据。集成测试使用 H2 与合成账号，不修改游戏账号。

### 2026-10-30 验收记录

- 安全及账号相关定向回归：70 项全部通过，包括既有邮箱、注销、大区和游戏时钟回归。注册/游客超限现在也返回 429，避免被错误呈现为服务器维护。
- 部署与本地启动脚本：60 项全部通过。需要本机进程检查和临时端口的测试在沙箱外执行；不操作游戏账号。
- 真实 MySQL 9.7.1：仅在本轮新建的 `wargame_auth_acceptance_20261030_*` 独立库验证 V65 到 V142 的管理员表升级，确认历史固定凭据被停用、自定义凭据被保留。验收库已删除。未执行对现有游戏库的迁移或重启游戏服务。
- YAML 解析与 `git diff --check` 通过。当前环境没有 Docker/Nginx 可执行文件，未做实际网关重载验收。
- 前端全量最新一轮：1266 项中 1265 项通过，剩余 `map.test.cjs` 的带兵上限文案断言仍匹配“当前带兵上限”，现有界面已为“当前出征带兵上限”。该玩法相关文件属于本轮安全修复之外的工作区改动。
- 后端全量首轮：2178 项，3 个失败、3 个跳过。受本次 CORS 扩展影响的 `GameClockStartupTest` 已更新 `Retry-After` 断言并在上述定向回归通过；另外两项为 `UniversalSkillBookTest` 的互斥提示断言及 `WoundedServiceTest` 的旧守城兵力断言，涉及工作区同时存在的技能/守城变更，本轮不据此宣称全量回归通过。MySQL 全库迁移测试需要专用 `WARGAME_TEST_MYSQL_URL`，本轮真实库验收仅覆盖管理员迁移。

定向回归命令（`JAVA_TOOL_OPTIONS` 指定 UTF-8，避免本机旧 Maven 增量记录受中文路径编码影响）：

```bash
JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8 mvn -o -f backend/pom.xml -Dtest=AdminSecurityIntegrationTest,AdminCredentialMigrationTest,AdminAccountBootstrapTest,ClientIpResolverTest,RequestRateLimitsTest,RateLimiterRetentionTest,AdminSystemTest,AccountEmailIntegrationTest,AccountDeletionTest,ServerRegionTest,SecurityOriginsTest,GameClockStartupTest test
python3 -m unittest discover -s scripts/tests -p 'test_*.py'
```
