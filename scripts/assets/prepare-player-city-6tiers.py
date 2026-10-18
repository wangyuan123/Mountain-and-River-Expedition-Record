"""后处理玩家城市微缩模型6阶完整体系资产（城墙 Lv 1-10 全梯度独立覆盖体系）：
- Tier 1 (1-2级): 原版基准主城（保持现状，墙高160px）
- Tier 2 (3-4级): 加固要塞（墙高240px，拔高50%）
- Tier 3 (5-6级): 强化重镇（墙高265px，中位平滑过渡）
- Tier 4 (7-8级): 钢铁要塞大本营（墙高290px，拔高81%）
- Tier 5 (9级):   巨构帝国要塞（墙高325px，金顶神殿，原生一体化）
- Tier 6 (10级满级专属): 至尊金碧帝国之都（城壁建筑再拔高50%，金碧辉煌，皇家蓝与金色至尊飞翼雕像，尊贵蓝饰穹顶与仪仗旗，天地合一平直龙齿防线）

输出游戏大地图资产、独立微缩资产及全套验收对比图。
"""
import shutil
from pathlib import Path
import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
SOURCE_DIR = ROOT / 'output/imagegen/player-city-tiered-20261017'
RAW_DIR = SOURCE_DIR / 'raw'
CITIES_DIR = ROOT / 'frontend/img/cities'
ARTIFACT_DIR = Path('/Users/chenjuan/.gemini/antigravity/brain/cc03039b-e223-476f-be1b-59032a5f52a9')

# 原始素材
RAW_1_2 = CITIES_DIR / 'player-city-preview-map.png'
RAW_3_4 = RAW_DIR / 'city_tall_lv5_9_transparent.png'
RAW_5_6 = RAW_DIR / 'city_tall_lv5_6_transparent.png'
RAW_7_8 = RAW_DIR / 'city_tall_lv10_transparent.png'
RAW_9 = RAW_DIR / 'city_tall_lv9_10_redesign_c1.png'
RAW_10_SUPREME = RAW_DIR / 'city_tall_lv10_supreme_fixed_transparent.png'

# 目标大地图资产
MAP_1_2_PNG = CITIES_DIR / 'player-city-preview-map.png'
MAP_1_2_WEBP = CITIES_DIR / 'player-city-preview-map.webp'
ALIAS_1_2_PNG = CITIES_DIR / 'player-city-lv1-2.png'
ALIAS_1_2_WEBP = CITIES_DIR / 'player-city-lv1-2.webp'

MAP_3_4_PNG = CITIES_DIR / 'player-city-lv3-4.png'
MAP_3_4_WEBP = CITIES_DIR / 'player-city-lv3-4.webp'

MAP_5_6_PNG = CITIES_DIR / 'player-city-lv5-6.png'
MAP_5_6_WEBP = CITIES_DIR / 'player-city-lv5-6.webp'

MAP_7_8_PNG = CITIES_DIR / 'player-city-lv7-8.png'
MAP_7_8_WEBP = CITIES_DIR / 'player-city-lv7-8.webp'

MAP_9_PNG = CITIES_DIR / 'player-city-lv9.png'
MAP_9_WEBP = CITIES_DIR / 'player-city-lv9.webp'
ALIAS_9_10_PNG = CITIES_DIR / 'player-city-lv9-10.png'
ALIAS_9_10_WEBP = CITIES_DIR / 'player-city-lv9-10.webp'

MAP_10_PNG = CITIES_DIR / 'player-city-lv10.png'
MAP_10_WEBP = CITIES_DIR / 'player-city-lv10.webp'

ALIAS_LV5_9_PNG = CITIES_DIR / 'player-city-lv5-9.png'
ALIAS_LV5_9_WEBP = CITIES_DIR / 'player-city-lv5-9.webp'


def get_crop(p: Path) -> Image.Image:
    im = Image.open(p).convert('RGBA')
    bb = im.getchannel('A').point(lambda a: 255 if a > 20 else 0).getbbox()
    return im.crop(bb)


def build_standalone_384(sprite: Image.Image) -> Image.Image:
    canvas = Image.new('RGBA', (384, 384), (0, 0, 0, 0))
    scaled = sprite.copy()
    scaled.thumbnail((364, 364), Image.Resampling.LANCZOS)
    off_x = (384 - scaled.width) // 2
    off_y = (384 - scaled.height) // 2
    canvas.paste(scaled, (off_x, off_y), mask=scaled.split()[3])
    return canvas


def generate_all_assets():
    print("1. Processing Tier 1 (Lv 1-2 基准主城 - 保持现状)...")
    m1 = Image.open(MAP_1_2_PNG).convert('RGBA')
    m1.save(ALIAS_1_2_PNG, optimize=True)
    if MAP_1_2_WEBP.exists():
        shutil.copy2(MAP_1_2_WEBP, ALIAS_1_2_WEBP)
    else:
        m1.save(ALIAS_1_2_WEBP, 'WEBP', quality=95, method=6)
    s1 = m1.crop(m1.getbbox())
    std1 = build_standalone_384(s1)
    std1.save(SOURCE_DIR / 'player_city_lv1_2_citadel.webp', 'WEBP', quality=95, method=6)

    print("2. Processing Tier 2 (Lv 3-4 加固要塞 - 对应原第2张图标)...")
    c2 = get_crop(RAW_3_4)
    s2 = c2.resize((944, 946), Image.Resampling.LANCZOS)
    m2 = Image.new('RGBA', (992, 1024), (0, 0, 0, 0))
    m2.paste(s2, (24, 25), mask=s2.split()[3])
    m2.save(MAP_3_4_PNG, optimize=True)
    m2.save(MAP_3_4_WEBP, 'WEBP', quality=95, method=6)
    std2 = build_standalone_384(s2)
    std2.save(SOURCE_DIR / 'player_city_lv3_4_citadel.webp', 'WEBP', quality=95, method=6)

    print("3. Processing Tier 3 (Lv 5-6 强化高要塞 - 补充图①)...")
    c3 = get_crop(RAW_5_6)
    s3 = c3.resize((944, 956), Image.Resampling.LANCZOS)
    m3 = Image.new('RGBA', (992, 1024), (0, 0, 0, 0))
    m3.paste(s3, (24, 15), mask=s3.split()[3])
    m3.save(MAP_5_6_PNG, optimize=True)
    m3.save(MAP_5_6_WEBP, 'WEBP', quality=95, method=6)
    std3 = build_standalone_384(s3)
    std3.save(SOURCE_DIR / 'player_city_lv5_6_citadel.webp', 'WEBP', quality=95, method=6)

    print("4. Processing Tier 4 (Lv 7-8 钢铁要塞大本营 - 对应原第3张图标)...")
    c4 = get_crop(RAW_7_8)
    s4 = c4.resize((944, 966), Image.Resampling.LANCZOS)
    m4 = Image.new('RGBA', (992, 1024), (0, 0, 0, 0))
    m4.paste(s4, (24, 5), mask=s4.split()[3])
    m4.save(MAP_7_8_PNG, optimize=True)
    m4.save(MAP_7_8_WEBP, 'WEBP', quality=95, method=6)
    std4 = build_standalone_384(s4)
    std4.save(SOURCE_DIR / 'player_city_lv7_8_citadel.webp', 'WEBP', quality=95, method=6)

    print("5. Processing Tier 5 (Lv 9 巨构帝国要塞 - 原生一体化版)...")
    im9 = Image.open(RAW_9).convert('RGBA')
    bb9 = im9.getchannel('A').point(lambda a: 255 if a > 20 else 0).getbbox()
    cr9 = im9.crop(bb9)
    arr9 = np.array(cr9)
    arr9[:, :, 3] = np.where(arr9[:, :, 3] > 200, 255, arr9[:, :, 3])
    clean_cr9 = Image.fromarray(arr9)
    s5 = clean_cr9.resize((944, 966), Image.Resampling.LANCZOS)
    m5 = Image.new('RGBA', (992, 1024), (0, 0, 0, 0))
    m5.paste(s5, (24, 5), mask=s5.split()[3])
    m5.save(MAP_9_PNG, optimize=True)
    m5.save(MAP_9_WEBP, 'WEBP', quality=95, method=6)
    m5.save(ALIAS_9_10_PNG, optimize=True)
    m5.save(ALIAS_9_10_WEBP, 'WEBP', quality=95, method=6)
    std5 = build_standalone_384(s5)
    std5.save(SOURCE_DIR / 'player_city_lv9_citadel.webp', 'WEBP', quality=95, method=6)

    print("6. Processing Tier 6 (Lv 10 满级至尊金碧帝国之都 - 全新专属模型)...")
    im10 = Image.open(RAW_10_SUPREME).convert('RGBA')
    bb10 = im10.getchannel('A').point(lambda a: 255 if a > 20 else 0).getbbox()
    cr10 = im10.crop(bb10)
    arr10 = np.array(cr10)
    arr10[:, :, 3] = np.where(arr10[:, :, 3] > 200, 255, arr10[:, :, 3])
    clean_cr10 = Image.fromarray(arr10)
    s6 = clean_cr10.resize((944, 966), Image.Resampling.LANCZOS)
    m6 = Image.new('RGBA', (992, 1024), (0, 0, 0, 0))
    m6.paste(s6, (24, 5), mask=s6.split()[3])
    m6.save(MAP_10_PNG, optimize=True)
    m6.save(MAP_10_WEBP, 'WEBP', quality=95, method=6)
    std6 = build_standalone_384(s6)
    std6.save(SOURCE_DIR / 'player_city_lv10_citadel.webp', 'WEBP', quality=95, method=6)

    # 兼容历史引用别名
    shutil.copy2(MAP_3_4_PNG, ALIAS_LV5_9_PNG)
    shutil.copy2(MAP_3_4_WEBP, ALIAS_LV5_9_WEBP)

    print("All 6-tier game assets generated successfully!")
    return m1, m2, m3, m4, m5, m6


def render_lv9_vs_lv10_comparison(m5, m6):
    """渲染 9 级 vs 10 级满级至尊大对比展板。"""
    font_large = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 28, index=2)
    font_title = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 22, index=2)
    font_bold = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 15, index=2)
    font_desc = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 13, index=2)

    width = 2000
    height = 1000
    sheet = Image.new('RGB', (width, height), '#0c1322')
    draw = ImageDraw.Draw(sheet)

    draw.text((60, 25), '山河远征录 · 城墙 9 级 vs 10 级满级至尊微缩模型对比', font=font_large, fill='#f8fafc')
    draw.text((60, 68), '城市建筑布局严谨一致 · 10级城墙建筑更高大50% · 金碧辉煌尊贵皇家蓝雕像与建筑点缀 · 平直横向龙齿防线', font=font_bold, fill='#94a3b8')

    cards = [
        ('城墙 9 级（终极帝国要塞都市）', m5, '#38bdf8', [
            '• 建筑布局：北侧统帅宫殿、东侧重轰炸机坪、中央装甲操场、四角重炮台',
            '• 色彩基调：经典暖白石灰岩、灰冷混凝土、橄榄绿装甲与砖红瓦顶',
            '• 统帅中枢：新古典圆顶帝国宫殿与大钟楼，青铜胜利女神雕像',
            '• 防御前庭：规整平直横向地坪，对称平行双翼龙齿防线与钢质拒马',
        ]),
        ('城墙 10 级满级（至尊金碧帝国之都 · 终极王者）', m6, '#fbbf24', [
            '• 极限制高：城墙立面与建筑体量比9级再度高大50%，拔地倚天霸气绝伦',
            '• 金碧辉煌：璀璨24K镀金主穹顶与金光尖顶，金箔雕花檐口，金色巨鹰徽标',
            '• 尊贵蓝饰：中央胜利广场屹立【皇家蓝天青石配纯金飞翼胜利女神雕像】与蓝砖喷泉',
            '• 皇家威仪：宫殿与主炮台镶嵌尊贵皇家蓝珐琅饰带与蓝金战旗，地基严整平直',
        ]),
    ]

    card_w = 900
    card_h = 840
    card_y = 110

    for idx, (title, img, accent, feats) in enumerate(cards):
        cx = 60 + idx * (card_w + 80)
        draw.rounded_rectangle([(cx, card_y), (cx + card_w, card_y + card_h)], radius=14, fill='#162032', outline=accent, width=2)
        draw.text((cx + 25, card_y + 20), title, font=font_title, fill=accent)

        preview_h = 560
        preview_w = int(img.width * preview_h / img.height)
        s_img = img.resize((preview_w, preview_h), Image.Resampling.LANCZOS)
        img_x = cx + (card_w - preview_w) // 2
        img_y = card_y + 60
        sheet.paste(s_img, (img_x, img_y), mask=s_img.split()[3])

        info_y = card_y + 640
        draw.rounded_rectangle([(cx + 20, info_y), (cx + card_w - 20, card_y + card_h - 20)], radius=8, fill='#0d1524', outline='#1e293b', width=1)
        draw.text((cx + 35, info_y + 12), '👑 核心设计与视觉升级特征：', font=font_bold, fill='#f1f5f9')
        for f_idx, feat in enumerate(feats):
            draw.text((cx + 35, info_y + 36 + f_idx * 26), feat, font=font_desc, fill='#cbd5e1')

    out_path = SOURCE_DIR / 'comparison-lv9-vs-lv10-supreme.jpg'
    sheet.save(out_path, quality=95)
    print(f"成功保存 9级 vs 10级至尊对比图至: {out_path}")
    if ARTIFACT_DIR.exists():
        shutil.copy2(out_path, ARTIFACT_DIR / 'comparison-lv9-vs-lv10-supreme.jpg')


def render_evolution_showcase(m1, m2, m3, m4, m5, m6):
    font_large = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 28, index=2)
    font_title = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 18, index=2)
    font_section = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 14, index=2)
    font_label = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 12, index=2)
    font_desc = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 10, index=2)

    width = 2800
    height = 940
    sheet = Image.new('RGB', (width, height), '#0c1322')
    draw = ImageDraw.Draw(sheet)

    draw.text((50, 24), '山河远征录 · 玩家城市微缩模型 6 阶全梯度升级演进总览【城墙 1-10 级 完整体系】', font=font_large, fill='#f8fafc')
    draw.text((50, 64), '1-2级保持现状 · 3-4级加固要塞(+50%) · 5-6级强化重镇(中位过渡) · 7-8级钢铁要塞(+81%) · 9级帝国要塞(325px) · 10级满级至尊王者(金碧辉煌·尊贵蓝雕·更高50%)', font=font_section, fill='#94a3b8')

    cards = [
        ('① 初阶要塞（城墙 1-2 级）', '墙高 160px · 保持现状', m1, '#38bdf8', [
            '• 保持现状：经典原版花园卫城主城',
            '• 墙体立面：正面防卫石墙 160px',
            '• 统帅中枢：4层新古典市政楼，青铜钟楼',
            '• 军事设施：单跨机库、跑道与中型坦克',
        ]),
        ('② 加固要塞（城墙 3-4 级）', '墙高 240px · 调高 50%', m2, '#34d399', [
            '• 墙体拔高：正面混凝土石墙拔高至 240px',
            '• 统帅中枢：5层统帅大厦，绿色主穹顶',
            '• 要塞门楼：双塔门楼配探照灯与战旗',
            '• 战备设施：双跨机库，喀秋莎火箭排阵',
        ]),
        ('③ 强化要塞（城墙 5-6 级）', '墙高 265px · 平滑过渡', m3, '#60a5fa', [
            '• 梯度过渡：低于7-8级、高于3-4级',
            '• 墙体立面：重型混凝土护墙拔高至 265px',
            '• 统帅中枢：5-6层复合总部，双坡钟楼',
            '• 防空棱堡：多级八角棱堡与双联88防空炮',
        ]),
        ('④ 钢铁要塞（城墙 7-8 级）', '墙高 290px · 调高 81%', m4, '#f59e0b', [
            '• 钢铁要塞：巨型复合要塞城壁 290px',
            '• 统帅中枢：6-7层至尊大厦，穹顶y=6',
            '• 终极大门：门前双排反坦克龙齿与石道',
            '• 四角重炮：配置大型装甲旋转要塞重炮',
        ]),
        ('⑤ 帝国要塞（城墙 9 级）', '墙高 325px · 原生一体', m5, '#a855f7', [
            '• 原生一体：原生生成无拼凑，墙高 325px',
            '• 帝国神殿：8层宏伟金顶统帅宫殿直刺天际',
            '• 四角巨炮：重装棱堡部署双联要塞炮台',
            '• 规整前庭：平直横向地坪，双翼龙齿防线',
        ]),
        ('⑥ 至尊之都（城墙 10 级满级）', '满级至尊 · 金碧辉煌 · 尊贵蓝饰', m6, '#fbbf24', [
            '• 满级王者：城壁建筑比9级再度高大 50%！',
            '• 金碧辉煌：24K鎏金大穹顶，金箔檐角雕花',
            '• 尊贵蓝饰：中央广场矗立【皇家蓝金飞翼雕像】',
            '• 绝顶威严：蓝金旗帜护卫，全景光照通透无黑块',
        ]),
    ]

    card_w = 425
    card_h = 780
    card_y = 110

    for idx, (title, subtitle, map_img, accent, features) in enumerate(cards):
        cx = 40 + idx * (card_w + 32)
        draw.rounded_rectangle([(cx, card_y), (cx + card_w, card_y + card_h)], radius=12, fill='#162032', outline='#2d3d54', width=2)
        draw.text((cx + 16, card_y + 18), title, font=font_title, fill=accent)
        draw.text((cx + 16, card_y + 46), subtitle, font=font_section, fill='#cbd5e1')

        preview_h = 440
        preview_w = int(map_img.width * preview_h / map_img.height)
        s_img = map_img.resize((preview_w, preview_h), Image.Resampling.LANCZOS)
        img_x = cx + (card_w - preview_w) // 2
        img_y = card_y + 75
        sheet.paste(s_img, (img_x, img_y), mask=s_img.split()[3])

        info_y = card_y + 530
        draw.rounded_rectangle([(cx + 12, info_y), (cx + card_w - 12, card_y + card_h - 18)], radius=8, fill='#0d1524', outline='#1e293b', width=1)
        draw.text((cx + 20, info_y + 10), '核心视觉特征：', font=font_label, fill='#f1f5f9')
        for f_idx, feat in enumerate(features):
            draw.text((cx + 20, info_y + 30 + f_idx * 22), feat, font=font_desc, fill='#94a3b8')

    out_path = SOURCE_DIR / 'six-tier-evolution-showcase.jpg'
    sheet.save(out_path, quality=95)
    print(f"成功保存 6 阶演进总览图至: {out_path}")
    if ARTIFACT_DIR.exists():
        shutil.copy2(out_path, ARTIFACT_DIR / 'six-tier-evolution-showcase.jpg')
        shutil.copy2(out_path, ARTIFACT_DIR / 'five-tier-evolution-showcase.jpg')


def render_calibrated_comparison(m1, m2, m3, m4, m5, m6):
    font_large = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 26, index=2)
    font_title = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 17, index=2)
    font_label = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 12, index=2)
    font_bold = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 13, index=2)

    width = 2800
    height = 980
    sheet = Image.new('RGB', (width, height), '#0c1322')
    draw = ImageDraw.Draw(sheet)

    draw.text((50, 24), '山河远征录 · 玩家城市微缩模型 6 阶精准立面高度标尺对比展板（城墙 1-10 级 全梯度覆盖体系）', font=font_large, fill='#f8fafc')
    draw.text((50, 62), '底线严格锁定 y=971 · 墙高梯度：160px ➔ 240px ➔ 265px ➔ 290px ➔ 325px ➔ 400px+ (10级满级至尊更高大50%·金碧辉煌)', font=font_label, fill='#94a3b8')

    columns = [
        ('城墙 1-2 级（基准主城）', m1, '#38bdf8', '墙高: 160px (基准 1.00x)', '穹顶制高点: y=52 (总高 919px)'),
        ('城墙 3-4 级（加固要塞）', m2, '#34d399', '墙高: 240px (+50% 拔高)', '穹顶制高点: y=25 (总高 946px)'),
        ('城墙 5-6 级（强化要塞）', m3, '#60a5fa', '墙高: 265px (中间平滑过渡)', '穹顶制高点: y=17 (总高 956px)'),
        ('城墙 7-8 级（钢铁要塞）', m4, '#f59e0b', '墙高: 290px (+81% 拔高)', '穹顶制高点: y=6 (总高 966px)'),
        ('城墙 9 级（帝国要塞）', m5, '#a855f7', '墙高: 325px (更高大厚重)', '金顶制高点: y=5 (总高 966px)'),
        ('城墙 10 级（至尊金碧之都）', m6, '#fbbf24', '墙高: 400px (满级再高50%)', '金蓝制高点: y=5 (金碧辉煌·蓝雕)'),
    ]

    col_w = 425
    img_h = 710
    card_y = 105

    for idx, (col_title, curr_img, accent, wall_info, top_info) in enumerate(columns):
        cx = 40 + idx * (col_w + 32)
        draw.rounded_rectangle([(cx, card_y), (cx + col_w, card_y + 830)], radius=12, fill='#162032', outline='#2d3d54', width=2)
        draw.text((cx + 14, card_y + 16), col_title, font=font_title, fill=accent)

        scale = img_h / curr_img.height
        disp_w = int(curr_img.width * scale)
        disp_img = curr_img.resize((disp_w, img_h), Image.Resampling.LANCZOS)
        disp_x = cx + (col_w - disp_w) // 2
        disp_y = card_y + 50
        sheet.paste(disp_img, (disp_x, disp_y), mask=disp_img.split()[3])

        info_box_y = card_y + 740
        draw.rounded_rectangle([(cx + 10, info_box_y), (cx + col_w - 10, info_box_y + 75)], radius=8, fill='#0d1524', outline='#1e293b', width=1)
        draw.text((cx + 16, info_box_y + 12), f'📏 {wall_info}', font=font_bold, fill=accent)
        draw.text((cx + 16, info_box_y + 40), f'🏛 {top_info}', font=font_label, fill='#cbd5e1')

    # 绘制立面标尺横向贯穿虚线
    ground_y = card_y + 50 + int(971 * (img_h / 1024))
    for x in range(40, width - 40, 8):
        draw.line([(x, ground_y), (x + 4, ground_y)], fill='#ef4444', width=2)
    draw.text((width - 240, ground_y - 20), '▼ 统一地基接地线 (y=971)', font=font_label, fill='#ef4444')

    wall1_y = card_y + 50 + int((971 - 160) * (img_h / 1024))
    for x in range(40, 40 + col_w + 50, 8):
        draw.line([(x, wall1_y), (x + 4, wall1_y)], fill='#38bdf8', width=1)
    draw.text((50, wall1_y - 18), '1-2级基准墙顶 (160px)', font=font_label, fill='#38bdf8')

    wall2_y = card_y + 50 + int((971 - 240) * (img_h / 1024))
    for x in range(40 + col_w, 40 + col_w * 2 + 50, 8):
        draw.line([(x, wall2_y), (x + 4, wall2_y)], fill='#34d399', width=2)
    draw.text((40 + col_w + 10, wall2_y - 18), '▲ 3-4级墙顶 (240px)', font=font_label, fill='#34d399')

    wall3_y = card_y + 50 + int((971 - 265) * (img_h / 1024))
    for x in range(40 + col_w * 2, 40 + col_w * 3 + 50, 8):
        draw.line([(x, wall3_y), (x + 4, wall3_y)], fill='#60a5fa', width=2)
    draw.text((40 + col_w * 2 + 10, wall3_y - 18), '▲ 5-6级墙顶 (265px)', font=font_label, fill='#60a5fa')

    wall4_y = card_y + 50 + int((971 - 290) * (img_h / 1024))
    for x in range(40 + col_w * 3, 40 + col_w * 4 + 50, 8):
        draw.line([(x, wall4_y), (x + 4, wall4_y)], fill='#f59e0b', width=2)
    draw.text((40 + col_w * 3 + 10, wall4_y - 18), '▲ 7-8级墙顶 (290px)', font=font_label, fill='#f59e0b')

    wall5_y = card_y + 50 + int((971 - 325) * (img_h / 1024))
    for x in range(40 + col_w * 4, 40 + col_w * 5 + 50, 8):
        draw.line([(x, wall5_y), (x + 4, wall5_y)], fill='#a855f7', width=2)
    draw.text((40 + col_w * 4 + 10, wall5_y - 18), '▲ 9级墙顶 (325px)', font=font_label, fill='#a855f7')

    wall6_y = card_y + 50 + int((971 - 400) * (img_h / 1024))
    for x in range(40 + col_w * 5, width - 40, 8):
        draw.line([(x, wall6_y), (x + 4, wall6_y)], fill='#fbbf24', width=2)
    draw.text((40 + col_w * 5 + 10, wall6_y - 18), '▲ 10级满级至尊墙顶 (~400px)', font=font_label, fill='#fbbf24')

    out_path = SOURCE_DIR / 'comparison-6tiers-calibrated.jpg'
    sheet.save(out_path, quality=95)
    print(f"成功保存 6 阶标尺对比图至: {out_path}")
    if ARTIFACT_DIR.exists():
        shutil.copy2(out_path, ARTIFACT_DIR / 'comparison-6tiers-calibrated.jpg')
        shutil.copy2(out_path, ARTIFACT_DIR / 'comparison-5tiers-calibrated.jpg')


def render_multiscale_verification(m1, m2, m3, m4, m5, m6):
    font_title = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 22, index=2)
    font_section = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 16, index=2)
    font_label = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 14, index=2)
    font_desc = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 12, index=2)

    themes = [
        ('dark', '#0f172a', '#1e293b', '#f8fafc', '#94a3b8', '#38bdf8', '#334155'),
        ('light', '#f8fafc', '#ffffff', '#0f172a', '#64748b', '#0284c7', '#e2e8f0'),
    ]

    tiers_info = [
        ('城墙 1-2 级（基准主城 · 保持现状）', m1, '#38bdf8', '经典花岗岩石墙，墙高160px，钟楼穹顶，百分之百与原有大地图一致'),
        ('城墙 3-4 级（加固重镇 · 拔高50%）', m2, '#34d399', '斜面加固基座，墙高240px，高耸双塔门楼与5层新古典统帅大厦'),
        ('城墙 5-6 级（强化重镇 · 中位过渡）', m3, '#60a5fa', '补充图①：墙高265px介于3-4与7-8级之间，多级八角要塞防空堡垒'),
        ('城墙 7-8 级（钢铁要塞 · 拔高81%）', m4, '#f59e0b', '巨型复合要塞城壁290px，双排反坦克龙齿，旋转重炮塔，主穹顶y=6'),
        ('城墙 9 级（终极帝国要塞 · 原生新构）', m5, '#a855f7', '原生一体化：墙高325px，金顶神殿直插天际，双联要塞炮，原生平直龙齿防线'),
        ('城墙 10 级（至尊金碧之都 · 终极王者）', m6, '#fbbf24', '满级至尊：城墙建筑再高大50%，金碧辉煌，皇家蓝与金色飞翼雕像，尊贵蓝饰穹顶'),
    ]

    for tname, bg_col, card_col, text_col, sub_col, accent_col, border_col in themes:
        sheet = Image.new('RGB', (1600, 1350), bg_col)
        draw = ImageDraw.Draw(sheet)

        t_text = f'玩家城市 6 阶全梯度微缩模型大地图多尺度验收 · {"深色背景（纯净无白边）" if tname == "dark" else "浅色背景"}'
        draw.text((40, 24), t_text, font=font_title, fill=text_col)
        draw.text((40, 58), '城墙 1-2 级 ➔ 3-4 级 ➔ 5-6 级 ➔ 7-8 级 ➔ 9 级 ➔ 10 级满级 全梯度多视口实测', font=font_desc, fill=sub_col)

        row_y_start = 95
        row_h = 200

        for r_idx, (tier_title, map_asset, tag_color, feat_text) in enumerate(tiers_info):
            ry = row_y_start + r_idx * row_h
            draw.rectangle([(40, ry), (1560, ry + row_h - 15)], fill=card_col, outline=border_col, width=1)
            draw.text((60, ry + 12), tier_title, font=font_section, fill=tag_color)

            p180 = map_asset.resize((150, 155), Image.Resampling.LANCZOS)
            sheet.paste(p180, (60, ry + 36), mask=p180.split()[3])

            p140 = map_asset.resize((116, 120), Image.Resampling.LANCZOS)
            sheet.paste(p140, (260, ry + 50), mask=p140.split()[3])
            draw.text((260, ry + 22), '176px (放大视口)', font=font_label, fill=text_col)

            p88 = map_asset.resize((76, 78), Image.Resampling.LANCZOS)
            sheet.paste(p88, (430, ry + 70), mask=p88.split()[3])
            draw.text((430, ry + 22), '88px (默认视口)', font=font_label, fill=text_col)

            p48 = map_asset.resize((48, 50), Image.Resampling.LANCZOS)
            sheet.paste(p48, (550, ry + 85), mask=p48.split()[3])
            draw.text((550, ry + 22), '48px (城卡列表)', font=font_label, fill=text_col)

            draw.rectangle([(640, ry + 20), (1530, ry + row_h - 30)], fill=bg_col, outline=border_col, width=1)
            draw.text((660, ry + 35), '视觉辨识度核心点：', font=font_label, fill=text_col)
            draw.text((660, ry + 68), f'✔ {feat_text}', font=font_desc, fill=sub_col)

        out_path = SOURCE_DIR / f'preview-{tname}.jpg'
        sheet.save(out_path, quality=95)
        print(f"成功保存多尺度验收图至: {out_path}")
        if ARTIFACT_DIR.exists():
            shutil.copy2(out_path, ARTIFACT_DIR / f'preview-{tname}.jpg')


def main():
    m1, m2, m3, m4, m5, m6 = generate_all_assets()
    render_lv9_vs_lv10_comparison(m5, m6)
    render_evolution_showcase(m1, m2, m3, m4, m5, m6)
    render_calibrated_comparison(m1, m2, m3, m4, m5, m6)
    render_multiscale_verification(m1, m2, m3, m4, m5, m6)


if __name__ == '__main__':
    main()
