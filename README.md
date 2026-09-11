# 山河远征 - 初步框架实现

## 项目架构

```
游戏/
├── backend/          # Java Spring Boot 后端 (端口 8080)
│   ├── src/main/java/com/wargame/
│   │   ├── controller/    # REST API 控制器
│   │   ├── service/        # 业务逻辑服务
│   │   ├── model/          # 实体、DTO、常量定义
│   │   ├── repository/     # JPA 数据访问层
│   │   ├── config/         # Spring Security、WebSocket、CORS 配置
│   │   └── util/           # JWT、JSON 工具类
│   ├── src/main/resources/
│   │   ├── application.yml       # 默认配置 (localhost MySQL)
│   │   ├── application-prod.yml   # 生产配置 (环境变量 MySQL)
│   │   └── db/migration/         # Flyway 数据库迁移脚本
│   ├── Dockerfile
│   └── pom.xml
├── frontend/         # 前端静态资源 (端口 8081)
│   ├── index.html
│   ├── css/style.css
│   └── js/
│       ├── api-client.js   # HTTP/WebSocket 客户端
│       ├── api.js          # API 接口封装
│       ├── core.js         # 游戏核心引擎
│       ├── main.js         # 主界面 (建筑/资源/军情)
│       ├── world.js        # 世界地图 (侦查/出征/野地)
│       ├── build.js        # 建筑升级
│       ├── army.js         # 征兵/解散
│       ├── tech.js         # 科技升级
│       ├── officer.js      # 军官招募/管理
│       ├── battle.js       # 战斗系统
│       ├── fort.js         # 城防工事
│       ├── depot.js        # 仓库系统
│       ├── map.js          # 地图解锁
│       ├── data.js         # 静态数据定义
│       ├── save.js         # 本地存档
│       ├── ws-client.js    # WebSocket 客户端
│       └── ws-handlers.js  # WebSocket 消息处理
├── docker-compose.yml    # Docker 编排 (MySQL + 后端 + Nginx)
├── nginx.conf            # Nginx 反向代理配置
├── start-local.sh        # 一键本地启动脚本
└── docs/                 # 设计文档
```

## 技术栈

- **后端**: Java 17 + Spring Boot 3.2 + Spring Security + JPA + Flyway
- **数据库**: MySQL 8+
- **前端**: 原生 HTML/CSS/JavaScript (无框架)
- **实时通信**: WebSocket (部队行军、战斗报告、资源产出)
- **部署**: Docker + Docker Compose + Nginx

## 服务启动方式

### 方式一: 一键本地启动 (推荐开发)

```bash
./start-local.sh
```

脚本自动完成:
- 检测并安装 JDK 17、Maven、MySQL
- 创建 `wargame` 数据库
- 启动后端 (端口 8080)
- 启动前端 (端口 8081)

启动后访问 http://localhost:8081

按 `Ctrl+C` 停止所有服务。

### 方式二: 手动启动

#### 1. 启动 MySQL 数据库

```bash
# 确保 MySQL 已运行
mysql -u root -e "CREATE DATABASE IF NOT EXISTS wargame DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
```

数据库连接配置见 `backend/src/main/resources/application.yml`:
- 地址: `localhost:3306`
- 用户: `root`
- 密码: 空
- 数据库名: `wargame`

#### 2. 启动后端服务

```bash
cd backend
mvn spring-boot:run
```

- 端口: 8080
- Flyway 自动执行数据库迁移 (建表)
- 新玩家注册时自动初始化资源: 粮10000/钢8000/油3000/稀1000/金1000

#### 3. 启动前端服务

```bash
cd frontend
python3 -m http.server 8081
```

- 端口: 8081
- 前端通过 `api-client.js` 自动将 API 请求代理到 `localhost:8080`

访问 http://localhost:8081 开始游戏。

### 方式三: Docker Compose 部署 (推荐生产)

```bash
docker compose up -d --build
```

启动三个容器:

| 服务 | 镜像 | 端口 | 说明 |
|------|------|------|------|
| MySQL | mysql:8 | 3306 | 数据库 |
| Backend | 自建 | 8080 | Java 后端 |
| Nginx | nginx:alpine | 80 | 前端 + API 反向代理 |

访问 http://localhost 即可。

> 注意: Docker 部署前需在 `docker-compose.yml` 的 backend 服务中添加 `SPRING_PROFILES_ACTIVE: prod` 环境变量，否则后端会连接 `localhost` 而非 `db` 容器。
