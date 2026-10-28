const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

test('NPC 步坦协同及多兵种方阵写实微缩模型资源完备且尺寸规范', () => {
  const root = path.resolve(__dirname, '..');

  const models = [
    'npc-fortress-orthogonal',
    'npc-phalanx-infantry',
    'npc-phalanx-light-armor',
    'npc-phalanx-aircraft',
    'npc-phalanx-mixed'
  ];

  models.forEach(m => {
    const webpPath = path.join(root, `img/map/${m}.webp`);
    assert.ok(fs.existsSync(webpPath), `${m}.webp 必须存在于地图模型目录中`);
    const stat = fs.statSync(webpPath);
    assert.ok(stat.size > 20000, `${m}.webp 文件体积应大于 20KB，实际 ${stat.size} 字节`);

    const embeddedPng = path.join(root, `img/map/${m}-map-embedded.png`);
    assert.ok(fs.existsSync(embeddedPng), `${m}-map-embedded.png 必须存在`);
    assert.ok(fs.statSync(embeddedPng).size > 20000, `${m}-map-embedded.png 体积需大于 20KB`);
  });

  // 验证 5 款方阵全家福大地图实装展示总览表
  const showcaseSheet = path.resolve(__dirname, '../../output/imagegen/npc-phalanx-20261011/npc_all_phalanxes_showcase.png');
  assert.ok(fs.existsSync(showcaseSheet), '5 款 NPC 方阵大地图实装展示总览长图必须存在');
  assert.ok(fs.statSync(showcaseSheet).size > 200000, '总览长图体积应大于 200KB');
});

test('world-map.js 中 NPC/流寇目标正确根据等级映射到多样化方阵模型', () => {
  const context = {
    console,
    Date,
    Map,
    Set,
    WeakMap,
    Uint8Array,
    Game: {
      MapChunks: function () {},
      DATA: {
        wildTypes: {}
      }
    },
    document: {
      createElement: () => ({
        getContext: () => ({
          drawImage() {},
          getImageData: () => ({ data: [] })
        })
      })
    }
  };
  context.window = context;
  vm.createContext(context);

  require('./load-constants.cjs')(context);

  const worldMapJs = fs.readFileSync(path.join(__dirname, '../js/world-map.js'), 'utf8');
  vm.runInContext(worldMapJs, context);

  const G = context.Game;
  assert.ok(G.WorldMap, 'Game.WorldMap 必须已定义');
  assert.equal(typeof G.WorldMap.icon, 'function', 'WorldMap.icon 必须是函数');

  // 默认无等级回退
  const defaultNpcIcon = G.WorldMap.icon({ kind: 'npc', x: 10, y: 10 });
  assert.equal(defaultNpcIcon, 'img/map/npc-fortress-orthogonal.webp', '无等级 NPC 流寇据点默认回退到基准中坦突击方阵');

  const defaultBanditIcon = G.WorldMap.icon({ kind: 'bandit', x: 20, y: 20 });
  assert.equal(defaultBanditIcon, 'img/map/npc-fortress-orthogonal.webp', '无等级日寇流寇默认回退到基准中坦突击方阵');

  // 根据等级分发各阶段专属方阵
  // 1) Lv.1~5 -> 步兵方阵
  assert.equal(G.WorldMap.icon({ kind: 'bandit', level: 1 }), 'img/map/npc-phalanx-infantry.webp');
  assert.equal(G.WorldMap.icon({ kind: 'bandit', level: 5 }), 'img/map/npc-phalanx-infantry.webp');

  // 2) Lv.6~8 -> 轻装甲方阵
  assert.equal(G.WorldMap.icon({ kind: 'bandit', level: 6 }), 'img/map/npc-phalanx-light-armor.webp');
  assert.equal(G.WorldMap.icon({ kind: 'bandit', level: 8 }), 'img/map/npc-phalanx-light-armor.webp');

  // 3) Lv.9~10 -> 双车中坦突击方阵
  assert.equal(G.WorldMap.icon({ kind: 'bandit', level: 9 }), 'img/map/npc-fortress-orthogonal.webp');
  assert.equal(G.WorldMap.icon({ kind: 'bandit', level: 10 }), 'img/map/npc-fortress-orthogonal.webp');

  // 4) Lv.11~20 -> 飞机方阵
  assert.equal(G.WorldMap.icon({ kind: 'bandit', level: 11 }), 'img/map/npc-phalanx-aircraft.webp');
  assert.equal(G.WorldMap.icon({ kind: 'bandit', level: 20 }), 'img/map/npc-phalanx-aircraft.webp');

  // 5) Lv.21~30 -> 混合编队
  assert.equal(G.WorldMap.icon({ kind: 'bandit', level: 21 }), 'img/map/npc-phalanx-mixed.webp');
  assert.equal(G.WorldMap.icon({ kind: 'bandit', level: 30 }), 'img/map/npc-phalanx-mixed.webp');

  assert.ok(worldMapJs.includes('v=5.9-npc-phalanxes-transparent-embedded'), 'world-map.js 纹理请求必须包含 v=5.9-npc-phalanxes-transparent-embedded 防缓存版本号');

  const indexHtml = fs.readFileSync(path.join(__dirname, '../index.html'), 'utf8');
  assert.match(indexHtml, /world-map\.js\?v=[^"&\s]+/, 'index.html 中 world-map.js 必须带防缓存版本参数');
});

test('5 款 NPC 寇微缩模型为规范 1:1 纯透明微缩方阵图标且尺寸规范', () => {
  const root = path.resolve(__dirname, '..');
  const models = [
    'npc-fortress-orthogonal',
    'npc-phalanx-infantry',
    'npc-phalanx-light-armor',
    'npc-phalanx-aircraft',
    'npc-phalanx-mixed'
  ];

  models.forEach(m => {
    const pngPath = path.join(root, `img/map/${m}-map-embedded.png`);
    const buf = fs.readFileSync(pngPath);
    // 验证必须是有效的 PNG 文件
    assert.equal(buf.subarray(0, 8).toString('hex'), '89504e470d0a1a0a', `${m} 必须是有效 PNG 格式`);
    assert.ok(buf.length > 20000, `${m} 必须大于 20KB`);

    // 验证 PNG 规范尺寸为 384x384 (IHDR 块从 offset 16 开始，宽 4 字节，高 4 字节)
    const width = buf.readUInt32BE(16);
    const height = buf.readUInt32BE(20);
    assert.equal(width, 384, `${m} 宽度必须为 384px 规范正方形`);
    assert.equal(height, 384, `${m} 高度必须为 384px 规范正方形`);
  });
});

test('根据 NPC 随机编队规则生成的 13 种兵种组合微缩模型完备且正确映射', () => {
  const root = path.resolve(__dirname, '..');
  const all13Combinations = [
    'npc-phalanx-infantry',
    'npc-phalanx-armored-infantry',
    'npc-phalanx-armored-infantry-ltank',
    'npc-phalanx-armored-fighter-infantry',
    'npc-phalanx-armored-fighter-infantry-ltank',
    'npc-phalanx-armored-bomber-fighter-infantry',
    'npc-phalanx-armored-bomber-infantry-ltank',
    'npc-phalanx-bomber-fighter',
    'npc-phalanx-ltank-rocket',
    'npc-phalanx-armored-fighter-htank-infantry',
    'npc-phalanx-fighter-infantry-special',
    'npc-phalanx-armored-bomber-ltank',
    'npc-phalanx-armored-htank-rocket'
  ];

  all13Combinations.forEach(m => {
    const webpPath = path.join(root, `img/map/${m}.webp`);
    assert.ok(fs.existsSync(webpPath), `${m}.webp 必须存在`);
    assert.ok(fs.statSync(webpPath).size > 20000, `${m}.webp 文件体积应大于 20KB`);

    const pngPath = path.join(root, `img/map/${m}-map-embedded.png`);
    assert.ok(fs.existsSync(pngPath), `${m}-map-embedded.png 必须存在`);
    const buf = fs.readFileSync(pngPath);
    assert.equal(buf.readUInt32BE(16), 384, `${m} 宽度必须为 384px`);
    assert.equal(buf.readUInt32BE(20), 384, `${m} 高度必须为 384px`);
  });

  // 测试在 world-map.js 中根据坐标随机种子与实际守军生成精准映射
  const context = {
    console, Date, Map, Set, WeakMap, Uint8Array,
    Game: { MapChunks: function () {}, DATA: { wildTypes: {} } },
    document: { createElement: () => ({ getContext: () => ({ drawImage() {}, getImageData: () => ({ data: [] }) }) }) }
  };
  context.window = context;
  vm.createContext(context);
  require('./load-constants.cjs')(context);
  const worldMapJs = fs.readFileSync(path.join(__dirname, '../js/world-map.js'), 'utf8');
  vm.runInContext(worldMapJs, context);
  const G = context.Game;

  // 1. 根据 t.army 显式配兵映射
  assert.equal(
    G.WorldMap.icon({ kind: 'bandit', level: 25, army: { ltank: 80, rocket: 20 } }),
    'img/map/npc-phalanx-ltank-rocket.webp'
  );
  assert.equal(
    G.WorldMap.icon({ kind: 'bandit', level: 30, army: { fighter: 60, bomber: 40 } }),
    'img/map/npc-phalanx-bomber-fighter.webp'
  );

  // 2. 根据坐标种子与等级映射
  // seed = |x * 31 + y|
  // x=10, y=0 -> seed=310; level=25 (highChoices length 6, 310 % 6 = 4 -> armored+bomber+ltank)
  assert.equal(
    G.WorldMap.icon({ kind: 'bandit', level: 25, x: 10, y: 0 }),
    'img/map/npc-phalanx-armored-bomber-ltank.webp'
  );
  // x=10, y=1 -> seed=311; 311 % 6 = 5 -> armored+htank+rocket
  assert.equal(
    G.WorldMap.icon({ kind: 'bandit', level: 25, x: 10, y: 1 }),
    'img/map/npc-phalanx-armored-htank-rocket.webp'
  );
  // x=10, y=2 -> seed=312; 312 % 6 = 0 -> bomber+fighter
  assert.equal(
    G.WorldMap.icon({ kind: 'bandit', level: 25, x: 10, y: 2 }),
    'img/map/npc-phalanx-bomber-fighter.webp'
  );
});


