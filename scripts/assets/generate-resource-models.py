"""通过 gpt-image-2 接口执行 batch.jsonl 生成资源微缩模型白底原稿。"""
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import requests

ROOT = Path(__file__).resolve().parents[2]
BATCH_FILE = ROOT / 'output/imagegen/resource-models-20260923/batch.jsonl'
RAW_DIR = ROOT / 'output/imagegen/resource-models-20260923/raw'
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
    resp = requests.post(API_URL, headers=headers, json=payload, timeout=120)
    if resp.status_code != 200:
        raise RuntimeError(f'Failed to generate {out_name}: {resp.status_code} {resp.text}')
    data = resp.json()
    img_url = data['data'][0]['url']
    img_resp = requests.get(img_url, timeout=60)
    if img_resp.status_code != 200:
        raise RuntimeError(f'Failed to download {out_name}: {img_resp.status_code}')
    target_path.write_bytes(img_resp.content)
    print(f'Successfully saved {out_name} ({len(img_resp.content)} bytes)')
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
    with ThreadPoolExecutor(max_workers=3) as pool:
        results = list(pool.map(generate_item, items))
    print(f'All {len(results)} resource models generated successfully: {results}')


if __name__ == '__main__':
    main()
