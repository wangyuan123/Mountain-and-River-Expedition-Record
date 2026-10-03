"""将 8 款高辨识度统帅头像导出为游戏用 WebP 资源，并生成多尺寸圆形聊天头像验收对比图。"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageOps

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'output/imagegen/distinct-commander-avatars-20261001/raw'
DEST = ROOT / 'frontend/img/avatars/historical'
PREVIEW_DIR = ROOT / 'output/imagegen/distinct-commander-avatars-20261001'

AVATARS = [
    ('rank-private-v1', '列兵', '双手持步枪 · 战壕朝阳'),
    ('rank-corporal-v1', '下士', '风镜+冲锋枪 · 密林冷绿'),
    ('rank-sergeant-v1', '中士', '正脸硬汉+雪茄 · 暮色黄昏'),
    ('rank-lieutenant-v1', '中尉', '大檐帽+望远镜 · 高空苍穹'),
    ('rank-captain-v1', '上尉', '拔刀指挥姿态 · 硝烟刀光'),
    ('rank-major-v1', '少校', '正脸墨镜+手套 · 战术冷蓝'),
    ('rank-colonel-v1', '上校', '翻毛皮衣+坦克帽 · 装甲风暴'),
    ('rank-general-v1', '将军', '正脸金叶帽+元帅呢袍 · 统帅暗红'),
]


def make_circular(img, size):
    """缩放并裁剪为圆形头像（与前端 .chat-avatar 表现一致）。"""
    resized = img.resize((size, size), Image.Resampling.LANCZOS).convert('RGBA')
    mask = Image.new('L', (size * 4, size * 4), 0)
    draw = ImageDraw.Draw(mask)
    draw.ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
    mask = mask.resize((size, size), Image.Resampling.LANCZOS)
    output = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    output.paste(resized, (0, 0), mask)
    return output


def main():
    DEST.mkdir(parents=True, exist_ok=True)
    PREVIEW_DIR.mkdir(parents=True, exist_ok=True)

    # 1. 导出 384x384 WebP 到游戏素材目录
    for key, name, _ in AVATARS:
        src_path = SOURCE / f'{key}.png'
        im = Image.open(src_path).convert('RGB')
        im_resized = im.resize((384, 384), Image.Resampling.LANCZOS)
        out_webp = DEST / f'{key}.webp'
        im_resized.save(out_webp, 'WEBP', quality=92, method=6)
        print(f'Saved {out_webp.name} ({out_webp.stat().st_size} bytes)')

    # 2. 生成多尺寸聊天预览对比长图 (同时检验浅色和深色聊天背景)
    font_large = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 22)
    font_sub = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 14)
    font_badge = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 12)

    for theme_name, bg_color, card_bg, text_main, text_dim, border_color in [
        ('dark', '#131b26', '#1a2636', '#f1f5f9', '#94a3b8', '#334155'),
        ('light', '#f5f5f7', '#ffffff', '#1e293b', '#64748b', '#e2e8f0'),
    ]:
        sheet_w = 1200
        row_h = 160
        header_h = 90
        sheet_h = header_h + len(AVATARS) * row_h + 30
        sheet = Image.new('RGB', (sheet_w, sheet_h), bg_color)
        draw = ImageDraw.Draw(sheet)

        # 标题栏
        draw.text((36, 24), '二战统帅预制头像重构 · 8款高辨识度角色总览', fill=text_main, font=font_large)
        draw.text((36, 56), '针对聊天小尺寸（26px/36px）强化轮廓差异：包含墨镜、正面直视、加兰德步枪、指挥军刀、战地风镜与翻毛皮大衣', fill=text_dim, font=font_sub)

        for idx, (key, name, desc) in enumerate(AVATARS):
            y = header_h + idx * row_h
            im = Image.open(SOURCE / f'{key}.png').convert('RGB')

            # 单行背景卡片
            draw.rounded_rectangle([24, y, sheet_w - 24, y + row_h - 12], radius=10, fill=card_bg, outline=border_color, width=1)

            # 1. 原始方图 (128x128)
            thumb_128 = im.resize((128, 128), Image.Resampling.LANCZOS)
            sheet.paste(thumb_128, (40, y + 10))

            # 2. 名称与辨识特征描述
            draw.text((188, y + 24), f'{name}（{key}）', fill=text_main, font=font_large)
            draw.text((188, y + 60), f'辨识特征：{desc}', fill=text_dim, font=font_sub)

            # 3. 个人资料/战报大圆形头像 72px
            c_72 = make_circular(im, 72)
            sheet.paste(c_72, (520, y + 38), c_72)
            draw.text((530, y + 14), '资料页 72px', fill=text_dim, font=font_badge)

            # 4. 战报/排行榜中圆形头像 48px
            c_48 = make_circular(im, 48)
            sheet.paste(c_48, (660, y + 50), c_48)
            draw.text((664, y + 26), '列表中 48px', fill=text_dim, font=font_badge)

            # 5. 聊天栏大头像 36px
            c_36 = make_circular(im, 36)
            sheet.paste(c_36, (770, y + 56), c_36)
            draw.text((768, y + 32), '聊天 36px', fill=text_dim, font=font_badge)

            # 6. 核心验收：当前聊天气泡 26px 圆形头像（.chat-avatar）
            c_26 = make_circular(im, 26)
            sheet.paste(c_26, (870, y + 61), c_26)
            draw.text((860, y + 38), '当前聊天 26px', fill=text_dim, font=font_badge)

            # 7. 模拟聊天消息气泡外观
            bubble_x = 940
            bubble_y = y + 42
            bubble_bg = '#2563eb' if theme_name == 'dark' else '#3b82f6'
            draw.rounded_rectangle([bubble_x, bubble_y, bubble_x + 210, bubble_y + 44], radius=8, fill=bubble_bg)
            # 气泡里放 26px 头像
            sheet.paste(c_26, (bubble_x + 8, bubble_y + 9), c_26)
            draw.text((bubble_x + 42, bubble_y + 8), f'{name} 统帅', fill='#ffffff', font=font_badge)
            draw.text((bubble_x + 42, bubble_y + 24), '各部就绪，出征！', fill='#e0e7ff', font=font_badge)

        preview_file = PREVIEW_DIR / f'preview-{theme_name}.jpg'
        sheet.save(preview_file, 'JPEG', quality=94)
        print(f'Saved preview {preview_file.name}')


if __name__ == '__main__':
    main()
