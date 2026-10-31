const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

test('list map collapses wild, NPC, and owned cards until opened', () => {
  const state = {
    player: { id: 1, cityName: '我城' }, reports: [],
    world: {
      cityPos: { x: 100, y: 100 }, pos: { x: 100, y: 100 },
      wildTiles: [
        { id: 11, type: 'swamp', level: 1, x: 101, y: 100, totalRes: 0 },
        { id: 12, type: 'swamp', level: 2, x: 102, y: 100, occupied: true, totalRes: 0 }
      ],
      bandits: [
        { id: 21, name: '据点', level: 3, x: 100, y: 101, commanderName: '敌将' },
        { id: 22, name: '日寇第1舰队', level: 3, x: 101, y: 101, sea: true },
        { id: 23, name: '日寇第4航母编队', level: 6, x: 102, y: 101, sea: true },
        { id: 24, name: '日寇第5潜艇支队', level: 3, x: 103, y: 101, sea: true },
        { id: 25, name: '日寇第6驱逐舰队', level: 4, x: 104, y: 101, sea: true }
      ],
      playerCities: [], marches: []
    }
  };
  const game = {
    DATA: { world: { size: 200, viewRadius: 8 }, wildTypes: { swamp: { name: '沼泽', icon: '🪨' } }, resources: {}, units: {} },
    Constants: { mapRadiusOptions: [{ v: 8, label: '8格' }], cityStateNames: {} },
    Core: { state, views: {} }, WorldView: { ensure: () => true }, fmt: String, resourceIconHtml: () => ''
  };
  const context = vm.createContext({ Game: game, Date, localStorage: { getItem: () => null, setItem() {} } });
  context.window = context;
  require('./load-constants.cjs')(context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/world.js'), 'utf8'), context);
  const view = { innerHTML: '' };

  game.World.renderView(view);
  assert.match(view.innerHTML, /class="tcard tcard-wild tcard-collapsible" data-target-key="wild:11"><button[^>]*aria-expanded="false"/);
  assert.match(view.innerHTML, /class="tcard tcard-npc tcard-collapsible" data-target-key="bandit:21"><button[^>]*aria-expanded="false"/);
  assert.match(view.innerHTML, /data-target-key="wild:11"[^]*?<div class="tcard-expand">[^]*?Game\.World\.attackWild\(0,'conquer'\)/);
  assert.match(view.innerHTML, /data-target-key="bandit:21"[^]*?<div class="tcard-expand">[^]*?Game\.World\.attack\('bandit',0,'conquer'\)/);
  assert.match(view.innerHTML, /data-target-key="bandit:21"[^]*?寇/);
  assert.doesNotMatch(view.innerHTML, /据点 Lv\.3/);
  for (const vessel of ['battleship', 'carrier', 'sub', 'destroyer']) {
    assert.match(view.innerHTML, new RegExp('src="img/npc/japanese-navy/' + vessel + '\\.webp"'));
  }

  const button = { setAttribute(name, value) { this[name] = value; } };
  const classes = new Set(['tcard-collapsible']);
  const row = {
    getAttribute: () => 'wild:11', querySelector: () => button,
    classList: { toggle(name) { if (classes.has(name)) { classes.delete(name); return false; } classes.add(name); return true; } }
  };
  game.World.toggleTargetCard(row);
  assert.equal(state.world._expandedTargetIds['wild:11'], true);
  assert.equal(button['aria-expanded'], 'true');
  game.World.renderView(view);
  assert.match(view.innerHTML, /class="tcard tcard-wild tcard-collapsible tcard-expanded" data-target-key="wild:11"/);
  assert.match(view.innerHTML, /class="tcard tcard-npc tcard-collapsible" data-target-key="bandit:21"/);
  game.World.toggleTargetCard(row);
  assert.equal(state.world._expandedTargetIds['wild:11'], undefined);
  assert.equal(button['aria-expanded'], 'false');

  state.world._activeTab = 'owned';
  game.World.renderView(view);
  assert.match(view.innerHTML, /class="tcard tcard-owned tcard-collapsible" data-target-key="wild:12"><button[^>]*aria-expanded="false"/);
  assert.match(view.innerHTML, /data-target-key="wild:12"[^]*?<div class="tcard-expand">[^]*?Game\.World\.dispatchWild\(1,'station'\)/);
  state.world.wildTiles[1].gathering = true;
  state.world.wildTiles[1].gatherMode = 'manual';
  state.world.wildTiles[1].gatherEndAt = Date.now() - 1;
  game.World.renderView(view);
  assert.match(view.innerHTML, /data-target-key="wild:12"><button[^]*?<span class="tcard-mini-badge tcard-mini-shield">待收获<\/span>/);
});

test('list map defaults view radius to 8 and hides range dropdown select', () => {
  const state = {
    player: { id: 1, cityName: '我城' }, reports: [],
    world: {
      cityPos: { x: 50, y: 50 }, pos: { x: 50, y: 50 },
      wildTiles: [], bandits: [], playerCities: [], marches: []
    }
  };
  let savedKey = null, savedVal = null;
  const store = {
    getItem: (k) => (k === 'wg_viewRadius' ? '3' : null),
    setItem: (k, v) => { savedKey = k; savedVal = v; }
  };
  const game = {
    DATA: { world: { size: 200, viewRadius: 8 }, wildTypes: {}, resources: {}, units: {} },
    Constants: { mapRadiusOptions: [{ v: 8, label: '范围: 8格' }, { v: 0, label: '全图' }], cityStateNames: {} },
    Core: { state, views: {} }, WorldView: { ensure: () => true }, fmt: String, resourceIconHtml: () => ''
  };
  const context = vm.createContext({ Game: game, Date, localStorage: store });
  context.window = context;
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/world.js'), 'utf8'), context);
  const view = { innerHTML: '' };

  game.World.renderView(view);

  // 1. 范围选择下拉框已被隐藏
  assert.match(view.innerHTML, /id="viewRadiusSel"[^>]*style="display:none !important;"/);
  // 2. 状态栏显示范围为 8格
  assert.match(view.innerHTML, /范围：8格/);
  // 3. 视野半径默认或旧默认值3自动升级为8格
  assert.equal(state.world._scan.r, 8);
  assert.equal(savedKey, 'wg_viewRadius');
  assert.equal(savedVal, '8');
});

test('list map displays correct player prestige for both own cities and other players', () => {
  const state = {
    player: { id: 1, cityName: '我城', prestige: 88888 }, reports: [],
    world: {
      cityPos: { x: 50, y: 50 }, pos: { x: 50, y: 50 },
      wildTiles: [], bandits: [],
      playerCities: [
        // 自己的副城：城市对象上的 prestige 为 0，但应正确展示玩家实际声望 88888
        { id: 101, ownerId: 1, selfCity: true, name: '健康城_副城1', x: 51, y: 50, prestige: 0, commanderName: '深入山林开荒种地', militaryRank: 17, militaryRankName: '上将' },
        // 其他玩家的城市：展示对方真实声望 66666
        { id: 102, ownerId: 2, selfCity: false, name: '敌军前哨', x: 52, y: 50, prestige: 66666, commanderName: '敌将乙', militaryRank: 10, militaryRankName: '上尉' }
      ],
      marches: []
    }
  };
  const game = {
    DATA: { world: { size: 200, viewRadius: 8 }, wildTypes: {}, resources: {}, units: {} },
    Constants: { mapRadiusOptions: [{ v: 8, label: '范围: 8格' }], cityStateNames: {} },
    Core: { state, views: {} }, WorldView: { ensure: () => true }, fmt: String, resourceIconHtml: () => ''
  };
  const context = vm.createContext({ Game: game, Date, localStorage: { getItem: () => '8', setItem() {} } });
  context.window = context;
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/world.js'), 'utf8'), context);
  const view = { innerHTML: '' };

  game.World.renderView(view);

  // 1. 自己的副城 mini-row 与卡片详情中均展示玩家真实声望 88888 而非 0
  assert.match(view.innerHTML, /data-player-id="101"[^>]*>[\s\S]*?<span class="tcard-mini-prestige">声望：88888<\/span>/);
  assert.match(view.innerHTML, /data-player-id="101"[^>]*>[\s\S]*?<div class="tcard-meta">声望：88888<\/div>/);

  // 2. 别的玩家城市展示对方的声望 66666
  assert.match(view.innerHTML, /data-player-id="102"[^>]*>[\s\S]*?<span class="tcard-mini-prestige">声望：66666<\/span>/);
  assert.match(view.innerHTML, /data-player-id="102"[^>]*>[\s\S]*?<div class="tcard-meta">声望：66666<\/div>/);
});
