const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup(initialCount = 0) {
  const state = { player: { id: 1 }, world: {}, reports: [], unreadReportCount: initialCount };
  const nav = {
    badge: null,
    getAttribute: () => 'reports',
    querySelector() { return this.badge; },
    appendChild(badge) { this.badge = badge; }
  };
  const context = vm.createContext({
    console, setTimeout, clearTimeout,
    document: {
      activeElement: null,
      querySelector: () => null,
      querySelectorAll: () => [nav],
      getElementById: () => ({ innerHTML: '' }),
      createElement: () => ({ remove() { nav.badge = null; } })
    },
    Game: {
      state, DATA: { units: {}, forts: {} },
      Core: { state, route: 'home', views: {}, render() {}, refreshTop() {}, renderNavBar() {} },
      toast() {}, escapeHtml: text => text || '',
      API: { getToken: () => 'alice', getUnreadReports: async () => ({ unreadCount: initialCount }) }
    }
  });
  context.window = context;
  const load = file => vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), context);
  load('battle.js');
  return { context, G: context.Game, nav, load };
}
const flush = () => new Promise(resolve => setImmediate(resolve));

test('login shows the server total without opening reports, caps at 99+, and hides zero', () => {
  const { G, nav, load } = setup(120);
  load('main-view.js');
  G.Battle.refreshUnread();
  assert.equal(G.state.reports.length, 0);
  assert.equal(nav.badge.className, 'nav-badge');
  assert.equal(nav.badge.textContent, '99+');
  assert.match(G.MainView.navBar(), /data-route="reports"[^]*?<span class="nav-badge">99\+<\/span>/);
  G.state.unreadReportCount = 2;
  G.Battle.refreshUnread();
  assert.equal(nav.badge.textContent, '2');
  G.state.unreadReportCount = 0;
  G.Battle.refreshUnread();
  assert.equal(nav.badge, null);
});

for (const type of ['battle', 'scoutReport']) {
  test(`${type} updates unread badge while composing and duplicate delivery does not double count`, async () => {
    const { context, G, nav, load } = setup(1);
    context.document.activeElement = { tagName: 'TEXTAREA' };
    let renders = 0;
    G.Core.render = () => { renders++; };
    G.API.getUnreadReports = async () => ({ unreadCount: 2 });
    load('ws-client.js');
    load('ws-handlers.js');
    const message = JSON.stringify({ type, data: { id: 42, type: type === 'battle' ? 'battle' : 'scout', win: true } });
    G.WS.handleMessage(message);
    G.WS.handleMessage(message);
    await flush();
    assert.equal(G.state.reports.length, 1);
    assert.equal(nav.badge.textContent, '2');
    assert.equal(renders, 0);
  });
}

test('state refresh preserves received reports for this player but not another account', () => {
  const { G, load } = setup(3);
  G.ApiClient = function () {};
  load('api.js');
  G.state.reports.push({ id: 1, readAt: 0 });
  const reports = G.state.reports;
  G.API.applyState({ player: { id: 1 }, unreadReportCount: 3 });
  assert.equal(G.state.reports, reports);
  G.API.applyState({ player: { id: 2 }, unreadReportCount: 0 });
  assert.equal(G.state.reports, undefined);
  assert.equal(G.Battle.unreadCount(), 0);
});

test('history loads even when a live report arrived first, without repeated empty requests', async () => {
  const { G } = setup(2);
  G.state.reports.push({ id: 2, time: 2, readAt: 0 });
  let calls = 0;
  G.API.getReports = async () => { calls++; return [{ id: 1, time: 1, readAt: 0 }, { id: 2, time: 2, readAt: 0 }]; };
  G.Battle.renderReportsList({});
  await flush();
  assert.deepEqual(Array.from(G.state.reports, report => report.id), [2, 1]);
  G.Battle.renderReportsList({});
  await flush();
  assert.equal(calls, 1);
  G.state.reports = [];
  G.API.getReports = async () => { calls++; return []; };
  G.Battle.renderReportsList({});
  await flush();
  G.Battle.renderReportsList({});
  await flush();
  assert.equal(calls, 2);
});

test('read acknowledgement decreases the total and failed reads retain the badge', async () => {
  const { G, nav } = setup(60);
  G.state.reports.push({ id: 1, readAt: 0 });
  let finish;
  G.API.markReportRead = () => new Promise(resolve => { finish = resolve; });
  G.API.getUnreadReports = async () => ({ unreadCount: 59 });
  const pending = G.Battle.markOneRead(1);
  assert.equal(G.Battle.unreadCount(), 60);
  assert.equal(G.state.reports[0].readAt, 0);
  finish({ success: true, readAt: 123, unreadCount: 59 });
  await pending;
  assert.equal(G.state.reports[0].readAt, 123);
  assert.equal(nav.badge.textContent, '59');
  G.state.reports.push({ id: 2, readAt: 0 });
  G.API.markReportRead = async () => { throw new Error('failed'); };
  await G.Battle.markOneRead(2);
  assert.equal(G.state.reports[1].readAt, 0);
  assert.equal(nav.badge.textContent, '59');
});

test('read-all clears reports beyond the loaded page, then a new battle restores the badge', async () => {
  const { G, nav, load } = setup(60);
  G.state.reports.push({ id: 1, readAt: 0 });
  G.API.markAllReportsRead = async () => ({ success: true, unreadCount: 0 });
  G.API.getUnreadReports = async () => ({ unreadCount: 0 });
  await G.Battle.markAllRead();
  assert.ok(G.state.reports[0].readAt > 0);
  assert.equal(nav.badge, null);
  load('ws-client.js');
  load('ws-handlers.js');
  G.API.getUnreadReports = async () => ({ unreadCount: 1 });
  G.WS.handleMessage(JSON.stringify({ type: 'battle', data: { id: 2, win: false } }));
  await flush();
  assert.equal(nav.badge.textContent, '1');
});

test('a delayed unread response cannot overwrite a newer count or another login', async () => {
  const { G } = setup(5);
  const responses = [];
  G.API.getUnreadReports = () => new Promise(resolve => responses.push(resolve));
  const older = G.Battle.syncUnread();
  const newer = G.Battle.syncUnread();
  responses[1]({ unreadCount: 2 });
  await newer;
  responses[0]({ unreadCount: 5 });
  await older;
  assert.equal(G.Battle.unreadCount(), 2);
  const previousAccount = G.Battle.syncUnread();
  G.API.getToken = () => 'bob';
  G.state.unreadReportCount = 0;
  responses[2]({ unreadCount: 99 });
  await previousAccount;
  assert.equal(G.Battle.unreadCount(), 0);
});
