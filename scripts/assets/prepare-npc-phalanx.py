"""处理日本步坦协同方阵模型（方案一：双车并进·掩护突击方阵）。
彻底去除垂直底座高度与菱形角度，构建正交正方形地表基底（与大地图网格水平垂直对齐）；
100% 保留坦克与步兵原始金属迷彩原色（杜绝草色滤镜污染）；
地表混合大地图草坪色与黑土色，边缘采用多频噪声自然羽化过渡至草坪；
输出 384x384 WebP 与地图嵌入 PNG。
"""
from pathlib import Path
import cv2
import numpy as np
from PIL import Image, ImageFilter

ROOT = Path(__file__).resolve().parents[2]
SOURCE_DIR = ROOT / 'output/imagegen/npc-phalanx-20261011'
RAW_IMAGE = SOURCE_DIR / 'raw/assault_column.jpg'
DEST_DIR = ROOT / 'frontend/img/map'
OPTIONS_DIR = SOURCE_DIR / 'options'

GRASS = np.array([104.0, 103.0, 71.0])      # 大地图草坪基准色（橄榄金绿）
SOIL = np.array([92.0, 81.0, 62.0])         # 大地图黑土基准色（深棕褐色）
DARK_SOIL = np.array([84.0, 74.0, 56.0])    # 履带泥土深土色
SIZE = 384


def smooth(e0: float, e1: float, v: np.ndarray) -> np.ndarray:
    t = np.clip((v - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def build_phalanx_model(src_path: Path) -> Image.Image:
    raw_bgr = cv2.imread(str(src_path))
    if raw_bgr is None:
        raise FileNotFoundError(f"无法读取原图: {src_path}")
    h, w = raw_bgr.shape[:2]
    raw_rgb = cv2.cvtColor(raw_bgr, cv2.COLOR_BGR2RGB).astype(float)
    y_indices, x_indices = np.mgrid[0:h, 0:w]

    # 1. 严格识别白色摄影棚背景与几何底座侧切面
    is_white = (raw_rgb.min(axis=2) > 230)
    # 底部底座侧壁几何边缘方程（仅在原画微缩地台最外缘生效，绝不影响车体间平坦地表与真实投影）
    plinth_left = (y_indices > 440 + 0.95 * (x_indices - 20)) & (x_indices <= 465) & (raw_rgb.mean(axis=2) < 65)
    plinth_right = (y_indices > 875 - 0.52 * (x_indices - 465)) & (x_indices > 465) & (raw_rgb.mean(axis=2) < 65)
    is_plinth = plinth_left | plinth_right | (y_indices > 850)
    is_invalid = is_white | is_plinth

    # 2. 前景军事目标保护掩膜（九七式战车 123号、124号与日军警戒突击步兵班）
    # 强制保证 100% 原始未调色 RGB，完全消除草色泛光与绿膜污染
    tank1_poly = np.array([
        [200, 520], [240, 420], [350, 390], [480, 450], [640, 580],
        [640, 670], [530, 785], [370, 785], [260, 725], [200, 620]
    ], dtype=np.int32)
    tank2_poly = np.array([
        [480, 430], [540, 310], [670, 300], [800, 370], [960, 470],
        [960, 575], [860, 645], [710, 645], [570, 575], [480, 470]
    ], dtype=np.int32)
    squad_poly = np.array([
        [60, 410], [120, 280], [260, 220], [380, 160], [520, 155],
        [630, 180], [670, 280], [610, 400], [450, 430], [260, 430],
        [150, 440]
    ], dtype=np.int32)

    fg_mask = np.zeros((h, w), dtype=np.float32)
    cv2.fillPoly(fg_mask, [tank1_poly, tank2_poly, squad_poly], 1.0)
    fg_mask = fg_mask * (~is_white).astype(np.float32)

    # 确保前景主体像素绝不被判定为无效
    is_invalid = is_invalid & (fg_mask < 0.5)

    # 3. 计算自微缩地台平滑过渡至大地图战场的距离场与多频噪声
    valid_ground = (~is_invalid).astype(np.uint8)
    dist_valid = cv2.distanceTransform(valid_ground, cv2.DIST_L2, 5)

    boundary_noise = np.sin(x_indices * 0.025) * 14.0 + np.cos(y_indices * 0.028) * 12.0
    dist_organic = dist_valid + boundary_noise
    raw_ground_weight = smooth(6.0, 48.0, dist_organic)
    raw_ground_weight[is_invalid] = 0.0

    # 4. 构建与大地图正交方格严格平行的正方形（Orthogonal Square）距离场
    # 彻底杜绝 45° 菱形斜切角度，使得图标地表四边呈水平与垂直正交方格对齐
    cx, cy = 510.0, 475.0
    half_size = 440.0
    corner_r = 65.0

    dx = np.abs(x_indices - cx)
    dy = np.abs(y_indices - cy)
    corner_dx = np.maximum(0.0, dx - (half_size - corner_r))
    corner_dy = np.maximum(0.0, dy - (half_size - corner_r))
    corner_dist = np.sqrt(corner_dx**2 + corner_dy**2)
    d_square = np.where((corner_dx == 0) | (corner_dy == 0),
                        half_size - np.maximum(dx, dy),
                        corner_r - corner_dist)

    # 多频边缘正弦噪声调制：生成类似方形的自然草丛凹凸边缘（与油田标准统一）
    n1 = np.sin(x_indices * 0.022 + 1.2) * 15.0 + np.cos(y_indices * 0.025 - 0.7) * 13.0
    n2 = np.sin(x_indices * 0.045 - y_indices * 0.03) * 7.5 + np.sin(y_indices * 0.052 + x_indices * 0.02) * 6.5
    d_effective = d_square + n1 + n2

    # 边缘平滑羽化 (24px 渐变衰减)
    alpha = smooth(-6.0, 26.0, d_effective)

    # 5. 大地图正方形地表基底生成：中心黑土色/履带深土色，向外渐变至草坪色
    inward = smooth(6.0, 65.0, d_effective)
    dist_center = np.sqrt((x_indices - cx)**2 + (y_indices - cy)**2)
    center_focus = 1.0 - smooth(120.0, 420.0, dist_center)
    patch_noise = np.sin(x_indices * 0.018 + y_indices * 0.012) * 0.16 + np.sin(y_indices * 0.022 - x_indices * 0.014) * 0.11

    soil_share = np.clip(0.44 * inward + 0.46 * center_focus * inward + patch_noise, 0.0, 1.0)
    grain = (np.sin(x_indices * 0.07 + y_indices * 0.03) * 3.0 + np.sin(y_indices * 0.06 - x_indices * 0.045) * 2.5)[:, :, None]

    map_terrain = np.where(soil_share[:, :, None] > 0.60,
                           SOIL[None, None, :] * (1.0 - (soil_share[:, :, None] - 0.60)/0.40) + DARK_SOIL[None, None, :] * ((soil_share[:, :, None] - 0.60)/0.40),
                           GRASS[None, None, :] * (1.0 - soil_share[:, :, None]/0.60) + SOIL[None, None, :] * (soil_share[:, :, None]/0.60))
    map_terrain = np.clip(map_terrain + grain, 0.0, 255.0)

    # 原画地表真实履带/车辙纹理亮度调和，使中心真实车道地表与黑土地基完美融合
    ground_lum = (0.299 * raw_rgb[:, :, 0] + 0.587 * raw_rgb[:, :, 1] + 0.114 * raw_rgb[:, :, 2])
    lum_factor = (ground_lum / 140.0) ** 1.02
    matched_raw_ground = (SOIL[None, None, :] * 0.38 + raw_rgb * 0.62) * (lum_factor[:, :, None] * 0.96)

    # 地表自然合成
    ground_blended = matched_raw_ground * raw_ground_weight[:, :, None] + map_terrain * (1.0 - raw_ground_weight[:, :, None])

    # 最外层周界羽化带 100% 锁定草坪色 (104, 103, 71)，使镶嵌融入大地图草坪时无任何生硬外边
    outer_fade = smooth(0.0, 36.0, d_effective)
    ground_with_grass = ground_blended * outer_fade[:, :, None] + GRASS[None, None, :] * (1.0 - outer_fade[:, :, None])

    # 最终合并：坦克与步兵主体 100% 原始色彩！地表为方格对齐的正方形黑土草坪自然战场！
    final_rgb = np.where(fg_mask[:, :, None] > 0.5, raw_rgb, ground_with_grass)

    rgba = np.dstack([np.clip(final_rgb, 0, 255).astype(np.uint8),
                      np.clip(alpha * 255.0, 0, 255).astype(np.uint8)])
    sprite = Image.fromarray(rgba, 'RGBA')
    a_channel = sprite.getchannel('A').filter(ImageFilter.GaussianBlur(1.0))
    sprite.putalpha(a_channel)

    bbox = a_channel.point(lambda v: 255 if v > 15 else 0).getbbox()
    cropped = sprite.crop(bbox)

    target_span = 362
    scale = min(target_span / cropped.width, target_span / cropped.height)
    new_w, new_h = round(cropped.width * scale), round(cropped.height * scale)
    resized = cropped.resize((new_w, new_h), Image.Resampling.LANCZOS)

    canvas = Image.new('RGBA', (SIZE, SIZE), (0, 0, 0, 0))
    canvas.alpha_composite(resized, ((SIZE - new_w) // 2, (SIZE - new_h) // 2))
    return canvas


def main():
    DEST_DIR.mkdir(parents=True, exist_ok=True)
    OPTIONS_DIR.mkdir(parents=True, exist_ok=True)
    if not RAW_IMAGE.exists():
        raise FileNotFoundError(f"{RAW_IMAGE} 不存在")

    print(f"Processing NPC combined arms phalanx from {RAW_IMAGE}...")
    artwork = build_phalanx_model(RAW_IMAGE)

    dest_orthogonal = DEST_DIR / "npc-fortress-orthogonal.webp"
    dest_embedded = DEST_DIR / "npc-fortress-orthogonal-map-embedded.png"
    dest_fortress = DEST_DIR / "npc-fortress.webp"
    dest_fortress_map = DEST_DIR / "npc-fortress-map.webp"
    dest_fortress_embedded = DEST_DIR / "npc-fortress-map-embedded.png"
    dest_option1 = OPTIONS_DIR / "option-1-assault.png"

    artwork.save(dest_orthogonal, "WEBP", quality=94, method=6)
    print(f"Saved {dest_orthogonal}")

    artwork.save(dest_embedded, optimize=True)
    print(f"Saved {dest_embedded}")

    artwork.save(dest_fortress, "WEBP", quality=94, method=6)
    print(f"Saved {dest_fortress}")

    artwork.save(dest_fortress_map, "WEBP", quality=94, method=6)
    print(f"Saved {dest_fortress_map}")

    artwork.save(dest_fortress_embedded, optimize=True)
    print(f"Saved {dest_fortress_embedded}")

    artwork.save(dest_option1, optimize=True)
    print(f"Saved {dest_option1}")

    print("NPC phalanx model processing completed successfully!")


if __name__ == '__main__':
    main()
