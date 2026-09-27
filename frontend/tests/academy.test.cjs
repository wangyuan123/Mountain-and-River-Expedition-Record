const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup(chance, refreshAt = 0, quota = {}, list = []) {
  const state = { buildings: { academy: 10 }, resources: { gold: 1000 },
    academy: { list, fiveStarBatchChance: chance, refreshAt,
      refreshRoundLimit: 30, refreshDailyLimit: 100, ...quota } };
  const context = vm.createContext({ Date, console, window: null, Game: {
    DATA: { starColor: { 1: '#fff', 2: '#4a90e2', 3: '#9013fe', 4: '#f5a623', 5: '#ffe14a' } }, Core: { state, views: {}, render() {} },
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
    assert.ok(view.innerHTML.includes('每次刷新10名候选人，整批出现1名五星的概率：<b>' + label + '</b>'));
    assert.match(view.innerHTML, /每批最多1名五星/);
  }
});

test('军校冷却中不提供立即刷新或自动刷新提示', () => {
  const G = setup(0.03, Date.now() + 3600000, { refreshRoundCount: 30, refreshDailyCount: 30 });
  const view = { innerHTML: '' };
  G.Officer.renderAcademyView(view);
  assert.match(view.innerHTML, /分钟后可再次刷新/);
  assert.match(view.innerHTML, /本轮已用满30次/);
  assert.doesNotMatch(view.innerHTML, /onclick="Game\.Officer\.refreshAcademy|自动|立即刷新/);
});

test('一轮未满30次时可连续刷新，显示本轮与今日已用次数', () => {
  for (const count of [0, 1, 29]) {
    const G = setup(0.03, 0, { refreshRoundCount: count, refreshDailyCount: count });
    const view = { innerHTML: '' };
    G.Officer.renderAcademyView(view);
    assert.ok(view.innerHTML.includes('本轮已刷新 ' + count + '/30'));
    assert.ok(view.innerHTML.includes('今日已刷新 ' + count + '/100'));
    assert.match(view.innerHTML, /Game.Officer.onRefreshClick/);
    assert.doesNotMatch(view.innerHTML, /disabled|刷新休整中/);
  }
});

test('每日100次用满时禁用按钮，即使本轮尚未满30次', () => {
  const G = setup(0.03, 0, { refreshRoundCount: 10, refreshDailyCount: 100, refreshDailyResetAt: Date.now() + 3600000 });
  const view = { innerHTML: '' };
  G.Officer.renderAcademyView(view);
  assert.match(view.innerHTML, /今日刷新已达100次（北京时间次日0点重置）/);
  assert.match(view.innerHTML, /disabled/);
  assert.doesNotMatch(view.innerHTML, /Game.Officer.onRefreshClick/);
});

test('冷却到期后开启下一轮，保留当日累计次数', () => {
  const G = setup(0.03, Date.now() - 1, { refreshRoundCount: 30, refreshDailyCount: 60 });
  const view = { innerHTML: '' };
  G.Officer.renderAcademyView(view);
  assert.ok(view.innerHTML.includes('本轮已刷新 0/30'));
  assert.ok(view.innerHTML.includes('今日已刷新 60/100'));
  assert.match(view.innerHTML, /Game.Officer.onRefreshClick/);
});

test('跨日恢复日额度但不能提前跳过本轮冷却', () => {
  const G = setup(0.03, Date.now() + 3600000, { refreshRoundCount: 30, refreshDailyCount: 100, refreshDailyResetAt: Date.now() - 1 });
  const view = { innerHTML: '' };
  G.Officer.renderAcademyView(view);
  assert.ok(view.innerHTML.includes('今日已刷新 0/100'));
  assert.match(view.innerHTML, /刷新休整中/);
  assert.doesNotMatch(view.innerHTML, /今日刷新已达|Game.Officer.onRefreshClick/);
});

test('跨日且未在冷却时可再次刷新', () => {
  const G = setup(0.03, 0, { refreshRoundCount: 10, refreshDailyCount: 100, refreshDailyResetAt: Date.now() - 1 });
  const view = { innerHTML: '' };
  G.Officer.renderAcademyView(view);
  assert.ok(view.innerHTML.includes('今日已刷新 0/100'));
  assert.match(view.innerHTML, /Game.Officer.onRefreshClick/);
});

test('后端拒绝刷新时显示失败原因', async () => {
  const G = setup(0.03);
  G.API.refreshAcademy = async () => ({ success: false, message: '黄金不足(需200)' });
  await G.Officer.refreshAcademy();
  assert.equal(G.message, '黄金不足(需200)');
});

test('军校候选五星显示钻石边框、包角和属性稀有度，四星及以下为普通卡片不显示金色边框', () => {
  const G = setup(0.03, 0, {}, [
    { id: 'five', name: '五星候选', star: 5, military: 219, defense: 180, logistics: 160, knowledge: 150, skills: [] },
    { id: 'four', name: '四星候选', star: 4, military: 140, defense: 130, logistics: 120, knowledge: 110, skills: [] }
  ]);
  const view = { innerHTML: '' };
  G.Officer.renderAcademyView(view);
  const html = view.innerHTML;
  assert.match(html, /menu-item ok officer-card tier-diamond/);
  assert.match(html, /class="tier-ribbon rarity-ur">极度稀有（UR）<\/span>/);
  assert.match(html, /corner-tl/);
  assert.match(html, /menu-item ok officer-card tier-normal/);
  assert.doesNotMatch(html, /tier-gold/);
  assert.doesNotMatch(html, /荣耀黄金|永恒钻石/);
});
