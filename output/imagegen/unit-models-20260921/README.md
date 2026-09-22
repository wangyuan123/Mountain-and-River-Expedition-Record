# 兵种微缩模型素材

本目录保存首页军队总览使用的 17 个二战装备微缩模型原稿、提示词和验收截图。用户后续指定将步兵与特种兵改为人物战斗形象，因此当前接入版本为：

- `infantry`：标准美军步枪兵，M1 钢盔、M1 加兰德步枪、直立推进姿态。
- `special`：英国突击队员，绿色贝雷帽、面部伪装、汤普森冲锋枪、低姿突击姿态。
- 其余 15 个兵种继续使用对应坦克、车辆、飞机或舰艇模型。

游戏素材在 `frontend/img/units/models/`，统一为 384 × 384 透明 WebP。首页由 `frontend/js/data.js` 的 `UNIT_MODEL` 映射到模型，军队总览的排序、数量和详情交互保持不变。

两张人物图由用户已授权的 gpt-image-2 API 生成：`infantry-soldier.prompt.txt` 与 `special-commando.prompt.txt` 记录完整提示词，`prepare-unit-models.py` 负责白底清理、透明边缘和统一画幅。`models-dark.jpg`、`models-light.jpg` 与 `preview-*.png` 是深浅背景和多尺寸验收截图。
