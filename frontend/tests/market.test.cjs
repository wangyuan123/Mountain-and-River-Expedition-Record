const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup(initialBuildings = {}) {
  const state = {
    player: { id: 101, name: '司令官' },
    resources: { food: 10000, steel: 10000, oil: 5000, rare: 2000, gold: 50000 },
    buildings: Object.assign({ command: 5, exchange: 3, depot: [2] }, initialBuildings),
    constructions: []
  };

  const context = vm.createContext({
    console, Date, Math, parseInt, Array, Object, String,
    window: null,
    document: {
      createElement() {
        return {
          className: '',
          innerHTML: '',
          style: {},
          querySelector() { return null; },
          querySelectorAll() { return []; },
          addEventListener() {},
          appendChild() {},
          remove() {}
        };
      },
      getElementById() { return null; },
      querySelector() { return null; }
    },
    Game: {
      state,
      DATA: {
        resources: {
          food: { name: '粮食' },
          steel: { name: '钢铁' },
          oil: { name: '石油' },
          rare: { name: '稀矿' },
          gold: { name: '黄金' }
        },
        buildings: {
          command: { name: '市政厅', desc: '核心中枢', slots: 1 },
          exchange: { name: '军需物资中转站', desc: '战备物资调配,按比例转换资源', slots: 1 },
          depot: { name: '军需仓库', desc: '提升战备物资上限', slots: 32 }
        }
      },
      fmt(n) { return String(n); },
      escapeHtml(s) { return String(s); },
      resourceIconHtml(k) { return '[' + k + ']'; },
      toast() {},
      go() {},
      Core: {
        state,
        views: {},
        buildingLevel(id) {
          const v = state.buildings[id];
          if (Array.isArray(v)) return v.reduce((a, b) => a + b, 0);
          return v || 0;
        },
        buildingLevels(id) {
          const v = state.buildings[id];
          if (Array.isArray(v)) return v;
          return v ? [v] : [];
        },
        refreshContent() {},
        render() {}
      },
      API: {
        getMarketOverview() { return Promise.resolve({ orders: [], usedSlots: 0, slotCap: 5 }); },
        getMyMarketOrders() { return Promise.resolve([]); },
        createMarketOrder() { return Promise.resolve({ success: true }); },
        buyMarketOrder() { return Promise.resolve({ success: true }); },
        cancelMarketOrder() { return Promise.resolve({ success: true }); },
        systemExchange() { return Promise.resolve({ success: true, exchange: { fromRes: 'food', toRes: 'steel', cost: 1000, gain: 620 } }); }
      }
    }
  });
  context.window = context;
  context.Game.Core.state = state;

  const marketCode = fs.readFileSync(path.join(__dirname, '../js/market.js'), 'utf8');
  vm.runInContext(marketCode, context);

  return { G: context.Game, state };
}

test('交易所视图正确注册到 Core.views.exchange', () => {
  const { G } = setup();
  assert.equal(typeof G.Core.views.exchange, 'function');
  assert.ok(G.Market);
  assert.equal(typeof G.Market.renderView, 'function');
  assert.equal(typeof G.Market.setTab, 'function');
  assert.equal(typeof G.Market.buyOrder, 'function');
  assert.equal(typeof G.Market.cancelOrder, 'function');
  assert.equal(typeof G.Market.doCreateOrder, 'function');
  assert.equal(typeof G.Market.doSystemExchange, 'function');
});

test('撤销挂单经主题确认弹窗，确认后才发送下架请求', async () => {
  const { G } = setup();
  let dialog;
  let canceledId;
  let refreshCount = 0;
  G.World = { showConfirm(options) { dialog = options; } };
  G.API.cancelMarketOrder = (id) => {
    canceledId = id;
    return Promise.resolve({ success: true });
  };
  G.Market.refresh = () => { refreshCount++; };

  G.Market.cancelOrder(42);
  assert.match(dialog.message, /上架资源将如数返还/);
  assert.equal(dialog.okText, '确认下架');
  assert.equal(canceledId, undefined);

  dialog.onConfirm();
  await Promise.resolve();
  assert.equal(canceledId, 42);
  assert.equal(refreshCount, 1);
});

test('未建造交易所时 renderView 提示尚未建造', () => {
  const { G } = setup({ exchange: 0 });
  const viewEl = { innerHTML: '' };
  G.Market.renderView(viewEl);
  assert.match(viewEl.innerHTML, /当前城池尚未建造交易所，无法使用交易功能/);
});

test('已建造交易所时 renderView 渲染市场Tab与统计信息', () => {
  const { G } = setup({ exchange: 3 });
  const viewEl = { innerHTML: '' };
  G.Market.renderView(viewEl);
  assert.match(viewEl.innerHTML, /交易所 Lv\.3/);
  assert.match(viewEl.innerHTML, /市场大厅/);
  assert.match(viewEl.innerHTML, /我的货架/);
  assert.match(viewEl.innerHTML, /战略调配/);
  // Lv.3 战略调配比率: 50% + 3*4% = 62%
  assert.match(viewEl.innerHTML, /62%/);
});

test('build.js 中 SPECIAL_ROUTES 包含 exchange: exchange', () => {
  const buildCode = fs.readFileSync(path.join(__dirname, '../js/build.js'), 'utf8');
  assert.match(buildCode, /exchange:\s*['"]exchange['"]/);
  assert.match(buildCode, /战略调配比率/);
  assert.match(buildCode, /货架挂单槽位/);
  assert.match(buildCode, /市场交易税率/);
});
