const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup() {
  const state = { world: {}, items: { recruit_military_weapon: 2, expBook: 1, emptySpeed: 0 } };
  const context = vm.createContext({
    console, Date,
    window: null,
    document: { getElementById() { return null; } },
    Game: {
      state,
      DATA: { items: {
        expBook: { cat: 'officer', icon: '📘', name: '经验书', desc: '提升经验' },
        emptySpeed: { cat: 'util', icon: '⚡', name: '空加速券', desc: '不应显示' }
      } },
      Core: { state, render() {}, views: {}, skillText() { return ''; } },
      toast() {}, go() {}
    }
  });
  context.window = context;
  require('./load-constants.cjs')(context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/depot.js'), 'utf8'), context);
  return context.Game;
}

test('仓库默认显示军官装备分类并生成横向 tabs', () => {
  const G = setup();
  let html = '';
  G.Depot.renderView({ set innerHTML(value) { html = value; } });
  assert.match(html, /class="depot-tabs"/);
  assert.match(html, /class="depot-tab active"[^>]*>军官装备<\/button>/);
  assert.match(html, /列兵军刀/);
  assert.doesNotMatch(html, /📘 经验书/);
  assert.equal((html.match(/role="tab"/g) || []).length, 5);
  assert.match(html, />兵种装备<\/button>/);
  assert.doesNotMatch(html, /zone-head/);
  assert.doesNotMatch(html, /===\s*军官装备\s*===/);
  assert.doesNotMatch(html, /- 仓库 -/);
});

test('切换分类只渲染当前分类并保留选中状态', () => {
  const G = setup();
  let renders = 0;
  G.Core.render = () => { renders++; };
  G.Depot.setTab('officer');
  assert.equal(G.state._depotTab, 'officer');
  assert.equal(renders, 1);
  let html = '';
  G.Depot.renderView({ set innerHTML(value) { html = value; } });
  assert.match(html, /class="depot-tab active"[^>]*>军官道具<\/button>/);
  assert.match(html, /📘 经验书/);
  assert.doesNotMatch(html, /列兵军刀/);
});

test('无效分类回到首个有效分类，避免空白仓库', () => {
  const G = setup();
  G.Depot.setTab('unknown');
  assert.equal(G.state._depotTab, 'equipment');
});

test('旧珠宝分类状态自动显示有效分类，仓库不会空白', () => {
  const G = setup();
  G.state._depotTab = 'jewelry';
  const view = {};
  G.Depot.renderView(view);
  assert.match(view.innerHTML, /class="depot-tab active"[^>]*>军官装备<\/button>/);
  assert.match(view.innerHTML, /列兵军刀/);
  assert.doesNotMatch(view.innerHTML, /珠宝珍品/);
});

test('数量为 0 的道具不会出现在对应分类中', () => {
  const G = setup();
  G.state._depotTab = 'util';
  let html = '';
  G.Depot.renderView({ set innerHTML(value) { html = value; } });
  assert.doesNotMatch(html, /空加速券/);
  assert.match(html, /暂无此类道具/);
});

test('军官装备宝箱仍可在军官道具分类中开启', () => {
  const G = setup();
  G.DATA.items.box_recruit_military = { cat: 'officer', icon: '📦', name: '列兵军事装备箱', desc: '开启获得整套列兵军事装备', isBox: true };
  G.state.items.box_recruit_military = 1;
  G.state._depotTab = 'officer';
  let html = '';
  G.Depot.renderView({ set innerHTML(value) { html = value; } });
  assert.match(html, /列兵军事装备箱/);
  assert.match(html, /onclick="Game\.Depot\.useItem\('box_recruit_military'\)">\[?开启宝箱\]?<\/button>/);
  assert.doesNotMatch(html, /前往晋升军衔/);
});

test('军衔凭证在功能道具分类中提供晋升入口', () => {
  const G = setup();
  G.DATA.items.rank_certificate_2 = { cat: 'util', icon: '🎖️', name: '上等兵晋升凭证', desc: '仅用于上等兵' };
  G.state.items.rank_certificate_2 = 1;
  G.Depot.setTab('util');
  let html = '';
  G.Depot.renderView({ set innerHTML(value) { html = value; } });
  assert.match(html, /前往晋升军衔/);
  assert.match(html, /上等兵晋升凭证/);
});

test('军官装备分类正确识别与渲染防御装备', () => {
  const G = setup();
  G.state.items.recruit_defense_weapon = 1;
  G.state.items.box_recruit_defense = 1;
  G.state._depotTab = 'equipment';
  let html = '';
  G.Depot.renderView({ set innerHTML(value) { html = value; } });
  assert.match(html, /列兵护身盾/);
  assert.match(html, /onclick="Game\.Depot\.useItem\('recruit_defense_weapon'\)"/);
});

test('改名卡对当天已改名军官不发送使用请求', () => {
  const G = setup();
  const messages = [];
  G.toast = message => messages.push(message);
  G.escapeHtml = value => String(value);
  G.Depot._pendingOfficer = { id: 1, name: '张辽', nameRenameAvailableAt: Date.now() + 60000 };
  G.API = { depotUse() { throw Error('不应使用改名卡'); } };
  const view = { innerHTML: '' };
  G.Depot.renderRename(view);
  assert.match(view.innerHTML, /北京时间 0:00 重置/);
  G.Depot.confirmRename();
  assert.match(messages[0], /请明日 0:00 后再试/);
});

test('城市改名卡从仓库打开城市改名窗口', () => {
  const G = setup();
  let opened = 0;
  G.DATA.items.cityRenameCard = { cat: 'util', name: '城市改名卡', icon: '🏷️' };
  G.state.items.cityRenameCard = 1;
  G.Main = { promptRenameCityInDrawer() { opened++; } };
  G.API = { depotUse() { throw Error('改名卡应由城市改名接口消耗'); } };
  G.Depot.useItem('cityRenameCard');
  assert.equal(opened, 1);
});
