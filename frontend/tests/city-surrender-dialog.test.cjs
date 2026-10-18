const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const flush = () => new Promise(setImmediate);

function container() {
  return {
    children: [],
    appendChild(node) { node.parentNode = this; this.children.push(node); },
    removeChild(node) { this.children.splice(this.children.indexOf(node), 1); node.parentNode = null; }
  };
}

function fixture() {
  const body = container(), requests = [], toasts = [];
  const document = {
    body, getElementById: () => null,
    createElement: () => ({
      controls: {},
      querySelector(selector) {
        return this.controls[selector] || (this.controls[selector] = { focus() { document.activeElement = this; } });
      }
    })
  };
  const offer = { offerId: 12, cityName: '待移交城', cityX: 101, cityY: 100, status: 'PENDING', canRespond: true };
  const game = {
    Core: { state: { player: { id: 1 }, world: {} }, views: {}, render() {} }, DATA: {},
    toast: message => toasts.push(message),
    API: {
      listMail: async () => ({ mails: [{ id: 1, from: '玩家乙', subject: '劝降提议', time: Date.now(), body: '提议正文', surrenderOfferId: 12 }] }),
      getSurrenderOffer: async () => offer, markMailRead: async () => ({}), mailUnread: async () => ({ unread: 0 }),
      respondSurrender: async (id, decision) => { requests.push([id, decision]); return { ...offer, status: 'ACCEPTED', canRespond: false }; }
    }
  };
  const context = vm.createContext({ Game: game, document, Date, confirm: () => assert.fail('不能调用浏览器原生确认框') });
  context.window = context;
  require('./load-constants.cjs')(context);
  for (const file of ['world.js', 'mail.js']) vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), context);
  return { game, document, body, requests, toasts, get dialog() { return body.children.at(-1); } };
}

test('接受劝降使用主题弹窗展示城名、期限和资产范围，确认前不移交', async () => {
  const f = fixture();
  f.game.Mail.renderView({ innerHTML: '' });
  await flush();
  f.game.Mail.open(1);
  await flush();
  f.game.Mail.respondSurrender(12, 'accept');
  assert.deepEqual(f.requests, []);
  const dialog = f.dialog;
  assert.equal(dialog.className, 'modal-mask confirm-dialog-mask');
  assert.match(dialog.innerHTML, /待移交城（101,100）/);
  assert.match(dialog.innerHTML, /立即冻结 24 小时.*永久移交/);
  assert.match(dialog.innerHTML, /军官装备.*本城所属野地.*双方均不能撤销/);
  assert.match(dialog.innerHTML, /账号科技.*钻石和账号道具不转移/);
  assert.match(dialog.innerHTML, /btn-danger.*接受并开始移交/);
  dialog.querySelector('#confirmOkBtn').onclick();
  dialog.querySelector('#confirmOkBtn').onclick();
  await flush();
  assert.deepEqual(f.requests, [[12, 'accept']]);
  assert.equal(f.dialog, undefined);
  assert.deepEqual(f.toasts, ['已接受劝降，城市将在 24 小时后完成移交']);
});

test('取消接受劝降不提交答复，拒绝劝降仍可直接提交', async () => {
  const f = fixture();
  f.game.Mail.respondSurrender(12, 'accept');
  f.dialog.querySelector('#confirmCancelBtn').onclick();
  assert.deepEqual(f.requests, []);
  f.game.Mail.respondSurrender(12, 'reject');
  await flush();
  assert.deepEqual(f.requests, [[12, 'reject']]);
  assert.deepEqual(f.toasts, ['已拒绝劝降']);
});

test('确认弹窗安全显示文本，Esc、遮罩和取消均关闭并恢复焦点', () => {
  for (const action of ['escape', 'backdrop', 'cancel']) {
    const f = fixture();
    const previous = { isConnected: true, focus() { f.document.activeElement = this; } };
    f.document.activeElement = previous;
    f.game.World.showConfirm({ title: '<img src=x>', message: '<script>test</script>', onConfirm: () => assert.fail('关闭操作不得提交') });
    const dialog = f.dialog, cancel = dialog.querySelector('#confirmCancelBtn'), ok = dialog.querySelector('#confirmOkBtn');
    assert.match(dialog.innerHTML, /&lt;img src=x&gt;/);
    assert.match(dialog.innerHTML, /&lt;script&gt;test&lt;\/script&gt;/);
    assert.match(dialog.innerHTML, /role="dialog" aria-modal="true"/);
    assert.equal(f.document.activeElement, cancel);
    ok.focus();
    dialog.onkeydown({ key: 'Tab', preventDefault() {} });
    assert.equal(f.document.activeElement, cancel);
    if (action === 'escape') dialog.onkeydown({ key: 'Escape' });
    if (action === 'backdrop') dialog.onclick({ target: dialog });
    if (action === 'cancel') cancel.onclick();
    ok.onclick();
    assert.equal(f.dialog, undefined);
    assert.equal(f.document.activeElement, previous);
  }
});

test('地图原生全屏时确认弹窗挂在全屏元素中，关闭后正常移除', () => {
  const f = fixture(), fullscreen = container();
  f.document.fullscreenElement = fullscreen;
  f.game.World.showConfirm({ message: '劝降确认', onConfirm: () => assert.fail('取消不得提交') });
  assert.equal(f.body.children.length, 0);
  assert.equal(fullscreen.children.length, 1);
  fullscreen.children[0].querySelector('#confirmCancelBtn').onclick();
  assert.equal(fullscreen.children.length, 0);
});
