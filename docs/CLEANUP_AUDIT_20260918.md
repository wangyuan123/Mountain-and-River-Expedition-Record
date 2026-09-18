# 无用代码与文件检查（2026-09-18）

初版为静态审查，随后按用户要求完成第一批清理，执行结果见下文。此前已删除的根目录 12 张 SVG 不计入第一批数量。审查检查了首页脚本加载、前端代码/HTML/测试引用、后端私有方法、资源制作脚本、部署配置及 Git 跟踪文件。以下审查条目的行号、文件数量和跟踪状态以清理前为准。没有运行游戏完整交互，不能把名称搜索结果当作完整调用图；公开方法、反射、动态属性访问和历史兼容需保留这一边界。

## 第一批执行结果

- 已删除第 1 节的 17 张旧建筑 SVG、6 个前端局部函数、8 个后端私有方法、两个一次性脚本及编译参数临时文件。
- 同步删除失去用途的 `MULTI_SLOT` 常量和 `FortDef` 导入。建筑图标测试改用仍存在的 `img/res-steel.svg`，继续验证 SVG 渲染。
- 两个 ZIP 已通过 `git rm --cached` 取消跟踪，本地文件保留；`.gitignore` 改为 `/output/**/*.zip`，覆盖全部素材输出目录。
- 第 2 节的公开入口和兼容层留待后续清理。本次未提交、未推送，也未改写 Git 历史。
- 前端：`node --test frontend/tests/*.test.cjs`，148 项全部通过。
- 后端：`LC_ALL=en_US.UTF-8 LANG=en_US.UTF-8 mvn -f backend/pom.xml -B test`，238 项中 237 项通过、1 项跳过，无失败。跳过的 MySQL 迁移测试要求显式配置 `WARGAME_TEST_MYSQL_URL`；本次未提供独立 MySQL 测试库。
- 首次后端测试受 `LC_ALL=C` 的中文路径编码影响，在编译前失败；显式设置 UTF-8 locale 后通过。没有修改项目业务代码来处理环境问题。
- `git diff --check` 通过；删除的函数及旧建筑 SVG 路径在当前前端/后端源码和测试中均无残留引用。

## 1. 优先清理：证据较明确

### 17 张旧建筑 SVG

目录：`frontend/img/buildings/` 根目录，共约 39.9 KiB。

`house.svg`、`academy.svg`、`radar.svg`、`depot.svg`、`lab.svg`、`transit.svg`、`exchange.svg`、`factory.svg`、`port.svg`、`staff.svg`、`lightfactory.svg`、`apron.svg`、`command.svg`、`liaison.svg`、`wall.svg`、`airport.svg`、`heavyfactory.svg`。

当前 `frontend/js/build.js:1258` 的 `BUILD_ICON` 已全部指向 `img/buildings/garden/*.webp`。当前前端仅 `frontend/tests/build.test.cjs:274` 的 HTML 字符串测试使用旧 `factory.svg` 路径，不实际读取文件；其他引用位于历史归档。清理时应把该测试改用仍存在的 SVG，继续保留 SVG 渲染能力验证，而不是删除断言。

### 6 个前端局部死函数

这些函数位于模块闭包内，没有调用或导出；当前前端代码和测试搜索只有定义。

| 位置 | 可清理函数 | 说明 |
| --- | --- | --- |
| `frontend/js/save.js:20` | `migrateBuildings`、`migrateConstructions` | 本地旧存档转换残留；删除后可同时移除仅供它们使用的 `MULTI_SLOT` |
| `frontend/js/depot.js:61` | `setShield`、`setMarchBoost` | 本地修改护盾/加速时间的旧辅助函数，道具现在通过后端处理 |
| `frontend/js/fort.js:20` | `fortCost`、`armyEmpty` | 未使用的城防辅助函数 |

### 8 个后端私有死方法

在 `backend/src`（含测试）中搜索方法名均仅命中定义，没有查到反射测试引用；方法本身没有框架回调注解。

| 位置 | 方法 |
| --- | --- |
| `backend/src/main/java/com/wargame/service/GameStateService.java:581` | `collectOccupiedCoordinates` |
| `backend/src/main/java/com/wargame/service/GameStateService.java:876` | `genNpcForts` |
| `backend/src/main/java/com/wargame/service/GameStateService.java:911` | `freeCoordNear` |
| `backend/src/main/java/com/wargame/service/BattleService.java:702` | `catAtkKey`、`catDefKey` |
| `backend/src/main/java/com/wargame/service/BattleService.java:878` | `minSpdOf` |
| `backend/src/main/java/com/wargame/service/ArmyService.java:422` | `queuedArmy`、`queuedPopulation` |

清理后通过编译和相关后端测试验证；被这些函数调用的其他辅助方法不能连带直接删除，需重新检查引用。

### 两个一次性脚本和编译临时文件

- `remove-go-backend.sh`：仅用于删除旧 `server/` Go 后端；该目录已经不存在，当前 Compose 使用 Java 后端。
- `fix-remove-genworld-on-register.sh`：对应的注册初始化修复已在当前代码中实现，不属于启动、CI 或日常维护流程。注意 `WorldBootstrap` 中仍需要调用 `genWorld(null)`，不要为了清理脚本而删除正常启动逻辑。
- `javac.20260911_225832.args`：约 86.7 KiB，编译器生成的参数文件，仍受 Git 跟踪。`.gitignore` 已有 `javac.*.args`，但不会自动取消对已提交文件的跟踪。

## 2. 建议第二批清理：公开入口、兼容层及测试需要联动

### 旧资料编辑函数

`frontend/js/main.js:202` 的 `toggleEditCommander`、`saveCommander`、`toggleEditDesc`、`saveDesc` 没有找到调用。其查找的 `editCommanderBox`、`epCommander`、`editDescBox`、`epCityDesc` 也未发现当前页面生成代码。保存逻辑仅修改本地状态，属于优先复核的历史入口。

### 旧客户端计算和状态修改

`frontend/js/core.js` 中 `atkMul`、`defMul`、`rangeMul`、`hpMul`、`medicalMul`、`addExp`、`addOfficerExp`、`payCost`、`skillBonus`、`addPrestige` 未发现当前前端调用。游戏已由后端结算，可逐项删除未用方法；不能因此删除整个 `core.js`，也不能连带删除仍供界面展示使用的计算函数。

`frontend/js/depot.js:187` 的 `useResourceItem`、`useUtilItem`、`useRecruitOrd` 未发现调用，当前道具入口是 `useItem`。`frontend/js/world.js` 的 `gatherWild`、`updateEstLoad` 也未发现调用，属于旧包装入口候选。

### 旧建筑和战报渲染

- `frontend/js/build.js:1307` 的 `renderBuilding` 在当前前端仅由 `frontend/tests/build.test.cjs:155` 调用；实际建筑页面使用卡槽网格。清理旧渲染器及其专属样式前，应把拆除按钮的覆盖转移到当前实际界面路径。不要简单删除仍有价值的拆除功能测试。
- `frontend/js/battle.js:483` 的 `renderSurvivors` 和 `:692` 的 `renderForceList` 未发现当前调用，可复核后清理。

### 存档与每日任务兼容层

- `save.js` 的 `Save` 对象（同步空方法）、`G.save`、`G.reset`、`G.growAttr` 未发现当前前端消费者，可作为兼容层清理候选。但 `G.load` 被登录流程使用，`ATTR_MAX`、`OFFICER_MAX_LEVEL`、`expNeeded` 被军官页面使用，不能整文件删除。
- `task-quests.js` 目前是“暂无每日任务数据”占位实现。`renderQuestCard`、`renderQuestEntry`、`renderQuestEmbed` 没有找到模块外使用，但 `Quests.init/onEvent` 仍由登录、建造、招募、侦察和行军流程调用。若删除整个模块，需同时删除这些空回调及首页脚本标签，并验证不会影响 `task.js`、`main-quest.js` 的真实任务功能。
- `api.js` 内部分封装没有当前 UI 调用，例如 `getUserInfo`、`dismissTutorial`、`getNearby`、`getWildTiles`、`wildScout`、`wildConquer`、`wildGather`。这是前端封装候选，不等于对应后端 HTTP 接口可删除；仍需检查兼容客户端及接口用途。

## 3. 可移出代码仓库的产物

当前 Git 仍跟踪两个 ZIP，合计约 26.1 MiB：

- `output/building-icon-concepts-20260917/building-icons-3-sets.zip`
- `output/imagegen/map-icons/map-icons-assets.zip`

它们是素材交付包，不是游戏运行依赖，可保留外部备份后取消 Git 跟踪。当前忽略规则仅覆盖 `/output/imagegen/**/*.zip`，没有覆盖第一个包；第二个包虽然匹配规则，已有跟踪也不会自动解除。

本地 `output/` 约 549 MiB，其中包括未跟踪产物，不能全部按无用文件删除。适合把原稿、交付包和截图转移到素材归档存储；生成脚本和必要输入应有明确保留位置。删除最新版本中的文件不会自动缩小 Git 历史。

`backend/target/` 和 `.dbg/` 为本地构建/调试产物，可在不使用相关进程时清理，收益是本地磁盘空间，不是提交体积。

## 4. 本轮不应误删的内容

- `frontend/img/map/*-map.webp`、`frontend/img/cities/*-map.webp`：`scripts/assets/embed-map-icons.py` 通过 glob 读取它们来生成地图 PNG。
- `snow-*-map-embedded.png`：除地图使用外，也是 `prepare-snow-ground.py` 的输入。
- `output/` 中的原图：`prepare-building-models.py`、`prepare-garden-city.py` 仍依赖这些输入。
- `frontend/tests/`、独立预览 HTML：没有首页链接不代表无用，它们是测试和人工验收入口；可从生产部署中排除，不应仅凭未被首页引用而删除。
- 所有 `frontend/js/*.js` 当前均由首页加载；没有找到可以仅凭“未加载”删除的 JS 文件。
- `backend/src/main/resources/db/migration/`：历史迁移用于升级及校验，即使某玩法已调整也不能作为死代码删除。
- 防沉迷相关模块：README 明确说明是暂时关闭、计划恢复的功能，不能按当前开关关闭判定废弃。
- `docs/archive/` 和内容审计报告：属于历史参考记录；可决定是否外部归档，但不能称为无用运行逻辑。

## 建议执行顺序

先清理第 1 节；再按模块清理第 2 节并更新测试；最后单独处理素材归档和生产部署排除规则。实际改动完成后运行前端测试、后端编译/测试和必要的页面验证。本报告不改写 Git 历史，也不删除此前尚未提交的变更。
