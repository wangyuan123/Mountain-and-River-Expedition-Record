const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function fixture(customData = {}) {
  const fields = {};
  const calls = [];
  const context = {
    Game: {
      Core: { views: {}, route: 'guild' },
      escapeHtml(value) { return String(value).replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]); },
      fmt: String,
      API: {
        createGuild(name, icon) { calls.push(['create', name, icon]); return Promise.resolve({}); },
        updateGuildSettings(name, icon) { calls.push(['updateSettings', name, icon]); return Promise.resolve({}); },
        updateGuildNotice(notice) { calls.push(['updateNotice', notice]); return Promise.resolve({}); },
        updateGuildRelation(guildId, status) { calls.push(['updateRelation', guildId, status]); return Promise.resolve({}); },
        updateGuildRole(playerId, role) { calls.push(['updateRole', playerId, role]); return Promise.resolve({}); },
        transferGuildLeadership(playerId) { calls.push(['transferLeadership', playerId]); return Promise.resolve({}); },
        removeGuildMember(playerId) { calls.push(['remove', playerId]); return Promise.resolve({}); },
        reviewGuildApplication(id, approved) { calls.push(['review', id, approved]); return Promise.resolve({}); },
        leaveGuild() { calls.push(['leave']); return Promise.resolve({}); }
      },
      toast(msg) { calls.push(['toast', msg]); }
    },
    document: {
      getElementById(id) {
        if (!fields[id]) {
          fields[id] = {
            id,
            style: { display: '' },
            classList: {
              classes: new Set(),
              add(c) { this.classes.add(c); },
              remove(c) { this.classes.delete(c); },
              contains(c) { return this.classes.has(c); }
            },
            value: ''
          };
        }
        return fields[id];
      }
    },
    setInterval() {}
  };
  context.window = context;
  vm.createContext(context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/guild.js'), 'utf8'), context);
  context.Game.Guild.reload = () => { calls.push(['reload']); };
  return { guild: context.Game.Guild, fields, calls, context };
}

test('renderMine renders emblem, notice, prestige sum, and 4 distinct feature tabs', () => {
  const { guild } = fixture();
  guild.mine = {
    joined: true,
    id: 10,
    name: '铁血第一旅',
    icon: 'g01',
    leaderName: '将军甲',
    role: 'leader',
    isLeader: true,
    isManager: true,
    maxMembers: 30,
    notice: '人在阵地在！',
    members: [
      { playerId: 1, name: '将军甲', role: 'leader', online: true, prestige: 500, cityName: '帝都' },
      { playerId: 2, name: '校尉乙', role: 'admin', online: false, prestige: 300, cityName: '边城' },
      { playerId: 3, name: '士官丙', role: 'member', online: true, prestige: 200, cityName: '前哨' }
    ],
    relations: [
      { guildId: 20, status: 'hostile', name: '敌对先锋' },
      { guildId: 30, status: 'friendly', name: '同盟兄弟' }
    ],
    applications: [
      { id: 99, playerId: 5, name: '新兵丁', prestige: 150 }
    ]
  };

  guild.list = [
    { id: 10, name: '铁血第一旅', icon: 'g01', leaderName: '将军甲', prestige: 1000, members: 3, maxMembers: 30 },
    { id: 20, name: '敌对先锋', icon: 'g02', leaderName: '黑煞', prestige: 1800, members: 5, maxMembers: 30 },
    { id: 30, name: '同盟兄弟', icon: 'g03', leaderName: '白羽', prestige: 800, members: 2, maxMembers: 30 }
  ];

  const html = guild.renderMine();

  // 1. 核心展示：团徽、名称、成员声望总和说明、编制与团长
  assert.match(html, /img\/guild\/eagle\.svg/);
  assert.match(html, /铁血第一旅/);
  assert.match(html, /军团声望: <strong>★1000<\/strong>/);
  assert.match(html, /由全体成员声望总和组成/);
  assert.match(html, /将军甲/);
  assert.match(html, /3\/30 人/);

  // 2. 军团公告卡片
  assert.match(html, /📢 军团公告/);
  assert.match(html, /人在阵地在！/);
  assert.match(html, /修改公告/);

  // 3. 四大功能分栏 Tabs
  assert.match(html, /id="guildTabBtn_members"/);
  assert.match(html, /id="guildTabBtn_rankings"/);
  assert.match(html, /id="guildTabBtn_diplomacy"/);
  assert.match(html, /id="guildTabBtn_manage"/);
  assert.match(html, /待审核 1 份/);

  // 4. Tab 1: 成员管理功能
  assert.match(html, /id="guildTabPanel_members"/);
  assert.match(html, /校尉乙/);
  assert.match(html, /取消管理/);
  assert.match(html, /转让团长/);
  assert.match(html, /移出/);
  assert.match(html, /\[写信\]/);

  // 5. Tab 2: 全服排名
  assert.match(html, /id="guildTabPanel_rankings"/);
  assert.match(html, /全服军团荣誉榜/);
  assert.match(html, /🥇/); // 敌对先锋 (1800) 第一
  assert.match(html, /🥈/); // 铁血第一旅 (1000) 第二
  assert.match(html, /🥉/); // 同盟兄弟 (800) 第三
  assert.match(html, /\(本军团\)/);

  // 6. Tab 3: 同盟外交关系
  assert.match(html, /id="guildTabPanel_diplomacy"/);
  assert.match(html, /敌对交战/);
  assert.match(html, /友好同盟/);
  assert.match(html, /设为友好/);
  assert.match(html, /重置中立/);

  // 7. Tab 4: 军团政务（新兵审核、改名团徽、退出解散）
  assert.match(html, /id="guildTabPanel_manage"/);
  assert.match(html, /新兵丁/);
  assert.match(html, /\[同意入伍\]/);
  assert.match(html, /\[拒绝申请\]/);
  assert.match(html, /id="guildCustomName"/);
  assert.match(html, /id="guildIcon"/);
  assert.match(html, /\[解散军团\]/);

  // 8. 确保没有硬币 emoji 🪙
  assert.doesNotMatch(html, /🪙/);
});

test('setTab switches tab buttons active class and toggles panel display', () => {
  const { guild, fields } = fixture();
  guild.setTab('rankings');
  assert.equal(guild.currentTab, 'rankings');
  assert.equal(fields.guildTabBtn_rankings.classList.contains('active'), true);
  assert.equal(fields.guildTabBtn_members.classList.contains('active'), false);
  assert.equal(fields.guildTabPanel_rankings.style.display, 'block');
  assert.equal(fields.guildTabPanel_members.style.display, 'none');

  guild.setTab('diplomacy');
  assert.equal(guild.currentTab, 'diplomacy');
  assert.equal(fields.guildTabBtn_diplomacy.classList.contains('active'), true);
  assert.equal(fields.guildTabBtn_rankings.classList.contains('active'), false);
  assert.equal(fields.guildTabPanel_diplomacy.style.display, 'block');
  assert.equal(fields.guildTabPanel_rankings.style.display, 'none');
});

test('renderRecruitment displays 10000 gold cost with official gold icon and prevents creation when gold is insufficient', () => {
  const { guild, fields, calls, context } = fixture();
  context.Game.Core.state = {
    resources: { gold: 5000 },
    player: { name: '长官A', prestige: 100 }
  };
  const html = guild.renderRecruitment();
  assert.match(html, /10,000 黄金/);
  assert.match(html, /img\/resources\/models\/gold\.webp/);
  assert.doesNotMatch(html, /🪙/);

  fields.guildName = { value: '近卫军' };
  fields.guildCreateIcon = { value: 'g01' };
  guild.create();

  // 应该提示黄金不足并被阻拦，不触发创建接口
  assert.equal(calls.some(c => c[0] === 'create'), false);
  assert.equal(calls.some(c => c[0] === 'toast' && c[1].includes('黄金不足')), true);

  // 黄金充足时（例如 12000 黄金），正常发起创建
  context.Game.Core.state.resources.gold = 12000;
  guild.create();
  assert.equal(calls.some(c => c[0] === 'create' && c[1] === '近卫军'), true);
});

test('guild tabs navigation supports horizontal scroll and scrolls active tab into view', () => {
  const css = fs.readFileSync(path.join(__dirname, '../css/guild.css'), 'utf8');
  assert.match(css, /\.guild-tabs-nav\s*\{[^}]*overflow-x:\s*auto;/);
  assert.match(css, /\.guild-tabs-nav\s*\{[^}]*scrollbar-width:\s*thin;/);
  assert.match(css, /\.guild-tab-btn\s*\{[^}]*flex:\s*1\s+0\s+auto;/);
  assert.match(css, /\.guild-tab-btn\s*\{[^}]*min-width:\s*max-content;/);

  const { guild, fields } = fixture();
  let scrolled = false;
  fields.guildTabBtn_manage = {
    classList: {
      classes: new Set(),
      add(c) { this.classes.add(c); },
      remove(c) { this.classes.delete(c); },
      contains(c) { return this.classes.has(c); }
    },
    scrollIntoView(options) {
      scrolled = true;
      assert.equal(options.inline, 'nearest');
    }
  };

  guild.setTab('manage');
  assert.equal(guild.currentTab, 'manage');
  assert.equal(scrolled, true);
});

