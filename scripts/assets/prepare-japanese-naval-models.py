"""将日本海军 NPC 的白底原图裁切为透明 WebP，并生成深浅预览。"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

from importlib.util import module_from_spec, spec_from_file_location


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'output/imagegen/japanese-naval-npc-20260929'
DEST = ROOT / 'frontend/img/npc/japanese-navy'
SHIPS = [
    ('carrier', '航母'),
    ('battleship', '战列舰'),
    ('sub', '潜艇'),
    ('destroyer', '驱逐舰'),
]


def load_cutout():
    path = Path(__file__).with_name('prepare-unit-models.py')
    spec = spec_from_file_location('prepare_unit_models', path)
    module = module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.prepare


def main():
    cutout = load_cutout()
    DEST.mkdir(parents=True, exist_ok=True)
    for key, _ in SHIPS:
        cutout(SOURCE / 'raw' / f'{key}.png').save(DEST / f'{key}.webp', quality=93, method=6)

    font = ImageFont.truetype('/System/Library/Fonts/PingFang.ttc', 24)
    for theme, bg, ink in [('light', '#f4f0e6', '#283b36'), ('dark', '#172431', '#eaf0f5')]:
        sheet = Image.new('RGB', (960, 700), bg)
        draw = ImageDraw.Draw(sheet)
        for i, (key, label) in enumerate(SHIPS):
            model = Image.open(DEST / f'{key}.webp').convert('RGBA')
            x, y = i % 2 * 480, i // 2 * 350
            large = model.resize((240, 240), Image.Resampling.LANCZOS)
            sheet.paste(large, (x + 24, y + 16), large)
            draw.text((x + 28, y + 270), label, fill=ink, font=font)
            for size, offset in [(32, 294), (48, 346), (72, 410)]:
                small = model.resize((size, size), Image.Resampling.LANCZOS)
                sheet.paste(small, (x + offset, y + 150), small)
        sheet.save(SOURCE / f'preview-{theme}.jpg', quality=94)


if __name__ == '__main__':
    main()
