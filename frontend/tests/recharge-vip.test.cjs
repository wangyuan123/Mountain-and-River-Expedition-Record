const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup(response) {
  const requests = [];
  const toasts = [];
  const topbar = {
    innerHTML: '',
    querySelector(selector) {
      if (selector === '.forum-entry-btn') return {};
      if (selector.includes('.diamond')) return { textContent: '' };
      if (selector === '.topbar-vip-badge') {
        const badge = this.innerHTML.match(/class="topbar-vip-badge[^\"]*">([^<]+)/);
        return badge ? { textContent: badge[1] } : null;
      }
      return null;
    }
  };
  const view = { innerHTML: '', scrollTop: 0, querySelector: selector => selector === '.page-backbar' ? {} : null };
  const storage = {};
  const mockLocalStorage = {
    getItem: k => (Object.prototype.hasOwnProperty.call(storage, k) ? storage[k] : null),
    setItem: (k, v) => { storage[k] = String(v); },
    removeItem: k => { delete storage[k]; }
  };
  const context = vm.createContext({
    console,
    localStorage: mockLocalStorage,
    document: { getElementById: id => ({ topbar, view })[id] || null },
    Game: {
      DATA: {}, toast: message => toasts.push(message),
      ApiClient: class {
        post(route, body) {
          requests.push({ route, body });
          return Promise.resolve(response);
        }
        getUsername() { return '指挥官'; }
        isLoggedIn() { return true; }
        getToken() { return 'test-token'; }
      }
    }
  });
  context.window = context;
  for (const file of ['constants.js', 'core.js', 'api.js', 'vip.js', 'recharge.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), context);
  }
  const game = context.Game;
  game.toast = message => toasts.push(message);
  game.Core.state = game.state = {
    player: { id: 1, name: '指挥官', vipLevel: 0, totalRechargeCents: 0 },
    resources: { diamond: 0 }, world: { cityPos: { x: 10, y: 10 }, marches: [], incoming: [] },
    officers: [], reports: []
  };
  game.Core.views.home = target => { target.innerHTML = game.Vip.renderHomeCard(game.Core.state); };
  game.Core.render();
  return { game, topbar, view, requests, toasts, storage, context };
}

test('充值入口保留钻石余额并提示未开放，不切换页面或修改历史', () => {
  const { game, topbar, view, requests, toasts } = setup();
  game.Core.state.resources.diamond = 1234;
  game.Core.render();
  assert.match(topbar.innerHTML, /💎 1234/);
  assert.match(topbar.innerHTML, /title="暂未开放充值，敬请期待"/);
  const oldView = view.innerHTML;
  const oldHistory = Array.from(game.Core.history);
  game.go('recharge');
  assert.equal(game.Core.route, 'home');
  assert.equal(view.innerHTML, oldView);
  assert.deepEqual(Array.from(game.Core.history), oldHistory);
  assert.deepEqual(requests, []);
  assert.deepEqual(toasts, ['暂未开放充值，敬请期待']);
});

test('background topbar refresh updates VIP badge on promotion and respects badge visibility', () => {
  const { game, topbar } = setup();
  game.Core.state.player.vipLevel = 1;
  game.Core.refreshTop();
  assert.match(topbar.innerHTML, /VIP 1/);
  game.Core.state.player.vipLevel = 4;
  game.Core.refreshTop();
  assert.match(topbar.innerHTML, /VIP 4/);
  assert.match(topbar.innerHTML, /vip-badge-gold/);
  game.Core.state.player.vipBadgeVisible = false;
  game.Core.refreshTop();
  assert.doesNotMatch(topbar.innerHTML, /topbar-vip-badge/);
});

test('直接调用旧确认按钮和前端充值 API 都不发请求、不改变钻石或 VIP', async () => {
  const { game, topbar, requests, toasts } = setup({ success: true, vip: { vipLevel: 10 } });
  for (const id of [undefined, 'p6', 'p10000', 'invalid']) {
    const result = await game.Recharge.confirmPay(id);
    assert.equal(result.success, false);
    assert.equal(result.code, 'PAYMENT_DISABLED');
    assert.equal(result.message, '暂未开放充值，敬请期待');
    const apiResult = await game.API.shopRecharge(id, 1, 999999, '模拟支付');
    assert.equal(apiResult.success, false);
    assert.equal(apiResult.message, result.message);
  }
  assert.equal(game.Core.state.player.vipLevel, 0);
  assert.equal(game.Core.state.player.totalRechargeCents, 0);
  assert.equal(game.Core.state.resources.diamond, 0);
  assert.equal(game.Vip.cachedStatus, null);
  assert.doesNotMatch(topbar.innerHTML, /topbar-vip-badge/);
  assert.deepEqual(requests, []);
  assert.deepEqual(toasts, Array(4).fill('暂未开放充值，敬请期待'));
});

test('home VIP card is visible by default, and can be toggled off and on by user setting', () => {
  const { game, view, toasts, storage } = setup();

  // 1. 默认状态：开启
  assert.equal(game.Vip.isHomeCardVisible(), true);
  assert.match(game.Vip.renderHomeCard(game.Core.state), /home-vip-card/);
  assert.match(view.innerHTML, /战时特勤统帅部/);

  // 2. 玩家在设置中关闭特勤部显示
  game.Vip.setHomeCardVisible(false);
  assert.equal(game.Vip.isHomeCardVisible(), false);
  assert.equal(game.Vip.renderHomeCard(game.Core.state), '');
  assert.equal(view.innerHTML, ''); // Core.render() updates view
  assert.equal(storage['wargame_home_vip_visible_指挥官'], '0');
  assert.ok(toasts.includes('已关闭首页特勤部卡片显示'));

  // 3. 玩家在设置中重新开启特勤部显示
  game.Vip.setHomeCardVisible(true);
  assert.equal(game.Vip.isHomeCardVisible(), true);
  assert.match(game.Vip.renderHomeCard(game.Core.state), /home-vip-card/);
  assert.match(view.innerHTML, /战时特勤统帅部/);
  assert.equal(storage['wargame_home_vip_visible_指挥官'], undefined);
  assert.ok(toasts.includes('已开启首页特勤部卡片显示'));
});

test('settings view renders home VIP card toggle selector and reflects preference', () => {
  const { game, context } = setup();
  game.Core.render = () => {};
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/main-view.js'), 'utf8'), context);

  const settingsView = { innerHTML: '' };

  // 默认开启状态下设置项渲染
  game.Core.views.settings(settingsView);
  assert.match(settingsView.innerHTML, /id="homeVipCardSelector"/);
  assert.match(settingsView.innerHTML, /首页特勤部/);
  assert.match(settingsView.innerHTML, /在首页展示特勤统帅部与每日军饷快捷入口/);
  assert.match(settingsView.innerHTML, /<option value="1" selected>开启 \(默认\)<\/option>/);
  assert.match(settingsView.innerHTML, /<option value="0">关闭<\/option>/);

  // 关闭后，设置页面对应选中“关闭”
  game.Vip.setHomeCardVisible(false);
  game.Core.views.settings(settingsView);
  assert.match(settingsView.innerHTML, /<option value="1">开启 \(默认\)<\/option>/);
  assert.match(settingsView.innerHTML, /<option value="0" selected>关闭<\/option>/);
});
