const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup() {
  const state = { world: {}, items: { gem: 2, expBook: 1, emptySpeed: 0 } };
  const context = vm.createContext({
    console, Date,
    window: null,
    document: { getElementById() { return null; } },
    Game: {
      state,
      DATA: { items: {
        gem: { cat: 'jewelry', icon: '💎', name: '宝石', desc: '珍贵宝石' },
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

test('仓库默认显示珠宝分类并生成横向 tabs', () => {
  const G = setup();
  let html = '';
  G.Depot.renderView({ set innerHTML(value) { html = value; } });
  assert.match(html, /class="depot-tabs"/);
  assert.match(html, /class="depot-tab active"[^>]*>珠宝珍品<\/button>/);
  assert.match(html, /💎 宝石/);
  assert.doesNotMatch(html, /📘 经验书/);
  assert.equal((html.match(/role="tab"/g) || []).length, 5);
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
  assert.doesNotMatch(html, /💎 宝石/);
});

test('无效分类回到珠宝分类，避免空白仓库', () => {
  const G = setup();
  G.Depot.setTab('unknown');
  assert.equal(G.state._depotTab, 'jewelry');
});

test('数量为 0 的道具不会出现在对应分类中', () => {
  const G = setup();
  G.state._depotTab = 'util';
  let html = '';
  G.Depot.renderView({ set innerHTML(value) { html = value; } });
  assert.doesNotMatch(html, /空加速券/);
  assert.match(html, /暂无此类道具/);
});

test('珠宝宝箱在珠宝分类中显示开启宝箱按钮而非前往晋升军衔', () => {
  const G = setup();
  G.DATA.items.box_gem = { cat: 'jewelry', icon: '🗃️', name: '军衔珠宝宝箱', desc: '开启获得晋升珠宝', isBox: true };
  G.state.items.box_gem = 1;
  G.state._depotTab = 'jewelry';
  let html = '';
  G.Depot.renderView({ set innerHTML(value) { html = value; } });
  assert.match(html, /🗃️ 军衔珠宝宝箱/);
  assert.match(html, /onclick="Game\.Depot\.useItem\('box_gem'\)">\[?开启宝箱\]?<\/button>/);
  assert.match(html, /onclick="Game\.go\('mainQuest'\)">\[?前往晋升军衔\]?<\/button>/);
});
