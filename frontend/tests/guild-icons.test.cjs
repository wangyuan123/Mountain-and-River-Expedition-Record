const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function fixture() {
  const fields = {};
  const calls = [];
  const context = {
    Game: {
      Core: { views: {}, route: 'guild' },
      escapeHtml(value) { return value.replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]); },
      fmt: String,
      API: {
        createGuild(name, icon) { calls.push(['create', name, icon]); return Promise.resolve({}); },
        updateGuildSettings(name, icon) { calls.push(['update', name, icon]); return Promise.resolve({}); }
      },
      toast() {}
    },
    document: { getElementById(id) { return fields[id] || null; } },
    setInterval() {}
  };
  context.window = context;
  vm.createContext(context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/guild.js'), 'utf8'), context);
  context.Game.Guild.reload = () => {};
  return { guild: context.Game.Guild, fields, calls };
}

test('creation offers six emblem models and sends the selected one', async () => {
  const { guild, fields, calls } = fixture();
  const html = guild.renderRecruitment();
  assert.equal((html.match(/class="guild-icon-option"/g) || []).length, 6);
  assert.match(html, /id="guildCreateIcon" value="g01"/);
  for (const file of ['eagle', 'mountain', 'compass', 'anchor', 'wings', 'flame']) {
    assert.ok(fs.existsSync(path.join(__dirname, '../img/guild', file + '.svg')));
  }
  fields.guildName = { value: '  战舞  ' };
  fields.guildCreateIcon = { value: 'g04' };
  guild.create();
  assert.deepEqual(calls, [['create', '战舞', 'g04']]);
});

test('preset and custom choices update the submitted value and pressed state', () => {
  const { guild, fields } = fixture();
  const buttons = ['g01', 'g02'].map(icon => ({ dataset: { icon }, setAttribute(name, value) { this[name] = value; } }));
  const custom = { value: '⚑' };
  const parentNode = {
    querySelectorAll() { return buttons; },
    querySelector() { return custom; }
  };
  fields.guildIcon = { value: '⚑', parentNode };
  fields.guildIconCustom = custom;
  guild.selectIcon('guildIcon', 'g02');
  assert.equal(fields.guildIcon.value, 'g02');
  assert.equal(custom.value, '');
  assert.deepEqual(buttons.map(button => button['aria-pressed']), ['false', 'true']);
  custom.value = '旗';
  guild.customIcon('guildIcon');
  assert.equal(fields.guildIcon.value, '旗');
  assert.deepEqual(buttons.map(button => button['aria-pressed']), ['false', 'false']);
});

test('leader can change a saved emblem; legacy icons remain escaped', () => {
  const { guild, fields, calls } = fixture();
  guild.mine = { joined: true, isManager: true, id: 1, icon: 'g03', name: '战舞', members: [], maxMembers: 30, relations: [] };
  const html = guild.renderMine();
  assert.match(html, /img\/guild\/compass\.svg/);
  assert.match(html, /id="guildIcon" value="g03"/);
  fields.guildCustomName = { value: '战舞' };
  fields.guildIcon = { value: 'g06' };
  guild.saveSettings();
  assert.deepEqual(calls, [['update', '战舞', 'g06']]);
  guild.mine.icon = '<x>';
  assert.match(guild.renderMine(), /&lt;x&gt;/);
  assert.doesNotMatch(guild.renderMine(), /img\/guild\/g/);
});
