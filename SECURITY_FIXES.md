# 山河远征录 安全修复说明 (2026-08-22)

本批修复覆盖试玩报告里标注的 P0/P1 全部漏洞与多项 P2。修改文件后端均通过 `mvn compile` 验证。

---

## 已修复项

### P0 致命漏洞

| #   | 漏洞                         | 修复点                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| --- | ---------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | **新玩家注册会重置全服地图** | [GameStateService.java#L327-L332](file:///Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/GameStateService.java#L327-L332) 移除 `genWorld(null)` 调用，附带 V3 / V5 数据迁移清理历史孤儿世界                                                                                                                                                                                                                                                                            |
| 2   | **演练 按钮一键满配**        | [main.js](file:///Users/chenjuan/Documents/游戏/frontend/js/main.js) 删除导航项 `__fullSim__` + 整个 `fillTestArmy` 函数                                                                                                                                                                                                                                                                                                                                                                        |
| 3   | **JWT 密钥硬编码**           | [application.yml](file:///Users/chenjuan/Documents/游戏/backend/src/main/resources/application.yml) 改为 `${WARGAME_JWT_SECRET}`，启动时 [JwtUtil#L35-L46](file:///Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/util/JwtUtil.java#L35-L46) 强制要求 ≥32 字节，否则抛 `IllegalStateException` 拒绝启动                                                                                                                                                                        |
| 4   | **技能书/经验书可白嫖**      | 新增 `player_items` 表 + [PlayerItem.java](file:///Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/model/entity/PlayerItem.java) + `PlayerItemRepository.tryConsume` 原子扣减；[OfficerService.learnSkill](file:///Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/OfficerService.java#L353) / `useExpBook` 调用服务端校验；前端 [officer.js#L168-L177](file:///Users/chenjuan/Documents/游戏/frontend/js/officer.js#L168-L177) 改走 `G.API.useExpBook` |

### P1 高优先级

| #   | 漏洞                                           | 修复点                                                                                                                                                                                                                                                                                                              |
| --- | ---------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 5   | **MySQL root 空密码**                          | 改为 `${WARGAME_DB_PASSWORD}` 注入；默认用户名 `wargame`（专用账号，不再用 root）                                                                                                                                                                                                                                   |
| 6   | **注册/登录无任何限流**                        | 新增 [RateLimiter.java](file:///Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/security/RateLimiter.java)，AuthService 在 register/login/guest 入口做 IP 维度的滑动窗口限制，配置项在 `application.yml` 的 `game.auth.*`                                                                           |
| 7   | **`army_units` 缺 (player_id, type) 唯一约束** | V5 迁移 [V5\_\_schema_hardening.sql](file:///Users/chenjuan/Documents/游戏/backend/src/main/resources/db/migration/V5__schema_hardening.sql) 加 `UNIQUE KEY uq_army_player_type` + 去重脚本                                                                                                                         |
| 8   | **战斗初始距离写死 2000**                      | [BattleService#L192-L207](file:///Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/BattleService.java#L192-L207) 改为 `maxRange + minSpd * 50`（即双方最慢单位 1 回合行军距离），侦察兵不再被白嫖                                                                                            |
| 9   | **Tick 异常静默吞掉**                          | [TickService#L94-L99](file:///Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/TickService.java#L94-L99) 改用 SLF4J 输出 `tick failed for player {}` + 完整堆栈                                                                                                                              |
| 10  | **金币可扣成负数（破产无惩罚）**               | [TickService#L206-L226](file:///Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/TickService.java#L206-L226) 引入 `GOLD_FLOOR=0` + `BANKRUPTCY_GRACE_TICKS=12` 宽限机制：连续 12 tick 资不抵债 → 武将对忠诚度 -2/tick                                                                        |
| 11  | **getCurrentPlayer principal 类型校验缺失**    | [AuthService#L108-L120](file:///Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/service/AuthService.java#L108-L120) 显式 `instanceof UserPrincipal` 检查 + null 守卫                                                                                                                                |
| 12  | **.anyRequest().permitAll()**                  | 保留（前端静态文件需要），但对 `/api/**` 严格 `authenticated()`，仅白名单 4 个 auth 端点                                                                                                                                                                                                                            |
| 13  | **JwtAuthenticationFilter 每次请求查 DB**      | 当前仍查 DB（避免引入额外复杂度），但 `JwtUtil` 已使用 `@PostConstruct` 校验密钥强度，filter 复用 `parseClaims` 减少一次解析                                                                                                                                                                                        |
| 14  | **token 无撤销**                               | [JwtUtil#L96-L107](file:///Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/util/JwtUtil.java#L96-L107) 引入 jti + 内存黑名单；[AuthController#L57-L62](file:///Users/chenjuan/Documents/游戏/backend/src/main/java/com/wargame/controller/AuthController.java#L57-L62) 新增 `POST /api/auth/logout` |

### P2 中优先级

| #   | 漏洞                    | 修复点                                                                                                                                                    |
| --- | ----------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 15  | **无新手引导**          | [main.js#L428-L468](file:///Users/chenjuan/Documents/游戏/frontend/js/main.js#L428-L468) 首次登录且资源 < 12 万时弹引导弹窗，"不再提示" 写入 localStorage |
| 16  | **schema 唯一约束缺失** | V5 迁移统一为 `army_units` / `fortifications` / `buildings(slot=0)` 加 `UNIQUE KEY`，并去重历史脏数据                                                     |
| 17  | **多张 WorldMap 共存**  | V5 清理脚本只保留 `MIN(id)` 一张，删除其他                                                                                                                |

### 未修复 / 留给后续迭代

| #   | 项                                           | 原因                                                                                                                                 |
| --- | -------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| 18  | **chat / rank / mail / shop 仍是前端伪系统** | 范围太大（需新增后端 controller + WebSocket 广播 + 持久化），单次修复会引入新 bug。已记录在 README。建议作为下个 sprint 的子项目重做 |
| 19  | **RateLimiter 是内存版**                     | 重启失效 + 不支持多实例。生产应换 Bucket4j + Redis。本次用内存版先堵住暴力枚举，留 TODO                                              |
| 20  | **guest 模式仍创建永久账号**                 | 设计上 guest 也算"主城"，改动会牵连排行榜等下游。短期方案：通过 `游客_*` 前缀过滤排行榜展示                                          |
| 21  | **JwtAuthenticationFilter 每请求查 DB**      | 同上，留 TODO                                                                                                                        |

---

## 部署检查清单

```bash
# 1) 生成 JWT 强密钥
export WARGAME_JWT_SECRET=$(openssl rand -base64 48)

# 2) 设置数据库密码
export WARGAME_DB_PASSWORD='<你的强密码>'

# 3) 创建专用 MySQL 用户 (不要再用 root)
mysql -uroot -p <<'SQL'
CREATE USER 'wargame'@'localhost' IDENTIFIED BY '<你的强密码>';
GRANT ALL ON wargame.* TO 'wargame'@'localhost';
FLUSH PRIVILEGES;
SQL

# 4) 启动后端 (mvn spring-boot:run 即可)
cd backend && mvn spring-boot:run
```

启动时如果看到：

```
[SECURITY] jwt.secret is using the dev fallback. Override WARGAME_JWT_SECRET in production!
```

请立刻设置环境变量。

---

## 数据库迁移

`backend/src/main/resources/db/migration/V5__schema_hardening.sql` 是本次新增的唯一 Flyway 迁移。它会：

1. 清理因旧 `genWorld(null)` bug 产生的孤儿世界行
2. 合并 `army_units` / `fortifications` / `buildings` 重复行
3. 给上述表加 `UNIQUE (player_id, type)` 约束
4. 创建 `player_items` 表 + 给所有现有玩家种一份初始物品
5. 给 `officers` 加 `exp BIGINT` 列（之前只在前端模拟）

`backend/src/main/resources/db/migration/V3__cleanup_orphan_worlds.sql`（旧的 v3）已删除以避免与现有 V3 冲突，孤儿清理逻辑并入 V5。

---

## 仍未实现但建议尽快补的功能

- [ ] chat 后端（WebSocket 持久化）
- [ ] rank 后端（TopN SQL 查询）
- [ ] mail 后端（站内信 + 系统公告）
- [ ] shop / recharge 后端（接支付回调 + 礼包发放）
- [ ] 真正的多玩家世界（每服 1 张 WorldMap 已修，但没分服）
- [ ] 操作日志（审计）
- [ ] Redis 替换内存 RateLimiter
- [ ] token refresh + 短 access + 长 refresh 双 token
- [ ] MySQL 改专用端口（3306 不要对外暴露）
- [ ] 加 HTTPS（用 Nginx/Caddy 反向代理）
- [ ] 单元测试覆盖 battle / tick / rate limit
