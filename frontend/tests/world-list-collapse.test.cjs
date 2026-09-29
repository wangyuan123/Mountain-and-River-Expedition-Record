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
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/world.js'), 'utf8'), context);
  const view = { innerHTML: '' };

  game.World.renderView(view);
  assert.match(view.innerHTML, /class="tcard tcard-wild tcard-collapsible" data-target-key="wild:11"><button[^>]*aria-expanded="false"/);
  assert.match(view.innerHTML, /class="tcard tcard-npc tcard-collapsible" data-target-key="bandit:21"><button[^>]*aria-expanded="false"/);
  assert.match(view.innerHTML, /data-target-key="wild:11"[^]*?<div class="tcard-expand">[^]*?Game\.World\.attackWild\(0,'conquer'\)/);
  assert.match(view.innerHTML, /data-target-key="bandit:21"[^]*?<div class="tcard-expand">[^]*?Game\.World\.attack\('bandit',0,'plunder'\)/);
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
