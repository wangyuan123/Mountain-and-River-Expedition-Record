# 玩家城市图标重设计方案与提示词

日期：2026-10-09。阶段：设计提案与提示词准备，尚未生成、替换或接入资源。

## 1. 设计方向

推荐方向：**近代街区式军政重镇**。

从零设计一座有居民生活、军事生产、军政管理、陆军集结与空军驻泊的完整城市。精美感来自建筑结构、装备形态、材质和街区秩序，而不是增加高楼、装饰塔或重复建筑。

建筑与装备沿用游戏当前近代战争题材。兵种造型以游戏已有配置为依据，属于游戏架空组合，不作为某年某支真实部队的历史复原。

本轮重新设计轮廓、布局、建筑和细节，不使用现有城市图片作为生成参考。旧方案中的高楼中心、城堡式塔楼、菱形围城、护城河和浮空模型底座不作为新设计的约束。

## 2. 构图与视角

- 地表为接近正方形的完整城市占地，适配玩家城市 2×2 格。
- 上北下南、左西右东，横向道路水平，纵向道路向画面上方延伸；城市不旋转 45 度。
- 正交相机从城市南侧俯视，仰角约为水平面以上 75 度，不采用透视镜头。主要看见屋顶，同时保留少量南向立面。
- 建筑以 1～3 层为主，市政厅约 4 层，为最醒目的建筑。雷达与通信桅杆细而高，不形成遮挡机场的高楼群。
- 外围采用低矮砖石与混凝土防线、门岗和少量观察哨。防线服务于城市轮廓，不能遮挡前排部队。
- 主干道、街区支路、军用车辆通道与机场滑行道形成连续网络；住宅不占跑道净空，车辆不占停机位。
- 留出约 5%～7% 画布边距，全部建筑、车辆、飞机、雷达和阴影完整入画。

概念布局示意，表示分区关系，不是最终建筑数量或严格尺寸：

```text
                         北 / 画面上方
┌──────────────────┬────────────────────┬─────────────────────┐
│ 民居街区、庭院    │ 军工厂、装配与装卸区│ 雷达、机库、机场塔台│
├──────────────────┼────────────────────┤                     │
│ 民居、生活街巷    │ 市政厅与市民广场    │ 停机坪：战斗机      │
│                  │                     │                     │
│                  │ 参谋部  /  司令部   │ 停机坪：轰炸机      │
├──────────────────┼────────────────────┤                     │
│ 步兵方阵         │ 坦克方阵            │ 停机坪：侦察机      │
│ 装甲车方阵       │ 反坦克火箭车队            │ 滑行道与完整跑道    │
└──────────────────┴────────────────────┴─────────────────────┘
               南 / 画面下方，主入口和军用通道
```

机场占内部可用面积约 28%～32%，陆军集结区约 18%～22%；其余分配给住宅、军政建筑、军工厂与道路。比例是构图目标，可在初稿中调整，必须保留所有指定设施。

## 3. 必须呈现的设施和装备

以下数量为美术排布目标，不表示玩家真实建筑等级、驻军规模或机场容量。画面中的编队属于城市图标的固定美术内容。

| 内容 | 排布目标 | 识别特征与细节 |
| --- | --- | --- |
| 民居 | 12～16 栋，组成 2～3 个连续街区 | 1～3 层灰白抹灰或砖墙，灰瓦与少量暗红瓦屋顶；窗框、门廊、烟囱、院墙、生活小巷；不做成整齐重复的兵营 |
| 军工厂 | 2 个主车间、1 个小附属车间 | 红砖墙、钢架锯齿采光顶、装配大门、装卸院；可见一处车辆装配或检修工位，工业特征不能只靠烟囱表达 |
| 市政厅 | 1 栋，位于中部广场 | 浅灰石材或浅色抹灰、对称主立面、入口台阶、低矮钟楼或钟面；周围保留开放广场，主建筑不过度拔高 |
| 参谋部 | 1 栋独立建筑 | 2～3 层长条办公楼，有平行翼楼、整齐窗列、地图与联络工作的外部空间暗示；不能与市政厅、司令部合并 |
| 司令部 | 1 组独立建筑 | 厚实的低层指挥楼、门前军用小广场、门岗、无线电桅杆与配套电台建筑；轮廓与参谋部明显不同 |
| 雷达 | 1 座，靠近机场北端 | 近代金属格栅雷达天线、转动支架和独立设备小屋，完整露出天线；避免现代白色球形雷达罩 |
| 步兵方阵 | 2 个方阵，每个约 4×6 人 | 成列成行、间距明确，头盔与步枪轮廓可见；位于独立操场，不能散落成无序人群 |
| 反坦克火箭车队 | 4～6 辆，2 列待命 | BM-13 喀秋莎式卡车底盘和外露发射导轨；不能画成普通货车或现代箱式导弹车 |
| 坦克方阵 | 6 辆轻坦与 3 辆重坦，分成 2 组 | 轻坦参考 M5A1，重坦参考 IS-2；履带、炮塔、炮管分明，重坦稍大；炮口一致朝向空旷场地 |
| 装甲车方阵 | 6 辆，独立于坦克停放区 | M8 灰狗与 T17E2 猎鹿犬防空型各约 3 辆；轮式底盘清楚，M8 六轮与防空车双联武器可在大图中识别 |
| 飞机场 | 1 个连续机场分区 | 完整跑道、连接停机坪的滑行道、机库、塔台；跑道无遮挡、滑行路径真实连通；不是在广场上摆几架飞机 |
| 停放的战斗机 | 约 4 架 | P-51 式单发动机螺旋桨战斗机；小巧机身、单尾、窄机翼，单独机位与间距 |
| 停放的轰炸机 | 约 2 架 | B-17G 式四发动机螺旋桨轰炸机；明显更大、更宽的翼展和四个发动机短舱；保留机翼完整轮廓 |
| 停放的侦察机 | 约 2 架 | P-38 衍生 F-5 照相侦察型；双尾撑、双发动机、中央驾驶舱吊舱；不是战后 F-5 喷气机 |

所有飞机静止停放在机场内，起落架落地，不悬浮、不飞过城市。轰炸机、战斗机和侦察机分组停车，翼尖不重叠、不进入建筑。

生活与后勤细节只补充必要内容：少量居民、厂区工人、道路车辆、机场地勤、军用卡车、维修工具和植被。不要用大量树木、旗帜、烟雾掩盖缺失设施。

## 4. 美术质感

采用写实微缩城市渲染，保留适度的游戏资产概括。建筑边缘、屋顶结构与装备轮廓清楚，避免玩具塑料质感、低模棱面、厚描边和重度油画笔触。

主色组合为浅灰石材、暗红砖、灰蓝金属屋顶、少量陶瓦、军绿装备和自然绿色植被。水泥道路与停机坪略偏中性灰，飞机通过金属灰和军绿形成层次。不能整座城市都染成同一种军绿、土黄或深灰。

左上方柔和日光，阴影短而清晰，轻度环境遮蔽表现接触关系。立面和阴影内部仍有细节；不使用夜景、浓雾、强景深、夸张高光、爆炸或战斗烟尘。

精细化优先顺序：建筑与装备轮廓、分区和街道关系、机翼与车身结构、材质差异，最后才是窗格、地勤、管线等微小细节。

## 5. 出图提示词

### 5.1 主方案完整提示词

以下是一条独立完整的生成提示词。旧城市图片不作为输入参考图。

```text
Use case: stylized-concept
Asset type: one complete player-city map asset for a WWII-inspired strategy game.

Primary request:
Design a brand-new, richly detailed and beautiful living military-administrative city from scratch. Show residential neighborhoods, an armaments factory, a city hall, a separate general staff building, a separate military command headquarters, a radar installation, organized infantry formations, rocket-launcher truck formations, tank formations, wheeled armored-car formations, and a complete airfield with PARKED fighters, bombers and reconnaissance aircraft. Every category must be visibly present in the same coherent city.

Camera and framing:
One city, one square canvas, the entire city visible with a 5-7 percent clear margin. Use an orthographic camera looking north from the south at approximately 75 degrees above the horizontal. North is at the top. East-west roads run horizontally across the image, north-south roads extend vertically into the image. Zero horizontal camera rotation, no 45-degree diamond orientation. The ground footprint is approximately square with minimal foreshortening. Show mostly roofs and a small amount of south-facing building facades. Keep buildings low enough that the interior, formations and parked aircraft remain visible.

City organization:
Make an organized, continuous street plan with modest-height perimeter defenses and readable entrances. Place residential blocks in the western part, an armaments industrial quarter in the north-central part, a civic and command district in the center, open ground-force assembly yards in the southern part, and a continuous airfield occupying roughly the eastern third. Leave functional roads and open spaces between districts. The airfield must be a real connected airfield within the city footprint, with an unobstructed runway, taxiway, parking aprons, hangars and a control tower. Keep buildings and vehicles clear of the runway.

Residential and industrial architecture:
Include approximately 12-16 individually modeled one- to three-story homes arranged into connected streets and courtyards. Use pale plaster, brick, gray tiled roofs and a few subdued red-tiled roofs. Show doors, windows, chimneys, porches and small lived-in yards. Include two main armaments factory halls and a smaller workshop with red brick walls, steel sawtooth roofs, loading doors, a loading yard and a visible vehicle-maintenance or assembly bay.

Three distinct administrative buildings:
The city hall is the main architectural focal point: a dignified, medium-height civic building with a pale stone facade, symmetrical entrance, steps and a modest clock feature, standing on an open central square. The general staff building is a SEPARATE lower, elongated office complex with ordered window rows and parallel wings. The military command headquarters is a THIRD SEPARATE low, substantial guarded building with a military forecourt, a radio mast and a small signals annex. Give these three buildings clearly different silhouettes. Do not merge them into one palace or tower.

Radar and ground forces:
Place a clearly exposed WWII-era metal lattice radar antenna on a support frame with a small equipment hut near the northern airfield edge. In separate open assembly yards show two compact rectangular infantry formations, approximately four ranks by six files each, with individual upright helmeted soldiers and rifles. Show four to six BM-13 Katyusha-style rocket trucks in orderly rows, with recognizable exposed launcher rails. Show a formation of six M5A1-style light tanks and a separate group of three larger IS-2-style heavy tanks, with clear tracks, turrets and barrels. Show six wheeled armored cars in their own formation: M8 Greyhound-style six-wheeled reconnaissance cars and T17E2 Staghound-style armored cars with twin anti-aircraft guns. Keep infantry, rocket trucks, tanks and armored cars in distinct groups with visible gaps. Vehicles must not touch or merge.

Parked aircraft, all three types required:
On the airfield parking aprons show approximately four P-51-style single-engine propeller fighters, two visibly larger B-17G-style four-engine propeller bombers, and two P-38-derived F-5 photo-reconnaissance aircraft with twin booms, twin engines and a central cockpit pod. F-5 means the WWII P-38 reconnaissance conversion, NOT the postwar F-5 jet. Preserve the relative aircraft sizes. Arrange each type in separate parking positions with wingtip clearance. All aircraft have landing gear on the ground, wings fully visible, and clear silhouettes. No aircraft is airborne or placed on city roofs, roads or civic squares.

Style, materials and lighting:
Premium realistic miniature-city rendering with crisp geometry, fine architectural relief and convincing brick, plaster, stone, roof tiles, steel, concrete and vehicle materials. Restrained stylization suitable for a strategy-game map. Balance pale stone, dark red brick, gray-blue roofs, olive-drab vehicles, neutral gray airfield paving and small natural green planted areas. Soft clear daylight from the upper left, short readable shadows, gentle ambient occlusion. Keep shadow details visible. Add a few correctly scaled civilians, workers, ground crew and service vehicles only where they support the existing districts. Tiny people, vehicles and aircraft must feel consistent in scale.

Output and constraints:
One complete city asset on a genuinely transparent background with preserved alpha. The city sits directly on a thin ground surface; only the buildings and equipment have height. Clean natural ground edges, subtle contact shadows, no raised slab, no cut-earth block, no pedestal or floating island. No UI, labels, lettering, numerals, faction logos, watermark, collage, inset views, explosions or smoke clouds. Runway threshold stripes and centerline markings may be simple unnumbered geometric markings. No medieval castle, moat, skyscraper cluster, science-fiction architecture, modern jets, missiles in box launchers, oversized decorative towers or flying aircraft. No cropped wings, merged vehicles, obscured formations, plastic toy surfaces, blurry details, heavy depth of field or monochromatic color grading.
```

### 5.2 两个可选建筑风格变体

首轮优先生成主方案。需要对比时，在主提示词后追加下面其中一个段落；两个变体都保留相同相机、布局、设施、装备与光线，不删减用户要求的内容。

**变体 B：砖石工业军城。** 更突出军工生产和厚实的城市结构。

```text
Architectural variation: strengthen the early-20th-century brick-and-stone industrial character. Use richly modeled dark red brick factory halls, pale stone civic entrances, slate-gray roofs, steel roof trusses and restrained utilitarian details. Residential streets must still feel inhabited and attractive. Keep the city hall, general staff building and command headquarters separate and readable. Preserve the full eastern airfield, every required formation, all three parked aircraft types, the orthographic camera and the balanced daylight palette.
```

**变体 C：浅石军政新城。** 更突出清晰、整洁的军政核心与住宅层次。

```text
Architectural variation: emphasize a refined early-20th-century civic city with pale gray stone, light plaster, subdued terracotta and gray-blue tiled roofs. Use attractive small residential courtyards and modest tree-lined streets without hiding military equipment. The armaments factory remains clearly industrial with brick walls and sawtooth roofs. Keep the city hall, general staff building and command headquarters separate and readable. Preserve the full eastern airfield, every required formation, all three parked aircraft types, the orthographic camera and the balanced daylight palette.
```

### 5.3 局部纠错提示词

生成模型不保证严格兑现数量和空间关系。发现缺项后，每次只修一个区域，并在修正后复核整座城市的必需内容。

**机场缺少一种飞机、飞机误飞行或外形混淆：**

```text
Edit only the existing airfield parking aprons. Keep the city boundary, camera, buildings, streets, runway, lighting and all ground-force formations unchanged. Make three clearly separated groups of PARKED aircraft: single-engine P-51 fighters, larger four-engine B-17G bombers, and twin-boom P-38-derived F-5 photo-reconnaissance aircraft. Keep all wheels on the apron and all wings unobstructed, with wingtip clearance and consistent relative sizes. Remove airborne aircraft. Preserve the transparent background and all other city details. Do not add modern jets or change the image framing.
```

**参谋部、司令部与市政厅被合并：**

```text
Edit only the central administrative district. Preserve the separate city hall on its civic square, and provide two additional independent buildings: a lower elongated general staff office complex and a substantial guarded military command headquarters with a radio mast. Make all three silhouettes distinct and visible without increasing the overall skyline height. Keep the residential districts, factory, airfield, radar, every formation, camera, ground boundary, lighting and transparent background unchanged. No new towers, labels or lettering.
```

**地面编队混杂、反坦克火箭车变普通卡车或装甲车变坦克：**

```text
Edit only the southern ground-force assembly yards. Preserve every building, the entire airfield, all aircraft, radar, city boundary, camera and lighting. Separate the yard into orderly infantry rectangles, tracked tank formations, BM-13-style rocket trucks with visible open launcher rails, and wheeled M8/T17E2-style armored-car formations. Keep clear gaps between every vehicle and between each formation. Soldiers stand upright in ranks and files. Show wheels on armored cars and tracks on tanks. Preserve the transparent background. Do not add smoke, firing effects or vehicles on the runway.
```

## 6. 分辨率与地图接入注意事项

现行源码中，玩家城市的占地为 2×2 格，相机初始比例为每格 44 像素，放大上限为每格 88 像素，所以模型显示宽度约为 88～176 像素。这是整体模型宽度，不是每架飞机或每栋建筑的宽度。

必须分别验收三个层级：

| 显示层级 | 验收目标 |
| --- | --- |
| 约 88 像素宽的普通地图 | 城市整体轮廓、军政核心、工业区、机场及陆军集结区有清晰层次；避免缩小后成为一团细纹 |
| 约 176 像素宽的放大地图 | 雷达、不同地面编队与停机坪仍有独立轮廓；不承诺可辨认每名士兵或每架飞机的发动机数量 |
| 512～1024 像素宽的详情或素材检查 | 所有指定设施存在，飞机类别可区分，军工厂结构、坦克与轮式装甲车差异、士兵队列和建筑材质清晰可见 |

母图建议至少 1536×1536，优先 2048×2048，实际出图尺寸以生成工具支持为准。保留透明 PNG 母图；选稿后再制作地图透明资源和详情资源。高分辨率可以保留细节，但不会让 88 像素缩略图承载大图的全部可辨信息。

当前 `world-map.js` 对城市宽高分别赋值，还根据 `coastal` 使用不同的高度系数。新稿接入时需要按实际图像比例重新核对该计算，避免同一新模型在内陆被压扁、在沿海被拉长。计划采用统一资产比例，不通过非等比缩放弥补构图。

城市名称、归属、选中框和蓝色护盾由地图另外绘制，不画入母图。选稿后应在护盾覆盖状态下复核雷达、机场与主要建筑是否仍然可读。

## 7. 出图与验收顺序

1. 先按主提示词生成一张完整城市初稿，检查相机、总体轮廓、街区比例及所有必需内容。
2. 逐项核对第 3 节清单，尤其是三个独立军政建筑、四类地面编队和三类停放飞机。
3. 若方向成立但局部缺项，使用对应局部纠错提示词；若总体视角或布局错误，重新生成整图。
4. 检查飞机翼尖、坦克炮管、车轮、士兵队列、跑道连通、遮挡与比例；数量是目标，类别完整和排列清晰是硬性要求。
5. 在地图底色上检查透明边缘、接触阴影，并检查 88、176 和 512 像素宽缩略图。
6. 完成选稿后再准备资源替换、统一比例接入与游戏画面验收。本阶段不删除旧文件或修改运行逻辑。

设计依据：当前 `frontend/js/world-map.js`、`frontend/js/map-camera.js`、`frontend/js/map-layout.js`、`frontend/js/data.js`，以及 `README.md` 的现行时代背景。旧设计文档含历史版本信息，涉及地图投影与当前资源时以现行源码为准。
