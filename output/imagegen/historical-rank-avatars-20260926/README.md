# 历史风格军衔人物微缩头像

状态：已全量完成 8 款 **3D 策略游戏角色微缩头像（非真人/战术暗色蜂窝底）** 原稿生成及游戏 WebP 导出。
- 已生成原稿（1024×1024 PNG，保存在当前目录）：`rank-private-v1.png` 至 `rank-general-v1.png`（8 款）。
- 已导出游戏资源（384×384 WebP，保存在 frontend/img/avatars/historical/）：
  `rank-private-v1.webp`（列兵）、`rank-corporal-v1.webp`（下士）、`rank-sergeant-v1.webp`（上士）、
  `rank-lieutenant-v1.webp`（少尉）、`rank-captain-v1.webp`（上尉）、`rank-major-v1.webp`（少校）、
  `rank-colonel-v1.webp`（上校）、`rank-general-v1.webp`（上将）。
- 风格重构说明：响应用户反馈，彻底摒弃可能引发侵权风险的真人摄影与写实毛孔面孔，全面采用 **3D 次世代游戏角色数字建模渲染（Game CG Asset）** 风格。统一暗色战术六边形蜂窝底，通过钢盔军衔标记（素面、单折角、三折角、单银杠、双银杠、金橡叶、银鹰、三银星）与立挺军服严格区分 8 阶军衔体系。

## 美术规格

- 8 款独立头像：列兵、下士、上士、少尉、上尉、少校、上校、上将。
- 视觉风格：3D 经典战略游戏数字角色立绘（非真人，避免肖像侵权）。
- 背景：统一暗色战术六边形网格背景（Dark Hexagonal Mesh），纯净无文字与杂乱符号。
- 构图：正方形 1024×1024，3/4 侧脸英雄半身像，钢盔至上胸部。
- 军服体系：二战卡其/橄榄绿挺括常服、领章与钢盔军衔符号严格对齐阶级。
- 交付规格：原稿 1024×1024 PNG，游戏端 384×384 WebP（体积 ~20KB/张）。

## 生成记录

通过 HandAI 官方接口以 `gpt-image-2`（quality=high）多线程并发完成批量生成并自动缩放转换为 WebP 资源。更新与生成脚本位于 `scripts/assets/generate-3d-avatars.py`。

## 接入方案（尚未执行）

1. 检查面部、比例、服装时代感和缩小后的可辨识度，并制作总览。
2. 新建 historical 子目录资源，更新 frontend/js/constants.js 的预设列表与默认头像。
3. 统一 main-view.js、core.js、player-profile.js 的默认值和错误回退，并兼容旧 SVG 选择。
4. GameController 的白名单目前仅接收 commander-1.svg 至 commander-8.svg，需同步放行新资源的精确路径。
5. 验证头像选择、保存、旧值映射、非法地址拒绝及 44px、72px 的显示效果。

现有头像与运行时代码保持原状，待真实图片完成后接入，避免空白头像或保存失败。
