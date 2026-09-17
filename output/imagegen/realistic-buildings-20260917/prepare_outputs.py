"""Prepare review assets from generated originals without modifying source images."""
from pathlib import Path
import json
import shutil
import zipfile
from PIL import Image, ImageDraw, ImageFont, ImageOps

ROOT = Path(__file__).resolve().parent
FONT = '/System/Library/Fonts/PingFang.ttc'
STYLE_TEXT = {
    'a-garden': ('A', '花园卫城配套', '奶油石墙 · 红陶屋瓦 · 精致庭院', '最接近现有玩家城市，推荐优先比较。'),
    'b-frontier': ('B', '滇缅战地营区', '灰瓦木构 · 军绿铁皮 · 战地院落', '更贴近远征军前进基地，材质有适度风化。'),
    'c-industrial': ('C', '红砖工业基地', '红砖立面 · 蓝灰屋顶 · 钢架设施', '更强的军工与后勤气质，建筑体量厚重。'),
}

def font(size):
    return ImageFont.truetype(FONT, size, index=2)

def main():
    manifest = json.loads((ROOT / 'manifest.json').read_text())
    buildings = manifest['buildings']
    # Batch CLI flattens output paths; use explicit style prefixes until filing them.
    for source in (ROOT / 'generated-bc').glob('*.png'):
        style, name = source.name.split('--', 1)
        target = ROOT / style / name
        if not target.exists():
            shutil.copy2(source, target)
    for style in ['b-frontier', 'c-industrial']:
        source = ROOT / 'pilots' / f'{style}--command-v2.png'
        target = ROOT / style / 'command.png'
        if source.exists():
            shutil.copy2(source, target)
    metadata = []
    for style in manifest['styles']:
        sid = style['id']
        code, name, subtitle, desc = STYLE_TEXT[sid]
        style.update(code=code, name=name, subtitle=subtitle, desc=desc)
        preview_dir = ROOT / sid / 'preview'
        preview_dir.mkdir(exist_ok=True)
        for building in buildings:
            source = ROOT / sid / f"{building['id']}.png"
            if not source.exists():
                continue
            with Image.open(source) as im:
                im.load()
                metadata.append(dict(style=sid, building=building['id'], size=list(im.size), mode=im.mode, bytes=source.stat().st_size))
                preview = ImageOps.contain(im.convert('RGB'), (512, 512), Image.Resampling.LANCZOS)
                preview.save(preview_dir / f"{building['id']}.webp", quality=88, method=6)
        if all((ROOT / sid / f"{b['id']}.png").exists() for b in buildings):
            sheet = Image.new('RGB', (2380, 1350), '#eeeae1')
            draw = ImageDraw.Draw(sheet)
            draw.text((52, 33), '山河远征录 / REALISTIC ARCHITECTURAL MINIATURES', fill='#657064', font=font(20))
            draw.text((52, 74), f'{code} · {name}', fill='#28392e', font=font(48))
            draw.text((52, 139), subtitle, fill='#6c7366', font=font(22))
            for i, b in enumerate(buildings):
                x, y = 50 + (i % 7) * 328, 208 + (i // 7) * 360
                draw.rounded_rectangle((x, y, x + 310, y + 340), radius=9, fill='#faf8f2', outline='#d1d1c4', width=1)
                with Image.open(ROOT / sid / f"{b['id']}.png") as im:
                    tile = ImageOps.contain(im.convert('RGB'), (294, 294), Image.Resampling.LANCZOS)
                    sheet.paste(tile, (x + 8 + (294-tile.width)//2, y + 8 + (294-tile.height)//2))
                label = f'{i+1:02}  {b["name"]}'
                draw.text((x + 15, y + 307), label, fill='#344638', font=font(21))
            draw.text((52, 1310), '军事区 17 栋 + 资源区 4 栋 / gpt-image-2 / 原始高清图与 512 px 预览均已保存', fill='#6c7366', font=font(19))
            sheet.save(ROOT / f'preview-{code.lower()}.jpg', quality=94, subsampling=0)
    manifest['generated_count'] = len(metadata)
    manifest['status'] = 'complete' if len(metadata) == 63 else 'generating'
    manifest['method'] = 'imagegen bundled CLI, gpt-image-2, user-authorized compatible API'
    (ROOT / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    (ROOT / 'image-metadata.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n')
    template = (ROOT / 'gallery-template.html').read_text()
    (ROOT / 'index.html').write_text(template.replace('__MANIFEST__', json.dumps(manifest, ensure_ascii=False)))
    # The overview compares the same three facility types across all directions.
    examples = ['command', 'factory', 'farm']
    if all((ROOT / s / f'{k}.png').exists() for s in STYLE_TEXT for k in examples):
        board = Image.new('RGB', (1560, 1730), '#eeeae1')
        d = ImageDraw.Draw(board)
        d.text((40, 26), '真实建筑微缩模型 · 三套方向', fill='#28392e', font=font(39))
        d.text((40, 90), '同一建筑横向对照：市政厅 / 军工厂 / 农田', fill='#6c7366', font=font(21))
        for col, (sid, (code, name, _, _)) in enumerate(STYLE_TEXT.items()):
            d.text((40+col*510, 138), f'{code} · {name}', fill='#344638', font=font(27))
            for row, key in enumerate(examples):
                x, y = 30+col*510, 188+row*508
                with Image.open(ROOT / sid / f'{key}.png') as im:
                    tile = ImageOps.fit(im.convert('RGB'), (480, 480), method=Image.Resampling.LANCZOS)
                    board.paste(tile, (x, y))
                title = next(b['name'] for b in buildings if b['id'] == key)
                d.text((x+10, y+480), title, fill='#344638', font=font(19))
        board.save(ROOT / 'three-directions.jpg', quality=94, subsampling=0)
    if len(metadata) == 63:
        with zipfile.ZipFile(ROOT / 'realistic-building-icons.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
            for sid in STYLE_TEXT:
                for b in buildings:
                    for file in [ROOT/sid/f"{b['id']}.png", ROOT/sid/f"{b['id']}.prompt.txt", ROOT/sid/'preview'/f"{b['id']}.webp"]:
                        archive.write(file, file.relative_to(ROOT))
            for name in ['index.html', 'manifest.json', 'image-metadata.json', 'README.md', 'prompts.jsonl', 'preview-a.jpg', 'preview-b.jpg', 'preview-c.jpg', 'three-directions.jpg']:
                if (ROOT/name).exists():
                    archive.write(ROOT/name, name)
    print(f'Prepared {len(metadata)}/63 images, preview assets, and review page.')

if __name__ == '__main__':
    main()
