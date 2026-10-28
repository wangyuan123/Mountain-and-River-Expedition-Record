const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup() {
  const elements = {}, requests = [], notices = [], messages = [], stored = new Map();
  let token = 'isolated-session';
  function element(id, tag = 'INPUT') {
    return { id, tagName: tag, value: '', disabled: false, textContent: '', previousElementSibling: {},
      focus() {}, setAttribute() {}, addEventListener() {}, isConnected: true };
  }
  for (const id of ['loginUser', 'registerEmail', 'registerEmailCode', 'registerEmailSend']) elements[id] = element(id);
  elements.loginUser.value = 'isolated-commander'; elements.registerEmail.value = 'fixture@qq.com';
  const document = { getElementById: id => elements[id] || null,
    createElement() {
      const mask = element('', 'DIV');
      mask.querySelectorAll = selector => mask.children.filter(node => selector === 'input' ? node.tagName === 'INPUT' : ['INPUT', 'BUTTON'].includes(node.tagName));
      mask.remove = () => { for (const node of mask.children) delete elements[node.id]; delete elements[mask.id]; };
      return mask;
    },
    body: { appendChild(mask) {
      elements[mask.id] = mask; mask.children = [];
      for (const match of mask.innerHTML.matchAll(/<(input|button|p|div)[^>]*\bid="([^"]+)"/g)) {
        const node = element(match[2], match[1].toUpperCase()); elements[node.id] = node; mask.children.push(node);
      }
    } }
  };
  const context = vm.createContext({ document, console, Date, Promise, setInterval: () => 1, clearInterval() {},
    localStorage: { getItem: key => stored.get(key), setItem: (key, value) => stored.set(key, value) },
    Game: { escapeHtml: value => String(value), Main: { showLoginMsg: text => messages.push(text) },
      Core: { render() {} }, toast: text => messages.push(text),
      Account: { endSession: notice => notices.push(notice) },
      API: { client: { serverId: 'jiangsu-1' }, getToken: () => token, isLoggedIn: () => !!token,
        getUsername: () => 'isolated-commander', accountEmail: async (route, body) => {
          requests.push({ route, body: JSON.parse(JSON.stringify(body || {})) });
          return env.reply(route, body);
        } }
    }
  });
  context.window = context;
  const env = { context, elements, requests, notices, messages, stored,
    reply(route) {
      if (route === '/server') return { accountSecurity: {} };
      const now = Date.now(); return { challengeId: 'isolated-challenge', serverNow: now, expiresAt: now + 300000, resendAt: now + 60000 };
    }, setToken(value) { token = value; }
  };
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/account-email.js'), 'utf8'), context);
  env.email = context.Game.AccountEmail;
  return env;
}
const flush = () => new Promise(resolve => setImmediate(resolve));

test('注册验证码绑定当前用户名、邮箱与大区，保留前导零', async () => {
  const env = setup();
  env.email.sendRegistration(); await flush();
  env.elements.registerEmailCode.value = '000123';
  assert.deepEqual(JSON.parse(JSON.stringify(env.email.registrationProof())), {
    email: 'fixture@qq.com', challengeId: 'isolated-challenge', emailCode: '000123'
  });
  env.context.Game.API.client.serverId = 'jiangsu-2';
  assert.throws(() => env.email.registrationProof(), /重新|获取验证码/);
  assert.equal(env.stored.size, 0);
});

test('补绑发送当前密码和验证码，成功后显示邮箱状态', async () => {
  const env = setup(); env.email.open('bind');
  env.elements.securityPassword.value = 'original-password'; env.elements.securityEmail.value = 'fixture@qq.com';
  const state = env.email.modal;
  env.email.send(state); await flush(); env.elements.securityCode.value = '123456';
  env.reply = () => ({ emailBound: true, maskedEmail: 'fi***re@qq.com' });
  env.email.submit(state); await flush();
  assert.equal(env.requests[1].route, '/email/bind');
  assert.equal(env.requests[1].body.currentPassword, 'original-password');
  assert.equal(env.email.data.emailBound, true); assert.equal(env.notices.length, 0);
  assert.equal(env.elements.accountEmailModal, undefined);
});

test('找回成功后要求新登录，不写入浏览器持久缓存', async () => {
  const env = setup(); env.setToken(''); env.email.open('reset');
  env.elements.securityUsername.value = 'isolated-commander'; env.elements.securityEmail.value = 'fixture@qq.com';
  env.email.send(env.email.modal); await flush();
  env.elements.securityCode.value = '123456'; env.elements.securityNewPassword.value = 'new-password'; env.elements.securityConfirmPassword.value = 'new-password';
  env.reply = () => ({ success: true });
  env.email.submit(env.email.modal); await flush();
  const request = env.requests.find(request => request.route === '/password/reset');
  assert.equal(request.body.newPassword, 'new-password'); assert.equal(env.notices[0].title, '密码已重置');
  assert.equal(env.stored.size, 0);
});

test('换绑原邮箱验证后清除密码，再验证新邮箱并处理先到达的断线事件', async () => {
  const env = setup(); env.email.data = { emailBound: true, maskedEmail: 'ol***il@qq.com' }; env.email.open('change');
  const state = env.email.modal;
  env.elements.securityPassword.value = 'original-password'; env.elements.securityEmail.value = 'new-fixture@qq.com';
  env.email.send(state); await flush(); env.elements.securityCode.value = '123456';
  env.reply = () => ({ changeToken: 'isolated-change-token' }); env.email.submit(state); await flush();
  assert.equal(state.stage, 'new'); assert.equal(env.elements.securityPassword.value, '');
  assert.equal(env.elements.securityEmail.disabled, true);
  env.reply = setup().reply; env.email.send(state); await flush(); env.elements.securityCode.value = '654321';
  let finish;
  env.reply = () => new Promise(resolve => { finish = resolve; }); env.email.submit(state);
  assert.equal(env.email.handleSessionExpiry(), true);
  finish({ success: true }); await flush();
  assert.equal(env.notices.length, 1); assert.equal(env.notices[0].title, '邮箱已更换');
  assert.equal(env.requests.at(-1).body.changeToken, 'isolated-change-token');
});

test('账号切换清除弹窗、密码和注册凭据', () => {
  const env = setup(); env.email.registration = { challengeId: 'isolated' }; env.email.open('bind');
  env.elements.securityPassword.value = 'original-password'; env.email.reset();
  assert.equal(env.email.modal, null); assert.equal(env.email.registration, null); assert.equal(env.elements.securityPassword, undefined);
});
