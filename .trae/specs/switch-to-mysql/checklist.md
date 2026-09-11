# Checklist

## Maven 依赖
- [x] pom.xml 中无 postgresql 依赖
- [x] pom.xml 中有 mysql-connector-j 依赖
- [x] flyway-core 依赖保留

## 应用配置
- [x] application.yml 数据源 URL 为 jdbc:mysql://localhost:3306/wargame
- [x] application.yml username 为 root，password 为空
- [x] application.yml driver-class-name 为 com.mysql.cj.jdbc.Driver
- [x] application.yml dialect 为 org.hibernate.dialect.MySQL8Dialect
- [x] application-prod.yml 数据源改为 MySQL
- [x] application-test.yml H2 模式为 MySQL

## Flyway 迁移脚本
- [x] V1__init.sql 无 BIGSERIAL，使用 BIGINT AUTO_INCREMENT
- [x] V2__game_tables.sql 无 BIGSERIAL
- [x] V2__game_tables.sql 无 ADD COLUMN IF NOT EXISTS（MySQL 不支持）
- [x] V2__game_tables.sql 无 CREATE INDEX IF NOT EXISTS（MySQL 不支持）
- [x] V3__add_construction_slot.sql 无 ADD COLUMN IF NOT EXISTS

## Docker Compose
- [x] db 服务镜像为 mysql:8
- [x] db 环境变量为 MYSQL_DATABASE/MYSQL_ROOT_PASSWORD
- [x] db 端口为 3306
- [x] backend 环境变量为 MySQL 连接串
- [x] healthcheck 使用 mysqladmin ping

## 启动脚本
- [x] start-local.sh 检测本地 MySQL 而非 Docker
- [x] start-local.sh 自动创建 wargame 数据库
- [x] start-local.sh 使用 mvn spring-boot:run 启动后端
- [x] start-local.sh 启动简易 HTTP 服务器托管前端

## 前端跨端口适配
- [x] api-client.js 自动检测跨端口，指向后端 8080
- [x] ws-client.js 自动检测跨端口，指向后端 8080
