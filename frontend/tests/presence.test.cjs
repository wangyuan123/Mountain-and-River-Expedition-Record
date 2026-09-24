const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
function load(name, ctx) { require('./load-constants.cjs')(ctx); vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', name), 'utf8'), ctx); }
function wsSetup() {
  const nodes = [{ outerHTML: '' }, { outerHTML: '' }];
  const sockets = []; let heartbeat, retry;
  class Socket {
    static OPEN = 1;
    constructor() { this.readyState = 0; this.sent = []; sockets.push(this); }
    send(data) { this.sent.push(data); }
    close() { this.readyState = 3; }
  }
  const ctx = vm.createContext({ console, Date, WebSocket: Socket,
    document: { querySelectorAll: () => nodes },
    location: { protocol: 'http:', host: 'localhost:8080', port: '8080' },
    setInterval(fn) { heartbeat = fn; return 1; }, clearInterval() {},
    setTimeout(fn) { retry = fn; return 1; }, clearTimeout() {},
    Game: { API: { getToken: () => 'test' } }
  });
  ctx.window = ctx; load('ws-client.js', ctx);
  return { ws: ctx.Game.WS, nodes, sockets, beat: () => heartbeat(), retry: () => retry() };
}
test('connection labels track opening, initial failure, reconnect, logout and stale socket events', () => {
  const t = wsSetup(); const w = t.ws;
  w.connect(); assert.equal(w.status, 'connecting');
  t.sockets[0].readyState = 3; t.sockets[0].onclose();
  assert.equal(w.status, 'reconnecting');
  t.retry(); t.sockets[1].readyState = 1; t.sockets[1].onopen();
  assert.equal(w.status, 'connected');
  t.nodes.forEach(n => assert.match(n.outerHTML, /已连接/));
  t.sockets[0].onclose(); assert.equal(w.connected, true);
  w.disconnect(); assert.equal(w.status, 'disconnected');
  t.nodes.forEach(n => assert.match(n.outerHTML, /已断开/));
});
test('heartbeats accept JSON pong and trigger reconnect when no response arrives', () => {
  const t = wsSetup(); t.ws.connect(); t.sockets[0].readyState = 1; t.sockets[0].onopen();
  t.beat(); assert.deepEqual(JSON.parse(t.sockets[0].sent[0]), { type: 'ping' });
  t.ws.lastPong = 0; t.ws.handleMessage('{"type":"pong"}');
  assert.ok(t.ws.lastPong > 0);
  t.ws.lastPong = Date.now() - 91000; t.beat();
  assert.equal(t.ws.connected, false); assert.equal(t.ws.status, 'reconnecting');
});
test('guild refresh patches only status, failed refresh is unknown, contact prefills recipient without sending', async () => {
  const labels = { guildPresence_1: { innerHTML: '' }, mailTo: { value: '' } };
  let fail = false, calls = 0, composed = false;
  const ctx = vm.createContext({ console, Date,
    document: { hidden: false, getElementById: id => labels[id] },
    setInterval() {},
    Game: { Core: { route: 'guild', views: {}, render() { throw Error('must not replace editor'); } },
      escapeHtml: String, fmt: String,
      API: { getMyGuild: async () => { calls++; if (fail) throw Error('offline'); return { id: 8, joined: true, members: [{ playerId: 1, online: true }] }; } },
      go(route) { ctx.Game.Core.route = route; }, Mail: { compose() { composed = true; } }
    }
  }); ctx.window = ctx; load('guild.js', ctx);
  const g = ctx.Game.Guild; g.mine = { id: 8, joined: true, members: [{ playerId: 1, name: '团员', online: false }] };
  g.refreshPresence(); await new Promise(setImmediate);
  assert.match(labels.guildPresence_1.innerHTML, /● 在线/);
  fail = true; g.refreshPresence(); await new Promise(setImmediate);
  assert.match(labels.guildPresence_1.innerHTML, /状态未知/);
  g.contact(1); assert.equal(composed, true); assert.equal(labels.mailTo.value, '团员');
  g.refreshPresence(); assert.equal(calls, 2);
});

test('guild leave uses themed confirmation with leader/member copy and submits only after confirmation', async () => {
  const controls = {
    '.guild-confirm-cancel': { focus() {} },
    '.guild-confirm-submit': { focus() {}, disabled: false, textContent: '' },
    '.guild-confirm-error': { hidden: true, textContent: '' }
  };
  let modal, calls = 0, reloads = 0;
  const trigger = { isConnected: true, focus() {} };
  const document = {
    activeElement: trigger,
    body: { appendChild(node) { modal = node; } },
    getElementById(id) { return id === 'guildLeaveConfirm' && modal && !modal.removed ? modal : null; },
    createElement() {
      return { querySelector: selector => controls[selector], remove() { this.removed = true; } };
    },
    addEventListener() {}, removeEventListener() {}
  };
  const ctx = vm.createContext({ console, Date, document, setInterval() {},
    Game: {
      Core: { views: {} }, escapeHtml: String, toast() {},
      API: { leaveGuild: async () => { calls++; return { message: '已完成' }; } }
    }
  });
  ctx.window = ctx;
  load('guild.js', ctx);
  const guild = ctx.Game.Guild;
  guild.reload = () => { reloads++; };
  guild.mine = { joined: true, isLeader: true, name: '测试军团', members: [{}] };

  guild.leave();
  assert.match(modal.className, /guild-confirm-mask/);
  assert.match(modal.innerHTML, /解散军团.*确认解散/);
  assert.doesNotMatch(modal.innerHTML, /确定退出当前军团吗/);
  guild.leave();
  controls['.guild-confirm-cancel'].onclick();
  assert.equal(calls, 0);
  assert.equal(modal.removed, true);

  guild.mine = { joined: true, isLeader: false, name: '测试军团' };
  guild.leave();
  assert.match(modal.innerHTML, /退出军团.*确认退出/);
  controls['.guild-confirm-submit'].onclick();
  controls['.guild-confirm-submit'].onclick();
  await new Promise(setImmediate);
  assert.equal(calls, 1);
  assert.equal(reloads, 1);
  assert.equal(modal.removed, true);

  guild.mine = { joined: true, isLeader: true, name: '测试军团', members: [{}, {}] };
  ctx.Game.API.leaveGuild = () => Promise.reject(new Error('请先移交团长或移出全部成员'));
  guild.leave();
  assert.match(modal.innerHTML, /请先移交团长或移出全部成员/);
  controls['.guild-confirm-submit'].onclick();
  await new Promise(setImmediate);
  assert.equal(modal.removed, undefined);
  assert.equal(controls['.guild-confirm-submit'].disabled, false);
  assert.equal(controls['.guild-confirm-error'].hidden, false);
  assert.match(controls['.guild-confirm-error'].textContent, /请先移交团长/);
  controls['.guild-confirm-cancel'].onclick();
});
