const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('./server-gameplay.cjs').vm;

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
    armyCap: () => 90000
  }, fmt: value => Number(value).toLocaleString('en-US'), escapeHtml: value => value };
  load(context, 'data.js');
  load(context, 'player-profile.js');

  context.Game.Core.state.gameplay.leadershipPercent=20;
  context.Game.PlayerProfile.showRankCapInfo();
  const mask = elements.rankCapInfoMask;
  assert.ok(mask);
  assert.equal((mask.innerHTML.match(/<tr(?: class=|>)/g) || []).length, 18);
  assert.match(mask.innerHTML, /rank-cap-current" aria-current="true"[^>]*><td>3<\/td><th scope="row"><span class="rank-cap-label"><svg[^>]*rank-insignia-sergeant/);
  assert.match(mask.innerHTML, /下士 <span>（当前）<\/span>/);
  assert.match(mask.innerHTML, /<td>17<\/td><th scope="row"><span class="rank-cap-label"><svg[^>]*rank-insignia-general/);
  assert.match(mask.innerHTML, /上将<\/span><\/th><td>600,000<\/td>/);
  assert.match(mask.innerHTML, /三军统帅（满级）：\+20%。/);
  assert.match(mask.innerHTML, /当前实际出征带兵上限：90,000/);
  assert.match(mask.innerHTML, /守城不受带兵上限限制/);
  assert.match(mask.innerHTML, /当前可拥有城市上限：3 座<\/strong>（含主城）/);
  assert.match(mask.innerHTML, /<th scope="col">城市上限<\/th>/);
  const cityCaps = [...mask.innerHTML.matchAll(/<td>\d+<\/td><th scope="row">.*?<\/th><td>[\d,]+<\/td><td>(\d+) 座<\/td>/g)]
    .map(match => Number(match[1]));
  assert.deepEqual(cityCaps, [1, 2, 3, 4, 4, 5, 5, 6, 6, 6, 7, 7, 7, 7, 8, 8, 8]);
  assert.match(mask.innerHTML, /上将<\/span><\/th><td>600,000<\/td><td>8 座<\/td>/);
  assert.doesNotMatch(mask.innerHTML, /75,000 ×|每阶增加/);
  assert.doesNotMatch(mask.innerHTML, /当前城市围墙|满级 Lv\.10 后|\+ 100,000/);
  assert.equal(closeButton.focused, true);
  context.Game.PlayerProfile.showRankCapInfo();
  assert.equal(elements.rankCapInfoMask, mask);
  mask.onkeydown({ key: 'Escape' });
  assert.equal(elements.rankCapInfoMask, undefined);
  assert.equal(trigger.focused, true);

  context.Game.Core.state.player.militaryRank = 17;
  context.Game.Core.state.cityOverview = { cap: 8 };
  context.Game.PlayerProfile.showRankCapInfo();
  assert.match(elements.rankCapInfoMask.innerHTML, /当前可拥有城市上限：8 座/);
  doneButton.onclick();
  assert.equal(elements.rankCapInfoMask, undefined);
});

test('profile rank card is an accessible action', () => {
  const context = vm.createContext({ console, document: {}, requestAnimationFrame() {} });
  context.window = context;
  context.Game = {
    Core: { state: { player: { militaryRank: 1, avatarFrame: 'frame-bronze-v1' }, resources: {}, world: {} }, armyCap: () => 25000 },
    MainView: { getCurrentAvatar: () => 'avatar.svg' }, WS: { statusHtml: () => '' },
    fmt: String, escapeHtml: value => value
  };
  let drawer;
  context.document.createElement = () => ({ setAttribute() {}, querySelector() { return null; } });
  context.document.getElementById = () => null;
  context.document.body = { appendChild(element) { drawer = element; } };
  load(context, 'constants.js');
  load(context, 'data.js');
  load(context, 'player-profile.js');
  context.Game.PlayerProfile.openPlayerDrawer();
  assert.match(drawer.innerHTML, /<button type="button" class="drawer-stat-box drawer-stat-action" onclick="Game.Main.showRankCapInfo\(\)" aria-label="查看军衔与带兵上限说明">[\s\S]*?统帅军衔/);
  assert.match(drawer.innerHTML, /<button type="button" class="drawer-stat-box drawer-stat-action" onclick="Game.Main.showRankCapInfo\(\)" aria-label="查看军衔与带兵上限说明">[\s\S]*?带兵上限/);
  assert.match(drawer.innerHTML, /rank-insignia-enlisted/);
  assert.match(drawer.innerHTML, /class="drawer-avatar-wrap frame-bronze-v1"/);
});

test('profile scales both power metrics for display and keeps raw scores and other stats', () => {
  const context = vm.createContext({ console, setInterval() {}, clearInterval() {}, requestAnimationFrame() {} });
  context.window = context;
  context.Game = {
    Core: { state: { player: { militaryRank: 1, formationPower: 1239000000, overallPower: 4173000000 }, resources: {}, world: {} }, armyCap: () => 25000 },
    MainView: { getCurrentAvatar: () => 'avatar.svg' }, WS: { statusHtml: () => '' },
    fmt: value => String(Math.floor(value)), escapeHtml: value => value
  };
  let drawer;
  context.document = { createElement: () => ({ setAttribute() {}, querySelector() { return null; } }),
    getElementById: () => null, body: { appendChild(element) { drawer = element; } } };
  load(context, 'constants.js');
  load(context, 'data.js');
  load(context, 'player-profile.js');
  context.Game.PlayerProfile.openPlayerDrawer();
  assert.match(drawer.innerHTML, /编队战力/);
  assert.ok(drawer.innerHTML.includes('<span title="1239000">123.9万</span>'));
  assert.match(drawer.innerHTML, /综合实力/);
  assert.ok(drawer.innerHTML.includes('<span title="4173000">417.3万</span>'));
  assert.ok(drawer.innerHTML.includes('<span title="25000">25000</span>'));
  assert.equal(context.Game.Core.state.player.formationPower, 1239000000);
  assert.equal(context.Game.Core.state.player.overallPower, 4173000000);
  for (const [raw, expected] of [[12345, '12'], [999, '0'], [0, '0'], [null, '—']]) {
    context.Game.Core.state.player.formationPower = raw;
    context.Game.Core.state.player.overallPower = raw;
    context.Game.PlayerProfile.openPlayerDrawer();
    const value = raw == null ? '—' : '<span title="' + expected + '">' + expected + '</span>';
    for (const label of ['编队战力', '综合实力']) {
      assert.ok(drawer.innerHTML.includes('<div class="stat-k">' + label + '</div>        <div class="stat-v highlight">' + value + '</div>'));
    }
  }
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

test('avatar picker hides custom avatar controls for VIP commanders while preserving the saved portrait', () => {
  const elements = {};
  const context = vm.createContext({
    document: {
      getElementById(id) { return elements[id] || null; },
      querySelector() { return null; },
      querySelectorAll() { return []; },
      createElement(tag) {
        const el = {
          tagName: tag.toUpperCase(),
          style: {},
          setAttribute() {},
          appendChild() {},
          remove() { delete elements[el.id]; }
        };
        Object.defineProperty(el, 'id', {
          set(v) { this._id = v; elements[v] = this; },
          get() { return this._id; }
        });
        return el;
      },
      body: { appendChild(el) { elements[el.id] = el; } }
    },
    Game: {
      escapeHtml(str) { return String(str); },
      Core: {
        state: {
          player: {
            username: 'vip10_player',
            vipLevel: 10,
            avatar: 'img/avatars/historical/rank-colonel-v1.webp',
            customAvatar: 'https://example.com/custom.png'
          }
        },
        getCurrentAvatar() { return 'https://example.com/custom.png'; }
      },
      MainView: {
        getCurrentAvatar() { return 'https://example.com/custom.png'; },
        presetAvatars: [
          { src: 'img/avatars/historical/rank-private-v1.webp', name: '列兵' },
          { src: 'img/avatars/historical/rank-general-v1.webp', name: '将军' }
        ]
      },
      toast() {}
    }
  });
  context.window = context;
  load(context, 'player-profile.js');
  context.Game.Main = context.Game.PlayerProfile;

  context.Game.Main.openAvatarPicker();
  const modal = elements.avatarPickerMask;
  assert.doesNotMatch(modal.innerHTML, /avatarPickerVipUrl|avatarPickerVipFile|avatarPickerVipUploadBtn|应用链接|本地上传|自定义|外链/);
  assert.match(modal.innerHTML, /https:\/\/example\.com\/custom\.png/);
  assert.match(modal.innerHTML, /历史军人预设头像/);
  assert.match(modal.innerHTML, /支持佩戴统帅专属头像框/);
  assert.equal(context.Game.Core.state.player.customAvatar, 'https://example.com/custom.png');
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
  context.Game = {};
  require('./load-constants.cjs')(context);
  load(context, 'core.js');
  load(context, 'player-profile.js');
  context.Game.Core.state = { player, resources: { gold: 120 } };
  context.Game.Core.render = () => { renders++; };
  context.Game.API = { setCityName(name) { calls.push(name); return fail ? Promise.reject(new Error('网络错误')) : Promise.resolve(); } };
  context.Game.toast = message => { messages.push(message); };
  context.Game.Main = context.Game.PlayerProfile;
  context.Game.Main.closePlayerDrawer = () => { drawerCloses++; };
  context.Game.Main.promptRenameCityInDrawer();
  const modal = elements.renameCityModal;
  assert.match(modal.innerHTML, /未持有城市改名卡，将消耗 60 黄金/);
  const input = modal.controls['#renameCityInput'];
  const submit = modal.controls['.account-confirm-submit'];
  const form = modal.controls['#renameCityForm'];
  const submitForm = () => form.onsubmit({ preventDefault() {} });

  input.value = ' '; submitForm();
  input.value = '超长城市名称一二三四五六七八九'; submitForm();
  input.value = '张三\n城'; submitForm();
  input.value = '旧城'; submitForm();
  assert.deepEqual(messages, ['城市名称不能为空', '城市名称最多12个字符', '城市名称不能包含换行或不可见字符', '新城市名称与当前名称相同']);
  assert.deepEqual(calls, []);
  input.value = ' 烈火·前哨☆ '; submitForm(); submitForm();
  assert.deepEqual(calls, ['烈火·前哨☆']);
  assert.equal(submit.disabled, true);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(elements.renameCityModal, modal);
  assert.equal(submit.disabled, false);
  assert.equal(player.cityName, '旧城');
  fail = false;
  submitForm();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(player.cityName, '烈火·前哨☆');
  assert.equal(elements.renameCityModal, undefined);
  assert.equal(drawerCloses, 1);
  assert.equal(renders, 1);
});

test('city rename shows daily reset and blocks another request that day', () => {
  const elements = {};
  const messages = [];
  const document = {
    getElementById(id) { return elements[id] || null; },
    addEventListener() {}, removeEventListener() {},
    createElement() {
      const controls = {};
      for (const selector of ['#renameCityInput', '.account-confirm-cancel', '.account-confirm-submit', '#renameCityForm']) {
        controls[selector] = { focus() {} };
      }
      return { controls, querySelector(selector) { return controls[selector]; }, remove() { delete elements[this.id]; } };
    },
    body: { appendChild(element) { elements[element.id] = element; } }
  };
  const context = vm.createContext({ console, document, Date });
  context.window = context;
  context.Game = {
    Core: { state: { player: { cityName: '北城', cityNameRenameAvailableAt: Date.now() + 60000 } } },
    API: { setCityName() { throw Error('不应发送改名请求'); } },
    toast(message) { messages.push(message); }
  };
  load(context, 'player-profile.js');
  context.Game.PlayerProfile.promptRenameCityInDrawer();
  const modal = elements.renameCityModal;
  assert.match(modal.innerHTML, /北京时间 0:00 重置/);
  assert.match(modal.innerHTML, /这座城市今日已修改/);
  assert.match(modal.innerHTML, /城市改名卡或黄金不足/);
  modal.controls['#renameCityForm'].onsubmit({ preventDefault() {} });
  assert.match(messages[0], /请明日 0:00 后再试/);
});

test('commander drawer locks header and footer to viewport height while drawer-body scrolls', () => {
  const context = vm.createContext({ console, document: {}, requestAnimationFrame() {} });
  context.window = context;
  context.Game = {
    Core: {
      state: {
        player: { militaryRank: 1, cityName: '主城', posX: 10, posY: 20 },
        resources: {},
        officerEquipment: { achievements: [{ name: '神兵利器', unlocked: true }] }
      },
      armyCap: () => 25000,
      morale: () => 80
    },
    MainView: { getCurrentAvatar: () => 'avatar.svg' },
    WS: { statusHtml: () => '' },
    fmt: String,
    escapeHtml: value => value
  };
  let drawerElement = null;
  const classes = new Set();
  context.document.createElement = () => ({ setAttribute() {}, querySelector() { return null; } });
  context.document.getElementById = () => null;
  context.document.body = {
    classList: {
      add(cls) { classes.add(cls); },
      remove(cls) { classes.delete(cls); },
      contains(cls) { return classes.has(cls); }
    },
    appendChild(el) { drawerElement = el; }
  };
  load(context, 'constants.js');
  load(context, 'data.js');
  load(context, 'player-profile.js');

  context.Game.PlayerProfile.openPlayerDrawer();
  const html = drawerElement.innerHTML;
  assert.equal(context.document.body.classList.contains('drawer-open'), true);

  // Header, body, and footer structure
  assert.match(html, /<div class="drawer-header">/);
  assert.match(html, /<div class="drawer-body">/);
  assert.match(html, /<div class="drawer-footer">/);
  assert.match(html, /切换主题/);
  assert.match(html, /系统设置/);

  // Achievement details are placed inside drawer-body before drawer-footer
  assert.match(html, /<details class="drawer-awards-details">[\s\S]*?<\/div><div class="drawer-footer">/);

  // CSS rules check
  const css = fs.readFileSync(path.join(__dirname, '../css/style.css'), 'utf8');
  assert.match(css, /\.player-drawer-mask\s*\{[^}]*position:\s*fixed(?:\s*!important)?;/);
  assert.match(css, /\.player-drawer-mask\s*\{[^}]*height:\s*100dvh(?:\s*!important)?;/);
  assert.match(css, /\.player-drawer\s*\{[^}]*position:\s*fixed(?:\s*!important)?;/);
  assert.match(css, /\.player-drawer\s*\{[^}]*height:\s*100dvh(?:\s*!important)?;/);
  assert.match(css, /\.player-drawer\s*\{[^}]*max-height:\s*100dvh(?:\s*!important)?;/);
  assert.match(css, /\.player-drawer\s*\{[^}]*display:\s*flex(?:\s*!important)?;/);
  assert.match(css, /\.player-drawer\s*\{[^}]*flex-direction:\s*column(?:\s*!important)?;/);
  assert.match(css, /\.drawer-body\s*\{[^}]*flex:\s*1 1 0%(?:\s*!important)?;/);
  assert.match(css, /\.drawer-body\s*\{[^}]*min-height:\s*0(?:\s*!important)?;/);
  assert.match(css, /\.drawer-body\s*\{[^}]*overflow-y:\s*auto(?:\s*!important)?;/);
  assert.match(css, /\.drawer-footer\s*\{[^}]*flex:\s*0 0 auto(?:\s*!important)?;/);
  assert.match(css, /\.drawer-footer\s*\{[^}]*margin-top:\s*auto(?:\s*!important)?;/);
  assert.match(css, /\.drawer-footer\s*\{[^}]*position:\s*sticky(?:\s*!important)?;/);
  assert.match(css, /\.drawer-footer\s*\{[^}]*bottom:\s*0(?:\s*!important)?;/);
});

test('updateDrawerHeight synchronizes drawer height with real visible viewport in portrait and landscape', () => {
  const styles = {};
  const drawer = { style: {} };
  const mask = { style: {} };
  const listeners = {};
  const win = {
    innerHeight: 640,
    addEventListener(evt, fn) { listeners[evt] = fn; },
    visualViewport: {
      height: 600,
      addEventListener(evt, fn) { listeners['vv_' + evt] = fn; }
    }
  };
  const doc = {
    documentElement: {
      style: {
        setProperty(k, v) { styles[k] = v; }
      }
    },
    querySelector(sel) { return sel === '.player-drawer' ? drawer : null; },
    getElementById(id) { return id === 'playerDrawerMask' ? mask : null; }
  };
  const context = vm.createContext({ console, window: win, document: doc, Game: {} });
  win.window = win;
  load(context, 'constants.js');
  load(context, 'data.js');
  load(context, 'player-profile.js');

  context.window.Game.PlayerProfile.updateDrawerHeight();
  assert.equal(styles['--drawer-height'], '600px');
  assert.equal(drawer.style.height, '600px');
  assert.equal(drawer.style.maxHeight, '600px');
  assert.equal(mask.style.height, '600px');
  assert.equal(mask.style.maxHeight, '600px');

  // Rotate to landscape
  win.innerHeight = 380;
  win.visualViewport.height = 360;
  listeners.orientationchange();
  assert.equal(styles['--drawer-height'], '360px');
  assert.equal(drawer.style.height, '360px');
  assert.equal(mask.style.height, '360px');
});

test('commander profile drawer does not display player UID', () => {
  const context = vm.createContext({ console, setInterval() {}, clearInterval() {}, requestAnimationFrame() {} });
  context.window = context;
  context.Game = {
    Core: { state: { player: { id: 5, militaryRank: 1, vipLevel: 10, faction: 'allied' }, resources: {}, world: {} }, armyCap: () => 25000 },
    MainView: { getCurrentAvatar: () => 'avatar.svg' }, WS: { statusHtml: () => '' },
    fmt: String, escapeHtml: value => value
  };
  let drawer;
  context.document = {
    createElement: () => ({ setAttribute() {}, querySelector() { return null; } }),
    getElementById: () => null,
    body: { classList: { add() {} }, appendChild(element) { drawer = element; } }
  };
  load(context, 'constants.js');
  load(context, 'data.js');
  load(context, 'player-profile.js');

  context.Game.PlayerProfile.openPlayerDrawer();
  assert.ok(drawer);
  assert.doesNotMatch(drawer.innerHTML, /drawer-uid-tag/);
  assert.doesNotMatch(drawer.innerHTML, /UID:\s*5/);
  assert.doesNotMatch(drawer.innerHTML, /UID:/i);
});
