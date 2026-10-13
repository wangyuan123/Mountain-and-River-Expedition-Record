"""使用 gpt-image-2 生成战地医院（Field Hospital）3D 写实微缩模型 3 款候选方案原图。"""
import base64
import os
from pathlib import Path
import socket
import time
import requests

# 规避内网 DNS 解析到离线内网 IP 的问题，直接路由到外网网关公网 IP
orig_getaddrinfo = socket.getaddrinfo
def patched_getaddrinfo(host, port, *args, **kwargs):
    if host == 'handai-code.hand-china.com':
        return orig_getaddrinfo('116.228.77.181', port, *args, **kwargs)
    return orig_getaddrinfo(host, port, *args, **kwargs)

socket.getaddrinfo = patched_getaddrinfo

ROOT = Path(__file__).resolve().parents[2]
BASE_DIR = ROOT / 'output/imagegen/field-hospital-20261012'
RAW_DIR = BASE_DIR / 'raw'
API_URL = 'https://handai-code.hand-china.com/v1/images/generations'
API_KEY = os.environ.get('HANDAI_API_KEY')

OPTIONS = [
    ('option-1-pavilion-clinic', 'prompt-option-1.txt'),
    ('option-2-courtyard-infirmary', 'prompt-option-2.txt'),
    ('option-3-field-trauma-station', 'prompt-option-3.txt'),
]


def generate_single_option(option_name, prompt_file):
    target_path = RAW_DIR / f'{option_name}.png'
    prompt = (BASE_DIR / prompt_file).read_text(encoding='utf-8').strip()
    print(f'Starting generation for {option_name}...')

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
        raise RuntimeError(f'Failed to generate {option_name}: {resp.status_code} {resp.text}')
    res_json = resp.json()
    if 'data' not in res_json or not res_json['data']:
        raise RuntimeError(f'No data in response for {option_name}: {res_json}')
    img_data = res_json['data'][0]

    if img_data.get('url'):
        img_resp = requests.get(img_data['url'], timeout=60)
        if img_resp.status_code != 200:
            raise RuntimeError(f'Failed to download {option_name} image: {img_resp.status_code}')
        target_path.write_bytes(img_resp.content)
    elif img_data.get('b64_json'):
        target_path.write_bytes(base64.b64decode(img_data['b64_json']))
    else:
        raise RuntimeError(f'Unknown image response format: {img_data.keys()}')

    print(f'Successfully saved {option_name} to {target_path} ({target_path.stat().st_size} bytes)')


def main():
    if not API_KEY:
        raise ValueError('HANDAI_API_KEY is not set')
    RAW_DIR.mkdir(parents=True, exist_ok=True)

    for option_name, prompt_file in OPTIONS:
        target_path = RAW_DIR / f'{option_name}.png'
        if target_path.exists() and target_path.stat().st_size > 10000:
            print(f'{option_name} already exists ({target_path.stat().st_size} bytes), skipping.')
            continue
        generate_single_option(option_name, prompt_file)
        time.sleep(2)


if __name__ == '__main__':
    main()
