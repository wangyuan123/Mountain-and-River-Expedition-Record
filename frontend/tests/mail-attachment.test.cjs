const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const flush = () => new Promise(setImmediate);

async function setup() {
  const requests = [], messages = [];
  const stored = [12, 34].map(id => ({
    id, system: true, from: '系统', to: 'test', subject: '新手礼包', body: '', ts: 0,
    claimed: false, attach: [{ type: 'gold', qty: 3000 }]
  }));
  const game = {
    Core: { views: {} }, DATA: {}, toast: message => messages.push(message),
    API: {
      listMail: async () => ({ mails: structuredClone(stored), unread: 0 }),
      markMailRead: async () => ({}), mailUnread: async () => ({ unread: 0 }),
      claimMailAttach: async id => {
        requests.push(id);
        stored.find(mail => String(mail.id) === String(id)).claimed = true;
        return { success: true };
      }
    }
  };
  const context = vm.createContext({ Game: game, document: { getElementById: () => null }, Date });
  context.window = context;
  require('./load-constants.cjs')(context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/mail.js'), 'utf8'), context);
  const view = { innerHTML: '' };
  game.Mail.renderView(view);
  await flush();
  game.Mail.open('12');
  await flush();
  function clickClaim() {
    const button = view.innerHTML.match(/onclick="(Game\.Mail\.claimAttach\([^"]*\))"/);
    assert.ok(button, '应存在可点击的领取附件按钮');
    vm.runInContext(button[1], context);
  }
  return { game, view, requests, messages, stored, clickClaim };
}

test('点击实际领取按钮后立即显示已领取，重新打开和刷新邮件仍保持状态', async () => {
  const f = await setup();
  f.clickClaim();
  assert.doesNotMatch(f.view.innerHTML, /已领取/);
  await flush();
  assert.deepEqual(f.requests, ['12']);
  assert.match(f.view.innerHTML, /<span class="mail-claimed">已领取<\/span>/);
  assert.doesNotMatch(f.view.innerHTML, /领取附件/);
  assert.deepEqual(f.messages, ['附件已领取']);

  f.game.Mail.open('34');
  assert.match(f.view.innerHTML, /领取附件/);
  f.game.Mail.open('12');
  assert.match(f.view.innerHTML, /已领取/);
  f.game.Mail.setFolder('system');
  await flush();
  f.game.Mail.open('12');
  assert.match(f.view.innerHTML, /已领取/);
  assert.doesNotMatch(f.view.innerHTML, /领取附件/);
});

for (const mode of ['errorResponse', 'businessRejection', 'rejectedRequest']) {
  test('领取失败保留领取入口并显示错误：' + mode, async () => {
    const f = await setup();
    f.game.API.claimMailAttach = () => mode === 'errorResponse'
      ? Promise.resolve({ success: false, error: '暂时无法领取' })
      : mode === 'businessRejection'
        ? Promise.resolve({ success: false, message: '需本城造船厂 Lv.6' })
      : Promise.reject(new Error('连接失败'));
    f.clickClaim();
    await flush();
    assert.match(f.view.innerHTML, /领取附件/);
    assert.doesNotMatch(f.view.innerHTML, /已领取/);
    assert.equal(f.stored[0].claimed, false);
    assert.deepEqual(f.messages, [mode === 'errorResponse' ? '暂时无法领取' : mode === 'businessRejection' ? '需本城造船厂 Lv.6' : '领取失败: 连接失败']);
  });
}
