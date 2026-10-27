const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { randomUUID } = require('node:crypto');

const drain = async () => { for (let i = 0; i < 20; i++) await Promise.resolve(); };

function createHarness(options = {}) {
  let elapsed = 0;
  const storage = new Map([
    ['wargame_token', options.token || 'user-token'],
    ['wargame_user', options.username || '雨天唐纳德'],
    ['wargame_server', 'jiangsu-1']
  ]);
  const calls = [];
  const events = {};
  const nodes = {};

  const context = {
    console,
    TypeError,
    AbortController,
    performance: { now: () => elapsed },
    localStorage: {
      getItem: k => storage.get(k) || null,
      setItem: (k, v) => storage.set(k, String(v)),
      removeItem: k => storage.delete(k)
    },
    document: {
      getElementById: id => nodes[id] || null,
      querySelector: sel => nodes[sel] || null,
      querySelectorAll: sel => (nodes[sel] ? [nodes[sel]] : []),
      addEventListener: (k, fn) => { events[k] = fn; },
      removeEventListener: () => {}
    },
    addEventListener: (k, fn) => { events[k] = fn; },
    removeEventListener: () => {},
    crypto: { randomUUID },
    setInterval: () => 1,
    clearInterval: () => {},
    setTimeout: (fn, ms) => {
      if (typeof fn === 'function') fn();
      return 1;
    },
    clearTimeout: () => {},
    matchMedia: () => ({ matches: false }),
    toasts: [],
    toast(msg) { this.toasts.push(msg); }
  };
  context.window = context;
  vm.createContext(context);

  const load = file => vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), context);
  load('constants.js');
  load('servers.js');
  load('api-client.js');

  const client = new context.Game.ApiClient('/api');
  context.Game.escapeHtml = s => String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  context.Game.API = {
    client,
    getToken: () => client.getToken(),
    getUsername: () => client.getUsername(),
    isLoggedIn: () => !!client.getToken(),
    clearToken: () => client.clearToken()
  };

  context.Game.Core = {
    route: 'login',
    views: {},
    history: [],
    state: null,
    init() {},
    bindKeys() {},
    render() {
      if (this.views[this.route]) {
        const v = { innerHTML: '' };
        this.views[this.route](v);
        this.renderedHtml = v.innerHTML;
      }
    }
  };

  context.fetch = (url, opts) => {
    return new Promise(resolve => calls.push({
      url,
      opts,
      reply(data, status = 200) {
        resolve({
          ok: status < 400,
          status,
          headers: { get: () => null },
          text: async () => JSON.stringify(data)
        });
      }
    }));
  };

  load('protection.js');
  load('main-view.js');
  load('main.js');

  return { context, G: context.Game, calls, storage, nodes };
}

test('isServerError accurately detects server down, 500, NoClassDefFoundError, and network failures', () => {
  const { G } = createHarness();
  assert.equal(G.Main.isServerError({ status: 500, message: 'Handler dispatch failed: java.lang.NoClassDefFoundError: com/wargame/model/constants/CommanderRenamePolicy' }), true);
  assert.equal(G.Main.isServerError({ status: 502, message: 'Bad Gateway' }), true);
  assert.equal(G.Main.isServerError({ status: 503, message: 'Service Unavailable' }), true);
  assert.equal(G.Main.isServerError({ code: 'SERVER_MAINTENANCE' }), true);
  assert.equal(G.Main.isServerError(new TypeError('Failed to fetch')), true);
  assert.equal(G.Main.isServerError({ message: '网络错误，请检查网络连接' }), true);

  // Business / client / auth / real-name errors are not server errors
  assert.equal(G.Main.isServerError({ code: 'REAL_NAME_REQUIRED', status: 403 }), false);
  assert.equal(G.Main.isServerError({ code: 'PLAY_WINDOW_CLOSED', status: 403 }), false);
  assert.equal(G.Main.isServerError({ code: 'INVALID_CREDENTIALS', status: 400 }), false);
  assert.equal(G.Main.isServerError(null), false);
});

test('server 500 error during startGame routes to login cover with maintenance notice, never protection', async () => {
  const { G, calls } = createHarness({ token: 'test-token', username: '雨天唐纳德' });
  G.load = () => Promise.reject({
    status: 500,
    message: 'Handler dispatch failed: java.lang.NoClassDefFoundError: com/wargame/model/constants/CommanderRenamePolicy'
  });

  const startPromise = G.Main.startGame();
  await drain();
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, '/api/anti-addiction/status');
  calls[0].reply({ enabled: true, canPlay: true, serverNow: 1000, allowedUntil: 999999, leaseUntil: 999999 });

  await drain();
  assert.equal(calls.length, 2);
  assert.equal(calls[1].url, '/api/play-sessions');
  calls[1].reply({ enabled: true, canPlay: true, serverNow: 1000, allowedUntil: 999999, leaseUntil: 999999 });

  await drain();
  await startPromise;

  // Crucial assertion: route is login (Game Cover), NOT protection!
  assert.equal(G.Core.route, 'login');
  assert.equal(G.Main.isMaintenanceActive(), true);

  // Rendered cover landing page includes the maintenance card
  const html = G.Core.renderedHtml;
  assert.match(html, /class="login-maintenance-card"/);
  assert.match(html, /战区指挥部通讯维护中/);
  assert.match(html, /服务器维护中/);
  assert.match(html, /雨天唐纳德/);
  assert.match(html, /江苏一区/);
  assert.match(html, /id="maintenanceRetryBtn"/);
  assert.match(html, /class="login-hero-maintenance-tag"/);
  assert.match(html, /链路维护中/);

  // Does NOT contain raw Java stack trace or irrelevant minor rules
  assert.doesNotMatch(html, /NoClassDefFoundError/);
  assert.doesNotMatch(html, /未成年人仅可在周五/);
});

test('maintenance notice provides retry mechanism that restores game when server recovers', async () => {
  const { G, calls, nodes } = createHarness({ token: 'test-token', username: '雨天唐纳德' });
  // clear initial boot startGame calls if any
  calls.length = 0;
  G.Main.setServerMaintenance({ status: 500, message: '服务器维护中...' });
  assert.equal(G.Main.isMaintenanceActive(), true);

  nodes.maintenanceRetryBtn = { disabled: false, innerHTML: '' };
  let started = false;
  G.Main.startGame = async () => { started = true; };

  const retryPromise = G.Main.retryConnection();
  await drain();
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, '/api/anti-addiction/status');
  calls[0].reply({ enabled: false, canPlay: true });

  await drain();
  await retryPromise;
  assert.equal(G.Main.isMaintenanceActive(), false);
  assert.equal(started, true);
});
