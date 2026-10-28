# 图片资源清理审计（2026-10-27）

基准提交：`f82bc49b0361fe14855d974aea35314ffed54c24`。范围为 `frontend/img`；原稿、设计候选、历史截图和归档保留在 `output`、`docs/archive` 中。

删除 **142 张图片**，共 **25,924,888 字节（24.72 MiB）**。图片目录从 403 张、58.13 MiB 减至 261 张、33.41 MiB，减少 42.53%。其中 243 张仍有游戏引用，另 18 张供本地制作脚本读取。

## 判断依据

- 核对前端 JavaScript、CSS、HTML、数据映射、预览页和本地制作脚本。历史记录、纯输出路径与选型稿不计入游戏使用依据。
- 加载当前 `constants.js`、`data.js`、`world-map.js`，枚举城墙等级 0～10、NPC/流寇等级 0～30、坐标种子、现有兵种组合、海军类型、野地变体、采空车队及三档积雪。
- 根据地图实际规则，将 WebP 路径展开为 `-map-embedded.png`，并保留油田、炼铁厂、稀矿厂各 12 帧动画。粮田现用的 `wild-grainfield-map-v2-map-embedded.png` 保留。
- 内容相同但仍被不同现用路径引用的图片继续保留；只删除已无游戏消费者的旧路径。
- 制作流程的输入另列保留理由，避免仅凭运行时没有请求就删除源图。

## 清理汇总

| 类别 | 图片数 | 体积（MiB） |
| --- | ---: | ---: |
| 旧城市模型与城市别名 | 19 | 11.20 |
| 旧通用护盾图标 | 1 | 0.00 |
| 旧登录背景与未使用商城图标 | 4 | 1.01 |
| 旧地形、资源别名与多余动画帧 | 100 | 11.97 |
| 旧兵种 PNG 与俯视模型 | 6 | 0.51 |
| 未采用的兵种候选图 | 12 | 0.03 |

同步删除兵种候选预览 `frontend/unit-icon-options.html` 和候选说明 `frontend/img/units/options/README.md`。登录预览移除旧版本切换，道具预览移除通用旧护盾，粮田候选预览改读 `output/imagegen/redesign-oil-grain-20261019/` 的两张原稿。

现有测试改为检查实际使用的六阶城市模型、地形变体和粮田嵌入图；SVG 渲染测试改用现用的 8 小时护盾图标。当前设计说明和图片作者登记同步更新。

## 保留的制作源图

| 文件 | 保留理由 |
| --- | --- |
| `frontend/img/map/wild-forest-dense-map-embedded.png` | scripts/assets/redesign-resource-ground.py 的输入 |
| `frontend/img/map/wild-forest-ridge-map-embedded.png` | scripts/assets/redesign-resource-ground.py 的输入 |
| `frontend/img/map/wild-forest-edge-map-embedded.png` | scripts/assets/redesign-resource-ground.py 的输入 |
| `frontend/img/map/wild-hill-peak-map-embedded.png` | scripts/assets/redesign-resource-ground.py 的输入 |
| `frontend/img/map/wild-hill-ridge-map-embedded.png` | scripts/assets/redesign-resource-ground.py 的输入 |
| `frontend/img/map/wild-hill-foothill-map-embedded.png` | scripts/assets/redesign-resource-ground.py 的输入 |
| `frontend/img/map/wild-swamp-deep-map-embedded.png` | scripts/assets/redesign-resource-ground.py 的输入 |
| `frontend/img/map/wild-swamp-creek-map-embedded.png` | scripts/assets/redesign-resource-ground.py 的输入 |
| `frontend/img/map/wild-swamp-marsh-map-embedded.png` | scripts/assets/redesign-resource-ground.py 的输入 |
| `frontend/img/map/wild-rock-map-embedded.png` | scripts/assets/redesign-resource-ground.py 的输入 |
| `frontend/img/map/wild-grainfield-map-embedded.png` | scripts/assets/redesign-resource-ground.py 的输入 |
| `frontend/img/map/wild-oil-map-embedded.png` | scripts/assets/redesign-resource-ground.py 的输入 |
| `frontend/img/map/wild-plains-map-embedded.png` | scripts/assets/redesign-resource-ground.py 的输入 |
| `frontend/img/map/wild-ironworks-integrated-map-embedded.png` | 工业资源动画制作源图（含油田静态导出） |
| `frontend/img/map/wild-rarefactory-integrated-map-embedded.png` | 工业资源动画制作源图（含油田静态导出） |
| `frontend/img/map/wild-oil-integrated-map-embedded.png` | 工业资源动画制作源图（含油田静态导出） |
| `frontend/img/cities/player-city-preview.png` | scripts/assets/generate-tactical-wall-assets.py 的输入 |
| `frontend/img/map/wild-oil-transparent-c.png` | scripts/assets/build-both-rice-variants-full.py 的输入 |

## 验证

- `node --test frontend/tests/*.test.cjs`：1176/1176 通过，0 失败、0 跳过。
- 运行时引用与动态展开的 243 个图片路径全部存在；游戏源码、样式和预览页的 270 处直接图片引用全部有效。
- `output/asset-provenance.json` 的 261 条记录与实际文件集合一致，逐项 SHA-256 校验通过。
- `git diff --check` 通过。
- 检查未运行格式化命令；未改动游戏生产代码或现用图片内容。

## 删除图片逐项清单

机器可读清单（含删除前字节数和 SHA-256）：[image-resource-cleanup-20261027.json](../output/image-resource-cleanup-20261027.json)。

| 文件 | 字节数 | 原因 |
| --- | ---: | --- |
| `frontend/img/cities/garden-citadel-map-embedded.png` | 447,970 | 旧城市模型与城市别名 |
| `frontend/img/cities/garden-citadel.webp` | 132,194 | 旧城市模型与城市别名 |
| `frontend/img/cities/harbor-map-embedded.png` | 164,378 | 旧城市模型与城市别名 |
| `frontend/img/cities/harbor-map.webp` | 40,702 | 旧城市模型与城市别名 |
| `frontend/img/cities/harbor.webp` | 40,702 | 旧城市模型与城市别名 |
| `frontend/img/cities/player-city-lv1-2.png` | 1,674,251 | 旧城市模型与城市别名 |
| `frontend/img/cities/player-city-lv1-2.webp` | 416,610 | 旧城市模型与城市别名 |
| `frontend/img/cities/player-city-lv1-4.png` | 1,674,251 | 旧城市模型与城市别名 |
| `frontend/img/cities/player-city-lv1-4.webp` | 416,610 | 旧城市模型与城市别名 |
| `frontend/img/cities/player-city-lv10.webp` | 440,936 | 旧城市模型与城市别名 |
| `frontend/img/cities/player-city-lv3-4.webp` | 380,566 | 旧城市模型与城市别名 |
| `frontend/img/cities/player-city-lv5-6.webp` | 414,336 | 旧城市模型与城市别名 |
| `frontend/img/cities/player-city-lv5-9.png` | 1,651,792 | 旧城市模型与城市别名 |
| `frontend/img/cities/player-city-lv5-9.webp` | 380,566 | 旧城市模型与城市别名 |
| `frontend/img/cities/player-city-lv7-8.webp` | 442,842 | 旧城市模型与城市别名 |
| `frontend/img/cities/player-city-lv9-10.png` | 1,783,574 | 旧城市模型与城市别名 |
| `frontend/img/cities/player-city-lv9-10.webp` | 412,636 | 旧城市模型与城市别名 |
| `frontend/img/cities/player-city-lv9.webp` | 412,636 | 旧城市模型与城市别名 |
| `frontend/img/cities/player-city-preview-map.webp` | 416,610 | 旧城市模型与城市别名 |
| `frontend/img/items/shield.svg` | 3,977 | 旧通用护盾图标 |
| `frontend/img/login-battlefield-diorama-v2.webp` | 219,104 | 旧登录背景与未使用商城图标 |
| `frontend/img/login-battlefield-diorama.jpg` | 397,560 | 旧登录背景与未使用商城图标 |
| `frontend/img/login-frontline.svg` | 443,602 | 旧登录背景与未使用商城图标 |
| `frontend/img/map/grass-lush-map-embedded.png` | 199,073 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/grass-lush-map.webp` | 41,020 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/grass-lush.webp` | 41,020 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/grass-medium-map-embedded.png` | 201,178 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/grass-medium-map.webp` | 40,692 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/grass-medium.webp` | 40,692 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/grass-plain-map-embedded.png` | 201,178 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/grass-plain-map.webp` | 40,692 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/grass-plain.webp` | 40,692 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/grass-sparse-map-embedded.png` | 184,607 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/grass-sparse-map.webp` | 34,600 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/grass-sparse.webp` | 34,600 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/npc-fortress-map-embedded.png` | 91,046 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/npc-fortress-map.webp` | 31,090 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/npc-fortress.webp` | 31,090 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/snow-medium-ground.png` | 359,948 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/snow-thick-ground.png` | 265,440 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/snow-thin-ground.png` | 428,546 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-depleted-convoy-integrated-map-embedded.png` | 165,809 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-depleted-convoy-integrated.webp` | 43,276 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-forest-dense-map.webp` | 77,258 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-forest-dense.webp` | 77,258 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-forest-edge-map.webp` | 76,316 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-forest-edge.webp` | 76,316 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-forest-map-embedded.png` | 325,635 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-forest-map.webp` | 77,258 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-forest-ridge-map.webp` | 76,158 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-forest-ridge.webp` | 76,158 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-forest.webp` | 77,258 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-grainfield-integrated-map-embedded.png` | 202,962 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-grainfield-integrated.webp` | 55,406 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-grainfield-map.webp` | 55,406 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-grainfield-orthogonal-map-embedded.png` | 202,962 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-grainfield-square-c.png` | 200,631 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-grainfield-transparent-c.png` | 202,962 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-grainfield-v1-waterway.png` | 202,962 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-grainfield-v1-waterway.webp` | 55,406 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-grainfield-v2-cart.png` | 182,809 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-grainfield-v2-cart.webp` | 49,878 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-grainfield.webp` | 55,406 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-grassland-dense-map-embedded.png` | 179,956 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-grassland-dense-map.webp` | 34,232 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-grassland-dense.webp` | 34,232 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-grassland-map.webp` | 34,232 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-grassland-patch-map-embedded.png` | 179,956 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-grassland-patch-map.webp` | 34,232 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-grassland-patch.webp` | 34,232 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-grassland-wetland-map-embedded.png` | 179,956 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-grassland-wetland-map.webp` | 34,232 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-grassland-wetland.webp` | 34,232 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-hill-foothill-map.webp` | 65,748 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-hill-foothill.webp` | 65,748 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-hill-map-embedded.png` | 286,523 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-hill-map.webp` | 60,340 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-hill-peak-map.webp` | 61,526 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-hill-peak.webp` | 61,526 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-hill-ridge-map.webp` | 60,340 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-hill-ridge.webp` | 60,340 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-hill.webp` | 60,340 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-ironworks-map-embedded.png` | 269,183 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-ironworks-map.webp` | 59,370 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-ironworks.webp` | 59,370 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-oil-animated.gif` | 1,262,985 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-oil-animated.webp` | 229,438 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-oil-frames/frame_12.png` | 265,244 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-oil-frames/frame_13.png` | 265,198 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-oil-frames/frame_14.png` | 265,194 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-oil-frames/frame_15.png` | 265,206 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-oil-map.webp` | 52,034 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-oil-orthogonal-map-embedded.png` | 174,692 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-oil-spritesheet.png` | 333,279 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-oil-square-c.png` | 181,700 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-oil.webp` | 52,034 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-plains-integrated-map.webp` | 40,692 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-plains-map.webp` | 40,692 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-plains-open-map-embedded.png` | 201,178 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-plains-open-map.webp` | 40,692 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-plains-open.webp` | 40,692 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-plains-steppe-map-embedded.png` | 201,178 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-plains-steppe-map.webp` | 40,692 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-plains-steppe.webp` | 40,692 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-plains-sunlit-map-embedded.png` | 201,178 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-plains-sunlit-map.webp` | 40,692 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-plains-sunlit.webp` | 40,692 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-plains.webp` | 40,692 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-rarefactory-map-embedded.png` | 292,259 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-rarefactory-map.webp` | 65,650 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-rarefactory.webp` | 65,650 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-rock-map.webp` | 62,124 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-rock.webp` | 62,124 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-snow-map.webp` | 18,140 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-swamp-creek-map.webp` | 56,338 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-swamp-creek.webp` | 56,338 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-swamp-deep-map.webp` | 65,772 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-swamp-deep.webp` | 65,772 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-swamp-map-embedded.png` | 289,970 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-swamp-map.webp` | 65,772 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-swamp-marsh-map.webp` | 63,842 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-swamp-marsh.webp` | 63,842 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/map/wild-swamp.webp` | 65,772 | 旧地形、资源别名与多余动画帧 |
| `frontend/img/shop.svg` | 3,575 | 旧登录背景与未使用商城图标 |
| `frontend/img/units/antitank.png` | 77,503 | 旧兵种 PNG 与俯视模型 |
| `frontend/img/units/bunker.png` | 174,808 | 旧兵种 PNG 与俯视模型 |
| `frontend/img/units/flak.png` | 113,286 | 旧兵种 PNG 与俯视模型 |
| `frontend/img/units/howitzer.png` | 123,042 | 旧兵种 PNG 与俯视模型 |
| `frontend/img/units/models/ltank-topdown.webp` | 24,722 | 旧兵种 PNG 与俯视模型 |
| `frontend/img/units/models/truck-topdown.webp` | 19,982 | 旧兵种 PNG 与俯视模型 |
| `frontend/img/units/options/battleship-option-A.svg` | 2,606 | 未采用的兵种候选图 |
| `frontend/img/units/options/battleship-option-B.svg` | 2,606 | 未采用的兵种候选图 |
| `frontend/img/units/options/battleship-option-C.svg` | 2,606 | 未采用的兵种候选图 |
| `frontend/img/units/options/bomber-option-A.svg` | 2,489 | 未采用的兵种候选图 |
| `frontend/img/units/options/bomber-option-B.svg` | 2,489 | 未采用的兵种候选图 |
| `frontend/img/units/options/bomber-option-C.svg` | 2,489 | 未采用的兵种候选图 |
| `frontend/img/units/options/fighter-option-A.svg` | 2,452 | 未采用的兵种候选图 |
| `frontend/img/units/options/fighter-option-B.svg` | 2,452 | 未采用的兵种候选图 |
| `frontend/img/units/options/fighter-option-C.svg` | 2,452 | 未采用的兵种候选图 |
| `frontend/img/units/options/htank-option-A.svg` | 2,751 | 未采用的兵种候选图 |
| `frontend/img/units/options/htank-option-B.svg` | 2,751 | 未采用的兵种候选图 |
| `frontend/img/units/options/htank-option-C.svg` | 2,751 | 未采用的兵种候选图 |
