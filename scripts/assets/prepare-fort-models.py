"""处理四类城防武器白底原图，输出透明 WebP 微缩模型并同步至前端。"""
import json
from pathlib import Path
import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'output/imagegen/fort-models-20261015'
RAW_DIR = SOURCE / 'raw'
DEST = ROOT / 'frontend/img/units/models'
DEST_PNG = ROOT / 'frontend/img/units'

WEAPONS = [
    {
        'id': 'bunker',
        'name': '碉堡',
        'desc': '坚固掩体,反步兵,近程高血防',
        'role': '六角形加固钢筋混凝土机枪暗堡，射击孔与沙袋掩体'
    },
    {
        'id': 'howitzer',
        'name': '榴弹炮',
        'desc': '远程压制,反步兵与建筑',
        'role': '155毫米重型野战加榴炮，大仰角曲射炮管与弧形防盾'
    },
    {
        'id': 'antitank',
        'name': '反坦克炮',
        'desc': '穿甲火力,反装甲',
        'role': '75毫米长身管穿甲直射反坦克炮，双层倾角防盾与制退器'
    },
    {
        'id': 'flak',
        'name': '防空炮',
        'desc': '对空火力,反空军',
        'role': '88毫米大仰角十字防空高炮，十字稳定底座与精密瞄具'
    }
]


def extract_foreground(image_path: Path) -> Image.Image:
    """精准抠出微缩模型前景，去除摄影棚纯白背景，保留金属、混凝土与防盾高光细节。"""
    original = Image.open(image_path)
    if original.mode == 'RGBA' and original.getchannel('A').getextrema()[0] < 240:
        # 已带透明通道且非纯白背景
        return normalize_sprite(original)

    im = original.convert('RGB')
    rgb = np.array(im)
    h, w = rgb.shape[:2]

    # 计算与纯白底色的色差距离
    white_distance = 255.0 - rgb.min(axis=2).astype(float)

    mask = np.where(white_distance > 24.0, cv2.GC_PR_FGD, cv2.GC_PR_BGD).astype('uint8')
    mask[white_distance > 60.0] = cv2.GC_FGD
    mask[white_distance < 8.0] = cv2.GC_BGD

    # 四周边缘外圈强制为背景
    mask[:3, :] = mask[-3:, :] = cv2.GC_BGD
    mask[:, :3] = mask[:, -3:] = cv2.GC_BGD

    cv2.setRNGSeed(0)
    bgd_model = np.zeros((1, 65), np.float64)
    fgd_model = np.zeros((1, 65), np.float64)
    cv2.grabCut(rgb, mask, None, bgd_model, fgd_model, 4, cv2.GC_INIT_WITH_MASK)

    alpha = np.where((mask == cv2.GC_FGD) | (mask == cv2.GC_PR_FGD), 255.0, 0.0)

    # 亚像素级羽化边缘与去白边（Alpha Matting）
    edge = cv2.dilate((alpha == 0).astype(np.uint8), np.ones((3, 3), np.uint8)) > 0
    soft = np.clip((white_distance - 5.0) / 48.0, 0.0, 1.0)
    alpha[edge] = np.minimum(alpha[edge], soft[edge] * 255.0)

    # 消除边缘白色底色渗入
    a_norm = alpha / 255.0
    clean_rgb = rgb.astype(float)
    partial = (a_norm > 0.01) & (a_norm < 0.99)
    clean_rgb[partial] = np.clip(
        (clean_rgb[partial] - 255.0 * (1.0 - a_norm[partial, None])) / a_norm[partial, None],
        0.0, 255.0
    )

    rgba = np.dstack([clean_rgb.astype(np.uint8), np.clip(alpha, 0, 255).astype(np.uint8)])
    sprite = Image.fromarray(rgba, 'RGBA')
    return normalize_sprite(sprite)


def normalize_sprite(sprite: Image.Image, target_span: int = 348) -> Image.Image:
    """裁剪外围透明留白并重采样居中到 384x384 标准微缩模型画布。"""
    a_channel = sprite.getchannel('A')
    box = a_channel.point(lambda v: 255 if v > 15 else 0).getbbox()
    if not box:
        return Image.new('RGBA', (384, 384), (0, 0, 0, 0))

    cropped = sprite.crop(box)
    scale = min(target_span / cropped.width, target_span / cropped.height)
    new_w = round(cropped.width * scale)
    new_h = round(cropped.height * scale)
    resized = cropped.resize((new_w, new_h), Image.Resampling.LANCZOS)

    canvas = Image.new('RGBA', (384, 384), (0, 0, 0, 0))
    canvas.alpha_composite(resized, ((384 - new_w) // 2, (384 - new_h) // 2))
    return canvas


def build_showcase(sprites: dict):
    """生成 4 类城防武器在浅色与深色背景下的验收展示图。"""
    tile_w, tile_h = 320, 320
    margin = 24
    cols = len(WEAPONS)
    header_h = 80
    footer_h = 65
    total_w = margin * 2 + cols * tile_w + (cols - 1) * margin
    total_h = header_h + tile_h * 2 + margin + footer_h

    sheet = Image.new('RGB', (total_w, total_h), (24, 26, 27))
    draw = ImageDraw.Draw(sheet)

    font_title = None
    font_sub = None
    font_desc = None
    for fp in [
        '/System/Library/Fonts/PingFang.ttc',
        '/System/Library/Fonts/STHeiti Light.ttc',
        '/System/Library/Fonts/Hiragino Sans GB.ttc',
    ]:
        if Path(fp).exists():
            try:
                font_title = ImageFont.truetype(fp, 22)
                font_sub = ImageFont.truetype(fp, 17)
                font_desc = ImageFont.truetype(fp, 13)
                break
            except Exception:
                pass

    if not font_title:
        font_title = font_sub = font_desc = ImageFont.load_default()

    draw.text((margin, 24), "《烽火平原》城防武器微缩模型总览（碉堡·榴弹炮·反坦克炮·防空炮）", fill=(245, 235, 215), font=font_title)

    bg_light = Image.new('RGBA', (tile_w, tile_h), (244, 240, 230, 255))
    bg_dark = Image.new('RGBA', (tile_w, tile_h), (27, 39, 49, 255))

    for idx, w_info in enumerate(WEAPONS):
        wid = w_info['id']
        sp = sprites.get(wid)
        if not sp:
            continue

        x = margin + idx * (tile_w + margin)

        # Row 1: 浅色卡片背景（模拟主界面与城防总览）
        y1 = header_h
        draw.rectangle([x - 2, y1 - 2, x + tile_w + 1, y1 + tile_h + 1], fill=(225, 220, 210), outline=(190, 185, 175))
        light_comp = Image.alpha_composite(bg_light, sp.resize((tile_w, tile_h), Image.Resampling.LANCZOS))
        sheet.paste(light_comp.convert('RGB'), (x, y1))

        # Row 2: 深色军事背景（模拟战场暗色模式）
        y2 = header_h + tile_h + margin
        draw.rectangle([x - 2, y2 - 2, x + tile_w + 1, y2 + tile_h + 1], fill=(20, 28, 36), outline=(45, 60, 75))
        dark_comp = Image.alpha_composite(bg_dark, sp.resize((tile_w, tile_h), Image.Resampling.LANCZOS))
        sheet.paste(dark_comp.convert('RGB'), (x, y2))

        # 底部名称与性能说明
        y_text = y2 + tile_h + 10
        draw.text((x + 4, y_text), f"{w_info['name']} ({wid})", fill=(245, 230, 175), font=font_sub)
        draw.text((x + 4, y_text + 24), w_info['desc'], fill=(180, 185, 175), font=font_desc)

    showcase_path = SOURCE / 'fort_models_showcase.png'
    sheet.save(showcase_path, quality=95)
    print(f"Saved showcase sheet: {showcase_path}")

    # 同步到 artifacts 目录
    artifact_path = Path('/Users/chenjuan/.gemini/antigravity/brain/9e95bd95-2b24-43d4-9674-df0e02fe2be4/fort_models_showcase.png')
    sheet.save(artifact_path, quality=95)
    print(f"Saved artifact showcase: {artifact_path}")


def main():
    DEST.mkdir(parents=True, exist_ok=True)
    DEST_PNG.mkdir(parents=True, exist_ok=True)

    print("Processing 4 fort weapon raw images...")
    sprites = {}

    for w in WEAPONS:
        wid = w['id']
        raw_path = RAW_DIR / f"{wid}.png"
        if not raw_path.exists():
            print(f"Error: {raw_path} not found!")
            continue

        print(f"Extracting foreground for {wid} ({w['name']})...")
        sprite = extract_foreground(raw_path)
        sprites[wid] = sprite

        # 1. 导出 384x384 WebP 生产资产到 frontend/img/units/models/
        webp_dest = DEST / f"{wid}.webp"
        sprite.save(webp_dest, 'WEBP', quality=95, method=6)
        print(f"  -> Saved {webp_dest}")

        # 2. 导出 PNG 资产到 frontend/img/units/
        png_dest = DEST_PNG / f"{wid}.png"
        sprite.save(png_dest, 'PNG', optimize=True)
        print(f"  -> Saved {png_dest}")

    build_showcase(sprites)
    print("All fort weapon models processed successfully!")


if __name__ == '__main__':
    main()
