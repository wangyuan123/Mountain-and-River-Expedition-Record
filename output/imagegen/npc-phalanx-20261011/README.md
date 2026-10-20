# 日本步坦协同方阵模型重构归档 (2026-10-11)

## 一、设计目标与重构背景
当前大地图上 NPC 流寇据点使用的是中世纪城防堡垒（`npc-fortress-orthogonal.webp`），存在以下体验痛点：
1. **题材割裂**：抗战时期日寇/伪军在野战中的据点与扫荡队主要为步兵与装甲战车编组，而非石头城堡；
2. **缺乏野战对抗张力**：原城堡模型单调静态，无法直观反映日军“步坦协同推进”的压迫感与战术威胁；
3. **底座镶嵌要求**：
   - 彻底剔除原画垂直底座厚度（无高度地盘）；
   - 地表基底必须精确混合大地图的草坪基准色 `GRASS (104, 103, 71)` 与黑土基准色 `SOIL (92, 81, 62)`；
   - 边缘采用自然不规则噪声调制与柔和羽化渐变，使模型图能够浑然一体镶嵌进大地图地表。

---

## 二、3 款候选方案特征

### 方案一：双车并进 · 掩护突击方阵 (`option-1-assault.png`) 【主推】
- **前排装甲**：两辆九七式“奇哈”中型战车平推前突，炮塔右倾警戒，车身涂装带鲜明黄色识别带与铆接钢板质感；
- **后排步兵**：步兵分队紧随战车行进，包括立姿持三八大盖步枪警戒搜索的步兵、军官以及就地架设卧姿九六式轻机枪的射手；
- **地表与镶嵌**：开阔野战平原微辙，草坪与黑土以 50%:50% 斑驳交融，外缘柔和羽化渐变，在 48px/64px/128px 各级缩放下轮廓极为舒展清晰。

### 方案二：车长探身 · 黑土深辙战术方阵 (`option-2-tactical.png`)
- **前排装甲**：两辆九七式中战车战车长探身出塔指挥观察，重型履带深深碾入泥泞黑土地表；
- **后排步兵**：步兵战斗小组沿履带车辙梯次跟进，手持刺刀步枪处于高度戒备状态；
- **地表与镶嵌**：泥泞黑土占比 65%，草坪占比 35%，泥痕与车辙细节极其丰富，战术现场感与沉浸感强烈。

### 方案三：多车协同 · 密集团队强攻方阵 (`option-3-heavy.png`)
- **前排装甲**：三辆战车呈突击梯队配置，正面装甲压迫感厚重；
- **后排步兵**：步兵分队严密依附战车推进，战车与步兵高密度交织；
- **地表与镶嵌**：战术重装方阵，适合表现高等级日寇精锐机械化集群。

---

## 三、文件目录清单
- `raw/`: AI 原始模型摄影高分辨率原图（1024×1024 无缝白底）
- `options/`: 经过底座去除、色彩调优与边缘羽化处理的 384×384 规范 RGBA 透明图片
  - `option-1-assault.png`
  - `option-2-tactical.png`
  - `option-3-heavy.png`
- `preview-options.png`: 3 款方案在深色背景、大地图草坪、大地图黑土及多视口尺度下的比选总览表
- `comparison-old-vs-new.png`: 原流寇城堡与 3 款步坦方阵的同屏大地图实装效果对照
- `npc_all_phalanxes_showcase.png`: 5 款全新正方形寇方阵模型全家福展示图
- `npc_all_phalanxes_square_full_comparison.png`: 45°原图菱形 vs 最新正方形占满空格微缩模型全阶段比对长图

---

## 四、2026-10-19 全新兵种组合微缩模型方阵重构规范

根据用户最新指令，彻底删除并摒弃旧版 NPC 图标，执行全新重构：
1. **彻底消除兵种截断问题**：
   - 摒弃以往将单张透视照片进行拉伸裁切的错误方案；
   - 采用标准 384×384 画布，所有步兵、特种兵、装甲车、轻坦、重坦、火箭车、战斗机、轰炸机均采用独立完整的微缩模型渲染；
   - 单位模型严格受安全边距保护（内缩至少 20~35px），确保士兵身躯、武器枪械、坦克炮管、履带、战机双翼 100% 完整展现，绝对无任何截断或肢体残缺；
2. **采纳【方案一：纯透明背景 + 真实地表接触投影】（自然无痕嵌入各色地貌）**：
   - 彻底剔除固定草绿色彩色底板，画布全透明（Alpha=0），彻底杜绝在黑土地、深草坪、雪地等不同地貌上的矩形接缝与色块脱节；
   - 战车履带与士兵脚下渲染高拟真**暗色接地接触阴影（Contact Shadow）**；
   - 战机凌空飞掠并在地表投射漫反射高度阴影，立体纵深感鲜明；
3. **严格对应后端 LandNpcGarrison 随机编队规则**：
   - 生成 13 种全部真实出现的随机兵种组合微缩方阵：
     - `npc-phalanx-infantry`: Lv.1~5 纯步兵方阵
     - `npc-phalanx-armored-infantry`: Lv.6~10 步装突击协同方阵
     - `npc-phalanx-armored-infantry-ltank`: Lv.11~24 步坦装突击方阵 (组0)
     - `npc-phalanx-armored-fighter-infantry`: Lv.11~15 步装战机协同方阵 (组1)
     - `npc-phalanx-armored-fighter-infantry-ltank`: Lv.11~24 全维突击方阵 (组2)
     - `npc-phalanx-armored-bomber-fighter-infantry`: Lv.16~24 步装双空战术方阵 (组1)
     - `npc-phalanx-armored-bomber-infantry-ltank`: Lv.16~24 装甲重轰支援方阵 (组3)
     - `npc-phalanx-bomber-fighter`: Lv.25~30 战巡空袭大队 (高阶0)
     - `npc-phalanx-ltank-rocket`: Lv.25~30 战车火箭炮兵群 (高阶1)
     - `npc-phalanx-armored-fighter-htank-infantry`: Lv.25~30 重机械化装甲军团 (高阶2)
     - `npc-phalanx-fighter-infantry-special`: Lv.25~30 特种伞降航空队 (高阶3)
     - `npc-phalanx-armored-bomber-ltank`: Lv.25~30 装甲轻坦轰炸队 (高阶4)
     - `npc-phalanx-armored-htank-rocket`: Lv.25~30 重甲火箭重装旅 (高阶5)
   - 同步兼容 5 大经典基准别名（`npc-phalanx-infantry`, `npc-phalanx-light-armor`, `npc-fortress-orthogonal`, `npc-phalanx-aircraft`, `npc-phalanx-mixed`, `npc-fortress`）；
   - 前端 `world-map.js` 支持根据 NPC 真实守军 `t.army` 或坐标随机种子 `|x * 31 + y|` 精准呈现对应的兵种组合微缩模型方阵。




