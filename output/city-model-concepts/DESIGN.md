# 城市微缩模型候选设计

用途：为游戏平面大地图选择城市模型美术风格。本文件为待生成的设计说明，尚未生成图片，也未替换游戏资源。

## 统一规格

- 四款各生成一张正方形图片，同一等距俯视角度、近似主体占比，城市完整入镜。
- 二十世纪上半叶城市建筑语汇：中央指挥部、低层民居、仓库、道路、少量树木。
- 灰白墙面、灰蓝屋顶、草绿色地块为基础，适配现有地图主题。
- 城市轮廓简洁，主体集中；既观察大图质感，也评估缩小后的识别度。
- 浅暖灰纯色背景用于首轮风格对比；不添加文字、水印或阵营标识。
- 用户选定后，再制作适合地图使用的资源、不同城市等级和状态变体。

## A · 写实沙盘

重点：真实微缩模型的材质与空间层次，适合近距离查看城市。

```text
Use case: stylized-concept
Asset type: miniature city concept for a WWII-era strategy game's world map
Primary request: A carefully crafted realistic miniature city diorama, a compact central civic headquarters with a small clock tower, surrounded by six low-rise residential and warehouse buildings, short streets and a few trees.
Style/medium: premium physical architectural scale model photographed in a studio; believable miniature plaster walls, slate roofs, restrained weathering and fine craftsmanship.
Composition/framing: square image, elevated isometric three-quarter view, entire city and shallow square terrain base visible, centered and filling approximately 75 percent of the frame, generous clear margin, all buildings in focus.
Lighting/mood: soft upper-left daylight, gentle contact shadows, calm and readable.
Color palette: warm gray-white walls, muted blue-gray roofs, sage green terrain, pale warm gray plain background.
Constraints: early twentieth-century architecture; strong compact silhouette, visually dominant headquarters; no text, watermark, insignia, people, modern skyscrapers or futuristic technology. Keep detail subordinate to clear building masses.
```

## B · 精致低多边形（优先推荐）

重点：建筑体块清晰、颜色克制，缩小后仍容易识别，适合地图密集展示。

```text
Use case: stylized-concept
Asset type: miniature city concept for a WWII-era strategy game's world map
Primary request: A refined low-poly miniature city, a compact central civic headquarters with a small clock tower, surrounded by six low-rise residential and warehouse buildings, short streets and a few trees.
Style/medium: polished low-poly 3D game art, crisp geometric silhouettes, carefully simplified architecture, matte surfaces, broad clean planes, subtle bevels and ambient occlusion.
Composition/framing: square image, elevated isometric three-quarter view, entire city and shallow square terrain base visible, centered and filling approximately 75 percent of the frame, generous clear margin, all buildings in focus.
Lighting/mood: soft upper-left daylight, gentle contact shadows, calm and readable.
Color palette: warm gray-white walls, muted blue-gray roofs, sage green terrain, pale warm gray plain background.
Constraints: early twentieth-century architecture; visually dominant headquarters and clear separation between building masses, designed to remain identifiable as a small map marker; no text, watermark, insignia, people, modern skyscrapers or futuristic technology. Avoid excessive tiny windows and visual clutter.
```

## C · 复古军事

重点：厚重的指挥部和整齐的军需建筑，突出战争策略题材。

```text
Use case: stylized-concept
Asset type: miniature city concept for a WWII-era strategy game's world map
Primary request: A vintage military strategy miniature city, a prominent period command headquarters with a small observation tower, surrounded by six compact low-rise barracks, civilian houses and supply warehouses, connected by short streets with a few trees.
Style/medium: detailed painted tabletop miniature, restrained hand-painted shading, sturdy masonry structures, period pitched roofs, subtle worn edges, serious and grounded art direction.
Composition/framing: square image, elevated isometric three-quarter view, entire city and shallow square terrain base visible, centered and filling approximately 75 percent of the frame, generous clear margin, all buildings in focus.
Lighting/mood: soft upper-left daylight, gentle contact shadows, calm and readable.
Color palette: warm stone-gray walls, muted blue-gray roofs, subtle olive accents, sage green terrain, pale warm gray plain background.
Constraints: early twentieth-century architecture; compact recognizable silhouette, no battle scene, smoke, weapons display, text, watermark, faction insignia, people, modern skyscrapers or futuristic technology. Avoid muddy dark colors.
```

## D · 清爽卡通

重点：柔和圆角和轻快色彩，手机屏幕上更亲切、清晰。

```text
Use case: stylized-concept
Asset type: miniature city concept for a WWII-era strategy game's world map
Primary request: A charming clean stylized miniature city, a compact central civic headquarters with a small clock tower, surrounded by six low-rise residential and warehouse buildings, short streets and a few trees.
Style/medium: high-quality stylized 3D mobile strategy game art, softly beveled forms, slightly exaggerated roofs and landmark proportions, matte clay-like materials, minimal surface detail, polished and coherent rather than toy clutter.
Composition/framing: square image, elevated isometric three-quarter view, entire city and shallow square terrain base visible, centered and filling approximately 75 percent of the frame, generous clear margin, all buildings in focus.
Lighting/mood: soft upper-left daylight, gentle contact shadows, welcoming and readable.
Color palette: warm gray-white walls, soft blue-gray roofs, fresh sage green terrain, pale warm gray plain background; moderate saturation.
Constraints: early twentieth-century architecture; strong compact silhouette, visually dominant headquarters; no text, watermark, insignia, people, modern skyscrapers or futuristic technology. Avoid neon colors, exaggerated fantasy architecture and dense micro-detail.
```

## 生成状态

尚未执行生成：本次会话没有可调用的内置 image_gen。若用户确认采用备用 API 方式，使用 imagegen 技能提供的 CLI 和以上四个独立提示词生成，需本机配置 OPENAI_API_KEY。不得将本设计说明表述为已生成的图片。
