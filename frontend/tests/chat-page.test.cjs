const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup() {
  const input = { value: '尚未发送的草稿', disabled: false };
  const button = { classList: { add() {}, remove() {} } };
  const box = { innerHTML: '', scrollTop: 0, scrollHeight: 300, clientHeight: 100 };
  const attributes = () => {
    const values = {};
    const classes = new Set();
    return {
      values,
      classes,
      getAttribute: name => values[name],
      setAttribute: (name, value) => { values[name] = value; },
      removeAttribute: name => { delete values[name]; },
      classList: { toggle: (name, active) => active ? classes.add(name) : classes.delete(name) }
    };
  };
  const messageTab = attributes();
  const forumTab = attributes();
  messageTab.setAttribute('data-chat-tab', 'messages');
  forumTab.setAttribute('data-chat-tab', 'forum');
  const messages = { hidden: false };
  const forum = { hidden: true, children: [], querySelector: selector => selector === 'iframe' ? forum.children[0] : null, appendChild(node) { this.children.push(node); } };
  const title = { textContent: '世界频道' };
  const subtitle = { textContent: '实时通联' };
  const page = {
    querySelector: selector => ({ '#chatMessagesPanel': messages, '#chatForumPanel': forum, '#chatPageTitle': title, '#chatPageSubtitle': subtitle })[selector] || null,
    querySelectorAll: selector => selector === '[data-chat-tab]' ? [messageTab, forumTab] : []
  };
  let mounted = false;
  const view = {
    html: '',
    set innerHTML(value) { this.html = value; mounted = value.includes('class="chat-page"'); },
    get innerHTML() { return this.html; },
    querySelector(selector) { return mounted && ['.chat-page', '.page-backbar'].includes(selector) ? {} : null; }
  };
  const classes = new Set();
  const screen = { classList: { toggle(name, enabled) { if (enabled) classes.add(name); else classes.delete(name); } } };
  const nodes = { view, screen, worldChatInput: input, worldChatSendBtn: button, worldChatBox: box, chatMessagesPanel: messages };
  const context = vm.createContext({
    document: {
      getElementById: id => nodes[id] || null,
      querySelector: selector => selector === '.chat-page' && mounted ? page : null,
      createElement: tag => ({ tagName: tag })
    },
    Game: {}, localStorage: { getItem: () => 'test-token' }
  });
  context.window = context;
  context.location = { protocol: 'http:', hostname: 'localhost' };
  for (const name of ['constants', 'core', 'chat', 'main-view']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', name + '.js'), 'utf8'), context);
  }
  const game = context.Game;
  game.Core.renderTop = () => {};
  game.state = game.Core.state = { player: { id: 1, username: 'alice' }, prestige: 10000, world: { incoming: [] } };
  game.Core.route = 'chat';
  return { game, view, box, input, button, classes, messages, forum, messageTab, forumTab, title, subtitle };
}

test('chat route renders world messages and preserves the draft and scroll during background updates', () => {
  const { game, view, input, box, classes } = setup();
  game.Chat.log.push({ id: 1, playerId: 2, username: 'bob', content: '世界消息', ts: Date.now() });
  game.Core.render();
  assert.match(view.innerHTML, /世界消息/);
  assert.ok(classes.has('chat-open'));
  const rendered = view.innerHTML;
  box.scrollTop = 40;
  game.Core.render();
  game.Core.refreshContent();
  game.Core.silentUpdate({});
  assert.equal(view.innerHTML, rendered);
  assert.equal(box.scrollTop, 40);
  assert.equal(input.value, '尚未发送的草稿');
  game.state.prestige = 9999;
  game.Core.silentUpdate({});
  assert.equal(input.disabled, true);
  assert.equal(game.Chat.canSend(), false);
});

test('late chat history populates the dedicated page without replacing the composer', async () => {
  const { game, view, box, input } = setup();
  game.Core.render();
  const rendered = view.innerHTML;
  game.API = {
    worldChatHistory: () => Promise.resolve({ messages: [
      { id: 2, playerId: 2, username: 'bob', avatar: game.Constants.presetAvatars[3].src, content: '服务器历史', ts: Date.now() }
    ] })
  };
  await game.Chat.loadHistory();
  assert.match(box.innerHTML, /服务器历史/);
  assert.match(box.innerHTML, /rank-lieutenant-v1.webp/);
  assert.equal(view.innerHTML, rendered);
  assert.equal(input.value, '尚未发送的草稿');
});

test('forum tab loads once and keeps the chat draft and reading position across switches', () => {
  const { game, input, box, messages, forum, messageTab, forumTab, title } = setup();
  game.Core.render();
  assert.match(game.Core.getForumUrl({ shareReportId: 7, boardId: 2 }), /:5174\?token=test-token&share_report_id=7&boardId=2$/);
  box.scrollTop = 64;
  game.MainView.selectChatTab('forum');
  assert.equal(forum.children.length, 1);
  assert.match(forum.children[0].src, /:5174\?token=test-token$/);
  assert.equal(forum.children[0].referrerPolicy, 'no-referrer');
  assert.equal(messages.hidden, true);
  assert.equal(forum.hidden, false);
  assert.equal(title.textContent, '山河论坛');
  assert.equal(forumTab.getAttribute('aria-current'), 'page');
  assert.equal(messageTab.getAttribute('aria-current'), undefined);
  game.Core.render();
  game.MainView.selectChatTab('messages');
  assert.equal(box.scrollTop, 64);
  assert.equal(input.value, '尚未发送的草稿');
  assert.equal(forum.hidden, true);
  assert.equal(messageTab.getAttribute('aria-current'), 'page');
  game.MainView.selectChatTab('forum');
  assert.equal(forum.children.length, 1);
  assert.equal(forum.children[0].src, 'http://localhost:5174?token=test-token');
});

test('navigation keeps chat immediately before mail and shop on page two with or without city switching', () => {
  const { game } = setup();
  for (const cities of [null, { nav: () => '<button class="navitem">切换</button>' }]) {
    game.Cities = cities;
    const html = game.MainView.navBar();
    const pages = html.split('class="nav-page"').slice(1);
    assert.equal(pages.length, 2);
    assert.ok(pages[0].indexOf('data-route="chat"') < pages[0].indexOf('data-route="mail"'));
    assert.doesNotMatch(pages[0], /data-route="shop"/);
    assert.match(pages[1], /data-route="shop"/);
    assert.match(html, /data-nav-page="1"/);
  }
});
