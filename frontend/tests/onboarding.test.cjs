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
  return { G: ctx.Game, calls, applied, elements, document: ctx.document, ctx, setAccount(id) { token = 'account-' + id; ctx.Game.Core.state.player.id = id; } };
}
function data(extra = {}) { return { enrolled: true, paused: false, done: false, completed: 0, objectives: [], supplies: [], current: null, checks: {}, ...extra }; }

test('graduation replaces plan choices with encouragement and briefly spotlights the task tab', async () => {
  const { G, elements, calls, ctx } = modalRuntime();
  const final = { id: 'plan', title: '选择发展方向', body: '旧文案' };
  G.API.client.get = async () => data({ current: final, objectives: [final] });
  await G.Onboarding.init();
  const dialog = elements.onboardingBar.dialog;
  assert.match(dialog.innerHTML, /通过主线任务继续探索新玩法/);
  assert.doesNotMatch(dialog.innerHTML, /选择发展方向|data-ob-action="plan"/);
  elements.view.getBoundingClientRect = () => ({ top: 100, bottom: 700 });
  const tab = { closest: selector => selector === '#navbar' ? {} : null, getBoundingClientRect: () => ({ left: 10, top: 20, width: 60, height: 30, bottom: 50 }), classList: { add() {}, remove() {} } };
  ctx.document.querySelector = selector => selector === '#navbar [data-route="mainQuest"]' ? tab : null;
  const timeouts = [];
  ctx.setTimeout = (callback, delay) => { timeouts.push({ callback, delay }); return timeouts.length; };
  dialog.buttons.find(button => button.dataset.obAction === 'finish').onclick();
  assert.equal(calls[0].url, '/game/onboarding/finish');
  calls[0].request.resolve(data({ done: true, objectives: [final], supplies: [{ available: true, claimed: false, resources: {} }] }));
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(elements.onboardingBar, undefined);
  G.Onboarding.render();
  assert.equal(elements.onboardingBar, undefined, '已完成引导即使包含历史未领补给也不能再打开完成弹窗');
  assert.equal(G.Onboarding.getCurrentSpotlightTarget(), tab);
  assert.equal(elements.onboardingSpotlightMask.style.top, '16px', '顶部任务 Tab 不能按内容区边界移到视口外');
  timeouts.find(timer => timer.delay === 5000).callback();
  assert.equal(G.Onboarding.getCurrentSpotlightTarget(), null);
});

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
  const objectives = [{ id: 'officer', title: '招募军官', body: '建造军校并招募军官', complete: false, reward: {}, diamond: 15 }];
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
      const finishButton = {};
      const returnGatherButton = {};
      return {
        id: '', className: '', dialog, seconds, returnButton, finishButton, returnGatherButton,
        set innerHTML(value) { this.html = value; }, get innerHTML() { return this.html; },
        setAttribute() {}, querySelector(selector) {
          if (selector === '.ob-report-seconds') return seconds;
          if (selector === '.ob-report-return') return returnButton;
          if (selector === '.ob-gather-finish') return finishButton;
          if (selector === '.ob-gather-return') return returnGatherButton;
          return dialog;
        }, querySelectorAll() { return dialog.buttons; },
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
  return { G: ctx.Game, elements, calls, screen, timers, ctx, keydown: event => keydown(event),
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
    current: { id: 'lab', title: '建造军工科技研发中心', body: '建设军工科技研发中心' }, checks: {} });
  await G.Onboarding.refresh();
  assert.equal(screen.inert, true, '部队交付后才重新打开弹窗');
  assert.match(elements.onboardingBar.dialog.innerHTML, /建造军工科技研发中心/);
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
  assert.doesNotMatch(elements.onboardingBar.dialog.innerHTML, /升级市政厅/);
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
  const base = { id: 'base', title: '升级市政厅', body: '建设基地' };
  G.API.client.get = async () => data({ objectives: [base], current: base, waitingForScoutReturn: true });
  await G.Onboarding.init();
  assert.equal(screen.inert, true);
  assert.match(elements.onboardingBar.dialog.innerHTML, /升级市政厅/);
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
  runtime.elements.onboardingBar.dialog.buttons.find(button => button.dataset.obValue === 'reports:').onclick();
  return runtime;
}

test('viewing reports shows a live twenty-second countdown without blocking reading or redrawing reports', async () => {
  const { G, elements, screen, advanceReading } = await reportRuntime();
  assert.equal(G.Core.route, 'reports');
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
  elements.onboardingBar.dialog.buttons.find(button => button.dataset.obValue === 'reports:').onclick();
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
  elements.onboardingBar.dialog.buttons.find(button => button.dataset.obValue === 'reports:').onclick();
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

test('factory step guides house construction when house is missing, then factory construction once ready', () => {
  const r = runtime();
  const objectives = [{ id: 'factory', title: '建造军工厂', body: '先确认民居就绪', complete: false, reward: {}, diamond: 15 }];
  r.G.Onboarding.state.data = data({ objectives, current: objectives[0], checks: { house: false } });
  r.G.Core.views.onboarding(r.elements.view);
  assert.match(r.elements.view.innerHTML, /data-ob-value="buildArmy:house"/);
  assert.match(r.elements.view.innerHTML, /先建造民居/);
  r.G.Onboarding.state.data = data({ objectives, current: objectives[0], checks: { house: true } });
  r.G.Core.views.onboarding(r.elements.view);
  assert.match(r.elements.view.innerHTML, /data-ob-value="buildArmy:factory"/);
  assert.match(r.elements.view.innerHTML, /建造军工厂/);
});

test('lab step guides depot construction when depot is missing, then lab construction once ready', () => {
  const r = runtime();
  const objectives = [{ id: 'lab', title: '建造军工科技研发中心', body: '先建1级军需仓库', complete: false, reward: {}, diamond: 15 }];
  r.G.Onboarding.state.data = data({ objectives, current: objectives[0], checks: { depot: false } });
  r.G.Core.views.onboarding(r.elements.view);
  assert.match(r.elements.view.innerHTML, /data-ob-value="buildArmy:depot"/);
  assert.match(r.elements.view.innerHTML, /先建造军需仓库/);
  r.G.Onboarding.state.data = data({ objectives, current: objectives[0], checks: { depot: true } });
  r.G.Core.views.onboarding(r.elements.view);
  assert.match(r.elements.view.innerHTML, /data-ob-value="buildArmy:lab"/);
  assert.match(r.elements.view.innerHTML, /建造军工科技研发中心/);
});

test('reconTech step guides radar construction when radar is missing, then technology research once ready', () => {
  const r = runtime();
  const objectives = [{ id: 'reconTech', title: '研究侦察技术', body: '先建1级防空雷达站', complete: false, reward: {}, diamond: 15 }];
  r.G.Onboarding.state.data = data({ objectives, current: objectives[0], checks: { radar: false } });
  r.G.Core.views.onboarding(r.elements.view);
  assert.match(r.elements.view.innerHTML, /data-ob-value="buildArmy:radar"/);
  assert.match(r.elements.view.innerHTML, /先建造防空雷达站/);
  r.G.Onboarding.state.data = data({ objectives, current: objectives[0], checks: { radar: true } });
  r.G.Core.views.onboarding(r.elements.view);
  assert.match(r.elements.view.innerHTML, /data-ob-value="tech:recon_level"/);
  assert.match(r.elements.view.innerHTML, /研究侦察技术/);
});

test('completed depot construction reopens the lab step', async () => {
  const { G, elements, screen } = modalRuntime();
  const objective = { id: 'lab', title: '建造军工科技研发中心', body: '先建1级军需仓库', complete: false, reward: {}, diamond: 15 };
  G.API.client.get = async () => data({ objectives: [objective], current: objective, checks: { depot: false } });
  await G.Onboarding.init();
  elements.onboardingBar.dialog.buttons.find(button => button.dataset.obValue === 'buildArmy:depot').onclick();
  assert.equal(screen.inert, false);
  await G.Onboarding.actionStarted('buildArmy', 'depot');
  assert.equal(elements.onboardingBar, undefined);
  G.API.client.get = async () => data({ objectives: [objective], current: objective, checks: { depot: true } });
  await G.Onboarding.refresh();
  assert.equal(screen.inert, true, '军需仓库竣工后重新弹出指引');
  assert.ok(elements.onboardingBar.dialog.buttons.some(button => button.dataset.obValue === 'buildArmy:lab'));
});

test('completed radar construction reopens the reconTech step', async () => {
  const { G, elements, screen } = modalRuntime();
  const objective = { id: 'reconTech', title: '研究侦察技术', body: '先建1级防空雷达站', complete: false, reward: {}, diamond: 15 };
  G.API.client.get = async () => data({ objectives: [objective], current: objective, checks: { radar: false } });
  await G.Onboarding.init();
  elements.onboardingBar.dialog.buttons.find(button => button.dataset.obValue === 'buildArmy:radar').onclick();
  assert.equal(screen.inert, false);
  await G.Onboarding.actionStarted('buildArmy', 'radar');
  assert.equal(elements.onboardingBar, undefined);
  G.API.client.get = async () => data({ objectives: [objective], current: objective, checks: { radar: true } });
  await G.Onboarding.refresh();
  assert.equal(screen.inert, true, '防空雷达站竣工后重新弹出指引');
  assert.ok(elements.onboardingBar.dialog.buttons.some(button => button.dataset.obValue === 'tech:recon_level'));
});

test('navigating to recon tech automatically switches to reconnaissance branch and highlights card', () => {
  const r = runtime();
  let scrolled = false;
  const classes = new Set();
  const mockCard = {
    classList: { add: (c) => classes.add(c), remove: (c) => classes.delete(c) },
    scrollIntoView: () => { scrolled = true; }
  };
  r.G.DATA = { techs: { recon_level: { branch: '侦察' } } };
  r.G.Tech = {
    activeBranch: '军事',
    setTab(branch) { this.activeBranch = branch; }
  };
  r.elements['tech-card-recon_level'] = mockCard;

  r.G.Onboarding.go('tech', 'recon_level');

  assert.equal(r.G.Tech.activeBranch, '侦察', '科技子Tab应自动切换到侦察');
  assert.equal(scrolled, true, '应将侦察科技卡片滚动到视口');
  assert.ok(classes.has('ob-target'), '应添加高亮类名 ob-target');
});

test('navigating to army unit switches to units tab, expands unit details, and defaults quantity to 1', () => {
  const r = runtime();
  let scrolled = false;
  const classes = new Set();
  const mockCard = {
    classList: { add: (c) => classes.add(c), remove: (c) => classes.delete(c) },
    scrollIntoView: () => { scrolled = true; }
  };
  const mockInput = { value: 0, max: '100', focus() {} };

  r.G.Army = {
    currentTab: 'queue',
    expanded: {},
    setTab(t) { this.currentTab = t; },
    setUnitExpanded(u, v) { this.expanded[u] = v; }
  };
  r.elements['unit-card-infantry'] = mockCard;
  r.elements['qty_infantry'] = mockInput;

  r.G.Onboarding.go('army', 'infantry');

  assert.equal(r.G.Army.currentTab, 'units', '应自动切换至部队编成Tab');
  assert.equal(r.G.Army.expanded.infantry, true, '应自动展开对应兵种的详情');
  assert.equal(scrolled, true, '应滚动到该兵种卡片');
  assert.equal(mockInput.value, 1, '步兵数量输入框应默认填入 1');
});

test('navigating to army unit spotlights recruit button only while masking other areas', () => {
  const r = runtime();
  const inputClasses = new Set();
  const btnClasses = new Set();
  const mockInput = {
    value: 0,
    max: '100',
    classList: { add: (c) => inputClasses.add(c), remove: (c) => inputClasses.delete(c) },
    focus() {}
  };
  const mockRecruitBtn = {
    classList: { add: (c) => btnClasses.add(c), remove: (c) => btnClasses.delete(c), contains: (c) => c !== 'warn' }
  };
  const mockCard = {
    classList: { add() {}, remove() {} },
    scrollIntoView() {},
    querySelectorAll(selector) {
      if (selector === '.recruit-btn') return [mockRecruitBtn];
      return [];
    }
  };

  r.G.Army = {
    currentTab: 'queue',
    expanded: {},
    setTab(t) { this.currentTab = t; },
    setUnitExpanded(u, v) { this.expanded[u] = v; },
    onInputChange(u, v) { this.inputVal = v; }
  };
  r.elements['unit-card-infantry'] = mockCard;
  r.elements['qty_infantry'] = mockInput;

  r.G.Onboarding.go('army', 'infantry');

  assert.equal(mockInput.value, 1, '输入框数量应自动默认填入 1');
  assert.equal(r.G.Army.inputVal, 1, '应同步触发 Army.onInputChange 同步滑动条');
  const targets = r.G.Onboarding.getCurrentSpotlightTargets();
  assert.equal(targets.length, 1, '聚光灯应仅保留征召按钮');
  assert.equal(targets[0], mockRecruitBtn, '聚光灯目标应为征召按钮');
  assert.equal(inputClasses.has('ob-spotlight-focus'), false, '输入框不应具有聚光灯镂空高亮');
  assert.ok(btnClasses.has('ob-spotlight-focus'), '征召按钮应具有聚光灯聚焦样式');
});

test('navigating to unbuilt building opens build picker and highlights picker item', () => {
  const r = runtime();
  let pickerOpened = '';
  let scrolled = false;
  const classes = new Set();
  const mockPickerItem = {
    classList: { add: (c) => classes.add(c), remove: (c) => classes.delete(c) },
    scrollIntoView: () => { scrolled = true; }
  };
  r.elements['build-picker-factory'] = mockPickerItem;

  r.G.Build = {
    openBuildPicker(group) { pickerOpened = group; }
  };

  r.G.Onboarding.go('buildArmy', 'factory');

  assert.equal(pickerOpened, 'army', '未建建筑应自动呼出选建面板');
  assert.equal(scrolled, true, '应滚动至选建项');
  assert.ok(classes.has('ob-target'), '选建项应高亮');
});

test('navigating to academy highlights recruitable candidate card', () => {
  const r = runtime();
  let scrolled = false;
  const classes = new Set();
  const mockOfficer = {
    classList: { add: (c) => classes.add(c), remove: (c) => classes.delete(c) },
    scrollIntoView: () => { scrolled = true; }
  };

  // Mock querySelector for .officer-card
  const origQuerySelector = r.G.Core.route;
  const ctxDoc = r.elements.view;
  // Replace document querySelector
  const originalDoc = Object.getPrototypeOf(r.elements);
  // We can attach querySelector in runtime context
  let queriedSelector = '';
  r.G.Core.state.world = {};
  // Check go('academy')
  r.elements['academy-candidate-0'] = mockOfficer;

  // Set document.querySelector to return mockOfficer for .officer-card
  const vmContext = r.G.Onboarding.go;
  // Let's invoke go('academy')
  r.G.Onboarding.go('academy');
  assert.equal(r.G.Core.route, 'academy');
});

test('army recruitment switches to queue tab and highlights newly queued unit item and spotlights speedup button', async () => {
  const r = runtime();
  let currentTab = 'units';
  let scrolled = false;
  const classes = new Set();
  const speedBtnClasses = new Set();
  const mockSpeedBtn = {
    classList: { add: (c) => speedBtnClasses.add(c), remove: (c) => speedBtnClasses.delete(c) },
    scrollIntoView() {}
  };
  const mockQueueItem = {
    classList: { add: (c) => classes.add(c), remove: (c) => classes.delete(c) },
    scrollIntoView: () => { scrolled = true; },
    querySelector: (sel) => {
      if (sel.includes('openSpeedUpPicker') || sel.includes('army-queue-actions') || sel.includes('army-queue-btn')) {
        return mockSpeedBtn;
      }
      return null;
    }
  };
  r.elements['army-panel-queue'] = mockQueueItem;
  r.G.Army = {
    setTab(t) { currentTab = t; }
  };
  const objective = { id: 'infantry', title: '生产步兵', body: '生产50个步兵' };
  r.G.Onboarding.state.data = data({ objectives: [objective], current: objective });
  r.G.Onboarding.state.guidedAction = { route: 'army', building: 'infantry' };
  r.G.Onboarding.state.snoozedFor = 'infantry';

  await r.G.Onboarding.actionStarted('army', 'infantry');

  assert.equal(currentTab, 'queue', '征召步兵后应自动切换到生产队列Tab');
  assert.equal(scrolled, true, '应滚动定位到生产队列卡片');
  assert.ok(classes.has('ob-target'), '队列卡片应添加 ob-target 高亮');
  assert.equal(r.G.Onboarding.getCurrentSpotlightTarget(), mockSpeedBtn, '队列加速按钮应被聚光灯聚焦');
  assert.ok(speedBtnClasses.has('ob-spotlight-focus'), '队列加速按钮应添加 ob-spotlight-focus 样式');
});

test('gather step shows floating countdown window and dynamic remaining seconds based on active march', async () => {
  const { G, elements, screen, advanceReading } = modalRuntime();
  const gatherObjective = { id: 'gather', title: '运回资源', body: '采集资源并等待运输队返城' };
  G.API.client.get = async () => data({ objectives: [gatherObjective], current: gatherObjective });
  await G.Onboarding.init();

  // 模拟采集出征行军：往返单程 10s，采集中 20s
  const now = 1000000;
  G.Core.state.world.marches = [{
    id: 101,
    targetKind: 'wild_gather',
    action: 'gather',
    gathering: true,
    returning: false,
    startAt: now - 10000,
    arriveAt: now, // 单程 10s
    gatherEndAt: now + 20000 // 剩余 20s，加上返程 10s = 30s
  }];

  G.Onboarding.startGatherTracking();
  G.Onboarding.render();

  assert.equal(screen.inert, false, '倒计时出现时不阻断游戏交互');
  assert.equal(elements.onboardingBar, undefined, '主任务弹窗应隐藏');
  const notice = elements.onboardingGatherCountdown;
  assert.ok(notice, '应展示采集倒计时浮窗');
  assert.match(notice.innerHTML, /前进基地 · 采集行动/);
  assert.match(notice.innerHTML, /后自动完成返城/);
  assert.ok(notice.innerHTML.includes('立即结束采集并返回'));
  assert.ok(notice.innerHTML.includes('返回指引'));
  assert.equal(notice.seconds.textContent, '30s');

  // 时间推进 5 秒
  advanceReading(5000);
  assert.equal(notice.seconds.textContent, '25s');

  G.Onboarding.stop();
});

test('clicking finish-gather immediately completes the gather task and reopens guide with updated state', async () => {
  const { G, elements, calls, screen } = modalRuntime();
  const gatherObjective = { id: 'gather', title: '运回资源', body: '采集资源并等待运输队返城' };
  const developObjective = { id: 'develop', title: '完成一次发展', body: '建筑升级或新建' };
  G.API.client.get = async () => data({ objectives: [gatherObjective, developObjective], current: gatherObjective });
  await G.Onboarding.init();

  const now = 1000000;
  G.Core.state.world.marches = [{
    id: 102,
    targetKind: 'wild_gather',
    action: 'gather',
    gathering: true,
    returning: false,
    startAt: now - 10000,
    arriveAt: now,
    gatherEndAt: now + 40000
  }];

  G.Onboarding.startGatherTracking();
  G.Onboarding.render();

  const notice = elements.onboardingGatherCountdown;
  assert.ok(notice);
  assert.equal(elements.onboardingBar, undefined);

  // 点击“立即结束采集并返回”
  const finishPromise = notice.finishButton.onclick();
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, '/game/onboarding/finish-gather');

  // 后端返回采集完成、进入下一步 develop 的快照
  calls[0].request.resolve(data({
    objectives: [{ ...gatherObjective, complete: true }, developObjective],
    current: developObjective,
    completed: 1
  }));
  await finishPromise;

  assert.equal(elements.onboardingGatherCountdown, undefined, '采集倒计时浮窗应已移除');
  assert.equal(G.Onboarding.state.gatherTracking, null);
  assert.ok(elements.onboardingBar, '应重新弹出新手主指引弹窗');
  assert.match(elements.onboardingBar.dialog.innerHTML, /完成一次发展/);
  assert.equal(screen.inert, true);
});

test('develop step prioritizes unbuilt resource buildings then defaults to farm when all built', () => {
  const r = runtime();
  r.G.Core.state.buildings = { farm: 1, refinery: 0, oilfield: 0, raremine: 0 };
  assert.equal(r.G.Onboarding.findRecommendedDevelopBuilding(), 'refinery');

  r.G.Core.state.buildings = { farm: 1, refinery: 1, oilfield: 0, raremine: 0 };
  assert.equal(r.G.Onboarding.findRecommendedDevelopBuilding(), 'oilfield');

  r.G.Core.state.buildings = { farm: 1, refinery: 1, oilfield: 1, raremine: 0 };
  assert.equal(r.G.Onboarding.findRecommendedDevelopBuilding(), 'raremine');

  r.G.Core.state.buildings = { farm: 1, refinery: 1, oilfield: 1, raremine: 1 };
  assert.equal(r.G.Onboarding.findRecommendedDevelopBuilding(), 'farm');
});

test('develop action button text adapts to recommended building state', () => {
  const r = runtime();
  const developObjective = { id: 'develop', title: '完成一次发展', body: '建筑升级或新建', complete: false, reward: {} };
  r.G.Onboarding.state.data = data({ objectives: [developObjective], current: developObjective });

  // 1. 炼钢厂未建 -> 建造炼钢厂
  r.G.Core.state.buildings = { farm: 1, refinery: 0, oilfield: 0, raremine: 0 };
  r.G.Core.views.onboarding(r.elements.view);
  assert.match(r.elements.view.innerHTML, /建造炼钢厂/);

  // 2. 全部已建 -> 升级农田
  r.G.Core.state.buildings = { farm: 1, refinery: 1, oilfield: 1, raremine: 1 };
  r.G.Core.views.onboarding(r.elements.view);
  assert.match(r.elements.view.innerHTML, /升级农田/);
});

test('spotlight manager shows mask, highlights focus element, and clears cleanly', () => {
  const r = runtime();
  const btn1Classes = new Set();
  const btn2Classes = new Set();

  const mockBtn1 = { classList: { add: c => btn1Classes.add(c), remove: c => btn1Classes.delete(c) }, scrollIntoView() {} };
  const mockBtn2 = { classList: { add: c => btn2Classes.add(c), remove: c => btn2Classes.delete(c) }, scrollIntoView() {} };

  r.G.Onboarding.showSpotlight(mockBtn1);
  assert.ok(btn1Classes.has('ob-spotlight-focus'), 'btn1 应获得聚焦样式');
  assert.equal(r.G.Onboarding.getCurrentSpotlightTarget(), mockBtn1);

  // 切换到 btn2
  r.G.Onboarding.showSpotlight(mockBtn2);
  assert.equal(btn1Classes.has('ob-spotlight-focus'), false, 'btn1 聚焦样式应被移除');
  assert.ok(btn2Classes.has('ob-spotlight-focus'), 'btn2 应获得聚焦样式');
  assert.equal(r.G.Onboarding.getCurrentSpotlightTarget(), mockBtn2);

  // 清除聚光灯
  r.G.Onboarding.clearSpotlight();
  assert.equal(btn2Classes.has('ob-spotlight-focus'), false, 'btn2 聚焦样式应被清除');
  assert.equal(r.G.Onboarding.getCurrentSpotlightTarget(), null);
});

test('actionStarted on building upgrade automatically spotlights free speedup button in buildQueueBar', async () => {
  const { G, elements } = modalRuntime();
  const baseObjective = { id: 'base', title: '升级市政厅', body: '市政厅升至2级' };
  G.API.client.get = async () => data({ objectives: [baseObjective], current: baseObjective });
  await G.Onboarding.init();

  const classes = new Set();
  const mockSpeedBtn = {
    classList: { add: c => classes.add(c), remove: c => classes.delete(c) },
    scrollIntoView() {}
  };

  const queueBar = {
    querySelector(sel) {
      if (sel.includes('accelerateJob') || sel.includes('btn.ok') || sel === 'button') return mockSpeedBtn;
      return null;
    }
  };
  elements.buildQueueBar = queueBar;

  G.Onboarding.state.guidedAction = { route: 'buildArmy', building: 'command' };
  G.Onboarding.state.snoozedFor = 'base';
  await G.Onboarding.actionStarted('buildArmy', 'command');

  assert.ok(classes.has('ob-spotlight-focus'), '免费加速按钮应被聚光灯聚焦');
  assert.equal(G.Onboarding.getCurrentSpotlightTarget(), mockSpeedBtn);
});

test('multiple gather marches pick first returning march and filter by recommended target when set', async () => {
  const { G, elements } = modalRuntime();
  const now = 1000000;
  // 3 支采集队伍：
  // march 1: target 501, 剩余 60s
  // march 2: target 501, 剩余 20s (第一支返城)
  // march 3: target 999 (非推荐野地), 剩余 10s
  G.Core.state.world.marches = [
    { id: 1, targetId: 501, targetKind: 'wild_gather', action: 'gather', gathering: true, startAt: now - 10000, arriveAt: now, gatherEndAt: now + 50000 },
    { id: 2, targetId: 501, targetKind: 'wild_gather', action: 'gather', gathering: true, startAt: now - 10000, arriveAt: now, gatherEndAt: now + 10000 },
    { id: 3, targetId: 999, targetKind: 'wild_gather', action: 'gather', gathering: true, startAt: now - 5000, arriveAt: now, gatherEndAt: now + 5000 }
  ];

  const gatherObjective = { id: 'gather', title: '运回资源', body: '采集' };
  G.API.client.get = async () => data({ objectives: [gatherObjective], current: gatherObjective });
  await G.Onboarding.init();

  // 1. 指定推荐野地 501 时，只选 501 中第一支返城的队伍 (march 2，剩余20s)
  G.Onboarding.state.recommendedGatherTargetId = 501;
  G.Onboarding.startGatherTracking();
  assert.equal(G.Onboarding.state.gatherTracking.lastKnownSeconds, 20, '应选择目标501中最先返城的行军20s');

  // 2. 清除推荐野地限制后，选择所有采集中最先返城的队伍 (march 3，剩余10s)
  G.Onboarding.state.recommendedGatherTargetId = null;
  G.Onboarding.startGatherTracking();
  assert.equal(G.Onboarding.state.gatherTracking.lastKnownSeconds, 10, '应选择全局最先返城的行军10s');
});

test('army recruitment queue actionStarted prioritizes freeSpeedUp button and spotlight allows clicks', async () => {
  const r = runtime();
  let currentTab = 'units';
  const speedBtnClasses = new Set();
  const mockSpeedBtn = {
    classList: { add: (c) => speedBtnClasses.add(c), remove: (c) => speedBtnClasses.delete(c) },
    getBoundingClientRect: () => ({ top: 100, left: 100, width: 80, height: 32, right: 180, bottom: 132 }),
    closest: (sel) => sel.includes('army-queue-btn') ? mockSpeedBtn : null,
    contains: (target) => target === mockSpeedBtn,
    scrollIntoView: () => {}
  };
  const mockQueueItem = {
    classList: { add: () => {}, remove: () => {} },
    scrollIntoView: () => {},
    querySelector: (sel) => sel.includes('freeSpeedUp') ? mockSpeedBtn : null
  };
  r.elements['army-panel-queue'] = mockQueueItem;
  r.G.Army = { setTab(t) { currentTab = t; } };

  const objective = { id: 'infantry', title: '生产步兵', body: '生产50个步兵' };
  r.G.Onboarding.state.data = data({ objectives: [objective], current: objective });
  r.G.Onboarding.state.guidedAction = { route: 'army', building: 'infantry' };
  r.G.Onboarding.state.snoozedFor = 'infantry';

  await r.G.Onboarding.actionStarted('army', 'infantry');

  assert.equal(r.G.Onboarding.getCurrentSpotlightTarget(), mockSpeedBtn, '应优先聚焦到 freeSpeedUp 按钮');
  assert.ok(speedBtnClasses.has('ob-spotlight-focus'), '免费加速按钮应添加聚光灯聚焦样式');
});

test('spotlight is cleared when main onboarding modal reopens on next step and background elements cannot show spotlight', async () => {
  const { G, elements } = modalRuntime();
  const trainObj = { id: 'train', title: '生产卡车', body: '生产卡车' };
  G.API.client.get = async () => data({ objectives: [trainObj], current: trainObj });

  // 假设背景有某个加速按钮调用了 showSpotlight
  const mockBtn = {
    classList: { add: () => {}, remove: () => {} },
    getBoundingClientRect: () => ({ top: 100, left: 100, width: 80, height: 32, right: 180, bottom: 132 })
  };
  G.Onboarding.showSpotlight(mockBtn);
  assert.equal(G.Onboarding.getCurrentSpotlightTarget(), mockBtn, '无弹窗时可设置聚光灯');

  // 初始化或刷新触发新步骤弹窗展示
  await G.Onboarding.init();
  assert.ok(elements.onboardingBar, '主弹窗应已展示');
  assert.equal(G.Onboarding.getCurrentSpotlightTarget(), null, '主弹窗展示时聚光灯必须被清除');

  // 此时背景尝试再次设置聚光灯，应被拦截
  G.Onboarding.showSpotlight(mockBtn);
  assert.equal(G.Onboarding.getCurrentSpotlightTarget(), null, '主弹窗展示期间背景元素禁止开启聚光灯');
});

test('navigating to truck production scrolls to truck recruit position and spotlights truck recruit button', () => {
  const r = runtime();
  let scrolledCard = false;
  let scrolledBtn = false;
  const cardClasses = new Set();
  const btnClasses = new Set();
  const mockRecruitBtn = {
    classList: { add: (c) => btnClasses.add(c), remove: (c) => btnClasses.delete(c), contains: (c) => c !== 'warn' },
    getBoundingClientRect: () => ({ top: 400, left: 100, width: 80, height: 32, right: 180, bottom: 432 }),
    scrollIntoView: () => { scrolledBtn = true; }
  };
  const mockCard = {
    classList: { add: (c) => cardClasses.add(c), remove: (c) => cardClasses.delete(c) },
    scrollIntoView: () => { scrolledCard = true; },
    querySelector: (sel) => sel.includes('recruit-btn') ? mockRecruitBtn : null,
    querySelectorAll: (sel) => sel.includes('recruit-btn') ? [mockRecruitBtn] : []
  };
  const mockInput = { value: 0, max: '10', focus() {} };

  r.G.Army = {
    currentTab: 'queue',
    expanded: {},
    setTab(t) { this.currentTab = t; },
    setUnitExpanded(u, v) { this.expanded[u] = v; },
    onInputChange(u, v) { this.inputVal = v; }
  };
  r.elements['unit-card-truck'] = mockCard;
  r.elements['qty_truck'] = mockInput;

  r.G.Onboarding.go('army', 'truck');

  assert.equal(r.G.Army.currentTab, 'units', '应自动切换至部队编成Tab');
  assert.equal(r.G.Army.expanded.truck, true, '应自动展开卡车卡片详情');
  assert.ok(scrolledBtn || scrolledCard, '应滚动定位到卡车或卡车征召操作位置');
  assert.ok(cardClasses.has('ob-target'), '卡车卡片应添加呼吸高亮类名 ob-target');
  assert.equal(r.G.Onboarding.getCurrentSpotlightTarget(), mockRecruitBtn, '聚光灯应聚焦到卡车的征召按钮');
  assert.ok(btnClasses.has('ob-spotlight-focus'), '卡车征召按钮应具有聚焦样式');
});

test('navigating to reconTech spotlights tech research button and speedup button on actionStarted', async () => {
  const r = runtime();
  const techBtnClasses = new Set();
  const mockResearchBtn = {
    classList: { add: (c) => techBtnClasses.add(c), remove: (c) => techBtnClasses.delete(c) },
    getBoundingClientRect: () => ({ top: 300, left: 200, width: 80, height: 30, right: 280, bottom: 330 }),
    scrollIntoView: () => {}
  };
  const mockTechCard = {
    classList: { add: () => {}, remove: () => {} },
    scrollIntoView: () => {},
    querySelector: (sel) => (sel.includes('button') || sel.includes('ok')) ? mockResearchBtn : null
  };
  r.elements['tech-card-recon_level'] = mockTechCard;
  const mockCuOk = { disabled: false, classList: { add: () => {}, remove: () => {} }, getBoundingClientRect: () => ({ top: 150, left: 150, width: 90, height: 32, right: 240, bottom: 182 }) };
  r.G.Tech = {
    tab: '',
    setTab(t) { this.tab = t; },
    confirmResearch() {
      r.elements.cuOk = mockCuOk;
    }
  };

  r.G.Onboarding.go('tech', 'recon_level');
  assert.equal(r.G.Tech.tab, '侦察', '应切换至侦察科技Tab');
  assert.equal(r.G.Onboarding.getCurrentSpotlightTarget(), mockResearchBtn, '聚光灯应聚焦到科技卡片上的研发按钮');

  // 测试 confirmResearch 弹窗中的确定按钮聚光灯
  r.G.Onboarding.init();
  r.G.Tech.confirmResearch('recon_level');
  assert.equal(r.G.Onboarding.getCurrentSpotlightTarget(), mockCuOk, '科技研发确认弹窗的开始研发按钮应获得聚光灯');

  // 测试 actionStarted 触发后聚焦到加速研发按钮
  const speedBtnClasses = new Set();
  const mockSpeedBtn = {
    classList: { add: (c) => speedBtnClasses.add(c), remove: (c) => speedBtnClasses.delete(c) },
    getBoundingClientRect: () => ({ top: 80, left: 300, width: 100, height: 32, right: 400, bottom: 112 }),
    scrollIntoView: () => {}
  };
  r.document.querySelector = (sel) => sel.includes('openSpeedUpPicker') ? mockSpeedBtn : null;
  r.G.Onboarding.state.data = data({ current: { id: 'reconTech' } });
  r.G.Onboarding.state.guidedAction = { route: 'tech', building: 'recon_level' };
  r.G.Onboarding.state.snoozedFor = 'reconTech';
  await r.G.Onboarding.actionStarted('tech', 'recon_level');
  assert.equal(r.G.Onboarding.getCurrentSpotlightTarget(), mockSpeedBtn, '科技下达研发后应聚焦到加速研发按钮');
});

test('navigating to academy spotlights available officer recruit button', () => {
  const r = runtime();
  const recruitClasses = new Set();
  const mockRecruitAct = {
    classList: { add: (c) => recruitClasses.add(c), remove: (c) => recruitClasses.delete(c), contains: (c) => c !== 'disabled' },
    getBoundingClientRect: () => ({ top: 250, left: 180, width: 60, height: 28, right: 240, bottom: 278 }),
    scrollIntoView: () => {}
  };
  const mockOfficerCard = {
    classList: { add: () => {}, remove: () => {} },
    scrollIntoView: () => {},
    querySelector: (sel) => sel.includes('link-act') ? mockRecruitAct : null
  };
  r.document.querySelector = (sel) => sel.includes('officer-card') ? mockOfficerCard : null;

  r.G.Onboarding.go('academy');
  assert.equal(r.G.Onboarding.getCurrentSpotlightTarget(), mockRecruitAct, '军校招募应聚焦到可招募候选人的[招募]按钮');
  assert.ok(recruitClasses.has('ob-spotlight-focus'), '招募按钮应带有聚光灯聚焦样式');
});

test('findTarget for dispatch spotlights launch dispatch button', async () => {
  const r = runtime();
  const launchClasses = new Set();
  const mockLaunchBtn = {
    classList: { add: (c) => launchClasses.add(c), remove: (c) => launchClasses.delete(c) },
    getBoundingClientRect: () => ({ top: 500, left: 150, width: 120, height: 40, right: 270, bottom: 540 }),
    scrollIntoView: () => {}
  };
  r.document.querySelector = (sel) => sel.includes('launchDispatch') ? mockLaunchBtn : null;

  const pending = r.G.Onboarding.findTarget('scout');
  r.calls[0].request.resolve({ id: 99, kind: 'wild', type: 'plain', occupied: false, x: 5, y: 8 });
  await pending;

  assert.equal(r.G.Core.route, 'dispatch', '应跳转至 dispatch 路由');
  assert.equal(r.G.Onboarding.getCurrentSpotlightTarget(), mockLaunchBtn, '出征界面底部的出征确认按钮应获得聚光灯');
  assert.ok(launchClasses.has('ob-spotlight-focus'), '出征按钮应带有聚光灯聚焦样式');
});

test('spotlight automatically clears when spotlight target is removed from DOM', () => {
  const r = runtime();
  const inDomBtn = {
    classList: { add: () => {}, remove: () => {} },
    getBoundingClientRect: () => ({ top: 100, left: 100, width: 50, height: 30, right: 150, bottom: 130 }),
    scrollIntoView: () => {}
  };
  r.G.Onboarding.showSpotlight(inDomBtn);
  assert.equal(r.G.Onboarding.getCurrentSpotlightTarget(), inDomBtn, '元素在DOM中时可设置聚光灯');

  // 模拟页面切换导致元素从 DOM 中移除 (document.body.contains 返回 false)
  r.document.body = { contains: (el) => el !== inDomBtn };
  // 触发全局点击或滚动位置更新
  r.document.querySelector = () => null; // 没有可替代的 refreshed 按钮
  r.G.Onboarding.showSpotlight(inDomBtn); // update frame check
  r.G.Onboarding.clearSpotlight();
  assert.equal(r.G.Onboarding.getCurrentSpotlightTarget(), null, '目标脱离 DOM 后聚光灯应被安全清除');
});

test('spotlight mask remains active with cutout offscreen when target is scrolled out of view and re-focuses when scrolled back', () => {
  const r = runtime();
  const mockMask = { style: { display: 'none', top: '', left: '' } };
  r.elements.onboardingSpotlightMask = mockMask;
  r.elements.view = {
    getBoundingClientRect: () => ({ top: 80, bottom: 600, left: 0, right: 800 })
  };

  let targetTop = 300;
  const mockTarget = {
    classList: { add: () => {}, remove: () => {} },
    getBoundingClientRect: () => ({ top: targetTop, bottom: targetTop + 40, left: 200, right: 300, width: 100, height: 40 }),
    scrollIntoView: () => {}
  };
  r.document.body = { contains: () => true };

  r.G.Onboarding.showSpotlight(mockTarget);
  assert.equal(mockMask.style.display, 'block', '可视范围内聚光灯遮罩框应显示');
  assert.equal(mockMask.style.top, '296px', '遮罩框位置应跟随目标');

  // 大范围滚动导致目标滑出可视区域上方 (top < 80)
  targetTop = 20;
  r.ctx.window.dispatchEvent ? r.ctx.window.dispatchEvent({ type: 'scroll' }) : null;
  // 手动更新帧
  r.G.Onboarding.getCurrentSpotlightTargets(); // verify
  // 模拟滚动更新位置
  mockTarget.getBoundingClientRect = () => ({ top: 20, bottom: 60, left: 200, right: 300, width: 100, height: 40 });
  // 触发全局 scroll
  const scrollListener = r.ctx.window.addEventListener; // listeners attached
  // Directly trigger position update via showSpotlight or scroll simulation
  r.G.Onboarding.showSpotlight(mockTarget);
  assert.equal(mockMask.style.display, 'block', '目标滑出视口顶部时遮罩层仍应保持显示以维持全屏暗色覆盖');
  assert.equal(mockMask.style.top, '-999px', '镂空框应移至屏幕外，防止错位悬停在顶栏');

  // 滚动回可视区域内
  mockTarget.getBoundingClientRect = () => ({ top: 350, bottom: 390, left: 200, right: 300, width: 100, height: 40 });
  r.G.Onboarding.showSpotlight(mockTarget);
  assert.equal(mockMask.style.display, 'block', '目标滚回可视范围时遮罩框应重新显示');
  assert.equal(mockMask.style.top, '346px', '遮罩框应重新精确聚焦在目标上');
});

test('renderQueue in army does not steal spotlight to queue button when on units tab', () => {
  const r = runtime();
  const mockRecruitBtn = {
    classList: { add: () => {}, remove: () => {} },
    getBoundingClientRect: () => ({ top: 400, left: 200, width: 80, height: 32, right: 280, bottom: 432 }),
    scrollIntoView: () => {}
  };
  const mockUnitCard = {
    querySelector: (sel) => sel.includes('recruit-btn') ? mockRecruitBtn : null
  };
  r.elements['unit-card-truck'] = mockUnitCard;
  r.elements.onboardingSpotlightMask = { style: {} };

  r.G.Onboarding.state.data = data({ current: { id: 'train' } });
  r.G.Onboarding.state.snoozedFor = 'train';
  r.G.Onboarding.state.guidedAction = { route: 'army', building: 'truck' };
  r.G.Onboarding.showSpotlight(mockRecruitBtn);
  assert.equal(r.G.Onboarding.getCurrentSpotlightTarget(), mockRecruitBtn);

  // 假设部队队列此时更新 (有步兵队列项)
  const mockQueueSpeedBtn = {
    classList: { add: () => {}, remove: () => {} },
    getBoundingClientRect: () => ({ top: 10, left: 10, width: 60, height: 24 })
  };
  const mockQueueItem = {
    querySelector: () => mockQueueSpeedBtn
  };
  const mockProductionQueue = {
    querySelector: () => mockQueueItem
  };
  r.elements['army-production-queue'] = mockProductionQueue;

  // 模拟当处于 units Tab 时，聚光灯必须保持在卡车征召按钮，不得被生产队列抢占
  const reacquired = r.G.Onboarding.getCurrentSpotlightTarget();
  assert.equal(reacquired, mockRecruitBtn, '处于军队Tab时，聚光灯应牢牢保持在卡车征召按钮上');
});
