const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup() {
  const nodes = {};
  const calls = [];
  let document;
  function element() {
    return {
      children: [], value: '', hidden: false, disabled: false, isConnected: true,
      appendChild(child) { this.children.push(child); if (child.id) nodes[child.id] = child; },
      querySelector(selector) { return nodes[selector.slice(1)] || null; },
      querySelectorAll(selector) { return selector.startsWith('details') ? [] : Object.values(nodes).filter(n => n.control); },
      focus() { document.activeElement = this; },
      remove() { delete nodes[this.id]; this.isConnected = false; },
      set innerHTML(html) {
        this.html = html;
        for (const match of html.matchAll(/<(input|select|div|p|button)\b([^>]*\bid="([^"]+)"[^>]*)>/g)) {
          const el = element(); el.id = match[3]; el.control = /input|select|button/.test(match[1]); el.checked = / checked/.test(match[2]); nodes[el.id] = el;
        }
      },
      get innerHTML() { return this.html || ''; }
    };
  }
  nodes.friendList = element();
  const profile = { id: 2, username: 'bob', displayName: '<img src=x onerror=alert(1)>', available: true, friend: true, blocked: false, groupId: 0 };
  const response = { groups: [{ id: 0, name: '我的好友' }, { id: 1, name: '<先锋>' }], contacts: [profile], requests: [], settings: { friendRequestPolicy: 'APPROVAL', blockStrangerMessages: true } };
  const game = {
    Core: { state: { player: { id: 1 } } }, Chat: { avatarSrc: () => 'avatar.webp' },
    fmt: value => Number(value).toLocaleString('en-US'),
    toast: text => calls.push(['toast', text]),
    PrivateChat: { start: (name, id) => { calls.push(['chat', id]); return Promise.resolve(); }, refresh: () => Promise.resolve(), cleared: id => { calls.push(['cleared', id]); return Promise.resolve(); } },
    API: {
      client: { serverId: 'test' },
      friends: () => Promise.resolve(response), friendProfile: () => Promise.resolve(profile), friendSearch: () => Promise.resolve(profile),
      friendSettings: value => { calls.push(['settings', value]); return Promise.resolve(); },
      friendBlock: (id, blocked) => { calls.push(['block', id, blocked]); return Promise.resolve(); },
      privateChatClear: id => { calls.push(['clear', id]); return Promise.resolve(); },
      friendGroupSave: (id, name) => { calls.push(['group', id, name]); return Promise.resolve(); },
      friendAdd: id => { calls.push(['add', id]); return Promise.resolve({ pending: true }); },
      friendMove: (id, groupId) => { calls.push(['move', id, groupId]); return Promise.resolve(); }
    }
  };
  document = { getElementById: id => nodes[id] || null, createElement: element, body: element(), addEventListener() {}, removeEventListener() {} };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../js/friends.js'), 'utf8'), { window: { Game: game }, document, Promise });
  function action(label) { return nodes.friendModalActions.children.find(button => button.textContent === label); }
  return { game, nodes, calls, profile, response, action };
}

test('friends list escapes player names and group names and always includes default and blacklist groups', async () => {
  const { game, nodes } = setup();
  await game.Friends.refresh();
  assert.match(nodes.friendList.innerHTML, /我的好友/);
  assert.match(nodes.friendList.innerHTML, /黑名单/);
  assert.match(nodes.friendList.innerHTML, /&lt;先锋&gt;/);
  assert.match(nodes.friendList.innerHTML, /&lt;img/);
  assert.doesNotMatch(nodes.friendList.innerHTML, /<img src=x/);
});

test('privacy dialog loads saved preferences and posts both editable settings', async () => {
  const { game, nodes, calls, action } = setup();
  await game.Friends.settings();
  assert.match(nodes.friendModal.innerHTML, /APPROVAL/);
  assert.match(nodes.friendModal.innerHTML, /ANYONE/);
  assert.match(nodes.friendModal.innerHTML, /NONE/);
  assert.equal(nodes.friendBlockStrangers.checked, true);
  nodes.friendRequestPolicy.value = 'NONE'; nodes.friendBlockStrangers.checked = false;
  await action('保存设置').onclick();
  assert.equal(calls[0][0], 'settings');
  assert.deepEqual(JSON.parse(JSON.stringify(calls[0][1])), { friendRequestPolicy: 'NONE', blockStrangerMessages: false });
  assert.equal(nodes.friendModal, undefined);
});

test('profile opens chat with stable player ID and supports moving friend to custom group', async () => {
  const { game, nodes, calls, action } = setup();
  await game.Friends.view(2);
  assert.match(nodes.friendModal.innerHTML, /&lt;先锋&gt;/);
  nodes.friendGroupSelect.value = '1';
  await action('保存分组').onclick();
  assert.deepEqual(calls[0], ['move', 2, 1]);
  await game.Friends.view(2);
  await action('发起聊天').onclick();
  assert.ok(calls.some(c => c[0] === 'chat' && c[1] === 2));
});

test('profile displays public formation and overall power', async () => {
  const { game, nodes, profile } = setup();
  profile.formationPower = 12345;
  profile.overallPower = 54321;
  await game.Friends.view(2);
  assert.match(nodes.friendModal.innerHTML, /编队战力/);
  assert.match(nodes.friendModal.innerHTML, /12,345/);
  assert.match(nodes.friendModal.innerHTML, /综合实力/);
  assert.match(nodes.friendModal.innerHTML, /54,321/);
});

test('profile only locates a city when the API provides an authorized recon coordinate', async () => {
  const { game, nodes, calls, profile, action } = setup();
  await game.Friends.view(2);
  assert.match(nodes.friendModal.innerHTML, /未获得有效侦察坐标/);
  assert.equal(action('定位城市'), undefined);

  profile.cityX = 5; profile.cityY = 387;
  game.World = { locateTarget: (x, y) => calls.push(['locate', x, y]) };
  await game.Friends.view(2);
  assert.match(nodes.friendModal.innerHTML, /\(5,387\)/);
  await action('定位城市').onclick();
  assert.deepEqual(calls, [['locate', 5, 387]]);
});

test('clearing and blacklisting require confirmation before mutating and call private refresh hooks', async () => {
  const { game, calls, action } = setup();
  await game.Friends.view(2);
  await action('清空会话内容').onclick();
  assert.equal(calls.length, 0);
  await action('确认清空会话').onclick();
  assert.deepEqual(calls.slice(0, 2), [['clear', 2], ['cleared', 2]]);
  await game.Friends.view(2);
  await action('拉黑').onclick();
  assert.equal(calls.filter(c => c[0] === 'block').length, 0);
  await action('确认拉黑').onclick();
  assert.ok(calls.some(c => c[0] === 'block' && c[1] === 2 && c[2] === true));
});

test('pending or blocked player cannot be added again and API errors keep dialog open', async () => {
  const { game, nodes, profile, action } = setup();
  profile.friend = false; profile.requestState = 'OUTGOING';
  game.Friends.showProfile(profile);
  assert.equal(action('已发送申请').disabled, true);
  profile.requestState = 'NONE'; profile.blocked = true;
  game.Friends.showProfile(profile);
  assert.equal(action('添加好友').disabled, true);
  assert.equal(action('发起聊天').disabled, true);
  profile.blocked = false;
  game.API.friendAdd = () => Promise.reject(new Error('对方已关闭好友申请'));
  game.Friends.showProfile(profile);
  await action('添加好友').onclick();
  assert.equal(nodes.friendModalError.hidden, false);
  assert.equal(nodes.friendModalError.textContent, '对方已关闭好友申请');
  assert.equal(action('添加好友').disabled, false);
});

test('late friend responses from previous account do not render', async () => {
  const { game, nodes, response } = setup();
  let finish;
  game.API.friends = () => new Promise(resolve => { finish = resolve; });
  const pending = game.Friends.refresh();
  game.Core.state.player.id = 9;
  finish(response); await pending;
  assert.equal(nodes.friendList.innerHTML, '');
});

test('incoming friend requests increment unread count and trigger nav bar render while outgoing requests do not', async () => {
  const { game, response } = setup();
  let navBarRenderCount = 0;
  game.Main = { renderNavBar: () => { navBarRenderCount++; } };
  response.requests = [
    { id: 10, outgoing: false, peer: { id: 3, username: 'charlie' } },
    { id: 11, outgoing: true, peer: { id: 4, username: 'david' } }
  ];
  await game.Friends.refresh();
  assert.equal(game.Friends.unread(), 1);
  assert.ok(navBarRenderCount > 0);
});

test('request accept and reject decrement unread count and re-render badges', async () => {
  const { game, response } = setup();
  let navBarRenderCount = 0;
  game.Main = { renderNavBar: () => { navBarRenderCount++; } };
  game.API.friendRequestAccept = () => {
    response.requests = [];
    return Promise.resolve();
  };
  response.requests = [
    { id: 10, outgoing: false, peer: { id: 3, username: 'charlie' } }
  ];
  await game.Friends.refresh();
  assert.equal(game.Friends.unread(), 1);
  const btn = { disabled: false };
  await game.Friends.request(10, true, btn);
  assert.equal(game.Friends.unread(), 0);
  assert.ok(navBarRenderCount >= 2);
});

test('acknowledging incoming friend requests clears unread badge but keeps request in list', async () => {
  const { game, response, nodes } = setup();
  let navBarRenderCount = 0;
  game.Main = { renderNavBar: () => { navBarRenderCount++; } };
  response.requests = [
    { id: 10, outgoing: false, peer: { id: 3, username: 'charlie' } }
  ];
  await game.Friends.refresh();
  assert.equal(game.Friends.unread(), 1);
  assert.match(nodes.friendList.innerHTML, /申请添加战友/);

  game.Friends.acknowledge();
  assert.equal(game.Friends.unread(), 0);
  assert.match(nodes.friendList.innerHTML, /申请添加战友/);

  response.requests = [
    { id: 10, outgoing: false, peer: { id: 3, username: 'charlie' } },
    { id: 11, outgoing: false, peer: { id: 4, username: 'david' } }
  ];
  await game.Friends.refresh();
  assert.equal(game.Friends.unread(), 1);
});


