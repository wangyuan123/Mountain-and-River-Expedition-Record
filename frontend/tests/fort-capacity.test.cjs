const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('./server-gameplay.cjs').vm;

test('城防页面按实际数据展示四类工事共享容量，升级不改变库存', () => {
  const state = { buildings: { wall: 1 }, forts: { bunker: 1000, antitank: 500, howitzer: 250, flak: 250 } };
  const context = vm.createContext({
    window: { Game: {
      Core: { state, views: {}, wallMul: () => 1 + 0.05 * ((state.tech || {}).wall_reinforcement || 0) },
      resourceIconHtml: id => id,
      fmt: String,
      escapeHtml: text => text
    } }
  });
  for (const name of ['data.js', 'fort.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '..', 'js', name), 'utf8'), context, { filename: name });
  }
  const G = context.window.Game;
  for (const [level, capacity, hp, defense] of [[1, 3000, 250000, 20], [5, 15000, 1250000, 100], [10, 30000, 2500000, 200]]) {
    state.buildings.wall = level;
    Object.assign(state.gameplay, {fortCap: capacity, wall: {maxHp: hp, defense}});
    const view = { innerHTML: '' };
    G.Fort.renderView(view);
    assert.match(view.innerHTML, new RegExp('四类城防武器共享容量 2000/' + capacity));
    assert.match(view.innerHTML, /每级\+3000/);
    assert.match(view.innerHTML, new RegExp("墙体生命 " + hp));
    assert.match(view.innerHTML, new RegExp("墙体防御 " + defense));
    assert.match(view.innerHTML, /仅保护墙体/);
    for (const type of ['bunker', 'antitank', 'howitzer', 'flak']) {
      assert.match(view.innerHTML, new RegExp(G.DATA.forts[type].name));
    }
  }
  state.tech = { wall_reinforcement: 10 };
  state.gameplay.wall = {maxHp:3750000, defense:300};
  const reinforced = { innerHTML: '' };
  G.Fort.renderView(reinforced);
  assert.match(reinforced.innerHTML, /墙体生命 3750000/);
  assert.match(reinforced.innerHTML, /墙体防御 300/);
  assert.match(reinforced.innerHTML, /共享容量 2000\/30000/);
  assert.deepEqual(state.forts, { bunker: 1000, antitank: 500, howitzer: 250, flak: 250 });
});
