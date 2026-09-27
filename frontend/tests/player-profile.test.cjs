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
    armyCap: () => 210000
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
  assert.match(mask.innerHTML, /上将<\/span><\/th><td>600,000<\/td>/);
  assert.match(mask.innerHTML, /75,000 \+ 100,000\) × \(1 \+ 20%\) = 210,000/);
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
    Core: { state: { player: { militaryRank: 1 }, resources: {}, world: {} }, armyCap: () => 25000 },
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

test('avatar picker only offers and saves preset images', () => {
  const elements = {};
  const document = {
    getElementById(id) { return elements[id] || null; },
    createElement() {
      return { style: {}, remove() { delete elements[this.id]; } };
    },
    body: { appendChild(element) { elements[element.id] = element; } },
    querySelector() { return null; }
  };
  const saved = [];
  const storage = {};
  const context = vm.createContext({ console, document, localStorage: {
    setItem(key, value) { storage[key] = value; }
  } });
  context.window = context;
  context.Game = {
    Core: { state: { player: { username: 'player' } } },
    MainView: {
      getCurrentAvatar: () => 'https://example.com/old.png',
      presetAvatars: [
        { src: 'img/avatars/historical/rank-private-v1.webp', name: '列兵' },
        { src: 'img/avatars/historical/rank-general-v1.webp', name: '将军' }
      ]
    },
    API: { setAvatar(avatar) { saved.push(avatar); return Promise.resolve(); } },
    toast() {}
  };
  load(context, 'player-profile.js');
  context.Game.Main = context.Game.PlayerProfile;

  context.Game.Main.openAvatarPicker();
  const modal = elements.avatarPickerMask;
  assert.match(modal.innerHTML, /选择指挥官头像/);
  assert.match(modal.innerHTML, /id="avatarPreviewImg"[^>]*historical\/rank-private-v1\.webp/);
  assert.doesNotMatch(modal.innerHTML, /customAvatarInput|previewCustomAvatar|example\.com|网络 URL/);

  context.Game.Main._selectedAvatar = 'https://example.com/new.png';
  context.Game.Main.saveSelectedAvatar();
  assert.deepEqual(saved, []);
  context.Game.Main.selectPresetAvatar('https://example.com/new.png');
  assert.equal(context.Game.Main._selectedAvatar, 'https://example.com/new.png');
  context.Game.Main.selectPresetAvatar('img/avatars/historical/rank-general-v1.webp');
  context.Game.Main.saveSelectedAvatar();
  assert.deepEqual(saved, ['img/avatars/historical/rank-general-v1.webp']);
  assert.equal(storage.wargame_avatar_player, 'img/avatars/historical/rank-general-v1.webp');
});

test('previously saved network avatars fall back to a preset when displayed', () => {
  const context = vm.createContext({ document: {}, localStorage: {
    getItem: () => 'https://example.com/old.png'
  } });
  context.window = context;
  for (const file of ['constants.js', 'data.js', 'core.js', 'main-view.js']) load(context, file);

  context.Game.Core.state = { player: { username: 'player', avatar: 'https://example.com/old.png' } };
  assert.equal(context.Game.MainView.getCurrentAvatar(), 'img/avatars/historical/rank-private-v1.webp');
  context.Game.Core.state.player.avatar = 'img/avatars/historical/rank-colonel-v1.webp';
  assert.equal(context.Game.MainView.getCurrentAvatar(), 'img/avatars/historical/rank-colonel-v1.webp');
});

test('historical avatar catalog covers the available files and backend whitelist', () => {
  const context = vm.createContext({});
  context.window = context;
  load(context, 'constants.js');
  const avatars = Array.from(context.Game.Constants.presetAvatars, avatar => avatar.src);
  const assetDirectory = path.join(__dirname, '../img/avatars/historical');
  const files = fs.readdirSync(assetDirectory).filter(file => file.endsWith('.webp'))
    .map(file => 'img/avatars/historical/' + file);
  const definition = fs.readFileSync(path.join(__dirname, '../../backend/src/main/java/com/wargame/model/constants/AvatarDef.java'), 'utf8');
  const allowed = Array.from(definition.matchAll(/"(img\/avatars\/historical\/[^"]+)"/g), match => match[1]);
  assert.deepEqual(avatars.slice().sort(), files.sort());
  assert.deepEqual(avatars.slice().sort(), allowed.sort());
  assert.equal(new Set(avatars).size, 8);
});

test('server avatar wins over stale cache and accounts do not share a default avatar', () => {
  const storage = {
    wargame_avatar_default: 'img/avatars/historical/rank-general-v1.webp',
    wargame_avatar_player: 'img/avatars/commander-8.svg'
  };
  const context = vm.createContext({ document: {}, localStorage: { getItem: key => storage[key] || null } });
  context.window = context;
  for (const file of ['constants.js', 'data.js', 'core.js', 'main-view.js']) load(context, file);
  const core = context.Game.Core;
  core.state = { player: { username: 'player', avatar: 'img/avatars/historical/rank-major-v1.webp' } };
  assert.equal(core.getCurrentAvatar(), core.state.player.avatar);
  assert.equal(context.Game.MainView.getCurrentAvatar(), core.state.player.avatar);
  core.state.player.avatar = 'img/avatars/commander-8.svg';
  assert.equal(core.getCurrentAvatar(), 'img/avatars/historical/rank-private-v1.webp');
  storage.wargame_avatar_player = 'img/avatars/historical/rank-captain-v1.webp';
  assert.equal(core.getCurrentAvatar(), storage.wargame_avatar_player);
  core.state.player = { username: 'other' };
  assert.equal(core.getCurrentAvatar(), 'img/avatars/historical/rank-private-v1.webp');
});

test('city rename uses a themed dialog and restores focus when cancelled', () => {
  const elements = {};
  const trigger = { isConnected: true, focus() { document.activeElement = this; } };
  const document = {
    activeElement: trigger,
    getElementById(id) { return elements[id] || null; },
    addEventListener(event, handler) { this.onKey = handler; },
    removeEventListener() { this.onKey = null; },
    createElement() {
      const controls = {};
      for (const selector of ['#renameCityInput', '.account-confirm-cancel', '.account-confirm-submit', '#renameCityForm']) {
        controls[selector] = { focus() { document.activeElement = this; } };
      }
      return { querySelector(selector) { return controls[selector]; }, controls,
        remove() { delete elements[this.id]; } };
    },
    body: { appendChild(element) { elements[element.id] = element; } }
  };
  const context = vm.createContext({ console, document, prompt() { throw Error('native prompt called'); } });
  context.window = context;
  context.Game = { Core: { state: { player: { cityName: '北城' } } }, toast() {} };
  load(context, 'player-profile.js');
  context.Game.Main = context.Game.PlayerProfile;

  context.Game.Main.promptRenameCityInDrawer();
  const modal = elements.renameCityModal;
  assert.match(modal.className, /account-confirm-mask/);
  assert.match(modal.innerHTML, /role="dialog" aria-modal="true"/);
  assert.equal(modal.controls['#renameCityInput'].value, '北城');
  assert.equal(document.activeElement, modal.controls['#renameCityInput']);
  context.Game.Main.promptRenameCityInDrawer();
  assert.equal(elements.renameCityModal, modal);
  modal.controls['.account-confirm-cancel'].onclick();
  assert.equal(elements.renameCityModal, undefined);
  assert.equal(document.activeElement, trigger);
  assert.equal(document.onKey, null);
  context.Game.Main.promptRenameCityInDrawer();
  let prevented = false;
  document.onKey({ key: 'Escape', stopPropagation() {}, preventDefault() { prevented = true; } });
  assert.equal(prevented, true);
  assert.equal(elements.renameCityModal, undefined);
  assert.equal(document.activeElement, trigger);
});

test('city rename validates, retries failed saves, and updates the city after success', async () => {
  const elements = {};
  const messages = [];
  const calls = [];
  let fail = true;
  let renders = 0;
  let drawerCloses = 0;
  const document = {
    getElementById(id) { return elements[id] || null; },
    addEventListener() {}, removeEventListener() {},
    createElement() {
      const controls = {};
      for (const selector of ['#renameCityInput', '.account-confirm-cancel', '.account-confirm-submit', '#renameCityForm']) {
        controls[selector] = { focus() { document.activeElement = this; } };
      }
      return { querySelector(selector) { return controls[selector]; }, controls,
        remove() { delete elements[this.id]; } };
    },
    body: { appendChild(element) { elements[element.id] = element; } }
  };
  const player = { cityName: '旧城' };
  const context = vm.createContext({ console, document });
  context.window = context;
  context.Game = {
    Core: { state: { player }, render() { renders++; } },
    API: { setCityName(name) { calls.push(name); return fail ? Promise.reject(new Error('网络错误')) : Promise.resolve(); } },
    toast(message) { messages.push(message); }
  };
  load(context, 'player-profile.js');
  context.Game.Main = context.Game.PlayerProfile;
  context.Game.Main.closePlayerDrawer = () => { drawerCloses++; };
  context.Game.Main.promptRenameCityInDrawer();
  const modal = elements.renameCityModal;
  const input = modal.controls['#renameCityInput'];
  const submit = modal.controls['.account-confirm-submit'];
  const form = modal.controls['#renameCityForm'];
  const submitForm = () => form.onsubmit({ preventDefault() {} });

  input.value = ' '; submitForm();
  input.value = 'bad!'; submitForm();
  assert.deepEqual(messages, ['城市名不能为空', '城市名仅限中英文/数字/下划线，最多12字']);
  assert.deepEqual(calls, []);
  input.value = ' 新城 '; submitForm(); submitForm();
  assert.deepEqual(calls, ['新城']);
  assert.equal(submit.disabled, true);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(elements.renameCityModal, modal);
  assert.equal(submit.disabled, false);
  assert.equal(player.cityName, '旧城');
  fail = false;
  submitForm();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(player.cityName, '新城');
  assert.equal(elements.renameCityModal, undefined);
  assert.equal(drawerCloses, 1);
  assert.equal(renders, 1);
});
