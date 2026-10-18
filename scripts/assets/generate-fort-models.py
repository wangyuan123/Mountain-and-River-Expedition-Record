"""使用 gpt-image-2 生成四类城防武器（碉堡、榴弹炮、反坦克炮、防空炮）的 3D 写实微缩模型原图。"""
import base64
import os
from pathlib import Path
import socket
import sys
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
OUTPUT_DIR = ROOT / 'output/imagegen/fort-models-20261015'
RAW_DIR = OUTPUT_DIR / 'raw'
API_URL = 'https://handai-code.hand-china.com/v1/images/generations'
API_KEY = os.environ.get('HANDAI_API_KEY')

PROMPTS = {
    'bunker': (
        "Use case: product-mockup\n"
        "Asset type: realistic miniature military fortification icon for historical strategy game defense.\n"
        "Primary request: One WWII reinforced concrete bunker pillbox miniature model (碉堡). "
        "Low-profile hexagonal cast reinforced concrete bunker with rough weathered texture, olive drab and grey concrete finish, "
        "dark horizontal armored machine gun firing slits (embrasure) on front facets, low reinforced dome roof with subtle olive camouflage paint and battlefield wear, "
        "neat small sandbags stacked around the base. Solid, heavy, low-profile defensive bunker model.\n"
        "Style/medium: premium museum-quality painted resin/concrete scale miniature photographed as a real physical model, "
        "realistic concrete, steel, and canvas textures, restrained field wear, sophisticated realistic details. "
        "Never cartoon, flat vector, toy caricature, badge or game UI.\n"
        "Composition/framing: square canvas, elevated three-quarter isometric view from above at about 30 degrees, "
        "facing lower-left, entire bunker fits in frame with at least 10 percent clean margin. Clean silhouette.\n"
        "Scene/backdrop: perfectly uniform pure white #ffffff seamless background, isolated subject, no ground plate, no plinth, no scenery, no horizon. "
        "No cast shadow on background; subtle ambient contact shading only under the bunker base.\n"
        "Lighting/mood: crisp neutral studio daylight from upper left, realistic material highlights.\n"
        "Constraints: exactly one bunker. No people, no vehicles, no text, no labels, no watermark, no flags."
    ),
    'howitzer': (
        "Use case: product-mockup\n"
        "Asset type: realistic miniature artillery gun icon for WWII strategy game city defense.\n"
        "Primary request: One heavy field howitzer artillery gun miniature model (155mm howitzer / 榴弹炮). "
        "Large-caliber rifled cannon barrel elevated at a steep indirect fire angle (about 35 degrees) pointing towards the left, "
        "heavy curved protective gun shield with sighting port, sturdy split-trail carriage with ground spades anchored at the rear, "
        "heavy twin rubber artillery tires, hydraulic recoil cylinders above and below the barrel, olive drab military paint with matte steel mechanical wear.\n"
        "Style/medium: premium museum-quality painted scale miniature artillery photographed as a real physical model, "
        "crisp metal casting, rivets, realistic machined steel and rubber, restrained battle wear. "
        "Never cartoon, flat vector, toy caricature, badge or game UI.\n"
        "Composition/framing: square canvas, elevated three-quarter view from above at about 25 degrees, "
        "gun barrel aiming towards upper-left, entire artillery piece fits in frame with at least 8 percent clean margin. Broad readable silhouette.\n"
        "Scene/backdrop: perfectly uniform pure white #ffffff seamless background, isolated subject, no ground base, no plinth, no scenery, no horizon. "
        "No cast shadow on background; subtle contact shading only under tires and spades.\n"
        "Lighting/mood: soft neutral studio daylight from upper left, realistic metallic highlights.\n"
        "Constraints: exactly one howitzer gun. No soldiers, no muzzle flash, no explosion, no smoke, no text, no watermark, no flags."
    ),
    'antitank': (
        "Use case: product-mockup\n"
        "Asset type: realistic miniature anti-tank gun icon for WWII strategy game city defense.\n"
        "Primary request: One WWII anti-tank direct-fire artillery gun miniature model (PaK 40 / 57mm M1 anti-tank gun / 反坦克炮). "
        "Very long slender high-velocity gun barrel with a distinctive perforated double-baffle muzzle brake, "
        "aimed forward at a low direct-fire angle pointing left, dual-layered angled armored gun shield with sloped plates, "
        "low-slung compact carriage, rubber-tired spoked wheels, optical sight, olive drab and dunkelgrau military camouflage finish with steel edge wear. "
        "Low-profile deadly armor-piercing anti-tank cannon.\n"
        "Style/medium: premium museum-quality painted scale model artillery photographed as a real physical model, "
        "crisp sculpted metal, thin shield plates, realistic gun breech and trail, restrained field wear. "
        "Never cartoon, flat vector, toy caricature, badge or game UI.\n"
        "Composition/framing: square canvas, elevated three-quarter view from above at about 25 degrees, "
        "barrel pointing left, entire anti-tank gun fits within frame with at least 8 percent clean margin. Crisp readable silhouette.\n"
        "Scene/backdrop: perfectly uniform pure white #ffffff seamless background, isolated subject, no base, no plinth, no terrain, no scenery. "
        "No cast shadow on background; subtle contact shading only under tires.\n"
        "Lighting/mood: soft neutral studio daylight from upper left, realistic metallic highlights.\n"
        "Constraints: exactly one anti-tank gun. No soldiers, no muzzle blast, no modern gear, no text, no watermark, no flags."
    ),
    'flak': (
        "Use case: product-mockup\n"
        "Asset type: realistic miniature anti-aircraft gun icon for WWII strategy game city defense.\n"
        "Primary request: One WWII anti-aircraft artillery flak gun miniature model (88mm Flak / 40mm Bofors AA gun / 防空炮). "
        "Gun barrel pointing steeply high into the sky at a 65-degree anti-aircraft angle aimed towards upper-left, "
        "cruciform steel firing platform base with four stabilizing outriggers, high-angle elevation handwheels, "
        "detailed gunner seats, optical computing anti-aircraft gunsight, cylindrical recoil mechanism, "
        "perforated barrel jacket and flash hider, military olive drab and gunmetal finish.\n"
        "Style/medium: premium museum-quality painted scale miniature AA artillery photographed as a real physical model, "
        "crisp mechanical gears, outriggers, rivets, realistic machined metal. "
        "Never cartoon, flat vector, toy caricature, badge or game UI.\n"
        "Composition/framing: square canvas, elevated three-quarter view from above at about 25 degrees, "
        "gun barrel aiming steeply upward-left, entire flak gun from cruciform base to barrel tip fits in frame with at least 8 percent clean margin. Dynamic silhouette.\n"
        "Scene/backdrop: perfectly uniform pure white #ffffff seamless background, isolated subject, no base, no plinth, no terrain, no scenery. "
        "No cast shadow on background; subtle contact shading only under platform feet.\n"
        "Lighting/mood: soft neutral studio daylight from upper left, realistic metallic highlights.\n"
        "Constraints: exactly one anti-aircraft gun. No soldiers, no muzzle flash, no aircraft, no text, no watermark, no flags."
    )
}


def generate_weapon(weapon_id: str, prompt: str):
    target_path = RAW_DIR / f"{weapon_id}.png"
    prompt_file = OUTPUT_DIR / f"{weapon_id}.prompt.txt"
    prompt_file.write_text(prompt, encoding='utf-8')

    if target_path.exists() and target_path.stat().st_size > 10000:
        print(f"Skipping {weapon_id}, already exists: {target_path}")
        return

    print(f"Generating {weapon_id} via HANDAI API...")
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
        raise RuntimeError(f'Failed to generate {weapon_id}: {resp.status_code} {resp.text}')
    res_json = resp.json()
    if 'data' not in res_json or not res_json['data']:
        raise RuntimeError(f'No data in response for {weapon_id}: {res_json}')
    img_data = res_json['data'][0]

    if img_data.get('url'):
        img_resp = requests.get(img_data['url'], timeout=60)
        if img_resp.status_code != 200:
            raise RuntimeError(f'Failed to download {weapon_id}: {img_resp.status_code}')
        target_path.write_bytes(img_resp.content)
    elif img_data.get('b64_json'):
        target_path.write_bytes(base64.b64decode(img_data['b64_json']))
    else:
        raise RuntimeError(f'Unknown image response format: {img_data.keys()}')

    print(f"Successfully generated and saved {weapon_id} -> {target_path}")


def main():
    if not API_KEY:
        raise ValueError('HANDAI_API_KEY is not set')
    RAW_DIR.mkdir(parents=True, exist_ok=True)

    for weapon_id, prompt in PROMPTS.items():
        generate_weapon(weapon_id, prompt)
        time.sleep(1)

    print("All 4 fort weapon raw images generated successfully!")


if __name__ == '__main__':
    main()
