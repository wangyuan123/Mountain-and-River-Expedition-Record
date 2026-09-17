"""Build three original SVG icon collections and their local review gallery."""
from pathlib import Path
import json
import html
import zipfile

ROOT = Path(__file__).resolve().parent
BUILDINGS = [
    ('command', '市政厅', '军事', '石砌门廊、中央钟塔，表现基地行政中枢'),
    ('house', '民居', '军事', '滇西坡屋顶与成组住屋，保留生活温度'),
    ('factory', '军工厂', '军事', '锯齿采光顶、烟囱与装配厂门'),
    ('lightfactory', '轻工厂', '军事', '低矮单跨车间与紧凑轻型坦克'),
    ('heavyfactory', '重工厂', '军事', '高大厂房、龙门吊与厚重履带'),
    ('airport', '机场', '军事', '拱形机库、跑道与螺旋桨飞机'),
    ('port', '港口', '军事', '栈桥、装卸吊臂与船体'),
    ('academy', '军校', '军事', '对称校舍、操场与展开的书本'),
    ('staff', '参谋部', '军事', '野战指挥帐篷与作战地图'),
    ('lab', '科研中心', '军事', '砖砌研究室与实验玻璃器皿'),
    ('radar', '雷达站', '军事', '早期矩形栅格天线与支撑塔'),
    ('wall', '围墙', '军事', '混凝土防御墙、射孔与岗哨'),
    ('apron', '停机坪', '军事', '开放硬化地坪、停机线与螺旋桨飞机'),
    ('liaison', '联络中心', '军事', '低层通信所、线式天线与无线电'),
    ('depot', '仓库', '军事', '双坡储备库、板条箱与装卸门'),
    ('transit', '运输站', '军事', '雨棚货台与四十年代军用卡车'),
    ('exchange', '交易所', '军事', '市集拱廊、布篷与秤盘'),
    ('farm', '农田', '资源', '成行水田、田埂与稻穗'),
    ('refinery', '炼钢厂', '资源', '高炉、热钢槽与工业烟囱'),
    ('oilfield', '石油基地', '资源', '游梁抽油机与圆形储油罐'),
    ('raremine', '稀矿厂', '资源', '坑口木支架、矿车与矿石'),
]
STYLES = [
    dict(id='a-diorama', code='A', name='滇缅微缩', tag='建筑感最强 · 推荐', desc='暖砂岩、军绿铁皮与红土。用统一的斜俯视建筑小景，表现滇缅公路沿线的前进基地。', color='#8b9d71'),
    dict(id='b-insignia', code='B', name='军需铜章', tag='复古厚重 · 小图标清晰', desc='深橄榄底、黄铜浮雕与军需章牌。以正面轮廓强化识别，更适合当前暖金色建筑卡片。', color='#d9b978'),
    dict(id='c-blueprint', code='C', name='战地蓝图', tag='克制精确 · 战术界面', desc='军用测绘蓝、骨白线稿与少量橙色标记。用设施平面和工程立面，营造作战室图纸的秩序感。', color='#8dc9cf'),
]

def path(d, fill='none', stroke=None, width=2, extra=''):
    return f'<path d="{d}" fill="{fill}"' + (f' stroke="{stroke}" stroke-width="{width}"' if stroke else '') + f' {extra}/>'

def rect(x, y, w, h, fill, rx=0, stroke=None, width=2):
    return f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" fill="{fill}"' + (f' stroke="{stroke}" stroke-width="{width}"' if stroke else '') + '/>'

def circle(x, y, r, fill, stroke=None, width=2):
    return f'<circle cx="{x}" cy="{y}" r="{r}" fill="{fill}"' + (f' stroke="{stroke}" stroke-width="{width}"' if stroke else '') + '/>'

def poly(points, fill, stroke=None, width=1.4):
    return f'<polygon points="{points}" fill="{fill}"' + (f' stroke="{stroke}" stroke-width="{width}"' if stroke else '') + '/>'

def line(d, color, width=2):
    return path(d, stroke=color, width=width)

def group(content, transform):
    return f'<g transform="{transform}">{content}</g>'

def plane(color, edge=None):
    return path('M0 -23 L4 -18 L5 -4 L23 6 L23 11 L5 6 L4 19 L11 24 L11 28 L0 24 L-11 28 L-11 24 L-4 19 L-5 6 L-23 11 L-23 6 L-5 -4 L-4 -18 Z', color, edge, 1.5)

def tank(x, y, scale=1, color='#7c8c5d'):
    content = rect(0, 14, 41, 13, '#293933', 6) + rect(3, 16, 35, 8, '#a8ac83', 4)
    content += ''.join(circle(i, 20, 2.5, '#37433b') for i in [9, 17, 25, 33])
    content += poly('3,14 9,7 32,7 38,14', color) + rect(15, 0, 16, 9, color, 2) + rect(28, 2, 20, 3, '#414d3b')
    return group(content, f'translate({x} {y}) scale({scale})')

def truck(x, y, scale=1):
    return group(rect(0, 0, 29, 19, '#929a69', 2) + path('M30 6 H40 L47 16 V25 H0 V19 H30 Z', '#526346') + rect(33, 9, 7, 8, '#c1d6cc') + circle(9, 25, 6, '#293832') + circle(38, 25, 6, '#293832') + circle(9, 25, 2, '#c4baa0') + circle(38, 25, 2, '#c4baa0'), f'translate({x} {y}) scale({scale})')

def crate(x, y, size=13):
    return rect(x, y, size, size, '#b29364', 1, '#735b3c', 1.5) + line(f'M{x+2} {y+2} L{x+size-2} {y+size-2} M{x+size-2} {y+2} L{x+2} {y+size-2}', '#dfc291', 1.5)

def iso_house(x=25, y=65, w=46, depth=24, height=28, roof='#66795c', wall='#d0bf99'):
    # Shared projection keeps every miniature at the same viewing angle.
    dx, dy = depth * .82, depth * -.42
    out = poly(f'{x},{y} {x+w},{y+12} {x+w},{y+12-height} {x},{y-height}', wall)
    out += poly(f'{x+w},{y+12} {x+w+dx},{y+12+dy} {x+w+dx},{y+12+dy-height} {x+w},{y+12-height}', '#8c8c72')
    out += poly(f'{x-3},{y-height} {x+w+1},{y-height+12} {x+w/2+dx/2},{y-height-13} {x+dx/2},{y-height-22}', roof)
    out += poly(f'{x+w+1},{y-height+12} {x+w+dx+3},{y-height+12+dy} {x+w/2+dx/2},{y-height-13}', '#415846')
    out += line(f'M{x-3} {y-height} L{x+w+1} {y-height+12} L{x+w+dx+3} {y-height+12+dy}', '#bec3a0', 1.5)
    for i in range(2):
        wx = x + 8 + i * (w - 18)
        out += poly(f'{wx},{y-height+8+i*7} {wx+7},{y-height+10+i*7} {wx+7},{y-height+18+i*7} {wx},{y-height+16+i*7}', '#435e59')
    return out

def iso(key):
    sand, olive, dark, cream = '#b99870', '#788864', '#3f5246', '#ded0ad'
    out = poly('9,86 55,60 119,84 74,112', '#736851') + poly('9,80 55,54 119,78 74,106', '#a69872')
    out += path('M17 81 L57 59 L109 79 L73 100 Z', '#bbad84')
    if key == 'command':
        out += iso_house(25, 72, 57, 21, 31, '#697c66', cream)
        out += rect(52, 30, 20, 49, '#d4c29b') + poly('52,30 62,16 72,30', '#586d56')
        out += circle(62, 40, 5, '#eee3c8') + line('M62 36 V40 H65', '#425346', 1.6)
        out += rect(58, 63, 9, 17, '#43564a') + line('M48 82 H81 M45 86 H85', cream, 3)
    elif key == 'house':
        out += iso_house(51, 55, 32, 20, 22, '#8d735e') + iso_house(21, 77, 44, 24, 25, '#687f69')
        out += rect(39, 62, 8, 18, '#506051') + path('M87 69 V84 M80 76 H95', stroke='#5a704b', width=5)
    elif key in ('factory', 'lightfactory', 'heavyfactory'):
        if key == 'factory':
            out += rect(32, 19, 10, 41, '#956b51') + rect(29, 17, 16, 5, cream)
            out += path('M20 75 V47 L39 35 V47 L59 37 V49 L80 39 V91 Z', '#bda985', '#6c6d53', 1.5)
            out += poly('80,39 108,27 108,77 80,91', '#778469')
            out += rect(58, 64, 17, 22, dark)
            out += path('M26 54 H34 V63 H26 Z M45 56 H53 V65 H45 Z M88 47 H101 V58 H88 Z', '#d9d3a1')
        elif key == 'lightfactory':
            out += iso_house(24, 67, 52, 29, 29, '#7f9272') + poly('47,55 68,60 68,81 47,76', dark)
            out += tank(45, 71, .9)
        else:
            out += iso_house(22, 67, 63, 28, 37, '#64766c')
            out += line('M28 84 V28 H103 V85 M24 28 H108', '#a88750', 7)
            out += line('M78 29 V52 Q78 60 85 56', dark, 2.5) + tank(44, 76, 1.1)
    elif key == 'airport':
        out += poly('23,86 77,52 99,62 47,98', '#737e71') + line('M39 89 L81 63', cream, 2)
        out += path('M22 67 V45 C22 21 61 21 61 47 V79 Z', '#9daa92')
        out += path('M29 67 V47 C29 29 53 31 53 48 V76 Z', dark)
        out += poly('61,47 86,33 86,65 61,79', '#657e69')
        out += group(plane('#e3d8b6', '#60715e'), 'translate(77 78) rotate(30) scale(.55)')
    elif key == 'port':
        out = poly('10,78 56,52 120,75 74,111', '#65918d') + line('M22 83 L62 60 M42 93 L100 64 M62 104 L111 76', '#a2c1ad', 2)
        out += poly('22,70 67,45 77,50 31,77', '#ae9c75') + poly('59,53 69,48 106,63 97,70', '#d0bb8f')
        out += line('M56 65 V25 L90 35 M56 25 L72 47 M87 35 V56', '#515f4c', 4)
        out += path('M53 84 L82 65 L103 73 L79 95 H67 Z', '#354f49') + poly('63,81 82,71 94,75 77,88', cream) + rect(75, 67, 10, 12, '#acb29b')
    elif key == 'academy':
        out += iso_house(20, 70, 65, 27, 27, '#7c8967', cream)
        out += line('M31 63 V77 M43 66 V80 M56 69 V83 M69 72 V86', '#eaddbb', 4)
        out += path('M48 86 Q58 81 65 87 Q74 82 86 89 V101 Q74 94 65 99 Q57 94 48 97 Z', '#efe0b5', '#797558', 1.5) + line('M65 87 V99', '#797558', 1.5)
    elif key == 'staff':
        out += poly('18,75 49,25 84,38 109,77 65,98', '#87906a') + poly('18,75 49,25 65,98', '#a6ad7f')
        out += poly('44,60 49,38 60,91 40,81', dark) + line('M49 25 V15 M49 17 L65 20 L49 24', '#d3b677', 2)
        out += poly('72,73 98,65 105,82 80,91', cream) + line('M80 79 L87 74 L92 80 L99 75', '#977049', 2.5)
    elif key == 'lab':
        out += iso_house(21, 71, 53, 26, 35, '#6c8582') + rect(34, 19, 8, 17, '#bbbd9d')
        out += path('M84 59 H94 M86 59 V73 L76 91 Q74 99 83 100 H98 Q107 98 103 91 L92 73 V59', '#c8ded1', '#405f57', 2.5)
        out += path('M81 88 H99 L104 96 H77 Z', '#79957b')
    elif key == 'radar':
        out += iso_house(30, 84, 39, 22, 19)
        out += line('M49 69 L61 28 L78 74 M53 57 H72 M57 44 H67 M53 57 L69 44', '#4b6658', 3)
        out += poly('30,23 77,12 92,36 43,49', '#aebcac', '#425b4d', 2)
        out += line('M35 31 L82 20 M39 40 L87 28 M43 21 L56 45 M56 18 L68 42 M69 15 L80 39', '#526b59', 2)
    elif key == 'wall':
        out += poly('19,81 19,53 84,74 84,100', '#bcbda0') + poly('84,74 109,59 109,87 84,100', '#7d8f7d')
        out += poly('19,53 42,42 109,59 84,74', '#d5cfb3')
        out += path('M27 61 L37 64 V68 L27 65 Z M48 68 L58 71 V75 L48 72 Z M68 74 L78 77 V81 L68 78 Z', dark)
        out += rect(75, 28, 23, 40, '#acb299') + poly('69,29 86,16 104,25 98,34', '#5f7560') + rect(80, 37, 12, 8, dark)
    elif key == 'apron':
        out += poly('15,77 59,51 115,76 74,102', '#889489') + line('M29 77 L62 58 L101 77 L74 93 Z M65 65 L66 85', '#e2d8b4', 2.5)
        out += group(plane('#e4d5af', '#4c6556'), 'translate(64 73) rotate(-28) scale(.87)')
        out += crate(96, 80, 10)
    elif key == 'liaison':
        out += iso_house(23, 76, 56, 23, 28)
        out += line('M33 48 V16 M93 64 V23 M33 18 Q60 35 93 25 M33 26 Q60 43 93 33', '#516452', 2.5)
        out += rect(72, 73, 30, 20, '#4c6152', 3) + circle(82, 83, 5, '#b5bd98') + line('M91 80 H98 M91 85 H98 M79 72 V61', '#dcd2a9', 2)
    elif key == 'depot':
        out += iso_house(22, 69, 60, 28, 31, '#7f8268') + poly('39,49 67,57 67,84 39,76', dark)
        out += line('M43 57 L63 63 M43 63 L63 69 M43 69 L63 75', '#a6ad8c', 2)
        out += crate(76, 75, 17) + crate(92, 82, 13) + crate(80, 61, 13)
    elif key == 'transit':
        out += poly('22,65 77,34 108,47 51,79', '#7b8d70') + line('M28 67 V87 M100 50 V75 M50 76 V96', dark, 4)
        out += poly('22,65 77,34 108,47 51,79', '#a6ad81') + crate(63, 57, 17) + truck(34, 73, 1)
    elif key == 'exchange':
        out += iso_house(25, 68, 53, 23, 26, '#8c775b')
        out += poly('20,58 79,73 87,58 29,43', '#d7be82') + line('M28 62 V85 M78 75 V96', dark, 3)
        out += line('M62 62 V89 M46 69 H80 M50 69 L44 79 H56 Z M76 69 L70 79 H82 Z', '#565e40', 2.5)
    elif key == 'farm':
        out += poly('14,77 51,56 108,78 73,101', '#72834f')
        out += line('M25 78 L59 60 M40 83 L74 65 M55 89 L90 71 M70 95 L104 77', '#d8c67d', 5)
        out += iso_house(62, 48, 25, 15, 14, '#8e7452')
        out += line('M37 76 V32 M37 45 L27 36 M37 53 L48 43 M37 62 L25 51', '#e0bd61', 3.5)
        out += path('M37 28 Q25 34 34 41 Q45 34 37 28 M25 33 Q18 44 30 46 Q31 36 25 33 M49 40 Q36 41 39 53 Q51 53 49 40 M23 49 Q17 61 31 61 Q32 53 23 49', '#e9ca78')
    elif key == 'refinery':
        out += rect(27, 18, 11, 57, '#927664') + rect(77, 27, 10, 43, '#8b7562')
        out += path('M46 29 H66 V46 L75 57 V87 H39 V57 L46 46 Z', '#72847a', '#40554e', 2)
        out += rect(44, 66, 26, 17, '#554c3b', 2) + rect(49, 69, 17, 11, '#f0aa58', 2)
        out += path('M60 78 L97 92 L85 102 L52 85 Z', '#d48b4b') + line('M38 35 H45 M70 55 H88 V74', '#b1b5a0', 4)
    elif key == 'oilfield':
        out += line('M26 90 L42 43 L60 87 M31 76 H54 M35 62 H49', '#586b54', 5)
        out += path('M20 38 L72 27 L77 34 L23 46 Z', '#687b5e') + path('M69 25 Q89 28 83 46 L73 49 L70 34 Z', '#ad9b65') + line('M79 45 V84', dark, 2)
        out += rect(83, 59, 27, 26, '#b4b89c', 3) + '<ellipse cx="96.5" cy="59" rx="13.5" ry="6" fill="#d4d1af"/>' + line('M87 71 H107', '#747f69', 2)
    elif key == 'raremine':
        out += path('M19 82 L28 44 L53 28 L78 43 L91 78 L65 95 Z', '#8d9280')
        out += path('M38 80 V52 Q54 36 70 54 V88 Z', '#354a42') + line('M33 81 V48 H74 V89 M31 49 L76 49', '#b9a276', 6)
        out += line('M42 84 L34 99 M65 87 L68 108', '#5d6655', 3)
        out += path('M72 73 L103 77 L99 93 L77 90 Z', '#657f76') + circle(81, 95, 4, dark) + circle(96, 97, 4, dark)
        out += poly('76,75 82,62 91,70 98,64 104,78', '#b7c7a7')
    return out

def emblem(key):
    gold, dark = '#e1c48a', '#2e3d35'
    r = lambda x,y,w,h: rect(x,y,w,h,gold,1)
    p = lambda d: path(d,gold)
    l = lambda d,w=3: line(d,gold,w)
    if key == 'command':
        return p('M23 57 L64 37 L105 57 Z') + r(29,60,10,31) + r(49,60,10,31) + r(69,60,10,31) + r(89,60,10,31) + r(23,94,82,6) + r(57,23,14,17) + circle(64,30,3,dark)
    if key == 'house':
        return p('M21 62 L43 41 L65 62 V94 H23 V65 Z M63 53 L84 32 L105 53 V84 H91 V60 H76 V80 H65 Z') + rect(38,72,13,22,dark,1) + rect(78,44,10,8,dark,1)
    if key in ('factory','lightfactory','heavyfactory'):
        if key == 'factory':
            return r(29,28,11,35) + p('M23 66 L47 49 V64 L72 49 V64 L103 49 V98 H23 Z') + rect(35,77,13,10,dark) + rect(58,77,13,10,dark) + rect(81,77,13,21,dark)
        factory = p('M23 57 L64 40 L105 57 V87 H94 V63 H34 V87 H23 Z')
        if key == 'heavyfactory':
            factory = l('M25 82 V32 H103 V82 M20 32 H108',7) + l('M80 34 V49 Q80 56 88 51',3)
        return factory + rect(34,80,62,17,gold,8) + rect(44,65,40,14,gold,2) + r(72,68,30,5) + ''.join(circle(x,88,4,dark) for x in (45,58,71,84))
    if key == 'airport':
        return path('M22 97 V60 A42 33 0 0 1 84 31 Q105 41 106 60 V97 H93 V62 A29 22 0 0 0 35 62 V97 Z',gold) + group(plane(gold),'translate(64 69) scale(.82)')
    if key == 'port':
        return l('M29 76 V32 H81 L92 45 M34 32 L58 56 M79 33 V57',6) + p('M35 82 H107 L94 97 H48 Z') + r(65,66,23,13) + l('M23 104 Q34 98 45 104 T67 104 T89 104 T109 104',3)
    if key == 'academy':
        return p('M22 54 L64 32 L106 54 Z') + r(29,59,10,22) + r(89,59,10,22) + path('M39 68 Q52 61 64 70 Q78 61 91 68 V97 Q78 90 64 99 Q51 90 39 97 Z',gold) + line('M64 70 V97',dark,3)
    if key == 'staff':
        return p('M18 88 L60 32 H69 L108 88 H80 L64 58 L47 88 Z') + rect(40,85,50,19,gold,2) + line('M48 96 L57 91 L69 98 L80 91',dark,3) + l('M64 31 V20 H81',3)
    if key == 'lab':
        return p('M22 96 V56 L46 39 L57 47 V60 H35 V96 Z M79 43 L105 56 V96 H94 V62 H79 Z') + path('M56 45 H74 M59 46 V70 L46 92 Q44 98 51 99 H79 Q85 97 81 91 L70 70 V46',stroke=gold,width=6) + l('M52 87 H76',4)
    if key == 'radar':
        return rect(26,27,76,37,'none',2,gold,5) + l('M40 28 V63 M56 28 V63 M72 28 V63 M88 28 V63 M28 45 H100',2.5) + l('M62 66 L44 99 M66 66 L84 99 M51 86 H77 M36 102 H92',5)
    if key == 'wall':
        return p('M22 98 V49 H36 V59 H48 V49 H61 V59 H72 V49 H85 V59 H99 V49 H107 V98 Z') + rect(32,72,12,6,dark) + rect(85,72,12,6,dark) + path('M56 98 V83 a10 10 0 0 1 20 0 V98 Z',dark) + r(24,30,22,14)
    if key == 'apron':
        return rect(24,28,80,74,'none',9,gold,4) + group(plane(gold),'translate(64 61) scale(1)') + line('M34 88 H44 M84 88 H94',gold,4)
    if key == 'liaison':
        return r(28,48,7,43) + r(94,30,7,60) + path('M31 31 Q64 56 97 31 M31 40 Q64 65 97 40',stroke=gold,width=3) + rect(40,67,48,32,gold,3) + circle(53,83,7,dark) + line('M68 77 H80 M68 86 H80',dark,3)
    if key == 'depot':
        return p('M20 51 L64 29 L108 51 V98 H96 V57 H32 V98 H20 Z') + rect(42,64,22,32,gold,1) + rect(68,78,21,18,gold,1) + line('M45 68 L61 90 M61 68 L45 90 M72 82 L85 92',dark,2)
    if key == 'transit':
        return l('M25 71 V39 H99 V55 M19 40 L64 24 L106 40',5) + r(28,61,43,28) + p('M75 69 H92 L104 81 V94 H75 Z') + rect(80,73,10,9,dark) + circle(42,96,8,gold) + circle(91,96,8,gold) + circle(42,96,3,dark) + circle(91,96,3,dark)
    if key == 'exchange':
        return p('M20 48 L32 32 H96 L108 48 Z') + r(25,53,7,44) + r(97,53,7,44) + l('M64 55 V96 M46 98 H82 M40 64 H88',4) + p('M42 65 L30 82 Q42 95 54 82 Z M86 65 L74 82 Q86 95 98 82 Z') + path('M42 72 L36 81 H48 Z M86 72 L80 81 H92 Z',dark)
    if key == 'farm':
        return l('M27 98 L94 79 M25 87 L87 69 M23 76 L49 68',5) + l('M66 74 V28',4) + path('M65 42 Q46 45 45 29 Q64 27 65 42 M69 53 Q86 53 88 37 Q70 36 69 53 M63 61 Q47 62 44 48 Q61 45 63 61 M66 28 Q55 18 65 14 Q78 20 66 28',gold)
    if key == 'refinery':
        return r(24,25,10,66) + r(91,37,10,54) + p('M48 29 H73 V53 L83 66 V98 H39 V66 L48 53 Z') + rect(50,72,23,21,dark,2) + path('M59 91 Q48 82 60 72 Q59 79 66 80 Q75 89 59 91',gold) + l('M34 49 H44 M78 62 H92',4)
    if key == 'oilfield':
        return l('M27 98 L48 46 L69 98 M37 77 H59',6) + path('M21 35 L82 23 L86 31 L24 44 Z M80 21 Q104 20 102 42 L86 49 Z',gold) + l('M94 46 V75',3) + rect(79,77,28,23,gold,4) + line('M82 85 H104',dark,2)
    if key == 'raremine':
        return path('M20 97 L29 49 L62 26 L96 48 L108 96 H94 L83 54 H43 L32 97 Z',gold) + l('M43 58 H83 M45 58 V86 M81 58 V75',4) + path('M48 80 H93 L87 98 H53 Z',gold) + circle(58,103,4,gold) + circle(82,103,4,gold) + p('M54 78 L62 64 L72 75 L81 67 L89 78 Z')
    raise KeyError(key)

def blueprint(key):
    ink, accent = '#d8e8da', '#e3ad63'
    l = lambda d,w=3: line(d,ink,w)
    box = lambda x,y,w,h: rect(x,y,w,h,'#213f49',2,ink,3)
    if key == 'command':
        return box(28,42,72,56) + box(49,23,30,73) + l('M30 62 H48 M80 62 H98 M30 80 H48 M80 80 H98 M60 78 V96 M69 78 V96') + circle(64,40,7,'none',accent,3)
    if key == 'house':
        return box(22,26,35,31) + box(71,42,35,31) + box(35,81,35,24) + l('M39 28 V54 M24 42 H55 M88 44 V70 M73 57 H104 M37 93 H68 M56 59 V70 H86 V91 H73',2.5)
    if key == 'factory':
        return path('M22 96 V51 L48 28 V51 L74 28 V51 L102 28 V96 Z',stroke=ink,width=3) + l('M24 64 H101 M39 64 V96 M64 64 V96 M88 64 V96',2) + line('M48 80 H76',accent,5)
    if key in ('lightfactory','heavyfactory'):
        out = box(25,29,78,72) + l('M26 46 H103 M45 30 V45 M81 30 V45',2)
        if key == 'heavyfactory':
            out += line('M21 22 H108 M32 20 V52 M98 20 V52 M67 23 V53',accent,4) + l('M67 50 Q77 61 82 51')
        else:
            out += line('M28 24 H49',accent,4)
        return out + rect(42,63,45,28,'none',7,ink,3) + box(51,67,26,18) + l('M65 66 V53',4)
    if key == 'airport':
        return box(21,28,42,28) + l('M23 35 H60 M23 43 H60',2) + path('M76 23 H101 V106 H76 Z',stroke=ink,width=3) + path('M88 30 V45 M88 86 V99',stroke=accent,width=3) + group(plane(ink),'translate(61 78) rotate(-28) scale(.62)')
    if key == 'port':
        return l('M24 24 V101 H42 V49 H59 V87 H72 V49 H91 V101 H104 V24 Z',3) + path('M49 62 L55 69 V93 L49 99 L44 93 V69 Z M83 66 L88 73 V91 L83 98 L77 91 V73 Z',accent) + l('M33 34 H95',2)
    if key == 'academy':
        return path('M23 27 H105 V101 H81 V53 H47 V101 H23 Z',stroke=ink,width=3) + rect(54,66,20,35,'none',1,accent,2.5) + l('M24 43 H104 M35 54 V91 M94 54 V91 M57 83 H71',2)
    if key == 'staff':
        return path('M24 96 V50 L64 25 L104 50 V96 Z M24 50 H104 M64 26 V53',stroke=ink,width=3) + path('M37 62 L53 59 L73 65 L90 60 V85 L74 91 L54 85 L37 90 Z',stroke=ink,width=2.5) + line('M46 77 L60 71 L72 80 L83 73',accent,3)
    if key == 'lab':
        return box(25,27,78,74) + l('M26 48 H102 M43 28 V47 M86 28 V47',2) + path('M53 59 H73 M57 60 V73 L47 90 H81 L70 73 V60',stroke=ink,width=3) + line('M52 84 H76',accent,4)
    if key == 'radar':
        return circle(64,64,39,'none',ink,2.5) + circle(64,64,25,'none',ink,2) + circle(64,64,10,'none',ink,2) + l('M64 19 V108 M19 64 H109',1.5) + path('M64 64 L89 34 A39 39 0 0 1 102 63 Z','#e3ad63',None,extra='opacity=".22"') + line('M64 64 L89 34',accent,3) + circle(86,78,4,accent)
    if key == 'wall':
        return path('M24 24 H104 V104 H24 Z M38 38 H90 V90 H76 M52 90 H38 V38',stroke=ink,width=3) + ''.join(box(x,y,19,19) for x,y in [(18,18),(91,18),(18,91),(91,91)]) + line('M55 100 H73',accent,4)
    if key == 'apron':
        return box(24,24,80,80) + l('M25 51 H45 M83 51 H104 M25 80 H45 M83 80 H104 M51 25 V43 M80 25 V43 M51 87 V103 M80 87 V103',2) + group(plane(ink),'translate(65 61) scale(.8)') + line('M31 94 H42',accent,3)
    if key == 'liaison':
        return box(26,59,76,43) + circle(45,81,9,'none',ink,3) + l('M64 73 H91 M64 84 H82 M81 61 V24',3) + path('M91 24 Q110 37 92 51 M68 24 Q49 37 67 51',stroke=accent,width=3) + circle(81,38,4,ink)
    if key == 'depot':
        return box(26,27,76,75) + l('M27 45 H101 M27 87 H101',2) + ''.join(box(x,y,15,17) for x,y in [(35,55),(57,55),(79,55)]) + line('M52 98 H76',accent,4)
    if key == 'transit':
        return l('M24 30 H103 V57 H24 Z M24 39 H103 M37 31 V56 M65 31 V56 M91 31 V56',2.5) + path('M23 72 H74 V96 H23 Z M74 80 H96 L105 89 V99 H74 Z',stroke=ink,width=3) + circle(39,101,6,'#213f49',ink,3) + circle(91,101,6,'#213f49',ink,3) + line('M28 65 H64',accent,3)
    if key == 'exchange':
        return box(23,27,26,26) + box(79,75,26,26) + l('M57 38 H96 V64 M96 64 L87 55 M96 64 L105 55 M71 90 H32 V66 M32 66 L23 75 M32 66 L41 75',3) + line('M29 40 H43 M92 81 V95',accent,3)
    if key == 'farm':
        return path('M23 31 L97 23 L106 97 L31 105 Z',stroke=ink,width=3) + l('M26 53 L99 45 M28 76 L102 68 M48 29 L56 102 M73 27 L82 99',2) + line('M38 41 L86 36 M44 88 L93 82',accent,3)
    if key == 'refinery':
        return l('M23 99 V29 H36 V70 H44 M93 99 V42 H105 V99',3) + path('M53 27 H77 V51 L85 63 V100 H45 V63 L53 51 Z',stroke=ink,width=3) + l('M45 63 H85 M54 28 V40 H75 M54 75 H76',2) + line('M56 90 H75',accent,5)
    if key == 'oilfield':
        return circle(90,86,17,'none',ink,3) + circle(90,86,10,'none',ink,2) + l('M24 101 L46 44 L68 101 M32 82 H61 M35 68 H55 M20 34 L80 23 L86 33 L24 44 Z M84 34 V66',3) + line('M24 105 H67',accent,4)
    if key == 'raremine':
        return path('M22 95 V38 H99 V95 M30 38 L41 26 H83 L94 38 M41 40 V80 M82 40 V80 M47 82 L36 105 M75 82 L87 105 M42 95 H81',stroke=ink,width=3) + path('M44 69 H82 L77 85 H49 Z',stroke=ink,width=3) + poly('51,67 62,48 76,67','none',accent,3)
    raise KeyError(key)

def svg(style, key, name):
    if style == 'a-diorama':
        content = iso(key)
    elif style == 'b-insignia':
        content = '<defs><linearGradient id="bg" x2="0" y2="1"><stop stop-color="#4c5a45"/><stop offset="1" stop-color="#283a33"/></linearGradient></defs>'
        content += path('M24 8 H104 L120 24 V94 L101 117 H27 L8 94 V24 Z','url(#bg)','#b79b62',3)
        content += path('M27 15 H101 L113 28 V92 L97 109 H31 L15 92 V28 Z','none','#778066',1)
        content += group(emblem(key),'translate(10 9) scale(.84)')
        content += circle(20,25,2,'#d7ba80') + circle(108,25,2,'#d7ba80')
    else:
        content = rect(5,5,118,118,'#213f49',17,'#4c747a',1.5)
        content += path('M32 12 V116 M64 12 V116 M96 12 V116 M12 32 H116 M12 64 H116 M12 96 H116',stroke='#3c5b61',width=.65)
        content += blueprint(key)
        content += line('M12 22 V12 H22 M106 116 H116 V106','#94b7ae',1.5)
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 128 128" width="128" height="128" role="img"><title>{html.escape(name)}</title><g stroke-linejoin="round" stroke-linecap="round">{content}</g></svg>'

def main():
    for style in STYLES:
        directory = ROOT / style['id']
        directory.mkdir(exist_ok=True)
        for key, name, _, _ in BUILDINGS:
            (directory / f'{key}.svg').write_text(svg(style['id'], key, name), encoding='utf-8')
    manifest = dict(styles=STYLES, buildings=[dict(id=k,name=n,group=g,idea=d) for k,n,g,d in BUILDINGS])
    (ROOT/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    template = (ROOT/'gallery-template.html').read_text(encoding='utf-8')
    (ROOT/'index.html').write_text(template.replace('__MANIFEST__',json.dumps(manifest,ensure_ascii=False)),encoding='utf-8')
    with zipfile.ZipFile(ROOT/'building-icons-3-sets.zip','w',zipfile.ZIP_DEFLATED) as archive:
        for style in STYLES:
            for file in sorted((ROOT/style['id']).glob('*.svg')):
                archive.write(file,file.relative_to(ROOT))
        for name in ['manifest.json','index.html','README.md','preview-a.png','preview-b.png','preview-c.png']:
            if (ROOT/name).exists():
                archive.write(ROOT/name,name)
    print('Generated 63 SVGs, manifest, review gallery, and ZIP.')

if __name__ == '__main__':
    main()
