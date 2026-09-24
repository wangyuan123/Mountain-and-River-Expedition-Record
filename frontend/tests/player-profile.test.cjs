const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function load(context, file) {
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), context, { filename: file });
}

test('profile rank opens the full rank-to-cap table with the current city bonus', () => {
  const elements = {};
  const trigger = { isConnected: true, focus() { this.focused = true; } };
  const closeButton = { focus() { this.focused = true; } };
  const doneButton = {};
  const body = { appendChild(element) { element.parentNode = body; elements[element.id] = element; },
    removeChild(element) { element.parentNode = null; delete elements[element.id]; } };
  const document = {
    body, activeElement: trigger, getElementById(id) { return elements[id] || null; },
    createElement() { return { setAttribute() {}, querySelector(selector) { return selector === '.rank-cap-close' ? closeButton : doneButton; } }; }
  };
  const context = vm.createContext({ console, document });
  context.window = context;
  context.Game = { Core: {
    state: { player: { militaryRank: 3 } }, buildingLevel: () => 10,
    getCommanderSkills: () => ({ leadership: 5 }), skillBonus: skill => skill === 'leadership' ? 0.2 : 0,
    armyCap: () => 300000
  }, fmt: value => Number(value).toLocaleString('en-US'), escapeHtml: value => value };
  load(context, 'data.js');
  load(context, 'player-profile.js');

  context.Game.PlayerProfile.showRankCapInfo();
  const mask = elements.rankCapInfoMask;
  assert.ok(mask);
  assert.equal((mask.innerHTML.match(/<tr(?: class=|>)/g) || []).length, 18);
  assert.match(mask.innerHTML, /rank-cap-current" aria-current="true"[^>]*><td>3<\/td><th scope="row"><span class="rank-cap-label"><svg[^>]*rank-insignia-sergeant/);
  assert.match(mask.innerHTML, /下士 <span>（当前）<\/span>/);
  assert.match(mask.innerHTML, /<td>17<\/td><th scope="row"><span class="rank-cap-label"><svg[^>]*rank-insignia-general/);
  assert.match(mask.innerHTML, /上将<\/span><\/th><td>850,000<\/td>/);
  assert.match(mask.innerHTML, /150,000 \+ 100,000\) × \(1 \+ 20%\) = 300,000/);
  assert.equal(closeButton.focused, true);
  context.Game.PlayerProfile.showRankCapInfo();
  assert.equal(elements.rankCapInfoMask, mask);
  mask.onkeydown({ key: 'Escape' });
  assert.equal(elements.rankCapInfoMask, undefined);
  assert.equal(trigger.focused, true);

  context.Game.PlayerProfile.showRankCapInfo();
  doneButton.onclick();
  assert.equal(elements.rankCapInfoMask, undefined);
});

test('profile rank card is an accessible action', () => {
  const context = vm.createContext({ console, document: {}, requestAnimationFrame() {} });
  context.window = context;
  context.Game = {
    Core: { state: { player: { militaryRank: 1 }, resources: {}, world: {} }, armyCap: () => 50000 },
    MainView: { getCurrentAvatar: () => 'avatar.svg' }, WS: { statusHtml: () => '' },
    fmt: String, escapeHtml: value => value
  };
  let drawer;
  context.document.createElement = () => ({ setAttribute() {}, querySelector() { return null; } });
  context.document.getElementById = () => null;
  context.document.body = { appendChild(element) { drawer = element; } };
  load(context, 'data.js');
  load(context, 'player-profile.js');
  context.Game.PlayerProfile.openPlayerDrawer();
  assert.match(drawer.innerHTML, /<button type="button" class="drawer-stat-box drawer-stat-action" onclick="Game.Main.showRankCapInfo\(\)" aria-label="查看军衔与带兵上限说明">/);
  assert.match(drawer.innerHTML, /rank-insignia-enlisted/);
});

test('each of the 17 ranks has a distinct insignia', () => {
  const context = vm.createContext({ console });
  context.window = context;
  context.Game = {};
  load(context, 'data.js');
  const icons = context.Game.DATA.militaryRanks.map(rank => context.Game.renderMilitaryRankIcon(rank.tier));
  assert.equal(icons.length, 17);
  assert.equal(new Set(icons).size, 17);
  assert.ok(icons.every(icon => icon.startsWith('<svg') && icon.endsWith('</svg>')));
});
