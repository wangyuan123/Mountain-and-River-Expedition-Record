#!/usr/bin/env python3
"""Create a small set of selectable, non-cartoon unit icon directions.

The files are intentionally kept under frontend/img/units/options until a
visual direction is selected.  Production mappings are not changed here.
"""
from pathlib import Path

OUT = Path(__file__).resolve().parents[2] / "frontend/img/units/options"
OUT.mkdir(parents=True, exist_ok=True)

STYLES = {
    "A": {
        "label": "A · 军用目录写实",
        "bg": "#11171d",
        "bg2": "#26323a",
        "rim": "#4b5961",
        "base": "#1d252b",
        "metal": "#aab1aa",
        "metal2": "#67736f",
        "olive": "#56634d",
        "olive2": "#303a30",
        "glass": "#8ea9b2",
        "warm": "#d3b476",
        "wake": "#b8c9cc",
    },
    "B": {
        "label": "B · 沙盘微缩模型",
        "bg": "#a69b86",
        "bg2": "#ded1b8",
        "rim": "#6b6254",
        "base": "#6f675a",
        "metal": "#b8b09d",
        "metal2": "#6c6a60",
        "olive": "#687050",
        "olive2": "#404638",
        "glass": "#6f898d",
        "warm": "#d9a963",
        "wake": "#f1e9d9",
    },
    "C": {
        "label": "C · 海军蓝图档案",
        "bg": "#081524",
        "bg2": "#163755",
        "rim": "#47728f",
        "base": "#10283a",
        "metal": "#b7c5c8",
        "metal2": "#5c7b8b",
        "olive": "#687b72",
        "olive2": "#334c4b",
        "glass": "#8ed0e3",
        "warm": "#e0b768",
        "wake": "#a7d7e5",
    },
}

DEFS = """<defs>
  <radialGradient id="bg" cx="42%" cy="34%" r="80%"><stop offset="0" stop-color="{bg2}"/><stop offset="1" stop-color="{bg}"/></radialGradient>
  <linearGradient id="metal" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="{metal}"/><stop offset="0.46" stop-color="{metal2}"/><stop offset="1" stop-color="#20282b"/></linearGradient>
  <linearGradient id="olive" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="{olive}"/><stop offset="1" stop-color="{olive2}"/></linearGradient>
  <linearGradient id="warm" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#f0d28b"/><stop offset="1" stop-color="{warm}"/></linearGradient>
  <filter id="shadow" x="-30%" y="-30%" width="160%" height="170%"><feGaussianBlur in="SourceAlpha" stdDeviation="2.3"/><feOffset dy="2"/><feComponentTransfer><feFuncA type="linear" slope="0.55"/></feComponentTransfer><feMerge><feMergeNode/><feMergeNode in="SourceGraphic"/></feMerge></filter>
  <filter id="soft"><feGaussianBlur stdDeviation="0.7"/></filter>
  <filter id="grain"><feTurbulence type="fractalNoise" baseFrequency="0.72" numOctaves="2" stitchTiles="stitch"/><feColorMatrix values="1 0 0 0 0  0 1 0 0 0  0 0 1 0 0  0 0 0 .07 0"/></filter>
</defs>"""


def esc_style(s, key):
    return s[key]


def start(style):
    s = STYLES[style]
    vals = {k: esc_style(s, k) for k in s if k != "label"}
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 128 128" width="128" height="128" role="img">
{DEFS.format(**vals)}
<rect width="128" height="128" rx="12" fill="url(#bg)"/>
<circle cx="64" cy="64" r="52" fill="none" stroke="{s['rim']}" stroke-opacity=".32" stroke-width="1"/>
<circle cx="64" cy="64" r="48" fill="none" stroke="#fff" stroke-opacity=".06" stroke-width="1"/>
<rect width="128" height="128" rx="12" filter="url(#grain)" opacity=".28"/>
'''


def end():
    return "</svg>\n"


def fighter(style):
    s = STYLES[style]
    return start(style) + f'''
<g filter="url(#shadow)" transform="rotate(-12 64 63)">
  <ellipse cx="64" cy="85" rx="42" ry="7" fill="#000" opacity=".35" filter="url(#soft)"/>
  <path d="M63 19 L68 38 L105 59 L102 66 L70 60 L65 84 L61 84 L57 60 L25 66 L22 59 L58 38 Z" fill="url(#metal)" stroke="#101519" stroke-width="1.3"/>
  <path d="M60 39 L64 21 L68 39 L66 74 L62 74 Z" fill="url(#olive)"/>
  <path d="M31 58 L57 53 L57 60 L28 64 Z M71 53 L98 58 L100 64 L71 60 Z" fill="{s['metal2']}"/>
  <path d="M60 35 L64 28 L68 35 L67 47 L61 47 Z" fill="{s['glass']}" stroke="#182127" stroke-width="1"/>
  <path d="M63 18 L64 13 L67 18 L66 27 L62 27 Z" fill="{s['olive2']}"/>
  <path d="M62 74 L64 88 L66 74" fill="none" stroke="{s['warm']}" stroke-width="1.1"/>
  <path d="M48 56 L54 56 M74 56 L80 56" stroke="#e3ded1" stroke-width="1" opacity=".7"/>
</g>
'''+end()


def bomber(style):
    s = STYLES[style]
    return start(style) + f'''
<g filter="url(#shadow)" transform="rotate(-8 64 64)">
  <ellipse cx="64" cy="89" rx="47" ry="7" fill="#000" opacity=".38" filter="url(#soft)"/>
  <path d="M61 17 L68 17 L72 44 L111 59 L109 67 L73 62 L70 88 L58 88 L55 62 L19 67 L17 59 L56 44 Z" fill="url(#metal)" stroke="#11181d" stroke-width="1.2"/>
  <path d="M60 19 L65 19 L68 85 L59 85 Z" fill="url(#olive)"/>
  <path d="M27 59 L55 52 L55 61 L23 65 Z M73 52 L101 59 L105 65 L73 61 Z" fill="{s['metal2']}"/>
  <path d="M55 47 L64 43 L73 47 L70 57 L58 57 Z" fill="{s['glass']}" stroke="#19262b" stroke-width="1"/>
  <g fill="#20282b" stroke="#b9b9aa" stroke-width=".8"><circle cx="41" cy="57" r="3.2"/><circle cx="87" cy="57" r="3.2"/><circle cx="47" cy="59" r="2.5"/><circle cx="81" cy="59" r="2.5"/></g>
  <path d="M61 85 L64 94 L67 85" fill="none" stroke="{s['warm']}" stroke-width="1.2"/>
</g>
'''+end()


def tank(style):
    s = STYLES[style]
    return start(style) + f'''
<g filter="url(#shadow)" transform="rotate(-9 64 65)">
  <ellipse cx="63" cy="91" rx="47" ry="8" fill="#000" opacity=".42" filter="url(#soft)"/>
  <path d="M22 52 L91 47 L107 62 L100 85 L28 88 L17 72 Z" fill="#202624" stroke="#111618" stroke-width="1.5"/>
  <path d="M27 54 L88 50 L99 62 L95 79 L29 82 L21 70 Z" fill="url(#olive)" stroke="#111618" stroke-width="1"/>
  <path d="M34 52 L50 51 L45 82 L30 82 Z M79 49 L91 51 L96 79 L82 80 Z" fill="#171d1d" opacity=".72"/>
  <path d="M43 56 L75 54 L87 65 L78 76 L45 77 L35 67 Z" fill="url(#olive)" stroke="#111618" stroke-width="1.2"/>
  <path d="M61 61 L72 59 L76 66 L69 72 L57 71 L53 66 Z" fill="url(#metal)" stroke="#202a27" stroke-width="1"/>
  <path d="M68 64 L111 56 L112 60 L73 70 Z" fill="url(#metal)" stroke="#202326" stroke-width="1"/>
  <path d="M37 59 L40 57 M84 55 L87 53 M40 78 L43 79 M84 77 L88 77" stroke="#d6c98e" stroke-width="1" opacity=".7"/>
  <g fill="#0f1415" stroke="#69736d" stroke-width="1"><circle cx="29" cy="67" r="6"/><circle cx="91" cy="65" r="6"/><circle cx="33" cy="79" r="5"/><circle cx="91" cy="77" r="5"/></g>
</g>
'''+end()


def battleship(style):
    s = STYLES[style]
    return start(style) + f'''
<g filter="url(#shadow)" transform="rotate(-8 64 65)">
  <ellipse cx="65" cy="87" rx="48" ry="9" fill="{s['wake']}" opacity=".32" filter="url(#soft)"/>
  <path d="M11 69 L25 54 L86 51 L112 69 L91 82 L33 82 Z" fill="url(#metal)" stroke="#111b20" stroke-width="1.4"/>
  <path d="M22 67 L88 63 L105 70 L89 77 L35 77 Z" fill="{s['base']}" opacity=".95"/>
  <path d="M46 55 L49 38 L63 36 L68 57 Z" fill="url(#olive)" stroke="#172127" stroke-width="1"/>
  <path d="M57 37 L60 25 L65 25 L66 38 Z" fill="{s['metal2']}" stroke="#14202a" stroke-width="1"/>
  <path d="M56 29 L56 20 M63 26 L63 16" stroke="{s['metal']}" stroke-width="1"/>
  <path d="M36 61 L43 58 L50 61 L43 65 Z M78 58 L87 60 L84 64 L75 62 Z" fill="url(#warm)" stroke="#1b2327" stroke-width=".8"/>
  <path d="M18 72 L7 78 M24 76 L12 84 M95 74 L118 81 M92 78 L110 88" stroke="{s['wake']}" stroke-width="1.5" opacity=".62"/>
  <path d="M49 42 L67 40 M49 46 L69 44" stroke="#d7d1bd" stroke-width=".8" opacity=".65"/>
</g>
'''+end()


UNITS = {"fighter": fighter, "bomber": bomber, "htank": tank, "battleship": battleship}

for style, cfg in STYLES.items():
    for unit, fn in UNITS.items():
        (OUT / f"{unit}-option-{style}.svg").write_text(fn(style), encoding="utf-8")

mapping = """# 兵种微缩模型图标选型稿\n\n"""
mapping += "本目录暂存 3 套风格 × 4 个代表兵种，生产映射尚未切换。\n\n"
for key, cfg in STYLES.items():
    mapping += f"- {cfg['label']}：`*-option-{key}.svg`\n"
(OUT / "README.md").write_text(mapping, encoding="utf-8")
print(f"wrote {len(STYLES) * len(UNITS)} SVG options to {OUT}")
