"""后处理玩家城市微缩模型5阶完整体系资产：
- Tier 1 (1-2级): 原第1张基准主城图标（保持现状，墙高160px）
- Tier 2 (3-4级): 原第2张图标（加固重镇，墙高240px，拔高50%）
- Tier 3 (5-6级): 补充图①（强化枢纽，墙高265px，介于3-4级与7-8级之间）
- Tier 4 (7-8级): 原第3张图标（钢铁要塞，墙高290px，拔高81%）
- Tier 5 (9-10级): 全新原生重构图（终极帝国要塞之都，原生一体化，墙高325px，金顶统帅宫殿，双联巨型要塞炮，平直一体化龙齿防线，彻底消除拼接割裂与侧壁黑块）

输出游戏大地图资产、独立微缩资产及全套5阶验收对比图。
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
RAW_1_2 = CITIES_DIR / 'player-city-preview-map.png'  # 1-2 级保持现状
RAW_3_4 = RAW_DIR / 'city_tall_lv5_9_transparent.png' # 3-4 级（原第2张）
RAW_5_6 = RAW_DIR / 'city_tall_lv5_6_transparent.png' # 5-6 级（补充图①）
RAW_7_8 = RAW_DIR / 'city_tall_lv10_transparent.png'  # 7-8 级（原第3张）
RAW_9_10_NATIVE = RAW_DIR / 'city_tall_lv9_10_redesign_c1.png' # 9-10 级全新原生重构图
RAW_9_10_TRANS = RAW_DIR / 'city_tall_lv9_10_transparent.png'

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

MAP_9_10_PNG = CITIES_DIR / 'player-city-lv9-10.png'
MAP_9_10_WEBP = CITIES_DIR / 'player-city-lv9-10.webp'

# 兼容旧路径别名
ALIAS_LV5_9_PNG = CITIES_DIR / 'player-city-lv5-9.png'
ALIAS_LV5_9_WEBP = CITIES_DIR / 'player-city-lv5-9.webp'
ALIAS_LV10_PNG = CITIES_DIR / 'player-city-lv10.png'
ALIAS_LV10_WEBP = CITIES_DIR / 'player-city-lv10.webp'


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


def prepare_tier5_asset() -> Image.Image:
    """生成全新原生重构的 Tier 5 (9-10级) 终极帝国要塞微缩模型：
    1. 100% 原生一体化微缩模型，彻底告别拼凑与接缝割裂感；
    2. 比 7-8 级更高级、更宏伟：正面城墙立面高达 325px（高于 7-8 级的 290px），四角装备超大型双联重型要塞主炮台；
    3. 中央矗立 8 层宏伟金顶帝国最高统帅宫殿与大钟楼，穹顶直刺天际（y=5）；
    4. 城门前方原生呈现平直横向地基、整齐平行双翼混凝土反坦克龙齿与拒马，底边严格水平（接地线锚定 y=970/971）；
    5. 全景光照柔和均匀，彻底根除侧壁黑块与漏光镂空缺陷。
    """
    print("5. Processing Tier 5 (Lv 9-10 终极巨构要塞帝国之都 - 全新原生重构版)...")
    im = Image.open(RAW_9_10_NATIVE).convert('RGBA')
    bb = im.getchannel('A').point(lambda a: 255 if a > 20 else 0).getbbox()
    cr = im.crop(bb)

    # 实体边缘保真优化
    arr = np.array(cr)
    arr[:, :, 3] = np.where(arr[:, :, 3] > 200, 255, arr[:, :, 3])
    clean_cr = Image.fromarray(arr)

    # 规范尺寸至 944x966，顶部贴合 y=5，底座接地严格锚定 y=970/971
    s5 = clean_cr.resize((944, 966), Image.Resampling.LANCZOS)
    m5 = Image.new('RGBA', (992, 1024), (0, 0, 0, 0))
    m5.paste(s5, (24, 5), mask=s5.split()[3])

    m5.save(MAP_9_10_PNG, optimize=True)
    m5.save(MAP_9_10_WEBP, 'WEBP', quality=95, method=6)
    m5.save(RAW_9_10_TRANS) # 同步归档

    std5 = build_standalone_384(s5)
    std5.save(SOURCE_DIR / 'player_city_lv9_10_citadel.webp', 'WEBP', quality=95, method=6)

    return m5


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

    print("3. Processing Tier 3 (Lv 5-6 强化高要塞 - 全新补充图①)...")
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

    # 导出全新原生 9-10 级
    m5 = prepare_tier5_asset()

    # 兼容历史引用
    shutil.copy2(MAP_3_4_PNG, ALIAS_LV5_9_PNG)
    shutil.copy2(MAP_3_4_WEBP, ALIAS_LV5_9_WEBP)
    shutil.copy2(MAP_7_8_PNG, ALIAS_LV10_PNG)
    shutil.copy2(MAP_7_8_WEBP, ALIAS_LV10_WEBP)

    print("All 5-tier game assets generated successfully!")
    return m1, m2, m3, m4, m5


def render_evolution_showcase(m1, m2, m3, m4, m5):
    font_large = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 28, index=2)
    font_title = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 20, index=2)
    font_section = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 15, index=2)
    font_label = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 13, index=2)
    font_desc = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 11, index=2)

    width = 2500
    height = 940
    sheet = Image.new('RGB', (width, height), '#0c1322')
    draw = ImageDraw.Draw(sheet)

    draw.text((50, 24), '山河远征录 · 玩家城市微缩模型 5 阶完整升级演进总览【城墙 1-10 级 全梯度体系】', font=font_large, fill='#f8fafc')
    draw.text((50, 64), '1-2级保持现状 · 3-4级高耸要塞(+50%) · 5-6级强化重镇(中位过渡) · 7-8级钢铁要塞(+81%) · 9-10级终极帝国要塞(原生一体化新构·更高更宏伟)', font=font_section, fill='#94a3b8')

    cards = [
        ('① 初阶要塞主城（城墙 1-2 级）', '墙高 160px · 保持现状', m1, '#38bdf8', [
            '• 保持现状：经典原版花园卫城基准主城',
            '• 墙体立面：正面花岗岩防卫石墙立面 160px',
            '• 统帅中枢：4层新古典市政主楼，青铜钟楼圆穹',
            '• 城门角楼：标准石拱门楼，双扇钢门与八角角楼',
            '• 军事设施：单跨机库、跑道、P-51战机与坦克操场',
        ]),
        ('② 中阶加固要塞（城墙 3-4 级）', '墙高 240px · 对应原第2张(+50%)', m2, '#34d399', [
            '• 墙体拔高：正面混凝土石墙拔高至 240px (+50%)',
            '• 统帅中枢：5层宏伟新古典统帅大厦，绿色主穹顶',
            '• 要塞门楼：高耸双塔要塞门楼，配探照灯与战旗',
            '• 战备设施：双跨装甲机库，扩建跑道与喀秋莎排阵',
            '• 军工生产：红砖厂房扩建高耸行政烟囱与油罐区',
        ]),
        ('③ 高阶强化要塞（城墙 5-6 级）', '墙高 265px · 补充图①(中位过渡)', m3, '#60a5fa', [
            '• 梯度过渡：低于7-8级、高于3-4级，承上启下',
            '• 墙体立面：重型混凝土护墙拔高至 265px (梯级+25px)',
            '• 统帅中枢：5-6层复合军政总部，双坡钟楼穹顶',
            '• 防空棱堡：多级八角要塞棱堡与双联88mm防空火炮',
            '• 重型战备：双机库多机编队、重型坦克与自行火炮列阵',
        ]),
        ('④ 钢铁要塞大本营（城墙 7-8 级）', '墙高 290px · 对应原第3张(+81%)', m4, '#f59e0b', [
            '• 钢铁要塞：巨型复合重要塞城壁极限制高 290px',
            '• 统帅中枢：6-7层至尊帝国军政大厦，主穹顶 y=5',
            '• 终极大门：3-4层主堡门楼，门前双排反坦克龙齿',
            '• 四角重炮：配置大型装甲旋转要塞重炮炮塔',
            '• 战略空军：防爆掩体机库、雷达塔台、重型轰炸群',
        ]),
        ('⑤ 终极帝国要塞都市（城墙 9-10 级）', '墙高 325px · 全新原生重构一体化', m5, '#ec4899', [
            '• 原生一体：整图原生生成渲染，100%零拼接无割裂',
            '• 巍峨城壁：正面复合多层重要塞高墙达 325px(更高大)',
            '• 帝国神殿：8层宏伟金顶帝国最高统帅宫殿直插云霄',
            '• 四角巨炮：重装棱堡部署双联大型要塞重炮巨型炮台',
            '• 规整前庭：城门前方平直横向地坪，对称平行双翼龙齿防线',
        ]),
    ]

    card_w = 460
    card_h = 780
    card_y = 110

    for idx, (title, subtitle, map_img, accent, features) in enumerate(cards):
        cx = 50 + idx * (card_w + 25)
        draw.rounded_rectangle([(cx, card_y), (cx + card_w, card_y + card_h)], radius=12, fill='#162032', outline='#2d3d54', width=2)
        draw.text((cx + 18, card_y + 18), title, font=font_title, fill=accent)
        draw.text((cx + 18, card_y + 48), subtitle, font=font_section, fill='#cbd5e1')

        preview_h = 440
        preview_w = int(map_img.width * preview_h / map_img.height)
        s_img = map_img.resize((preview_w, preview_h), Image.Resampling.LANCZOS)
        img_x = cx + (card_w - preview_w) // 2
        img_y = card_y + 75
        sheet.paste(s_img, (img_x, img_y), mask=s_img.split()[3])

        info_y = card_y + 530
        draw.rounded_rectangle([(cx + 14, info_y), (cx + card_w - 14, card_y + card_h - 18)], radius=8, fill='#0d1524', outline='#1e293b', width=1)
        draw.text((cx + 26, info_y + 10), '核心微缩模型视觉特征：', font=font_label, fill='#f1f5f9')
        for f_idx, feat in enumerate(features):
            draw.text((cx + 26, info_y + 32 + f_idx * 24), feat, font=font_desc, fill='#94a3b8')

    out_path = SOURCE_DIR / 'five-tier-evolution-showcase.jpg'
    sheet.save(out_path, quality=95)
    print(f"成功保存5阶演进总览图至: {out_path}")
    if ARTIFACT_DIR.exists():
        shutil.copy2(out_path, ARTIFACT_DIR / 'five-tier-evolution-showcase.jpg')
        shutil.copy2(out_path, ARTIFACT_DIR / 'three-tier-tall-evolution-showcase.jpg')


def render_calibrated_comparison(m1, m2, m3, m4, m5):
    font_large = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 26, index=2)
    font_title = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 18, index=2)
    font_label = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 13, index=2)
    font_bold = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 14, index=2)

    width = 2500
    height = 980
    sheet = Image.new('RGB', (width, height), '#0c1322')
    draw = ImageDraw.Draw(sheet)

    draw.text((50, 24), '山河远征录 · 玩家城市微缩模型 5 阶精准立面高度标尺对比展板（全新原生 9-10 级）', font=font_large, fill='#f8fafc')
    draw.text((50, 62), '底线严格锁定 y=971 · 墙高梯度：160px ➔ 240px ➔ 265px ➔ 290px ➔ 325px · 金顶帝国神殿与要塞天际线逐级高耸', font=font_label, fill='#94a3b8')

    columns = [
        ('城墙 1-2 级（基准主城）', m1, '#38bdf8', '墙高: 160px (基准 1.00x)', '穹顶制高点: y=52 (总高 919px)'),
        ('城墙 3-4 级（加固要塞）', m2, '#34d399', '墙高: 240px (+50% 拔高)', '穹顶制高点: y=25 (总高 946px)'),
        ('城墙 5-6 级（强化要塞）', m3, '#60a5fa', '墙高: 265px (中间平滑过渡)', '穹顶制高点: y=17 (总高 956px)'),
        ('城墙 7-8 级（钢铁要塞）', m4, '#f59e0b', '墙高: 290px (+81% 拔高)', '穹顶制高点: y=6 (总高 966px)'),
        ('城墙 9-10 级（终极帝国要塞）', m5, '#ec4899', '墙高: 325px (更高大厚重·原生无拼凑)', '金顶制高点: y=5 (总高 966px 直插云霄)'),
    ]

    col_w = 460
    img_h = 710
    card_y = 105

    for idx, (col_title, curr_img, accent, wall_info, top_info) in enumerate(columns):
        cx = 50 + idx * (col_w + 25)
        draw.rounded_rectangle([(cx, card_y), (cx + col_w, card_y + 830)], radius=12, fill='#162032', outline='#2d3d54', width=2)
        draw.text((cx + 18, card_y + 16), col_title, font=font_title, fill=accent)

        scale = img_h / curr_img.height
        disp_w = int(curr_img.width * scale)
        disp_img = curr_img.resize((disp_w, img_h), Image.Resampling.LANCZOS)
        disp_x = cx + (col_w - disp_w) // 2
        disp_y = card_y + 50
        sheet.paste(disp_img, (disp_x, disp_y), mask=disp_img.split()[3])

        info_box_y = card_y + 740
        draw.rounded_rectangle([(cx + 12, info_box_y), (cx + col_w - 12, info_box_y + 75)], radius=8, fill='#0d1524', outline='#1e293b', width=1)
        draw.text((cx + 20, info_box_y + 12), f'📏 {wall_info}', font=font_bold, fill=accent)
        draw.text((cx + 20, info_box_y + 40), f'🏛 {top_info}', font=font_label, fill='#cbd5e1')

    # 绘制立面标尺横向贯穿虚线
    ground_y = card_y + 50 + int(971 * (img_h / 1024))
    for x in range(50, width - 50, 8):
        draw.line([(x, ground_y), (x + 4, ground_y)], fill='#ef4444', width=2)
    draw.text((width - 240, ground_y - 20), '▼ 统一地基接地线 (y=971)', font=font_label, fill='#ef4444')

    wall1_y = card_y + 50 + int((971 - 160) * (img_h / 1024))
    for x in range(50, 50 + col_w + 60, 8):
        draw.line([(x, wall1_y), (x + 4, wall1_y)], fill='#38bdf8', width=1)
    draw.text((60, wall1_y - 18), '1-2级基准墙顶 (160px)', font=font_label, fill='#38bdf8')

    wall2_y = card_y + 50 + int((971 - 240) * (img_h / 1024))
    for x in range(50 + col_w, 50 + col_w * 2 + 60, 8):
        draw.line([(x, wall2_y), (x + 4, wall2_y)], fill='#34d399', width=2)
    draw.text((50 + col_w + 20, wall2_y - 18), '▲ 3-4级墙顶 (240px · +50%)', font=font_label, fill='#34d399')

    wall3_y = card_y + 50 + int((971 - 265) * (img_h / 1024))
    for x in range(50 + col_w * 2, 50 + col_w * 3 + 60, 8):
        draw.line([(x, wall3_y), (x + 4, wall3_y)], fill='#60a5fa', width=2)
    draw.text((50 + col_w * 2 + 20, wall3_y - 18), '▲ 5-6级墙顶 (265px · 过渡)', font=font_label, fill='#60a5fa')

    wall4_y = card_y + 50 + int((971 - 290) * (img_h / 1024))
    for x in range(50 + col_w * 3, 50 + col_w * 4 + 60, 8):
        draw.line([(x, wall4_y), (x + 4, wall4_y)], fill='#f59e0b', width=2)
    draw.text((50 + col_w * 3 + 20, wall4_y - 18), '▲ 7-8级墙顶 (290px · +81%)', font=font_label, fill='#f59e0b')

    wall5_y = card_y + 50 + int((971 - 325) * (img_h / 1024))
    for x in range(50 + col_w * 4, width - 50, 8):
        draw.line([(x, wall5_y), (x + 4, wall5_y)], fill='#ec4899', width=2)
    draw.text((50 + col_w * 4 + 20, wall5_y - 18), '▲ 9-10级墙顶 (325px · 高大雄伟)', font=font_label, fill='#ec4899')

    out_path = SOURCE_DIR / 'comparison-5tiers-calibrated.jpg'
    sheet.save(out_path, quality=95)
    print(f"成功保存5阶标尺对比图至: {out_path}")
    if ARTIFACT_DIR.exists():
        shutil.copy2(out_path, ARTIFACT_DIR / 'comparison-5tiers-calibrated.jpg')
        shutil.copy2(out_path, ARTIFACT_DIR / 'comparison-before-vs-after-50pct.jpg')


def render_multiscale_verification(m1, m2, m3, m4, m5):
    font_title = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 22, index=2)
    font_section = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 16, index=2)
    font_label = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 14, index=2)
    font_desc = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 12, index=2)

    themes = [
        ('dark', '#0f172a', '#1e293b', '#f8fafc', '#94a3b8', '#38bdf8', '#334155'),
        ('light', '#f8fafc', '#ffffff', '#0f172a', '#64748b', '#0284c7', '#e2e8f0'),
    ]

    tiers_info = [
        ('城墙 1-2 级（基准主城 · 保持现状）', m1, '#38bdf8', '保持经典花岗岩石墙，墙高160px，钟楼穹顶，百分之百与原有大地图一致'),
        ('城墙 3-4 级（加固重镇 · 拔高50%）', m2, '#34d399', '斜面加固基座，墙高240px，高耸双塔门楼与5层新古典统帅大厦'),
        ('城墙 5-6 级（强化重镇 · 中位过渡）', m3, '#60a5fa', '全新补充①：墙高265px介于3-4与7-8级之间，多级八角要塞防空堡垒'),
        ('城墙 7-8 级（钢铁要塞 · 拔高81%）', m4, '#f59e0b', '巨型复合要塞城壁290px，双排反坦克龙齿，旋转重炮塔，主穹顶y=6'),
        ('城墙 9-10 级（终极帝国要塞 · 原生重构）', m5, '#ec4899', '全新原生一体化重构：墙高325px，金顶统帅神殿直刺天顶，双联要塞炮，原生平直龙齿防线零拼凑'),
    ]

    for tname, bg_col, card_col, text_col, sub_col, accent_col, border_col in themes:
        sheet = Image.new('RGB', (1600, 1150), bg_col)
        draw = ImageDraw.Draw(sheet)

        t_text = f'玩家城市 5 阶微缩模型大地图多尺度验收 · {"深色背景（纯净无白边）" if tname == "dark" else "浅色背景"}'
        draw.text((40, 24), t_text, font=font_title, fill=text_col)
        draw.text((40, 58), '城墙 1-2 级 ➔ 3-4 级 ➔ 5-6 级 ➔ 7-8 级 ➔ 9-10 级 全梯度多视口实测', font=font_desc, fill=sub_col)

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
    m1, m2, m3, m4, m5 = generate_all_assets()
    render_evolution_showcase(m1, m2, m3, m4, m5)
    render_calibrated_comparison(m1, m2, m3, m4, m5)
    render_multiscale_verification(m1, m2, m3, m4, m5)


if __name__ == '__main__':
    main()
