const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

test('world chat stays readable below 10000 prestige and unlocks at the threshold', async () => {
  const calls = [];
  const input = { value: '尚未发送的草稿' };
  const button = {
    disabled: false,
    textContent: '',
    classList: { add() {}, remove() {} }
  };
  const context = {
    Game: {
      Constants: { chatMax: 50, chatCooldownSec: 5, chatMinPrestige: 10000 },
      state: { prestige: 9999 },
      API: {
        worldChatHistory: () => Promise.resolve({ messages: [{ id: 1, playerId: 2, username: '玩家', content: '历史消息', ts: 1 }] }),
        sendWorldChat: content => { calls.push(content); return Promise.resolve(); }
      }
    },
    document: { getElementById: id => ({ worldChatSendBtn: button, worldChatInput: input })[id] || null },
    setInterval() { return 1; },
    clearInterval() {}
  };
  context.window = context;
  vm.createContext(context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/chat.js'), 'utf8'), context);

  await context.Game.Chat.loadHistory();
  assert.equal(context.Game.Chat.recent(1)[0].content, '历史消息');
  context.Game.Chat.updateSendBtnUI();
  assert.equal(button.disabled, true);
  assert.equal(button.textContent, '只读');
  assert.equal(input.disabled, true);
  assert.equal(input.placeholder, '声望达到 10,000 后可发言');
  await assert.rejects(context.Game.Chat.send('测试消息'), /声望达到 10000/);
  assert.equal(calls.length, 0);

  context.Game.state.prestige = 10000;
  context.Game.state.worldChatUnlocked = true;
  context.Game.Chat.updateSendBtnUI();
  assert.equal(button.disabled, false);
  assert.equal(button.textContent, '发送');
  assert.equal(input.disabled, false);
  assert.equal(input.placeholder, '善言结善缘 恶语伤人心');
  assert.equal(input.value, '尚未发送的草稿');
  await context.Game.Chat.send('测试消息');
  assert.deepEqual(calls, ['测试消息']);

  context.Game.state.worldChatUnlocked = true;
  context.Game.state.prestige = 0;
  context.Game.Chat.updateSendBtnUI();
  assert.equal(button.disabled, false);
  assert.equal(input.disabled, false);
  await context.Game.Chat.send('声望归零后继续发言');
  assert.deepEqual(calls, ['测试消息', '声望归零后继续发言']);
});
