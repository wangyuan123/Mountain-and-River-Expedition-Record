const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

test('野地页使用服务端本城名额，参谋部等级不会被重复相加', () => {
  const state = {
    buildings: { command: 10, staff: 10 },
    wilds: { commandLevel: 10, cap: 15, owned: [] },
    progress: {}
  };
  const context = vm.createContext({ Game: { DATA: {}, Core: { state, views: {} } } });
  context.window = context;
  require('./load-constants.cjs')(context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/map.js'), 'utf8'), context);
  const view = { innerHTML: '' };
  context.Game.Wild.renderView(view);
  assert.match(view.innerHTML, /本城市政厅 Lv\.10/);
  assert.match(view.innerHTML, /野地上限 0\/15/);
  assert.doesNotMatch(view.innerHTML, /参谋部|0\/25/);
  state.buildings.command = 2;
  state.wilds = { commandLevel: 2, cap: 7, owned: [] };
  context.Game.Wild.renderView(view);
  assert.match(view.innerHTML, /野地上限 0\/7/);
});
