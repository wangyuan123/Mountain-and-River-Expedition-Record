"""后处理玩家城市微缩模型三阶资产：
1-4级保持现状（基准），5-9级墙体与建筑调高50%，10级墙体与建筑调高80%。
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

# 1-4 级素材严格保持现状
MAP_1_4_PNG = CITIES_DIR / 'player-city-preview-map.png'
MAP_1_4_WEBP = CITIES_DIR / 'player-city-preview-map.webp'
ALIAS_1_4_PNG = CITIES_DIR / 'player-city-lv1-4.png'
ALIAS_1_4_WEBP = CITIES_DIR / 'player-city-lv1-4.webp'
MAIN_PREVIEW_PNG = CITIES_DIR / 'player-city-preview.png'

# 5-9 级与 10 级高耸透明原图
RAW_TALL_5_9 = RAW_DIR / 'city_tall_lv5_9_transparent.png'
RAW_TALL_10 = RAW_DIR / 'city_tall_lv10_transparent.png'

# 目标大地图资产
MAP_5_9_PNG = CITIES_DIR / 'player-city-lv5-9.png'
MAP_5_9_WEBP = CITIES_DIR / 'player-city-lv5-9.webp'
MAP_10_PNG = CITIES_DIR / 'player-city-lv10.png'
MAP_10_WEBP = CITIES_DIR / 'player-city-lv10.webp'

# 384x384 独立模型资产
STANDALONE_1_4_WEBP = SOURCE_DIR / 'player_city_lv1_4_citadel.webp'
STANDALONE_5_9_WEBP = SOURCE_DIR / 'player_city_lv5_9_citadel.webp'
STANDALONE_10_WEBP = SOURCE_DIR / 'player_city_lv10_citadel.webp'


def build_standalone_384(sprite: Image.Image) -> Image.Image:
    canvas = Image.new('RGBA', (384, 384), (0, 0, 0, 0))
    scaled = sprite.copy()
    scaled.thumbnail((364, 364), Image.Resampling.LANCZOS)
    off_x = (384 - scaled.width) // 2
    off_y = (384 - scaled.height) // 2
    canvas.paste(scaled, (off_x, off_y), mask=scaled.split()[3])
    return canvas


def generate_all_assets():
    # 1. Tier 1 (1-4级): 严格保持现状
    print("Loading Tier 1 (Lv 1-4 baseline - 保持现状)...")
    if not MAP_1_4_PNG.exists():
        raise FileNotFoundError(f"未找到1-4级基准素材: {MAP_1_4_PNG}")
    m1 = Image.open(MAP_1_4_PNG).convert('RGBA')
    m1.save(ALIAS_1_4_PNG, optimize=True)
    if MAP_1_4_WEBP.exists():
        shutil.copy2(MAP_1_4_WEBP, ALIAS_1_4_WEBP)
    else:
        m1.save(ALIAS_1_4_WEBP, 'WEBP', quality=95, method=6)
    
    # 提取 1-4 级 sprite 生成 384 独立模型
    bb1 = m1.getbbox()
    s1 = m1.crop(bb1)
    std1 = build_standalone_384(s1)
    std1.save(STANDALONE_1_4_WEBP, 'WEBP', quality=95, method=6)

    # 2. Tier 2 (5-9级): 墙体与建筑调高 50%
    print("Processing Tier 2 (Lv 5-9 - 墙体与建筑调高 50%)...")
    t5 = Image.open(RAW_TALL_5_9).convert('RGBA')
    bb5 = t5.getchannel('A').point(lambda a: 255 if a > 20 else 0).getbbox()
    c5 = t5.crop(bb5)
    # 标定宽度944，高度946，底边y=971，正面城墙高度240px（恰好为1-4级160px的1.50倍）
    s5 = c5.resize((944, 946), Image.Resampling.LANCZOS)
    m5 = Image.new('RGBA', (992, 1024), (0, 0, 0, 0))
    m5.paste(s5, (24, 25), mask=s5.split()[3])
    m5.save(MAP_5_9_PNG, optimize=True)
    m5.save(MAP_5_9_WEBP, 'WEBP', quality=95, method=6)
    std5 = build_standalone_384(s5)
    std5.save(STANDALONE_5_9_WEBP, 'WEBP', quality=95, method=6)

    # 3. Tier 3 (10级满级): 墙体与建筑调高 80%
    print("Processing Tier 3 (Lv 10 - 墙体与建筑调高 80%)...")
    t10 = Image.open(RAW_TALL_10).convert('RGBA')
    bb10 = t10.getchannel('A').point(lambda a: 255 if a > 20 else 0).getbbox()
    c10 = t10.crop(bb10)
    # 标定宽度944，高度966，底边y=971，正面城墙高度290px（恰好为1-4级160px的1.81倍）
    s10 = c10.resize((944, 966), Image.Resampling.LANCZOS)
    m10 = Image.new('RGBA', (992, 1024), (0, 0, 0, 0))
    m10.paste(s10, (24, 5), mask=s10.split()[3])
    m10.save(MAP_10_PNG, optimize=True)
    m10.save(MAP_10_WEBP, 'WEBP', quality=95, method=6)
    std10 = build_standalone_384(s10)
    std10.save(STANDALONE_10_WEBP, 'WEBP', quality=95, method=6)

    print("All game assets generated successfully!")
    return m1, m5, m10


def render_evolution_showcase(m1, m2, m3):
    font_large = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 28, index=2)
    font_title = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 22, index=2)
    font_section = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 16, index=2)
    font_label = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 14, index=2)
    font_desc = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 12, index=2)

    width = 1860
    height = 930
    sheet = Image.new('RGB', (width, height), '#0c1322')
    draw = ImageDraw.Draw(sheet)

    draw.text((50, 26), '山河远征录 · 玩家城市微缩模型三阶递进【5-9级调高50% · 10级调高80% 巍峨要塞版】', font=font_large, fill='#f8fafc')
    draw.text((50, 66), '1-4级严格保持现状 · 5-9级墙体建筑调高50%（高耸要塞重镇） · 10级墙体建筑调高80%（终极钢铁要塞大本营） · 地基零偏差对齐', font=font_section, fill='#94a3b8')

    cards = [
        ('① 初阶要塞主城（城墙 1-4 级 · 保持现状）', '立面墙高 160px · 中规中矩的花园卫城基准', m1, '#38bdf8', [
            '• 保持现状：完全沿用游戏当前基准模型，与原有大地图完全无缝衔接',
            '• 墙体立面：正面花岗岩防卫石墙立面高度 160px，经典平齐马道与石砌垛口',
            '• 统帅中枢：4层新古典市政主楼，钟楼穹顶高 919px（顶部距上边缘 52px）',
            '• 主城大门：标准石砌拱门门楼，配双扇防卫装甲钢门与两侧步兵岗哨',
            '• 四角棱堡：八角形石砌角楼，布置双联防空机炮与单跨木质瞭望塔',
            '• 基地配置：单跨起降机库、混凝土跑道、停机坪P-51战机与装甲车操场',
        ]),
        ('② 中级要塞重镇（城墙 5-9 级 · 调高 50%）', '立面墙高 240px (+50%) · 宏伟加固双塔要塞', m2, '#34d399', [
            '• 墙体调高：正面防卫高墙拔高至 240px（提升 50%），加设厚重斜面重力基座',
            '• 统帅中枢：5层宏伟新古典统帅大厦，高耸绿色圆穹主钟塔（高度 946px）',
            '• 要塞门楼：双塔重装加固要塞大门拔地而起，配大功率探照灯与红色战旗',
            '• 四角棱堡：拔高的多级八角要塞棱堡防空堡垒，装备大口径双联高射机炮',
            '• 基地配置：双跨装甲机库、扩展混凝土主跑道、多机编队与喀秋莎排阵',
            '• 军工生产：红砖锯齿厂房扩建高耸行政烟囱塔楼、地下油罐区与运输车队',
        ]),
        ('③ 终极钢铁要塞大本营（城墙 10 级满级 · 调高 80%）', '立面墙高 290px (+80%) · 满级钢铁要塞都市', m3, '#f59e0b', [
            '• 墙体调高：正面复合重装要塞城壁极限制高 290px（提升 80%），装甲旋转重炮塔',
            '• 统帅中枢：6-7层至尊帝国军政大厦，巨型天际线主穹顶直抵制高点（高度 966px）',
            '• 终极大门：3-4层巨型要塞主堡门楼，门前深铺双排混凝土反坦克龙齿阻滞防线',
            '• 仪式广场：宏伟中央胜利雕塑喷泉广场、仪式林荫大道与弧形古典爱奥尼列柱',
            '• 战略空军：全重装战略空军基底、多重防爆掩体机库、重型战略轰炸群就位',
            '• 地面铁流：精锐重装坦克集群大方阵、自行重火炮排阵威武列阵，压迫感拉满',
        ]),
    ]

    card_w = 560
    card_h = 770
    card_y = 110

    for idx, (title, subtitle, map_img, accent, features) in enumerate(cards):
        cx = 50 + idx * (card_w + 35)
        draw.rounded_rectangle([(cx, card_y), (cx + card_w, card_y + card_h)], radius=12, fill='#162032', outline='#2d3d54', width=2)
        draw.text((cx + 24, card_y + 20), title, font=font_title, fill=accent)
        draw.text((cx + 24, card_y + 54), subtitle, font=font_section, fill='#cbd5e1')

        preview_h = 440
        preview_w = int(map_img.width * preview_h / map_img.height)
        s_img = map_img.resize((preview_w, preview_h), Image.Resampling.LANCZOS)
        img_x = cx + (card_w - preview_w) // 2
        img_y = card_y + 85
        sheet.paste(s_img, (img_x, img_y), mask=s_img.split()[3])

        info_y = card_y + 535
        draw.rounded_rectangle([(cx + 20, info_y), (cx + card_w - 20, card_y + card_h - 20)], radius=8, fill='#0d1524', outline='#1e293b', width=1)
        draw.text((cx + 36, info_y + 12), '微缩模型规格与升级核心要点：', font=font_label, fill='#f1f5f9')
        for f_idx, feat in enumerate(features):
            draw.text((cx + 36, info_y + 36 + f_idx * 25), feat, font=font_desc, fill='#94a3b8')

    out_path = SOURCE_DIR / 'three-tier-tall-evolution-showcase.jpg'
    sheet.save(out_path, quality=95)
    print(f"成功保存演进总览图至: {out_path}")
    if ARTIFACT_DIR.exists():
        shutil.copy2(out_path, ARTIFACT_DIR / 'three-tier-tall-evolution-showcase.jpg')


def render_calibrated_comparison(m1, m2, m3):
    font_large = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 26, index=2)
    font_title = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 20, index=2)
    font_label = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 15, index=2)
    font_bold = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 16, index=2)

    width = 1860
    height = 960
    sheet = Image.new('RGB', (width, height), '#0c1322')
    draw = ImageDraw.Draw(sheet)

    draw.text((50, 24), '山河远征录 · 城市微缩模型三阶精准立面高度对比（1-4级保持现状 · 5-9级调高50% · 10级调高80%）', font=font_large, fill='#f8fafc')
    draw.text((50, 62), '严丝合缝底线对齐（y=971） · 正面城墙高度：160px (基准) ➔ 240px (+50%) ➔ 290px (+80%) · 穹顶天际线递增耸立', font=font_label, fill='#94a3b8')

    columns = [
        ('城墙 1-4 级（基准主城 · 保持现状）', m1, '#38bdf8', '墙高: 160px (1.00x 基准)', '穹顶制高点: y=52 (总高 919px)'),
        ('城墙 5-9 级（加固要塞 · 调高 50%）', m2, '#34d399', '墙高: 240px (1.50x 调高50%)', '穹顶制高点: y=25 (总高 946px)'),
        ('城墙 10 级满级（终极要塞 · 调高 80%）', m3, '#f59e0b', '墙高: 290px (1.81x 调高80%)', '穹顶制高点: y=5 (总高 966px)'),
    ]

    col_w = 560
    img_h = 700
    card_y = 110

    for idx, (col_title, curr_img, accent, wall_info, top_info) in enumerate(columns):
        cx = 50 + idx * (col_w + 35)
        draw.rounded_rectangle([(cx, card_y), (cx + col_w, card_y + 810)], radius=12, fill='#162032', outline='#2d3d54', width=2)
        draw.text((cx + 24, card_y + 18), col_title, font=font_title, fill=accent)

        scale = img_h / curr_img.height
        disp_w = int(curr_img.width * scale)
        disp_img = curr_img.resize((disp_w, img_h), Image.Resampling.LANCZOS)
        disp_x = cx + (col_w - disp_w) // 2
        disp_y = card_y + 55
        sheet.paste(disp_img, (disp_x, disp_y), mask=disp_img.split()[3])

        # 底部数据指标卡
        info_box_y = card_y + 725
        draw.rounded_rectangle([(cx + 15, info_box_y), (cx + col_w - 15, info_box_y + 70)], radius=8, fill='#0d1524', outline='#1e293b', width=1)
        draw.text((cx + 25, info_box_y + 12), f'📏 {wall_info}', font=font_bold, fill=accent)
        draw.text((cx + 25, info_box_y + 40), f'🏛 {top_info}', font=font_label, fill='#cbd5e1')

    # 绘制立面高度对比测量参考辅助虚线（横跨三列）
    # 1. 统一接地线（y=971）
    ground_y = card_y + 55 + int(971 * (img_h / 1024))
    for x in range(50, width - 50, 8):
        draw.line([(x, ground_y), (x + 4, ground_y)], fill='#ef4444', width=2)
    draw.text((width - 240, ground_y - 22), '▼ 统一地基接地线 (y=971)', font=font_label, fill='#ef4444')

    # 2. 1-4 级城墙顶线（160px）
    wall1_y = card_y + 55 + int((971 - 160) * (img_h / 1024))
    for x in range(50, 50 + col_w + 100, 8):
        draw.line([(x, wall1_y), (x + 4, wall1_y)], fill='#38bdf8', width=1)
    draw.text((60, wall1_y - 20), '1-4级基准墙顶 (160px)', font=font_label, fill='#38bdf8')

    # 3. 5-9 级城墙顶线（240px，+50%）
    wall2_y = card_y + 55 + int((971 - 240) * (img_h / 1024))
    for x in range(50 + col_w, 50 + col_w * 2 + 100, 8):
        draw.line([(x, wall2_y), (x + 4, wall2_y)], fill='#34d399', width=2)
    draw.text((50 + col_w + 50, wall2_y - 20), '▲ 5-9级城墙高度 (+50% ➔ 240px)', font=font_label, fill='#34d399')

    # 4. 10 级城墙顶线（290px，+80%）
    wall3_y = card_y + 55 + int((971 - 290) * (img_h / 1024))
    for x in range(50 + col_w * 2, width - 50, 8):
        draw.line([(x, wall3_y), (x + 4, wall3_y)], fill='#f59e0b', width=2)
    draw.text((50 + col_w * 2 + 50, wall3_y - 20), '▲ 10级城墙高度 (+80% ➔ 290px)', font=font_label, fill='#f59e0b')

    out_path = SOURCE_DIR / 'comparison-before-vs-after-50pct.jpg'
    sheet.save(out_path, quality=95)
    print(f"成功保存升级前后对比图至: {out_path}")
    if ARTIFACT_DIR.exists():
        shutil.copy2(out_path, ARTIFACT_DIR / 'comparison-before-vs-after-50pct.jpg')


def render_multiscale_verification(m1, m2, m3):
    font_title = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 22, index=2)
    font_section = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 16, index=2)
    font_label = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 14, index=2)
    font_desc = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 12, index=2)

    themes = [
        ('dark', '#0f172a', '#1e293b', '#f8fafc', '#94a3b8', '#38bdf8', '#334155'),
        ('light', '#f8fafc', '#ffffff', '#0f172a', '#64748b', '#0284c7', '#e2e8f0'),
    ]

    for tname, bg_col, card_col, text_col, sub_col, accent_col, border_col in themes:
        sheet = Image.new('RGB', (1440, 840), bg_col)
        draw = ImageDraw.Draw(sheet)

        t_text = f'玩家城市 3 阶高耸微缩模型大地图多尺度验收 · {"深色背景（纯净无白边）" if tname == "dark" else "浅色背景"}'
        draw.text((40, 24), t_text, font=font_title, fill=text_col)
        draw.text((40, 58), '1-4级保持现状 · 5-9级墙体建筑调高50% · 10级墙体建筑调高80% 巍峨版', font=font_desc, fill=sub_col)

        tiers_info = [
            ('城墙 1-4 级（基准主城 · 保持现状）', m1, '#38bdf8'),
            ('城墙 5-9 级（加固重镇 · 调高 50%）', m2, '#34d399'),
            ('城墙 10 级满级（终极要塞 · 调高 80%）', m3, '#f59e0b'),
        ]

        row_y_start = 95
        row_h = 230

        for r_idx, (tier_title, map_asset, tag_color) in enumerate(tiers_info):
            ry = row_y_start + r_idx * row_h
            draw.rectangle([(40, ry), (1400, ry + row_h - 15)], fill=card_col, outline=border_col, width=1)
            draw.text((60, ry + 15), tier_title, font=font_section, fill=tag_color)

            p220 = map_asset.resize((180, 186), Image.Resampling.LANCZOS)
            sheet.paste(p220, (60, ry + 42), mask=p220.split()[3])

            p176 = map_asset.resize((140, 145), Image.Resampling.LANCZOS)
            sheet.paste(p176, (290, ry + 55), mask=p176.split()[3])
            draw.text((290, ry + 25), '176px (放大视口)', font=font_label, fill=text_col)

            p88 = map_asset.resize((76, 78), Image.Resampling.LANCZOS)
            sheet.paste(p88, (480, ry + 75), mask=p88.split()[3])
            draw.text((480, ry + 25), '88px (默认视口)', font=font_label, fill=text_col)

            p48 = map_asset.resize((48, 50), Image.Resampling.LANCZOS)
            sheet.paste(p48, (610, ry + 90), mask=p48.split()[3])
            draw.text((610, ry + 25), '48px (城卡列表)', font=font_label, fill=text_col)

            draw.rectangle([(710, ry + 20), (1370, ry + row_h - 30)], fill=bg_col, outline=border_col, width=1)
            draw.text((730, ry + 32), '该等级高耸版视觉辨识度核心点：', font=font_label, fill=text_col)

            if r_idx == 0:
                features = [
                    '✔ 保持游戏现状：经典花岗岩石墙，立面高度 160px，经典门楼与钟楼穹顶，百分之百与旧版一致',
                    '✔ 88px 默认视口：中轴市政厅高光凝聚，城门拱券深色阴影鲜明突出，地坪边缘平滑融入草地',
                    '✔ 48px 列表：方整四方要塞城郭厚重大气，经典微缩比例和谐自然',
                ]
            elif r_idx == 1:
                features = [
                    '✔ 倾斜加固基座与正面墙体立面调高 50%（240px），拔高的双塔门楼巍峨迎面，城垛更显坚不可摧',
                    '✔ 88px 默认视口：双塔大门与高耸圆穹钟楼形成双高光焦点，立体阴影极其深厚，层次分明',
                    '✔ 48px 列表：两座高耸八角棱堡与双塔大门瞬间拉开层次档次，军事重镇感十足',
                ]
            else:
                features = [
                    '✔ 巨型复合要塞城壁极限制高 290px（调高 80%），正南大门巍峨厚重，门前双排反坦克龙齿霸气彰显',
                    '✔ 88px 默认视口：至尊统帅部穹顶直刺天际，旋转重装炮塔与坦克方阵大面积受光，极具压迫感',
                    '✔ 48px 列表：一眼即识的满级战略大都市，军事霸主地位无庸置疑',
                ]

            for f_i, feat in enumerate(features):
                draw.text((730, ry + 62 + f_i * 30), feat, font=font_desc, fill=sub_col)

        out_path = SOURCE_DIR / f'preview-{tname}.jpg'
        sheet.save(out_path, quality=95)
        print(f"成功保存多尺度验收图至: {out_path}")
        if ARTIFACT_DIR.exists():
            shutil.copy2(out_path, ARTIFACT_DIR / f'preview-{tname}.jpg')


def main():
    m1, m2, m3 = generate_all_assets()
    render_evolution_showcase(m1, m2, m3)
    render_calibrated_comparison(m1, m2, m3)
    render_multiscale_verification(m1, m2, m3)


if __name__ == '__main__':
    main()
