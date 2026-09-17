# A · 花园卫城建筑模型

2026-09-17 用户选择 A，覆盖军事区 17 栋与资源区 4 栋建筑。

- 原稿：`output/imagegen/realistic-buildings-20260917/a-garden/`，由 gpt-image-2 经用户授权的兼容 API 生成；提示词与原始 PNG 一并保留。
- 游戏素材：本目录 21 张 384 × 384 透明 WebP，总计约 1.1 MiB。保留原图视角与真实材质，移除摄影棚背景，统一留白；雷达栅格单独处理透明度。
- 制作脚本：`scripts/assets/prepare-building-models.py`，依赖 Pillow、numpy、opencv-python-headless。从仓库根目录运行 Python 脚本即可重建，不访问 API。
- 映射：`frontend/js/build.js` 的 `BUILD_ICON`。建筑卡槽、选建列表、详情弹窗共用这套素材；顶部资源数量继续用原资源符号。
- 显示尺寸：卡槽 84 px、选建 64 px、详情 88 px、旧式展开卡片 52 px；卡槽宽度下限 104 px、高度 148 px。
- 独立预览：`frontend/tests/building-model-preview.html`，使用真实前端渲染器和示例数据，不发送玩家操作。
- 验证：33 项建筑/客户端测试通过；浏览器验证桌面、375 px 与 320 px 布局、深色主题、建筑详情与选建列表，无丢失图片或卡槽溢出。
- 深浅背景素材总览与页面截图：`output/imagegen/realistic-buildings-20260917/integration/`。
