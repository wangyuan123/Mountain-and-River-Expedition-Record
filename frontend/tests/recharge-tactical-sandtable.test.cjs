const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setupTest(response) {
  const requests = [];
  const toasts = [];
  const topbar = { innerHTML: '', querySelector: () => null };
  const view = {
    innerHTML: '',
    scrollTop: 0,
    querySelector: selector => (selector === '.page-backbar' ? {} : null)
  };
  const soundsPlayed = [];

  const context = vm.createContext({
    console,
    localStorage: { getItem: () => null, setItem: () => {}, removeItem: () => {} },
    document: {
      getElementById: id => ({ topbar, view })[id] || null,
      createElement: tag => ({
        className: '',
        type: '',
        innerHTML: '',
        setAttribute: () => {},
        addEventListener: () => {},
        appendChild: () => {}
      })
    },
    Game: {
      DATA: {},
      toast: msg => toasts.push(msg),
      Sound: { play: cue => soundsPlayed.push(cue) },
      fmt: n => String(n),
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
  game.toast = msg => toasts.push(msg);
  game.Core.state = game.state = {
    player: { id: 1, name: '指挥官', vipLevel: 1, totalRechargeCents: 600 },
    resources: { diamond: 100 },
    world: { marches: [], incoming: [] }
  };
  game.Core.views.home = target => { target.innerHTML = ''; };

  return { game, view, toasts, requests, soundsPlayed };
}

test('直接渲染旧充值页面只展示未开放说明，不展示档位或支付表单', () => {
  const { game, view, requests } = setupTest();
  game.Recharge.renderView(view);
  assert.match(view.innerHTML, /暂未开放充值，敬请期待/);
  assert.doesNotMatch(view.innerHTML, /确认充值|模拟支付|rech-grid|rech-modal-overlay|onclick/);
  assert.equal(requests.length, 0);
});

test('旧充值页面的缓存操作均只提示未开放，不播放到账音或生成收执', async () => {
  const { game, toasts, requests, soundsPlayed } = setupTest({ success: true, diamonds: 1080 });
  game.Recharge.setTab('diamond');
  game.Recharge.selectPackage('p98');
  game.Recharge.selectChannel('模拟支付');
  await game.Recharge.confirmPay();
  assert.deepEqual(requests, []);
  assert.deepEqual(soundsPlayed, []);
  assert.deepEqual(toasts, Array(4).fill('暂未开放充值，敬请期待'));
  assert.equal(game.Recharge.lastReceipt, undefined);
  assert.equal(game.Core.state.resources.diamond, 100);
  assert.equal(game.Core.state.player.totalRechargeCents, 600);
});
