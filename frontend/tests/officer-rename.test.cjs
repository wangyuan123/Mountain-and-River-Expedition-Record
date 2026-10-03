const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setupTestEnvironment() {
  const elements = {};
  let keydownListeners = [];

  const document = {
    activeElement: null,
    getElementById(id) {
      return elements[id] || null;
    },
    addEventListener(type, listener) {
      if (type === 'keydown') keydownListeners.push(listener);
    },
    removeEventListener(type, listener) {
      if (type === 'keydown') keydownListeners = keydownListeners.filter(l => l !== listener);
    },
    createElement(tag) {
      const controls = {};
      const el = {
        tagName: tag.toUpperCase(),
        id: '',
        className: '',
        style: {},
        innerHTML: '',
        parentNode: null,
        controls,
        focus() {
          document.activeElement = this;
        },
        select() {
          this.selected = true;
        },
        querySelector(selector) {
          if (!controls[selector]) {
            controls[selector] = {
              value: '',
              disabled: false,
              style: {},
              focus() { document.activeElement = this; },
              select() { this.selected = true; }
            };
          }
          return controls[selector];
        },
        querySelectorAll() {
          return [];
        },
        remove() {
          if (this.id && elements[this.id]) {
            delete elements[this.id];
          }
          this.parentNode = null;
        }
      };
      return el;
    },
    body: {
      appendChild(element) {
        element.parentNode = document.body;
        if (element.id) {
          elements[element.id] = element;
        }
      },
      removeChild(element) {
        if (element.id && elements[element.id]) {
          delete elements[element.id];
        }
        element.parentNode = null;
      }
    }
  };

  const context = vm.createContext({
    Date,
    console,
    Math,
    document,
    location: { port: '80', protocol: 'http:', hostname: 'localhost' },
    window: null,
    Game: {
      DATA: {},
      Core: {},
      API: {},
      fmt: (n) => String(n),
      expNeeded: () => 100,
      toast: () => {},
      escapeHtml: (s) => String(s)
    }
  });
  context.window = context;
  context.G = context.Game;

  require('./load-constants.cjs')(context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/data.js'), 'utf8'), context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/core.js'), 'utf8'), context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/api-client.js'), 'utf8'), context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/api.js'), 'utf8'), context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/officer.js'), 'utf8'), context);

  context.elements = elements;
  context.getKeydownListeners = () => keydownListeners;
  return context;
}

test('军官详情界面正确渲染修改名称入口按钮', () => {
  const context = setupTestEnvironment();
  const officer = {
    id: '101',
    name: '张辽',
    star: 5,
    level: 30,
    exp: 50,
    role: 'commander',
    military: 100,
    defense: 80,
    logistics: 70,
    knowledge: 60,
    loyalty: 90,
    salary: 50
  };

  context.Game.Core.state = {
    officers: [officer],
    _detailOfficerId: '101',
    items: {},
    resources: { gold: 1000 }
  };

  const container = { innerHTML: '' };
  context.Game.Officer.renderDetail(container);

  assert.match(container.innerHTML, /officer-rename-trigger/, '详情顶部卡片应包含改名按钮');
  assert.match(container.innerHTML, /✏️ 修改名称/, '详情顶部应有改名文案');
  assert.match(container.innerHTML, /Game\.Officer\.promptRename\('101'\)/, '点击事件应绑定对应军官ID');
  assert.match(container.innerHTML, /\[修改名称\]/, '底部操作栏也应包含修改名称操作按钮');
});

test('军官改名弹窗支持改名卡与金币提示，并提供无障碍和键盘交互', () => {
  const context = setupTestEnvironment();
  const officer = {
    id: '102',
    name: '赵云',
    star: 5,
    level: 50,
    role: 'commander',
    loyalty: 100
  };

  context.Game.Core.state = {
    officers: [officer],
    _detailOfficerId: '102',
    items: { renameCard: 2 },
    resources: { gold: 500 }
  };

  const triggerBtn = { isConnected: true, focus() { context.document.activeElement = this; } };
  context.document.activeElement = triggerBtn;

  context.Game.Officer.promptRename('102');
  const modal = context.elements.officerRenameModal;
  assert.ok(modal, '弹窗应当已插入DOM');
  assert.match(modal.className, /account-confirm-mask/, '应使用 account-confirm-mask 样式类');
  assert.match(modal.innerHTML, /role="dialog" aria-modal="true"/, '应具有dialog无障碍属性');
  assert.match(modal.innerHTML, /拥有军官改名卡 ×2，将优先消耗 1 张【军官改名卡】/, '拥有军官改名卡时应有对应提示');
  assert.match(modal.innerHTML, /赵云/, '应显示当前军官名称');

  // 测试 Escape 关闭
  const listeners = context.getKeydownListeners();
  assert.ok(listeners.length > 0, '应当注册键盘监听器');
  let prevented = false;
  listeners[0]({ key: 'Escape', stopPropagation() {}, preventDefault() { prevented = true; } });
  assert.equal(prevented, true, 'Escape应调用preventDefault');
  assert.equal(context.elements.officerRenameModal, undefined, '按Esc后弹窗应关闭');
  assert.equal(context.document.activeElement, triggerBtn, '关闭弹窗后应恢复焦点到触发按钮');
});

test('军官改名前端表单校验及提交成功交互', async () => {
  const context = setupTestEnvironment();
  const officer = {
    id: '103',
    name: '典韦',
    star: 4,
    level: 25,
    role: 'idle'
  };

  let rendered = false;
  context.Game.Core.state = {
    officers: [officer],
    _detailOfficerId: '103',
    items: {},
    resources: { gold: 200 }
  };
  context.Game.Core.render = () => { rendered = true; };

  const toasts = [];
  context.Game.toast = (msg) => toasts.push(msg);

  let renamePayload = null;
  context.Game.API.renameOfficer = async (id, name) => {
    renamePayload = { id, name };
    officer.name = name;
    return { success: true, message: '改名成功: 典韦 → 古之恶来' };
  };

  context.Game.Officer.promptRename('103');
  const modal = context.elements.officerRenameModal;
  assert.ok(modal);
  assert.match(modal.innerHTML, /未持有军官改名卡，将消耗 60 黄金/, '无卡且金币充足时提示扣60黄金');

  const form = modal.querySelector('#officerRenameForm');
  const input = modal.querySelector('#officerRenameInput');

  // 1. 提交空名字
  input.value = '   ';
  form.onsubmit({ preventDefault() {} });
  assert.match(toasts.pop(), /军官名称不能为空/);

  // 2. 提交超长名字 (>12字)
  input.value = '一二三四五六七八九十一二三';
  form.onsubmit({ preventDefault() {} });
  assert.match(toasts.pop(), /最多12个字符/);

  // 3. 提交与原名相同
  input.value = '典韦';
  form.onsubmit({ preventDefault() {} });
  assert.match(toasts.pop(), /新名称与当前军官名称相同/);

  // 4. 提交合法的新名字
  input.value = '古之恶来';
  form.onsubmit({ preventDefault() {} });

  // 等待异步完成
  await new Promise(resolve => setTimeout(resolve, 10));

  assert.deepEqual(renamePayload, { id: '103', name: '古之恶来' });
  assert.match(toasts.pop(), /改名成功/);
  assert.equal(context.elements.officerRenameModal, undefined, '改名成功后弹窗应已移除');
  assert.equal(rendered, true, '改名成功后应重新渲染视图');
});

test('军官当天已改名时提示重置时间且不发送请求', () => {
  const context = setupTestEnvironment();
  const officer = { id: 'daily', name: '张辽', star: 3, level: 1, nameRenameAvailableAt: Date.now() + 60000 };
  context.Game.Core.state = { officers: [officer], items: { renameCard: 1 }, resources: { gold: 500 } };
  const messages = [];
  context.Game.toast = message => messages.push(message);
  context.Game.API.renameOfficer = () => { throw Error('不应发送改名请求'); };

  context.Game.Officer.promptRename('daily');
  const modal = context.elements.officerRenameModal;
  assert.match(modal.innerHTML, /北京时间 0:00 重置/);
  assert.match(modal.innerHTML, /这名军官今日已修改/);
  modal.querySelector('#officerRenameForm').onsubmit({ preventDefault() {} });
  assert.match(messages[0], /请明日 0:00 后再试/);
});

test('展示名支持标点和 emoji，拒绝不可见字符，军官名称输出经过转义', () => {
  const context = setupTestEnvironment();
  assert.equal(context.Game.normalizeDisplayName(' 大河之剑天上来-李白☆ ', 12, '军官名称'), '大河之剑天上来-李白☆');
  assert.equal(context.Game.normalizeDisplayName('剑客🎖️', 8, '统帅名'), '剑客🎖️');
  assert.throws(() => context.Game.normalizeDisplayName('张三\n将军', 12, '军官名称'), /不可见字符/);
  assert.throws(() => context.Game.normalizeDisplayName('张三\n', 12, '军官名称'), /不可见字符/);
  assert.throws(() => context.Game.normalizeDisplayName('\t张三', 12, '军官名称'), /不可见字符/);
  assert.throws(() => context.Game.normalizeDisplayName('张三\u202e将军', 12, '军官名称'), /不可见字符/);
  assert.throws(() => context.Game.normalizeDisplayName('😀'.repeat(9), 8, '统帅名'), /最多8个字符/);

  context.Game.Core.state = {
    officers: [{ id: 'safe', name: '<勇者>☆', star: 5, level: 100, role: 'idle' }],
    _detailOfficerId: 'safe', items: {}, resources: { gold: 500 }
  };
  const view = { innerHTML: '' };
  context.Game.Officer.renderDetail(view);
  assert.match(view.innerHTML, /&lt;勇者&gt;☆/);
  assert.doesNotMatch(view.innerHTML, /<勇者>/);
});

test('统帅名改名从玩家档案提交，用户名保持独立', async () => {
  const context = setupTestEnvironment();
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/player-profile.js'), 'utf8'), context);
  context.Game.Core.state = { player: { id: 1, username: 'login-user', name: 'login-user' } };
  context.Game.Core.render = () => {};
  context.Game.Main = { closePlayerDrawer() {} };
  const calls = [];
  context.Game.API.setDisplayName = async (name) => {
    calls.push(name);
    context.Game.Core.state.player.name = name;
  };

  context.Game.PlayerProfile.promptRenameCommander();
  const modal = context.elements.renameCommanderModal;
  assert.ok(modal);
  const input = modal.querySelector('#renameCommanderInput');
  const form = modal.querySelector('#renameCommanderForm');
  input.value = '剑客☆';
  form.onsubmit({ preventDefault() {} });
  await new Promise(resolve => setTimeout(resolve, 0));

  assert.deepEqual(calls, ['剑客☆']);
  assert.equal(context.Game.Core.state.player.username, 'login-user');
  assert.equal(context.Game.Core.state.player.name, '剑客☆');
});
