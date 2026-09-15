# NPC 城市与野地图标候选

3 款 NPC 城市、7 类实际野地。沿用已选 D 款的半写实立体沙盘风格。

生成方式：imagegen 备用 CLI，用户指定第三方兼容 API，gpt-image-2。请求规格：1024 × 1024，high，PNG。

当前状态：10 张图已生成，已输出两张分类对比图、64 像素缩略检查图和 384 像素 WebP 资源。图片含浅灰背景。用户选定 NPC B；NPC B 及全部七类野地图标已接入地图、详情和对应列表显示。正式轻量资源位于 frontend/img/map/。

每张完整提示词见同名 .prompt.txt，批次提示词见 prompts.jsonl。

- NPC A · 边境驻军城：npc-frontier.png
- NPC B · 重防要塞城：npc-fortress.png
- NPC C · 军需铁路城：npc-logistics.png
- 森林：wild-forest.png
- 丘陵：wild-hill.png
- 沼泽：wild-swamp.png
- 粮田：wild-grainfield.png
- 炼铁厂：wild-ironworks.png
- 油田：wild-oil.png
- 稀矿厂：wild-rarefactory.png


对比图：npc-cities-comparison.jpg、wild-icons-comparison.jpg。
小图检查：map-size-preview.png。
轻量资源：web/。
实际返回尺寸见 image-metadata.json。
