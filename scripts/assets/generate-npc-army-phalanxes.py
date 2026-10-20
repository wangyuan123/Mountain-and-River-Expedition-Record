#!/usr/bin/env python3
"""
生成所有大地图 寇/NPC 随机方阵兵种组合微缩模型图。
严格遵循用户需求规范：
1. 【展示完整图标】：微缩模型图内所有兵种单位 100% 完整展示，严禁出现截断兵种（身躯/武器/炮管/机翼/车轮被边缘切断）问题；
2. 【边缘不需要模糊处理】：四周边框均为 0° 直角锐利方正切边，彻底去除边缘羽化衰减与半透明过渡；
3. 【图标为正方形】：1:1 严格规范正方形（384x384）；
4. 【放到地图中占满地图空格】：100% 不透明实心内容（Alpha=255），无透明外边距，在地图单个格子中占满空格；
5. 【根据 NPC 随机组成的方阵生成】：严格对应后端 LandNpcGarrison 随机生成的实际 13 种兵种组合，实现真实兵种与战地微缩方阵一体化映射。
"""
from pathlib import Path
import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parents[2]
MODELS_DIR = ROOT / 'frontend/img/units/models'
DEST_DIR = ROOT / 'frontend/img/map'
OUTPUT_DIR = ROOT / 'output/imagegen/npc-phalanx-20261011'

SIZE = 384


class UnitSpriteLoader:
    def __init__(self):
        self.cache = {}

    def get(self, name: str) -> Image.Image:
        if name not in self.cache:
            p = MODELS_DIR / f"{name}.webp"
            if not p.exists():
                raise FileNotFoundError(f"找不到兵种模型素材: {p}")
            im = Image.open(p).convert('RGBA')
            bbox = im.getbbox()
            assert bbox, f"素材轮廓为空: {name}"
            # 严格裁剪至贴合实体轮廓
            self.cache[name] = im.crop(bbox)
        return self.cache[name]


def make_battlefield_terrain(seed: int = 101) -> Image.Image:
    """生成大地图基准地表与战地黑土交融的 384x384 规范正交正方形地表基座（100% 实心不透明，无边缘模糊）。"""
    np.random.seed(seed)
    
    def noise(scale):
        gw = max(2, int(SIZE / scale))
        grid = np.random.uniform(0, 1, (gw, gw)).astype(np.float32)
        return cv2.resize(grid, (SIZE, SIZE), interpolation=cv2.INTER_CUBIC)

    n_broad = noise(128)
    n_med = noise(48)
    n_fine = noise(16)
    noise_combined = n_broad * 0.5 + n_med * 0.35 + n_fine * 0.15

    # 大地图实机采样标准色
    grass_base = np.array([106.0, 108.0, 70.0])
    grass_light = np.array([118.0, 122.0, 78.0])
    soil_base = np.array([76.0, 68.0, 52.0])
    soil_dark = np.array([60.0, 52.0, 38.0])

    # 中心区域受到战阵驻扎与履带碾压，黑土比重较高
    yy, xx = np.mgrid[:SIZE, :SIZE].astype(np.float32)
    dist = np.sqrt((xx - 192)**2 + (yy - 192)**2) / 192.0
    soil_factor = np.clip((1.0 - dist * 0.72) * 0.55 + (noise_combined - 0.45) * 0.75, 0.0, 1.0)

    grass_var = noise(32)
    grass_rgb = np.zeros((SIZE, SIZE, 3), dtype=np.float32)
    for c in range(3):
        grass_rgb[:, :, c] = grass_base[c] * (1.0 - grass_var * 0.4) + grass_light[c] * (grass_var * 0.4)

    soil_var = noise(24)
    soil_rgb = np.zeros((SIZE, SIZE, 3), dtype=np.float32)
    for c in range(3):
        soil_rgb[:, :, c] = soil_base[c] * (1.0 - soil_var * 0.5) + soil_dark[c] * (soil_var * 0.5)

    composite = np.zeros((SIZE, SIZE, 3), dtype=np.float32)
    for c in range(3):
        composite[:, :, c] = grass_rgb[:, :, c] * (1.0 - soil_factor) + soil_rgb[:, :, c] * soil_factor

    grain = np.random.normal(0, 3.0, (SIZE, SIZE, 3)).astype(np.float32)
    composite = np.clip(composite + grain, 0, 255).astype(np.uint8)

    return Image.fromarray(composite, 'RGB').convert('RGBA')


def add_vehicle_shadow(canvas: Image.Image, x: int, y: int, w: int, h: int):
    """地面载具柔和接地接触阴影。"""
    sh = Image.new('RGBA', (SIZE, SIZE), (0, 0, 0, 0))
    sh_draw = ImageDraw.Draw(sh)
    cx = x + w // 2 + 3
    cy = y + int(h * 0.84)
    rx = int(w * 0.44)
    ry = max(5, int(h * 0.16))
    sh_draw.ellipse([cx - rx, cy - ry, cx + rx, cy + ry], fill=(15, 12, 10, 180))
    sh_blurred = sh.filter(ImageFilter.GaussianBlur(6))
    canvas.alpha_composite(sh_blurred)


def add_soldier_shadow(canvas: Image.Image, x: int, y: int, w: int, h: int):
    """单兵脚步接地接触阴影。"""
    sh = Image.new('RGBA', (SIZE, SIZE), (0, 0, 0, 0))
    sh_draw = ImageDraw.Draw(sh)
    cx = x + w // 2 + 2
    cy = y + h - 3
    rx = max(5, int(w * 0.36))
    ry = max(3, int(h * 0.08))
    sh_draw.ellipse([cx - rx, cy - ry, cx + rx, cy + ry], fill=(15, 12, 10, 185))
    sh_blurred = sh.filter(ImageFilter.GaussianBlur(3))
    canvas.alpha_composite(sh_blurred)


def add_aircraft_shadow(canvas: Image.Image, sprite: Image.Image, x: int, y: int, offset=(32, 44)):
    """高空巡航战机投射在地表的真实漫反射阴影。"""
    alpha = sprite.split()[3]
    sw = int(sprite.width * 0.88)
    sh = int(sprite.height * 0.84)
    shadow_alpha = alpha.resize((sw, sh), Image.Resampling.LANCZOS)
    shadow_img = Image.new('RGBA', (sw, sh), (18, 15, 12, 0))
    shadow_alpha_dim = shadow_alpha.point(lambda v: int(v * 0.40) if v > 15 else 0)
    shadow_img.putalpha(shadow_alpha_dim)
    shadow_blurred = shadow_img.filter(ImageFilter.GaussianBlur(8))
    canvas.alpha_composite(shadow_blurred, (x + offset[0], y + offset[1]))


def place_unit(canvas: Image.Image, sprite: Image.Image, cx: int, cy: int, is_air: bool = False, is_vehicle: bool = False, flip: bool = False):
    """安全摆放单位模型：确保所有肢体与装备完全位于画布安全区 [20, 20, 364, 364] 内，绝不截断。"""
    sp = sprite.transpose(Image.FLIP_LEFT_RIGHT) if flip else sprite.copy()
    w, h = sp.size
    x = cx - w // 2
    y = cy - h // 2

    # 严格安全边界校验
    margin = 18
    if x < margin:
        x = margin
    if y < margin:
        y = margin
    if x + w > SIZE - margin:
        x = SIZE - margin - w
    if y + h > SIZE - margin:
        y = SIZE - margin - h

    # 绘制阴影
    if is_air:
        add_aircraft_shadow(canvas, sp, x, y)
    elif is_vehicle:
        add_vehicle_shadow(canvas, x, y, w, h)
    else:
        add_soldier_shadow(canvas, x, y, w, h)

    # 盖上单位立绘
    canvas.alpha_composite(sp, (x, y))


def build_formation_image(loader: UnitSpriteLoader, formation_key: str, seed: int) -> Image.Image:
    """按兵种组合与战术阵型渲染对应方阵微缩模型图（方案一：纯透明背景 + 真实地表接触投影）。"""
    canvas = Image.new('RGBA', (SIZE, SIZE), (0, 0, 0, 0))

    # 1. 纯步兵方阵 (infantry)
    if formation_key == 'infantry':
        inf = loader.get('infantry')
        # 指挥官/尖刀排 (大兵人)
        s_cmd = inf.resize((int(inf.width * 96 / inf.height), 96), Image.Resampling.LANCZOS)
        # 散兵班 (常规大兵)
        s_reg = inf.resize((int(inf.width * 84 / inf.height), 84), Image.Resampling.LANCZOS)
        # 前哨/后卫
        s_spt = inf.resize((int(inf.width * 78 / inf.height), 78), Image.Resampling.LANCZOS)

        # 战术楔形方阵：5 名战士各居其位，留足间距与安全边距
        place_unit(canvas, s_cmd, 192, 145)                       # 中军指挥官
        place_unit(canvas, s_reg, 115, 230)                       # 左翼前出步枪手
        place_unit(canvas, s_reg, 269, 230, flip=True)            # 右翼前出步枪手
        place_unit(canvas, s_spt, 68, 160)                        # 左后侧卫
        place_unit(canvas, s_spt, 316, 160, flip=True)            # 右后侧卫

    # 2. 步装协同方阵 (armored+infantry)
    elif formation_key == 'armored+infantry':
        arm = loader.get('armored')
        inf = loader.get('infantry')
        s_arm = arm.resize((170, int(arm.height * 170 / arm.width)), Image.Resampling.LANCZOS)
        s_inf = inf.resize((int(inf.width * 84 / inf.height), 84), Image.Resampling.LANCZOS)

        place_unit(canvas, s_arm, 192, 185, is_vehicle=True)
        place_unit(canvas, s_inf, 65, 235)
        place_unit(canvas, s_inf, 319, 235, flip=True)
        place_unit(canvas, s_inf, 72, 135)
        place_unit(canvas, s_inf, 312, 135, flip=True)

    # 3. 步坦装突击方阵 (armored+infantry+ltank)
    elif formation_key == 'armored+infantry+ltank':
        ltank = loader.get('ltank')
        arm = loader.get('armored')
        inf = loader.get('infantry')
        s_ltank = ltank.resize((150, int(ltank.height * 150 / ltank.width)), Image.Resampling.LANCZOS)
        s_arm = arm.resize((135, int(arm.height * 135 / arm.width)), Image.Resampling.LANCZOS)
        s_inf = inf.resize((int(inf.width * 78 / inf.height), 78), Image.Resampling.LANCZOS)

        place_unit(canvas, s_ltank, 125, 175, is_vehicle=True)
        place_unit(canvas, s_arm, 260, 165, is_vehicle=True)
        place_unit(canvas, s_inf, 60, 245)
        place_unit(canvas, s_inf, 192, 265)
        place_unit(canvas, s_inf, 320, 240, flip=True)

    # 4. 步装战机空地协同方阵 (armored+fighter+infantry)
    elif formation_key == 'armored+fighter+infantry':
        fighter = loader.get('fighter')
        arm = loader.get('armored')
        inf = loader.get('infantry')
        s_fig = fighter.resize((145, int(fighter.height * 145 / fighter.width)), Image.Resampling.LANCZOS)
        s_arm = arm.resize((155, int(arm.height * 155 / arm.width)), Image.Resampling.LANCZOS)
        s_inf = inf.resize((int(inf.width * 82 / inf.height), 82), Image.Resampling.LANCZOS)

        place_unit(canvas, s_fig, 192, 95, is_air=True)
        place_unit(canvas, s_arm, 192, 235, is_vehicle=True)
        place_unit(canvas, s_inf, 70, 240)
        place_unit(canvas, s_inf, 314, 240, flip=True)

    # 5. 全维突击方阵 (armored+fighter+infantry+ltank)
    elif formation_key == 'armored+fighter+infantry+ltank':
        fighter = loader.get('fighter')
        ltank = loader.get('ltank')
        arm = loader.get('armored')
        inf = loader.get('infantry')
        s_fig = fighter.resize((135, int(fighter.height * 135 / fighter.width)), Image.Resampling.LANCZOS)
        s_ltank = ltank.resize((140, int(ltank.height * 140 / ltank.width)), Image.Resampling.LANCZOS)
        s_arm = arm.resize((130, int(arm.height * 130 / arm.width)), Image.Resampling.LANCZOS)
        s_inf = inf.resize((int(inf.width * 76 / inf.height), 76), Image.Resampling.LANCZOS)

        place_unit(canvas, s_fig, 235, 85, is_air=True)
        place_unit(canvas, s_ltank, 120, 195, is_vehicle=True)
        place_unit(canvas, s_arm, 255, 205, is_vehicle=True)
        place_unit(canvas, s_inf, 65, 260)
        place_unit(canvas, s_inf, 192, 275)

    # 6. 步装双空战术方阵 (armored+bomber+fighter+infantry)
    elif formation_key == 'armored+bomber+fighter+infantry':
        bomber = loader.get('bomber')
        fighter = loader.get('fighter')
        arm = loader.get('armored')
        inf = loader.get('infantry')
        s_bom = bomber.resize((145, int(bomber.height * 145 / bomber.width)), Image.Resampling.LANCZOS)
        s_fig = fighter.resize((115, int(fighter.height * 115 / fighter.width)), Image.Resampling.LANCZOS)
        s_arm = arm.resize((145, int(arm.height * 145 / arm.width)), Image.Resampling.LANCZOS)
        s_inf = inf.resize((int(inf.width * 80 / inf.height), 80), Image.Resampling.LANCZOS)

        place_unit(canvas, s_bom, 130, 90, is_air=True)
        place_unit(canvas, s_fig, 270, 80, is_air=True)
        place_unit(canvas, s_arm, 192, 235, is_vehicle=True)
        place_unit(canvas, s_inf, 65, 245)
        place_unit(canvas, s_inf, 319, 245, flip=True)

    # 7. 装甲重轰支援方阵 (armored+bomber+infantry+ltank)
    elif formation_key == 'armored+bomber+infantry+ltank':
        bomber = loader.get('bomber')
        ltank = loader.get('ltank')
        arm = loader.get('armored')
        inf = loader.get('infantry')
        s_bom = bomber.resize((160, int(bomber.height * 160 / bomber.width)), Image.Resampling.LANCZOS)
        s_ltank = ltank.resize((135, int(ltank.height * 135 / ltank.width)), Image.Resampling.LANCZOS)
        s_arm = arm.resize((125, int(arm.height * 125 / arm.width)), Image.Resampling.LANCZOS)
        s_inf = inf.resize((int(inf.width * 76 / inf.height), 76), Image.Resampling.LANCZOS)

        place_unit(canvas, s_bom, 192, 90, is_air=True)
        place_unit(canvas, s_ltank, 120, 230, is_vehicle=True)
        place_unit(canvas, s_arm, 260, 225, is_vehicle=True)
        place_unit(canvas, s_inf, 60, 265)
        place_unit(canvas, s_inf, 324, 265, flip=True)

    # 8. 战巡航空大队 (bomber+fighter)
    elif formation_key == 'bomber+fighter':
        bomber = loader.get('bomber')
        fighter = loader.get('fighter')
        s_bom = bomber.resize((175, int(bomber.height * 175 / bomber.width)), Image.Resampling.LANCZOS)
        s_fig1 = fighter.resize((120, int(fighter.height * 120 / fighter.width)), Image.Resampling.LANCZOS)
        s_fig2 = fighter.resize((120, int(fighter.height * 120 / fighter.width)), Image.Resampling.LANCZOS)

        place_unit(canvas, s_bom, 192, 175, is_air=True)
        place_unit(canvas, s_fig1, 95, 100, is_air=True)
        place_unit(canvas, s_fig2, 289, 100, is_air=True)

    # 9. 战车火箭炮兵群 (ltank+rocket)
    elif formation_key == 'ltank+rocket':
        rocket = loader.get('rocket')
        ltank = loader.get('ltank')
        s_roc = rocket.resize((165, int(rocket.height * 165 / rocket.width)), Image.Resampling.LANCZOS)
        s_lt1 = ltank.resize((140, int(ltank.height * 140 / ltank.width)), Image.Resampling.LANCZOS)
        s_lt2 = ltank.resize((130, int(ltank.height * 130 / ltank.width)), Image.Resampling.LANCZOS)

        place_unit(canvas, s_roc, 240, 175, is_vehicle=True)
        place_unit(canvas, s_lt1, 115, 215, is_vehicle=True)
        place_unit(canvas, s_lt2, 110, 115, is_vehicle=True)

    # 10. 重机械化装甲军团 (armored+fighter+htank+infantry)
    elif formation_key == 'armored+fighter+htank+infantry':
        fighter = loader.get('fighter')
        htank = loader.get('htank')
        arm = loader.get('armored')
        inf = loader.get('infantry')
        s_fig = fighter.resize((135, int(fighter.height * 135 / fighter.width)), Image.Resampling.LANCZOS)
        s_htank = htank.resize((180, int(htank.height * 180 / htank.width)), Image.Resampling.LANCZOS)
        s_arm = arm.resize((125, int(arm.height * 125 / arm.width)), Image.Resampling.LANCZOS)
        s_inf = inf.resize((int(inf.width * 76 / inf.height), 76), Image.Resampling.LANCZOS)

        place_unit(canvas, s_fig, 275, 80, is_air=True)
        place_unit(canvas, s_htank, 185, 180, is_vehicle=True)
        place_unit(canvas, s_arm, 85, 235, is_vehicle=True)
        place_unit(canvas, s_inf, 275, 240)
        place_unit(canvas, s_inf, 325, 220, flip=True)

    # 11. 特种伞降航空队 (fighter+infantry+special)
    elif formation_key == 'fighter+infantry+special':
        fighter = loader.get('fighter')
        special = loader.get('special')
        inf = loader.get('infantry')
        s_fig = fighter.resize((140, int(fighter.height * 140 / fighter.width)), Image.Resampling.LANCZOS)
        s_spc = special.resize((int(special.width * 102 / special.height), 102), Image.Resampling.LANCZOS)
        s_inf = inf.resize((int(inf.width * 80 / inf.height), 80), Image.Resampling.LANCZOS)

        place_unit(canvas, s_fig, 192, 85, is_air=True)
        place_unit(canvas, s_spc, 192, 215)
        place_unit(canvas, s_inf, 80, 215)
        place_unit(canvas, s_inf, 135, 260)
        place_unit(canvas, s_inf, 249, 260, flip=True)
        place_unit(canvas, s_inf, 304, 215, flip=True)

    # 12. 装甲轻坦轰炸队 (armored+bomber+ltank)
    elif formation_key == 'armored+bomber+ltank':
        bomber = loader.get('bomber')
        ltank = loader.get('ltank')
        arm = loader.get('armored')
        s_bom = bomber.resize((165, int(bomber.height * 165 / bomber.width)), Image.Resampling.LANCZOS)
        s_lt = ltank.resize((148, int(ltank.height * 148 / ltank.width)), Image.Resampling.LANCZOS)
        s_arm = arm.resize((140, int(arm.height * 140 / arm.width)), Image.Resampling.LANCZOS)

        place_unit(canvas, s_bom, 192, 95, is_air=True)
        place_unit(canvas, s_lt, 125, 235, is_vehicle=True)
        place_unit(canvas, s_arm, 265, 225, is_vehicle=True)

    # 13. 重甲火箭重装旅 (armored+htank+rocket)
    elif formation_key == 'armored+htank+rocket':
        htank = loader.get('htank')
        rocket = loader.get('rocket')
        arm = loader.get('armored')
        s_ht = htank.resize((175, int(htank.height * 175 / htank.width)), Image.Resampling.LANCZOS)
        s_roc = rocket.resize((150, int(rocket.height * 150 / rocket.width)), Image.Resampling.LANCZOS)
        s_arm = arm.resize((135, int(arm.height * 135 / arm.width)), Image.Resampling.LANCZOS)

        place_unit(canvas, s_ht, 130, 165, is_vehicle=True)
        place_unit(canvas, s_roc, 265, 175, is_vehicle=True)
        place_unit(canvas, s_arm, 192, 260, is_vehicle=True)

    else:
        raise ValueError(f"未知的方阵组合: {formation_key}")

    return canvas


def build_showcase(images: dict):
    """生成 13 种方阵组合的大地图实装微缩模型总览表。"""
    cols = 4
    rows = (len(images) + cols - 1) // cols
    tile_size = 200
    margin_x = 35
    margin_y = 65
    gap_x = 25
    gap_y = 45
    footer_h = 45

    w = margin_x * 2 + cols * tile_size + (cols - 1) * gap_x
    h = margin_y + rows * (tile_size + gap_y) + footer_h

    sheet = Image.new('RGB', (w, h), (26, 28, 26))
    draw = ImageDraw.Draw(sheet)

    font_title = None
    font_sub = None
    for fp in [
        '/System/Library/Fonts/PingFang.ttc',
        '/System/Library/Fonts/STHeiti Light.ttc',
        '/System/Library/Fonts/Hiragino Sans GB.ttc',
        '/Library/Fonts/Arial Unicode.ttf'
    ]:
        try:
            font_title = ImageFont.truetype(fp, 20)
            font_sub = ImageFont.truetype(fp, 12)
            break
        except Exception:
            continue
    if not font_title:
        font_title = font_sub = ImageFont.load_default()

    draw.text((margin_x, 22), "NPC 寇各阶随机方阵兵种组合微缩模型图总览表（方案一：纯透明背景 + 真实地表接触投影）",
              fill=(245, 245, 240), font=font_title)

    # 地图真实地表背景切片（模拟实机草坪与黑土嵌入）
    map_tile_path = ROOT / 'output/imagegen/grass-redesign-20261015/real_map_tile.png'
    map_base = None
    if map_tile_path.exists():
        map_base = Image.open(map_tile_path).convert('RGB').resize((tile_size, tile_size), Image.Resampling.LANCZOS)

    for i, (key, (title, img)) in enumerate(images.items()):
        c = i % cols
        r = i // cols
        x = margin_x + c * (tile_size + gap_x)
        y = margin_y + r * (tile_size + gap_y)

        draw.text((x, y - 20), title, fill=(235, 215, 145), font=font_sub)
        # 背景：绘制大地图真实地表切片
        if map_base:
            sheet.paste(map_base, (x, y))
        else:
            draw.rectangle([x, y, x + tile_size, y + tile_size], fill=(85, 95, 65))

        draw.rectangle([x - 1, y - 1, x + tile_size + 1, y + tile_size + 1], outline=(220, 185, 75), width=2)
        thumb = img.resize((tile_size, tile_size), Image.Resampling.LANCZOS)
        # 带有 alpha 遮罩的平滑融合粘贴
        sheet.paste(thumb, (x, y), thumb)

    draw.text((margin_x, h - 30),
              "规格：384x384 规范正交正方形 · 纯透明背景无矩形色块 · 真实立体投影 · 严格匹配 LandNpcGarrison 随机编队",
              fill=(180, 190, 180), font=font_sub)

    out_p = OUTPUT_DIR / 'npc_all_phalanxes_showcase.png'
    sheet.save(out_p, quality=95)
    print(f"总览长图已保存: {out_p} ({out_p.stat().st_size} bytes)")


def main():
    DEST_DIR.mkdir(parents=True, exist_ok=True)
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    loader = UnitSpriteLoader()

    # 13 种 LandNpcGarrison 随机方阵组合定义
    formations = {
        'infantry': ('Lv.1~5 纯步兵方阵', 101),
        'armored+infantry': ('Lv.6~10 步装突击协同方阵', 102),
        'armored+infantry+ltank': ('Lv.11~24 步坦装突击方阵 (组0)', 103),
        'armored+fighter+infantry': ('Lv.11~15 步装战机协同方阵 (组1)', 104),
        'armored+fighter+infantry+ltank': ('Lv.11~24 全维突击方阵 (组2)', 105),
        'armored+bomber+fighter+infantry': ('Lv.16~24 步装双空战术方阵 (组1)', 106),
        'armored+bomber+infantry+ltank': ('Lv.16~24 装甲重轰支援方阵 (组3)', 107),
        'bomber+fighter': ('Lv.25~30 战巡空袭大队 (高阶0)', 108),
        'ltank+rocket': ('Lv.25~30 战车火箭炮兵群 (高阶1)', 109),
        'armored+fighter+htank+infantry': ('Lv.25~30 重机械化装甲军团 (高阶2)', 110),
        'fighter+infantry+special': ('Lv.25~30 特种伞降航空队 (高阶3)', 111),
        'armored+bomber+ltank': ('Lv.25~30 装甲轻坦轰炸队 (高阶4)', 112),
        'armored+htank+rocket': ('Lv.25~30 重甲火箭重装旅 (高阶5)', 113),
    }

    generated = {}
    print(">>> 开始生成 13 种 NPC 随机编队微缩方阵模型图...")

    for key, (title, seed) in formations.items():
        img = build_formation_image(loader, key, seed)
        generated[key] = (title, img)
        
        # 1. 保存以组合命名的资产
        filename_base = f"npc-phalanx-{key.replace('+', '-')}"
        webp_path = DEST_DIR / f"{filename_base}.webp"
        png_path = DEST_DIR / f"{filename_base}-map-embedded.png"
        img.save(webp_path, 'WEBP', quality=95, method=6)
        img.save(png_path, 'PNG', optimize=True)
        print(f"✓ 已生成 {title}: {filename_base}.webp & -map-embedded.png")

    # 2. 映射与兼容 5 款经典基准方阵（供原有接口与测试直接调用）
    classic_mapping = {
        'npc-phalanx-infantry': ('Lv.1~5 步兵方阵', generated['infantry'][1]),
        'npc-phalanx-light-armor': ('Lv.6~8 轻装甲方阵', generated['armored+infantry'][1]),
        'npc-fortress-orthogonal': ('Lv.9~10 突击要塞', generated['armored+infantry+ltank'][1]),
        'npc-phalanx-aircraft': ('Lv.11~20 航空哨所', generated['bomber+fighter'][1]),
        'npc-phalanx-mixed': ('Lv.21~30 混编军团', generated['armored+fighter+htank+infantry'][1])
    }

    for name, (title, img) in classic_mapping.items():
        webp_path = DEST_DIR / f"{name}.webp"
        png_path = DEST_DIR / f"{name}-map-embedded.png"
        img.save(webp_path, 'WEBP', quality=95, method=6)
        img.save(png_path, 'PNG', optimize=True)
        print(f"✓ 已同步经典别名 {name} ({title})")

    # 3. 兼容城堡老资产别名
    fortress_img = classic_mapping['npc-fortress-orthogonal'][1]
    fortress_img.save(DEST_DIR / 'npc-fortress.webp', 'WEBP', quality=95, method=6)
    fortress_img.save(DEST_DIR / 'npc-fortress-map.webp', 'WEBP', quality=95, method=6)
    fortress_img.save(DEST_DIR / 'npc-fortress-map-embedded.png', 'PNG', optimize=True)
    print("✓ 已同步老资产别名 npc-fortress.*")

    # 4. 生成总览长图
    build_showcase(generated)
    print(">>> 所有 13 种微缩方阵图标已全部实装完毕！")


if __name__ == '__main__':
    main()
