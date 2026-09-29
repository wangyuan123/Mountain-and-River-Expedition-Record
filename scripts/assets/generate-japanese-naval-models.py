"""通过 gpt-image-2 接口执行 batch.jsonl 生成日本海军 NPC 微缩模型白底原稿。"""
import base64
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import requests

ROOT = Path(__file__).resolve().parents[2]
BATCH_FILE = ROOT / 'output/imagegen/japanese-naval-npc-20260929/batch.jsonl'
RAW_DIR = ROOT / 'output/imagegen/japanese-naval-npc-20260929/raw'
API_URL = 'https://handai-code.hand-china.com/v1/images/generations'
API_KEY = os.environ.get('HANDAI_API_KEY')


def generate_item(item):
    out_name = item['out']
    target_path = RAW_DIR / out_name
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
        raise RuntimeError(f'Failed to generate {out_name}: {resp.status_code} {resp.text}')
    res_json = resp.json()
    if 'data' not in res_json or not res_json['data']:
        raise RuntimeError(f'No data in response for {out_name}: {res_json}')
    img_data = res_json['data'][0]
    
    if img_data.get('url'):
        img_resp = requests.get(img_data['url'], timeout=60)
        if img_resp.status_code != 200:
            raise RuntimeError(f'Failed to download {out_name}: {img_resp.status_code}')
        target_path.write_bytes(img_resp.content)
    elif img_data.get('b64_json'):
        target_path.write_bytes(base64.b64decode(img_data['b64_json']))
    else:
        raise RuntimeError(f'Unknown image response format for {out_name}: {img_data.keys()}')

    print(f'Successfully saved {out_name} ({target_path.stat().st_size} bytes)')
    return out_name


def main():
    if not API_KEY:
        raise ValueError('HANDAI_API_KEY is not set')
    RAW_DIR.mkdir(parents=True, exist_ok=True)
    items = []
    with open(BATCH_FILE, 'r', encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if line:
                items.append(json.loads(line))
    print(f'Loaded {len(items)} items from {BATCH_FILE}')
    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(generate_item, items))
    print(f'All {len(results)} Japanese naval models generated successfully: {results}')


if __name__ == '__main__':
    main()
