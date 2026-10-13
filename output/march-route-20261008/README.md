# 直线行军与浅色光带

日期：2026-10-08。

## 行为

- 征服、掠夺出征显示浅红色半透明光带；返城、撤退、运输、调遣显示浅蓝色光带。侦查、采集、进驻也使用蓝色。
- 光带有浅色边缘及沿行军方向流动的箭头；宽度随地图缩放调整并限制在合理范围。
- 起终点之间直接连线，部队模型沿直线移动。历史折点路线在前端显示时也使用当前起终点。
- 新行军使用 `hypot(dx, dy)` 计算距离，时间、油耗使用完整小数，界面显示到小数点后两位。
- 路线预览、出征统计、地图详情和目标列表同步使用直线距离。扫描可见范围保持服务端原有规则。
- 港口、海域连通性和运输机运力校验继续生效；地形寻路用于判断通行资格，不再决定路线长度和绘制折点。

## 验证

- 前端地图行军、模型朝向、路线预览：40/40 通过。
- 后端 `OceanTerrainTest`：12/12 通过，覆盖海陆空直线距离、保存小数距离、时间、油耗及返程。
- 后端 `ApronAirMarchSpeedTest`：3/3 通过。
- 三个修改的前端业务脚本均通过 `node --check`；本次文件通过 `git diff --check`。
- Playwright 检查 1440×960 和 390×844 视口，无页面异常及横向溢出；画布截图非空，动画与缩放有效。
- 截图：`desktop.png`、`mobile.png`。
- 全量前端：540 项，536 通过，4 项首页排序测试因缺少 `officers` 配置失败。
- 全量后端：586 项，35 失败、7 报错、2 跳过，未全绿。失败涉及战斗伤害与平衡、战报、采集量、城市/注册控制器、情报宣战校验等。本次没有修改这些模块来消除失败。
- MySQL 迁移集成测试因没有指定独立测试数据库而跳过；没有对正在运行的游戏数据库执行迁移。

执行命令：

```sh
node --test frontend/tests/map-march-status.test.cjs frontend/tests/map-picking.test.cjs frontend/tests/dispatch-route.test.cjs
node --test frontend/tests/*.test.cjs
JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8 mvn -f backend/pom.xml -Dtest=OceanTerrainTest test
JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8 mvn -f backend/pom.xml -Dspring.profiles.active=test test
```

## 本地查看与生效

- 游戏：http://127.0.0.1:8081/
- 光带演示：http://127.0.0.1:8081/tests/ocean-map-preview.html?showcase&marchbands
- 后端重新启动后，Flyway 自动执行 `V77__march_straight_distance.sql`，把行军距离列改为 DOUBLE，新出征按直线距离计时。
- 已存在的行军和已保存驻军保留原定时间及距离记录，不追溯更改已经扣除的油耗；地图展示改为直线。
