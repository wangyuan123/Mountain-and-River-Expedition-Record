const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup() {
  const state = {
    world: {},
    resources: { food: 5000, steel: 5000, oil: 5000, rare: 5000, gold: 5000 },
    buildings: {
      command: 2,
      farm: [2, 1], // slot 0: Lv.2, slot 1: Lv.1
      refinery: [1],
      radar: 1
    },
    constructions: [],
    items: { speedUp10m: 3 }
  };

  const context = vm.createContext({
    console, Date, Math, parseInt, Array, Object, String,
    setTimeout(fn) { fn(); return 1; }, clearTimeout() {},
    setInterval() { return 1; }, clearInterval() {},
    window: null,
    document: {
      createElement() {
        return {
          className: '',
          innerHTML: '',
          style: {},
          querySelector() { return null; },
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
          farm:     { name: '农田', desc: '粮食', baseCost: { steel: 80 }, growth: 1.5, produces: 'food', baseProduce: 40, slots: 32 },
          refinery: { name: '炼钢厂', desc: '钢铁', baseCost: { steel: 80 }, growth: 1.5, produces: 'steel', slots: 32 },
          oilfield: { name: '石油基地', desc: '石油', baseCost: { steel: 80 }, growth: 1.5, produces: 'oil', slots: 32 },
          raremine: { name: '稀矿厂', desc: '稀矿', baseCost: { steel: 120, oil: 40 }, growth: 1.6, produces: 'rare', slots: 32 },
          radar:    { name: '雷达站', desc: '侦察', baseCost: { steel: 180, oil: 60, rare: 20 }, growth: 1.6, produces: '', slots: 1 }
        },
        groupSlots: { res: 12, army: 10 },
        resources: {
          food: { name: '粮食', icon: '🌾' },
          steel: { name: '钢铁', icon: '🏭' },
          oil: { name: '石油', icon: '🛢️' },
          rare: { name: '稀矿', icon: '⛏️' },
          gold: { name: '黄金', icon: '🪙' }
        },
        resEmoji: { food: '🌾', steel: '🏭', oil: '🛢️', rare: '⛏️', gold: '🪙' }
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
          const cmd = this.buildingLevel('command');
          return key === 'res' ? Math.min(32, 12 + cmd * 2) : (10 + cmd * 2);
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
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/build.js'), 'utf8'), context);
  return { G, context, state };
}

test('卡槽地块网格正确渲染已建建筑与空闲卡槽(+号)', () => {
  const { G, state } = setup();
  // command level = 2, so res groupCap = 12 + 2*2 = 16
  const groupCap = G.Core.groupSlotsCap('res');
  assert.equal(groupCap, 16);

  // farm has [2, 1] (2 slots), refinery has [1] (1 slot) -> 3 occupied slots
  const gridHtml = G.Build.renderSlotGrid('res', [], state);
  assert.match(gridHtml, /class="slot-overview-panel"/);
  assert.match(gridHtml, /已用 3 \/ 16 卡槽/);
  assert.match(gridHtml, /农田/);
  assert.match(gridHtml, /炼钢厂/);

  // Should have empty slots with '+' buttons
  assert.match(gridHtml, /class="slot-card slot-card-empty"/);
  assert.match(gridHtml, /class="slot-card-plus">\+<\/div>/);
  assert.match(gridHtml, /选建建筑/);

  // Total empty slot cards should be 16 - 3 = 13
  const emptyMatches = gridHtml.match(/class="slot-card slot-card-empty"/g);
  assert.equal(emptyMatches.length, 13);
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

test('资源Tab卡槽卡片图标与首页资源区SVG图标严格对齐', () => {
  const { G, state } = setup();
  const gridHtml = G.Build.renderSlotGrid('res', [], state);
  // 农田 -> img/res-food.svg
  assert.match(gridHtml, /<img class="b-icon-img" src="img\/res-food\.svg" alt="农田"/);
  // 炼钢厂 -> img/res-steel.svg
  assert.match(gridHtml, /<img class="b-icon-img" src="img\/res-steel\.svg" alt="炼钢厂"/);

  // BUILD_ICON 四大资源建筑严格对齐首页资源SVG
  assert.equal(G.Build.BUILD_ICON.farm, 'img/res-food.svg');
  assert.equal(G.Build.BUILD_ICON.refinery, 'img/res-steel.svg');
  assert.equal(G.Build.BUILD_ICON.oilfield, 'img/res-oil.svg');
  assert.equal(G.Build.BUILD_ICON.raremine, 'img/res-rare.svg');
});

test('军事Tab所有建筑采用二战战术矢量SVG图标', () => {
  const { G, state } = setup();
  // 测试军事建筑 BUILD_ICON 映射
  const armyBuildings = [
    'command', 'house', 'factory', 'lightfactory', 'heavyfactory',
    'airport', 'port', 'academy', 'staff', 'lab', 'radar', 'wall',
    'apron', 'liaison', 'depot', 'transit', 'exchange'
  ];
  for (const bid of armyBuildings) {
    assert.equal(G.Build.BUILD_ICON[bid], `img/buildings/${bid}.svg`);
  }

  // 渲染军事建筑卡槽网格，包含已建成的 command (Lv.2) 和 radar (Lv.1)
  const armyGridHtml = G.Build.renderSlotGrid('army', [], state);
  assert.match(armyGridHtml, /<img class="b-icon-img" src="img\/buildings\/command\.svg" alt="市政厅"/);
  assert.match(armyGridHtml, /<img class="b-icon-img" src="img\/buildings\/radar\.svg" alt="雷达站"/);
});

test('renderBuildingIcon 支持 SVG 图片路径及 emoji 降级', () => {
  const { G } = setup();
  const svgImg = G.Build.renderBuildingIcon('img/buildings/factory.svg', '军工厂', 'custom-cls');
  assert.equal(svgImg, '<img class="b-icon-img custom-cls" src="img/buildings/factory.svg" alt="军工厂" draggable="false"/>');

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

