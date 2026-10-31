# 海洋城市微缩模型候选

状态：四款设计与提示词已准备，用户已同意 CLI/API 生图。项目 `.env` 中配置的两版 `OPENAI_API_KEY` 均被图像 API 以 HTTP 401 `invalid_api_key` 拒绝；尚未生成图片或接入游戏。凭证问题解决后可直接用下方命令重试。

## 生成配置

批量输入：同目录 `batch.jsonl`。四个独立任务使用 `gpt-image-2`、1024×1024、high 质量、PNG，浅灰背景用于选型；不会覆盖现用城市资源。

配置有效的 `OPENAI_API_KEY` 后，在仓库根目录执行：

```sh
python3 /Users/chenjuan/.codex/skills/.system/imagegen/scripts/image_gen.py generate-batch --input output/imagegen/ocean-city-concepts-20261030/batch.jsonl --out-dir output/imagegen/ocean-city-concepts-20261030 --concurrency 2 --max-attempts 1 --no-augment
```

该命令使用技能提供的原始 CLI，四款完整提示词保存在批量输入内。候选输出分别为 `a-sheltered-inner-harbor.png`、`b-twin-basin-naval-city.png`、`c-naval-shipyard-city.png`、`d-island-naval-citadel.png`。

## 共同设计基准

- 参考当前 `frontend/img/cities/player-city-lv10.png` 的写实微缩质感、正面高俯视角、石砌防御体系及蓝金屋顶，不采用历史已废弃的民用港口城风格。
- 本轮先比较四款高阶海洋城市的布局方向，选定后再扩展城墙 Lv.1～2、3～4、5～6、7～8、9、10 六档。
- 每款都是一张完整的独立城市图标，军港属于城市内部空间，至少可辨认一艘战列舰和一艘航母。
- 水面、码头、住宅、海军司令部和防御工事形成连续空间。军舰漂浮在水中并有连通外海的航道，不摆在街道、屋顶或封闭水池里。
- 先制作浅灰背景候选图，便于比较；正式游戏透明素材另行制作与检查。

## 四款候选

| 编号 | 名称 | 主要布局与辨识点 |
| --- | --- | --- |
| A | 环抱内港城 | U 形石砌城市包围中央军港，航母和战列舰并列停泊，前方开放港口入口；蓝金海军司令部位于后方高处。 |
| B | 双湾海军城 | 中央陆地城区将港区分成两湾，一湾泊航母、一湾泊战列舰，形成清楚的双舰轮廓；港湾均向前方外海敞开。 |
| C | 船坞工业城 | 军港与造船工业并重，带大型船坞、钢架吊机、红砖船厂；航母停在开放港池，战列舰停在进水船坞。 |
| D | 海上要塞城 | 紧凑岛城与防波堤围成受保护军港，低矮海防炮台形成轮廓，蓝金核心建筑拔高；舰船完整露出，港口有宽阔出入口。 |

## 通用提示词

将本段分别与 A、B、C、D 的专用提示词组合，每次生成一张独立图片。

```text
Use case: stylized-concept.
Asset type: one premium miniature ocean-city map icon for the military strategy game Strategies of the Flaming Plains.
Primary request: design one complete fortified naval city with an integrated military harbor, a clearly recognizable battleship and a clearly recognizable aircraft carrier moored INSIDE the city harbor. The result must read as a city with a naval base, with substantial inhabited urban architecture as well as ships and water.
Style reference: the project's current high-tier player-city art uses richly detailed limestone fortifications, dignified monumental headquarters, deep blue roofs and restrained gold architectural trim, realistic miniature materials, organized inner districts and crisp military equipment. Translate that visual language into naval architecture. Produce a new naval-city composition rather than reproducing the land-city layout.
Camera and framing: square canvas, single complete city, elevated near-orthographic front view looking inward across the harbor entrance, mostly roof and deck views with enough facades to show depth. Match the current asset's frontal orientation, no 45-degree diamond rotation. Keep the entire skyline, coastline, breakwaters, cranes, masts and ship hulls visible with approximately 8 percent clear margin. Compose strong readable masses for a small strategy-map icon while retaining detail when enlarged.
Required subjects: a prominent navy headquarters, smaller residential blocks, naval workshops and quay warehouses, connected streets, stone sea defenses, an open navigable harbor mouth, and at least one battleship and one aircraft carrier. Use a 1930s-1940s-inspired fleet: battleship with a long hull, distinct heavy gun turrets and superstructure; aircraft carrier with a long unobstructed flight deck, a small island superstructure and a few parked propeller aircraft. Keep both vessels fully visible with recognizable separate silhouettes. Harbor basins, piers, ships and buildings must have believable relative scales. Ships float in water with visible hull-water contact and mooring space. Piers connect to land; water channels connect to the harbor mouth. Use selective game-map compression without making ships tiny props or giant objects that overwhelm the whole city.
Materials and light: finely modeled warm gray stone, cream limestone, blue-gray and deep blue roofs, restrained gold accents, muted red brick workshops, gray steel warships, dark timber flight deck, gently rippled blue-green harbor water. Soft daylight from upper left, short readable shadows, subtle ambient occlusion, sharp all-in-focus rendering.
Background: clean pale warm-gray studio background outside the compact city-and-water footprint, no horizon or surrounding ocean panorama. Shallow natural terrain and water edges, no thick pedestal, aquarium walls or floating cut-earth block.
Constraints: one city only, no collage, no inset view, no UI, no labels, no readable lettering, no logos or watermark. No modern jets, angled modern carrier deck, giant missiles, futuristic towers, plastic toy surfaces, heavy blur, night lighting, explosions or smoke. No ships on roads, merged vessels, clipped hulls, blocked flight decks, water beneath land buildings, or enclosed harbor with no exit. Balance attractive city architecture with clearly legible naval facilities.
```

## A：环抱内港城

```text
Layout variation A — sheltered U-shaped naval city. Arrange a continuous U-shaped band of fortified limestone city districts around one broad central harbor basin, open toward the viewer. A dignified blue-roofed navy headquarters rises at the rear center, with stepped subordinate buildings and inhabited neighborhoods along both harbor arms. Stone seawalls and modest coastal gun positions define the perimeter. Inside the U, moor one aircraft carrier along one quay and one battleship along a separate quay with clear water between them; orient both long hulls into the basin so their decks remain unobstructed. Keep a wide open navigable harbor entrance in the foreground. Small warehouses and a few period cranes reinforce the naval function without obscuring the ships. Strong symmetrical overall city silhouette, naturally varied secondary architecture, central water as the clearest visual feature.
```

## B：双湾海军城

```text
Layout variation B — twin-basin fleet headquarters. A substantial central land district projects between two adjacent open naval basins, connected to a broad rear city district. Place the navy headquarters and inhabited urban blocks on this central spine and rear land, with restrained blue-and-gold roof accents. One basin contains a fully visible aircraft carrier; the other contains a fully visible battleship, each with dedicated quays and visible water clearance. Both basins open toward the viewer into the same narrow foreground strip of sea. Give the carrier flight deck and battleship gun turrets equal clarity. Use low stone seawalls, short breakwaters, warehouses and modest service cranes. The twin-water-bay silhouette must remain readable at icon scale. No bridge spans either ship exit and no buildings stand on the ships or over the basins.
```

## C：船坞工业城

```text
Layout variation C — fortified shipbuilding and fleet-support city. Organize the compact city around one operational harbor basin and an adjacent large FLOODED repair dock connected to it by a visibly open caisson entrance. Moor an aircraft carrier in the open basin with its full flight deck exposed. Place a battleship afloat inside the repair dock, with heavy turrets and hull visibly separate from the dock walls. Red-brick naval workshops with sawtooth roofs, a restrained steel gantry crane and quay warehouses form a clear shipbuilding quarter. Preserve a substantial inhabited stone city district and a blue-roofed naval headquarters so the image reads as an entire city, not only a shipyard. Put the tall crane away from the ship silhouettes. Stone sea defenses and a short breakwater shelter the open harbor mouth. Industrial materials create variation while blue-roofed civic architecture keeps continuity with the existing city art.
```

## D：海上要塞城

```text
Layout variation D — compact island naval citadel. Build one inhabited island fortress city whose rear and lateral stone urban districts embrace an internal military harbor. Short detached stone breakwaters and low coastal gun bastions protect the foreground entrance while leaving a visibly wide navigable channel. A monumental blue-roofed navy headquarters with restrained gilded details forms the rear skyline, supported by lower residential terraces and naval service buildings. Inside the protected harbor, show one battleship and one aircraft carrier side by side at separate piers, with full hulls, readable decks and clear water gaps. Water wraps a narrow part of the exterior coastline as well as filling the inner harbor, but remains within a compact icon footprint. Favor a powerful fortified silhouette and balanced land-water composition. No fully closed ring wall across the channel, no bridge blocking the entrance, no thick rock pedestal, and no ocean panorama.
```

## 后续六档扩展原则

选择一种布局后固定相机、港口朝向和主建筑位置，通过城防厚度、城区密度、码头规模和配套设施数量逐档成长；本轮所有候选都保留航母与战列舰，六档各自舰船数量待选型时再确定。图标内舰船属于固定美术内容，不表示玩家真实舰队库存。
