# 山河远征录 · 文字战争策略游戏

二战题材文字策略网页游戏。当前已接入多人账号、共享世界、军团、邮件和实时结算；核心游戏状态由后端和 MySQL 管理。

当前采用游戏名称“山河远征录”，商标相同及近似检索尚未完成。名称初筛、品牌展示调整和待核查事项见 [名称与展示审查记录](docs/BRAND_REVIEW.md)。

## 故事背景

1941 年太平洋战争爆发后，日军迅速席卷东南亚，战线直指缅甸，并企图切断当时中国唯一的国际援华通道——滇缅公路，迫使中国停止抵抗。为了守住这条连接中国与世界的生命线，中英两国于同年 12 月 23 日签署《中英共同防御滇缅路协定》。随后，中国紧急编组由第 5、第 6、第 66 军组成的远征军，总计十万余人，远赴缅甸支援英军对日作战，将战火阻挡在国门之外。

你将穿越到这段烽火岁月，在滇缅公路沿线建立属于自己的前进基地。这里没有凭空出现的英雄，也没有能够独自赢下战争的城池：你需要建设城市、发展资源、训练部队、培养军官，并在有限的时间与补给中做出每一次战略选择。

随着战局推进，来自不同地区的玩家也将在同一片共享世界中集结。他们各自组建军团，互通情报、调配物资、协同出征，共同守护援华通道，抵抗侵略者的推进。游戏以真实历史为时代背景，在尊重历史记忆的基础上进行架空续写：玩家的每一座城市、每一支部队和每一次军团协作，都会成为这场共同御敌之战的一部分。

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

一键全量验证脚本（对标 CI）：

```sh
./scripts/verify.sh
```

## 代码提交校验 (Git Hooks)

工程已集成 Git Hooks 提交校验机制，防止语法错误或测试失败的代码被意外提交：

- **自动生效**：运行 `./start-local.sh` 或 `./scripts/install-hooks.sh` 会自动启用 Git 钩子（`core.hooksPath = .githooks`）。
- **提交前校验 (pre-commit)**：
  - 后端代码变更：自动执行 `test-compile` 编译与类型/注解校验，并执行单元测试。
  - 前端代码变更：自动执行 `frontend/tests/*.test.cjs` 回归测试。
  - 快速提交：若仅需执行快速编译校验跳过耗时单元测试，可使用 `FAST_COMMIT=1 git commit -m "..."`。
- **推送前校验 (pre-push)**：自动执行前后端全量测试，确保与 CI 保持一致。

## 主要入口

| 职责                           | 文件                                              |
| ------------------------------ | ------------------------------------------------- |
| 定时调度 / 单玩家结算          | `TickScheduler.java` / `TickService.java`         |
| 状态聚合 / 地图视野            | `GameStateService.java` / `WorldViewService.java` |
| 行军生命周期 / 目标数据        | `MarchService.java` / `MarchTargetService.java`   |
| 首页展示 / 资料操作 / 启动登录 | `main-view.js` / `player-profile.js` / `main.js`  |
| 地图请求与过期响应处理         | `world-view.js`                                   |
| 请求鉴权、重试与错误提示       | `api-client.js`                                   |

项目设计见 [docs/DESIGN.md](docs/DESIGN.md)，维护约定见 [HANDOVER.md](HANDOVER.md)。
