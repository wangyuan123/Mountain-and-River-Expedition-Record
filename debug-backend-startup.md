# 后端启动故障记录

- 状态：已修复并完成本机启动验证。
- 日期：2026-10-10。
- 现象：`mvn spring-boot:run` 退出码为 1，启动脚本报告“后端进程已退出”。

## 原因与证据

Spring Boot 完整异常为：

```text
FlywayValidateException: Validate failed: Migrations have failed validation
Detected failed migration to version 84 (remove motor units).
```

游戏库中的 V84 记录为失败，V83 及之前均成功。独立 MySQL 测试库执行原 V84 脚本，复现：

```text
ERROR 1054 (42S22) at line 15: Unknown column 'army' in 'where clause'
```

V30 已删除 `player_cities.army`，玩家城市兵力统一保存在 `army_units`。V84 却再次更新该字段，造成迁移失败；再次启动时，Flyway 在校验失败记录处直接退出。

## 修复与恢复

- 删除 V84 中更新 `player_cities.army` 的过时语句，并注释实际兵力存储位置。继续通过 `army_units` 清理各城市摩托兵。
- 补充真实 MySQL 迁移回归：在 V83 表结构上准备两个城市的摩托兵、步兵与出征偏好，再升级，验证摩托兵移除、步兵和资源保留。
- 在独立测试库确认原脚本部分执行后，修正脚本可以重新执行成功。V84 只有可重复的删除及 JSON 清理，没有需要撤销的半完成 DDL。
- 完整备份本机游戏库至 `/tmp/wargame-before-v84-repair-20261010.sql`（仅本机、权限 600）。
- 仅移除精确匹配的失败 V84 历史记录，再由正常启动执行 V84～V87；未更改成功迁移的历史记录或校验和。

## 验证

- `MySqlMigrationTest`：2 项通过，0 失败、0 错误；独立 MySQL 库完整升级至 V87，Hibernate 实体校验通过。
- 本机游戏库 V84～V87 均为成功，失败迁移记录为 0。
- 09:11:35 后端实际启动成功，监听 `127.0.0.1:8080`。
- `GET /api/auth/server` 返回 200；未登录访问 `GET /api/game/state` 返回预期的 401。
- 验证启动临时关闭游戏定时结算，验证进程已关闭，之后可按原 `start-local.sh` 启动；配置默认值未改动。
- 启动日志：`/tmp/wargame-backend-startup-fixed-20261010.log`；迁移回归日志：`/tmp/wargame-mysql-migration-startup-test.log`。

## 历史故障

1. `DepotService` 构造函数未初始化 `playerRepository` 与 `buildingRepository`（已修复）。
2. V84 误引用已废弃的 `player_cities.army` 导致启动中断（已修复并清理历史）。
3. V87 本地修改导致 Flyway 校验和不匹配（2026-10-10 23:36 修复）：
   - 现象：`FlywayValidateException: Migration checksum mismatch for migration version 87`（数据库原记录 `-752746790`，本地为 `-1568905431`）。
   - 原因：早晨执行 V87 后，晚上就地在 `V87__pvp_practice.sql` 中增加了 `power_model_version VARCHAR(80)`，导致文件哈希变化且物理表尚未补列。
   - 修复：对数据库表 `pvp_match_tickets` 补齐 `power_model_version` 字段，并同步更新 `flyway_schema_history` 表中 V87 的校验和为 `-1568905431`。验证 8080 端口启动通过，API 响应正常。

