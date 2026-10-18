"""生成城墙 10 级满级专属至尊金碧微缩模型原图 (修复飞机机头变机翼的AI生成缺陷).
"""
import base64
import os
from pathlib import Path
import socket
import sys
import requests

orig_getaddrinfo = socket.getaddrinfo
def patched_getaddrinfo(host, port, *args, **kwargs):
    if host == 'handai-code.hand-china.com':
        return orig_getaddrinfo('116.228.77.181', port, *args, **kwargs)
    return orig_getaddrinfo(host, port, *args, **kwargs)

socket.getaddrinfo = patched_getaddrinfo

ROOT = Path(__file__).resolve().parents[2]
OUTPUT_DIR = ROOT / 'output/imagegen/player-city-tiered-20261017'
RAW_DIR = OUTPUT_DIR / 'raw'
API_URL = 'https://handai-code.hand-china.com/v1/images/generations'
API_KEY = os.environ.get('HANDAI_API_KEY')

PROMPT_LV10_FIXED = """Use case: stylized-concept.
Asset type: one single complete realistic 3D miniature player-city ultimate supreme citadel model asset for the WWII strategy game 山河远征录.
Tier: Supreme Maximum Level 10 Pinnacle Citadel (城墙10级满级至尊金碧帝国要塞大本营 · 终极王者之都).

LAYOUT CONSISTENCY WITH LEVEL 9:
- Identical core military-civic city layout:
  1. North-central axis: Monumental Supreme Command Imperial Citadel Palace with sweeping neoclassical colonnaded wings, ceremonial marble staircase, high central clock tower, and magnificent 24k gilded royal blue and gold dome topped with golden eagle crest.
  2. Central victory plaza in front of palace with ornamental tiered fountains and a monumental ROYAL SAPPHIRE BLUE AND GOLD winged victory goddess statue.
  3. East sector: Heavy strategic military airfield with reinforced concrete aircraft hangars, blue glass arched roofs with gold trims, flight operations tower, and parked heavy strategic bombers.
  4. Center & South sector: Vast paved assembly parade grounds densely arrayed with heavy battle tank battalions, self-propelled artillery, and Katyusha rocket trucks.
  5. Four corners: Massive elevated corner bastions equipped with rotating double-barrel heavy fortress artillery turrets.
  6. South gate: Monumental barbican gate fortress flanked by twin soaring bastion towers with armored steel portcullis gates and searchlights.

AIRFIELD & AIRCRAFT ACCURACY SPECIFICATIONS (STRICT):
- All parked aircraft on the airfield tarmac must have anatomically correct, realistic WWII multi-engine strategic bomber geometry:
  * Distinct, rounded fuselage nose cone and glass greenhouse cockpit canopy clearly pointing diagonally towards the lower-left (southwest) runway.
  * Symmetrical main wings mounted perpendicularly to the fuselage sides, with 4 forward-facing radial piston engines with spinning propellers.
  * Tall, upright vertical stabilizer and horizontal tailplanes at the rear empennage.
  * Clean landing gear wheels resting on the paved stone tarmac with realistic ground contact shadows.
  * ABSOLUTELY NO WINGS EXTENDING FROM THE NOSE, NO DEFORMED AIRPLANE HEADS, NO MUTATED FUSELAGES.

EXTREME VERTICAL HEIGHT (+50% TALLER WALLS & ARCHITECTURE):
- The fortress perimeter walls and central command palace are 50% TALLER than Level 9, reaching the supreme peak of vertical grandeur.
- Soaring monolithic cut-limestone and sloped reinforced concrete fortress walls rising to extraordinary heights with deep double-tiered patrol catwalks, monumental stepped buttresses, and crenellated battlements.
- The central Supreme Command Imperial Palace is an awe-inspiring 8-10 story masterpiece that pierces high into the skyline, towering like an impregnable golden mountain fortress.

RESPLENDENT OPULENCE, VIBRANT COLORS & ROYAL BLUE ACCENTS (金碧辉煌 · 丰富多彩 · 尊贵蓝饰):
- Resplendent golden glory (金碧辉煌): Gleaming 24k gilded golden dome, golden spires, polished brass balustrades, gilded cornices, and majestic golden eagle crests. Rich royal crimson-and-gold ceremonial war banners cascading down the gate towers.
- Royal Sapphire Blue Accents & Noble Statues (尊贵蓝色雕像与建筑点缀):
  * In the center of the grand Victory Plaza stands a breathtaking, monumental ROYAL SAPPHIRE BLUE AND GOLD winged victory statue (sculpted from polished royal blue lapis lazuli stone with gleaming gold-leaf wings and laurels), surrounded by blue-tiled tiered fountains.
  * The palace and grand towers feature elegant royal sapphire blue enameled pediments, cobalt blue dome ribbing accents, and royal blue ceremonial pennants with golden fringes, radiating supreme imperial majesty and noble sovereignty.
- Rich, colorful environment: Emerald manicured formal gardens, cypress trees, terracotta clay roofs, ivory marble colonnades, dark olive-drab tanks and aircraft, and clean grey stone paving.

FRONT GATE & FLAT HORIZONTAL BASE REQUIREMENTS:
- The southern defensive wall and ground base perimeter run strictly FLAT, STRAIGHT, and HORIZONTAL across the entire bottom.
- In front of the gate: A wide, formal, rectangular cobblestone ceremonial avenue and flat horizontal defensive apron featuring neat, parallel horizontal rows of concrete dragon's teeth (tetrahedral anti-tank pyramids) and steel Czech hedgehogs.
- ABSOLUTELY NO TRIANGULAR JUTTING OUT, NO POINTED ARROWHEAD RAMPS, NO ANGLED CHEVRONS. Clean horizontal base retaining wall from left to right.

LIGHTING & CAMERA:
- Brilliant, crystal-clear morning sunlight from upper-left with golden highlights and soft secondary ambient fill light. Every wall facade and corner tower is fully illuminated with clear cut-stone masonry texture, golden trims, and blue accents. NO PITCH-BLACK VOIDS OR UNDETAILED SHADOW MASSES.
- Elevated three-quarter isometric perspective (35-40 degrees above horizon, viewing from south towards north).
- Strictly SQUARE ground footprint aligned to cardinal directions, flat thin ground base with stone border, solid seamless pure white background (#ffffff) for clean transparency isolation. All-in-focus miniature model.
- NO floating island, NO water moat, NO modern glass skyscrapers, NO text.
"""


def generate_image(prompt: str, target_path: Path):
    if not API_KEY:
        raise ValueError('HANDAI_API_KEY is not set')
    headers = {
        'Authorization': f'Bearer {API_KEY}',
        'Content-Type': 'application/json'
    }
    payload = {
        'model': 'gpt-image-2',
        'prompt': prompt.strip(),
        'size': '1024x1024',
        'quality': 'high',
        'n': 1
    }
    print(f'Sending request to {API_URL} for {target_path.name}...')
    resp = requests.post(API_URL, headers=headers, json=payload, timeout=180)
    if resp.status_code != 200:
        raise RuntimeError(f'API error {resp.status_code}: {resp.text}')
    res_json = resp.json()
    if 'data' not in res_json or not res_json['data']:
        raise RuntimeError(f'No data in response: {res_json}')
    img_data = res_json['data'][0]
    if img_data.get('url'):
        r = requests.get(img_data['url'], timeout=60)
        target_path.write_bytes(r.content)
    elif img_data.get('b64_json'):
        target_path.write_bytes(base64.b64decode(img_data['b64_json']))
    else:
        raise RuntimeError(f'Unknown image format: {img_data.keys()}')
    print(f'Successfully saved {target_path} ({target_path.stat().st_size} bytes)')


def main():
    RAW_DIR.mkdir(parents=True, exist_ok=True)
    c_fixed = RAW_DIR / 'city_tall_lv10_supreme_fixed.png'

    print('Generating Lv 10 Supreme Fixed Candidate...')
    generate_image(PROMPT_LV10_FIXED, c_fixed)


if __name__ == '__main__':
    main()
