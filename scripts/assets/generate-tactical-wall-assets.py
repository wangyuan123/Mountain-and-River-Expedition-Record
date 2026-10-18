#!/usr/bin/env python3
"""
生成战术指挥面板真实 3D 俯视微缩要塞城墙防线资产：
1. 武器朝向全部朝向前方战场（防守迎敌方向）：
   - tactical-city-wall-left.webp（城墙在右，正面城门与草坡朝左）：4款武器全部朝向正前方战场（朝左迎敌）；
   - tactical-city-wall-right.webp（城墙在左，正面城门与草坡朝右）：4款武器全部朝向正前方战场（朝右迎敌）。
2. 四大城防武器微缩模型全部具备鲜明 3D 等轴外观：
   - 平台 1 (Flak 高空对空): 双联装重型高射炮，炮口迎击前方空地；
   - 平台 2 (Howitzer 远程压制): 152mm 重型曲射榴弹炮身管与重型双室制退器；
   - 平台 3 (Antitank 穿甲破甲): 细长大口径高速反坦克炮管、倾斜装甲防盾与炮口制退器；
   - 平台 4 (Bunker 永备碉堡): 钢筋混凝土弧形重装甲穹顶暗堡与突击射孔重炮身。
3. 武器平台中心 Y 轴百分比与指挥面板弧形光带精确像素级对齐：
   - 平台 1 (Flak): Y = 86px (8.96%)
   - 平台 2 (Howitzer): Y = 276px (28.75%)
   - 中央城门楼 (Gatehouse): Y = 480px (50.00%)
   - 平台 3 (Antitank): Y = 681px (70.94%)
   - 平台 4 (Bunker): Y = 872px (90.83%)
4. 占满指挥面板高度（960px）：
   - tactical-city-wall-left.webp: 正面朝左
   - tactical-city-wall-right.webp: 正面朝右
   - player-city-wall-front.webp: 保持兼容更新
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
SOURCE_IMAGE = ROOT / 'frontend/img/cities/player-city-preview.png'
ASSAULT_IMAGE = ROOT / 'frontend/img/units/models/assault.webp'
HTANK_IMAGE = ROOT / 'frontend/img/units/models/htank.webp'
DEST_DIR = ROOT / 'frontend/img/cities'

def extract_clean_horizontal_wall(im):
    mask = Image.new('L', im.size, 0)
    draw = ImageDraw.Draw(mask)
    poly = [
        # 西端八角棱堡完整外轮廓
        (25, 1024), (25, 840), (45, 792), (155, 792), (180, 808),
        # 西段城墙马道内壁
        (380, 808),
        # 城门楼北侧与双箭楼
        (395, 775), (440, 765), (580, 765), (625, 775),
        # 东段城墙马道内壁
        (640, 808),
        # 东端八角棱堡完整外轮廓
        (840, 808), (865, 805), (895, 805), (965, 805), (990, 835), (995, 1024)
    ]
    draw.polygon(poly, fill=255)
    mask = mask.filter(ImageFilter.GaussianBlur(radius=0.7))

    arr = np.array(im)
    arr[:, :, 3] = np.minimum(arr[:, :, 3], np.array(mask))
    wall_clean = Image.fromarray(arr, 'RGBA')
    bbox = wall_clean.getbbox()
    return wall_clean.crop(bbox)

def build_platforms(source_im, assault_im, htank_im):
    # 提取纯净八角石台
    crop_b = source_im.crop((20, 745, 195, 930)) # 175 x 185
    b_vert = crop_b.transpose(Image.Transpose.ROTATE_270) # 185 x 175

    # 裁剪八角外廓，消除储油罐
    mask_b = Image.new('L', b_vert.size, 0)
    draw_b = ImageDraw.Draw(mask_b)
    poly = [
        (0, 84), (25, 20), (55, 12), (115, 12), (148, 45),
        (150, 125), (115, 158), (55, 158), (25, 148), (0, 84)
    ]
    draw_b.polygon(poly, fill=255)
    mask_b = mask_b.filter(ImageFilter.GaussianBlur(0.8))

    arr_b = np.array(b_vert)
    arr_b[:, :, 3] = np.minimum(arr_b[:, :, 3], np.array(mask_b))
    clean_platform = Image.fromarray(arr_b)

    # 平台中心：(104, 96)
    cx, cy = 104, 96

    # 彻底抹除原画朝北炮管在垂直视角下的残影
    src = clean_platform.crop((cx + 10, cy + 15, cx + 42, cy + 45)).transpose(Image.Transpose.FLIP_TOP_BOTTOM)
    mask_erase = Image.new('L', (32, 30), 0)
    d_e = ImageDraw.Draw(mask_erase)
    d_e.polygon([(0, 25), (10, 0), (32, 0), (25, 30)], fill=255)
    mask_erase = mask_erase.filter(ImageFilter.GaussianBlur(1.5))
    clean_platform.paste(src, (cx + 8, cy - 38), mask_erase)

    # 绘制纯净钢质齿圈基盘
    draw_plate = clean_platform.copy()
    d = ImageDraw.Draw(draw_plate)
    d.ellipse([cx - 21, cy - 21, cx + 21, cy + 21], fill=(38, 42, 34, 255), outline=(22, 25, 20, 255))
    d.ellipse([cx - 19, cy - 19, cx + 19, cy + 19], fill=(58, 66, 50, 255), outline=(36, 42, 30, 255))
    d.ellipse([cx - 11, cy - 11, cx + 11, cy + 11], fill=(46, 52, 38, 255), outline=(28, 32, 24, 255))

    # --- 平台 1: 防空炮 (Flak) ---
    unproj_orig = crop_b.resize((crop_b.width, int(crop_b.height * 1.44)), Image.Resampling.LANCZOS)
    ucx, ucy = 80, 136
    rot_turret = unproj_orig.crop((ucx - 75, ucy - 75, ucx + 75, ucy + 75)).rotate(155, resample=Image.Resampling.BICUBIC)
    gun_155 = rot_turret.resize((90, 90), Image.Resampling.LANCZOS).transpose(Image.Transpose.ROTATE_270)

    g_arr = np.array(gun_155)
    gw, gh = gun_155.size
    gcx, gcy = 45, 45
    y_idx, x_idx = np.ogrid[:gh, :gw]
    dist = np.sqrt((x_idx - gcx)**2 + (y_idx - gcy)**2)
    is_floor = (g_arr[:, :, 0] > 80) & (g_arr[:, :, 1] > 80) & (g_arr[:, :, 2] > 80) & (np.abs(g_arr[:, :, 0].astype(int) - g_arr[:, :, 1].astype(int)) < 15)
    is_dark = (g_arr[:, :, 0] < 55) & (g_arr[:, :, 1] < 55) & (g_arr[:, :, 2] < 55) & (g_arr[:, :, 3] > 150)
    is_green = (g_arr[:, :, 1] > 32) & (g_arr[:, :, 1] > g_arr[:, :, 0] - 5) & (g_arr[:, :, 1] > g_arr[:, :, 2] - 5)
    flak_gun_only = (dist <= 21.0) & (is_green | is_dark) & (~is_floor)
    barrel_ext = (x_idx <= gcx) & (x_idx >= 4) & (y_idx >= gcy - 12) & (y_idx <= gcy + 6) & (is_dark | is_green)
    clean_flak_mask = Image.fromarray(((flak_gun_only | barrel_ext).astype(np.uint8) * 255), mode='L').filter(ImageFilter.GaussianBlur(0.5))
    flak_sprite = Image.new('RGBA', (gw, gh), (0, 0, 0, 0))
    flak_sprite.paste(gun_155, (0, 0), clean_flak_mask)
    p1_flak = draw_plate.copy()
    p1_flak.alpha_composite(flak_sprite, (cx - gcx, cy - gcy))

    # --- 平台 2: 榴弹炮 (Howitzer) ---
    mask_h = Image.new('L', assault_im.size, 0)
    d_h = ImageDraw.Draw(mask_h)
    d_h.polygon([
        (0, 195), (55, 175), (105, 145), (165, 135), (185, 160),
        (185, 225), (145, 240), (105, 235), (45, 255), (0, 240)
    ], fill=255)
    mask_h = mask_h.filter(ImageFilter.GaussianBlur(1.0))
    h_raw = Image.new('RGBA', assault_im.size, (0, 0, 0, 0))
    h_raw.paste(assault_im, (0, 0), mask_h)
    h_gun = h_raw.crop(h_raw.getbbox())
    h_scale = 62.0 / h_gun.width
    h_gun = h_gun.resize((int(h_gun.width * h_scale), int(h_gun.height * h_scale)), Image.Resampling.LANCZOS)
    p2_howitzer = draw_plate.copy()
    p2_howitzer.alpha_composite(h_gun, (cx - h_gun.width + 24, cy - h_gun.height // 2 - 2))

    # --- 平台 3: 反坦克炮 (Anti-Tank) ---
    mask_at = Image.new('L', htank_im.size, 0)
    d_at = ImageDraw.Draw(mask_at)
    d_at.polygon([
        (0, 190), (45, 175), (135, 140), (185, 125), (220, 130),
        (220, 185), (160, 205), (135, 200), (45, 240), (0, 225)
    ], fill=255)
    mask_at = mask_at.filter(ImageFilter.GaussianBlur(1.0))
    at_raw = Image.new('RGBA', htank_im.size, (0, 0, 0, 0))
    at_raw.paste(htank_im, (0, 0), mask_at)
    at_gun = at_raw.crop(at_raw.getbbox())
    at_scale = 75.0 / at_gun.width
    at_gun = at_gun.resize((int(at_gun.width * at_scale), int(at_gun.height * at_scale)), Image.Resampling.LANCZOS)
    p3_antitank = draw_plate.copy()
    p3_antitank.alpha_composite(at_gun, (cx - at_gun.width + 26, cy - at_gun.height // 2 - 2))

    # --- 平台 4: 碉堡 (Bunker) ---
    mask_bk = Image.new('L', htank_im.size, 0)
    d_bk = ImageDraw.Draw(mask_bk)
    d_bk.ellipse([140, 95, 255, 195], fill=255)
    d_bk.polygon([(85, 155), (150, 140), (150, 175), (85, 175)], fill=255)
    mask_bk = mask_bk.filter(ImageFilter.GaussianBlur(1.2))
    bk_raw = Image.new('RGBA', htank_im.size, (0, 0, 0, 0))
    bk_raw.paste(htank_im, (0, 0), mask_bk)
    bk_gun = bk_raw.crop(bk_raw.getbbox())
    bk_scale = 56.0 / bk_gun.width
    bk_gun = bk_gun.resize((int(bk_gun.width * bk_scale), int(bk_gun.height * bk_scale)), Image.Resampling.LANCZOS)
    p4_bunker = draw_plate.copy()
    p4_bunker.alpha_composite(bk_gun, (cx - bk_gun.width + 28, cy - bk_gun.height // 2 - 2))

    return p1_flak, p2_howitzer, p3_antitank, p4_bunker, cx, cy

def main():
    if not SOURCE_IMAGE.exists():
        raise FileNotFoundError(f"Source image not found: {SOURCE_IMAGE}")

    im = Image.open(SOURCE_IMAGE)
    assault_im = Image.open(ASSAULT_IMAGE)
    htank_im = Image.open(HTANK_IMAGE)

    cropped_wall = extract_clean_horizontal_wall(im)

    target_h = 960
    scale = target_h / cropped_wall.width
    scaled_w = int(cropped_wall.height * scale)

    base_wall_left = cropped_wall.transpose(Image.Transpose.ROTATE_270)
    base_wall_left = base_wall_left.resize((scaled_w, target_h), Image.Resampling.LANCZOS)

    # 清除基底上下两端原画中朝北炮管的残影
    for cy_val in [86, 872]:
        src = base_wall_left.crop((154 + 10, cy_val + 15, 154 + 42, cy_val + 45)).transpose(Image.Transpose.FLIP_TOP_BOTTOM)
        mask_m = Image.new('L', (32, 30), 0)
        d_m = ImageDraw.Draw(mask_m)
        d_m.polygon([(0, 25), (10, 0), (32, 0), (25, 30)], fill=255)
        mask_m = mask_m.filter(ImageFilter.GaussianBlur(1.5))
        base_wall_left.paste(src, (154 + 8, cy_val - 38), mask_m)

    p1, p2, p3, p4, cx, cy = build_platforms(im, assault_im, htank_im)

    scale_plat = scale * 0.95
    pw = int(p1.width * scale_plat)
    ph = int(p1.height * scale_plat)
    cx_s = int(cx * scale_plat)
    cy_s = int(cy * scale_plat)

    p1_s = p1.resize((pw, ph), Image.Resampling.LANCZOS)
    p2_s = p2.resize((pw, ph), Image.Resampling.LANCZOS)
    p3_s = p3.resize((pw, ph), Image.Resampling.LANCZOS)
    p4_s = p4.resize((pw, ph), Image.Resampling.LANCZOS)

    pos_x = 154 - cx_s

    final_left = base_wall_left.copy()
    # 平台发射中心与战术面板百分比 100% 像素级对齐：
    # - 平台 1 (防空炮 Flak): Y = 86px (8.96%)
    # - 平台 2 (榴弹炮 Howitzer): Y = 276px (28.75%)
    # - 平台 3 (反坦克炮 Antitank): Y = 681px (70.94%)
    # - 平台 4 (碉堡 Bunker): Y = 872px (90.83%)
    final_left.alpha_composite(p1_s, (pos_x, 86 - cy_s))
    final_left.alpha_composite(p2_s, (pos_x, 276 - cy_s))
    final_left.alpha_composite(p3_s, (pos_x, 681 - cy_s))
    final_left.alpha_composite(p4_s, (pos_x, 872 - cy_s))

    final_right = final_left.transpose(Image.Transpose.FLIP_LEFT_RIGHT)

    DEST_DIR.mkdir(parents=True, exist_ok=True)

    left_path = DEST_DIR / 'tactical-city-wall-left.webp'
    final_left.save(left_path, format='WEBP', quality=95, method=6)
    print(f"Saved Forward-Facing Left wall: {left_path} ({left_path.stat().st_size} bytes)")

    right_path = DEST_DIR / 'tactical-city-wall-right.webp'
    final_right.save(right_path, format='WEBP', quality=95, method=6)
    print(f"Saved Forward-Facing Right wall: {right_path} ({right_path.stat().st_size} bytes)")

    compat_path = DEST_DIR / 'player-city-wall-front.webp'
    final_left.save(compat_path, format='WEBP', quality=95, method=6)
    print(f"Saved Compat wall: {compat_path} ({compat_path.stat().st_size} bytes)")

if __name__ == '__main__':
    main()
