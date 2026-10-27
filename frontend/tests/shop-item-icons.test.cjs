const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup() {
  const context = vm.createContext({
    window: null,
    Game: {
      Core: { state: { resources: { diamond: 5000 }, items: {} }, views: {}, render() {} },
      API: {
        shopBuy() { return Promise.resolve({ success: true }); }
      },
      toast() {}
    }
  });
  context.window = context;
  for (const file of ['icons.js', 'constants.js', 'data.js', 'shop.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), context);
  }
  return { game: context.Game };
}

test('商城全量道具均使用专属矢量或高清图片，已完全消除原生 Emoji', () => {
  const { game } = setup();

  const allShopItems = game.Shop.items();
  assert.ok(allShopItems.length >= 25, '商城商品总数应不少于25种');

  for (const item of allShopItems) {
    assert.match(item.icon, /^img\/.+\.(svg|webp|png)$/i, `商品 [${item.name}] (${item.id}) 应使用高清图片或 SVG 图标，当前为: ${item.icon}`);

    // 验证物理文件存在且非空
    const filePath = path.join(__dirname, '..', item.icon);
    assert.ok(fs.existsSync(filePath), `图标文件应存在: ${filePath}`);

    // 如果是 svg，验证其有效性
    if (item.icon.endsWith('.svg')) {
      const content = fs.readFileSync(filePath, 'utf8');
      assert.ok(content.includes('<svg'), `文件应为合法的 SVG 格式: ${item.icon}`);
      assert.ok(content.includes('viewBox="0 0 64 64"'), `SVG 应具备 64x64 viewBox: ${item.icon}`);
    }
  }
});

test('基础道具数据中对应道具与商城保持一致', () => {
  const { game } = setup();

  const keyItems = [
    'expBook', 'expBookAdv', 'expBookMax',
    'skillBook', 'universalSkillBook',
    'loyaltyBox', 'renameCard', 'recruitOrd', 'starUp',
    'resBox', 'foodPack', 'steelPack', 'oilPack', 'rarePack', 'supplyPack',
    'resourcePack100w', 'resourcePack200w', 'resourcePack350w', 'resourcePack500w',
    'speedUp10m', 'speedUp1h', 'speedUp5h', 'speedUp12h',
    'speedUp24h', 'speedUp36h', 'speedUp48h', 'speedUp72h',
    'shield2h', 'shield', 'shield12h', 'shield24h', 'shield72h',
    'marchOrd', 'signalJammingOrder', 'feintOrder',
    'populationOrder', 'emergencyPopulationOrder', 'cityRenameCard',
    'teleportNormal', 'teleportAdvanced',
    'newbiePack'
  ];

  for (const id of keyItems) {
    const shopItem = game.Shop.items().find(it => it.id === id);
    const dataItem = game.DATA.items[id];

    assert.ok(shopItem, `商城中应存在商品: ${id}`);
    assert.ok(dataItem, `基础数据中应存在道具: ${id}`);
    assert.equal(shopItem.icon, dataItem.icon, `商城与基础数据道具 [${id}] 图标路径应一致`);
    assert.match(dataItem.icon, /^img\/.+\.(svg|webp|png)$/i);
  }
});

test('G.iconHtml 正确将道具图片路径转换为 img 标签并转义', () => {
  const { game } = setup();

  const expHtml = game.iconHtml('img/items/exp-book.svg', '经验书');
  assert.equal(expHtml, '<img class="game-icon-img" src="img/items/exp-book.svg" alt="经验书" draggable="false"/>');

  const speedHtml = game.iconHtml('img/items/speedup-1h.svg', '1时加速符');
  assert.equal(speedHtml, '<img class="game-icon-img" src="img/items/speedup-1h.svg" alt="1时加速符" draggable="false"/>');

  const recruitHtml = game.iconHtml('img/items/recruit-ord.svg', '征募令');
  assert.equal(recruitHtml, '<img class="game-icon-img" src="img/items/recruit-ord.svg" alt="征募令" draggable="false"/>');
});

test('商城各分类视图渲染全部使用图片图标', () => {
  const { game } = setup();
  const categories = ['officer', 'resource', 'util', 'gift'];

  for (const cat of categories) {
    game.Shop.curCat = cat;
    const view = {};
    game.Shop.renderView(view);

    // 检查渲染内容不含任何旧 emoji
    assert.doesNotMatch(view.innerHTML, /⚡|📘|📕|📙|📗|📚|🎖️|✨|📦(?![\w-])|🚩|📡|🎭|👥|📣|🏷️|🛡️|📍/);
    assert.ok(view.innerHTML.includes('img/items/'));
  }
});

test('商城套装细分筛选样式优化：容器规范、自定义下拉与一键重置', () => {
  const { game } = setup();

  // 1. 全部视图下展示美化后的套装细分筛选栏与防脱节自定义 DOM 下拉
  game.Shop.curCat = 'all';
  const view1 = {};
  game.Shop.renderView(view1);
  assert.match(view1.innerHTML, /class="shop-equip-filter-bar"/);
  assert.match(view1.innerHTML, /class="shop-equip-filter-tag"/);
  assert.match(view1.innerHTML, /套装细分/);
  assert.match(view1.innerHTML, /class="shop-custom-dropdown/);
  assert.match(view1.innerHTML, /class="shop-custom-dropdown-trigger/);
  assert.match(view1.innerHTML, /class="shop-custom-dropdown-menu/);
  assert.doesNotMatch(view1.innerHTML, /class="shop-equip-filter-reset"/);

  // 2. 自定义下拉展开与选择测试
  game.Shop.toggleDropdown('tier');
  assert.equal(game.Shop.openDropdown, 'tier');
  game.Shop.selectEquipmentFilter('equipmentTier', 'general');
  assert.equal(game.Shop.openDropdown, null);
  assert.equal(game.Shop.equipmentTier, 'general');

  // 3. 选择筛选条件后，对应下拉触发框高亮显示并渲染“上将”与一键重置按钮
  const view2 = {};
  game.Shop.renderView(view2);
  assert.match(view2.innerHTML, /shop-custom-dropdown-trigger is-active[^>]*>[\s\S]*?上将/);
  assert.match(view2.innerHTML, /class="shop-equip-filter-reset"[^>]*onclick="Game\.Shop\.clearEquipmentFilters\(\)"/);

  // 4. 点击一键重置，清除全部筛选状态并还原为默认文案
  game.Shop.clearEquipmentFilters();
  assert.equal(game.Shop.equipmentTier, '');
  assert.equal(game.Shop.equipmentBranch, '');
  const view3 = {};
  game.Shop.renderView(view3);
  assert.doesNotMatch(view3.innerHTML, /class="shop-custom-dropdown-trigger is-active"/);
  assert.doesNotMatch(view3.innerHTML, /class="shop-equip-filter-reset"/);
  assert.match(view3.innerHTML, /shop-dropdown-text">全部档次<\/span>/);
});
