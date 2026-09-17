const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '..');

function setup() {
  const nodes = Object.fromEntries(['disablePassword', 'disableConfirm', 'disableMsg', 'accountSubmit', 'accountCancel',
    'recoverAccount', 'keepDeletion', 'recoveryMsg'].map(id => [id, { value: '', textContent: '', disabled: false }]));
  nodes.disablePassword.value = 'test password';
  nodes.disableConfirm.value = '确认注销';
  const storage = new Map([['wargame_token', 'old-token']]);
  const listeners = {};
  let renders = 0, disconnects = 0, starts = 0;
  const context = {
    console, Date, Promise, Math, TypeError, setTimeout, clearTimeout, clearInterval,
    location: { port: '80' },
    localStorage: { getItem: k => storage.get(k) || null, setItem: (k, v) => storage.set(k, v), removeItem: k => storage.delete(k) },
    document: { getElementById: id => nodes[id] || null, activeElement: null },
    addEventListener: (name, fn) => { listeners[name] = fn; },
    Game: { escapeHtml: v => String(v).replaceAll('<', '&lt;'),
      Core: { state: { secret: 'old' }, route: 'settings', render() { renders++; } },
      Main: { guestMode: false, startGame() { starts++; return Promise.resolve(); } },
      WS: { disconnect() { disconnects++; } }, toast() {} }
  };
  context.window = context;
  const sandbox = vm.createContext(context);
  const load = file => vm.runInContext(fs.readFileSync(path.join(root, 'js', file), 'utf8'), sandbox);
  load('api-client.js'); load('api.js'); load('account.js');
  const account = context.Game.Account;
  account.preview = { blocker: '' }; account.username = '测试账号'; account.requestId = 'request-test-123';
  return { ...context, nodes, storage, listeners, account, counters: () => ({ renders, disconnects, starts }) };
}
const settle = () => new Promise(resolve => setImmediate(resolve));

test('login requiring recovery never creates a game session', async () => {
  const ctx = setup(); ctx.storage.clear();
  ctx.Game.API.client.post = () => Promise.resolve({ status: 'RECOVERY_REQUIRED', recoveryToken: 'secret', recoverUntil: 123 });
  const data = await ctx.Game.API.login('user', 'pass');
  assert.equal(data.status, 'RECOVERY_REQUIRED');
  assert.equal(ctx.storage.has('wargame_token'), false);
  assert.equal(ctx.counters().starts, 0);
});

test('lost deletion response performs a read-only status check and shows a durable result', async () => {
  const ctx = setup(); let mutations = 0, checks = 0;
  ctx.Game.API.disableAccount = () => { mutations++; return Promise.reject(Object.assign(new Error('lost'), { uncertain: true })); };
  ctx.Game.API.deletionStatus = () => { checks++; return Promise.resolve({ status: 'PENDING_DELETION', recoverUntil: 1900000000000 }); };
  ctx.account.submit(); ctx.account.submit();
  await settle();
  assert.equal(mutations, 1); assert.equal(checks, 1);
  assert.equal(ctx.Game.Core.route, 'login'); assert.equal(ctx.Game.Core.state, null);
  assert.equal(ctx.storage.has('wargame_token'), false);
  assert.match(ctx.account.loginPanel(), /注销申请已受理/);
  assert.match(ctx.account.loginPanel(), /北京时间/);
  assert.equal(ctx.counters().starts, 0);
});

test('uncertain status retry never repeats the destructive request', async () => {
  const ctx = setup(); let mutations = 0, checks = 0;
  ctx.Game.API.disableAccount = () => { mutations++; return Promise.reject(Object.assign(new Error('lost'), { uncertain: true })); };
  ctx.Game.API.deletionStatus = () => { checks++; return Promise.reject(new Error('offline')); };
  ctx.account.submit(); await settle();
  assert.equal(ctx.account.uncertain, true);
  assert.equal(ctx.nodes.accountSubmit.textContent, '查询注销状态');
  ctx.account.submit(); await settle();
  assert.equal(mutations, 1); assert.equal(checks, 2);
  assert.match(ctx.nodes.disableMsg.textContent, /结果待确认/);
});

test('wrong password stays in the dialog without clearing the active session', async () => {
  const ctx = setup();
  ctx.Game.API.disableAccount = () => Promise.reject(Object.assign(new Error('当前密码不正确'), { code: 'PASSWORD_INVALID' }));
  ctx.account.submit(); await settle();
  assert.equal(ctx.storage.get('wargame_token'), 'old-token');
  assert.equal(ctx.Game.Core.route, 'settings');
  assert.equal(ctx.nodes.accountSubmit.disabled, false);
  assert.match(ctx.nodes.disableMsg.textContent, /密码不正确/);
});

test('keeping deletion never invokes recovery or starts the game', () => {
  const ctx = setup(); let recoveries = 0;
  ctx.Game.API.recoverAccount = () => { recoveries++; };
  ctx.account.showRecovery({ username: '<script>', recoverUntil: 1900000000000, recoveryToken: 'secret' });
  assert.match(ctx.account.recoveryPanel(), /&lt;script>/);
  assert.doesNotMatch(ctx.account.recoveryPanel(), /secret/);
  ctx.account.keepDeletion();
  assert.equal(recoveries, 0); assert.equal(ctx.counters().starts, 0);
  assert.equal(ctx.account.recovery, null);
});

test('double-clicking recovery consumes one credential and starts the game once', async () => {
  const ctx = setup(); let calls = 0, finish;
  ctx.account.recovery = { recoveryToken: 'secret' };
  ctx.Game.API.recoverAccount = () => { calls++; return new Promise(resolve => { finish = resolve; }); };
  ctx.account.recover(); ctx.account.recover();
  assert.equal(calls, 1);
  finish({}); await settle();
  assert.equal(ctx.counters().starts, 1);
  assert.equal(ctx.account.recovery, null);
});

test('another tab changing account invalidates old responses without deleting its new token', () => {
  const ctx = setup();
  ctx.storage.set('wargame_token', 'new-account-token');
  const before = ctx.Game.API.client.cityRevision;
  ctx.listeners.storage({ key: 'wargame_token', oldValue: 'old-token', newValue: 'new-account-token' });
  assert.equal(ctx.storage.get('wargame_token'), 'new-account-token');
  assert.equal(ctx.Game.Core.state, null);
  assert.equal(ctx.Game.API.client.cityRevision, before + 1);
});

test('a parallel unauthorized poll cannot erase the pending deletion result', async () => {
  const ctx = setup(); let finish;
  ctx.Game.API.disableAccount = () => new Promise(resolve => { finish = resolve; });
  ctx.account.submit();
  const revision = ctx.Game.API.client.cityRevision;
  ctx.Game.API.client.handleUnauthorized();
  assert.equal(ctx.Game.API.client.cityRevision, revision);
  finish({ status: 'PENDING_DELETION', recoverUntil: 1900000000000 });
  await settle();
  assert.match(ctx.account.loginPanel(), /注销申请已受理/);
});

test('switching accounts during deletion never logs out the new account', async () => {
  const ctx = setup(); let finish;
  ctx.Game.API.disableAccount = () => new Promise(resolve => { finish = resolve; });
  ctx.account.submit();
  ctx.storage.set('wargame_token', 'new-account-token');
  ctx.listeners.storage({ key: 'wargame_token', oldValue: 'old-token', newValue: 'new-account-token' });
  finish({ status: 'PENDING_DELETION', recoverUntil: 1900000000000 });
  await settle();
  assert.equal(ctx.storage.get('wargame_token'), 'new-account-token');
  assert.match(ctx.account.loginPanel(), /账号已切换/);
});
