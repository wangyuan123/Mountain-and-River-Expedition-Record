# V1.0 操作手册

## 本地准备

安装 Java 17、Maven、MySQL 8、Python 3 和 Node.js 22。创建独立开发库：

```sh
mysql -u root -e "CREATE DATABASE IF NOT EXISTS wargame DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
```

复制 `.env.example` 为本地私有 `.env`，只在受信终端加载，不要把密码、JWT 密钥、SMTP 授权码或支付密钥写入 Git。

## 启动游戏

在项目根目录启动后端：

```sh
set -a
[ ! -f .env ] || . ./.env
set +a
mvn -f backend/pom.xml spring-boot:run -Dspring-boot.run.profiles=local
```

另开终端启动前端：

```sh
python3 -m http.server 8081 --directory frontend
```

浏览器访问 `http://localhost:8081`。后端默认使用 8080；`local` profile 仅监听回环地址，并提供 `DEMO-ADULT`、`DEMO-PARENT`、`DEMO-TEEN`、`DEMO-CHILD` 虚构测试身份。

## 核心操作路径

注册或登录后，按新手任务完成前进基地建设、资源生产、征兵、科技研究和第一次侦察；随后可从城市、地图和军情页面进入行军、采集、拦截、战斗、军团援防、邮件、好友、排行榜、军官和商城功能。页面中的余额、行军状态、战报和邮件状态以服务端响应为准。

## 验证命令

```sh
node --test frontend/tests/*.test.cjs
mvn -f backend/pom.xml test
```

后端测试默认使用 H2。执行 MySQL 迁移测试时，必须先创建独立、可丢弃的 MySQL 8 测试库，并通过 `WARGAME_TEST_MYSQL_URL`、`WARGAME_TEST_MYSQL_USER` 和 `WARGAME_TEST_MYSQL_PASSWORD` 指定，禁止指向实际游戏库。

## 部署提示

公网部署使用 `setup-server.sh` 准备 Ubuntu/Debian 环境，再在项目根目录执行 `deploy.sh --check` 和 `deploy.sh`。生产环境应保留原 `.env` 和数据库卷，使用备份后升级流程；禁止执行 `docker compose down -v`。部署前必须完成备案、HTTPS、正式实名/防沉迷和支付服务配置。

## V1.0 支付状态

V1.0 不包含真实支付渠道和生产订单系统。充值页面和相关接口保持关闭或预览状态，不能用本手册中的本地测试身份模拟真实付款，也不要在公网开启模拟充值。
