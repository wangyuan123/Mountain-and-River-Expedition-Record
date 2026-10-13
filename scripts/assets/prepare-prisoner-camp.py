"""处理战俘营（prisoner_camp）微缩模型原稿，输出 384×384 透明 WebP 及深浅背景多尺度验收图。"""
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
SOURCE_DIR = ROOT / 'output/imagegen/prisoner-camp-20261009'
RAW_IMAGE = SOURCE_DIR / 'raw/prisoner_camp.png'
DEST_DIR = ROOT / 'frontend/img/buildings/garden'
DEST_FILE = DEST_DIR / 'prisoner_camp.webp'
DEPOT_REF = DEST_DIR / 'depot.webp'


def prepare_prisoner_camp():
    if not RAW_IMAGE.exists():
        raise FileNotFoundError(f"原稿图片不存在: {RAW_IMAGE}")

    raw = Image.open(RAW_IMAGE).convert('RGBA')
    arr = np.array(raw)

    # 规范化 alpha 通道，将 253 饱满实体映射至 255
    alpha = arr[:, :, 3].astype(float)
    alpha = np.clip(alpha * (255.0 / 253.0), 0, 255).astype(np.uint8)
    arr[:, :, 3] = alpha

    sprite = Image.fromarray(arr)
    bbox = sprite.getchannel('A').point(lambda a: 255 if a > 10 else 0).getbbox()
    if not bbox:
        raise ValueError("模型轮廓为空")
    sprite = sprite.crop(bbox)

    # 沿用花园卫城 21 款现役建筑规范：最大边长缩放至 368，居中放入 384×384 画布
    sprite.thumbnail((368, 368), Image.Resampling.LANCZOS)
    canvas = Image.new('RGBA', (384, 384), (0, 0, 0, 0))
    offset = ((384 - sprite.width) // 2, (384 - sprite.height) // 2)
    canvas.alpha_composite(sprite, offset)

    DEST_DIR.mkdir(parents=True, exist_ok=True)
    canvas.save(DEST_FILE, 'WEBP', quality=95, method=6)
    print(f"成功保存透明 WebP 素材至: {DEST_FILE} (画布尺寸: {canvas.size}, 主体尺寸: {sprite.size})")
    return canvas


def render_previews(camp_img):
    font_title = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 22)
    font_label = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 15)
    font_caption = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 12)

    depot_img = Image.open(DEPOT_REF).convert('RGBA') if DEPOT_REF.exists() else None

    themes = [
        ('dark', '#0f172a', '#1e293b', '#f8fafc', '#94a3b8', '#38bdf8', '#334155'),
        ('light', '#f8fafc', '#ffffff', '#0f172a', '#64748b', '#0284c7', '#e2e8f0'),
    ]

    for name, bg_col, card_col, text_col, sub_col, accent_col, border_col in themes:
        sheet = Image.new('RGB', (1160, 720), bg_col)
        draw = ImageDraw.Draw(sheet)

        # 标题栏
        t_text = '战俘营微缩模型验收 · ' + ('深色背景（纯净无白边）' if name == 'dark' else '浅色背景')
        draw.text((40, 24), t_text, font=font_title, fill=text_col)
        sub_text = '两排营房 · 铁丝网围栏院落 · 双角木构岗楼 · 门卫双扇营门 · 沿用花园卫城/军需仓库写实风格'
        draw.text((40, 58), sub_text, font=font_caption, fill=sub_col)

        # 左卡片：战俘营（新制素材）
        draw.rectangle([(40, 90), (600, 680)], fill=card_col, outline=border_col, width=1)
        draw.text((64, 110), '新制建筑：战俘营（prisoner_camp）', font=font_label, fill=accent_col)
        draw.text((64, 134), '384×384 透明 WebP · 居中对齐规范', font=font_caption, fill=sub_col)

        # 240px 大图
        s240 = camp_img.resize((240, 240), Image.Resampling.LANCZOS)
        sheet.paste(s240, (64, 165), s240)
        draw.text((64, 415), '240px 细节展示', font=font_caption, fill=sub_col)

        # 多视口对比检查
        draw.text((340, 165), '实际游戏视口实测：', font=font_caption, fill=sub_col)

        # 88px 详情弹窗
        s88 = camp_img.resize((88, 88), Image.Resampling.LANCZOS)
        sheet.paste(s88, (340, 195), s88)
        draw.text((340, 290), '88px 详情弹窗', font=font_caption, fill=text_col)

        # 84px 建筑卡槽
        s84 = camp_img.resize((84, 84), Image.Resampling.LANCZOS)
        sheet.paste(s84, (460, 199), s84)
        draw.text((460, 290), '84px 建筑槽位', font=font_caption, fill=text_col)

        # 64px 选建列表
        s64 = camp_img.resize((64, 64), Image.Resampling.LANCZOS)
        sheet.paste(s64, (340, 335), s64)
        draw.text((340, 405), '64px 选建列表', font=font_caption, fill=text_col)

        # 游戏卡槽与列表卡片模拟
        draw.rectangle([(64, 455), (576, 655)], fill=bg_col, outline=border_col, width=1)
        draw.text((80, 470), '游戏内卡片槽位模拟 (84px / 64px)：', font=font_caption, fill=sub_col)
        draw.rectangle([(80, 500), (180, 600)], fill=card_col, outline=border_col, width=1)
        s80 = camp_img.resize((80, 80), Image.Resampling.LANCZOS)
        sheet.paste(s80, (90, 510), s80)
        draw.text((85, 610), '军事卡槽 #18', font=font_caption, fill=text_col)

        draw.rectangle([(220, 500), (320, 600)], fill=card_col, outline=border_col, width=1)
        sheet.paste(s80, (230, 510), s80)
        draw.text((225, 610), '已建 Lv.1', font=font_caption, fill=text_col)

        draw.rectangle([(360, 500), (556, 560)], fill=card_col, outline=border_col, width=1)
        s48 = camp_img.resize((48, 48), Image.Resampling.LANCZOS)
        sheet.paste(s48, (370, 506), s48)
        draw.text((430, 514), '战俘营', font=font_label, fill=text_col)
        draw.text((430, 536), '每级收容1000战俘', font=font_caption, fill=sub_col)

        # 右卡片：参考基准（军需仓库）
        if depot_img:
            draw.rectangle([(620, 90), (1120, 680)], fill=card_col, outline=border_col, width=1)
            draw.text((644, 110), '风格基准对比：军需仓库（depot）', font=font_label, fill='#a855f7' if name == 'dark' else '#7e22ce')
            draw.text((644, 134), '相同红陶瓦 · 奶油石壁 · 35° 等距视角 · 浅地坪', font=font_caption, fill=sub_col)

            s240_depot = depot_img.resize((240, 240), Image.Resampling.LANCZOS)
            sheet.paste(s240_depot, (644, 165), s240_depot)
            draw.text((644, 415), '240px 基准仓库', font=font_caption, fill=sub_col)

            s88_depot = depot_img.resize((88, 88), Image.Resampling.LANCZOS)
            sheet.paste(s88_depot, (920, 195), s88_depot)
            draw.text((920, 290), '88px 详情弹窗', font=font_caption, fill=text_col)

            s64_depot = depot_img.resize((64, 64), Image.Resampling.LANCZOS)
            sheet.paste(s64_depot, (920, 335), s64_depot)
            draw.text((920, 405), '64px 选建列表', font=font_caption, fill=text_col)

            draw.rectangle([(644, 455), (1096, 655)], fill=bg_col, outline=border_col, width=1)
            draw.text((660, 470), '基准卡片槽位比对：', font=font_caption, fill=sub_col)
            draw.rectangle([(660, 500), (760, 600)], fill=card_col, outline=border_col, width=1)
            s80_depot = depot_img.resize((80, 80), Image.Resampling.LANCZOS)
            sheet.paste(s80_depot, (670, 510), s80_depot)
            draw.text((670, 610), '军需仓库 Lv.5', font=font_caption, fill=text_col)

            draw.rectangle([(800, 500), (1076, 560)], fill=card_col, outline=border_col, width=1)
            s48_depot = depot_img.resize((48, 48), Image.Resampling.LANCZOS)
            sheet.paste(s48_depot, (810, 506), s48_depot)
            draw.text((870, 514), '军需仓库', font=font_label, fill=text_col)
            draw.text((870, 536), '资源储量上限提升', font=font_caption, fill=sub_col)

        out_path = SOURCE_DIR / f'preview-{name}.jpg'
        sheet.save(out_path, quality=95)
        print(f"成功保存验收图至: {out_path}")


def main():
    camp = prepare_prisoner_camp()
    render_previews(camp)


if __name__ == '__main__':
    main()
