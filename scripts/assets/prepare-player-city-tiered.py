"""后处理三款阶梯升级的玩家城市微缩模型，输出游戏地图资产及多尺度深浅色验收图。"""
from pathlib import Path
import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
SOURCE_DIR = ROOT / 'output/imagegen/player-city-tiered-20261017'
RAW_DIR = SOURCE_DIR / 'raw'
CITIES_DIR = ROOT / 'frontend/img/cities'

RAW_1_4 = RAW_DIR / 'city_lv1_4.png'
RAW_5_9 = RAW_DIR / 'city_lv5_9.png'
RAW_10 = RAW_DIR / 'city_lv10.png'

# 目标地图资产
MAP_1_4_PNG = CITIES_DIR / 'player-city-preview-map.png'
MAP_1_4_WEBP = CITIES_DIR / 'player-city-preview-map.webp'
ALIAS_1_4_PNG = CITIES_DIR / 'player-city-lv1-4.png'
ALIAS_1_4_WEBP = CITIES_DIR / 'player-city-lv1-4.webp'

MAP_5_9_PNG = CITIES_DIR / 'player-city-lv5-9.png'
MAP_5_9_WEBP = CITIES_DIR / 'player-city-lv5-9.webp'

MAP_10_PNG = CITIES_DIR / 'player-city-lv10.png'
MAP_10_WEBP = CITIES_DIR / 'player-city-lv10.webp'

# 384x384 标准微缩模型
STANDALONE_1_4_WEBP = SOURCE_DIR / 'player_city_lv1_4_citadel.webp'
STANDALONE_5_9_WEBP = SOURCE_DIR / 'player_city_lv5_9_citadel.webp'
STANDALONE_10_WEBP = SOURCE_DIR / 'player_city_lv10_citadel.webp'


def get_warped_sprite(img_path: Path) -> Image.Image:
    raw = Image.open(img_path).convert('RGBA')
    arr = np.array(raw)

    # 规范化 alpha
    alpha = arr[:, :, 3].astype(float)
    alpha = np.clip(alpha, 0, 255).astype(np.uint8)
    arr[:, :, 3] = alpha

    h, w = arr.shape[:2]
    temp_sprite = Image.fromarray(arr)
    bbox_raw = temp_sprite.getchannel('A').point(lambda a: 255 if a > 10 else 0).getbbox()
    x0, y0, x1, y1 = bbox_raw
    w_box = x1 - x0
    h_box = y1 - y0

    pad = 220
    padded_arr = np.pad(arr, ((pad, pad), (pad, pad), (0, 0)), mode='constant')

    top_expand = w_box * 0.02
    top_lift = h_box * 0.16

    src_pts = np.float32([
        [x0 + pad, y0 + pad],
        [x1 + pad, y0 + pad],
        [x1 + pad, y1 + pad],
        [x0 + pad, y1 + pad]
    ])
    dst_pts = np.float32([
        [x0 + pad - top_expand, y0 + pad - top_lift],
        [x1 + pad + top_expand, y0 + pad - top_lift],
        [x1 + pad, y1 + pad],
        [x0 + pad, y1 + pad]
    ])
    M = cv2.getPerspectiveTransform(src_pts, dst_pts)
    warped = cv2.warpPerspective(padded_arr, M, (w + 2 * pad, h + 2 * pad), flags=cv2.INTER_LANCZOS4, borderMode=cv2.BORDER_CONSTANT, borderValue=(0, 0, 0, 0))

    sprite = Image.fromarray(warped)
    bbox = sprite.getchannel('A').point(lambda a: 255 if a > 10 else 0).getbbox()
    if not bbox:
        raise ValueError(f"模型轮廓为空: {img_path}")
    return sprite.crop(bbox)


def build_map_canvas(sprite: Image.Image, tier: str) -> Image.Image:
    canvas = Image.new('RGBA', (992, 1024), (0, 0, 0, 0))
    if tier == 'lv1_4':
        scaled = sprite.resize((944, int(sprite.height * 944 / sprite.width)), Image.Resampling.LANCZOS)
        off_x = (992 - scaled.width) // 2
        off_y = 971 - scaled.height
        canvas.paste(scaled, (off_x, off_y), mask=scaled.split()[3])
    elif tier == 'lv5_9':
        scaled = sprite.resize((944, int(sprite.height * 944 / sprite.width)), Image.Resampling.LANCZOS)
        off_x = (992 - scaled.width) // 2
        off_y = 971 - scaled.height
        canvas.paste(scaled, (off_x, off_y), mask=scaled.split()[3])
    elif tier == 'lv10':
        scaled = sprite.copy()
        scaled.thumbnail((944, 960), Image.Resampling.LANCZOS)
        off_x = (992 - scaled.width) // 2
        off_y = min(985 - scaled.height, 1024 - scaled.height - 24)
        canvas.paste(scaled, (off_x, off_y), mask=scaled.split()[3])
    return canvas


def build_standalone_384(sprite: Image.Image) -> Image.Image:
    canvas = Image.new('RGBA', (384, 384), (0, 0, 0, 0))
    scaled = sprite.copy()
    scaled.thumbnail((364, 364), Image.Resampling.LANCZOS)
    off_x = (384 - scaled.width) // 2
    off_y = (384 - scaled.height) // 2
    canvas.paste(scaled, (off_x, off_y), mask=scaled.split()[3])
    return canvas


def generate_all_assets():
    print("Processing Tier 1 (Lv 1-4)...")
    s1 = get_warped_sprite(RAW_1_4)
    # Tier 1 保持已有的 MAP_1_4_PNG，若已存在且一致则同时写入别名
    m1 = Image.open(MAP_1_4_PNG) if MAP_1_4_PNG.exists() else build_map_canvas(s1, 'lv1_4')
    m1.save(ALIAS_1_4_PNG, optimize=True)
    m1.save(ALIAS_1_4_WEBP, 'WEBP', quality=95, method=6)
    std1 = build_standalone_384(s1)
    std1.save(STANDALONE_1_4_WEBP, 'WEBP', quality=95, method=6)

    print("Processing Tier 2 (Lv 5-9)...")
    s2 = get_warped_sprite(RAW_5_9)
    m2 = build_map_canvas(s2, 'lv5_9')
    m2.save(MAP_5_9_PNG, optimize=True)
    m2.save(MAP_5_9_WEBP, 'WEBP', quality=95, method=6)
    std2 = build_standalone_384(s2)
    std2.save(STANDALONE_5_9_WEBP, 'WEBP', quality=95, method=6)

    print("Processing Tier 3 (Lv 10)...")
    s3 = get_warped_sprite(RAW_10)
    m3 = build_map_canvas(s3, 'lv10')
    m3.save(MAP_10_PNG, optimize=True)
    m3.save(MAP_10_WEBP, 'WEBP', quality=95, method=6)
    std3 = build_standalone_384(s3)
    std3.save(STANDALONE_10_WEBP, 'WEBP', quality=95, method=6)

    print("Assets generated successfully!")
    return m1, m2, m3


def render_evolution_showcase(m1, m2, m3):
    font_large = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 28, index=2)
    font_title = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 22, index=2)
    font_section = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 16, index=2)
    font_label = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 14, index=2)
    font_desc = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 12, index=2)

    width = 1860
    height = 920
    sheet = Image.new('RGB', (width, height), '#0c1322')
    draw = ImageDraw.Draw(sheet)

    # 标题区
    draw.text((50, 28), '山河远征录 · 玩家城市微缩模型三阶演化总览（城墙升级 1-10 级）', font=font_large, fill='#f8fafc')
    draw.text((50, 68), '阶梯演化原则：城墙更高·建筑更宏伟·装饰更严整·军事风格更浓厚 | 统一 35° 等距俯视与二阶逆透视校正', font=font_section, fill='#94a3b8')

    cards = [
        ('① 初阶要塞主城（城墙 1-4 级）', '已有标准基准款 · 规整城防', m1, '#38bdf8', [
            '• 城墙防线：标准低矮花岗岩与混凝土防卫矮墙',
            '• 统帅中枢：3层新古典市政厅大楼配青铜钟塔',
            '• 防空警戒：单架桁架抛物面雷达站与通讯天线',
            '• 基地配置：单拱机库、单条跑道、P-51与重轰',
            '• 地面部队：标准中型坦克装甲方阵与卡车车队',
            '• 装饰防卫：标准铁丝网战俘营、简易防车拒马',
        ]),
        ('② 中级要塞重镇（城墙 5-9 级）', '城墙显著加高 · 重装军政要塞', m2, '#34d399', [
            '• 城墙防线：城墙显著加高至 1.8x，重装多边形棱堡角台',
            '• 统帅中枢：4-5层扩大新古典统帅大楼，绿顶圆穹钟楼',
            '• 门楼防御：双塔加固装甲城门楼，增设探照灯与垛口',
            '• 基地配置：双跨装甲机库、扩展停机坪、战斗机巡列',
            '• 地面部队：装甲阅兵操场扩建、喀秋莎火箭炮与坦克群',
            '• 军工生产：红砖锯齿顶军工厂扩建、带行政塔楼',
        ]),
        ('③ 终极钢铁要塞大本营（城墙 10 级满级）', '至高巍峨巨厦 · 终极要塞都市', m3, '#f59e0b', [
            '• 城墙防线：巨型高耸复合重型要塞城壁，装甲旋转炮塔',
            '• 统帅中枢：5-6层至尊帝国军政大厦，巨型天际线穹顶',
            '• 门楼防御：巨型要塞主堡门楼、双道钢闸、双排反坦克龙齿',
            '• 仪式广场：宏伟中央胜利雕塑广场、林荫大道与列柱',
            '• 基地配置：全重装战略空军基底、多重防爆掩体机库',
            '• 地面部队：精锐重装坦克集群大方阵、自行重火炮排阵',
        ]),
    ]

    card_w = 560
    card_h = 760
    card_y = 110

    for idx, (title, subtitle, map_img, accent, features) in enumerate(cards):
        cx = 50 + idx * (card_w + 35)
        # 背景卡片
        draw.rounded_rectangle([(cx, card_y), (cx + card_w, card_y + card_h)], radius=12, fill='#162032', outline='#2d3d54', width=2)

        # 头部标题
        draw.text((cx + 24, card_y + 20), title, font=font_title, fill=accent)
        draw.text((cx + 24, card_y + 54), subtitle, font=font_section, fill='#cbd5e1')

        # 模型图像展示 (420 x 433)
        preview_h = 430
        preview_w = int(map_img.width * preview_h / map_img.height)
        s_img = map_img.resize((preview_w, preview_h), Image.Resampling.LANCZOS)
        img_x = cx + (card_w - preview_w) // 2
        img_y = card_y + 85
        sheet.paste(s_img, (img_x, img_y), mask=s_img.split()[3])

        # 底部特性列表区
        info_y = card_y + 525
        draw.rounded_rectangle([(cx + 20, info_y), (cx + card_w - 20, card_y + card_h - 20)], radius=8, fill='#0d1524', outline='#1e293b', width=1)
        draw.text((cx + 36, info_y + 12), '核心要素升级要点：', font=font_label, fill='#f1f5f9')
        for f_idx, feat in enumerate(features):
            draw.text((cx + 36, info_y + 36 + f_idx * 25), feat, font=font_desc, fill='#94a3b8')

    out_path = SOURCE_DIR / 'three-tier-evolution-showcase.jpg'
    sheet.save(out_path, quality=95)
    print(f"成功保存演进总览图至: {out_path}")


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

        t_text = f'玩家城市 3 阶微缩模型大地图多尺度验收 · {"深色背景（纯净无白边）" if tname == "dark" else "浅色背景"}'
        draw.text((40, 24), t_text, font=font_title, fill=text_col)
        draw.text((40, 58), '城墙 1-4 级（基准） vs 城墙 5-9 级（加固） vs 城墙 10 级满级（终极大本营）', font=font_desc, fill=sub_col)

        # 3 行展示：每一行对应一个等级
        tiers_info = [
            ('城墙 1-4 级（已有）', m1, '#38bdf8'),
            ('城墙 5-9 级（中级加固）', m2, '#34d399'),
            ('城墙 10 级满级（终极要塞）', m3, '#f59e0b'),
        ]

        row_y_start = 95
        row_h = 230

        for r_idx, (tier_title, map_asset, tag_color) in enumerate(tiers_info):
            ry = row_y_start + r_idx * row_h
            # 行背景卡片
            draw.rectangle([(40, ry), (1400, ry + row_h - 15)], fill=card_col, outline=border_col, width=1)

            # 标头
            draw.text((60, ry + 15), tier_title, font=font_section, fill=tag_color)

            # 220px 中大图展示
            p220 = map_asset.resize((180, 186), Image.Resampling.LANCZOS)
            sheet.paste(p220, (60, ry + 42), mask=p220.split()[3])

            # 176px (大地图 200% 视口)
            p176 = map_asset.resize((140, 145), Image.Resampling.LANCZOS)
            sheet.paste(p176, (290, ry + 55), mask=p176.split()[3])
            draw.text((290, ry + 25), '176px (放大视口)', font=font_label, fill=text_col)

            # 88px (大地图 100% 默认视口)
            p88 = map_asset.resize((76, 78), Image.Resampling.LANCZOS)
            sheet.paste(p88, (480, ry + 75), mask=p88.split()[3])
            draw.text((480, ry + 25), '88px (默认视口)', font=font_label, fill=text_col)

            # 48px (城卡列表视口)
            p48 = map_asset.resize((48, 50), Image.Resampling.LANCZOS)
            sheet.paste(p48, (610, ry + 90), mask=p48.split()[3])
            draw.text((610, ry + 25), '48px (城卡列表)', font=font_label, fill=text_col)

            # 右侧特性说明面板
            draw.rectangle([(710, ry + 20), (1370, ry + row_h - 30)], fill=bg_col, outline=border_col, width=1)
            draw.text((730, ry + 32), '该等级视觉辨识度核心点：', font=font_label, fill=text_col)

            if r_idx == 0:
                features = [
                    '✔ 方整低矮石灰岩城墙，双角楼低位防空炮，亲和稳固的初阶大本营风格',
                    '✔ 88px 默认视口：中轴市政厅高光凝聚，东侧跑道浅色条带形成鲜明识别',
                    '✔ 48px 列表：方框城门清晰，轮廓规整易辨识',
                ]
            elif r_idx == 1:
                features = [
                    '✔ 城墙高度明显提升，基座加固斜面与外挑垛口显著强化防御感，正南门楼拔高',
                    '✔ 88px 默认视口：城墙立体阴影更深厚，统帅部钟楼穹顶高光更集中',
                    '✔ 48px 列表：两座高耸八角棱堡与双塔大门瞬间与1-4级拉开层级档次',
                ]
            else:
                features = [
                    '✔ 巨型复合重要塞高墙，正南门楼巍峨厚重，门前双排反坦克龙齿霸气彰显',
                    '✔ 88px 默认视口：最高统帅部穹顶与天际线极高拔起，重装坦克集群大面积受光，极具压迫感',
                    '✔ 48px 列表：一眼即识的满级战略大都市，军事霸主地位无庸置疑',
                ]

            for f_i, feat in enumerate(features):
                draw.text((730, ry + 62 + f_i * 30), feat, font=font_desc, fill=sub_col)

        out_path = SOURCE_DIR / f'preview-{tname}.jpg'
        sheet.save(out_path, quality=95)
        print(f"成功保存多尺度验收图至: {out_path}")


def main():
    m1, m2, m3 = generate_all_assets()
    render_evolution_showcase(m1, m2, m3)
    render_multiscale_verification(m1, m2, m3)


if __name__ == '__main__':
    main()
