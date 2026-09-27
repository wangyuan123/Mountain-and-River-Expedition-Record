"""通过 HandAI 图像接口执行 batch.jsonl 生成历史风格军衔人物微缩模型图并导出 WebP。"""
import base64
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
from PIL import Image
import requests

ROOT = Path('/Users/chenjuan/Documents/游戏/Strategies-of-the-Flaming-Plains')
OUT_DIR = ROOT / 'output/imagegen/historical-rank-avatars-20260926'
GAME_DIR = ROOT / 'frontend/img/avatars/historical'
ARTIFACT_DIR = Path('/Users/chenjuan/.gemini/antigravity/brain/e7c50366-4aba-468a-b1e4-620745aa6c86')

API_URL = 'https://handai-code.hand-china.com/v1/images/generations'
API_KEY = os.environ.get('HANDAI_API_KEY')

ITEMS_TO_GENERATE = [
    'rank-sergeant-v1.png',
    'rank-lieutenant-v1.png',
    'rank-captain-v1.png',
    'rank-major-v1.png',
    'rank-colonel-v1.png',
    'rank-general-v1.png'
]


def process_item(item):
    out_name = item['out']
    target_png = OUT_DIR / out_name
    stem = Path(out_name).stem
    target_webp = GAME_DIR / f'{stem}.webp'
    artifact_png = ARTIFACT_DIR / out_name

    print(f'Starting generation for {out_name}...')
    headers = {
        'Authorization': f'Bearer {API_KEY}',
        'Content-Type': 'application/json'
    }
    payload = {
        'model': item.get('model', 'gpt-image-2'),
        'prompt': item['prompt'],
        'size': item.get('size', '1024x1024'),
        'quality': item.get('quality', 'high'),
        'n': 1
    }
    resp = requests.post(API_URL, headers=headers, json=payload, timeout=180)
    if resp.status_code != 200:
        raise RuntimeError(f'Failed to generate {out_name}: {resp.status_code} {resp.text[:300]}')
    
    data = resp.json()
    item_data = data['data'][0]
    
    if 'b64_json' in item_data:
        img_bytes = base64.b64decode(item_data['b64_json'])
    elif 'url' in item_data:
        img_resp = requests.get(item_data['url'], timeout=60)
        if img_resp.status_code != 200:
            raise RuntimeError(f'Failed to download {out_name} from URL')
        img_bytes = img_resp.content
    else:
        raise RuntimeError(f'No image data in response for {out_name}: {list(item_data.keys())}')

    # 1. Save original PNG to OUT_DIR
    target_png.write_bytes(img_bytes)
    # Also save to artifact dir for preview
    artifact_png.write_bytes(img_bytes)
    print(f'Saved original PNG: {target_png} ({len(img_bytes)} bytes)')

    # 2. Convert and resize to 384x384 WebP
    with Image.open(target_png) as img:
        thumb = img.resize((384, 384), Image.Resampling.LANCZOS)
        thumb.save(target_webp, 'WEBP', quality=90)
    print(f'Saved game WebP: {target_webp}')

    return out_name


def main():
    if not API_KEY:
        raise ValueError('HANDAI_API_KEY environment variable is not set')

    GAME_DIR.mkdir(parents=True, exist_ok=True)
    ARTIFACT_DIR.mkdir(parents=True, exist_ok=True)

    with open(OUT_DIR / 'batch.jsonl', 'r', encoding='utf-8') as f:
        all_items = [json.loads(line) for line in f if line.strip()]

    target_items = [it for it in all_items if it['out'] in ITEMS_TO_GENERATE]
    print(f'Items to generate: {[it["out"] for it in target_items]}')

    with ThreadPoolExecutor(max_workers=3) as pool:
        results = list(pool.map(process_item, target_items))

    print(f'Successfully completed all {len(results)} items: {results}')


if __name__ == '__main__':
    main()
