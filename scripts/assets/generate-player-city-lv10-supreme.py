"""生成城墙 10 级满级专属至尊金碧微缩模型原图 (采用 gpt-image-2)。
设计要点：
1. 城市内建筑布局与当前 9-10 级模型保持一致（北侧最高统帅帝国宫殿、中央胜利广场、东侧战备机场、中南部装甲操场、四角重型炮台、南面城门前庭）；
2. 更加金碧辉煌、色彩丰富：金色大穹顶与金箔雕花饰边、皇家红金色战役旗帜、翠绿园林、汉白玉柱廊；
3. 尊贵蓝色建筑与雕像点缀：中央广场矗立皇家蓝配金色的至尊胜利女神飞翼雕像与天青蓝喷泉水池，建筑群点缀尊贵皇家蓝琉璃/天青珐琅饰边与蓝金仪仗旗帜；
4. 城墙与建筑更加高大 50%，雄伟耸立，气势直破苍穹；
5. 城门前方严格保持平直横向地基，规整平行排列反坦克龙齿与拒马，底座平直无三角形突起，全景光照通透无黑块。
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

PROMPT_LV10_SUPREME = """Use case: stylized-concept.
Asset type: one single complete realistic 3D miniature player-city ultimate supreme citadel model asset for the WWII strategy game 山河远征录.
Tier: Supreme Maximum Level 10 Pinnacle Citadel (城墙10级满级至尊金碧帝国要塞大本营 · 终极王者之都).

LAYOUT CONSISTENCY WITH LEVEL 9:
- Identical core military-civic city layout:
  1. North-central axis: Monumental Supreme Command Imperial Citadel Palace with sweeping neoclassical colonnaded wings, ceremonial marble staircase, and high central clock tower.
  2. Central victory plaza in front of palace with ornamental fountains and monument.
  3. East sector: Heavy strategic military airfield with reinforced concrete aircraft hangars, flight operations tower, and parked multi-engine heavy strategic bombers.
  4. Center & South sector: Vast paved assembly parade grounds densely arrayed with heavy battle tank battalions, self-propelled artillery, and Katyusha rocket trucks.
  5. Four corners: Massive elevated corner bastions equipped with rotating double-barrel heavy fortress artillery turrets.
  6. South gate: Monumental barbican gate fortress flanked by twin soaring bastion towers with armored steel portcullis gates and searchlights.

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

LIGHTING & MATERIAL VISIBILITY:
- Brilliant, crystal-clear morning sunlight from upper-left with golden highlights and soft secondary ambient fill light. Every wall facade and corner tower is fully illuminated with clear cut-stone masonry texture, golden trims, and blue accents. NO PITCH-BLACK VOIDS OR UNDETAILED SHADOW MASSES.

CAMERA & FRAMING:
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
    c1 = RAW_DIR / 'city_tall_lv10_supreme_c1.png'
    c2 = RAW_DIR / 'city_tall_lv10_supreme_c2.png'

    print('Generating Lv 10 Supreme Candidate 1...')
    generate_image(PROMPT_LV10_SUPREME, c1)
    print('Generating Lv 10 Supreme Candidate 2...')
    generate_image(PROMPT_LV10_SUPREME, c2)


if __name__ == '__main__':
    main()
