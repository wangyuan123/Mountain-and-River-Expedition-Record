"""批量生成新版 3D 游戏角色指挥官风格微缩模型头像并导出 WebP 资源。"""
import base64
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
from PIL import Image
import requests
import shutil

ROOT = Path('/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains')
OUT_DIR = ROOT / 'output/imagegen/historical-rank-avatars-20260926'
GAME_DIR = ROOT / 'frontend/img/avatars/historical'
ARTIFACT_DIR = Path('/Users/chenjuan/.gemini/antigravity/brain/e7c50366-4aba-468a-b1e4-620745aa6c86')

API_URL = 'https://handai-code.hand-china.com/v1/images/generations'
API_KEY = os.environ.get('HANDAI_API_KEY')

PROMPTS = [
    {
        'id': 'rank-private-v1',
        'name': '列兵',
        'out': 'rank-private-v1.png',
        'prompt': (
            'Stylized 3D game character bust portrait of a fictional WWII military private soldier, video game commander asset style. '
            'Clean 3D CGI character model render, smooth shaded stylized skin, youthful heroic facial planes, distinct video game art aesthetic, NOT a real human photograph, non-photorealistic. '
            'Wearing a smooth olive-gray steel combat helmet with plain unadorned surface (no insignia, no stars), simple neat khaki-olive field uniform tunic with utilitarian collar. '
            'Three-quarter bust profile, disciplined and determined young soldier expression. '
            'Plain dark tactical background with subtle dark gray hexagonal honeycomb texture only. '
            'Completely clean background, absolutely NO text, NO letters, NO words, NO flags, NO emblems, NO logos, NO map graphics, NO watermarks.'
        )
    },
    {
        'id': 'rank-corporal-v1',
        'name': '下士',
        'out': 'rank-corporal-v1.png',
        'prompt': (
            'Stylized 3D game character bust portrait of a fictional WWII military corporal, video game commander asset style. '
            'Clean 3D CGI character model render, smooth shaded stylized skin, alert lean facial planes, distinct video game art aesthetic, NOT a real human photograph, non-photorealistic. '
            'Wearing a smooth olive-gray steel combat helmet with a subtle small painted corporal chevron marking, clean military field tunic with subtle junior enlisted collar rank tabs. '
            'Three-quarter bust profile, sharp and vigilant squad leader expression. '
            'Plain dark tactical background with subtle dark gray hexagonal honeycomb texture only. '
            'Completely clean background, absolutely NO text, NO letters, NO words, NO flags, NO emblems, NO logos, NO map graphics, NO watermarks.'
        )
    },
    {
        'id': 'rank-sergeant-v1',
        'name': '上士',
        'out': 'rank-sergeant-v1.png',
        'prompt': (
            'Stylized 3D game character bust portrait of a fictional WWII military senior sergeant, video game commander asset style. '
            'Clean 3D CGI character model render, smooth shaded stylized skin, chiseled rugged facial planes, distinct video game art aesthetic, NOT a real human photograph, non-photorealistic. '
            'Wearing a smooth olive-gray steel combat helmet with three clean sergeant chevrons emblem, field uniform jacket with leather equipment strap and sergeant collar tabs. '
            'Three-quarter bust profile, calm, tough and experienced senior enlisted soldier expression. '
            'Plain dark tactical background with subtle dark gray hexagonal honeycomb texture only. '
            'Completely clean background, absolutely NO text, NO letters, NO words, NO flags, NO emblems, NO logos, NO map graphics, NO watermarks.'
        )
    },
    {
        'id': 'rank-lieutenant-v1',
        'name': '少尉',
        'out': 'rank-lieutenant-v1.png',
        'prompt': (
            'Stylized 3D game character bust portrait of a fictional WWII military platoon lieutenant officer, video game commander asset style. '
            'Clean 3D CGI character model render, smooth shaded stylized skin, sharp handsome facial planes, distinct video game art aesthetic, NOT a real human photograph, non-photorealistic. '
            'Wearing a smooth olive-gray steel combat helmet with a single clean silver bar insignia on the front, crisp military officer dress tunic with collar tie and single bar collar insignia. '
            'Three-quarter bust profile, focused and ambitious junior officer expression. '
            'Plain dark tactical background with subtle dark gray hexagonal honeycomb texture only. '
            'Completely clean background, absolutely NO text, NO letters, NO words, NO flags, NO emblems, NO logos, NO map graphics, NO watermarks.'
        )
    },
    {
        'id': 'rank-captain-v1',
        'name': '上尉',
        'out': 'rank-captain-v1.png',
        'prompt': (
            'Stylized 3D game character bust portrait of a fictional WWII military company commander captain, video game commander asset style. '
            'Clean 3D CGI character model render, smooth shaded stylized skin, broad confident facial planes, distinct video game art aesthetic, NOT a real human photograph, non-photorealistic. '
            'Wearing a smooth olive-gray steel combat helmet with two clean parallel silver bars insignia on the front, crisp military officer service tunic with tie and captain collar insignia. '
            'Three-quarter bust profile, decisive and composed tactical commander expression. '
            'Plain dark tactical background with subtle dark gray hexagonal honeycomb texture only. '
            'Completely clean background, absolutely NO text, NO letters, NO words, NO flags, NO emblems, NO logos, NO map graphics, NO watermarks.'
        )
    },
    {
        'id': 'rank-major-v1',
        'name': '少校',
        'out': 'rank-major-v1.png',
        'prompt': (
            'Stylized 3D game character bust portrait of a fictional WWII military battalion major officer, video game commander asset style. '
            'Clean 3D CGI character model render, smooth shaded stylized skin, thoughtful mature facial planes, distinct video game art aesthetic, NOT a real human photograph, non-photorealistic. '
            'Wearing a smooth olive-gray steel combat helmet with a clean gold oak leaf insignia on the front, tailored military service tunic with tie and golden collar insignias. '
            'Three-quarter bust profile, reflective and seasoned field officer expression. '
            'Plain dark tactical background with subtle dark gray hexagonal honeycomb texture only. '
            'Completely clean background, absolutely NO text, NO letters, NO words, NO flags, NO emblems, NO logos, NO map graphics, NO watermarks.'
        )
    },
    {
        'id': 'rank-colonel-v1',
        'name': '上校',
        'out': 'rank-colonel-v1.png',
        'prompt': (
            'Stylized 3D game character bust portrait of a fictional WWII military regimental colonel commander, video game commander asset style. '
            'Clean 3D CGI character model render, smooth shaded stylized skin, sharp weathered angular jawline, distinct video game art aesthetic, NOT a real human photograph, non-photorealistic. '
            'Wearing a smooth olive-gray steel combat helmet with a clean silver eagle crest on the front, crisp formal officer service coat with epaulets, tie and colonel collar insignias. '
            'Three-quarter bust profile, commanding and authoritative senior officer expression. '
            'Plain dark tactical background with subtle dark gray hexagonal honeycomb texture only. '
            'Completely clean background, absolutely NO text, NO letters, NO words, NO flags, NO emblems, NO logos, NO map graphics, NO watermarks.'
        )
    }
]


def generate_single_item(item):
    out_name = item['out']
    target_png = OUT_DIR / out_name
    stem = Path(out_name).stem
    target_webp = GAME_DIR / f'{stem}.webp'
    artifact_png = ARTIFACT_DIR / out_name

    print(f'Starting generation for {out_name} ({item["name"]})...')
    headers = {
        'Authorization': f'Bearer {API_KEY}',
        'Content-Type': 'application/json'
    }
    payload = {
        'model': 'gpt-image-2',
        'prompt': item['prompt'],
        'size': '1024x1024',
        'quality': 'high',
        'n': 1
    }
    resp = requests.post(API_URL, headers=headers, json=payload, timeout=180)
    if resp.status_code != 200:
        raise RuntimeError(f'Failed to generate {out_name}: {resp.status_code} {resp.text[:300]}')
    
    data = resp.json()
    item_data = data['data'][0]
    b64 = item_data.get('b64_json')
    if not b64:
        raise RuntimeError(f'No b64_json returned for {out_name}')
    
    img_bytes = base64.b64decode(b64)
    target_png.write_bytes(img_bytes)
    artifact_png.write_bytes(img_bytes)
    print(f'Saved PNG: {out_name} ({len(img_bytes)} bytes)')

    # Convert to 384x384 WebP
    with Image.open(target_png) as img:
        thumb = img.resize((384, 384), Image.Resampling.LANCZOS)
        thumb.save(target_webp, 'WEBP', quality=90)
    print(f'Saved WebP: {target_webp}')

    return out_name


def main():
    if not API_KEY:
        raise ValueError('HANDAI_API_KEY environment variable is not set')

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    GAME_DIR.mkdir(parents=True, exist_ok=True)
    ARTIFACT_DIR.mkdir(parents=True, exist_ok=True)

    # 1. Process General (using approved style_test_v2.png)
    approved_general = ARTIFACT_DIR / 'style_test_v2.png'
    target_general_png = OUT_DIR / 'rank-general-v1.png'
    artifact_general_png = ARTIFACT_DIR / 'rank-general-v1.png'
    target_general_webp = GAME_DIR / 'rank-general-v1.webp'

    if approved_general.exists():
        shutil.copy(approved_general, target_general_png)
        shutil.copy(approved_general, artifact_general_png)
        with Image.open(approved_general) as img:
            thumb = img.resize((384, 384), Image.Resampling.LANCZOS)
            thumb.save(target_general_webp, 'WEBP', quality=90)
        print('General (上将) copied from approved style_test_v2.png successfully.')

    # 2. Concurrently generate the other 7 ranks
    with ThreadPoolExecutor(max_workers=3) as pool:
        results = list(pool.map(generate_single_item, PROMPTS))

    print(f'All 7 items generated successfully: {results}')


if __name__ == '__main__':
    main()
