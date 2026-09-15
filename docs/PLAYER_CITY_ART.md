# 玩家城市地图素材（2026-09-15）

陆地玩家城市采用花园卫城 C 修正版；真实临海城市采用已选的滨海港口城。地图图标和目标详情共用 coastal 字段选择规则，包括自己的主城、分城和其他玩家城市。legacyNaval 不代表临海。

花园卫城原稿：output/imagegen/fortified-cities-20260914/c-garden-citadel-v2.png。
地图资源：frontend/img/cities/garden-citadel-map-embedded.png。
详情资源：frontend/img/cities/garden-citadel.webp。
港口城沿用 frontend/img/cities/harbor-map-embedded.png 和 harbor.webp。

保留玩家城市 2×2 占地与点击规则。花园卫城透明蒙版及地面投影由 scripts/assets/prepare-garden-city.py 制作，原稿保留。

地图、占地、点击和海洋相关 24 项测试通过。本地浏览器确认两种城市同时显示，点击花园卫城打开正确详情。修改前文件位于 docs/archive/player-city-types-before-20260915/。
