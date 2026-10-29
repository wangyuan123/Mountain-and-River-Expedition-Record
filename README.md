# 山河远征录 · 文字战争策略游戏

近代战争题材文字策略网页游戏。当前已接入多人账号、共享世界、军团、邮件和实时结算；核心游戏状态由后端和 MySQL 管理。

当前采用游戏名称“山河远征录”，商标相同及近似检索尚未完成。名称初筛、品牌展示调整和待核查事项见 [名称与展示审查记录](docs/BRAND_REVIEW.md)。

## 故事背景

1941 年太平洋战争爆发后，日军迅速席卷东南亚，战线直指缅甸，并企图切断当时中国唯一的国际援华通道——滇缅公路，迫使中国停止抵抗。为了守住这条连接中国与世界的生命线，中英两国于同年 12 月 23 日签署《中英共同防御滇缅路协定》。随后，中国紧急编组由第 5、第 6、第 66 军组成的远征军，总计十万余人，远赴缅甸支援英军对日作战，将战火阻挡在国门之外。

你将穿越到这段烽火岁月，在滇缅公路沿线建立属于自己的前进基地。这里没有凭空出现的英雄，也没有能够独自赢下战争的城池：你需要建设城市、发展资源、训练部队、培养军官，并在有限的时间与补给中做出每一次战略选择。

随着战局推进，来自不同地区的玩家也将在同一片共享世界中集结。他们各自组建军团，互通情报、调配物资、协同出征，共同守护援华通道，抵抗侵略者的推进。游戏以真实历史为时代背景，在尊重历史记忆的基础上进行架空续写：玩家的每一座城市、每一支部队和每一次军团协作，都会成为这场共同御敌之战的一部分。

## 技术与模块

- 后端：Java 17、Spring Boot 3.2.5、Spring Security/JWT、JPA、Flyway。
- 前端：原生 HTML/CSS/JavaScript，按功能使用 `Game` 命名空间组织，无打包依赖。
- 前端静态枚举与固定配置集中在 `frontend/js/constants.js`，新增页面常量时优先放入 `Game.Constants`，业务模块只引用配置，不重复维护同一份字典。
- 数据库：MySQL 8+；`backend/src/main/resources/db/migration` 是数据库版本的唯一依据。
- 通信：HTTP API + WebSocket；部署使用 Docker Compose + Nginx。
- 玩法：城建、资源与税收、征兵、科技、军官与装备、行军战斗、野地、军团、任务、邮件、排行榜、商城。
- [新手保护](docs/NEWBIE_PROTECTION.md)：首次进入自动保护 7 天，声望不足 10000 可延长至最多 14 天；主动参与玩家战争须确认永久退出。
- 实名与防沉迷暂时关闭：保留准入、时段、时长、监护和休息页实现，待产品完善后恢复。当前注册、登录可直接进入游戏。
- 当前真实支付未接通，防沉迷启用时同时关闭模拟充值。正式实名适配尚待接入，重新启用防沉迷后，未实名账号不能进入游戏。

## 本地启动

安装 JDK 17、Maven、MySQL 和 Python 3。默认数据库地址为 `localhost:3306/wargame`，用户 `root`，密码为空；可通过 `.env.example` 列出的环境变量覆盖。手动启动前需将变量导出到进程环境，Spring 不会自动读取根目录 `.env`。

```sh
mysql -u root -e "CREATE DATABASE IF NOT EXISTS wargame DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
# 已配置根目录 .env 时，在可信终端加载配置（不要开启 set -x）。
set -a
[ ! -f .env ] || . ./.env
set +a
mvn -f backend/pom.xml spring-boot:run -Dspring-boot.run.profiles=local
```

另开终端启动前端：

```sh
python3 -m http.server 8081 --directory frontend
```

访问 <http://localhost:8081>，后端端口为 8080。`.mvn/jvm.config` 指定 UTF-8，避免中文项目路径在不同终端编码下导致 Maven 读取旧构建记录失败。

`local` 仅监听回环地址，并提供虚构身份 `DEMO-ADULT`、`DEMO-PARENT`、`DEMO-TEEN`、`DEMO-CHILD`。当前默认关闭防沉迷，无需提交测试凭据。需要验收原有控制时，设置 `WARGAME_COMPLIANCE_ENABLED=true`，注册后在实名页提交测试凭据。仅使用独立开发库；不要向公网代理该配置。未指定 `local` 时不会接受测试身份。配置、接口与正式接入边界见 [防沉迷实施说明](docs/ANTI_ADDICTION_IMPLEMENTATION.md)。

`SPRING_PROFILES_ACTIVE=local ./start-local.sh` 可自动准备本机依赖并启动本机测试服务；该脚本会读取并导出根目录受信任的 `.env` 配置（文件值覆盖同名环境变量），安装缺失依赖，并结束占用 8080/8081 的旧进程，适合专用开发环境。SMTP 授权码及邮箱密钥的配置见 [QQ 邮箱实施说明](docs/QQ_EMAIL_ACCOUNT_RECOVERY_IMPLEMENTATION.md)。

## 服务器与 Docker 一键部署

工程提供了一键安全部署脚本 [`deploy.sh`](deploy.sh)，并遵循 [服务器一键部署前置安全规范](docs/DEPLOYMENT_SECURITY_SPEC.md)。详细的服务器购买、代码上传、一键部署与本地后台管理连接实战教程可参考 [《服务器部署与本地后台管理连接指引》](docs/SERVER_DEPLOYMENT_AND_ADMIN_GUIDE.md)。

### 两个脚本的使用时机

| 脚本 | 负责什么 | 什么时候使用 | 执行位置 |
| --- | --- | --- | --- |
| [`setup-server.sh`](setup-server.sh) | 安装 Python、Git、Docker Engine 和 Compose V2，检查服务器环境 | 新服务器通常执行一次；后续需要检查环境时使用 `--check` | SSH 登录服务器后执行，可单独上传，不要求在项目根目录 |
| [`deploy.sh`](deploy.sh) | 准备游戏凭证、构建启动游戏、升级前备份和部署验收 | 首次部署，以及每次更新游戏 | 完整项目上传后，在服务器项目根目录执行 |

环境初始化支持 Ubuntu 22.04 / Debian 12，已有冲突安装会停止，不自动卸载。小内存服务器可为 `setup-server.sh` 追加 `--swap-gb 2`；默认不创建 Swap。

游戏部署入口 `deploy.sh` 依赖 Python 3.9+、Docker Engine 和 Docker Compose V2，不自动安装系统软件。首次部署会生成私有凭证；已有数据卷时必须保留原 `.env`，缺失或弱凭证会阻止部署，不会自动轮换数据库密码或邮箱密钥。

脚本固定解析根目录的 Compose 配置，在临时部署配置中关闭数据库与后端宿主机端口；不改写原 Compose 文件。升级前备份数据库、`.env` 和头像到不入库的 `backups/deploy/`，再替换后端并重建 Nginx。数据库认证、实际端口绑定、网关 API 与首页验收通过后才报告成功。

### 首次部署顺序

先把 `setup-server.sh` 单独上传到服务器 `/tmp/`。SSH 登录后执行环境初始化：

```sh
sudo bash /tmp/setup-server.sh
```

环境准备完成后，上传或克隆完整项目。如果本机 `.env` 已填好邮箱，可在**本机项目根目录**一键导出私有配置包，免去服务器上重复填写：

```sh
python3 scripts/export-mail-config.py
scp .private-deploy/mail-config.tar.gz ubuntu@你的服务器公网IP:/tmp/
```

包中仅包含 SMTP 设置，不包含本机数据库密码、JWT 或邮箱加密密钥；包包含授权码，不能入库或公开分享。然后在**服务器项目根目录**解压并部署（下面路径按实际情况替换）：

```sh
cd /opt/Strategies-of-the-Flaming-Plains
chmod +x deploy.sh
tar -xzf /tmp/mail-config.tar.gz
sudo ./deploy.sh --init-env
sudo ./deploy.sh --check
sudo ./deploy.sh
```

`--init-env` 会自动把 `.deploy-mail.env` 中的 SMTP 设置写入 `.env`，独立生成服务器凭证；也可以解压后直接运行 `sudo ./deploy.sh`，一次完成导入与部署。已有完整邮箱配置保持原样，配置冲突时停止。如果没有导入包，仍可执行 `sudo nano .env` 手动填写后部署；缺少发信配置时注册与邮箱找回不可用。

确认 `.env` 已导入后，可删除服务器上的待导入文件与传输包：

```sh
sudo rm -f .deploy-mail.env /tmp/mail-config.tar.gz
```

即：**安装服务器环境 → 上传完整项目和邮箱配置包 → 自动准备 `.env` → 预检 → 部署游戏**。`setup-server.sh` 本身不会部署游戏。

### 后续更新顺序

保留原 `.env`、数据卷及生产网关配置，更新源码后，在服务器项目根目录执行：

```sh
sudo ./deploy.sh --check
sudo ./deploy.sh
```

通常无需重新安装环境，也无需再次执行 `--init-env`。如需单独检查环境，可在项目根目录执行 `sudo bash ./setup-server.sh --check`。

目录改名或原来使用过自定义 Compose 项目名时，预检和部署都要带上同一个原项目名，例如 `sudo ./deploy.sh --project-name <原项目名> --check` 和 `sudo ./deploy.sh --project-name <原项目名>`，避免连接到新数据卷。命令中的 `<原项目名>` 需替换后再执行。旧 `./scripts/security-preflight.py` 入口等价于只读的 `./deploy.sh --check`。

升级会短暂停服，不自动回滚数据库。生产启动请始终使用部署脚本；直接执行 `docker compose up` 会使用源码中的开发端口映射，绕过部署配置隔离。脚本不会配置 HTTPS、修改云安全组，也不会发送测试邮件。

> **安全红线**：日常维护升级请使用 `./deploy.sh` 或 `docker compose down`，**严禁执行 `docker compose down -v`**（带 `-v` 会删除 `mysqldata` 数据库存储卷造成数据丢失）。

## 验证

### 自定义头像存储与清理

- 图片保存为服务器文件：默认 `frontend/img/avatars/custom/`。从仓库根目录或 `backend/` 启动均指向同一前端目录；可用 `wargame.avatar-upload-dir`（环境变量 `WARGAME_AVATAR_UPLOAD_DIR`）覆盖。Docker Compose 将此目录挂载给后端并由 Nginx 提供访问，备份时需同时备份数据库和该目录。
- 数据库 `players.custom_avatar` 只保存当前头像的路径或外链，上传的 Base64 仅用于传输，不保存图片正文或历史版本。单张图片上限为 1MB。
- 同一玩家的上传按数据库行锁串行处理。新文件写入并成功提交路径后，删除不再被引用的旧文件；事务回滚则删除新文件并保留原头像。文件名使用独立版本，避免同名覆盖导致缓存继续显示旧图。切换外链或恢复预设也会清理原上传文件。
- 后台每小时检查一次，清理超过 24 小时且没有有效玩家引用的受管文件；启动后首轮延迟 60 秒。覆盖旧版时间戳头像、异常退出残留和已完成注销账号的文件，跳过预设图片、符号链接及仍在使用的头像。删除失败写日志并在下轮重试。该任务遵循 `game.scheduling.enabled`，周期可通过 `game.avatar.cleanup-interval-ms` 配置。
- 正常情况下每个使用本地头像的玩家保留一份文件；替换过程中会短暂存在新旧两份，异常残留由定时任务回收。并发请求的短期内存、网络和磁盘吞吐仍取决于请求量，这套机制用于限制长期文件累积。

### 自动化测试

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

## 安全来源配置

生产环境默认只允许同源 API 和 WebSocket。跨域客户端需设置 `WARGAME_ALLOWED_ORIGINS`，如 `https://game.example.com,https://forum.example.com`，必须包含协议和非默认端口，不含路径、末尾斜杠或通配符。HTTP 与 WebSocket 共用白名单，修改后重启后端。本地默认允许 localhost / 127.0.0.1 的 8081、5174 端口；局域网调试需显式加入前端来源。独立论坛若需要跨域访问现有 API，应把其来源加入白名单，论坛实现本次不修改。

API 使用显式 Authorization Bearer Token，不接受 Cookie 登录凭据，因此保持无状态并关闭 CSRF token 校验；将来引入 Cookie 登录时必须重新设计 CSRF 防护。CORS 不允许跨域 Cookie 凭据。

游戏首页的 CSP meta 与 Nginx 响应头限制脚本来源、插件、基址和表单提交；Nginx 另外限制连接来源与页面嵌入。现有大量内联事件仍需 `unsafe-inline`，Pixi 7 WebGL 着色器生成仍需 `unsafe-eval`，因此这是基础加固，不能替代所有用户文本的输出转义。严格禁止内联脚本和动态执行需要先迁移内联事件并更换兼容严格 CSP 的渲染器；本次不修改论坛页面或其 Token 传递方式。部署后重载 Nginx 并刷新页面。

## 玩家聊天关键词过滤

世界聊天（含首页预览）、军团聊天、私聊（含会话预览）在服务端入库和读取历史时统一遮盖命中词，原文分隔符保留，命中字符替换为 `*`。词库位于 `backend/src/main/resources/chat/chat-sensitive-words.txt`，政治类沿用 `political-sensitive-words.txt`；每行一个明确词条，更新后重新构建并重启后端，无需修改数据库。历史玩家消息读取时过滤，不批量改写历史数据。

匹配使用启动时构建的 Aho-Corasick Trie 自动机，支持重叠词、失败转移、大小写混写、全角字符及插入空格/标点/emoji/不可见字符。谐音采用已列出的明确变体，不声称能自动识别全部谐音或语义违规；政治不当称呼、民族/宗教歧视和低俗露骨内容等概括类别需补充具体词条才能覆盖。短英文缩写按子串匹配，“私聊、辅助、弹药”等正常游戏词汇也按指定列表遮盖。系统生成通知和严格 UUID 战报凭证保留。过滤不替代 XSS 输出转义；论坛本次仍不处理。
