const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function rank(overrides = {}) {
  return {
    tier: 1, name: '列兵', baseCap: 25000, nextTier: 2, nextName: '上等兵', nextBaseCap: 50000,
    prestige: 10000, reqPrestige: 10000, prestigeEnough: true,
    npcVictories: 10, reqNpcVictories: 10, victoriesEnough: true,
    certificate: { name: '上等兵晋升凭证', owned: 1, required: 1, price: 20, source: '商城「晋升凭证」购买，20 钻石/张；NPC 不再掉落' },
    certificateEnough: true, canTakeExam: true, canPromote: false,
    exam: { status: 'not_started', questionCount: 5, requiredCorrect: 4, retryAt: 0 }, ...overrides
  };
}

function paper(overrides = {}) {
  return {
    attemptId: 'attempt-1', tier: 2, rankName: '上等兵', expiresAt: Date.now() + 900000,
    serverTime: Date.now(), requiredCorrect: 4,
    questions: Array.from({ length: 5 }, (_, i) => ({ id: String(i), prompt: '军事题目 ' + i, options: ['甲', '乙', '丙', '丁'] })),
    ...overrides
  };
}

function setup(api = {}) {
  const saved = new Map();
  const elements = Object.fromEntries(['rank-exam-clock', 'rank-exam-count', 'rank-exam-submit'].map(id => [id, {}]));
  const inputs = Array.from({ length: 20 }, () => ({ disabled: false }));
  const intervals = new Map();
  let timerId = 0;
  const toasts = [];
  const G = {
    Core: { state: { player: { id: 1 }, rankInfo: rank() }, route: 'mainQuest', views: {}, render() {} },
    DATA: { items: {} }, Constants: { equipmentNames: {} },
    API: { getQuestList: async () => ({ chapters: [] }), getRankInfo: async () => G.Core.state.rankInfo,
      startRankExam: async () => paper(), ...api },
    toast: message => toasts.push(message), fmt: String, renderMilitaryRankIcon: () => '', go() {}
  };
  const context = vm.createContext({ Game: G, Date, console,
    localStorage: { getItem: key => saved.get(key) ?? null, setItem: (key, value) => saved.set(key, value), removeItem: key => saved.delete(key) },
    setInterval: callback => { const id = ++timerId; intervals.set(id, callback); return id; },
    clearInterval: id => intervals.delete(id),
    document: { getElementById: id => elements[id], querySelectorAll: selector => selector === '.rank-exam-question input' ? inputs : [] }
  });
  context.window = context;
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/constants.js'), 'utf8'), context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/main-quest.js'), 'utf8'), context);
  const view = { innerHTML: '' };
  G.MainQuest.state.playerId = 1;
  const render = async () => {
    G.MainQuest.renderQuestView(view);
    await new Promise(resolve => setImmediate(resolve));
    return view.innerHTML;
  };
  return { G, saved, elements, inputs, intervals, toasts, view, render };
}

test('晋升面板显示四项门槛，并只使用服务器资格开启考试或授衔', async () => {
  const { G, render } = setup();
  G.Core.state.rankInfo = rank({ canTakeExam: false, certificateEnough: false });
  let html = await render();
  assert.match(html, /声望/); assert.match(html, /累计剿寇胜场/);
  assert.match(html, /上等兵晋升凭证/); assert.match(html, /军事笔试/);
  assert.match(html, /startRankExam\(\)" disabled/);
  G.Core.state.rankInfo = rank({ examPassed: true, canPromote: true });
  html = await render();
  assert.match(html, /授衔晋升/);
  assert.doesNotMatch(html, /参加军事笔试/);
});

test('缺少晋升凭证时展示商城来源和对应军衔的购买入口', async () => {
  const { G, render } = setup();
  G.Shop = { openRankCertificate() {} };
  G.Core.state.rankInfo = rank({ certificateEnough: false, canTakeExam: false });
  let html = await render();
  assert.match(html, /商城「晋升凭证」购买，20 钻石\/张/);
  assert.match(html, /Game.Shop.openRankCertificate\(2\)/);
  assert.doesNotMatch(html, /掉率/);
  G.Core.state.rankInfo = rank();
  html = await render();
  assert.doesNotMatch(html, /Game.Shop.openRankCertificate/);
});

test('晋升面板展示新胜场目标，并按服务端资格在临界值启用考试', async () => {
  const { G, render } = setup();
  for (const [nextTier, nextName, required] of [[2, '上等兵', 10], [17, '上将', 6100]]) {
    G.Core.state.rankInfo = rank({ tier: nextTier - 1, nextTier, nextName,
      npcVictories: required - 1, reqNpcVictories: required, victoriesEnough: false, canTakeExam: false });
    let html = await render();
    assert.match(html, new RegExp('累计剿寇胜场</span><b class="rank-unmet">' + (required - 1) + ' / ' + required));
    assert.match(html, /startRankExam\(\)" disabled/);
    G.Core.state.rankInfo = rank({ tier: nextTier - 1, nextTier, nextName,
      npcVictories: required, reqNpcVictories: required, victoriesEnough: true, canTakeExam: true });
    html = await render();
    assert.match(html, new RegExp('累计剿寇胜场</span><b class="rank-met">' + required + ' / ' + required));
    assert.match(html, /startRankExam\(\)">参加军事笔试/);
  }
});

test('未答完阻止交卷，返回后恢复同一次考试选项', async () => {
  let calls = 0;
  const fixed = paper();
  const { G, saved, toasts, render } = setup({ startRankExam: async () => ({ ...fixed }), submitRankExam() { calls++; } });
  await G.MainQuest.startRankExam();
  G.MainQuest.chooseAnswer(0, 2);
  G.MainQuest.submitRankExam();
  assert.equal(calls, 0); assert.match(toasts.at(-1), /完成所有题目/);
  assert.equal(JSON.parse(saved.get('rank-exam:1:attempt-1'))[0], 2);
  G.MainQuest.leaveExam();
  await G.MainQuest.startRankExam();
  assert.equal(G.MainQuest.state.answers[0], 2);
  assert.match(await render(), /value="2" checked/);
});

test('提交失败保留答案，成功后清除答案并展示服务器成绩', async () => {
  let calls = 0;
  const { G, saved, render } = setup({ submitRankExam: async (id, answers) => {
    calls++;
    assert.equal(id, 'attempt-1'); assert.equal(answers.length, 5);
    if (calls === 1) throw Error('网络中断');
    return { correct: 4, total: 5, passed: true, message: '军事笔试合格', rankInfo: rank({ canPromote: true, examPassed: true }) };
  } });
  await G.MainQuest.startRankExam();
  for (let i = 0; i < 5; i++) G.MainQuest.chooseAnswer(i, i % 4);
  await G.MainQuest.submitRankExam();
  assert.ok(G.MainQuest.state.paper); assert.ok(saved.has('rank-exam:1:attempt-1'));
  await G.MainQuest.submitRankExam();
  assert.equal(G.MainQuest.state.paper, null); assert.equal(saved.has('rank-exam:1:attempt-1'), false);
  const html = await render();
  assert.match(html, /本次成绩：4\/5/); assert.match(html, /授衔晋升/);
});

test('考试倒计时按服务器时钟，超时允许结束未答完的试卷', async () => {
  let calls = 0;
  const fixed = paper({ serverTime: Date.now() + 3600000, expiresAt: Date.now() + 3600000 + 60000 });
  const { G, elements, inputs, render } = setup({ startRankExam: async () => fixed,
    submitRankExam: async () => { calls++; return { correct: 0, total: 5, message: '考试超时', rankInfo: rank() }; } });
  await G.MainQuest.startRankExam();
  await render();
  assert.match(elements['rank-exam-clock'].textContent, /^(1分钟0秒|59秒)$/);
  G.MainQuest.state.paper.expiresAt = fixed.serverTime - 1000;
  await render();
  assert.equal(elements['rank-exam-submit'].disabled, false);
  assert.ok(inputs.every(input => input.disabled));
  await G.MainQuest.submitRankExam(); assert.equal(calls, 1);
});

test('提交中阻止重复交卷及修改答案，并废弃迟到的旧资格请求', async () => {
  let resolveRank;
  let resolveSubmit;
  let calls = 0;
  const { G, render, view, inputs } = setup({ getRankInfo: () => new Promise(resolve => { resolveRank = resolve; }),
    submitRankExam: () => { calls++; return new Promise(resolve => { resolveSubmit = resolve; }); } });
  await G.MainQuest.startRankExam();
  for (let i = 0; i < 5; i++) G.MainQuest.chooseAnswer(i, 1);
  const pending = G.MainQuest.submitRankExam();
  await render();
  assert.ok(inputs.every(input => input.disabled));
  assert.match(view.innerHTML, /type="radio"[^>]+disabled/);
  G.MainQuest.chooseAnswer(0, 3); G.MainQuest.submitRankExam();
  assert.equal(calls, 1); assert.equal(G.MainQuest.state.answers[0], 1);
  resolveSubmit({ correct: 5, total: 5, passed: true, message: '军事笔试合格', rankInfo: rank({ canPromote: true, examPassed: true }) });
  await pending;
  resolveRank(rank()); await new Promise(resolve => setImmediate(resolve));
  assert.equal(G.Core.state.rankInfo.canPromote, true);
  assert.doesNotMatch(view.innerHTML, /本次成绩：0/);
});

test('切换账号后隔离试卷与答题记录，旧账号响应不会覆盖新账号', async () => {
  let resolvePaper;
  const { G, saved, render, intervals } = setup();
  await G.MainQuest.startRankExam();
  G.MainQuest.chooseAnswer(0, 1); await render();
  assert.equal(intervals.size, 1);
  G.MainQuest.leaveExam();
  G.API.startRankExam = () => new Promise(resolve => { resolvePaper = resolve; });
  const pending = G.MainQuest.startRankExam();
  G.Core.state = { player: { id: 2 }, rankInfo: rank() };
  await render();
  resolvePaper(paper()); await pending;
  assert.equal(G.MainQuest.state.paper, null); assert.equal(intervals.size, 0);
  assert.ok(saved.has('rank-exam:1:attempt-1'));
  G.API.startRankExam = async () => paper();
  await G.MainQuest.startRankExam();
  assert.equal(G.MainQuest.state.answers[0], null);
});

test('冷却判断使用服务器时钟并显示重考时间', async () => {
  const { G, render } = setup();
  G.Core.state.rankInfo = rank({ serverTime: Date.now() + 3600000, canTakeExam: false,
    exam: { status: 'failed', questionCount: 5, requiredCorrect: 4, retryAt: Date.now() + 3660000 } });
  assert.match(await render(), /下次考试/);
});

test('损坏或越界的本地答案不能作为有效作答', async () => {
  const { G, saved } = setup();
  saved.set('rank-exam:1:attempt-1', '[4,-1,"1",null,2,3]');
  await G.MainQuest.startRankExam();
  assert.deepEqual(Array.from(G.MainQuest.state.answers), [null, null, null, null, 2]);
});
