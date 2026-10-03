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
      Constants: {
        chatMax: 100,
        chatMinPrestige: 10000,
        chatCooldownSec: 5,
        presetAvatars: [{ src: 'avatar1.png' }]
      },
      escapeHtml(value) { return String(value).replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]); },
      fmt: String,
      API: {
        getGuildChatHistory() {
          calls.push(['getGuildChatHistory']);
          return Promise.resolve({
            messages: [
              { id: 1, guildId: 10, playerId: null, username: '系统', role: 'system', content: '【军团外交】我团与「先锋营」结为友好同盟！', ts: 1000, avatar: null },
              { id: 2, guildId: 10, playerId: 5, username: '赵统领', role: 'leader', content: '全员准备！', ts: 2000, avatar: 'avatar1.png' }
            ]
          });
        },
        sendGuildChat(content) {
          calls.push(['sendGuildChat', content]);
          return Promise.resolve({ id: 3, guildId: 10, playerId: 5, username: '赵统领', role: 'leader', content, ts: 3000, avatar: 'avatar1.png' });
        }
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
            value: '',
            children: [],
            innerHTML: '',
            insertAdjacentHTML(position, text) {
              this.innerHTML += text;
              this.children.push({ tagName: 'DIV', innerHTML: text });
            },
            appendChild(child) { this.children.push(child); },
            removeChild(child) { const idx = this.children.indexOf(child); if (idx >= 0) this.children.splice(idx, 1); }
          };
        }
        return fields[id];
      },
      createElement(tag) {
        return {
          tagName: tag.toUpperCase(),
          className: '',
          innerHTML: '',
          children: [],
          appendChild(child) { this.children.push(child); }
        };
      }
    },
    setInterval() {},
    clearInterval() {}
  };
  context.window = context;
  vm.createContext(context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/chat.js'), 'utf8'), context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/guild.js'), 'utf8'), context);
  return { chat: context.Game.Chat, guild: context.Game.Guild, fields, calls, context };
}

test('guild chat sending and history loading', async () => {
  const { chat, calls } = fixture();

  // 1. 加载历史
  await chat.loadGuildHistory();
  assert.equal(calls.some(c => c[0] === 'getGuildChatHistory'), true);
  assert.equal(chat.guildLog.length, 2);
  assert.equal(chat.guildLog[0].username, '系统');
  assert.equal(chat.guildLog[1].username, '赵统领');
  assert.equal(chat.guildLog[1].role, 'leader');

  // 2. 发送消息
  await chat.sendGuild('报告，先遣队已就绪！');
  assert.equal(calls.some(c => c[0] === 'sendGuildChat' && c[1] === '报告，先遣队已就绪！'), true);
  assert.equal(chat.getGuildCooldown() > 0, true);

  // 3. 冷却中不允许再次发送
  await assert.rejects(async () => {
    await chat.sendGuild('快速重复发送');
  }, /发言过于频繁/);
});

test('renderGuildMessageHtml correctly marks role tags and system messages', () => {
  const { chat } = fixture();
  const leaderMsg = {
    id: 1,
    type: 'player',
    username: '张团长',
    role: 'leader',
    content: '集合出征！',
    ts: Date.now(),
    avatar: 'avatar1.png'
  };
  const adminMsg = {
    id: 2,
    type: 'player',
    username: '李参谋',
    role: 'admin',
    content: '路线已标记。',
    ts: Date.now(),
    avatar: 'avatar1.png'
  };
  const sysMsg = {
    id: 3,
    type: 'system',
    username: '系统',
    role: 'system',
    content: '【军团外交】我团与「先锋营」结为友好同盟！',
    ts: Date.now(),
    avatar: null
  };

  const htmlLeader = chat.renderGuildMessageHtml(leaderMsg);
  assert.match(htmlLeader, /tag-guild-leader/);
  assert.match(htmlLeader, /\[团长\]/);
  assert.match(htmlLeader, /张团长/);

  const htmlAdmin = chat.renderGuildMessageHtml(adminMsg);
  assert.match(htmlAdmin, /tag-guild-admin/);
  assert.match(htmlAdmin, /\[管理\]/);

  const htmlSys = chat.renderGuildMessageHtml(sysMsg);
  assert.match(htmlSys, /tag-system/);
  assert.match(htmlSys, /【军团外交】/);
});

test('guild renderMine contains 💬 军团频道 tab and terminal box', () => {
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
      { playerId: 1, name: '将军甲', role: 'leader', online: true, prestige: 500, cityName: '帝都' }
    ],
    relations: [],
    applications: []
  };

  const html = guild.renderMine();
  assert.match(html, /id="guildTabBtn_chat"/);
  assert.match(html, /💬 军团频道/);
  assert.match(html, /id="guildTabPanel_chat"/);
  assert.match(html, /COMM-LINK \/\/ 军团加密内频/);
  assert.match(html, /id="guildChatBox"/);
  assert.match(html, /id="guildChatInput"/);
  assert.match(html, /id="guildChatSendBtn"/);
});

test('sendGuild immediately appends message to guildLog and DOM without reload', async () => {
  const { chat, fields } = fixture();
  // Before sending
  assert.equal(chat.guildLog.length, 0);

  // Send message
  await chat.sendGuild('第一道防线已构筑！');

  // Immediately visible in guildLog
  assert.equal(chat.guildLog.length, 1);
  assert.equal(chat.guildLog[0].id, 3);
  assert.equal(chat.guildLog[0].content, '第一道防线已构筑！');
  assert.equal(chat.guildLog[0].role, 'leader');

  // Also check that it appended to guildChatBox
  const box = fields.guildChatBox;
  assert.equal(box.children.length, 1);
});

test('ws-client routes guild_chat message to registered listener', () => {
  const context = {
    Game: {},
    console,
    Date,
    JSON,
    setTimeout,
    clearTimeout,
    setInterval,
    clearInterval
  };
  context.window = context;
  vm.createContext(context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/ws-client.js'), 'utf8'), context);

  let receivedData = null;
  context.Game.WS.on('guild_chat', (data) => {
    receivedData = data;
  });

  // Simulate WebSocket receiving a guild_chat message
  context.Game.WS.handleMessage(JSON.stringify({
    type: 'guild_chat',
    data: { id: 88, guildId: 10, content: '敌袭警报！', username: '哨兵', role: 'member', ts: 5000 }
  }));

  assert.notEqual(receivedData, null);
  assert.equal(receivedData.id, 88);
  assert.equal(receivedData.content, '敌袭警报！');
});

test('chat-msg-self places avatar on the right and wraps sender tag in header', () => {
  const css = fs.readFileSync(path.join(__dirname, '../css/style.css'), 'utf8');
  assert.match(css, /\.chat-msg\.chat-msg-self\s+\.chat-avatar\s*\{[^}]*order:\s*2/);
  assert.match(css, /\.chat-msg\.chat-msg-self\s+\.chat-msg-body\s*\{[^}]*order:\s*1/);
  assert.match(css, /\.chat-msg-header/);

  const themeCss = fs.readFileSync(path.join(__dirname, '../css/themes.css'), 'utf8');
  assert.match(themeCss, /\.chat-msg\.chat-msg-self\s+\.chat-avatar\s*\{[^}]*order:\s*2\s*!important/);
  assert.match(themeCss, /\.chat-msg\.chat-msg-self\s+\.chat-msg-body\s*\{[^}]*order:\s*1\s*!important/);

  const { chat } = fixture();
  const html = chat.renderGuildMessageHtml({
    id: 99,
    playerId: 1,
    username: '我',
    role: 'leader',
    content: '测试消息',
    avatar: 'avatar1.png'
  });
  assert.match(html, /class="chat-msg-header"/);
});

