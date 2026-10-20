const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup(quests) {
  const calls = [];
  const buttons = new Map();
  const rankPanel = { scrollIntoView: options => calls.push(['rank', options.block]) };
  const G = {
    Core: { route: 'mainQuest', state: { player: { id: 1, mainCity: true }, world: {}, officers: [{ id: 7 }] },
      views: {}, openForum: () => calls.push(['forum']) },
    DATA: { items: {} },
    Main: { promptRenameCommander: () => calls.push(['commanderName']), promptRenameCityInDrawer: () => calls.push(['cityName']) },
    Cities: { open: () => calls.push(['cities']) },
    Army: { setTab: tab => calls.push(['armyTab', tab]) },
    WorldMap: { setMode: mode => calls.push(['mapMode', mode]) },
    API: { claimQuest: id => { calls.push(['claim', id]); return new Promise(() => {}); } },
    go: route => calls.push(['route', route]), toast: text => calls.push(['toast', text])
  };
  let html = '';
  const view = {
    get innerHTML() { return html; },
    set innerHTML(value) {
      html = value;
      buttons.clear();
      for (const match of value.matchAll(/<button\b([^>]*)>([^<]*)<\/button>/g)) {
        const attrs = Object.fromEntries([...match[1].matchAll(/([\w-]+)="([^"]*)"/g)].map(m => [m[1], m[2]]));
        if (!attrs['data-quest-goto'] && !attrs['data-id']) continue;
        buttons.set(attrs['data-quest-goto'] || attrs['data-id'], { attrs, text: match[2], getAttribute: key => attrs[key] });
      }
    }
  };
  const context = vm.createContext({ Game: G, console, document: {
    getElementById: () => null,
    querySelector: selector => selector === '.rank-promotion' ? rankPanel : null,
    querySelectorAll: selector => [...buttons.values()].filter(button => {
      if (selector === '[data-quest-goto]') return !!button.attrs['data-quest-goto'];
      if (selector === '.quest-claim-btn[data-id]') return !!button.attrs['data-id'];
      // 模拟浏览器会把共用布局类的两种按钮同时选中，防止领取处理误绑到跳转按钮。
      if (selector === '.quest-claim-btn') return true;
      return false;
    })
  } });
  context.window = context;
  require('./load-constants.cjs')(context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/main-quest.js'), 'utf8'), context);
  G.MainQuest.state.chapters = [{ id: 'stage1', name: '阶段', quests, status: 'in_progress' }];
  const render = () => G.MainQuest.drawQuestView(view);
  return { G, calls, buttons, view, render };
}

test('进行中的任务提供可点击的去完成，已完成只领奖，已领取和锁定任务没有跳转', () => {
  const f = setup([
    { id: 'q1_2', title: '拓荒开垦', status: 'in_progress' },
    { id: 'q1_3', title: '钢铁脊梁', status: 'completed' },
    { id: 'q1_1', title: '万丈高楼', status: 'claimed' },
    { id: 'q1_4', title: '招兵买马', status: 'locked' }
  ]);
  f.render();
  assert.equal(f.buttons.get('q1_2').text, '去完成');
  assert.equal(f.buttons.get('q1_3').text, '领取奖励');
  assert.equal(f.buttons.has('q1_1'), false);
  assert.equal(f.buttons.has('q1_4'), false);
  f.buttons.get('q1_2').onclick();
  assert.deepEqual(f.calls, [['route', 'buildRes']]);
  f.buttons.get('q1_3').onclick();
  assert.deepEqual(f.calls.at(-1), ['claim', 'q1_3']);
  for (const id of ['q1_3', 'q1_1', 'q1_4', 'unknown']) f.G.MainQuest.gotoQuest(id);
  assert.equal(f.calls.length, 2);
});

test('建设、征兵、科技和军官任务定位到对应功能入口', () => {
  const cases = [
    ['q1_1', 'buildArmy'], ['q1_2', 'buildRes'], ['q1_3', 'buildRes'],
    ['q1_4', 'army'], ['q2_6', 'tech'], ['q1_7', 'officer'], ['q3_3', 'academy']
  ];
  const f = setup(cases.map(([id]) => ({ id, title: id, status: 'in_progress' })));
  f.render();
  for (const [id, route] of cases) {
    f.calls.length = 0;
    f.buttons.get(id).onclick();
    assert.deepEqual(f.calls[0], ['route', route]);
    if (route === 'army') assert.deepEqual(f.calls[1], ['armyTab', 'units']);
  }
  f.G.Core.state.officers = [];
  f.G.MainQuest.gotoQuest('q1_7');
  assert.deepEqual(f.calls.at(-1), ['route', 'academy']);
});

test('改名任务打开对应窗口，主城改名在分城中先引导切城，晋升定位到本页晋升区域', () => {
  const f = setup(['q1_5', 'q1_6', 'q1_8', 'q1_9', 'q2_9'].map(id => ({ id, title: id })));
  f.render();
  for (const [id, expected] of [['q1_5', 'commanderName'], ['q1_6', 'cityName'], ['q1_8', 'forum'], ['q2_9', 'cities']]) {
    f.buttons.get(id).onclick();
    assert.deepEqual(f.calls.at(-1), [expected]);
  }
  f.buttons.get('q1_9').onclick();
  assert.deepEqual(f.calls.at(-1), ['rank', 'start']);
  f.calls.length = 0;
  f.G.Core.state.player.mainCity = false;
  f.buttons.get('q1_6').onclick();
  assert.deepEqual(f.calls[0], ['cities']);
  assert.match(f.calls[1][1], /切换至主城/);
  assert.equal(f.calls.some(call => call[0] === 'cityName'), false);
});

test('地图任务选择目标分类，讨伐任务保留掠夺与征服配置', () => {
  const ids = ['q2_3', 'q3_5', 'q4_1', 'q2_4', 'q2_5', 'q4_3', 'q4_4'];
  const f = setup(ids.map(id => ({ id, title: id, status: 'in_progress' })));
  f.render();
  for (const [id, tab] of [['q2_3', 'wild'], ['q3_5', 'npc'], ['q4_1', 'player']]) {
    f.buttons.get(id).onclick();
    assert.equal(f.G.Core.state.world._activeTab, tab);
    assert.deepEqual(f.calls.slice(-2), [['route', 'world'], ['mapMode', 'list']]);
  }
  for (const [id, kind, occupyTarget] of [['q2_4', 'wild', false], ['q2_5', 'wild', true], ['q4_3', 'player', false], ['q4_4', 'player', true]]) {
    f.buttons.get(id).onclick();
    assert.equal(f.G.Core.state.world._campaignTaskMode.kind, kind);
    assert.equal(f.G.Core.state.world._campaignTaskMode.occupyTarget, occupyTarget);
  }
});

test('当前任务目录的每项主线任务都有导航入口', () => {
  const catalog = fs.readFileSync(path.join(__dirname, '../../backend/src/main/java/com/wargame/service/quest/QuestCatalog.java'), 'utf8');
  const ids = [...catalog.matchAll(/\bq\("(q\d+_\d+)"/g)].map(match => match[1]);
  const f = setup(ids.map(id => ({ id, title: id, status: 'in_progress' })));
  f.render();
  assert.ok(ids.length > 0);
  for (const id of ids) assert.equal(f.buttons.get(id)?.text, '去完成', id);
  const resourceBuildings = new Set(['farm', 'refinery', 'oilfield', 'raremine']);
  for (const match of catalog.matchAll(/\bq\("(q\d+_\d+)"[^\n]*"BUILD_UPGRADE_DONE", "(\w+)"/g)) {
    f.buttons.get(match[1]).onclick();
    assert.deepEqual(f.calls.at(-1), ['route', resourceBuildings.has(match[2]) ? 'buildRes' : 'buildArmy'], match[1]);
  }
});
