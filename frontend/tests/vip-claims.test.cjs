const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup() {
  const requests = [], toasts = [], elements = {};
  const state = { player: { id: 7, activeCityId: 10 }, resources: { gold: 500 }, items: {} };
  const updated = { player: { id: 7, activeCityId: 10 }, resources: { gold: 400500 }, items: { recruitOrd: 1 } };
  const status = { vipLevel: 1, totalRechargeCents: 600, claimedPackLevels: [],
    levels: [{ level: 0 }, { level: 1, title: '特遣队指挥官', requiredCents: 600,
      levelPackRewards: { recruitOrd: 1, gold: 400000 } }] };
  const storage = new Map([['wargame_token', 'test-token'], ['wargame_token_server', 'jiangsu-1']]);
  const game = { Core: { state, refreshTop() {} }, state, toast: message => toasts.push(message),
    escapeHtml: value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]) };
  const body = { appendChild(el) {
    elements[el.id] = el; el.parentNode = body;
    elements.vipHeroWrap = { innerHTML: '' }; elements.vipModalBody = { innerHTML: '' };
  }, removeChild(el) { delete elements[el.id]; } };
  const context = { Game: game, location: { port: '8081', hostname: 'localhost' },
    localStorage: { getItem: key => storage.get(key) || null },
    document: { body, createElement: () => ({ setAttribute() {} }),
      getElementById: key => elements[key] || null, querySelectorAll: () => [] },
    setTimeout: () => 1, clearTimeout() {}, performance: { now: () => 0 },
    fetch: async (url, options) => {
      requests.push({ path: new URL(url).pathname, method: options.method, body: options.body && JSON.parse(options.body) });
      const result = options.method === 'GET' ? status : { success: true, message: '领取成功', state: updated };
      if (options.method !== 'GET') { status.claimedPackLevels = [1]; status.dailyClaimed = true; }
      return { ok: true, status: 200, headers: { get: () => null }, text: async () => JSON.stringify(result) };
    } };
  context.window = context;
  vm.createContext(context);
  for (const file of ['constants.js', 'api-client.js', 'api.js', 'vip.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), context);
  }
  return { game, requests, toasts, elements, context };
}

const claims = [
  ['claimDailySalary', [], '/api/game/vip/daily-salary/claim', { targetCitySlot: 0 }],
  ['claimPack', [1], '/api/game/vip/level-pack/claim', { targetLevel: 1, targetCitySlot: 0 }],
  ['claimAllPacks', [], '/api/game/vip/level-pack/claim-all', { targetCitySlot: 0 }],
  ['claimMilestone', [150000], '/api/game/vip/milestone/claim', { milestoneCents: 150000, targetCitySlot: 0 }]
];
for (const [action, args, endpoint, payload] of claims) {
  test(`VIP 弹窗 ${action} 使用真实客户端提交正确接口并立即同步奖励`, async () => {
    const { game, requests, elements } = setup();
    game.Vip.openModal('packs');
    await new Promise(resolve => setImmediate(resolve));
    game.Vip[action](...args);
    game.Vip[action](...args);
    await new Promise(resolve => setImmediate(resolve));
    const writes = requests.filter(request => request.method === 'POST');
    assert.equal(writes.length, 1, '连续点击只能提交一次');
    assert.equal(writes[0].path, endpoint);
    assert.deepEqual(writes[0].body, payload);
    assert.equal(game.Core.state.resources.gold, 400500);
    assert.equal(game.Core.state.items.recruitOrd, 1);
    assert.ok(game.Vip.cachedStatus.levelPacks[0].claimed);
    assert.match(elements.vipModalBody.innerHTML, /已受领/);
    assert.equal(game.Vip.pending, false);
  });
}

test('头像框选择使用后端注册的路由和标准标识', async () => {
  const { game, requests } = setup();
  await game.API.vipSetAvatarFrame('frame-bronze-v1');
  assert.deepEqual(requests[0], {
    path: '/api/game/vip/avatar/frame', method: 'POST', body: { frameId: 'frame-bronze-v1' }
  });
});

test('VIP 领取失败保留原资产及可领状态，解除提交锁后可重试', async () => {
  const { game, context, toasts } = setup();
  game.Vip.openModal('packs');
  await new Promise(resolve => setImmediate(resolve));
  context.fetch = async () => ({ ok: false, status: 400, headers: { get: () => null },
    text: async () => JSON.stringify({ error: '该礼包尚未解锁' }) });
  game.Vip.claimPack(2);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(game.Core.state.resources.gold, 500);
  assert.equal(game.Vip.cachedStatus.levelPacks[0].claimable, true);
  assert.equal(game.Vip.pending, false);
  assert.ok(toasts.includes('该礼包尚未解锁'));
});

test('VIP 领取后的状态查询完成前不重复提交同一礼包', async () => {
  const { game } = setup();
  game.Vip.openModal('packs');
  await new Promise(resolve => setImmediate(resolve));
  let finishStatus, submissions = 0;
  game.API.vipStatus = () => new Promise(resolve => { finishStatus = resolve; });
  game.API.vipClaimPack = async () => { submissions++; return { success: true }; };
  game.Vip.claimPack(1);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(game.Vip.pending, true);
  game.Vip.claimPack(1);
  assert.equal(submissions, 1);
  finishStatus({ vipLevel: 1, claimedPackLevels: [1] });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(game.Vip.pending, false);
});

test('首页军饷提示在领取后与刷新后遵循服务端状态，未知状态不显示可领', () => {
  const { game } = setup();
  const player = game.Core.state.player;
  player.vipLevel = 1;
  assert.match(game.Vip.renderHomeCard(game.Core.state), /查看每日特勤军饷/);
  assert.doesNotMatch(game.Vip.renderHomeCard(game.Core.state), /已就绪/);
  player.vipDailySalaryClaimed = false;
  assert.match(game.Vip.renderHomeCard(game.Core.state), /每日特勤军饷已就绪/);
  game.Vip.applyStatus({ vipLevel: 1, dailyClaimed: true });
  assert.equal(player.vipDailySalaryClaimed, true);
  assert.match(game.Vip.renderHomeCard(game.Core.state), /今日特勤军饷已受领/);
  assert.match(game.Vip.renderHomeCard(game.Core.state), /查看特勤军饷/);
  assert.doesNotMatch(game.Vip.renderHomeCard(game.Core.state), /已就绪/);
  game.Vip.cachedStatus = null;
  assert.match(game.Vip.renderHomeCard(game.Core.state), /今日特勤军饷已受领/);
});
