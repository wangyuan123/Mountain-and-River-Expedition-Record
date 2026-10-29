# 山河远征录：从购买服务器到游戏上线

本文适合第一次部署本项目的人。默认部署一个“公测一区”，使用 Ubuntu 22.04、Docker 和项目根目录的 `deploy.sh`。Debian 12 也支持，但安装 Docker 时要选 Debian 的官方说明。

**先分清两台电脑：**“本机”指你现在使用的 Mac / Windows 电脑；“服务器”指购买的云主机。代码、数据库和游戏运行在服务器上；本地管理端可以继续在本机运行。

下面先完成公网 IP 访问，再配置域名与 HTTPS。已有线上数据的服务器请先看第九节，不要按首次部署重新创建配置。

## 一、购买什么服务器

在阿里云、腾讯云、华为云等平台，选择“轻量应用服务器”或“云服务器”。购买时按下表选择：

| 项目 | 建议 | 原因 |
| --- | --- | --- |
| 操作系统 | Ubuntu 22.04 LTS，纯净系统镜像 | 与本文安装命令一致；不需要预装宝塔、Nginx 或 MySQL |
| CPU / 内存 | 建议从 2 核 4GB 起步 | 服务器要同时运行数据库、Java 服务，还要编译镜像；2GB 可试运行但余量较小 |
| 系统盘 | 建议 50GB 或更多 | 游戏镜像、数据库和历次备份会占用空间 |
| 公网网络 | 需要公网 IPv4；带宽先按小规模测试购买 | 玩家需要从互联网连接服务器，后续按流量和延迟扩容 |
| 购买地区 | 尽量靠近主要玩家 | 影响登录和操作延迟 |

这些是起步建议，不是支持人数承诺。上线后应根据 CPU、内存、磁盘和实际访问量调整。中国大陆公网网站通常还涉及域名备案，请按云平台要求办理；买服务器本身不等于完成备案。

购买完成后记下：服务器公网 IP、SSH 用户名、SSH 密码或密钥。本文路径统一使用 `/opt/Strategies-of-the-Flaming-Plains`。

## 二、设置安全组，再连接服务器

### 1. 云控制台放行端口

在实例的“防火墙”或“安全组”页面设置入站规则：

| 端口 | 来源 | 用途 |
| --- | --- | --- |
| TCP 22 | 尽量只允许你当前的公网 IP | SSH 登录服务器 |
| TCP 80 | 玩家来源；公网游戏通常允许全部来源 | 初次 HTTP 访问、申请证书 |
| TCP 443 | 玩家来源；公网游戏通常允许全部来源 | 后续 HTTPS 访问 |
| TCP 3306、8080 | 不要添加公网允许规则 | 数据库和后端应只在容器网络内通信 |

若主机自身还有防火墙，也要核对这些规则。先确保 SSH 22 可用，再调整防火墙，避免把自己锁在服务器外。Docker 的端口发布不完全受普通 UFW 规则控制，本项目通过部署脚本移除数据库与后端的宿主机端口映射。

### 2. 在本机连接

打开 Mac“终端”或 Windows PowerShell：

```bash
ssh ubuntu@你的服务器公网IP
```

`ubuntu` 是示例用户名；云平台给的是 `root`、`debian` 等账号时，请对应替换。首次连接核对服务器指纹后确认，按提示输入密码。使用密钥时可改为 `ssh -i /你的密钥路径 ubuntu@你的服务器公网IP`。

**接下来第三节的命令全部在 SSH 登录后的服务器终端执行。**

## 三、一键安装运行环境

这一步由独立脚本 `setup-server.sh` 完成。它可以在上传完整项目之前运行，**不要求位于项目根目录**；但你需要先把这个脚本文件传到服务器。脚本仅支持 Ubuntu 22.04 / Debian 12 的 amd64、arm64 服务器。

### 1. 本机把脚本上传到服务器

在**本机终端**进入项目根目录，执行下面命令。若正在 SSH 会话中，请另开一个本机终端标签页：

```bash
scp setup-server.sh ubuntu@你的服务器公网IP:/tmp/setup-server.sh
```

用户名按云平台实际账号替换。这一步只上传环境脚本，不需要先安装 Git，也不携带 `.env` 或邮箱授权码。

### 2. SSH 登录后，一条命令安装

在**服务器终端**执行：

```bash
sudo bash /tmp/setup-server.sh
```

脚本会自动完成：

- 检查操作系统、CPU 架构和 systemd。
- 安装 Python 3、Git、Nano、curl、系统证书及必要工具。
- 在需要安装 Docker 时，按 Ubuntu / Debian 使用对应的 Docker 官方软件源，安装 Docker Engine、Buildx 和 Compose V2。
- 设置 Docker 开机启动并启动服务；已经运行的 Docker 不会被脚本主动重启。
- 验证 Python 版本、Docker 服务、Compose，显示内存、磁盘和 Swap 状态。

出现“环境配置完成”后，即可进入第四节上传完整项目。游戏所需 Java、Maven 和 MySQL 随容器提供，服务器不需要单独安装；本地管理端使用的 Node.js 也不需要安装在服务器上。

如果完整项目已经在服务器，进入项目目录执行 `sudo bash ./setup-server.sh` 也可以，效果相同。使用 root 账号时可省略 sudo；不要在本机 Mac 或 Windows 上直接运行。

### 3. 小内存服务器可同时配置 Swap

**Swap 是借用一部分硬盘作为内存的补充。** 2GB 内存及以下实例可以使用下面这一条命令，安装环境时同时创建 2GB Swap：

```bash
sudo bash /tmp/setup-server.sh --swap-gb 2
```

这是上一小节安装命令的可选替代，也可以在安装后单独再运行。参数允许 `1` 到 `16`，单位为 GB；默认不创建 Swap。

脚本只在没有启用任何 Swap、没有现成 `/swapfile`、根文件系统为 ext4/xfs 且空间足够时创建。新文件权限为 `600`，成功启用后备份 `/etc/fstab` 并添加开机加载配置。创建后至少保留 2GB 磁盘空间；这只是 Swap 检查要求，游戏镜像与备份还需要额外空间。

已有启用的 Swap 会保留，不追加第二份；已有 `/swapfile` 但未启用时会停止，要求先人工核对，不会覆盖旧文件。Swap 不能代替扩容，编译仍被 OOM 杀死时应增加内存。

### 4. 后续只检查环境

环境安装通常只需执行一次。需要复查时运行：

```bash
sudo bash /tmp/setup-server.sh --check
```

如果临时目录的脚本已被系统清理，可在项目目录执行 `sudo bash ./setup-server.sh --check`。检查模式不会安装软件、启动服务或修改 Swap。

脚本不修改 SSH、防火墙、云安全组、用户组、游戏数据或 `.env`，也不会启动游戏。已有可用 Docker 与 Compose 会保留；发现不完整安装对应的软件包冲突、其他 Docker 软件源或不支持的系统时会明确停止，不能保证任意旧服务器一键迁移。

安装失败先看上方具体报错，修复后可以重试。软件包或 Docker 镜像下载失败时，检查服务器访问 Ubuntu/Debian 软件源、Docker 官方源和镜像仓库的网络；不要删除游戏数据卷。需要人工安装时参考：

- Ubuntu：https://docs.docker.com/engine/install/ubuntu/
- Debian：https://docs.docker.com/engine/install/debian/

本文后续统一使用 `sudo ./deploy.sh` 和 `sudo docker ...`。两个脚本分工如下：

| 脚本 | 用途 | 什么时候执行 |
| --- | --- | --- |
| `setup-server.sh` | 准备服务器环境 | 新服务器首次安装，或检查环境 |
| `deploy.sh` | 配置凭证、构建并部署游戏 | 环境准备好后，在项目根目录执行；后续更新也使用它 |

## 四、把项目传到服务器

两种方式任选其一。首次上线前应确保本次新脚本及相关文件都已提交或上传，包括 `deploy.sh`、`scripts/deploy.py`、`.env.example`、后端源码和 `frontend/`。

### 方式 A：通过 Git 获取，后续更新更方便

**服务器终端：**

```bash
sudo mkdir -p /opt/Strategies-of-the-Flaming-Plains
sudo chown "$(id -un):$(id -gn)" /opt/Strategies-of-the-Flaming-Plains
git clone '你的Git仓库地址' /opt/Strategies-of-the-Flaming-Plains
cd /opt/Strategies-of-the-Flaming-Plains
```

将 `你的Git仓库地址` 替换为实际地址。私有仓库需要先准备有读取权限的密钥或账号。目录必须为空；若已经有项目文件，不要再执行 `git clone` 覆盖。

### 方式 B：本机打包，通过 SCP 上传

**本机终端，先进入你的项目根目录：**

```bash
tar --exclude='.git' --exclude='.env' --exclude='.env.*' \
    --exclude='backend/target' --exclude='node_modules' \
    --exclude='backups' --exclude='output' --exclude='.dbg' \
    --exclude='.private-deploy' --exclude='.deploy-mail.env' \
    --exclude='.codex' --exclude='.agents' --exclude='.deploy.lock' \
    --exclude='*.tar.gz' --exclude='*.log' \
    -cf /tmp/wargame-deploy.tar .
tar -rf /tmp/wargame-deploy.tar .env.example
gzip -f /tmp/wargame-deploy.tar
scp /tmp/wargame-deploy.tar.gz ubuntu@你的服务器公网IP:/tmp/
```

上述打包命令适用于 Mac / Linux / WSL；Windows 可用 WSL 终端执行。先排除所有真实 `.env`，再单独加入 `.env.example`。不要把本机授权码、数据库配置或备份打进源码包。

**服务器终端，仅对首次创建的空项目目录解压：**

```bash
sudo mkdir -p /opt/Strategies-of-the-Flaming-Plains
sudo chown "$(id -un):$(id -gn)" /opt/Strategies-of-the-Flaming-Plains
tar -xzf /tmp/wargame-deploy.tar.gz -C /opt/Strategies-of-the-Flaming-Plains
cd /opt/Strategies-of-the-Flaming-Plains
```

验证关键文件齐全：

```bash
ls deploy.sh scripts/deploy.py docker-compose.yml .env.example
```

## 五、首次准备游戏配置

### 1. 自动带入本机邮箱配置（推荐）

如果本机项目 `.env` 已经配置 SMTP，可免去服务器上重复手填。在**本机项目根目录**执行：

```bash
python3 scripts/export-mail-config.py
scp .private-deploy/mail-config.tar.gz ubuntu@你的服务器公网IP:/tmp/
```

脚本自动读取本机 `.env`，只导出邮件功能开关、SMTP 主机、端口、SSL、账号、授权码和发件人七项，生成权限为 `600` 的私有压缩包。不会导出本机数据库密码、JWT 或邮箱加密/HMAC 密钥；包也不是完整游戏源码。默认输出目录已被 Git 忽略，不要把包放进公开下载目录。

在**服务器项目根目录**执行：

```bash
tar -xzf /tmp/mail-config.tar.gz
chmod +x deploy.sh
sudo ./deploy.sh
```

解压只产生一个权限为 `600` 的 `.deploy-mail.env`。游戏部署脚本会自动带入邮箱设置，独立生成本服务器数据库密码和加密密钥，然后部署；不需要再打开编辑器填写邮箱。已有 `.env` 的 SMTP 为空时也可补入，但必须保留原数据库凭证和密钥。已有完整 SMTP 配置不会被覆盖；部分配置与导入文件冲突时会停止，需先人工核对。

也可先执行 `sudo ./deploy.sh --init-env` 完成导入，再执行 `--check` 和正式部署。`--check` 是只读检查，发现有待导入配置时会要求先完成初始化，不会偷偷写入。

确认 `.env` 导入成功后，可移除服务器上多余的传输文件：

```bash
sudo rm -f .deploy-mail.env /tmp/mail-config.tar.gz
```

`setup-server.sh` 不读取此文件，邮箱配置由 `deploy.sh` 导入。若目标已有数据库却缺失原 `.env`，导入包不能替代原配置，也不能恢复旧邮箱密文；必须找回原数据库凭证和密钥。

使用本方式且不改默认大区时，跳过下面的手动填写流程，直接进入第六节验收。需要修改显示名称等设置时，再参考下一小节。

### 2. 不使用配置包时，手动生成本服务器凭证

**服务器项目根目录：**

```bash
chmod +x deploy.sh
sudo ./deploy.sh --init-env
```

这一步需要 Docker 可用，但不会启动游戏容器。对全新实例，脚本从 `.env.example` 准备 `.env`，生成数据库密码、JWT 密钥及两项独立邮箱密钥，权限设为 `600`。

如果提示发现已有数据库或其他项目的数据卷，先核对是否是旧服务器。不要用删除数据卷或 `--new-instance` 来消除报错，已有数据处理见第九节。

### 3. 手动填写邮箱与大区信息

```bash
sudo nano .env
```

找到并修改原有配置行，**不要在文件末尾重复添加同名键**：

```dotenv
WARGAME_SERVER_ID=jiangsu-1
WARGAME_SERVER_NAME=公测一区
WARGAME_EMAIL_ENABLED=true
WARGAME_MAIL_HOST=smtp.qq.com
WARGAME_MAIL_PORT=465
WARGAME_MAIL_SSL=true
WARGAME_MAIL_USERNAME=你的完整QQ邮箱
WARGAME_MAIL_PASSWORD=该邮箱对应的SMTP授权码
WARGAME_MAIL_FROM=你的完整QQ邮箱
```

Nano 中按 `Ctrl+O`、回车保存，再按 `Ctrl+X` 退出。

QQ 邮箱需在邮箱网页的设置中开启 SMTP，并获取授权码。游戏只需要 SMTP 发信，不需要配置 IMAP。授权码不是邮箱登录密码，发信账号、授权码与发件人应对应同一个邮箱。

当前前端与管理端默认连接 `jiangsu-1`，所以首次按本文部署请保留这个 ID；只改显示名称可以。另开大区需要同时调整前端、管理端、后端和独立数据库，不能只改这一行。数据库初始化后也不能随意更改大区 ID。

**保留脚本生成的以下四项，不要重新生成或拿本机开发配置覆盖：**

| 配置项 | 用途 |
| --- | --- |
| `WARGAME_DB_PASSWORD` | 后端连接数据库的密码 |
| `WARGAME_JWT_SECRET` | 登录凭证签名 |
| `WARGAME_EMAIL_DATA_KEY` | 已绑定邮箱的加密与解密 |
| `WARGAME_EMAIL_HMAC_KEY` | 邮箱查询和验证码校验 |

没有发信配置时，脚本仍允许部署并提示邮件功能不可用；新用户不能正常注册，邮箱找回也不能使用。已有账号密码登录可继续使用。

本机整份 `.env` 不应上传覆盖服务器配置。使用第一小节的私有包可自动带入邮箱，否则需单独填写。不要执行 `source .env` 再部署，脚本不接受导出的 `WARGAME_*`、`COMPOSE_*` 和 `MYSQL_DATABASE` 覆盖。

### 4. 预检并保存原配置

```bash
sudo chmod 600 .env
sudo ./deploy.sh --check
```

预检会检查配置、凭证格式、项目归属和数据卷，不启动游戏，也不实际尝试数据库密码登录或投递邮件。旧 `scripts/security-preflight.py` 等价于该预检入口。

把 `.env` 的私密备份放进你自己的受控存储，今后数据库备份应与原配置一起保管。不要上传 Git，也不要在公网可下载目录存放。

## 六、启动游戏并验收

### 1. 一键部署

**服务器项目根目录：**

```bash
sudo ./deploy.sh
```

首次需要下载镜像、Maven 依赖并编译 Java，等待时间取决于网络和服务器性能。正常过程为：

1. 检查依赖、配置及持久卷，生成私有临时部署配置。
2. 编译后端镜像。
3. 启动 MySQL，确认健康并实际认证、查询数据库。
4. 已有实例先备份，再更新后端；全新实例直接初始化。
5. 启动后端，由 Flyway 执行当前代码携带的迁移，`WorldBootstrap` 初始化世界数据。
6. 重建 Nginx，验证端口隔离、网关 API、大区 ID 和首页。

只有所有验收通过才输出“部署成功”。数据库和后端的宿主机端口在临时配置中移除，原 `docker-compose.yml` 不会被脚本改写。不要直接用 `docker compose up` 代替部署入口，否则可能重新暴露源码配置中的开发端口。

### 2. 浏览器验收

在**本机浏览器**依次验证：

- 打开 `http://你的服务器公网IP/`，能看到游戏页面。
- 打开 `http://你的服务器公网IP/api/auth/server`，返回 JSON，`id` 为 `jiangsu-1`。
- 注册一个测试账号，发送 QQ 邮箱验证码，确认真实收件箱能收到并能完成注册。
- 登录进入游戏，确认地图、资源及基本操作可用。
- 上传自定义头像后刷新页面，确认图片能正常显示。

仅能打开登录页不足以证明后端正常；`mailAvailable: true` 也只说明邮件配置具备使用条件，不代表邮件已经成功投递。

此时完成的是 **HTTP 试运行**。正式面向玩家前，完成下一节 HTTPS、修改初始管理员密码，并保存配置与数据库备份。

## 七、配置域名和 HTTPS

域名是玩家输入的网址，HTTPS 用于加密登录密码和 Token。现有配置只提供 HTTP；放行 443 并不会自动启用 HTTPS。

下面给出 Ubuntu / Debian 的一种做法：由服务器申请证书，让现有游戏 Nginx 容器直接处理 HTTPS。示例域名统一为 `game.example.com`，请全部换成你的域名。服务器已有证书网关时，可沿用原网关，不要重复占用端口。

### 1. 域名指向服务器

在域名服务商控制台添加一条 A 记录，例如主机记录 `game`，值为服务器公网 IPv4。等待解析生效。若添加 AAAA 记录，必须确认 IPv6 同样能访问本机，否则可能影响证书申请。

确认云安全组与主机防火墙都允许 80 / 443。申请证书期间，外部证书服务必须能直接访问该域名的 80 端口。

### 2. 申请证书

**服务器终端：** 下方暂停 Nginx 会使网页短暂不可访问，不会删除数据库。项目名使用本文标准目录对应的 `strategies-of-the-flaming-plains`，自定义项目须同步替换。

```bash
sudo apt-get install -y certbot
cd /opt/Strategies-of-the-Flaming-Plains
sudo docker compose -p strategies-of-the-flaming-plains -f docker-compose.yml stop nginx
sudo certbot certonly --standalone --preferred-challenges http -d game.example.com
```

按提示填写接收续期提醒的邮箱并接受服务条款。成功后会显示证书路径，通常为 `/etc/letsencrypt/live/game.example.com/`。若显示带编号的不同目录，以实际输出为准。

若申请失败，先恢复原 HTTP 入口，再排查域名和端口：

```bash
sudo docker compose -p strategies-of-the-flaming-plains -f docker-compose.yml start nginx
```

### 3. 给 Nginx 配置证书

用 `sudo nano nginx.conf` 编辑。把原来开头的 `listen 80;` 与 `server_name localhost;` 改成下面这些行，**保留原 server 内的所有 location 和响应头配置**：

```nginx
listen 443 ssl;
server_name game.example.com;
ssl_certificate /etc/letsencrypt/live/game.example.com/fullchain.pem;
ssl_certificate_key /etc/letsencrypt/live/game.example.com/privkey.pem;
```

再在文件末尾追加一个独立的 server，把 HTTP 请求跳转到 HTTPS：

```nginx
server {
    listen 80;
    server_name game.example.com;
    return 301 https://$host$request_uri;
}
```

用 `sudo nano docker-compose.yml` 编辑，只在 `nginx` 服务中增加 443 映射与证书目录挂载，其他服务保持原样：

```yaml
  nginx:
    image: nginx:alpine
    depends_on:
      - backend
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./nginx.conf:/etc/nginx/conf.d/default.conf
      - ./frontend:/usr/share/nginx/html
      - /etc/letsencrypt:/etc/letsencrypt:ro
    restart: unless-stopped
```

挂载整个 `/etc/letsencrypt` 是为了让 `live/` 中指向 `archive/` 的证书链接可正常读取。

再用 `sudo nano .env` 找到 `WARGAME_ALLOWED_ORIGINS`，填入你的 HTTPS 来源：

```dotenv
WARGAME_ALLOWED_ORIGINS=https://game.example.com
```

已有其他客户端来源时用逗号分隔追加，不能覆盖丢失。这里不带路径和末尾 `/`。Nginx 解密 HTTPS 后通过 HTTP 转发给后端，当前项目没有启用转发头来源还原，因此必须显式允许该 HTTPS 来源，否则浏览器登录、注册等请求可能被拒绝。

```bash
sudo ./deploy.sh --check
sudo ./deploy.sh
```

脚本内部首页请求会先收到 HTTP 跳转再访问 HTTPS；因此域名解析、证书和服务器自身访问该域名的网络都必须正常。完成后打开 `https://game.example.com/`，确认浏览器无证书警告，并重新验证登录、邮件及游戏操作。

### 4. 配置自动续期

Let's Encrypt 证书需要定期续期。当前使用 standalone 申请方式，续期时 Certbot 需要暂时占用 80 端口，因此要配置停止/启动游戏 Nginx 的钩子。以下路径与项目名要和你的部署保持一致。

用下面命令创建脚本目录：

```bash
sudo mkdir -p /etc/letsencrypt/renewal-hooks/pre /etc/letsencrypt/renewal-hooks/post
sudo nano /etc/letsencrypt/renewal-hooks/pre/stop-game-nginx
```

写入：

```sh
#!/bin/sh
set -eu
/usr/bin/docker compose --project-directory /opt/Strategies-of-the-Flaming-Plains -p strategies-of-the-flaming-plains -f /opt/Strategies-of-the-Flaming-Plains/docker-compose.yml stop nginx
```

再编辑 `sudo nano /etc/letsencrypt/renewal-hooks/post/start-game-nginx`，写入：

```sh
#!/bin/sh
set -eu
/usr/bin/docker compose --project-directory /opt/Strategies-of-the-Flaming-Plains -p strategies-of-the-flaming-plains -f /opt/Strategies-of-the-Flaming-Plains/docker-compose.yml start nginx
```

使脚本可执行并测试续期：

```bash
sudo chmod 700 /etc/letsencrypt/renewal-hooks/pre/stop-game-nginx
sudo chmod 700 /etc/letsencrypt/renewal-hooks/post/start-game-nginx
sudo certbot renew --dry-run
sudo systemctl enable --now certbot.timer
sudo systemctl list-timers certbot.timer
```

若 `command -v docker` 显示的位置不是 `/usr/bin/docker`，先修改两个钩子里的路径。测试续期会短暂停止网页服务，宜在低峰操作；完成后再次确认游戏可访问。续期失败时检查 Certbot 输出、定时器与 Nginx 状态。

## 八、本机管理端连接线上游戏

### 1. 配置代理

管理端是另一个工程 `Strategies-of-the-Flaming-Plains-Admin`。在本机打开它的 `vite.config.js`，修改 `server.proxy` 中的 `/api` 项，保留其他配置：

```javascript
'/api': {
  target: 'https://game.example.com',
  changeOrigin: true,
  headers: {
    Origin: 'https://game.example.com'
  }
}
```

域名换成游戏真实域名。`Origin` 应填写协议和域名，不带路径；目标用了非标准端口时，也要填写该端口。当前旧配置写死 `Origin: http://localhost:8081`，不能只改 `target` 就保留它。修改后重启本机 Vite。

浏览器调用本机 Vite，Vite 再访问游戏服务器。`changeOrigin` 主要改 Host，不代替后端的来源校验；服务器 `.env` 应已按第七节把 `https://game.example.com` 加入 `WARGAME_ALLOWED_ORIGINS`。管理端代理使用同一个已授权来源即可，不需要再把本机 5173 端口加入白名单。

### 2. 安装和启动

本机先安装受支持的 Node.js LTS；管理端使用 Vite 5，Node.js 至少需满足其依赖要求（建议 Node.js 22 LTS）。进入**本机管理端目录**：

```bash
npm install
npm run dev
```

浏览器访问 `http://localhost:5173`。本机 Vite 不需要暴露给公网，也不需要在服务器安装 Node.js。

### 3. 登录后修改初始管理员密码

全新数据库默认管理员为 `admin / admin123456`。已有数据库沿用原密码，更新部署不会重置。

当前管理端没有“头像 → 修改密码”的页面入口。后端已提供改密接口。可以在管理端登录后，通过浏览器开发者工具 Console 执行下面代码：它会弹窗索取旧、新密码，使用已登录的管理员 Token 调用本机代理，不把密码写进代码示例。

```javascript
(async () => {
  const oldPassword = prompt('输入当前管理员密码');
  if (!oldPassword) return;
  const newPassword = prompt('输入新的管理员强密码');
  if (!newPassword) return;
  const response = await fetch('/api/admin/auth/change-password', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Game-Server': 'jiangsu-1',
      Authorization: 'Bearer ' + localStorage.getItem('admin_token')
    },
    body: JSON.stringify({ oldPassword, newPassword })
  });
  const result = await response.json();
  if (!response.ok || result.success !== true) {
    throw new Error(result.error || result.message || '修改失败');
  }
  alert('修改成功，请退出后使用新密码重新登录');
})();
```

仅在你自己的管理端页面执行，弹窗输入不是掩码显示，应避开旁人或共享屏幕。修改后退出，并用新密码重新登录确认。

### 4. 暂无 HTTPS 时使用 SSH 隧道

在**本机终端**保持下面的连接运行：

```bash
ssh -N -L 127.0.0.1:18080:127.0.0.1:80 ubuntu@你的服务器公网IP
```

管理端代理 `target` 改为 `http://127.0.0.1:18080`，`headers.Origin` 可以保留原来的 `http://localhost:8081`。同时在**服务器 `.env`** 的 `WARGAME_ALLOWED_ORIGINS` 中明确加入 `http://localhost:8081`，与已有来源用逗号分隔，然后重新部署。浏览器依然访问本机 `http://localhost:5173`。

隧道用于连接尚未跳转 HTTPS 的 HTTP 网关；已完成 HTTPS 的实例直接使用前面的 HTTPS 代理配置。

## 九、已有服务器如何更新

**有旧数据时，这一节优先于“首次部署”。** 不覆盖原 `.env`，不换数据库卷，也不重新命名项目。

### 1. 先确认原项目名

```bash
sudo docker ps -a --format '{{.Names}} {{.Labels}}'
sudo docker volume ls
```

在容器标签中找到 `com.docker.compose.project`；本文标准目录默认对应 `strategies-of-the-flaming-plains`。如果原部署用的是其他名字，后续命令全部使用原名字：

```bash
sudo ./deploy.sh --project-name 原项目名 --check
sudo ./deploy.sh --project-name 原项目名
```

目录名变了，不代表数据库应该新建。`--new-instance` 仅用于明确另开独立实例，不能用于解决旧库连接失败。

### 2. 更新源码并部署

建议安排低峰更新，提前保存原配置、头像与数据库备份，记录当前代码版本。Git 更新前先检查本地改动，尤其是 HTTPS 配置：

```bash
cd /opt/Strategies-of-the-Flaming-Plains
git status --short
git rev-parse HEAD
```

工作区确认可更新后再执行 `git pull --ff-only`；存在配置改动或冲突时先处理，不要用 `git reset --hard` 清掉生产配置。使用压缩包更新时也应保留生产 `.env`、头像、备份和 HTTPS 配置，不能直接清空目录再覆盖。

随后：

```bash
sudo ./deploy.sh --check
sudo ./deploy.sh
```

自定义项目名时使用上一小节带 `--project-name` 的形式。启动慢可加 `--timeout 600`，分别将数据库和 API 等待时间改为 600 秒，不是整次部署的总时限。

脚本在已有实例替换后端之前，备份数据库、当时的 `.env` 和头像到 `backups/deploy/时间戳/`：

```text
backups/deploy/时间戳/
  database.sql.gz
  .env
  avatars/
  COMPLETE
```

只有存在 `COMPLETE` 的目录才代表备份全部完成。SQL 为事务快照，旧后端在备份时可能继续写入，头像也不是与数据库同一时刻的联合快照；恢复会损失快照之后的数据。此备份不包括全部源码、Nginx 配置和 HTTPS 证书，需要另外保存。

构建失败不会替换后端容器，但前端目录是直接挂载的，更新磁盘上的静态文件可能已经影响玩家页面，所以整个更新不属于原子发布或无停机发布。

### 3. 旧凭证不合规怎么办

脚本遇到已有数据库且缺失原 `.env`、缺少邮箱密钥或凭证不符合检查规则时，会停止，不自动生成替代值。先找回原配置；数据库密码要在 MySQL 内按原认证方式轮换，再同步 `.env`。JWT 轮换会使旧登录凭证失效；已有邮箱加密/HMAC 密钥必须保持连续，不能为了通过检查随意更换。

## 十、日常查看、备份和故障处理

下面命令在服务器项目根目录执行。自定义项目名请替换 `-p` 后的值。状态与日志查询不会重新创建容器。

```bash
sudo docker compose -p strategies-of-the-flaming-plains -f docker-compose.yml ps
sudo docker compose -p strategies-of-the-flaming-plains -f docker-compose.yml logs --tail=100 backend
sudo docker compose -p strategies-of-the-flaming-plains -f docker-compose.yml logs --tail=100 db nginx
```

需要持续看后端日志，在 `logs` 后增加 `-f`，按 `Ctrl+C` 退出日志查看，不会停止游戏。

### 常见问题

| 现象 | 先检查什么 |
| --- | --- |
| Docker 运行时检查失败 | Docker 是否安装和启动；当前用户是否有权限；是否应使用 sudo |
| 提示检测到导出的环境变量 | 不要 source `.env`；退出曾导出游戏变量的 SSH 会话，重新登录后部署 |
| 预检提示 `.env` 权限过宽 | 执行 `sudo chmod 600 .env` 后重试 |
| 发现其他项目数据卷 | 核对原项目名，用 `--project-name` 指向原实例，不删卷 |
| MySQL 不健康或认证超时 | 看 db 日志、磁盘空间，核对原密码；改 `.env` 不会自动修改旧库密码 |
| 构建失败 / OOM | 看构建输出，检查内存、Swap、磁盘、镜像与 Maven 下载网络 |
| API 验收超时 / 502 | 看 backend、nginx 日志；核对数据库迁移、大区 ID、证书配置 |
| 本机可访问，公网打不开 | 检查公网 IP、云安全组、主机防火墙和 80 / 443 监听 |
| 页面正常但注册发不出邮件 | 核对 SMTP 授权码、账号、发件人、出站 465 网络与邮件日志 |
| HTTPS 页面能打开，但登录或注册 403 | 服务器 `WARGAME_ALLOWED_ORIGINS` 是否包含准确的 HTTPS 来源；修改后重新部署 |
| 管理端登录 403 | 核对代理 Origin 和服务器允许来源；修改后重启 Vite |
| HTTPS 到期或续期失败 | 检查域名、80 端口、Certbot 定时器、续期钩子及 Nginx 状态 |

不要把 `.env` 内容、完整 Compose 配置、Token 或带密码的日志截图发到公开渠道。

### 备份保管

自动升级备份不能代替定时备份。至少独立保存数据库、配套 `.env`、头像、生产网关配置、证书及对应代码版本，并复制到服务器之外的受控存储。备份目录不会被脚本自动清理，应自行安排保留周期并监控磁盘占用。

### 失败后怎样恢复

脚本失败会返回非零状态，不自动回滚数据库，也不主动删除数据卷。先查看错误和日志；配置、网络、内存等问题修复后通常可以再次运行部署脚本。

若必须回退到备份数据，按下面顺序由管理员处理：

1. 停止 Nginx 和后端，防止玩家和定时任务继续写入；保留 MySQL 与原数据卷。先另存当前状态，避免覆盖唯一可用数据。
2. 选定带 `COMPLETE` 的备份，确认大区、时间和对应代码版本。
3. 按 MySQL 恢复流程导入 SQL，使用与备份数据库兼容的代码版本；不要只换旧 jar 来尝试撤销已执行的迁移。导入现有库可能残留备份之后增加的表，完整回退应由管理员核对目标库和结构后处理。
4. 配套恢复 `.env` 与头像，确保数据库内实际密码和 `.env` 一致，并保留原邮箱密钥。恢复 `.env` 本身不会修改 MySQL 密码。
5. 恢复生产网关配置与证书，再运行预检和部署，重新验收登录、邮件和游戏操作。

**不要执行 `docker compose down -v`、`docker volume rm` 或带删卷参数的清理命令来解决部署错误。** 这些操作可能直接删除游戏数据。普通运维也不要直接用 `docker compose up` 绕过部署脚本。

部署机制的精确约束见 [服务器部署安全与数据保护约定](DEPLOYMENT_SECURITY_SPEC.md)。
