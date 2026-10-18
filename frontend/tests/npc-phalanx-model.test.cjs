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

  // 兼容老路径
  const legacyEmbeddedPng = path.join(root, 'img/map/npc-fortress-map-embedded.png');
  assert.ok(fs.existsSync(legacyEmbeddedPng), 'npc-fortress-map-embedded.png 必须存在并更新');
  const fortressWebp = path.join(root, 'img/map/npc-fortress.webp');
  assert.ok(fs.existsSync(fortressWebp), 'npc-fortress.webp 必须存在并更新');

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

  assert.ok(worldMapJs.includes('v=5.7-npc-phalanxes'), 'world-map.js 纹理请求必须包含 v=5.7-npc-phalanxes 防缓存版本号');

  const indexHtml = fs.readFileSync(path.join(__dirname, '../index.html'), 'utf8');
  assert.ok(indexHtml.includes('world-map.js?v=5.23-npc-phalanxes'), 'index.html 中 world-map.js 必须引入最新版本防缓存参数');
});
