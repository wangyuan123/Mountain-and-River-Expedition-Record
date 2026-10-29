const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup() {
  const view = { innerHTML: '' };
  const requests = [];
  const row = { scrollIntoView() { this.scrolled = true; }, focus() { this.focused = true; } };
  const context = {
    document: { getElementById: () => view, querySelector: () => row },
    setInterval() {},
    Game: {
      Core: { state: { player: { id: 7 } }, route: 'rankings', views: {}, renderBackButton() {} },
      Servers: { current: () => ({ id: 'test', name: '测试大区' }) },
      escapeHtml: value => value.replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]),
      fmt: value => Math.floor(Number(value || 0)).toLocaleString('en-US'),
      API: { getLeaderboard(type, metric, page) {
        return new Promise((resolve, reject) => { requests.push({ type, metric, page, resolve, reject }); });
      } }
    }
  };
  context.window = context;
  vm.createContext(context);
  for (const file of ['constants.js', 'guild.js', 'rankings.js', 'main-view.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), context);
  }
  return { G: context.Game, view, requests, row };
}

function response(request, overrides = {}) {
  return { type: request.type, metric: request.metric, page: request.page, pageSize: 20,
    total: 0, totalPages: 1, updatedAt: Date.now(), entries: [], mine: null, ...overrides };
}

async function settle() { await new Promise(resolve => setImmediate(resolve)); }

test('战力榜自身、主指标和副指标统一除以1000，保留服务端名次及原值', async () => {
  const { G, view, requests } = setup();
  G.Core.views.rankings(view);
  G.Rankings.setMetric('formationPower');
  const entry = { id: 7, rank: 1, name: '我', prestige: 7, formationPower: 1239000000,
    overallPower: 4173000000, militaryRankName: '列兵' };
  requests[1].resolve(response(requests[1], { entries: [entry], mine: entry, total: 1 }));
  await settle();
  assert.match(view.innerHTML, /编队战力/);
  assert.ok(view.innerHTML.includes('ranking-mine-score-val">1,239,000</strong>'));
  assert.ok(view.innerHTML.includes('ranking-score"><strong>1,239,000</strong>'));
  assert.ok(view.innerHTML.includes('ranking-secondary">4,173,000</td>'));
  G.Rankings.setMetric('overallPower');
  requests[2].resolve(response(requests[2], { entries: [entry], mine: entry, total: 1 }));
  await settle();
  assert.match(view.innerHTML, /综合实力/);
  assert.ok(view.innerHTML.includes('ranking-mine-score-val">4,173,000</strong>'));
  assert.ok(view.innerHTML.includes('ranking-score"><strong>4,173,000</strong>'));
  assert.ok(view.innerHTML.includes('ranking-secondary">1,239,000</td>'));
  assert.match(view.innerHTML, /第 1 名/);
  assert.equal(G.Rankings.data.entries[0].formationPower, 1239000000);
  assert.equal(G.Rankings.data.entries[0].overallPower, 4173000000);
});

test('排名和商城在第二组保持相邻，排名路由高亮且不受城市切换模块影响', () => {
  const { G } = setup();
  G.Core.state.world = { incoming: [] };
  for (const cities of [null, { nav: () => '<div class="navitem"><span class="navlabel">切换</span></div>' }]) {
    G.Cities = cities;
    const pages = G.MainView.navBar().split('class="nav-page"').slice(1);
    assert.doesNotMatch(pages[0], /data-route="rankings"|data-route="shop"/);
    assert.match(pages[1], /class="navitem active" data-route="rankings"/);
    assert.match(pages[1], /排名<\/span><\/div><div class="navitem" data-route="shop"/);
  }
  assert.equal(G.Constants.staticRoutes.rankings, 1);
});

test('玩家排名显示真实名次、头像及自身标识，所有玩家文本均转义', async () => {
  const { G, view, requests } = setup();
  G.Core.views.rankings(view);
  assert.match(view.innerHTML, /正在加载排名/);
  const entry = { id: 7, rank: 1, name: '<script>bad</script>', prestige: 12345,
    militaryRank: 3, militaryRankName: '中士', guildName: 'A&B', avatar: 'javascript:bad' };
  requests[0].resolve(response(requests[0], { entries: [entry], mine: entry, total: 1 }));
  await settle();
  assert.match(view.innerHTML, /第 1 名/);
  assert.match(view.innerHTML, /ranking-row-mine/);
  assert.match(view.innerHTML, /ranking-top-1/);
  assert.match(view.innerHTML, /12,345/);
  assert.match(view.innerHTML, /&lt;script&gt;bad/);
  assert.match(view.innerHTML, /A&amp;B/);
  assert.doesNotMatch(view.innerHTML, /javascript:bad|<script>/);
  G.Rankings.locateMine();
  G.Core.route = 'shop';
  view.innerHTML = 'shop';
  G.Rankings.load();
  requests[1].resolve(response(requests[1]));
  await settle();
  assert.equal(view.innerHTML, 'shop');
});

test('快速切换玩家与军团榜时忽略迟到响应，并复用军团徽章', async () => {
  const { G, view, requests } = setup();
  G.Core.views.rankings(view);
  G.Rankings.setType('guilds');
  const entry = { id: 3, rank: 1, name: '远征军', icon: 'g04', leaderName: '团长', prestige: 500, members: 12 };
  requests[1].resolve(response(requests[1], { entries: [entry], total: 1 }));
  await settle();
  requests[0].resolve(response(requests[0]));
  await settle();
  assert.equal(G.Rankings.data.type, 'guilds');
  assert.match(view.innerHTML, /远征军/);
  assert.match(view.innerHTML, /img\/guild\/anchor.svg/);
  G.Rankings.setMetric('members');
  assert.equal(requests[2].metric, 'members');
  assert.equal(requests[2].page, 1);
});

test('定位自身跳至所在页后滚动并聚焦自身行', async () => {
  const { G, view, requests, row } = setup();
  G.Core.views.rankings(view);
  const mine = { id: 7, rank: 23, name: '我', prestige: 1, militaryRankName: '列兵' };
  requests[0].resolve(response(requests[0], { mine, total: 24, totalPages: 2 }));
  await settle();
  G.Rankings.locateMine();
  assert.equal(requests[1].page, 2);
  requests[1].resolve(response(requests[1], { mine, entries: [mine], total: 24, totalPages: 2 }));
  await settle();
  assert.equal(G.Rankings.page, 2);
  assert.ok(row.scrolled);
  assert.ok(row.focused);
  G.Rankings.setPage(3);
  assert.equal(requests.length, 2);
});

test('错误可重试，空榜不会伪造名次，切换账号不复用上个账号的数据', async () => {
  const { G, view, requests } = setup();
  G.Core.views.rankings(view);
  requests[0].reject(new Error('<failed>'));
  await settle();
  assert.match(view.innerHTML, /&lt;failed&gt;/);
  assert.match(view.innerHTML, /重新加载/);
  G.Rankings.load();
  requests[1].resolve(response(requests[1]));
  await settle();
  assert.match(view.innerHTML, /暂无玩家上榜/);
  assert.doesNotMatch(view.innerHTML, /第 0 名/);
  G.Rankings.load();
  G.Core.state.player.id = 9;
  G.Core.views.rankings(view);
  requests[2].resolve(response(requests[2], { mine: { id: 7, rank: 1, name: '旧账号', prestige: 0 } }));
  await settle();
  assert.equal(G.Rankings.data, null);
  requests[3].resolve(response(requests[3]));
  await settle();
  assert.doesNotMatch(view.innerHTML, /旧账号/);
});

test('刷新或异步回包重绘后，下拉框状态与收起的菜单保持一致', async () => {
  const { G, view, requests } = setup();
  G.Core.views.rankings(view);
  requests[0].resolve(response(requests[0]));
  await settle();
  G.Rankings.toggleDropdown();
  G.Rankings.load();
  assert.equal(G.Rankings.dropdownOpen, false);
  G.Rankings.toggleDropdown();
  requests[1].resolve(response(requests[1]));
  await settle();
  assert.match(view.innerHTML, /id="rankingDropdownMenu"[^>]* hidden/);
  assert.equal(G.Rankings.dropdownOpen, false);
  G.Rankings.toggleDropdown();
  assert.equal(G.Rankings.dropdownOpen, true);
});

test('自定义榜单下拉组件支持展开切换，弹层与触发器结构紧密且保持无障碍和原生联动', async () => {
  const { G, view, requests } = setup();
  G.Core.views.rankings(view);
  requests[0].resolve(response(requests[0]));
  await settle();

  // 1. 结构验证：包含自定义下拉容器、触发器、浮层与同步原生 select
  assert.match(view.innerHTML, /class="ranking-dropdown-wrap"/);
  assert.match(view.innerHTML, /id="rankingDropdownBtn"/);
  assert.match(view.innerHTML, /id="rankingDropdownMenu"/);
  assert.match(view.innerHTML, /id="rankingMetric"/);

  // 2. 状态切换测试
  assert.equal(G.Rankings.dropdownOpen, false);
  G.Rankings.toggleDropdown();
  assert.equal(G.Rankings.dropdownOpen, true);
  G.Rankings.closeDropdown();
  assert.equal(G.Rankings.dropdownOpen, false);

  // 3. 选择指标切换
  G.Rankings.selectMetric('militaryRank');
  assert.equal(G.Rankings.dropdownOpen, false);
  assert.equal(requests[1].metric, 'militaryRank');
});

test('前几名与自身渲染对应荣誉胶囊标签与榜首印章', async () => {
  const { G, view, requests } = setup();
  G.Core.views.rankings(view);
  const top1 = { id: 1, rank: 1, name: '榜首司令', prestige: 99999, militaryRank: 17, militaryRankName: '上将', guildName: '第一远征军' };
  const top2 = { id: 7, rank: 2, name: '我', prestige: 88888, militaryRank: 16, militaryRankName: '中将', guildName: '并肩作战' };
  const top3 = { id: 3, rank: 3, name: '先锋大将', prestige: 77777, militaryRank: 15, militaryRankName: '少将', guildName: '锋芒军团' };
  const top5 = { id: 5, rank: 5, name: '十强统帅名', prestige: 55555, militaryRank: 14, militaryRankName: '大校', guildName: '卫戍军团' };
  requests[0].resolve(response(requests[0], { entries: [top1, top2, top3, top5], mine: top2, total: 4 }));
  await settle();

  // 校验榜首标签与斜盖图章
  assert.match(view.innerHTML, /ranking-honor-tag tag-top1">战区榜首/);
  assert.match(view.innerHTML, /ranking-stamp-seal">【至尊统帅】/);

  // 校验第2名、第3名、第5名荣誉标签
  assert.match(view.innerHTML, /ranking-honor-tag tag-top2">战区副帅/);
  assert.match(view.innerHTML, /ranking-honor-tag tag-top3">三军先锋/);
  assert.match(view.innerHTML, /ranking-honor-tag tag-top10">十强统帅/);

  // 校验前三名专属精雕边框与名次徽章，第5名保持常规卡片
  assert.match(view.innerHTML, /ranking-row-top1/);
  assert.match(view.innerHTML, /ranking-row-top2/);
  assert.match(view.innerHTML, /ranking-row-top3/);
  assert.match(view.innerHTML, /ranking-frame-top1/);
  assert.match(view.innerHTML, /ranking-frame-top2/);
  assert.match(view.innerHTML, /ranking-frame-top3/);
  assert.match(view.innerHTML, /ranking-top-1/);
  assert.match(view.innerHTML, /ranking-top-2/);
  assert.match(view.innerHTML, /ranking-top-3/);

  // 第5名不作特殊边框处理，无 frame 图层，保持标准卡片
  assert.doesNotMatch(view.innerHTML, /ranking-row-top5/);
  assert.doesNotMatch(view.innerHTML, /ranking-frame-top5/);
  assert.doesNotMatch(view.innerHTML, /ranking-top-5/);

  // 校验自身标识依然完整保留
  assert.match(view.innerHTML, /ranking-you">我/);
});

