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
    sortieArmy: null, sortieCap: 25000, locked: false
  };
  const nodes = {};
  nodes['battle-defaults-message'] = { textContent: '' };
  const Game = {
    Core: { route: 'battleDefaults', state: { player: { id: 10 }, army: { infantry: 50000, rocket: 50000 } }, views: {}, render() {} },
    DATA: { units: { infantry: { name: '步兵' }, rocket: { name: '反坦克火箭' } } },
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
  Game.Core.state.gameplay = { battleDefaultsLocked: true };
  Game.Core.views.battleDefaults(view);
  assert.match(view.innerHTML, /id="battle-defaults-save"[^>]*disabled/);
  Game.BattleDefaults.setAction('outgoing', 'infantry', 'RETREAT');
  await Game.BattleDefaults.save();
  assert.equal(calls.length, 1);
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
  Game.BattleDefaults.setAction('defending', 'infantry', 'RETREAT');
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
  assert.match(view.innerHTML, /出征战斗/);
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

test('守城全员规则可见且没有数量配置，仅提交攻守行动', async () => {
  const { Game, calls, saved } = setup();
  saved.sortieArmy = { infantry: 0 };
  Game.Core.state.army = { infantry: 1000000, rocket: 1000000 };
  const view = { innerHTML: '' };
  Game.Core.views.battleDefaults(view);
  await new Promise(setImmediate);
  Game.Core.views.battleDefaults(view);
  assert.match(view.innerHTML, /守城：不受带兵上限限制/);
  assert.match(view.innerHTML, /出征：受带兵上限限制/);
  assert.match(view.innerHTML, /25000/);
  assert.match(view.innerHTML, /当前城内 1000000/);
  assert.match(view.innerHTML, /全部参战/);
  assert.match(view.innerHTML, /参战名单在开战时锁定/);
  assert.match(view.innerHTML, /下一场战斗开始参战/);
  assert.match(view.innerHTML, /后勤部队同样参战并承担战损/);
  assert.match(view.innerHTML, /伤兵、在途部队不加入本场/);
  assert.match(view.innerHTML, /禁止从该城市发起任何新出征/);
  assert.doesNotMatch(view.innerHTML, /type="(?:range|number|checkbox)"/);
  assert.doesNotMatch(view.innerHTML, /按比例缩减|迎战上限|自动选兵/);
  await Game.BattleDefaults.save();
  assert.deepEqual(Object.keys(calls.at(-1)).sort(), ['defending', 'outgoing']);
});

test('动员到期只更新出征容量，保留未保存的默认行动', async () => {
  const { Game, calls, nodes, saved } = setup();
  let cap = 37500;
  Game.Core.armyCap = () => cap;
  Game.Mobilization = { info: () => ({ key: 'mobilization:2' }) };
  saved.formationKey = 'default';
  const view = { innerHTML: '' };
  Game.Core.views.battleDefaults(view);
  await new Promise(setImmediate);
  Game.Core.views.battleDefaults(view);
  assert.match(view.innerHTML, /37500/);
  Game.BattleDefaults.setAction('defending', 'rocket', 'HOLD');
  cap = 25000;
  nodes['battle-defaults-dispatch-cap'] = { textContent: '' };
  Game.BattleDefaults.refreshMobilization();
  assert.equal(nodes['battle-defaults-dispatch-cap'].textContent, '25000');
  await Game.BattleDefaults.save();
  assert.equal(calls.filter(call => call === 'load').length, 1);
  assert.equal(calls.at(-1).defending.rocket, 'HOLD');
  assert.equal(Object.hasOwn(calls.at(-1), 'formationKey'), false);
});

test('无雷达来袭信息时城市战斗锁仍禁止编辑和保存', async () => {
  const { Game, calls } = setup();
  const view = { innerHTML: '' };
  Game.Core.views.battleDefaults(view);
  await new Promise(setImmediate);
  Game.Core.state.cityBattleLocked = true;
  Game.Core.state.gameplay = { battleDefaultsLocked: true };
  Game.Core.views.battleDefaults(view);
  assert.match(view.innerHTML, /id="battle-defaults-save"[^>]*disabled/);
  Game.BattleDefaults.setAction('defending', 'rocket', 'HOLD');
  await Game.BattleDefaults.save();
  assert.equal(calls.length, 1);
});

test('切换城市后重读出征容量，旧账号迟到响应不会覆盖新账号', async () => {
  const { Game, calls, saved } = setup();
  const view = { innerHTML: '' };
  Game.Core.render = () => Game.Core.views.battleDefaults(view);
  Game.Core.render();
  await new Promise(setImmediate);
  saved.sortieCap = 137500;
  Game.Core.state.player.activeCityId = 2;
  Game.Core.render();
  await new Promise(setImmediate);
  assert.match(view.innerHTML, /137500/);
  let finish;
  Game.API.getBattleDefaults = () => new Promise(resolve => { finish = resolve; });
  Game.Core.state.player.activeCityId = 3;
  Game.Core.render();
  Game.Core.state.player.id = 20;
  Game.API.getBattleDefaults = () => Promise.resolve({ outgoing: { infantry: 'RETREAT', rocket: 'HOLD' },
    defending: { infantry: 'RETREAT', rocket: 'HOLD' }, sortieCap: 30000, locked: false });
  Game.Core.render();
  await new Promise(setImmediate);
  finish(saved);
  await new Promise(setImmediate);
  assert.match(view.innerHTML, /30000/);
  assert.doesNotMatch(view.innerHTML, /137500/);
  await Game.BattleDefaults.save();
  assert.equal(calls.at(-1).outgoing.infantry, 'RETREAT');
});

test('保存失败保留行动草稿以便重试', async () => {
  const { Game, calls, nodes } = setup();
  const view = { innerHTML: '' };
  Game.Core.views.battleDefaults(view);
  await new Promise(setImmediate);
  Game.BattleDefaults.setAction('outgoing', 'infantry', 'RETREAT');
  const save = Game.API.saveBattleDefaults;
  Game.API.saveBattleDefaults = () => Promise.reject(new Error('网络异常'));
  await Game.BattleDefaults.save();
  assert.equal(nodes['battle-defaults-message'].textContent, '网络异常');
  Game.API.saveBattleDefaults = save;
  await Game.BattleDefaults.save();
  assert.equal(calls.at(-1).outgoing.infantry, 'RETREAT');
});
