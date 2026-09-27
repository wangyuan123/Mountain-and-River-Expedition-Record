const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function deferred() { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; }
function runtime() {
  const elements = {}, applied = [], calls = [];
  const view = { innerHTML: '', querySelectorAll: () => [], insertBefore(el) { elements[el.id] = el; } };
  elements.view = view;
  let token = 'account-a';
  const ctx = { console, Promise, setTimeout: () => 1, clearTimeout() {}, setInterval: () => 1, clearInterval() {},
    document: { getElementById: id => elements[id] || null, querySelector: () => null,
      createElement() { return { innerHTML: '', setAttribute() {}, querySelectorAll: () => [], remove() { delete elements[this.id]; } }; } } };
  ctx.window = ctx;
  ctx.Game = { Core: { views: {}, route: 'home', state: { player: { id: 1, citySlot: 0 }, world: {} }, renderTop() {} }, toast() {},
    API: { getToken: () => token, isLoggedIn: () => !!token, applyState: s => applied.push(s), client: {
      get() { const request = deferred(); calls.push({ method: 'GET', request }); return request.promise; },
      post(url, body) { const request = deferred(); calls.push({ method: 'POST', url, body, request }); return request.promise; }
    } }, go(route) { this.Core.route = route; } };
  vm.createContext(ctx);
  require('./load-constants.cjs')(ctx);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/onboarding.js'), 'utf8'), ctx);
  return { G: ctx.Game, calls, applied, elements, setAccount(id) { token = 'account-' + id; ctx.Game.Core.state.player.id = id; } };
}
function data(extra = {}) { return { enrolled: true, paused: false, done: false, completed: 0, objectives: [], supplies: [], current: null, checks: {}, ...extra }; }

test('a delayed response from the previous account cannot revive its guide', async () => {
  const r = runtime(); const old = r.G.Onboarding.init();
  r.setAccount(2); const next = r.G.Onboarding.init();
  r.calls[1].request.resolve(data({ paused: true })); await next;
  r.calls[0].request.resolve(data({ paused: false })); await old;
  assert.equal(r.G.Onboarding.state.data.paused, true);
  assert.equal(r.elements.onboardingBar, undefined);
});

test('a pre-mutation poll cannot undo a persisted pause and polling waits for mutations', async () => {
  const r = runtime(); const pending = r.G.Onboarding.init();
  const pause = r.G.Onboarding.mutate('pause', { paused: true });
  await r.G.Onboarding.refresh(); assert.equal(r.calls.length, 2);
  r.calls[1].request.resolve(data({ paused: true })); await pause;
  r.calls[0].request.resolve(data({ paused: false })); await pending;
  assert.equal(r.G.Onboarding.state.data.paused, true);
  assert.equal(r.G.Onboarding.state.busy, false);
});

test('duplicate clicks send one explicit supply request and stale rewards do not overwrite a new account', async () => {
  const r = runtime();
  const first = r.G.Onboarding.mutate('claim', { supplyId: 'base' });
  await r.G.Onboarding.mutate('claim', { supplyId: 'base' });
  assert.equal(r.calls.length, 1); assert.equal(r.calls[0].body.supplyId, 'base');
  r.setAccount(2); r.G.Onboarding.stop();
  r.calls[0].request.resolve(data({ state: { resources: { steel: 999 } } })); await first;
  assert.equal(r.applied.length, 0);
});

test('paused players can see and claim supplies without forcing the guide open', async () => {
  const r = runtime(); const pending = r.G.Onboarding.init();
  r.calls[0].request.resolve(data({ paused: true, supplies: [{ id: 'base', available: true, claimed: false }] })); await pending;
  assert.equal(r.G.Onboarding.hasSupply(), true); assert.equal(r.elements.onboardingBar, undefined);
  const claim = r.G.Onboarding.mutate('claim', { supplyId: 'base' });
  r.calls[1].request.resolve(data({ paused: true, supplies: [{ id: 'base', available: true, claimed: true }], state: { resources: { steel: 4000 } } })); await claim;
  assert.equal(r.applied[0].resources.steel, 4000); assert.equal(r.G.Onboarding.hasSupply(), false);
});

test('recommended gathering opens a returning gathering march rather than garrison deployment', async () => {
  const r = runtime(); const pending = r.G.Onboarding.findTarget('gather');
  assert.equal(r.calls[0].body.gather, true);
  r.calls[0].request.resolve({ id: 17, kind: 'wild', type: 'grainfield', occupied: true, x: 12, y: 10 }); await pending;
  const target = r.G.Core.state.world._dispatchTarget;
  assert.equal(target.kind, 'wild_gather'); assert.equal(target.action, 'gather'); assert.equal(target.target.id, 17);
  assert.equal(target.onboardingGather, true);
  assert.equal(target.gatherMode, 'auto');
  assert.equal(r.G.Core.route, 'dispatch');
});

test('officer step guides academy construction then recruitment before scouting', () => {
  const r = runtime();
  const objectives = [{ id: 'officer', title: '招募军官', body: '建造陆军讲武堂并招募军官', complete: false, reward: {}, diamond: 15 }];
  r.G.Onboarding.state.data = data({ objectives, current: objectives[0], checks: { academy: false } });
  r.G.Core.views.onboarding(r.elements.view);
  assert.match(r.elements.view.innerHTML, /data-ob-value="buildArmy:academy"/);
  r.G.Onboarding.state.data = data({ objectives, current: objectives[0], checks: { academy: true } });
  r.G.Core.views.onboarding(r.elements.view);
  assert.match(r.elements.view.innerHTML, /data-ob-value="academy:"/);
});

test('completed academy construction reopens the recruit step', async () => {
  const { G, elements, screen } = modalRuntime();
  const objective = { id: 'officer', title: '招募军官', body: '招募一名军官', complete: false, reward: {}, diamond: 15 };
  G.API.client.get = async () => data({ objectives: [objective], current: objective, checks: { academy: false } });
  await G.Onboarding.init();
  elements.onboardingBar.dialog.buttons.find(button => button.dataset.obValue === 'buildArmy:academy').onclick();
  assert.equal(screen.inert, false);
  await G.Onboarding.actionStarted('buildArmy', 'academy');
  G.API.client.get = async () => data({ objectives: [objective], current: objective, checks: { academy: true } });
  await G.Onboarding.refresh();
  assert.equal(screen.inert, true);
  assert.match(elements.onboardingBar.dialog.innerHTML, /data-ob-value="academy:"/);
});

function modalRuntime() {
  const elements = {}, calls = [], screen = { inert: false };
  const timers = new Map();
  let nextTimer = 1;
  let now = 1000000;
  let activeElement = null;
  let keydown;
  const document = {
    body: { appendChild(element) { elements[element.id] = element; } },
    get activeElement() { return activeElement; },
    getElementById(id) { return id === 'screen' ? screen : elements[id] || null; },
    addEventListener(name, listener) { if (name === 'keydown') keydown = listener; },
    querySelector() { return null; },
    createElement() {
      const seconds = { textContent: '' };
      const returnButton = {};
      const dialog = {
        html: '', buttons: [],
        set innerHTML(value) {
          this.html = value;
          this.buttons = [...value.matchAll(/<button\b([^>]*)>/g)].map(([, attrs]) => {
            const button = {
              dataset: { obAction: /data-ob-action="([^"]+)"/.exec(attrs)?.[1], obValue: /data-ob-value="([^"]*)"/.exec(attrs)?.[1] },
              focus() { activeElement = this; }
            };
            return button;
          });
        },
        get innerHTML() { return this.html; },
        querySelectorAll(selector) { return selector === '[data-ob-action]' ? this.buttons : this.buttons; },
        querySelector() { return this.buttons[0] || null; },
        contains(element) { return this.buttons.includes(element); }
      };
      return {
        id: '', className: '', dialog, seconds, returnButton,
        set innerHTML(value) { this.html = value; }, get innerHTML() { return this.html; },
        setAttribute() {}, querySelector(selector) { return selector === '.ob-report-seconds' ? seconds : selector === '.ob-report-return' ? returnButton : dialog; }, querySelectorAll() { return dialog.buttons; },
        contains(element) { return dialog.contains(element); }, remove() { delete elements[this.id]; }
      };
    }
  };
  elements.view = { innerHTML: '', querySelectorAll: () => [] };
  const ctx = { console, Promise, document, Date: class extends Date { static now() { return now; } },
    setTimeout: () => 1, clearTimeout() {},
    setInterval(callback, delay) { const id = nextTimer++; timers.set(id, { callback, delay }); return id; },
    clearInterval(id) { timers.delete(id); } };
  ctx.window = ctx;
  ctx.Game = { Core: { views: {}, route: 'home', state: { player: { id: 1, citySlot: 0 }, world: {} }, renderTop() {} }, toast() {},
    API: { getToken: () => 'token', isLoggedIn: () => true, applyState() {}, client: {
      get: async () => data({ objectives: [{ id: 'base' }], current: { id: 'base', title: '整备基地', body: '建设基地' },
        supplies: [{ id: 'base', title: '整备补给', available: false, claimed: false,
          resources: { food: 4000, steel: 4000, oil: 1200, rare: 200, gold: 500 } }] }),
      post(url, body) { const request = deferred(); calls.push({ url, body, request }); return request.promise; }
    } }, go(route) { this.Core.route = route; } };
  vm.createContext(ctx);
  require('./load-constants.cjs')(ctx);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/onboarding.js'), 'utf8'), ctx);
  return { G: ctx.Game, elements, calls, screen, timers, keydown: event => keydown(event),
    advanceReading(milliseconds) {
      now += milliseconds;
      for (const timer of [...timers.values()]) { if (timer.delay === 250) timer.callback(); }
    } };
}

test('guide modal locks the game, skip rewards once, and unlocks after completion', async () => {
  const { G, elements, calls, screen, keydown } = modalRuntime();
  await G.Onboarding.init();
  const modal = elements.onboardingBar;
  assert.equal(screen.inert, true);
  assert.match(modal.innerHTML, /aria-modal="true"/);
  assert.match(modal.dialog.innerHTML, /跳过指引 · 领取剩余补给/);
  assert.match(modal.dialog.innerHTML, /跳过后立即获得未领行动补给：/);
  modal.dialog.buttons.find(button => button.dataset.obAction === 'expand').onclick();
  assert.equal(screen.inert, true);
  assert.match(modal.dialog.innerHTML, /ob-modal-roadmap/);
  assert.match(modal.dialog.innerHTML, /收起目标/);
  let blocked = false;
  keydown({ key: '1', preventDefault() { blocked = true; }, stopPropagation() {} });
  assert.equal(blocked, true);
  blocked = false;
  keydown({ key: 'Enter', preventDefault() { blocked = true; }, stopPropagation() {} });
  assert.equal(blocked, false, '键盘回车仍可触发弹窗按钮');
  modal.dialog.buttons.find(button => button.dataset.obAction === 'skip').onclick();
  assert.equal(calls[0].url, '/game/onboarding/skip');
  calls[0].request.resolve(data({ done: true, skipped: true, paused: true, current: null, supplies: [] }));
  await new Promise(setImmediate);
  assert.equal(screen.inert, false);
  assert.equal(elements.onboardingBar, undefined);
  G.Onboarding.render();
  assert.equal(elements.onboardingBar, undefined);
});

test('entering a guided action unlocks the game until the next objective', async () => {
  const { G, elements, screen } = modalRuntime();
  await G.Onboarding.init();
  elements.onboardingBar.dialog.buttons.find(button => button.dataset.obAction === 'route' && button.dataset.obValue.startsWith('buildArmy')).onclick();
  assert.equal(G.Core.route, 'buildArmy');
  assert.equal(screen.inert, false);
  G.Onboarding.render();
  assert.equal(elements.onboardingBar, undefined);
  G.Onboarding.state.data.current = { id: 'train', title: '组织小队', body: '生产部队' };
  G.Onboarding.render();
  assert.equal(screen.inert, true);
  assert.ok(elements.onboardingBar);
});

test('a successful upgrade keeps the modal hidden until construction completes', async () => {
  const { G, elements, screen } = modalRuntime();
  await G.Onboarding.init();
  elements.onboardingBar.dialog.buttons.find(button => button.dataset.obValue === 'buildArmy:command').onclick();
  assert.equal(screen.inert, false);
  await G.Onboarding.actionStarted('buildArmy', 'factory');
  assert.equal(screen.inert, false, 'unrelated upgrades must not end the guided action');
  await G.Onboarding.actionStarted('buildArmy', 'command');
  assert.equal(screen.inert, false, '建筑施工期间弹窗必须保持隐藏，允许玩家加速');
  assert.equal(elements.onboardingBar, undefined);
  G.API.client.get = async () => data({ objectives: [{ id: 'base', complete: true }, { id: 'farm', complete: false }],
    current: { id: 'farm', title: '建设农田', body: '升级农田' }, checks: { command: true } });
  await G.Onboarding.refresh();
  assert.equal(screen.inert, true, '建筑完成后才重新打开弹窗');
  assert.match(elements.onboardingBar.dialog.innerHTML, /建设农田/);
  assert.doesNotMatch(elements.onboardingBar.dialog.innerHTML, /施工命令已下达/);
});

test('army production keeps the modal hidden until the unit is delivered', async () => {
  const { G, elements, screen } = modalRuntime();
  await G.Onboarding.init();
  G.Onboarding.state.data.current = { id: 'train', title: '生产卡车', body: '完成一次卡车生产' };
  G.Onboarding.render();
  elements.onboardingBar.dialog.buttons.find(button => button.dataset.obValue === 'army:truck').onclick();
  assert.equal(G.Core.route, 'army');
  assert.equal(screen.inert, false);
  await G.Onboarding.actionStarted('army', 'truck');
  assert.equal(screen.inert, false, '生产队列完成前弹窗必须保持隐藏，允许玩家加速');
  assert.equal(elements.onboardingBar, undefined);
  G.API.client.get = async () => data({ objectives: [{ id: 'train', complete: true }, { id: 'lab', complete: false }],
    current: { id: 'lab', title: '建造国防研究所', body: '建设国防研究所' }, checks: {} });
  await G.Onboarding.refresh();
  assert.equal(screen.inert, true, '部队交付后才重新打开弹窗');
  assert.match(elements.onboardingBar.dialog.innerHTML, /建造国防研究所/);
  assert.doesNotMatch(elements.onboardingBar.dialog.innerHTML, /生产命令已下达/);
});

test('technology research keeps the modal hidden until the research completes', async () => {
  const { G, elements, screen } = modalRuntime();
  await G.Onboarding.init();
  G.Onboarding.state.data.current = { id: 'reconTech', title: '研究侦察技术', body: '完成侦察技术研究' };
  G.Onboarding.render();
  elements.onboardingBar.dialog.buttons.find(button => button.dataset.obValue === 'tech:recon_level').onclick();
  assert.equal(G.Core.route, 'tech');
  assert.equal(screen.inert, false);
  await G.Onboarding.actionStarted('tech', 'recon_level');
  assert.equal(screen.inert, false, '研发队列完成前弹窗必须保持隐藏，允许玩家加速');
  assert.equal(elements.onboardingBar, undefined);
  G.API.client.get = async () => data({ objectives: [{ id: 'reconTech', complete: true }, { id: 'recon', complete: false }],
    current: { id: 'recon', title: '生产侦察机', body: '完成一次侦察机生产' }, checks: {} });
  await G.Onboarding.refresh();
  assert.equal(screen.inert, true, '科技完成后才重新打开弹窗');
  assert.match(elements.onboardingBar.dialog.innerHTML, /生产侦察机/);
  assert.doesNotMatch(elements.onboardingBar.dialog.innerHTML, /研发命令已下达/);
});

test('completion of a subtask within the same objective reopens the modal without a page refresh', async () => {
  const { G, elements, screen } = modalRuntime();
  await G.Onboarding.init();
  elements.onboardingBar.dialog.buttons.find(button => button.dataset.obValue === 'buildArmy:command').onclick();
  assert.equal(screen.inert, false);
  G.API.client.get = async () => data({ objectives: [{ id: 'base', complete: true }, { id: 'farm', complete: false }],
    current: { id: 'farm', title: '建设农田', body: '升级农田' }, checks: { command: true } });
  await G.Onboarding.refresh();
  assert.equal(screen.inert, true);
  assert.match(elements.onboardingBar.dialog.innerHTML, /建设农田/);
  assert.doesNotMatch(elements.onboardingBar.dialog.innerHTML, /升级前线指挥部/);
});

test('recommended scouting keeps the task modal hidden through travel, reports, and page reload until return', async () => {
  const { G, elements, screen, calls } = modalRuntime();
  const scout = { id: 'scout', title: '完成一次侦察', body: '等待侦察机返回城内', complete: false };
  const occupy = { id: 'occupy', title: '占领资源点', body: '占领野地', complete: false };
  const waiting = data({ objectives: [scout, occupy], current: scout, waitingForScoutReturn: true });
  G.API.client.get = async () => data({ ...waiting, waitingForScoutReturn: false });
  await G.Onboarding.init();
  assert.equal(screen.inert, true);
  const target = G.Onboarding.findTarget('scout');
  calls[0].request.resolve({ id: 17, kind: 'wild', type: 'grainfield', x: 12, y: 10 });
  await target;
  assert.equal(G.Core.route, 'dispatch');
  assert.equal(G.Core.state.world._dispatchTarget.action, 'scout');
  assert.equal(elements.onboardingBar, undefined);
  G.API.client.get = async () => waiting;
  await G.Onboarding.refresh();
  assert.equal(screen.inert, false, '出征途中不应弹出任务窗口');
  G.Core.state.reports = [{ id: 100, type: 'scout' }];
  G.Onboarding.render();
  await G.Onboarding.refresh();
  assert.equal(elements.onboardingBar, undefined, '报告送达不等于侦察机已经返城');
  await G.Onboarding.init();
  assert.equal(screen.inert, false, '刷新页面后也必须继续等待返城');
  assert.equal(elements.onboardingBar, undefined);
  G.API.client.get = async () => { throw new Error('连接中断'); };
  await G.Onboarding.refresh();
  assert.equal(elements.onboardingBar, undefined, '读取失败不能提前结束已确认的等待');
  G.API.client.get = async () => data({ objectives: [{ ...scout, complete: true }, occupy],
    current: occupy, waitingForScoutReturn: false });
  await G.Onboarding.refresh();
  assert.equal(screen.inert, true);
  assert.match(elements.onboardingBar.dialog.innerHTML, /占领资源点/);
  const modal = elements.onboardingBar;
  await G.Onboarding.refresh();
  assert.equal(elements.onboardingBar, modal, '重复轮询不应重复创建弹窗');
});

test('a recalled scout reopens the unfinished task after return, while paused guides stay hidden', async () => {
  for (const paused of [false, true]) {
    const { G, elements, screen } = modalRuntime();
    const scout = { id: 'scout', title: '完成一次侦察', body: '侦察资源点', complete: false };
    const waiting = data({ objectives: [scout], current: scout, waitingForScoutReturn: true, paused });
    G.API.client.get = async () => waiting;
    await G.Onboarding.init();
    G.Onboarding.state.snoozedFor = 'scout';
    assert.equal(elements.onboardingBar, undefined);
    G.API.client.get = async () => ({ ...waiting, waitingForScoutReturn: false });
    await G.Onboarding.refresh();
    assert.equal(screen.inert, !paused);
    assert.equal(G.Onboarding.state.data.current.id, 'scout');
    assert.equal(G.Onboarding.state.data.objectives[0].complete, false);
    if (paused) assert.equal(elements.onboardingBar, undefined);
    else assert.match(elements.onboardingBar.dialog.innerHTML, /完成一次侦察/);
  }
});

test('scout travel does not suppress earlier onboarding tasks', async () => {
  const { G, elements, screen } = modalRuntime();
  const base = { id: 'base', title: '升级前线指挥部', body: '建设基地' };
  G.API.client.get = async () => data({ objectives: [base], current: base, waitingForScoutReturn: true });
  await G.Onboarding.init();
  assert.equal(screen.inert, true);
  assert.match(elements.onboardingBar.dialog.innerHTML, /升级前线指挥部/);
  elements.onboardingBar.dialog.buttons.find(button => button.dataset.obValue === 'buildArmy:command').onclick();
  G.API.client.get = async () => data({ objectives: [base], current: base, waitingForScoutReturn: false });
  await G.Onboarding.refresh();
  assert.equal(elements.onboardingBar, undefined, '其他侦察返城不能打断当前建设操作');
});

async function reportRuntime() {
  const runtime = modalRuntime();
  runtime.G.API.client.get = async () => data({ objectives: [{ id: 'report', complete: false }],
    current: { id: 'report', title: '阅读战报', body: '阅读一次获胜的野地战报' } });
  await runtime.G.Onboarding.init();
  runtime.elements.onboardingBar.dialog.buttons.find(button => button.dataset.obValue === 'alerts:').onclick();
  return runtime;
}

test('viewing reports shows a live twenty-second countdown without blocking reading or redrawing reports', async () => {
  const { G, elements, screen, advanceReading } = await reportRuntime();
  assert.equal(G.Core.route, 'alerts');
  assert.equal(screen.inert, false);
  assert.equal(elements.onboardingBar, undefined);
  const notice = elements.onboardingReportCountdown;
  assert.match(notice.innerHTML, /后自动返回/);
  assert.ok(notice.innerHTML.includes('<button type="button" class="btn ob-report-return">返回新手指引</button>'));
  assert.equal(notice.seconds.textContent, '20s');
  elements.view.innerHTML = '正在阅读的战报';
  advanceReading(999);
  assert.equal(notice.seconds.textContent, '20s');
  advanceReading(1);
  assert.equal(notice.seconds.textContent, '19s');
  G.Core.route = 'reportDetail';
  G.Onboarding.render();
  assert.equal(elements.onboardingReportCountdown, notice);
  for (let remaining = 18; remaining >= 1; remaining--) {
    advanceReading(1000);
    assert.equal(notice.seconds.textContent, remaining + 's');
    assert.equal(elements.onboardingBar, undefined);
    assert.equal(screen.inert, false);
  }
  assert.equal(elements.view.innerHTML, '正在阅读的战报');
  G.Onboarding.stop();
});

test('a completed report poll cannot reopen onboarding until the full reading period ends', async () => {
  const { G, elements, screen, advanceReading } = await reportRuntime();
  advanceReading(5000);
  G.API.client.get = async () => data({ objectives: [{ id: 'report', complete: true }, { id: 'gather', complete: false }],
    current: { id: 'gather', title: '运回补给', body: '采集资源' } });
  await G.Onboarding.refresh();
  assert.equal(G.Onboarding.state.data.current.id, 'gather');
  assert.equal(elements.onboardingBar, undefined);
  assert.equal(screen.inert, false);
  advanceReading(14999);
  assert.equal(elements.onboardingReportCountdown.seconds.textContent, '1s');
  assert.equal(elements.onboardingBar, undefined);
  advanceReading(1);
  await new Promise(setImmediate);
  assert.equal(elements.onboardingReportCountdown, undefined);
  assert.equal(G.Onboarding.state.reportReading, null);
  assert.equal(screen.inert, true);
  assert.match(elements.onboardingBar.dialog.innerHTML, /运回补给/);
});

test('countdown expiry returns to an unfinished report task without awarding or faking completion', async () => {
  const { G, elements, calls, screen, timers, advanceReading } = await reportRuntime();
  const before = JSON.stringify(G.Onboarding.state.data);
  advanceReading(20000);
  await new Promise(setImmediate);
  assert.equal(screen.inert, true);
  assert.match(elements.onboardingBar.dialog.innerHTML, /阅读战报/);
  assert.equal(JSON.stringify(G.Onboarding.state.data), before);
  assert.equal(calls.length, 0);
  assert.equal(elements.onboardingReportCountdown, undefined);
  assert.deepEqual([...timers.values()].map(timer => timer.delay), [5000]);
  elements.onboardingBar.dialog.buttons.find(button => button.dataset.obValue === 'alerts:').onclick();
  assert.equal(elements.onboardingReportCountdown.seconds.textContent, '20s');
  assert.equal(screen.inert, false);
});

test('a throttled countdown catches up using elapsed time instead of counting timer callbacks', async () => {
  const { elements, screen, advanceReading } = await reportRuntime();
  advanceReading(7300);
  assert.equal(elements.onboardingReportCountdown.seconds.textContent, '13s');
  advanceReading(15000);
  await new Promise(setImmediate);
  assert.equal(elements.onboardingReportCountdown, undefined);
  assert.ok(elements.onboardingBar);
  assert.equal(screen.inert, true);
});

test('inactive onboarding cancels the reading notice and timer', async () => {
  for (const change of [{ paused: true }, { done: true }, { enrolled: false }]) {
    const { G, elements, timers, screen, advanceReading } = await reportRuntime();
    G.API.client.get = async () => data(change);
    await G.Onboarding.refresh();
    assert.equal(elements.onboardingReportCountdown, undefined);
    assert.equal(timers.size, 0);
    assert.equal(screen.inert, false);
    advanceReading(20000);
    assert.equal(elements.onboardingBar, undefined);
  }
});

test('pausing or skipping during report reading removes the countdown without reopening the guide', async () => {
  for (const action of ['pause', 'skip']) {
    const { G, elements, calls, timers, advanceReading } = await reportRuntime();
    const mutation = G.Onboarding.mutate(action, action === 'pause' ? { paused: true } : {});
    calls[0].request.resolve(data({ paused: true, done: action === 'skip' }));
    await mutation;
    assert.equal(elements.onboardingReportCountdown, undefined);
    assert.equal(timers.size, 0);
    advanceReading(20000);
    assert.equal(elements.onboardingBar, undefined);
  }
});

test('logout clears the reading timer and ignores queued callbacks', async () => {
  const { G, elements, timers, screen } = await reportRuntime();
  const callback = timers.get(G.Onboarding.state.reportReading.timer).callback;
  G.API.isLoggedIn = () => false;
  G.Onboarding.render();
  callback();
  assert.equal(timers.size, 0);
  assert.equal(elements.onboardingReportCountdown, undefined);
  assert.equal(elements.onboardingBar, undefined);
  assert.equal(screen.inert, false);
});

test('a stale reading timer cannot remove a new account countdown or reopen its modal', async () => {
  const { G, elements, timers, screen, advanceReading } = await reportRuntime();
  const callback = timers.get(G.Onboarding.state.reportReading.timer).callback;
  const staleReturn = elements.onboardingReportCountdown.returnButton.onclick;
  G.Core.state.player.id = 2;
  await G.Onboarding.init();
  assert.equal(elements.onboardingReportCountdown, undefined);
  assert.equal(timers.size, 1);
  elements.onboardingBar.dialog.buttons.find(button => button.dataset.obValue === 'alerts:').onclick();
  const notice = elements.onboardingReportCountdown;
  advanceReading(1000);
  callback();
  staleReturn();
  assert.equal(elements.onboardingReportCountdown, notice);
  assert.equal(notice.seconds.textContent, '19s');
  assert.equal(elements.onboardingBar, undefined);
  assert.equal(screen.inert, false);
});

test('manual return immediately opens the guide, cancels the countdown, and preserves server task progress', async () => {
  for (const complete of [false, true]) {
    const { G, elements, calls, timers, screen, advanceReading } = await reportRuntime();
    const current = complete ? { id: 'gather', title: '运回补给', body: '采集资源' }
      : { id: 'report', title: '阅读战报', body: '阅读一次获胜的野地战报' };
    const latest = data({ objectives: [{ id: 'report', complete }, { id: 'gather', complete: false }], current });
    G.API.client.get = async () => latest;
    await G.Onboarding.refresh();
    advanceReading(1000);
    assert.equal(elements.onboardingReportCountdown.seconds.textContent, '19s');
    const returnNow = elements.onboardingReportCountdown.returnButton.onclick;
    const staleTick = timers.get(G.Onboarding.state.reportReading.timer).callback;
    const before = JSON.stringify(G.Onboarding.state.data);
    const pending = deferred();
    let reads = 0;
    G.API.client.get = () => { reads++; return pending.promise; };
    const returned = returnNow();
    assert.equal(screen.inert, true, '返回按钮必须立即打开弹窗，不等待倒计时或网络响应');
    assert.ok(elements.onboardingBar.dialog.innerHTML.includes(current.title));
    assert.equal(elements.onboardingReportCountdown, undefined);
    assert.equal(G.Onboarding.state.reportReading, null);
    assert.deepEqual([...timers.values()].map(timer => timer.delay), [5000]);
    assert.equal(JSON.stringify(G.Onboarding.state.data), before, '主动返回不能伪造任务完成');
    assert.equal(calls.length, 0, '返回不会请求领取奖励或跳过指引');
    const modal = elements.onboardingBar;
    staleTick();
    returnNow();
    assert.equal(reads, 1, '重复点击只检查一次最新任务状态');
    assert.equal(elements.onboardingBar, modal);
    pending.resolve(latest);
    await returned;
    assert.ok(elements.onboardingBar.dialog.innerHTML.includes(current.title));
  }
});
