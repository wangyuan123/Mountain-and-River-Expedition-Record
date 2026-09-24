const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup() {
  const calls = [];
  const saved = {
    outgoing: { infantry: 'ADVANCE', rocket: 'HOLD' },
    defending: { infantry: 'HOLD', rocket: 'RETREAT' }
  };
  const nodes = {};
  const Game = {
    Core: { route: 'battleDefaults', state: { player: { id: 10 } }, views: {}, render() {} },
    DATA: { units: { infantry: { name: '步兵' }, rocket: { name: '火箭' } } },
    API: {
      getBattleDefaults() { calls.push('load'); return Promise.resolve(saved); },
      saveBattleDefaults(value) { calls.push(value); return Promise.resolve(value); }
    },
    escapeHtml: String
  };
  const context = vm.createContext({ window: { Game }, document: { getElementById(id) { return nodes[id] || null; } } });
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/battle-defaults.js'), 'utf8'), context);
  return { Game, calls, nodes };
}

test('顶部军队后展示独立默认战术入口', () => {
  const source = fs.readFileSync(path.join(__dirname, '../js/main-view.js'), 'utf8');
  assert.match(source, /route: 'army' \},\s*\{ key: '[^']+', label: '(?:默认)?战术', route: 'battleDefaults'/);
});

test('分别编辑攻守兵种，只在保存时提交两套完整预设', async () => {
  const { Game, calls, nodes } = setup();
  const view = { innerHTML: '' };
  Game.Core.views.battleDefaults(view);
  await Promise.resolve();
  Game.Core.views.battleDefaults(view);
  assert.match(view.innerHTML, /出城战斗/);
  assert.match(view.innerHTML, /守城战斗/);
  assert.match(view.innerHTML, /保存默认战术/);
  nodes['battle-defaults-message'] = { textContent: '' };
  nodes['battle-defaults-save'] = { disabled: false };
  Game.BattleDefaults.setAction('outgoing', 'rocket', 'ADVANCE');
  assert.equal(nodes['battle-defaults-message'].textContent, '有未保存的修改');
  await Game.BattleDefaults.save();
  assert.equal(calls.length, 2);
  assert.equal(calls[1].outgoing.rocket, 'ADVANCE');
  assert.equal(calls[1].defending.rocket, 'RETREAT');
});
