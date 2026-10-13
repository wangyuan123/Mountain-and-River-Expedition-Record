# 侦查装甲车实施与验证

日期：2026-10-09。功能已实现，已应用用户提供的 M8 写实微缩模型。

## 实现范围

按照 `docs/RECON_ARMORED_CAR_DESIGN_20261008.md` 的当前版本实施：

- `recon_car` 为军工厂制造的装甲陆军，攻击 18／5／45／36、防御 33、生命 360、速度 5、射程 300，造价与防空装甲车一致。
- 可参加常规攻防、同军团驻防、自有城市调遣及野地驻防；反坦克火箭车对其 ×3，没有防空装甲车的专项防空倍率。
- 侦查界面选择空中或地面一种方式，切换清空原选择；后端拒绝混编和普通作战部队。陆路不通或海上目标不能执行地面侦查，预览和出征采用同一校验。
- 两种方式仅与同方式驻防侦查单位遭遇，共用侦察科技权限；十回合战平且攻方幸存时可以获取情报。
- 援军参与拦截，战损分摊及伤兵保留原派兵城市；抵达、撤回、返程和全灭共用现有生命周期。
- 双方报告具有侦查方式、初始／幸存兵力快照和遭遇记录；守方不收到内部情报。飞机用“架／击落”，车辆用“辆／损失车辆”，旧报告按飞机兼容。
- 兼容野地快捷侦查接口优先派飞机，无飞机时使用新车；未调整 NPC 编成数量。

## 验证结果

| 验证 | 结果 |
| --- | --- |
| 后端专项 | 67 项通过，6 个测试类；其中 `ReconArmoredCarTest` 23 项、`GuildSupportTest` 16 项 |
| 前端专项 | 88 项通过 |
| 桌面浏览器 1280×900 | 真实前端配合模拟接口：地面选择、出征、仅车默认、陆路拒绝、手动重试及报告通过，无页面异常或横向溢出 |
| 手机浏览器 390×844 | 同上，通过 |
| JS 语法与差异空白检查 | 通过，未执行自动格式化 |
| 前端全量 | 571 项，567 通过、4 失败；均为 `home-module-order.test.cjs` 的 `officers` 夹具错误 |
| 后端全量 | 644 项，33 个失败、10 个错误、2 个跳过；专项 6 类在全量中仍通过 |

后端全量失败涉及现有采集载重断言、战斗与宣战前置条件、威望计算、NPC 布局、Controller 注册夹具、军官技能文案、共享 H2 上下文表删除，以及未使用测试配置的 MySQL 用例受沙箱连接限制。尚未用独立基线逐一证明这些失败的来源，不将其表述为已证明的基线问题，也不宣称全量通过。本轮不修改这些其他功能的规则或并行任务代码。

专项测试覆盖 Lv.0–5 权限、双向跨方式不拦截、无拦截、全灭、十回合战平、补给与机动、制造、普通攻防、撤回、重复结算、援军战损守恒、准确伤兵数量、分城归属及返城。

## 复现命令

后端专项：

```sh
LC_ALL=en_US.UTF-8 JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8' mvn -q -o -f backend/pom.xml -Dtest=ReconArmoredCarTest,GuildSupportTest,WildScoutTest,ApronAirMarchSpeedTest,BattleDamageTest,NpcJapaneseUnitTest test
```

前端专项：

```sh
node --test frontend/tests/recon-car.test.cjs frontend/tests/reports.test.cjs frontend/tests/map.test.cjs frontend/tests/combat-data.test.cjs frontend/tests/japanese-units.test.cjs frontend/tests/apron-air-speed.test.cjs frontend/tests/dispatch-route.test.cjs frontend/tests/guild-support.test.cjs frontend/tests/army*.test.cjs
```

前端全量：`node --test frontend/tests/*.test.cjs`。后端全量使用上述 UTF-8 环境，去掉 `-Dtest=...`。

浏览器验证：`node output/recon-car-20261009/browser-qa.cjs`，使用本地 Chrome 和 bundled Playwright。页面 `preview.html` 仅使用模拟接口；不能将该结果视为真实服务联调或数据库迁移验证。

浏览器结果在 `browser-checks.json`，截图为 `dispatch-1280.png`、`dispatch-390.png`、`report-1280.png`、`report-390.png`。测试摘要在 `verification.json`。

## 模型应用

正式素材为用户提供的 `frontend/img/units/models/recon_car.webp`：384×384、RGBA、透明 WebP。`Game.UNIT_MODEL.recon_car` 和 `Game.UNIT_ICON.recon_car` 均指向此文件，首页、军队、战术界面、地图及通用兵种图标统一使用该模型。

已删除此前的低模 SVG `frontend/img/units/recon_car.svg` 及生成脚本 `scripts/assets/create-recon-car-model.py`，防止旧低模再次覆盖正式模型。用户提供的模型文件未重新生成或改写。

写实模型原稿、处理脚本和深浅背景的 56px／72px／240px 验收图保留在 `output/imagegen/recon-car-20261009/`。功能预览截图使用当前正式模型更新。

本次模型应用验证：相关 43 项前端测试通过，桌面 1280×900 和手机 390×844 浏览器预览通过；已核对模型与通用图标的路径、尺寸和透明通道，正式 WebP 的文件内容保持原样。
