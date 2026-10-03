const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup() {
  const calls = [];
  const saved = {
    outgoing: { infantry: 'ADVANCE', rocket: 'HOLD' },
    defending: { infantry: 'HOLD', rocket: 'RETREAT' },
    sortieArmy: null, sortieCap: 37500, locked: false
  };
  const nodes = {};
  for (const id of ['message', 'sortie-total', 'sortie-remaining', 'sortie-cap']) {
    nodes['battle-defaults-' + id] = { textContent: '' };
  }
  for (const unitId of ['infantry', 'rocket']) {
    nodes['battle-defaults-sortie-limit-' + unitId] = { textContent: '' };
    for (const type of ['range', 'number']) {
      nodes['battle-defaults-sortie-' + type + '-' + unitId] = {
        value: '0', max: '0', disabled: false,
        style: { setProperty(name, value) { this[name] = value; } }
      };
    }
  }
  const Game = {
    Core: { route: 'battleDefaults', state: { player: { id: 10 }, army: { infantry: 50000, rocket: 50000 } }, views: {}, render() {} },
    DATA: { units: { infantry: { name: '步兵' }, rocket: { name: '火箭' } } },
    API: {
      getBattleDefaults() { calls.push('load'); return Promise.resolve(JSON.parse(JSON.stringify(saved))); },
      saveBattleDefaults(value) { calls.push(JSON.parse(JSON.stringify(value))); return Promise.resolve(value); }
    },
    escapeHtml: String
  };
  const context = vm.createContext({ window: { Game }, document: { getElementById(id) { return nodes[id] || null; } } });
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/battle-defaults.js'), 'utf8'), context);
  return { Game, calls, nodes, saved };
}

test('战斗期间禁用战术表单并拒绝本地编辑和保存', async () => {
  const { Game, calls, saved, nodes } = setup();
  saved.locked = true;
  const view = { innerHTML: '' };
  Game.Core.views.battleDefaults(view);
  await Promise.resolve();
  Game.Core.views.battleDefaults(view);
  assert.match(view.innerHTML, /战斗或进攻行军期间无法修改默认战术/);
  assert.match(view.innerHTML, /id="battle-defaults-save"[^>]*disabled/);
  assert.match(view.innerHTML, /class="battle-defaults-trigger"[^>]*disabled/);
  Game.BattleDefaults.setAction('outgoing', 'infantry', 'RETREAT');
  Game.BattleDefaults.setAutomaticSortie(false);
  Game.BattleDefaults.setSortieCount('infantry', 100);
  await Game.BattleDefaults.save();
  assert.equal(calls.length, 1);
  assert.equal(nodes['battle-defaults-message'].textContent, '战斗或进攻行军期间无法修改默认战术');
});

test('页面打开后新增进攻行军也立即禁止编辑和保存', async () => {
  const { Game, calls } = setup();
  const view = { innerHTML: '' };
  Game.Core.views.battleDefaults(view);
  await Promise.resolve();
  Game.Core.state.world = { marches: [{ action: 'conquer', returning: false }] };
  Game.Core.views.battleDefaults(view);
  assert.match(view.innerHTML, /id="battle-defaults-save"[^>]*disabled/);
  Game.BattleDefaults.setAction('outgoing', 'infantry', 'RETREAT');
  await Game.BattleDefaults.save();
  assert.equal(calls.length, 1);
});

test('迎战按兵种编辑并随战术保存，自动与零兵力配置可区分', async () => {
  const { Game, calls, nodes } = setup();
  const view = { innerHTML: '' };
  Game.Core.views.battleDefaults(view);
  await Promise.resolve();
  Game.Core.views.battleDefaults(view);
  assert.match(view.innerHTML, /出城迎战兵力/);
  assert.match(view.innerHTML, /37500/);
  assert.match(view.innerHTML, /disabled placeholder="自动"/);
  assert.match(view.innerHTML, /type="range"[^>]*aria-label="步兵迎战数量滑动条" disabled/);
  nodes['battle-defaults-message'] = { textContent: '' };
  nodes['battle-defaults-sortie-total'] = { textContent: '' };
  Game.BattleDefaults.setAutomaticSortie(false);
  Game.BattleDefaults.setSortieCount('infantry', '25000');
  Game.BattleDefaults.setSortieCount('rocket', '12500');
  assert.equal(nodes['battle-defaults-sortie-total'].textContent, '37500');
  await Game.BattleDefaults.save();
  assert.deepEqual(JSON.parse(JSON.stringify(calls.at(-1).sortieArmy)), { infantry: 25000, rocket: 12500 });
  assert.equal(calls.at(-1).defending.infantry, 'HOLD');
  assert.equal(calls.at(-1).sortieCap, undefined);
  Game.BattleDefaults.setSortieCount('infantry', '0');
  Game.BattleDefaults.setSortieCount('rocket', '0');
  await Game.BattleDefaults.save();
  assert.deepEqual(JSON.parse(JSON.stringify(calls.at(-1).sortieArmy)), { infantry: 0, rocket: 0 });
  Game.BattleDefaults.setAutomaticSortie(true);
  await Game.BattleDefaults.save();
  assert.equal(calls.at(-1).sortieArmy, null);
});

test('非法数字恢复上一有效选择，超限整数自动限制后保存', async () => {
  const { Game, calls, nodes } = setup();
  Game.Core.views.battleDefaults({ innerHTML: '' });
  await Promise.resolve();
  nodes['battle-defaults-message'] = { textContent: '' };
  Game.BattleDefaults.setAutomaticSortie(false);
  Game.BattleDefaults.setSortieCount('infantry', '12345');
  for (const count of ['-1', '1.5', 'NaN', 'Infinity', '9007199254740992']) {
    nodes['battle-defaults-sortie-number-infantry'].value = count;
    Game.BattleDefaults.setSortieCount('infantry', count);
    assert.equal(calls.length, 1);
    assert.match(nodes['battle-defaults-message'].textContent, /非负整数/);
    assert.equal(nodes['battle-defaults-sortie-number-infantry'].value, '12345');
    assert.equal(nodes['battle-defaults-sortie-range-infantry'].value, '12345');
    assert.equal(nodes['battle-defaults-sortie-total'].textContent, '12345');
  }
  Game.BattleDefaults.setSortieCount('infantry', '37501');
  assert.match(nodes['battle-defaults-message'].textContent, /已限制到该兵种可选上限 37500/);
  await Game.BattleDefaults.save();
  assert.equal(calls.length, 2);
  assert.equal(calls.at(-1).sortieArmy.infantry, 37500);
});

test('百万驻军共用四十万防守额度，滑动与数字输入联动且降低数量释放额度', async () => {
  const { Game, calls, nodes, saved } = setup();
  saved.sortieCap = 400000;
  Game.Core.state.army = { infantry: 600000, rocket: 400000 };
  const view = { innerHTML: '' };
  Game.Core.views.battleDefaults(view);
  await new Promise(resolve => setImmediate(resolve));
  Game.BattleDefaults.setAutomaticSortie(false);
  Game.Core.views.battleDefaults(view);
  let renders = 0;
  Game.Core.render = () => { renders++; };
  const enter = (type, unitId, value) => {
    const id = 'battle-defaults-sortie-' + type + '-' + unitId;
    const tag = view.innerHTML.match(new RegExp('<input[^>]*id="' + id + '"[^>]*>'))[0];
    const handler = tag.match(/oninput="([^"]+)"/)[1];
    nodes[id].value = String(value);
    vm.runInNewContext('(function () { ' + handler + '; }).call(input)', { Game, input: nodes[id] });
  };
  enter('range', 'infantry', 300000);
  assert.equal(nodes['battle-defaults-sortie-number-infantry'].value, '300000');
  assert.equal(nodes['battle-defaults-sortie-range-infantry'].style['--p'], '75.0%');
  assert.equal(nodes['battle-defaults-sortie-range-rocket'].max, '100000');
  assert.equal(nodes['battle-defaults-sortie-number-rocket'].max, '100000');
  enter('number', 'rocket', 400000);
  assert.equal(nodes['battle-defaults-sortie-range-rocket'].value, '100000');
  assert.equal(nodes['battle-defaults-sortie-number-rocket'].value, '100000');
  assert.equal(nodes['battle-defaults-sortie-range-infantry'].max, '300000');
  assert.equal(nodes['battle-defaults-sortie-total'].textContent, '400000');
  assert.equal(nodes['battle-defaults-sortie-remaining'].textContent, '0');
  await Game.BattleDefaults.save();
  assert.deepEqual(calls.at(-1).sortieArmy, { infantry: 300000, rocket: 100000 });
  enter('range', 'infantry', 200000);
  assert.equal(nodes['battle-defaults-sortie-remaining'].textContent, '100000');
  assert.equal(nodes['battle-defaults-sortie-range-rocket'].style['--p'], '50.0%');
  assert.equal(nodes['battle-defaults-sortie-limit-rocket'].textContent, '200000');
  enter('range', 'rocket', 200000);
  assert.equal(nodes['battle-defaults-sortie-number-rocket'].value, '200000');
  assert.equal(nodes['battle-defaults-sortie-remaining'].textContent, '0');
  assert.equal(renders, 0, '拖动和输入不重新渲染整页');
  assert.deepEqual(Game.Core.state.army, { infantry: 600000, rocket: 400000 });
});

test('选择数量不能超过该兵种驻军，未驻军兵种滑动条禁用', async () => {
  const { Game, calls, nodes } = setup();
  Game.Core.state.army = { infantry: 18, rocket: 0 };
  Game.Core.views.battleDefaults({ innerHTML: '' });
  await new Promise(resolve => setImmediate(resolve));
  Game.BattleDefaults.setAutomaticSortie(false);
  Game.BattleDefaults.setSortieCount('infantry', '1000');
  Game.BattleDefaults.setSortieCount('rocket', '1000');
  assert.equal(nodes['battle-defaults-sortie-range-infantry'].value, '18');
  assert.equal(nodes['battle-defaults-sortie-range-infantry'].max, '18');
  assert.equal(nodes['battle-defaults-sortie-range-rocket'].disabled, true);
  assert.equal(nodes['battle-defaults-sortie-number-rocket'].value, '0');
  assert.equal(nodes['battle-defaults-sortie-total'].textContent, '18');
  await Game.BattleDefaults.save();
  assert.deepEqual(calls.at(-1).sortieArmy, { infantry: 18, rocket: 0 });
});

test('账号旧编队按当前驻军和额度缩减为草稿，余数不分给零出战兵种', async () => {
  const { Game, calls, saved } = setup();
  saved.sortieArmy = { htank: 0, infantry: 10, rocket: 2 };
  saved.sortieCap = 3;
  Game.DATA.units.htank = { name: '重型坦克' };
  Game.Core.state.army = { htank: 100, infantry: 2, rocket: 2 };
  const view = { innerHTML: '' };
  Game.Core.views.battleDefaults(view);
  await new Promise(resolve => setImmediate(resolve));
  Game.Core.views.battleDefaults(view);
  assert.match(view.innerHTML, /已按当前驻军和迎战上限调整编队，请保存生效/);
  assert.match(view.innerHTML, /id="battle-defaults-sortie-total">3</);
  assert.deepEqual(saved.sortieArmy, { htank: 0, infantry: 10, rocket: 2 });
  assert.deepEqual(calls, ['load'], '只调整草稿，不自动覆盖账号预设');
  await Game.BattleDefaults.save();
  assert.deepEqual(calls.at(-1).sortieArmy, { htank: 0, infantry: 2, rocket: 1 });
  assert.equal(calls.at(-1).defending.infantry, 'HOLD');
});

test('保存前驻军或上限变化时同步表单并一次性保存', async () => {
  const { Game, calls, nodes } = setup();
  let cap = 400000;
  Game.Core.sortieCap = () => cap;
  Game.Core.state.army = { infantry: 600000, rocket: 400000 };
  const view = { innerHTML: '' };
  Game.Core.views.battleDefaults(view);
  await new Promise(resolve => setImmediate(resolve));
  Game.BattleDefaults.setAutomaticSortie(false);
  Game.BattleDefaults.setSortieCount('infantry', 300000);
  Game.BattleDefaults.setSortieCount('rocket', 100000);
  cap = 150000;
  Game.Core.state.army.infantry = 100000;
  await Game.BattleDefaults.save();
  assert.equal(calls.length, 2);
  assert.equal(nodes['battle-defaults-sortie-total'].textContent, '150000');
  assert.equal(nodes['battle-defaults-sortie-cap'].textContent, '150000');
  assert.equal(nodes['battle-defaults-sortie-number-infantry'].value, '75000');
  assert.equal(nodes['battle-defaults-sortie-number-rocket'].value, '75000');
  assert.match(nodes['battle-defaults-message'].textContent, /已保存/);
  assert.deepEqual(calls.at(-1).sortieArmy, { infantry: 75000, rocket: 75000 });
  cap = 0;
  Game.Core.views.battleDefaults(view);
  assert.match(view.innerHTML, /id="battle-defaults-sortie-total">0</);
  await Game.BattleDefaults.save();
  assert.deepEqual(calls.at(-1).sortieArmy, { infantry: 0, rocket: 0 });
});

test('切换城市重新加载配置和上限，切换账号不保留前一个账号草稿', async () => {
  const { Game, calls, saved } = setup();
  const view = { innerHTML: '' };
  Game.Core.views.battleDefaults(view);
  await new Promise(resolve => setImmediate(resolve));
  saved.sortieCap = 137500;
  Game.Core.state.player.activeCityId = 2;
  Game.Core.views.battleDefaults(view);
  await new Promise(resolve => setImmediate(resolve));
  Game.Core.views.battleDefaults(view);
  assert.match(view.innerHTML, /137500/);
  assert.equal(calls.filter(call => call === 'load').length, 2);
  Game.BattleDefaults.setAutomaticSortie(false);
  Game.BattleDefaults.setSortieCount('infantry', 42);
  Game.API.getBattleDefaults = () => Promise.resolve({ outgoing: {}, defending: {}, sortieArmy: null, sortieCap: 37500 });
  Game.Core.state.player.id = 20;
  Game.Core.views.battleDefaults(view);
  await new Promise(resolve => setImmediate(resolve));
  Game.Core.views.battleDefaults(view);
  assert.match(view.innerHTML, /disabled placeholder="自动"/);
  await Game.BattleDefaults.save();
  assert.equal(calls.at(-1).sortieArmy, null);
});

test('保存过程中重新渲染按钮后恢复可用，并保留后续编辑的未保存提示', async () => {
  const { Game, nodes } = setup();
  const view = { innerHTML: '' };
  Game.Core.views.battleDefaults(view);
  await new Promise(resolve => setImmediate(resolve));
  nodes['battle-defaults-message'] = { textContent: '' };
  nodes['battle-defaults-save'] = { disabled: false };
  let completeSave;
  Game.API.saveBattleDefaults = () => new Promise(resolve => { completeSave = resolve; });
  const pending = Game.BattleDefaults.save();
  assert.equal(nodes['battle-defaults-save'].disabled, true);
  Game.BattleDefaults.setAutomaticSortie(false);
  Game.Core.views.battleDefaults(view);
  nodes['battle-defaults-save'] = { disabled: true };
  completeSave();
  await pending;
  assert.equal(nodes['battle-defaults-save'].disabled, false);
  assert.equal(nodes['battle-defaults-message'].textContent, '已保存；仍有未保存的修改');
});

test('导航栏末尾依次展示独立战术、排名与商城入口', () => {
  const context = vm.createContext({ window: null });
  context.window = context;
  require('./load-constants.cjs')(context);
  const navItems = context.Game.Constants.navItems;
  assert.equal(navItems.at(-3).route, 'battleDefaults');
  assert.match(navItems.at(-3).label, /^(?:默认)?战术$/);
  assert.equal(navItems.at(-2).route, 'rankings');
  assert.equal(navItems.at(-1).route, 'shop');
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
  assert.match(view.innerHTML, /离线战斗时，兵种优先攻击射程内的敌方同类型兵种/);
  assert.match(view.innerHTML, /在线指挥时，可指定攻击射程内的任意敌方兵种/);
  nodes['battle-defaults-message'] = { textContent: '' };
  nodes['battle-defaults-save'] = { disabled: false };
  Game.BattleDefaults.setAction('outgoing', 'rocket', 'ADVANCE');
  assert.equal(nodes['battle-defaults-message'].textContent, '有未保存的修改');
  await Game.BattleDefaults.save();
  assert.equal(calls.length, 2);
  assert.equal(calls[1].outgoing.rocket, 'ADVANCE');
  assert.equal(calls[1].defending.rocket, 'RETREAT');
});
