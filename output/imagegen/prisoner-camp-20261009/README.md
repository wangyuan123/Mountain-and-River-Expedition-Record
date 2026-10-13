# 战俘营微缩模型

设计方向：沿用 A「花园卫城」的写实建筑微缩风格。以低矮营房、围栏、双岗楼和门卫室形成清晰轮廓，用收容院落体现建筑功能。奶油色灰泥墙、红陶瓦、风化木材与灰绿色金属呼应军需仓库及军校。

## 建筑构成

| 部位 | 外观设计 | 功能辨识 |
| --- | --- | --- |
| 营房 | 两排单层长条营房，红陶坡屋顶、米色灰泥墙、深窗洞、石砌基脚 | 表现成批收容战俘的住宿设施 |
| 院落 | 营房之间留出小型集合场，采用沙土与少量石铺地 | 表现点名、收容与整编的活动空间 |
| 围栏 | 木材或金属立柱、铁丝网围栏，顶部两道细铁丝 | 形成完整封闭边界，突出战俘营用途 |
| 岗楼 | 对角设置两座木构岗楼，支撑斜撑、小型瞭望室、坡屋顶 | 提供有辨识度的高点，控制高度，保持营房主体地位 |
| 入口 | 双扇木质金属营门、小型门卫室 | 清楚交代院落入口与看守设施 |
| 附属物 | 少量长凳、水桶、整齐码放的袋装物资 | 补充生活与收容细节，避免遮挡主要轮廓 |

## 画面与材质

- 相机：与现有建筑一致的约 35° 俯视、三分之四等距视角，显示正面与右侧。
- 构图：方形画幅，一块紧凑浅地坪容纳整个营区；主体占画面约 80%，四周至少留 9% 安全边距，岗楼和围栏完整入镜。
- 光照：左上方柔和日光，暖色高光、冷色阴影，细节清晰，全景合焦。
- 材质：米色石墙、低饱和红陶瓦、风化棕色木材、灰绿色金属、沙色地面。围栏较墙体深，保证缩小后边界仍可辨认。
- 时代：1930—1940 年代军用营区，设施朴素实用，适度风化。
- 小图优先：先保证“营房＋围栏＋岗楼”的轮廓辨识，再补充瓦片、木结构和门窗细节。
- 画面限制：不出现文字、旗帜、徽记、悬浮符号、巨型锁链、人物、现代监控或现代监狱设施；外部背景不带地面投影，便于透明素材处理。

## 制作规格与交付成果

- 建筑 ID：`prisoner_camp`
- 生成提示词：[prompt.txt](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/output/imagegen/prisoner-camp-20261009/prompt.txt)
- 生成脚本：[generate-prisoner-camp.py](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/scripts/assets/generate-prisoner-camp.py)
- 后处理脚本：[prepare-prisoner-camp.py](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/scripts/assets/prepare-prisoner-camp.py)
- 生图原稿：[prisoner_camp.png](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/output/imagegen/prisoner-camp-20261009/raw/prisoner_camp.png) (1254 × 1254)
- 游戏素材：[prisoner_camp.webp](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/img/buildings/garden/prisoner_camp.webp) (384 × 384，透明背景，主体居中 368 × 308)
- 接入位置：[build.js](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/js/build.js) 中的 `BUILD_ICON`，共用于军事卡槽、选建列表与详情弹窗。
- 验收成果：
  - 深色背景验收长图：[preview-dark.jpg](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/output/imagegen/prisoner-camp-20261009/preview-dark.jpg)
  - 浅色背景验收长图：[preview-light.jpg](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/output/imagegen/prisoner-camp-20261009/preview-light.jpg)
- 验收结果：
  - 64 px（选建列表）、84 px（建筑槽位）、88 px（详情视图）下双排营房、对角木构岗楼、铁丝网围栏与大门均清晰可辨；
  - 深色与浅色背景下边缘干净平滑，无白斑、白边或地台厚度残留；
  - 建筑比例、红陶瓦坡顶、米色灰泥石墙、木料与浅地坪完全契合花园卫城基准（军需仓库）；
  - 单元测试 [build.test.cjs](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/tests/build.test.cjs) 与 [prisoner-camp-model.test.cjs](file:///Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains/frontend/tests/prisoner-camp-model.test.cjs) 100% 通过。

