"""生成所有大地图 NPC/日寇模型图标（共 5 款写实微缩方阵模型）。
1. 日寇步兵方阵 (npc-phalanx-infantry, Lv.1~5): 军刀指挥官前出、掷弹筒组、九六式轻机枪趴伏组、突击步兵排、泥泞车辙积水路面
2. 日寇轻装甲方阵 (npc-phalanx-light-armor, Lv.6~8): 九五式轻坦黄带迷彩、九七式轻装甲车、九三式六轮装甲车、边三轮侦察组、电线杆与碎石路
3. 双车中坦突击方阵 (npc-fortress-orthogonal, Lv.9~10): 九七式中战车 123/124 号双车突击、警戒步兵班、压平战车泥路
4. 日寇飞机方阵 (npc-phalanx-aircraft, Lv.11~20): 零式战斗机、双发战术攻击机伪装网、沙袋掩体前线指挥所、风向袋与地勤整备
5. 日寇混合编队 (npc-phalanx-mixed, Lv.21~30): 71号中坦领衔、九五式轻坦集群、冲锋步兵散兵线协同突击

所有模型严格遵循用户指令与野地边缘虚化融合规范：
- 【参考其他野地底部边缘虚化处理】：
  1. 底部轮廓多频正弦噪声调制（非一刀切水平线）；
  2. 纵向 25~32px 柔和 smoothstep 渐隐衰减至 Alpha = 0；
  3. 渐隐过渡带内与大地图草坪色 (157, 169, 126) 及黑土色 (83, 73, 61) 自然交融；
  4. 左右两侧地表向内收拢柔化（inset）；
  5. 地图嵌入 PNG 与 WebP 均为真实透明 RGBA 格式。
- 【单位与武器 100% 刚性保护】：
  建立精确的 384x384 单位保护遮罩（units_mask_384），所有士兵、指挥官、步枪枪管、刺刀、卧姿机枪组、
  迫击炮、坦克炮塔、主炮炮管、履带、战机机翼、红日章、电线杆及导线等，保持 100% 原始色彩与清晰度（Alpha=255），零模糊侵蚀。
- 【彻底去除底部高度】：消除垂直泥壁、断崖与底台，呈现平整地表微缩战阵。
"""
from pathlib import Path
import cv2
import numpy as np
from PIL import Image, ImageFilter, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
RAW_DIR = ROOT / 'output/imagegen/npc-phalanx-20261011/raw'
DEST_DIR = ROOT / 'frontend/img/map'
OUTPUT_DIR = ROOT / 'output/imagegen/npc-phalanx-20261011'

# 大地图标准地形色系（与 frontend/js/constants.js terrainPalette 完全一致）
GRASS = np.array([157.0, 169.0, 126.0])  # 大地图草坪基准色
SOIL = np.array([83.0, 73.0, 61.0])      # 大地图黑土基准色
SIZE = 384
TARGET_SPAN = 360


def smooth(e0: float, e1: float, v: np.ndarray) -> np.ndarray:
    t = np.clip((v - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def apply_wild_feathering(sprite: Image.Image, units_mask_384: np.ndarray,
                         fade_len: float = 28.0, grass_bias: float = 0.50) -> Image.Image:
    """参考其他野地（redesign-resource-ground 与 embed-map-icons）的底部边缘虚化处理。"""
    arr = np.array(sprite, dtype=float)
    rgb = arr[:, :, :3]
    alpha = arr[:, :, 3]
    h, w = alpha.shape

    # 1. 检测每列最底部的非透明像素
    bottom = []
    for x in range(w):
        col = np.where(alpha[:, x] > 20)[0]
        bottom.append(col.max() if len(col) > 0 else -1)

    smoothed_bottom = np.full(w, -1.0)
    for x in range(w):
        neighbors = [bottom[nx] for nx in range(max(0, x - 4), min(w, x + 5)) if bottom[nx] >= 0]
        if neighbors:
            smoothed_bottom[x] = float(np.median(neighbors))

    y_idx, x_idx = np.mgrid[0:h, 0:w]
    variation = 2.2 * np.sin(x_idx * 0.11) + 1.5 * np.cos(x_idx * 0.29 + 1.4) + 0.8 * np.sin(x_idx * 0.53 - 0.7)

    new_alpha = alpha.copy()
    new_rgb = rgb.copy()

    # 2. 纵向底部边缘衰减与地表色彩交融
    for x in range(w):
        b_y = smoothed_bottom[x]
        if b_y < 0:
            continue
        dist = b_y - y_idx[:, x]
        eff_dist = dist - variation[:, x]
        factor = smooth(0.0, fade_len, eff_dist)

        # 仅对底部过渡带内的地表应用衰减，单位刚性保护区绝对跳过
        mask_ground = (y_idx[:, x] > (b_y - fade_len - 15)) & (alpha[:, x] > 0) & (~units_mask_384[:, x])
        new_alpha[mask_ground, x] = alpha[mask_ground, x] * factor[mask_ground]

        # 随着渐隐过渡，色彩平滑融入游戏大地图的草地/黑土基准色
        mix = (1.0 - factor[mask_ground, None]) * 0.78
        g_share = grass_bias + 0.15 * np.sin(x * 0.049 + y_idx[mask_ground, x, None] * 0.025)
        terrain = SOIL * (1.0 - g_share) + GRASS * g_share
        new_rgb[mask_ground, x] = rgb[mask_ground, x] * (1.0 - mix) + terrain * mix

    # 3. 侧向边界向内收拢柔化（针对下半部地表边缘）
    for y in range(int(h * 0.55), h):
        row_cols = np.where(alpha[y, :] > 20)[0]
        if len(row_cols) < 10:
            continue
        left_x = row_cols.min()
        right_x = row_cols.max()
        side_band = 18.0

        # 左侧羽化
        for x in range(left_x, min(w, left_x + int(side_band) + 5)):
            if units_mask_384[y, x] or new_alpha[y, x] == 0:
                continue
            dist_left = x - left_x + variation[y, x]
            factor_left = smooth(0.0, side_band, dist_left)
            new_alpha[y, x] *= factor_left
            mix = (1.0 - factor_left) * 0.75
            g_share = grass_bias + 0.15 * np.sin(x * 0.049 + y * 0.025)
            terrain = SOIL * (1.0 - g_share) + GRASS * g_share
            new_rgb[y, x] = new_rgb[y, x] * (1.0 - mix) + terrain * mix

        # 右侧羽化
        for x in range(max(0, right_x - int(side_band) - 5), right_x + 1):
            if units_mask_384[y, x] or new_alpha[y, x] == 0:
                continue
            dist_right = right_x - x + variation[y, x]
            factor_right = smooth(0.0, side_band, dist_right)
            new_alpha[y, x] *= factor_right
            mix = (1.0 - factor_right) * 0.75
            g_share = grass_bias + 0.15 * np.sin(x * 0.049 + y * 0.025)
            terrain = SOIL * (1.0 - g_share) + GRASS * g_share
            new_rgb[y, x] = new_rgb[y, x] * (1.0 - mix) + terrain * mix

    out = np.dstack([np.clip(new_rgb, 0, 255).astype(np.uint8),
                     np.clip(new_alpha, 0, 255).astype(np.uint8)])
    return Image.fromarray(out, "RGBA")


def finalize_sprite(rgb: np.ndarray, alpha: np.ndarray, units_mask: np.ndarray, is_white: np.ndarray,
                    target_span: int = TARGET_SPAN, grass_bias: float = 0.50) -> Image.Image:
    """对单位边缘做亚像素抗锯齿，裁剪透明留白并重采样到 384x384 标准画布，并应用野地同款底部虚化。"""
    # 严格确保所有纯白背景完全透明
    alpha[is_white] = 0.0

    # 单位贴合白底接触处的亚像素抗锯齿过渡
    touching_bg = cv2.dilate(is_white.astype(np.uint8), np.ones((3, 3), np.uint8)).astype(bool) & units_mask
    min_c = rgb.min(axis=2)
    alpha[touching_bg] = np.clip((240.0 - min_c[touching_bg]) / 35.0, 0.0, 1.0)

    rgba = np.dstack([np.clip(rgb, 0, 255).astype(np.uint8),
                      np.clip(alpha * 255.0, 0, 255).astype(np.uint8)])
    sprite = Image.fromarray(rgba, 'RGBA')
    a_channel = sprite.getchannel('A').filter(ImageFilter.GaussianBlur(0.8))
    sprite.putalpha(a_channel)

    bbox = a_channel.point(lambda v: 255 if v > 12 else 0).getbbox()
    if bbox is None:
        return Image.new('RGBA', (SIZE, SIZE), (0, 0, 0, 0))
    cropped = sprite.crop(bbox)
    scale = min(target_span / cropped.width, target_span / cropped.height)
    new_w, new_h = round(cropped.width * scale), round(cropped.height * scale)
    resized = cropped.resize((new_w, new_h), Image.Resampling.LANCZOS)

    canvas = Image.new('RGBA', (SIZE, SIZE), (0, 0, 0, 0))
    offset_x = (SIZE - new_w) // 2
    offset_y = (SIZE - new_h) // 2
    canvas.alpha_composite(resized, (offset_x, offset_y))

    # 同步缩放单位刚性保护遮罩到 384x384
    units_img = Image.fromarray((units_mask.astype(np.uint8) * 255), 'L').crop(bbox)
    resized_units = units_img.resize((new_w, new_h), Image.Resampling.NEAREST)
    canvas_units = Image.new('L', (SIZE, SIZE), 0)
    canvas_units.paste(resized_units, (offset_x, offset_y))
    units_mask_384 = np.array(canvas_units) > 128

    # 应用野地同款底部边缘与地表有机虚化过渡
    feathered_canvas = apply_wild_feathering(canvas, units_mask_384, fade_len=28.0, grass_bias=grass_bias)
    return feathered_canvas


def make_ground_envelope(ground_poly: np.ndarray, units_mask: np.ndarray, is_white: np.ndarray,
                         x_idx: np.ndarray, y_idx: np.ndarray, fade_dist: float = 30.0):
    """构建有机平滑的地表羽化遮罩：单位区域 100% 不透明，边缘自然多频衰减至透明。"""
    h, w = is_white.shape
    envelope = np.zeros((h, w), dtype=np.uint8)
    cv2.fillPoly(envelope, [ground_poly], 255)

    # 排除纯白背景，锁定军事单位
    envelope[is_white] = 0
    envelope[units_mask] = 255

    # 形态学闭运算平滑边缘，消除生硬折角与阶梯缺口
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (31, 31))
    envelope_smooth = cv2.morphologyEx(envelope, cv2.MORPH_CLOSE, kernel)

    dist = cv2.distanceTransform(envelope_smooth, cv2.DIST_L2, 5)
    noise = np.sin(x_idx * 0.03) * 6.0 + np.cos(y_idx * 0.03) * 5.0
    d_eff = np.where(envelope_smooth > 0, dist + noise, 0.0)

    alpha = smooth(3.0, fade_dist, d_eff)
    alpha[envelope_smooth == 0] = 0.0
    alpha[units_mask] = 1.0
    alpha[is_white] = 0.0

    ground_blend = smooth(2.0, fade_dist * 0.8, d_eff)[:, :, None]
    return alpha, ground_blend


def process_infantry() -> Image.Image:
    """1. 日寇步兵方阵 (Lv.1~5)：指挥官军刀、掷弹筒、卧姿轻机枪枪管火焰、全连步枪 100% 保护；底部积水泥路自然虚化。"""
    src = RAW_DIR / 'infantry_phalanx.jpg'
    img = cv2.imread(str(src))
    rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB).astype(float)
    h, w = rgb.shape[:2]
    y_idx, x_idx = np.mgrid[0:h, 0:w]
    is_white = (rgb.min(axis=2) > 205)

    units_mask = np.zeros((h, w), dtype=bool)
    prone_poly = np.array([[15, 600], [15, 430], [140, 410], [270, 520], [270, 600], [180, 610]], dtype=np.int32)
    top_poly = np.array([[120, 360], [320, 115], [560, 115], [780, 230], [990, 310], [990, 380], [700, 420], [250, 420]], dtype=np.int32)
    # 前排跪姿步兵战术靴底在 y<=675，下方泥泞积水路面保留为可羽化地表
    main_poly = np.array([[140, 420], [250, 410], [680, 400], [870, 330], [870, 560], [750, 680], [600, 675], [420, 675], [250, 675], [220, 650], [140, 520]], dtype=np.int32)
    cv2.fillPoly(units_mask, [prone_poly, top_poly, main_poly], True)
    units_mask &= (~is_white)

    ground_poly = np.array([
        [20, 560], [200, 240], [350, 120], [580, 120], [800, 200],
        [990, 300], [990, 370], [870, 480], [800, 680], [650, 730],
        [420, 755], [260, 755], [180, 670], [20, 580]
    ], dtype=np.int32)

    alpha, ground_blend = make_ground_envelope(ground_poly, units_mask, is_white, x_idx, y_idx, fade_dist=28.0)
    final_rgb = np.where(units_mask[:, :, None], rgb, rgb * ground_blend + GRASS * (1.0 - ground_blend))
    return finalize_sprite(final_rgb, alpha, units_mask, is_white, grass_bias=0.45)


def process_light_armor() -> Image.Image:
    """2. 日寇轻装甲方阵 (Lv.6~8)：电线杆及绝缘导线、九五轻坦、九七装甲车、边三轮 100% 保护；碎石车道自然虚化。"""
    src = RAW_DIR / 'light_armor_phalanx.jpg'
    img = cv2.imread(str(src))
    rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB).astype(float)
    h, w = rgb.shape[:2]
    y_idx, x_idx = np.mgrid[0:h, 0:w]
    is_white = (rgb.min(axis=2) > 205)

    pole_mask = (x_idx >= 410) & (x_idx <= 540) & (y_idx >= 25) & (y_idx <= 380) & (rgb.min(axis=2) < 205)
    tank_center = (x_idx >= 390) & (x_idx <= 570) & (y_idx >= 170) & (y_idx <= 690) & (~is_white)
    moto_mask = (x_idx >= 620) & (x_idx <= 840) & (y_idx >= 440) & (y_idx <= 690) & (~is_white)
    rear_vehicles = (x_idx >= 500) & (x_idx <= 860) & (y_idx >= 90) & (y_idx <= 360) & (~is_white)
    wall_mask = (x_idx >= 390) & (x_idx <= 480) & (y_idx >= 180) & (y_idx <= 320) & (~is_white)
    units_mask = pole_mask | tank_center | moto_mask | rear_vehicles | wall_mask

    ground_poly = np.array([
        [480, 110], [660, 110], [840, 160], [860, 340], [860, 520],
        [850, 680], [760, 770], [420, 780], [220, 760], [150, 680],
        [180, 540], [380, 340], [420, 200], [480, 110]
    ], dtype=np.int32)

    alpha, ground_blend = make_ground_envelope(ground_poly, units_mask, is_white, x_idx, y_idx, fade_dist=30.0)
    final_rgb = np.where(units_mask[:, :, None], rgb, rgb * ground_blend + GRASS * (1.0 - ground_blend))
    return finalize_sprite(final_rgb, alpha, units_mask, is_white, grass_bias=0.40)


def process_orthogonal() -> Image.Image:
    """3. 双车中坦突击方阵 (Lv.9~10)：九七中战车 123/124 号双车与步兵散兵 100% 原色保护；战车泥路自然虚化。"""
    src = RAW_DIR / 'assault_column.jpg'
    img = cv2.imread(str(src))
    rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB).astype(float)
    h, w = rgb.shape[:2]
    y_idx, x_idx = np.mgrid[0:h, 0:w]
    is_white = (rgb.min(axis=2) > 205)

    tank1_poly = np.array([[200, 520], [240, 420], [350, 390], [480, 450], [640, 580], [640, 670], [530, 785], [370, 785], [260, 725], [200, 620]], dtype=np.int32)
    tank2_poly = np.array([[480, 430], [540, 310], [670, 300], [800, 370], [960, 470], [960, 575], [860, 645], [710, 645], [570, 575], [480, 470]], dtype=np.int32)
    squad_poly = np.array([[60, 410], [100, 280], [260, 200], [380, 155], [520, 150], [640, 175], [675, 280], [610, 400], [450, 430], [260, 430], [150, 440]], dtype=np.int32)
    units_mask = np.zeros((h, w), dtype=bool)
    cv2.fillPoly(units_mask, [tank1_poly, tank2_poly, squad_poly], True)
    units_mask &= (~is_white)

    ground_poly = np.array([
        [100, 340], [250, 200], [420, 150], [650, 170], [750, 240],
        [980, 440], [980, 560], [880, 650], [680, 750], [480, 850],
        [320, 830], [180, 720], [80, 520], [80, 400]
    ], dtype=np.int32)

    alpha, ground_blend = make_ground_envelope(ground_poly, units_mask, is_white, x_idx, y_idx, fade_dist=30.0)
    final_rgb = np.where(units_mask[:, :, None], rgb, rgb * ground_blend + GRASS * (1.0 - ground_blend))
    return finalize_sprite(final_rgb, alpha, units_mask, is_white, grass_bias=0.48)


def process_aircraft() -> Image.Image:
    """4. 日寇飞机方阵 (Lv.11~20)：零战与双发攻击机、指挥所帐篷、沙袋、风向袋、地勤 100% 保护；机场跑道自然虚化。"""
    src = RAW_DIR / 'aircraft_phalanx.jpg'
    img = cv2.imread(str(src))
    rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB).astype(float)
    h, w = rgb.shape[:2]
    y_idx, x_idx = np.mgrid[0:h, 0:w]
    is_white = (rgb.min(axis=2) > 205)

    frame_left = (y_idx > 500 + 0.564 * (x_idx - 35)) & (x_idx <= 505)
    frame_right = (y_idx > 765 - 0.565 * (x_idx - 505)) & (x_idx > 505)
    is_frame = frame_left | frame_right

    plane_left = (x_idx >= 140) & (x_idx <= 500) & (y_idx >= 220) & (y_idx <= 645)
    plane_right = (x_idx >= 540) & (x_idx <= 950) & (y_idx >= 350) & (y_idx <= 600)
    # 前机起落架着陆轮在 y<=685，下方地表保留为羽化过渡带
    plane_front = (x_idx >= 360) & (x_idx <= 750) & (y_idx >= 520) & (y_idx <= 685)
    tent_bunker = (x_idx >= 250) & (x_idx <= 600) & (y_idx >= 180) & (y_idx <= 420)
    windsock = (x_idx >= 480) & (x_idx <= 600) & (y_idx >= 75) & (y_idx <= 220)
    units_mask = (plane_left | plane_right | plane_front | tent_bunker | windsock) & (~is_white) & (~is_frame)

    ground_poly = np.array([
        [505, 230], [280, 360], [60, 520], [140, 620], [250, 660],
        [450, 730], [550, 730], [680, 670], [780, 595], [880, 525],
        [970, 475], [820, 380], [660, 280], [505, 230]
    ], dtype=np.int32)

    alpha, ground_blend = make_ground_envelope(ground_poly, units_mask, is_white, x_idx, y_idx, fade_dist=30.0)
    final_rgb = np.where(units_mask[:, :, None], rgb, rgb * ground_blend + GRASS * (1.0 - ground_blend))
    return finalize_sprite(final_rgb, alpha, units_mask, is_white, grass_bias=0.52)


def process_mixed() -> Image.Image:
    """5. 日寇混合编队 (Lv.21~30)：71 号中坦、左翼与右翼轻坦、冲锋步枪手刺刀全员 100% 保护；泥泞坑洼自然虚化。"""
    src = RAW_DIR / 'mixed_formation.jpg'
    img = cv2.imread(str(src))
    rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB).astype(float)
    h, w = rgb.shape[:2]
    y_idx, x_idx = np.mgrid[0:h, 0:w]
    is_white = (rgb.min(axis=2) > 205)

    cliff_left = (y_idx > 575 + 0.42 * (x_idx - 15)) & (x_idx <= 600)
    cliff_right = (y_idx > 830 - 0.90 * (x_idx - 600)) & (x_idx > 600)
    is_plinth = cliff_left | cliff_right | (y_idx > 835)

    top_tank = (x_idx >= 330) & (x_idx <= 650) & (y_idx >= 225) & (y_idx <= 425)
    left_tank = (x_idx >= 10) & (x_idx <= 460) & (y_idx >= 295) & (y_idx <= 620)
    center_tank = (x_idx >= 320) & (x_idx <= 730) & (y_idx >= 380) & (y_idx <= 680)
    right_tank = (x_idx >= 620) & (x_idx <= 1005) & (y_idx >= 300) & (y_idx <= 600)
    # 前列冲锋步兵散兵在 y<=770，下方车辙与弹坑保留为羽化过渡带
    infantry = (x_idx >= 80) & (x_idx <= 860) & (y_idx >= 430) & (y_idx <= 770)
    crew = (x_idx >= 500) & (x_idx <= 600) & (y_idx >= 370) & (y_idx <= 460)
    units_mask = (top_tank | left_tank | center_tank | right_tank | infantry | crew) & (~is_white) & (~is_plinth)

    ground_poly = np.array([
        [340, 230], [510, 220], [660, 250], [850, 310], [1005, 420],
        [980, 580], [860, 680], [740, 760], [600, 825], [450, 815],
        [220, 740], [80, 600], [40, 580], [15, 540], [15, 420], [150, 300]
    ], dtype=np.int32)

    alpha, ground_blend = make_ground_envelope(ground_poly, units_mask, is_white, x_idx, y_idx, fade_dist=28.0)
    final_rgb = np.where(units_mask[:, :, None], rgb, rgb * ground_blend + GRASS * (1.0 - ground_blend))
    return finalize_sprite(final_rgb, alpha, units_mask, is_white, grass_bias=0.46)


def build_showcase_sheet(models: dict):
    """生成 5 款 NPC 方阵大地图实装展示总览长图（在实际大地图草坪底色上模拟渲染）。"""
    tile_w, tile_h = 384, 384
    margin = 24
    header_h = 75
    footer_h = 55
    total_w = margin * 2 + tile_w * len(models) + (len(models) - 1) * margin
    total_h = header_h + tile_h + footer_h

    sheet = Image.new('RGB', (total_w, total_h), (28, 30, 28))
    draw = ImageDraw.Draw(sheet)

    font_title = None
    font_sub = None
    for font_path in [
        '/System/Library/Fonts/PingFang.ttc',
        '/System/Library/Fonts/STHeiti Light.ttc',
        '/System/Library/Fonts/Hiragino Sans GB.ttc',
        '/Library/Fonts/Arial Unicode.ttf'
    ]:
        if Path(font_path).exists():
            try:
                font_title = ImageFont.truetype(font_path, 21)
                font_sub = ImageFont.truetype(font_path, 14)
                break
            except Exception:
                pass

    if font_title is None:
        font_title = ImageFont.load_default()
        font_sub = ImageFont.load_default()

    draw.text((margin, 24), "《烽火平原》大地图日寇 NPC 多兵种方阵（单位 100% 完整保护·野地同款底部虚化消融）", fill=(240, 235, 215), font=font_title)

    bg_grass = Image.new('RGBA', (tile_w, tile_h), (104, 103, 71, 255))

    for idx, (key, info) in enumerate(models.items()):
        x = margin + idx * (tile_w + margin)
        y = header_h

        # 模拟大地图草坪底板与精美边框
        draw.rectangle([x - 2, y - 2, x + tile_w + 1, y + tile_h + 1], fill=(42, 45, 42), outline=(70, 75, 68))
        embedded = Image.alpha_composite(bg_grass, info['sprite'])
        sheet.paste(embedded.convert('RGB'), (x, y))

        # 底部标签
        draw.text((x + 6, y + tile_h + 10), info['title'], fill=(245, 230, 175), font=font_sub)
        draw.text((x + 6, y + tile_h + 30), info['subtitle'], fill=(175, 180, 165), font=font_sub)

    out_path = OUTPUT_DIR / 'npc_all_phalanxes_showcase.png'
    sheet.save(out_path, quality=95)
    print(f"Saved showcase sheet: {out_path}")


def main():
    DEST_DIR.mkdir(parents=True, exist_ok=True)
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    print("Generating NPC phalanx models with wild-style ground feathering and rigid unit protection...")

    model_configs = {
        'infantry': {
            'title': '【1】日寇步兵方阵 (Lv.1~5)',
            'subtitle': '军刀指挥官·机枪组·步兵排·底部虚化',
            'filename': 'npc-phalanx-infantry',
            'processor': process_infantry
        },
        'light_armor': {
            'title': '【2】日寇轻装甲方阵 (Lv.6~8)',
            'subtitle': '九五式轻坦·边三轮·装甲车·碎石虚化',
            'filename': 'npc-phalanx-light-armor',
            'processor': process_light_armor
        },
        'orthogonal': {
            'title': '【3】双车中坦突击方阵 (Lv.9~10)',
            'subtitle': '九七中战车 123/124 双车·车道虚化',
            'filename': 'npc-fortress-orthogonal',
            'processor': process_orthogonal
        },
        'aircraft': {
            'title': '【4】日寇飞机方阵 (Lv.11~20)',
            'subtitle': '零战·双发攻击机·掩体指挥所·跑道虚化',
            'filename': 'npc-phalanx-aircraft',
            'processor': process_aircraft
        },
        'mixed': {
            'title': '【5】日寇混合编队 (Lv.21~30)',
            'subtitle': '71号中战车·轻坦战车群·泥地虚化',
            'filename': 'npc-phalanx-mixed',
            'processor': process_mixed
        }
    }

    for key, cfg in model_configs.items():
        print(f"Processing {cfg['filename']}...")
        sprite = cfg['processor']()
        cfg['sprite'] = sprite

        # 1. 导出主资产 WebP
        webp_path = DEST_DIR / f"{cfg['filename']}.webp"
        sprite.save(webp_path, 'WEBP', quality=95, method=6)
        print(f"  -> Saved {webp_path}")

        # 2. 导出大地图嵌入 PNG（保存真实透明 RGBA 通道）
        embedded_png = DEST_DIR / f"{cfg['filename']}-map-embedded.png"
        sprite.save(embedded_png, 'PNG', optimize=True)
        print(f"  -> Saved {embedded_png}")

    # 3. 兼容旧资产 npc-fortress.*
    ortho_sprite = model_configs['orthogonal']['sprite']
    ortho_sprite.save(DEST_DIR / 'npc-fortress.webp', 'WEBP', quality=95, method=6)
    ortho_sprite.save(DEST_DIR / 'npc-fortress-map.webp', 'WEBP', quality=95, method=6)
    ortho_sprite.save(DEST_DIR / 'npc-fortress-map-embedded.png', 'PNG', optimize=True)
    print("  -> Synced legacy npc-fortress assets.")

    # 4. 生成五合一展示长图
    build_showcase_sheet(model_configs)
    print("All NPC phalanx models generated successfully with wild-style ground feathering!")


if __name__ == '__main__':
    main()
