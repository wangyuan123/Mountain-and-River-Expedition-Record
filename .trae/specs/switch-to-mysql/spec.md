# 切换数据库至 MySQL Spec

## Why
当前后端使用 PostgreSQL，用户本地已安装 MySQL（root 无密码），需要切换数据库以直接使用本地 MySQL 运行游戏，降低部署门槛。

## What Changes
- 将 `pom.xml` 中的 PostgreSQL 驱动替换为 MySQL 驱动（`mysql-connector-j`）
- 将 `application.yml`、`application-prod.yml` 的数据源 URL / 驱动 / 方言改为 MySQL
- 将 `application-test.yml` 的 H2 兼容模式从 `PostgreSQL` 改为 `MySQL`
- 将 3 个 Flyway 迁移脚本从 PostgreSQL 语法转换为 MySQL 语法
  - `BIGSERIAL` → `BIGINT AUTO_INCREMENT`
  - `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` → 存储过程或直接 ADD COLUMN
  - `CREATE INDEX IF NOT EXISTS` → 存储过程或直接 CREATE INDEX
- 将 `docker-compose.yml` 中的 PostgreSQL 容器替换为 MySQL 容器
- 更新 `start-local.sh`：检测本地 MySQL，自动建库，用 `mvn spring-boot:run` 启动后端，用 Python/Node 简易服务器托管前端

## Impact
- Affected code: `backend/pom.xml`、`backend/src/main/resources/application.yml`、`application-prod.yml`、`application-test.yml`、`db/migration/V1__init.sql`、`V2__game_tables.sql`、`V3__add_construction_slot.sql`、`docker-compose.yml`、`start-local.sh`

## ADDED Requirements

### Requirement: MySQL 数据源
系统 SHALL 使用 MySQL 作为主数据库，连接本地实例 `localhost:3306/wargame`，用户 `root`，无密码。

#### Scenario: 后端连接本地 MySQL
- **WHEN** 后端启动
- **THEN** 连接 `jdbc:mysql://localhost:3306/wargame?useSSL=false&serverTimezone=UTC`，用户名 root，密码为空

### Requirement: MySQL 兼容 Flyway 脚本
系统 SHALL 提供与 MySQL 兼容的 Flyway 迁移脚本。

#### Scenario: Flyway 执行迁移
- **WHEN** 后端首次启动
- **THEN** Flyway 执行 V1/V2/V3 迁移脚本，创建全部表和索引，无语法错误

### Requirement: 本地启动脚本
`start-local.sh` SHALL 检测本地 MySQL 并直接启动后端和前端，不依赖 Docker。

#### Scenario: 脚本运行
- **WHEN** 用户执行 `./start-local.sh`
- **THEN** 脚本检测 MySQL 是否运行，自动创建 `wargame` 数据库，执行 `mvn spring-boot:run` 启动后端，启动简易 HTTP 服务器托管前端

## MODIFIED Requirements

### Requirement: 数据库配置
**现有**: PostgreSQL（localhost:5432，postgres/postgres）
**迁移后**: MySQL（localhost:3306，root/无密码）

### Requirement: Docker Compose
**现有**: PostgreSQL 16 容器
**迁移后**: MySQL 8 容器（保留 Docker 部署选项）

### Requirement: 测试数据库
**现有**: H2 PostgreSQL 兼容模式
**迁移后**: H2 MySQL 兼容模式

## REMOVED Requirements

### Requirement: PostgreSQL 依赖
**Reason**: 不再使用 PostgreSQL
**Migration**: pom.xml 移除 postgresql 依赖，添加 mysql-connector-j
