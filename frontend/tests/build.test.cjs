const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup(realCatalog = false) {
  const timers = [];
  const state = {
    world: {},
    resources: { food: 5000, steel: 5000, oil: 5000, rare: 5000, gold: 5000 },
    buildings: {
      command: 2,
      farm: [2, 1], // slot 0: Lv.2, slot 1: Lv.1
      refinery: [1],
      radar: 1,
      guild_radar: 0,
      lab: 0,
      headquarters: 0
    },
    constructions: [],
    items: { speedUp10m: 3 }
  };

  const context = vm.createContext({
    console, Date, Math, parseInt, Array, Object, String,
    setTimeout(fn) { fn(); return 1; }, clearTimeout() {},
    setInterval(fn) { timers.push(fn); return timers.length; }, clearInterval() {},
    window: null,
    document: {
      createElement() {
        return {
          className: '',
          innerHTML: '',
          style: {},
          nodes: {},
          querySelector(selector) {
            if (!selector.startsWith('#') || !this.innerHTML.includes('id="' + selector.slice(1) + '"')) return null;
            return this.nodes[selector] || (this.nodes[selector] = { style: {} });
          },
          querySelectorAll() { return []; },
          addEventListener() {},
          appendChild() {},
          parentNode: { removeChild() {} }
        };
      },
      body: {
        appended: [],
        appendChild(el) { this.appended.push(el); }
      },
      getElementById() { return null; },
      querySelector() { return null; }
    },
    Game: {
      state,
      DATA: {
        buildings: {
          command:  { name: '市政厅', desc: '主城', baseCost: { steel: 400, food: 200 }, growth: 1.6, slots: 1 },
          lab:      { name: '军工科技研发中心', desc: '科研', baseCost: { steel: 200, food: 100, rare: 20 }, growth: 1.6, slots: 1 },
          headquarters: { name: '司令部', desc: '指挥', baseCost: { steel: 300, food: 150, gold: 150 }, growth: 1.6, slots: 1 },
          farm:     { name: '农田', desc: '粮食', baseCost: { steel: 80 }, growth: 1.5, produces: 'food', baseProduce: 600, slots: 35 },
          refinery: { name: '炼钢厂', desc: '钢铁', baseCost: { steel: 80 }, growth: 1.5, produces: 'steel', slots: 35 },
          oilfield: { name: '石油基地', desc: '石油', baseCost: { steel: 80 }, growth: 1.5, produces: 'oil', slots: 35 },
          raremine: { name: '稀矿厂', desc: '稀矿', baseCost: { steel: 120, oil: 40 }, growth: 1.6, produces: 'rare', slots: 35 },
          radar:    { name: '城防雷达', desc: '侦察', baseCost: { steel: 180, oil: 60, rare: 20 }, growth: 1.6, produces: '', slots: 1 },
          guild_radar: { name: '联防雷达', desc: '联防覆盖半径每级1格', baseCost: { steel: 180, oil: 60, rare: 20 }, growth: 1.6, slots: 1 }
        },
        groupSlots: { res: 35, army: 35 },
        resources: {
          food: { name: '粮食', icon: 'img/resources/models/food.webp' },
          steel: { name: '钢铁', icon: 'img/resources/models/steel.webp' },
          oil: { name: '石油', icon: 'img/resources/models/oil.webp' },
          rare: { name: '稀矿', icon: 'img/resources/models/rare.webp' },
          gold: { name: '黄金', icon: 'img/resources/models/gold.webp' }
        },
      },
      Core: {
        state,
        render() {},
        views: {},
        produceOf() { return 100; },
        resBonusMul() { return 1.0; },
        buildingLevels(id) {
          const v = state.buildings[id];
          if (v == null) return [];
          return Array.isArray(v) ? v : [v];
        },
        buildingLevel(id) {
          const arr = this.buildingLevels(id);
          return arr.reduce((a, b) => a + (b || 0), 0);
        },
        buildMul() { return 1.0; },
        costEnough() { return true; },
        groupSlotsCap(key) {
          return (G.DATA.groupSlots && G.DATA.groupSlots[key]) || 35;
        },
        groupSlotsUsed(key) {
          let used = 0;
          const order = (G.Build && G.Build.GROUPS && G.Build.GROUPS[key]) ? G.Build.GROUPS[key].order : [];
          for (let i = 0; i < order.length; i++) {
            const arr = this.buildingLevels(order[i]);
            for (let j = 0; j < arr.length; j++) if (arr[j] > 0) used++;
          }
          return used;
        },
        groupSlotsRemaining(key) {
          return Math.max(0, this.groupSlotsCap(key) - this.groupSlotsUsed(key));
        },
        buildingGroup(id) {
          const groups = G.Build && G.Build.GROUPS;
          for (const gk in groups) {
            if (groups[gk].order.indexOf(id) >= 0) return gk;
          }
          return null;
        }
      },
      toast(msg) { this._lastToast = msg; },
      go() {},
      fmt(n) { return String(n); },
      API: {
        buildDismantle(id, slot) {
          return Promise.resolve({ success: true, message: '开始拆除', state });
        },
        buildUpgrade(id, slot) {
          return Promise.resolve({ success: true, message: '开始升级', state });
        },
        buildCancel(id, slot) {
          return Promise.resolve({ success: true, message: '已取消施工', state });
        }
      }
    }
  });
  context.window = context;
  const G = context.Game;
  require('./load-constants.cjs')(context);
  if (realCatalog) vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/data.js'), 'utf8'), context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/build.js'), 'utf8'), context);
  return { G, context, state, timers };
}

test('建筑与升级入口使用全局按钮音效，弹窗保留可点击按钮', () => {
  const { G, context } = setup();
  G.Build.onSlotClick('farm', 1);
  const detail = context.document.body.appended.at(-1);
  assert.match(detail.innerHTML, /id="bdetailClose"/);
  detail.querySelector('#bdetailUpBtn').onclick();
  const confirmation = context.document.body.appended.at(-1);
  assert.match(confirmation.innerHTML, /id="cuOk"/);
  assert.match(confirmation.innerHTML, /id="cuCancel"/);
  assert.doesNotMatch(confirmation.innerHTML, /data-ui-sound/);
});

test('普通建筑保留24小时上限，关键建筑按专属曲线展示超过24小时的工期', () => {
  const { G, context, state } = setup();
  state.buildings.command = 10;
  state.buildings.lab = 9;
  state.buildings.headquarters = 9;

  G.Build.onSlotClick('farm', 1);
  assert.match(context.document.body.appended.at(-1).innerHTML, /升级至 Lv\.2[\s\S]*耗时: 1分钟12秒/);

  state.buildings.command = 9;
  G.Build.onSlotClick('command', null);
  assert.match(context.document.body.appended.at(-1).innerHTML, /升级至 Lv\.10[\s\S]*耗时: 2天15小时34分/);

  state.buildings.command = 10;
  G.Build.onSlotClick('lab', null);
  assert.match(context.document.body.appended.at(-1).innerHTML, /升级至 Lv\.10[\s\S]*耗时: 2天19小时52分/);

  G.Build.onSlotClick('headquarters', null);
  assert.match(context.document.body.appended.at(-1).innerHTML, /升级至 Lv\.10[\s\S]*耗时: 1天23小时41分/);
});

test('免费加速按钮在无道具时仍可用，超过门槛仍需道具', () => {
  const { G, state, context } = setup();
  context.Date = class extends Date { static now() { return 1000000; } };
  state.items = {};
  state.freeBuildSpeedUpSeconds = 300;
  const job = { id: 'farm', slot: 0, queueId: 42, finishesAt: 1300000 };
  assert.equal(G.Build.canFreeSpeedUp(job), true);
  assert.match(G.Build.renderQueueHtml([job], state), /免费加速/);
  assert.doesNotMatch(G.Build.renderQueueHtml([job], state), /disabled/);
  job.finishesAt++;
  assert.equal(G.Build.canFreeSpeedUp(job), false);
  assert.doesNotMatch(G.Build.renderQueueHtml([job], state), /免费加速/);
  assert.match(G.Build.renderQueueHtml([job], state), /disabled/);
  state.items.speedUp10m = 1;
  assert.doesNotMatch(G.Build.renderQueueHtml([job], state), /disabled/);
});

test('所有施工详情和满员弹窗在倒计时进入五分钟时切换免费按钮', () => {
  for (const view of ['showJobDetails', 'onSlotClick', 'showBusyJobs']) {
    const { G, state, context, timers } = setup();
    let now = 1000000;
    context.Date = class extends Date { static now() { return now; } };
    state.items = {};
    state.constructions = [{ id: 'farm', slot: 0, queueId: 42, startedAt: 990000,
      finishesAt: 1301000, targetLevel: 3 }];
    G.Build[view]('farm', 0);
    const modal = context.document.body.appended.at(-1);
    const selector = view === 'showJobDetails' ? '#detailSpeedUp' : view === 'onSlotClick' ? '#bdetailSpeedBtn' : '#busySpeedUp0';
    now += 1001;
    timers.forEach(fn => fn());
    const button = modal.querySelector(selector);
    assert.equal(button.disabled, false);
    assert.equal(button.textContent, '⚡ 免费加速');
  }
});

test('免费加速精确定位队列，连点只发一次请求，失败后可重试', async () => {
  const { G, state } = setup();
  state.constructions = [{ id: 'farm', queueId: 42, finishesAt: Date.now() + 60000 }];
  let resolve;
  let calls = 0;
  let renders = 0;
  G.Core.render = () => { renders++; };
  G.API.buildFreeSpeedUp = (id) => {
    assert.equal(id, 42);
    calls++;
    return new Promise(done => { resolve = done; });
  };
  G.Build.openSpeedUpPicker = () => assert.fail('免费加速不应打开道具选择器');
  const first = G.Build.accelerateJobById(42);
  assert.equal(G.Build.accelerateJobById(42), first);
  assert.equal(calls, 1);
  resolve({ success: false, message: '时间尚未满足' });
  await first;
  assert.equal(G._lastToast, '时间尚未满足');
  const retry = G.Build.accelerateJobById(42);
  resolve({ success: true });
  await retry;
  assert.equal(calls, 2);
  assert.equal(renders, 2);
  G.Build.accelerateJobById(999);
  assert.equal(calls, 2);
});

test('成功下达升级命令后通知新手指引，失败升级不重新弹出', async () => {
  const { G } = setup();
  const resumed = [];
  G.Core.route = 'buildArmy';
  G.Onboarding = { actionStarted(route, building) { resumed.push([route, building]); } };
  G.Build.upgrade('command', 0);
  await new Promise(setImmediate);
  assert.deepEqual(resumed, [['buildArmy', 'command']]);
  G.API.buildUpgrade = async () => ({ success: false, message: '资源不足' });
  G.Build.upgrade('command', 0);
  await new Promise(setImmediate);
  assert.deepEqual(resumed, [['buildArmy', 'command']]);
});

test('免费加速接口发送队列ID并立即应用完工后的服务端状态', async () => {
  const { G, state, context } = setup();
  const completedState = { ...state, buildings: { ...state.buildings, command: 3 },
    constructions: [], freeBuildSpeedUpSeconds: 300 };
  G.ApiClient = function () {
    this.post = async (url, payload) => {
      assert.equal(url, '/game/build/free-speedup');
      assert.equal(payload.queueId, 42);
      assert.equal(Object.keys(payload).length, 1);
      return { success: true, state: completedState };
    };
  };
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/api.js'), 'utf8'), context);
  await G.Build.freeSpeedUpJob({ queueId: 42 });
  assert.equal(G.Core.state, completedState);
  assert.equal(G.Core.state.buildings.command, 3);
  assert.equal(G.Core.state.constructions.length, 0);
});

test('卡槽地块网格正确渲染已建建筑与空闲卡槽(+号)', () => {
  const { G, state } = setup();
  // 卡槽容量固定为35，不随市政厅等级变化
  const groupCap = G.Core.groupSlotsCap('res');
  assert.equal(groupCap, 35);

  // farm has [2, 1] (2 slots), refinery has [1] (1 slot) -> 3 occupied slots
  const gridHtml = G.Build.renderSlotGrid('res', [], state);
  assert.match(gridHtml, /class="slot-overview-panel"/);
  assert.match(gridHtml, /已用 3 \/ 35 卡槽/);
  assert.match(gridHtml, /农田/);
  assert.match(gridHtml, /炼钢厂/);

  // Should have empty slots with '+' buttons
  assert.match(gridHtml, /class="slot-card slot-card-empty"/);
  assert.match(gridHtml, /class="slot-card-plus">\+<\/div>/);
  assert.match(gridHtml, /选建建筑/);

  // Total empty slot cards should be 35 - 3 = 32
  const emptyMatches = gridHtml.match(/class="slot-card slot-card-empty"/g);
  assert.equal(emptyMatches.length, 32);
});

test('建筑卡片渲染拆除按钮', () => {
  const { G, state } = setup();
  const farmCardHtml = G.Build.renderBuilding('farm', G.DATA.buildings.farm, [], 1);
  assert.match(farmCardHtml, /class="btn sm warn btn-dismantle"/);
  assert.match(farmCardHtml, /Game\.Build\.confirmDismantle\('farm',0\)/);
  assert.match(farmCardHtml, /Game\.Build\.confirmDismantle\('farm',1\)/);
});

test('市政厅不可被拆除', () => {
  const { G } = setup();
  G.Build.confirmDismantle('command', null);
  assert.equal(G._lastToast, '市政厅为核心枢纽，不可拆除');
});

test('队列渲染显示拆除中状态', () => {
  const { G, state } = setup();
  const jobs = [
    { id: 'farm', slot: 0, targetLevel: 1, fromLevel: 2, action: 'dismantle', finishesAt: Date.now() + 60000 },
    { id: 'refinery', slot: 0, targetLevel: 0, fromLevel: 1, action: 'dismantle', finishesAt: Date.now() + 30000 }
  ];
  const queueHtml = G.Build.renderQueueHtml(jobs, state);
  assert.match(queueHtml, /农田 #1 \(拆除中: 降至 Lv\.1\)/);
  assert.match(queueHtml, /炼钢厂 #1 \(拆除中: 移除\)/);
});

test('三支施工队的空闲提示随工程数量更新', () => {
  const { G, state } = setup();
  assert.match(G.Build.renderQueueHtml([], state), /3 支施工队空闲：可同时进行 3 项建筑工程/);
  const jobs = Array.from({ length: 3 }, (_, slot) => ({
    id: 'farm', slot, targetLevel: 1, finishesAt: Date.now() + 60000
  }));
  assert.match(G.Build.renderQueueHtml(jobs.slice(0, 1), state), /剩余 2 支施工队空闲/);
  assert.match(G.Build.renderQueueHtml(jobs.slice(0, 2), state), /剩余 1 支施工队空闲/);
  const fullHtml = G.Build.renderQueueHtml(jobs, state);
  assert.match(fullHtml, /施工队 3：/);
  assert.doesNotMatch(fullHtml, /空闲/);
});

test('不足三项工程可确认升级和拆除，三项及以上显示满员提示', () => {
  for (const count of [1, 2, 3, 6]) {
    for (const action of ['confirmUpgrade', 'confirmDismantle']) {
      const { G, state, context } = setup();
      state.constructions = Array.from({ length: count }, (_, slot) => ({
        id: 'oilfield', slot, targetLevel: 1, finishesAt: Date.now() + 60000
      }));
      G.Build[action]('farm', 1);
      const html = context.document.body.appended.at(-1).innerHTML;
      if (count < 3) {
        assert.doesNotMatch(html, /施工队全忙/);
        assert.match(html, action === 'confirmUpgrade' ? /升级确认/ : /拆除建筑确认/);
        assert.ok(html.includes(G.resourceIconHtml('steel')));
      } else {
        assert.match(html, /施工队全忙/);
        assert.match(html, /3 支施工队均在作业中/);
      }
      G.Build.onSlotClick('farm', 1);
      const detailHtml = context.document.body.appended.at(-1).innerHTML;
      if (count < 3) {
        assert.doesNotMatch(detailHtml, /施工队全忙/);
      } else {
        assert.ok(detailHtml.includes('施工队全忙 (' + count + '/3)'));
      }
    }
  }
});

test('renderGroup 不再渲染下方冗余的手风琴卡片列表', () => {
  const { G } = setup();
  const dummyEl = { innerHTML: '' };
  G.Build.renderGroup(dummyEl, 'res');
  // 确认包含卡槽地块总览
  assert.match(dummyEl.innerHTML, /class="slot-overview-panel"/);
  // 确认不再包含原有的 === 资源 === 标题和折叠卡片
  assert.doesNotMatch(dummyEl.innerHTML, /=== 资源 ===/);
  assert.doesNotMatch(dummyEl.innerHTML, /class="bcard"/);
});

test('资源区与军事区底部入口使用同款文字链接并正确跳转', () => {
  const { G } = setup();
  for (const [group, destination, label] of [
    ['res', 'buildArmy', '军工区'],
    ['army', 'buildRes', '后勤区']
  ]) {
    const element = { innerHTML: '' };
    G.Build.renderGroup(element, group);
    assert.ok(element.innerHTML.includes('onclick="Game.go(\'' + destination + '\')"'));
    assert.ok(element.innerHTML.includes('>' + label + ' &gt;</span>'));
    assert.match(element.innerHTML, /class="build-zone-link zone-head-action" role="button" tabindex="0"/);
    assert.doesNotMatch(element.innerHTML, /前往(?:后勤|军工)/);
  }
});

test('点击卡槽建筑弹出详情弹窗，包含资源产出、升级和拆除功能', () => {
  const { G, context } = setup();
  // 1. 测试 slot 1 (Lv.1，未满级，可升级到 Lv.2)
  G.Build.onSlotClick('farm', 1);
  const appended = context.document.body.appended;
  assert.ok(appended.length > 0);
  const modalHtml = appended[appended.length - 1].innerHTML;

  assert.match(modalHtml, /class="modal-card bdetail-modal"/);
  assert.match(modalHtml, /#2 农田/);
  assert.match(modalHtml, /Lv\.1/);
  assert.match(modalHtml, /本建筑产出/);
  assert.match(modalHtml, /全城该类产出/);
  assert.match(modalHtml, /升级至 Lv\.2/);
  assert.match(modalHtml, /id="bdetailUpBtn"/);
  assert.match(modalHtml, /id="bdetailDisBtn"/);
  assert.match(modalHtml, /拆除完成后建筑彻底消失，并释放该卡槽地块/);

  // 2. 测试 slot 0 (Lv.2，达市政厅上限，拆除降为 Lv.1)
  G.Build.onSlotClick('farm', 0);
  const modalHtml2 = appended[appended.length - 1].innerHTML;
  assert.match(modalHtml2, /#1 农田/);
  assert.match(modalHtml2, /已达当前市政厅限制上限/);
  assert.match(modalHtml2, /拆除完成后建筑等级降为 Lv\.1/);
});

test('市政厅详情弹窗中显示防拆提示且无拆除按钮', () => {
  const { G, context } = setup();
  G.Build.onSlotClick('command', null);
  const appended = context.document.body.appended;
  const modalHtml = appended[appended.length - 1].innerHTML;

  assert.match(modalHtml, /市政厅/);
  assert.match(modalHtml, /市政厅为主城核心枢纽，不可拆除/);
  assert.doesNotMatch(modalHtml, /id="bdetailDisBtn"/);
});

test('施工中建筑在详情弹窗中展示倒计时、进度条、加速与取消按钮', () => {
  const { G, context, state } = setup();
  state.constructions = [
    { id: 'farm', slot: 0, targetLevel: 3, startedAt: Date.now() - 10000, finishesAt: Date.now() + 50000, action: 'upgrade' }
  ];
  G.Build.onSlotClick('farm', 0);
  const appended = context.document.body.appended;
  const modalHtml = appended[appended.length - 1].innerHTML;

  assert.match(modalHtml, /正在施工升级/);
  assert.match(modalHtml, /目标 Lv\.3/);
  assert.match(modalHtml, /id="bdetailSpeedBtn"/);
  assert.match(modalHtml, /id="bdetailCancelBtn"/);
});

test('资源建筑显示对应设施的花园卫城模型', () => {
  const { G, state } = setup();
  const gridHtml = G.Build.renderSlotGrid('res', [], state);
  assert.match(gridHtml, /<img class="b-icon-img" src="img\/buildings\/garden\/farm\.webp" alt="农田"/);
  assert.match(gridHtml, /<img class="b-icon-img" src="img\/buildings\/garden\/refinery\.webp" alt="炼钢厂"/);
  for (const id of ['farm', 'refinery', 'oilfield', 'raremine']) {
    assert.equal(G.Build.BUILD_ICON[id], `img/buildings/garden/${id}.webp`);
  }
});

test('军事建筑显示花园卫城模型，全部 26 个映射复用实际素材', () => {
  const { G, state } = setup();
  const armyBuildings = [
    'command', 'house', 'factory', 'lightfactory', 'heavyfactory',
    'airport', 'port', 'shipyard', 'academy', 'staff', 'headquarters', 'lab', 'radar', 'guild_radar', 'wall',
    'apron', 'liaison', 'depot', 'transit', 'hospital', 'exchange', 'prisoner_camp'
  ];
  for (const id of armyBuildings) {
    assert.equal(G.Build.BUILD_ICON[id], `img/buildings/garden/${id}.webp`);
  }
  assert.equal(Object.keys(G.Build.BUILD_ICON).length, 26);
  assert.equal(G.Build.BUILD_ICON.guild_radar, 'img/buildings/garden/guild_radar.webp');
  assert.equal(G.Build.BUILD_ICON.shipyard, 'img/buildings/garden/shipyard.webp');
  for (const asset of Object.values(G.Build.BUILD_ICON)) {
    assert.ok(fs.existsSync(path.join(__dirname, '..', asset)), `缺少建筑素材：${asset}`);
  }
  const armyGridHtml = G.Build.renderSlotGrid('army', [], state);
  assert.match(armyGridHtml, /<img class="b-icon-img" src="img\/buildings\/garden\/command\.webp" alt="市政厅"/);
  assert.match(armyGridHtml, /<img class="b-icon-img" src="img\/buildings\/garden\/radar\.webp" alt="城防雷达"/);
});

test('雷达建筑目录保留旧键并新增相邻的单栋联防雷达，造价与分类相同', () => {
  const { G } = setup(true);
  assert.equal(G.DATA.buildings.radar.name, '城防雷达');
  assert.equal(G.DATA.buildings.guild_radar.name, '联防雷达');
  for (const id of ['radar', 'guild_radar']) {
    const building = G.DATA.buildings[id];
    assert.deepEqual({ ...building.baseCost }, { steel: 180, oil: 60, rare: 20 });
    assert.equal(building.growth, 1.6);
    assert.equal(building.cat, 'core');
    assert.equal(building.slots, 1);
  }
  const order = G.Build.GROUPS.army.order;
  assert.equal(order.indexOf('guild_radar'), order.indexOf('radar') + 1);
  assert.equal(G.DATA.groupSlots.army, 35);
});

test('城防与联防雷达卡片及详情各自读取建筑等级，情报明细由科技控制', () => {
  const { G, context, state } = setup(true);
  state.buildings.command = 10;
  for (const [cityLevel, guildLevel] of [[0, 0], [10, 0], [0, 1], [4, 7], [10, 10]]) {
    state.buildings.radar = cityLevel;
    state.buildings.guild_radar = guildLevel;
    for (const techLevel of [0, 10]) {
      state.tech = { recon_radar: techLevel };
      const cityCard = G.Build.renderBuilding('radar', G.DATA.buildings.radar, [], 0);
      const guildCard = G.Build.renderBuilding('guild_radar', G.DATA.buildings.guild_radar, [], 0);
      assert.ok(cityCard.includes('侦察视野</span><span class="bcard-stat-val">+' + cityLevel + ' 格'));
      assert.ok(guildCard.includes('联防覆盖半径</span><span class="bcard-stat-val">' + guildLevel + ' 格'));
      assert.doesNotMatch(cityCard, /bcard-stat-name">联防覆盖半径/);
      assert.doesNotMatch(guildCard, /bcard-stat-name">侦察视野/);
      for (const id of ['radar', 'guild_radar']) {
        const level = state.buildings[id];
        const card = id === 'radar' ? cityCard : guildCard;
        assert.match(card, /由雷达预警科技等级决定/);
        assert.ok(card.includes('data-building="' + id + '"'));
        if (level < 10) assert.ok(card.includes("Game.Build.confirmUpgrade('" + id + "')"));
        else assert.match(card, /已达最大等级/);
        G.Build.onSlotClick(id, null);
        const detail = context.document.body.appended.at(-1).innerHTML;
        assert.match(detail, /由雷达预警科技等级决定，与建筑等级无关/);
        if (id === 'radar') {
          assert.ok(detail.includes('+' + cityLevel + ' 格'));
          assert.match(detail, /不增加战斗属性或联防覆盖/);
          assert.doesNotMatch(detail, /bdetail-stat-lbl">联防覆盖半径/);
        } else {
          assert.ok(detail.includes('联防覆盖半径</span><span class="bdetail-stat-val">' + guildLevel + ' 格'));
          assert.match(detail, /未入团时暂无区域军情/);
          assert.doesNotMatch(detail, /侦察视野加成/);
          if (level < 10) assert.ok(detail.includes('(下级 ' + (guildLevel + 1) + ' 格)'));
          else assert.doesNotMatch(detail, /下级 11 格|升级至 Lv\.11/);
        }
      }
    }
  }
});

test('两类雷达的选建项独立，满足服务端建筑前置后无需军团即可建造', () => {
  const { G, context, state } = setup(true);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/prerequisites.js'), 'utf8'), context);
  G.Prerequisites.setRules(JSON.parse(fs.readFileSync(path.join(__dirname,
    '../../backend/src/main/resources/game/tech-building-prerequisites.json'), 'utf8')));
  state.buildings.command = 10;
  state.buildings.lab = 5;
  state.buildings.liaison = 5;
  for (const [cityLevel, guildLevel] of [[0, 0], [10, 0], [0, 10], [10, 10]]) {
    state.buildings.radar = cityLevel;
    state.buildings.guild_radar = guildLevel;
    G.Build.openBuildPicker('army');
    const html = context.document.body.appended.at(-1).innerHTML;
    for (const id of ['radar', 'guild_radar']) {
      assert.equal(html.includes('id="build-picker-' + id + '"'), state.buildings[id] === 0);
      if (state.buildings[id] === 0) assert.ok(html.includes("Game.Build._pickAndBuild('" + id + "')"));
    }
  }
});

test('联防雷达选建项展示服务端缺失的本城前置', () => {
  const { G, context, state } = setup(true);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/prerequisites.js'), 'utf8'), context);
  G.Prerequisites.setRules(JSON.parse(fs.readFileSync(path.join(__dirname,
    '../../backend/src/main/resources/game/tech-building-prerequisites.json'), 'utf8')));
  state.buildings.command = 3;
  state.buildings.lab = 1;
  state.buildings.liaison = 0;
  G.Build.openBuildPicker('army');
  const html = context.document.body.appended.at(-1).innerHTML;
  const guildItem = html.slice(html.indexOf('id="build-picker-guild_radar"'), html.indexOf('id="build-picker-wall"'));
  assert.match(guildItem, /前置未达成：需 .*联络中心/);
  assert.ok(guildItem.includes("Game.Build._pickBlocked('guild_radar', 'prereq')"));
});

test('迁移后的36栋军事建筑完整展示，仍以35槽限制新建', () => {
  const { G, state } = setup(true);
  state.buildings = { command: 10, house: Array(32).fill(1), radar: 10, guild_radar: 10, lab: 5 };
  const before = JSON.stringify(state.buildings);
  const html = G.Build.renderSlotGrid('army', [], state);
  assert.match(html, /已用 36 \/ 35 卡槽/);
  assert.equal((html.match(/slot-card-occupied/g) || []).length, 36);
  assert.match(html, /data-building="radar"/);
  assert.match(html, /data-building="guild_radar"/);
  assert.doesNotMatch(html, /slot-card-empty/);
  G.Build.confirmUpgrade('liaison', null);
  assert.match(G._lastToast, /军工建筑已达上限35栋/);
  assert.equal(JSON.stringify(state.buildings), before);
});

test('renderBuildingIcon 支持 WebP、SVG 图片路径及 emoji 降级', () => {
  const { G } = setup();
  const modelImg = G.Build.renderBuildingIcon(G.Build.BUILD_ICON.farm, '农田');
  assert.match(modelImg, /src="img\/buildings\/garden\/farm\.webp" alt="农田"/);
  const svgImg = G.Build.renderBuildingIcon('img/items/shield-8h.svg', '8小时护盾', 'custom-cls');
  assert.equal(svgImg, '<img class="b-icon-img custom-cls" src="img/items/shield-8h.svg" alt="8小时护盾" draggable="false"/>');

  const emojiText = G.Build.renderBuildingIcon('⚔️', '战斗');
  assert.equal(emojiText, '⚔️');

  const defaultText = G.Build.renderBuildingIcon(null);
  assert.equal(defaultText, '🏗');
});

test('单栋建筑与多栋建筑卡槽头部标签行高保持一致不塌陷', () => {
  const { G, state } = setup();
  const armyGridHtml = G.Build.renderSlotGrid('army', [], state);
  // 市政厅(单栋)应当包含 &nbsp; 防塌陷占位
  assert.match(armyGridHtml, /<span class="slot-card-tag">&nbsp;<\/span>/);
  // 多栋建筑或空闲卡槽应当包含 # 编号
  assert.match(armyGridHtml, /<span class="slot-card-tag">#\d+<\/span>/);
});

test('openBuildPicker 在资源不足时显示缺口提示并支持点击置灰按钮获得明确引导', () => {
  const { G, state, context } = setup();
  G.DATA.buildings.house = {
    name: '民居',
    desc: '驻扎战备部队,提供人口与兵员上限,每级+1200',
    baseCost: { steel: 120, food: 60 },
    growth: 1.5,
    slots: 35
  };
  G.Build.GROUPS.army.order.push('house');

  // 模拟当前玩家：钢铁充足(1000)，但粮食不足(10 < 60)
  state.resources.steel = 1000;
  state.resources.food = 10;
  state.buildings.house = [];

  const toasts = [];
  G.toast = (msg) => toasts.push(msg);

  G.Build.openBuildPicker('army');

  const modalEl = context.document.body.appended.at(-1);
  const html = modalEl.innerHTML;

  // 1. 标题与建筑存在
  assert.match(html, /选择建筑建造/);
  assert.match(html, /民居/);

  // 2. 粮食不足被醒目标红并注明缺少数量
  assert.match(html, /class="bcost-item bcost-lack"/);
  assert.match(html, /缺50/);

  // 3. 展现缺少粮食的提示条
  assert.match(html, /build-picker-tip lack/);
  assert.match(html, /缺少粮食 50/);

  // 4. 按钮为置灰样式（带 disabled 类名），但保留点击能力（不包含原生 disabled 属性）
  assert.match(html, /class="btn sm ok disabled"/);
  assert.match(html, /title="资源不足：粮食缺少50"/);
  assert.match(html, /onclick="Game\.Build\._pickBlocked\('house', 'resource'\)"/);

  // 5. 点击置灰按钮触发 _pickBlocked，给出明确 Toast 提示并打开确认弹窗供对照
  G.Build._pickBlocked('house', 'resource');
  assert.ok(toasts.some(t => t.includes('无法建造【民居】') && t.includes('粮食缺 50')));

  // 确认弹窗被呼出
  const confirmModal = context.document.body.appended.at(-1);
  assert.match(confirmModal.innerHTML, /新建确认/);
  assert.match(confirmModal.innerHTML, /民居/);
});

test('本城市政厅满级展示15块野地，升级前显示下一等级名额', () => {
  const { G, context, state } = setup();
  state.buildings.command = 10;
  G.Build.onSlotClick('command', null);
  let modalHtml = context.document.body.appended.at(-1).innerHTML;
  assert.match(modalHtml, /本城野地上限/);
  assert.match(modalHtml, /15（基础5＋市政厅等级）/);
  assert.doesNotMatch(modalHtml, /下级 16/);
  assert.match(G.Build.renderBuilding('command', G.DATA.buildings.command, [], 0), /本城野地上限<\/span><span class="bcard-stat-val">15<\/span>/);
  state.buildings.command = 1;
  G.Build.onSlotClick('command', null);
  modalHtml = context.document.body.appended.at(-1).innerHTML;
  assert.match(modalHtml, /6（基础5＋市政厅等级），下级 7/);
});

test('作战参谋部展示战略指挥枢纽，野地名额归市政厅管理', () => {
  const { G, context, state } = setup();
  G.DATA.buildings.staff = { name: '作战参谋部', desc: '军事指挥、军官管理与高级科技', baseCost: { steel: 220, food: 100 }, growth: 1.6, slots: 1 };
  state.buildings.staff = 10;
  G.Build.onSlotClick('staff', null);
  const appended = context.document.body.appended;
  const modalHtml = appended[appended.length - 1].innerHTML;

  assert.match(modalHtml, /作战参谋部/);
  assert.doesNotMatch(modalHtml, /野地.*上限/);
  assert.match(modalHtml, /战略指挥枢纽/);
  assert.doesNotMatch(modalHtml, /带兵容量加成/);

  // 详情与卡片都移除参谋部的旧野地名额说明。
  const cardHtml = G.Build.renderBuilding('staff', G.DATA.buildings.staff, [], 0);
  assert.doesNotMatch(cardHtml, /野地.*上限/);
  assert.doesNotMatch(cardHtml, /带兵容量加成/);
});

test('联络中心不显示废弃的军官刷新品质，展示情报联络枢纽', () => {
  const { G, context, state } = setup();
  G.DATA.buildings.liaison = { name: '联络中心', desc: '盟军情报与外交联络', baseCost: { steel: 200, food: 120, gold: 200 }, growth: 1.6, slots: 1 };
  state.buildings.liaison = 5;
  G.Build.onSlotClick('liaison', null);
  const modalHtml = context.document.body.appended.at(-1).innerHTML;

  assert.match(modalHtml, /联络中心/);
  assert.match(modalHtml, /情报联络枢纽/);
  assert.match(modalHtml, /战略科研前置/);
  assert.doesNotMatch(modalHtml, /偏向高星/);
  assert.doesNotMatch(modalHtml, /军官刷新/);

  const cardHtml = G.Build.renderBuilding('liaison', G.DATA.buildings.liaison, [], 0);
  assert.match(cardHtml, /情报联络/);
  assert.doesNotMatch(cardHtml, /偏向高星/);
});

test('军校正确展示整批五星概率与招募培养说明', () => {
  const { G, context, state } = setup();
  G.DATA.buildings.academy = { name: '军校', desc: '培养招募军官,等级提升整批五星概率', baseCost: { steel: 200, food: 120, gold: 200 }, growth: 1.6, slots: 1 };
  state.buildings.academy = 8;
  G.Build.onSlotClick('academy', null);
  const modalHtml = context.document.body.appended.at(-1).innerHTML;

  assert.match(modalHtml, /整批五星概率/);
  assert.match(modalHtml, /\+2\.4%/);
  assert.match(modalHtml, /将领培养中心/);

  const cardHtml = G.Build.renderBuilding('academy', G.DATA.buildings.academy, [], 0);
  assert.match(cardHtml, /五星概率/);
  assert.match(cardHtml, /\+2\.4%/);
});

test('研发中心升级预览按真实科技目录展示开放种类、等级上限与下级新增项目', () => {
  const { G, context, state } = setup();
  G.escapeHtml = value => String(value).replace(/[&<>"']/g, character => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[character]);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/data.js'), 'utf8'), context);
  for (let level = 1; level <= 10; level++) {
    state.buildings.lab = level;
    const count = [0, 6, 16, 22, 23, 24, 24, 24, 24, 24, 24][level];
    G.Build.onSlotClick('lab', null);
    const modal = context.document.body.appended.at(-1).innerHTML;
    assert.match(modal, new RegExp('>' + count + ' 项'));
    assert.match(modal, new RegExp('最高 Lv\\.' + level));
    assert.doesNotMatch(modal, /研发速度加成/);
    if (level === 3) assert.match(modal, /下级新增科技[\s\S]*喷气推进/);
    if (level === 4) assert.match(modal, /下级新增科技[\s\S]*舰船动力/);
    if (level >= 5) assert.doesNotMatch(modal, /下级新增科技/);
    const card = G.Build.renderBuilding('lab', G.DATA.buildings.lab, [], 0);
    assert.match(card, new RegExp(count + ' 项 / 最高 Lv\\.' + level));
    assert.doesNotMatch(card, /研发速度加成/);
  }
});

test('城墙、研发中心、雷达站与军需仓库展示准确属性与加成', () => {
  const { G, context, state } = setup();
  G.DATA.buildings.wall = { name: '城防', desc: '基地外围城防设施;四类城防武器共享容量,每级+3000', baseCost: { steel: 200, food: 80 }, growth: 1.5, fortCapPerLevel: 3000, wallHpPerLevel: 250000, wallDefensePerLevel: 20, slots: 1 };
  G.DATA.buildings.lab = { name: '军工科技研发中心', desc: '升级解锁更多科技，并提高可研究等级上限；不加速科研', baseCost: { steel: 200, food: 100, rare: 20 }, growth: 1.6, slots: 1 };
  G.DATA.buildings.depot = { name: '军需仓库', desc: '战备物资掠夺保护,被掠夺时保护资源', baseCost: { steel: 100 }, growth: 1.5, protectPer: 1000, slots: 35 };
  G.DATA.buildings.exchange = { name: '军需物资中转站', desc: '战备物资调配,按比例转换资源', baseCost: { steel: 180, food: 100, gold: 100 }, growth: 1.5, slots: 1 };

  // 1. 满级城防
  state.buildings.wall = 10;
  G.Build.onSlotClick('wall', null);
  const wallModal = context.document.body.appended.at(-1).innerHTML;
  assert.match(wallModal, /城防武器容量/);
  assert.match(wallModal, /墙体生命/);
  assert.match(wallModal, /2,?500,?000/);
  assert.match(wallModal, /200（仅保护墙体）/);
  assert.doesNotMatch(wallModal, /NaN/);
  assert.match(wallModal, /30,?000（四类共享）/);
  assert.doesNotMatch(wallModal, /满级带兵|100,000|守城防御|提升守城部队防御/);
  const wallCard = G.Build.renderBuilding('wall', G.DATA.buildings.wall, [], 0);
  assert.match(wallCard, /城防武器容量/);
  assert.match(wallCard, /2,?500,?000/);
  assert.match(wallCard, /200（仅墙体）/);
  assert.match(wallCard, /30,?000/);
  assert.doesNotMatch(wallCard, /满级带兵|100,000|守城防御|提升守城部队防御/);

  // 2. 研发中心科技准入
  state.buildings.lab = 6;
  G.Build.onSlotClick('lab', null);
  const labModal = context.document.body.appended.at(-1).innerHTML;
  assert.match(labModal, /开放科技种类/);
  assert.match(labModal, /最高 Lv\.6/);
  assert.match(labModal, /科技可研究等级/);
  assert.doesNotMatch(labModal, /研发速度加成|\+6%/);
  const labCard = G.Build.renderBuilding('lab', G.DATA.buildings.lab, [], 0);
  assert.match(labCard, /科技准入/);
  assert.match(labCard, /最高 Lv\.6/);
  assert.doesNotMatch(labCard, /研发速度加成|\+6%/);

  // 3. 雷达站视野
  state.buildings.radar = 4;
  G.Build.onSlotClick('radar', null);
  const radarModal = context.document.body.appended.at(-1).innerHTML;
  assert.match(radarModal, /侦察视野加成/);
  assert.match(radarModal, /\+4 格/);
  const radarCard = G.Build.renderBuilding('radar', G.DATA.buildings.radar, [], 0);
  assert.match(radarCard, /侦察视野/);
  assert.match(radarCard, /\+4 格/);

  // 4. 军需仓库资源保护（已与交易所槽位解耦，不再显示交易所槽位）
  state.buildings.depot = 3;
  G.Build.onSlotClick('depot', null);
  const depotModal = context.document.body.appended.at(-1).innerHTML;
  assert.doesNotMatch(depotModal, /交易所槽位/);
  assert.match(depotModal, /资源掠夺保护/);

  // 5. 军需物资中转站挂单槽位按 2 + 交易所等级计算 (Lv.5 -> 7 槽)
  state.buildings.command = 10;
  state.buildings.exchange = 5;
  G.Build.onSlotClick('exchange', null);
  const exchangeModal = context.document.body.appended.at(-1).innerHTML;
  assert.match(exchangeModal, /货架挂单槽位/);
  assert.match(exchangeModal, /7 槽 \(下级 8 槽\)/);
  assert.match(exchangeModal, /战略调配比率/);

  // 6. 司令部出征队伍上限按 5 + 司令部等级计算 (Lv.3 -> 8 支)
  G.DATA.buildings.headquarters = { name: '司令部', desc: '任命司令官与提升出征队伍上限', baseCost: { steel: 300, food: 150, gold: 150 }, growth: 1.6, slots: 1 };
  state.buildings.headquarters = 3;
  G.Build.onSlotClick('headquarters', null);
  const hqModal = context.document.body.appended.at(-1).innerHTML;
  assert.match(hqModal, /出征队伍上限/);
  assert.match(hqModal, /8 支 \(下级 9 支\)/);
  assert.match(hqModal, /司令官/);
});
