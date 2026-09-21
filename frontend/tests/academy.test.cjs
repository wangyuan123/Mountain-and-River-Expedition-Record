const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup(chance, refreshAt = 0) {
  const state = { buildings: { academy: 10 }, resources: { gold: 1000 },
    academy: { list: [], fiveStarBatchChance: chance, refreshAt } };
  const context = vm.createContext({ Date, console, window: null, Game: {
    DATA: {}, Core: { state, views: {}, render() {} },
    API: {}, toast(message) { this.message = message; }
  } });
  context.window = context;
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/officer.js'), 'utf8'), context);
  return context.Game;
}

test('军校按服务端概率显示整批五星规则，而非单人概率', () => {
  for (const [chance, label] of [[0.003, '0.3%'], [0.03, '3%']]) {
    const G = setup(chance);
    const view = { innerHTML: '' };
    G.Officer.renderAcademyView(view);
    assert.ok(view.innerHTML.includes('整批出现1名五星的概率：<b>' + label + '</b>'));
    assert.match(view.innerHTML, /每批最多1名五星/);
  }
});

test('军校冷却中不提供立即刷新或自动刷新提示', () => {
  const G = setup(0.03, Date.now() + 3600000);
  const view = { innerHTML: '' };
  G.Officer.renderAcademyView(view);
  assert.match(view.innerHTML, /分钟后可再次刷新/);
  assert.doesNotMatch(view.innerHTML, /onclick="Game\.Officer\.refreshAcademy|自动|立即刷新/);
});

test('后端拒绝刷新时显示失败原因', async () => {
  const G = setup(0.03);
  G.API.refreshAcademy = async () => ({ success: false, message: '黄金不足(需200)' });
  await G.Officer.refreshAcademy();
  assert.equal(G.message, '黄金不足(需200)');
});
