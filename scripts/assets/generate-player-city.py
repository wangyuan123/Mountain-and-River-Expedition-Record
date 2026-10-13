"""使用 gpt-image-2 生成玩家城市 3D 写实微缩模型原图。"""
import base64
import os
from pathlib import Path
import socket
import requests

# 规避内网 DNS 解析到离线内网 IP 的问题，直接路由到外网网关公网 IP
orig_getaddrinfo = socket.getaddrinfo
def patched_getaddrinfo(host, port, *args, **kwargs):
    if host == 'handai-code.hand-china.com':
        return orig_getaddrinfo('116.228.77.181', port, *args, **kwargs)
    return orig_getaddrinfo(host, port, *args, **kwargs)

socket.getaddrinfo = patched_getaddrinfo

ROOT = Path(__file__).resolve().parents[2]
PROMPT_FILE = ROOT / 'output/imagegen/player-city-20261009/prompt.txt'
RAW_DIR = ROOT / 'output/imagegen/player-city-20261009/raw'
API_URL = 'https://handai-code.hand-china.com/v1/images/generations'
API_KEY = os.environ.get('HANDAI_API_KEY')


def main():
    if not API_KEY:
        raise ValueError('HANDAI_API_KEY is not set')
    RAW_DIR.mkdir(parents=True, exist_ok=True)
    target_path = RAW_DIR / 'player_city_raw.png'

    prompt = PROMPT_FILE.read_text(encoding='utf-8').strip()
    print('Starting player city generation via HANDAI API...')
    headers = {
        'Authorization': f'Bearer {API_KEY}',
        'Content-Type': 'application/json'
    }
    payload = {
        'model': 'gpt-image-2',
        'prompt': prompt,
        'size': '1024x1024',
        'quality': 'high',
        'n': 1
    }
    resp = requests.post(API_URL, headers=headers, json=payload, timeout=180)
    if resp.status_code != 200:
        raise RuntimeError(f'Failed to generate player city: {resp.status_code} {resp.text}')
    res_json = resp.json()
    if 'data' not in res_json or not res_json['data']:
        raise RuntimeError(f'No data in response: {res_json}')
    img_data = res_json['data'][0]

    if img_data.get('url'):
        img_resp = requests.get(img_data['url'], timeout=60)
        if img_resp.status_code != 200:
            raise RuntimeError(f'Failed to download image: {img_resp.status_code}')
        target_path.write_bytes(img_resp.content)
    elif img_data.get('b64_json'):
        target_path.write_bytes(base64.b64decode(img_data['b64_json']))
    else:
        raise RuntimeError(f'Unknown image response format: {img_data.keys()}')

    print(f'Successfully saved raw player city to {target_path}')


if __name__ == '__main__':
    main()
