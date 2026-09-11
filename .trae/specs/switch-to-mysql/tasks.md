# Tasks

- [x] Task 1: 替换 Maven 依赖
  - [x] SubTask 1.1: pom.xml 移除 postgresql 依赖，添加 mysql-connector-j
  - [x] SubTask 1.2: 确认 flyway-core 保留（Flyway 9.x 内置 MySQL 支持）

- [x] Task 2: 修改应用配置
  - [x] SubTask 2.1: application.yml 数据源改为 MySQL（localhost:3306, root, 无密码, MySQL8Dialect）
  - [x] SubTask 2.2: application-prod.yml 数据源改为 MySQL
  - [x] SubTask 2.3: application-test.yml H2 模式改为 MySQL

- [x] Task 3: 转换 Flyway 迁移脚本为 MySQL 语法
  - [x] SubTask 3.1: V1__init.sql：BIGSERIAL -> BIGINT AUTO_INCREMENT
  - [x] SubTask 3.2: V2__game_tables.sql：BIGSERIAL -> BIGINT AUTO_INCREMENT，ALTER ADD COLUMN IF NOT EXISTS 改为兼容写法，CREATE INDEX IF NOT EXISTS 改为兼容写法
  - [x] SubTask 3.3: V3__add_construction_slot.sql：ALTER ADD COLUMN IF NOT EXISTS 改为兼容写法

- [x] Task 4: 更新 docker-compose.yml
  - [x] SubTask 4.1: PostgreSQL 容器替换为 MySQL 8 容器
  - [x] SubTask 4.2: 更新 backend 环境变量为 MySQL 连接串
  - [x] SubTask 4.3: 更新 healthcheck 为 mysqladmin ping

- [x] Task 5: 更新 start-local.sh
  - [x] SubTask 5.1: 移除 Docker 依赖，改为检测本地 MySQL
  - [x] SubTask 5.2: 自动创建 wargame 数据库
  - [x] SubTask 5.3: 用 mvn spring-boot:run 启动后端
  - [x] SubTask 5.4: 用 Python/Node 简易服务器托管前端静态文件

# Task Dependencies
- [Task 2] depends on [Task 1]
- [Task 3] depends on [Task 1]
- [Task 4] depends on [Task 2, Task 3]
- [Task 5] depends on [Task 2]
