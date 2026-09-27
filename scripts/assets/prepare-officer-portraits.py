"""Validate generated fictional officer portraits and export game-sized WebP assets."""

import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "output/imagegen/officer-portraits-20260927"
DESTINATION = ROOT / "frontend/img/officers/historical"


def main():
    assets = json.loads((SOURCE / "briefs.json").read_text(encoding="utf-8"))["assets"]
    missing = [asset["id"] for asset in assets if not (SOURCE / f"{asset['id']}.png").is_file()]
    if missing:
        raise SystemExit("Missing generated portraits: " + ", ".join(missing))

    # Validate the entire batch before writing game files, so a partial API run stays invisible to players.
    for asset in assets:
        with Image.open(SOURCE / f"{asset['id']}.png") as image:
            if image.width != image.height or image.width < 512:
                raise SystemExit(f"Unexpected portrait dimensions: {asset['id']} {image.size}")

    DESTINATION.mkdir(parents=True, exist_ok=True)
    sheet = Image.new("RGB", (6 * 210, 6 * 242), "#19231f")
    draw = ImageDraw.Draw(sheet)
    font = ImageFont.load_default()

    for index, asset in enumerate(assets):
        with Image.open(SOURCE / f"{asset['id']}.png") as image:
            thumbnail = image.convert("RGB").resize((384, 384), Image.Resampling.LANCZOS)
            thumbnail.save(DESTINATION / f"{asset['id']}.webp", "WEBP", quality=88, method=6)
            preview = thumbnail.resize((192, 192), Image.Resampling.LANCZOS)
        x = (index % 6) * 210 + 9
        y = (index // 6) * 242 + 8
        sheet.paste(preview, (x, y))
        draw.text((x, y + 197), asset["id"], fill="#f3e7d1", font=font)

    sheet.save(SOURCE / "contact-sheet.jpg", quality=90)
    print(f"Exported {len(assets)} 384px WebP portraits to {DESTINATION}")
    print(f"Review contact sheet: {SOURCE / 'contact-sheet.jpg'}")


if __name__ == "__main__":
    main()
