const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup(initialCheckin, apiMock) {
  let toasts = [];
  const G = {
    Core: { route: 'mainQuest', state: { player: { id: 1 } }, views: {}, render() {} },
    Constants: { equipmentNames: {} },
    DATA: { items: {} },
    API: {
      getQuestList: async () => ({
        chapters: [],
        dailyCheckin: initialCheckin || {
          date: '2026-10-27',
          claimed: false,
          rewards: { renameCard: 1, cityRenameCard: 1, gold: 10000, food: 10000, steel: 10000, oil: 10000, rare: 10000, speedUp10m: 1 }
        }
      }),
      claimDailyCheckin: apiMock || (async () => ({
        success: true,
        message: '每日军务签到成功',
        dailyCheckin: {
          date: '2026-10-27',
          claimed: true,
          rewards: { renameCard: 1, cityRenameCard: 1, gold: 10000, food: 10000, steel: 10000, oil: 10000, rare: 10000, speedUp10m: 1 }
        }
      }))
    },
    toast: msg => toasts.push(msg)
  };

  const view = { innerHTML: '' };
  const domElements = { view };

  const windowObj = { Game: G };
  const context = vm.createContext({
    Game: G,
    window: windowObj,
    console,
    document: {
      getElementById: id => domElements[id] || null,
      querySelector: () => null,
      querySelectorAll: () => []
    }
  });

  const code = fs.readFileSync(path.join(__dirname, '../js/main-quest.js'), 'utf8');
  vm.runInContext(code, context);

  return { G, view, toasts };
}

test('每日签到任务：在任务主面板展示自主领受军官与城市改名卡卡片', async () => {
  const { G, view } = setup({
    date: '2026-10-27',
    claimed: false,
    rewards: { renameCard: 1, cityRenameCard: 1, gold: 10000, food: 10000, steel: 10000, oil: 10000, rare: 10000, speedUp10m: 1 }
  });

  await G.MainQuest.loadQuests();
  G.MainQuest.drawQuestView(view);

  // 1. 验证面板标题与自主领受说明
  assert.match(view.innerHTML, /每日军务点卯签到/);
  assert.match(view.innerHTML, /自主选择是否领受/);
  assert.match(view.innerHTML, /未领受不强占背包/);

  // 2. 验证赠送道具与基础口粮
  assert.match(view.innerHTML, /军官改名卡 <strong>×1<\/strong>/);
  assert.match(view.innerHTML, /城市改名卡 <strong>×1<\/strong>/);
  assert.match(view.innerHTML, /黄金 <strong>×10,000<\/strong>/);
  assert.match(view.innerHTML, /粮食 <strong>×10,000<\/strong>/);
  for (const name of ['钢铁', '石油', '稀矿']) {
    assert.ok(view.innerHTML.includes(name + ' <strong>×10,000</strong>'));
  }
  assert.match(view.innerHTML, /10分加速 <strong>×1<\/strong>/);

  // 3. 验证存在领受按钮
  assert.match(view.innerHTML, /签到并领受战备/);
  assert.doesNotMatch(view.innerHTML, /今日已签到领受/);
});

test('每日签到任务：领受签到后按钮切换为已领受禁用状态', async () => {
  const { G, view, toasts } = setup({
    date: '2026-10-27',
    claimed: false,
    rewards: { renameCard: 1, cityRenameCard: 1, gold: 10000, food: 10000, steel: 10000, oil: 10000, rare: 10000, speedUp10m: 1 }
  });

  await G.MainQuest.loadQuests();
  G.MainQuest.drawQuestView(view);

  // 点击签到领受
  await G.MainQuest.claimDailyCheckin();

  assert.ok(toasts.some(t => t.includes('签到成功')));
  assert.match(view.innerHTML, /今日已签到领受/);
  assert.match(view.innerHTML, /disabled/);
  assert.doesNotMatch(view.innerHTML, /Game\.MainQuest\.claimDailyCheckin\(\)/);
});

test('签到资源展示跟随服务端配置', async () => {
  const { G, view } = setup({ claimed: false, rewards: { gold: 12345, food: 23456, steel: 34567, oil: 45678, rare: 56789 } });
  await G.MainQuest.loadQuests();
  G.MainQuest.drawQuestView(view);
  for (const value of ['12,345', '23,456', '34,567', '45,678', '56,789']) {
    assert.ok(view.innerHTML.includes('×' + value));
  }
});
