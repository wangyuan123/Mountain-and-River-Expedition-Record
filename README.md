# 山河远征 - 初步框架实现

二战题材文字策略网页游戏。当前已接入多人账号、共享世界、军团、邮件和实时结算；核心游戏状态由后端和 MySQL 管理。

## 技术与模块

- 后端：Java 17、Spring Boot 3.2.5、Spring Security/JWT、JPA、Flyway。
- 前端：原生 HTML/CSS/JavaScript，按功能使用 `Game` 命名空间组织，无打包依赖。
- 数据库：MySQL 8+；`backend/src/main/resources/db/migration` 是数据库版本的唯一依据。
- 通信：HTTP API + WebSocket；部署使用 Docker Compose + Nginx。
- 玩法：城建、资源与税收、征兵、科技、军官与装备、行军战斗、野地、军团、任务、邮件、排行榜、商城。
- 模拟充值仍对登录玩家开放，当前没有接入实际支付。

## 本地启动

安装 JDK 17、Maven、MySQL 和 Python 3。默认数据库地址为 `localhost:3306/wargame`，用户 `root`，密码为空；可通过 `.env.example` 列出的环境变量覆盖。手动启动前需将变量导出到进程环境，Spring 不会自动读取根目录 `.env`。

```sh
mysql -u root -e "CREATE DATABASE IF NOT EXISTS wargame DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
mvn -f backend/pom.xml spring-boot:run
```

另开终端启动前端：

```sh
python3 -m http.server 8081 --directory frontend
```

访问 <http://localhost:8081>，后端端口为 8080。`.mvn/jvm.config` 指定 UTF-8，避免中文项目路径在不同终端编码下导致 Maven 读取旧构建记录失败。

`./start-local.sh` 可自动准备本机依赖并启动服务；该脚本会安装缺失依赖，并结束占用 8080/8081 的旧进程，适合专用开发环境。

## Docker 启动

复制 `.env.example` 为 `.env`，填写数据库密码和随机 JWT 密钥后：

```sh
docker compose up -d --build
```

Compose 已启用 `prod` 配置，访问 <http://localhost>。公网部署应按实际环境配置 HTTPS 和端口开放范围。

## 验证

建议使用 Node.js 22；前端测试无需安装 npm 包。

```sh
node --test frontend/tests/*.test.cjs
mvn -f backend/pom.xml test
```

普通后端测试使用 H2，并关闭后台调度。MySQL 迁移测试只在显式设置测试库地址时运行：

```sh
WARGAME_TEST_MYSQL_URL='jdbc:mysql://127.0.0.1:3306/wargame_ci?useSSL=false&allowPublicKeyRetrieval=true' \
WARGAME_TEST_MYSQL_USER=root \
WARGAME_TEST_MYSQL_PASSWORD=test-password \
mvn -f backend/pom.xml -Dtest=MySqlMigrationTest test
```

必须预先创建独立、可丢弃的测试数据库，不能指向游戏库。测试先建立 V26 结构并写入样例余额，再执行新迁移，并由 Hibernate 验证结构。CI 使用 MySQL 8.4 自动执行前后端测试与迁移验证。

## 主要入口

| 职责 | 文件 |
|---|---|
| 定时调度 / 单玩家结算 | `TickScheduler.java` / `TickService.java` |
| 状态聚合 / 地图视野 | `GameStateService.java` / `WorldViewService.java` |
| 行军生命周期 / 目标数据 | `MarchService.java` / `MarchTargetService.java` |
| 首页展示 / 资料操作 / 启动登录 | `main-view.js` / `player-profile.js` / `main.js` |
| 地图请求与过期响应处理 | `world-view.js` |
| 请求鉴权、重试与错误提示 | `api-client.js` |

项目设计见 [docs/DESIGN.md](docs/DESIGN.md)，维护约定见 [HANDOVER.md](HANDOVER.md)。
