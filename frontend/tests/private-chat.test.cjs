const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
function setup() {
  const nodes = {
    privateChatListPanel: { hidden: false }, privateChatDialogPanel: { hidden: true },
    privateChatInput: { value: '' }, privateChatSend: {}, privateChatTitle: {},
    privateChatBox: { innerHTML: '', scrollHeight: 100, scrollTop: 0, clientHeight: 100 },
    chatMessagesPanel: { hidden: false }
  };
  nodes.privateChatConversations = { children: [], appendChild(child) { this.children.push(child); } };
  Object.defineProperty(nodes.privateChatConversations, 'innerHTML', { set() { this.children = []; } });
  const reads = [];
  const histories = {};
  const game = {
    Core: { route: 'chat', state: { player: { id: 1 } } },
    Chat: { renderMessageHtml: m => m.content },
    API: {
      privateChatOpen: peerId => Promise.resolve({ id: peerId, online: true, available: true }),
      privateChatHistory: id => Promise.resolve(histories[id] || { messages: [] }),
      privateChatRead: (id, through) => { reads.push([id, through]); return Promise.resolve(); },
      privateChatConversations: () => Promise.resolve([])
    }
  };
  const ctx = { window: { Game: game }, document: { getElementById: id => nodes[id] || null, createElement: () => ({ children: [], classList: { toggle() {} }, appendChild(child) { this.children.push(child); }, get innerHTML() { return this.children.length ? this.children[this.children.length - 1].innerHTML : this.html; }, set innerHTML(value) { this.html = value; }, onclick() { return this.children[this.children.length - 1].onclick(); } }) }, Promise, Date };
  vm.runInNewContext(fs.readFileSync(require('node:path').join(__dirname, '../js/private-chat.js'), 'utf8'), ctx);
  return { game, nodes, reads, histories };
}
test('hidden private conversations stay unread, visible ones mark through the displayed message', async () => {
  const { game, reads, histories, nodes } = setup();
  histories[2] = { messages: [{ id: 4, playerId: 2, recipientId: 1, content: 'hello' }] };
  await game.PrivateChat.open({ id: 2, username: 'bob' });
  assert.equal(reads.length, 0);
  game.PrivateChat.active = true;
  await game.PrivateChat.refresh();
  assert.deepEqual(reads, [[2, 4]]);
  nodes.chatMessagesPanel.hidden = true;
  await game.PrivateChat.refresh();
  assert.equal(reads.length, 1);
});
test('switching peers preserves drafts and discards late history from the previous peer', async () => {
  const { game, nodes } = setup();
  let resolveOld;
  game.API.privateChatHistory = id => id === 2 ? new Promise(r => { resolveOld = r; }) : Promise.resolve({ messages: [{ id: 8, content: 'new peer' }] });
  const old = game.PrivateChat.open({ id: 2, username: 'bob' });
  await Promise.resolve();
  nodes.privateChatInput.value = 'draft for bob';
  await game.PrivateChat.open({ id: 3, username: 'charlie' });
  resolveOld({ messages: [{ id: 2, content: 'wrong peer' }] });
  await old;
  assert.equal(nodes.privateChatBox.innerHTML, 'new peer');
  game.API.privateChatHistory = () => Promise.resolve({ messages: [] });
  await game.PrivateChat.open({ id: 2, username: 'bob' });
  assert.equal(nodes.privateChatInput.value, 'draft for bob');
});
test('send keeps the captured recipient and does not erase the next peer draft', async () => {
  const { game, nodes } = setup();
  await game.PrivateChat.open({ id: 2, username: 'bob' });
  let finish;
  let recipient;
  game.API.sendPrivateChat = (id, text) => {
    recipient = id;
    return new Promise(resolve => { finish = () => resolve({ id: 10, playerId: 1, recipientId: id, content: text }); });
  };
  nodes.privateChatInput.value = 'hello bob';
  const pending = game.PrivateChat.send();
  await game.PrivateChat.open({ id: 3, username: 'charlie' });
  nodes.privateChatInput.value = 'draft charlie';
  finish();
  await pending;
  assert.equal(recipient, 2);
  assert.equal(nodes.privateChatInput.value, 'draft charlie');
  assert.equal(nodes.privateChatBox.innerHTML.includes('hello bob'), false);
});

test('private channel opens a multi-player list and returns there without consuming unread', async () => {
  const { game, nodes, reads, histories } = setup();
  histories[2] = { messages: [{ id: 4, playerId: 2, recipientId: 1, content: 'hello' }] };
  game.PrivateChat.active = true;
  await game.PrivateChat.open({ id: 2, username: 'bob' });
  assert.equal(nodes.privateChatListPanel.hidden, true);
  assert.equal(nodes.privateChatDialogPanel.hidden, false);
  assert.equal(nodes.privateChatTitle.textContent, 'bob · 在线');
  await game.PrivateChat.showList();
  assert.equal(nodes.privateChatListPanel.hidden, false);
  assert.equal(nodes.privateChatDialogPanel.hidden, true);
  const readCount = reads.length;
  await game.PrivateChat.refresh();
  assert.equal(reads.length, readCount);
});

test('list retains multiple conversations including an empty one, each opens its own dialog', async () => {
  const { game, nodes } = setup();
  const rows = [
    { peer: { id: 2, username: 'bob', online: true }, lastMessage: { content: 'hello' }, unread: 2 },
    { peer: { id: 3, username: 'charlie', online: false }, lastMessage: null, unread: 0 }
  ];
  game.API.privateChatConversations = () => Promise.resolve(rows);
  await game.PrivateChat.showList();
  assert.equal(nodes.privateChatConversations.children.length, 2);
  assert.ok(nodes.privateChatConversations.children[1].innerHTML.includes('还没有消息'));
  await nodes.privateChatConversations.children[0].onclick();
  assert.equal(nodes.privateChatTitle.textContent, 'bob · 在线');
  await game.PrivateChat.showList();
  await nodes.privateChatConversations.children[1].onclick();
  assert.equal(nodes.privateChatTitle.textContent, 'charlie · 离线');
});
test('avatar entry uses player ID and directly opens dialog after entering chat', async () => {
  const { game, nodes } = setup();
  let opened;
  game.go = route => { assert.equal(route, 'chat'); };
  game.MainView = { selectChatChannel: channel => { assert.equal(channel, 'private'); return game.PrivateChat.showList(); } };
  game.API.privateChatOpen = id => { opened = id; return Promise.resolve({ id, username: 'new name', online: true }); };
  await game.PrivateChat.start('old name', '2');
  assert.equal(opened, 2);
  assert.equal(nodes.privateChatDialogPanel.hidden, false);
  assert.equal(nodes.privateChatTitle.textContent, 'new name · 在线');
});
test('returning to list while sending keeps its panel open and safely completes the send', async () => {
  const { game, nodes } = setup();
  await game.PrivateChat.open({ id: 2, username: 'bob' });
  let finish;
  game.API.sendPrivateChat = () => new Promise(resolve => { finish = () => resolve({ id: 10, playerId: 1, recipientId: 2, content: 'hello' }); });
  nodes.privateChatInput.value = 'hello';
  const pending = game.PrivateChat.send();
  await game.PrivateChat.showList();
  finish();
  await pending;
  assert.equal(nodes.privateChatDialogPanel.hidden, true);
  assert.equal(nodes.privateChatListPanel.hidden, false);
});

test('unread total updates navigation outside chat and clears when the server reports all read', async () => {
  const { game, nodes } = setup();
  delete nodes.privateChatConversations;
  delete nodes.privateChatBox;
  game.Core.route = 'home';
  let redraws = 0;
  game.Main = { renderNavBar: () => { redraws++; } };
  game.API.privateChatConversations = () => Promise.resolve([{ unread: 2 }, { unread: 3 }]);
  await game.PrivateChat.refresh();
  assert.equal(game.PrivateChat.unread(), 5);
  assert.equal(redraws, 1);
  await game.PrivateChat.refresh();
  assert.equal(redraws, 1);
  game.API.privateChatConversations = () => Promise.resolve([{ unread: 0 }]);
  await game.PrivateChat.refresh();
  assert.equal(game.PrivateChat.unread(), 0);
  assert.equal(redraws, 2);
});

test('repeated status refresh preserves message nodes, scroll, draft and conversation buttons', async () => {
  const { game, nodes, histories } = setup();
  let writes = 0;
  let html = '';
  Object.defineProperty(nodes.privateChatBox, 'innerHTML', {
    get: () => html,
    set: value => { writes++; html = value; }
  });
  histories[2] = { messages: [{ id: 4, content: 'hello' }] };
  let online = true;
  game.API.privateChatConversations = () => Promise.resolve([
    { peer: { id: 2, username: 'bob', online }, lastMessage: { id: 4, content: 'hello' }, unread: 0 }
  ]);
  await game.PrivateChat.open({ id: 2, username: 'bob' });
  const renderedWrites = writes;
  const button = nodes.privateChatConversations.children[0];
  nodes.privateChatBox.scrollTop = 20;
  nodes.privateChatInput.value = 'unsent draft';
  await game.PrivateChat.refresh();
  await game.PrivateChat.refresh();
  assert.equal(writes, renderedWrites);
  assert.equal(nodes.privateChatConversations.children[0], button);
  assert.equal(nodes.privateChatBox.scrollTop, 20);
  assert.equal(nodes.privateChatInput.value, 'unsent draft');
  online = false;
  await game.PrivateChat.refresh();
  assert.equal(nodes.privateChatTitle.textContent, 'bob · 离线');
  assert.equal(writes, renderedWrites);
  histories[2].messages.push({ id: 5, content: 'new message' });
  await game.PrivateChat.refresh();
  assert.equal(writes, renderedWrites + 1);
  assert.equal(html, 'hellonew message');
});
