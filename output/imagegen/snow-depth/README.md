# 雪地厚薄模型

已通过此前授权的图片 API / bundled ImageGen CLI，以 GPT Image 2 生成。三款使用相同的斜向 2:1 地块投影，经过透明底处理及边缘柔化后接入地图。

| 视觉类型 | 地图素材 | 提示词 |
| --- | --- | --- |
| 厚雪地 | ../../../frontend/img/map/snow-thick-map-embedded.png | ../snow-rock/snow.prompt.txt |
| 中等雪地 | ../../../frontend/img/map/snow-medium-map-embedded.png | medium.prompt.txt |
| 薄雪地 | ../../../frontend/img/map/snow-thin-map-embedded.png | thin.prompt.txt |
| 岩石 | ../../../frontend/img/map/wild-rock-map-embedded.png | ../snow-rock/rock-dry.prompt.txt |

同目录 models-preview.jpg 为三款模型对比图。frontend/img/map 下另有对应 WebP 图片。

雪地厚度按距雪地区域边缘的距离选择：边界为薄雪，向内一圈为中雪，更内部为厚雪；厚度与野地战斗等级无关。不会改变目标坐标、资源属性或现有的采集/建城规则。

预览页已增加 7×7 连片雪地及“查看雪地渐变”入口，中心坐标为 (95,96)。雪地和岩石已加入前后端野地类型定义；既有正式世界数据库没有在本次操作中新增地块。

验证：模型透明通道、11 类野地地图素材完整性、预览数据无重复坐标、三档雪地邻接判定、地图点击/投影/全屏/海洋测试、后端编译均通过。
