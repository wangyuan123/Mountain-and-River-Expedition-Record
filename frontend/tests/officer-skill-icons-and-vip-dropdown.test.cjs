const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setupTestEnvironment() {
  const context = vm.createContext({
    console,
    Math,
    Date,
    parseInt,
    window: null,
    document: {
      getElementById: () => null,
      querySelectorAll: () => [],
      querySelector: () => null,
      createElement: () => ({ classList: { contains: () => false, add: () => {}, remove: () => {} }, setAttribute: () => {}, querySelector: () => null, addEventListener: () => {} }),
      body: { appendChild: () => {} }
    },
    Game: {
      DATA: {},
      Core: { state: { items: { universalSkillBook: 10 } }, views: {} },
      fmt: (n) => String(n),
      escapeHtml: (s) => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c])),
      toast: () => {},
      API: {
        vipConvertSkillBook: () => Promise.resolve({ success: true, message: '推演成功' })
      }
    }
  });
  context.window = context;
  context.G = context.Game;

  // Load scripts
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/icons.js'), 'utf8'), context);
  require('./load-constants.cjs')(context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/data.js'), 'utf8'), context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/officer.js'), 'utf8'), context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/vip.js'), 'utf8'), context);

  return context;
}

test('每个技能均配置重新设计的高清矢量 SVG 图标，且物理文件全部有效存在', () => {
  const { Game } = setupTestEnvironment();
  const skills = Game.DATA.officerSkills;

  const expectedSkills = [
    'frenzy', 'bulwark', 'blitz', 'suppress', 'pierce',
    'leadership', 'supply', 'medic', 'harvest', 'construct',
    'finance', 'research', 'ration', 'counter', 'learn', 'borrow_armor'
  ];

  for (const skillId of expectedSkills) {
    const s = skills[skillId];
    assert.ok(s, `技能 ${skillId} 应在 officerSkills 中定义`);
    assert.match(s.icon, /^img\/skills\/[a-z0-9_-]+\.svg$/i, `技能 ${skillId} 的图标应为 SVG 路径，当前为: ${s.icon}`);

    const filePath = path.join(__dirname, '..', s.icon);
    assert.ok(fs.existsSync(filePath), `技能图标物理文件应存在: ${filePath}`);

    const content = fs.readFileSync(filePath, 'utf8');
    assert.ok(content.includes('<svg'), `文件应为合法的 SVG 格式: ${s.icon}`);
    assert.ok(content.includes('viewBox="0 0 64 64"'), `SVG 应具备 64x64 viewBox: ${s.icon}`);
  }
});

test('目标定向秘籍下拉选项展示 技能名称 [查看详情]，且原生 select 无长文本描述以防宽度无限展开', () => {
  const { Game } = setupTestEnvironment();

  const html = Game.Vip.renderSkillsTab({ level: 7 });

  // 1. 下拉容器与触发按钮结构
  assert.match(html, /class="vip-skill-dropdown-wrap" id="vipSkillDropdownWrap"/, '应具备自定义下拉容器');
  assert.match(html, /class="vip-skill-dropdown-trigger" id="vipSkillDropdownTrigger"/, '应具备下拉触发按钮');
  assert.match(html, /class="vip-skill-dropdown-menu" id="vipSkillDropdownMenu"/, '应具备自定义下拉选项菜单');

  // 2. 每个选项应包含技能图标、技能名称与 [查看详情] 按钮
  assert.match(html, /class="vip-skill-item-name"[^>]*>全军冲锋<\/span>/);
  assert.match(html, /class="vip-skill-detail-link"[^>]*>\[查看详情\]<\/button>/);
  assert.match(html, /Game\.Vip\.showSkillDetailFromDropdown\('frenzy', event\)/);

  // 3. 原生保底 select 的 option 中不得夹杂长达上百字的技能完整效果段落，避免横向爆屏
  assert.doesNotMatch(html, /<option[^>]*>全军冲锋 \(首次交战回合触发/);
  assert.match(html, /<option value="frenzy"[^>]*>全军冲锋<\/option>/);
});

test('点击 [查看详情] 触发技能详情弹窗且不关闭下拉浮层，点击选项行则选择技能并关闭浮层', () => {
  const context = setupTestEnvironment();
  const { Game } = context;

  const domElements = {};
  const menu = {
    hidden: false,
    attributes: {},
    setAttribute(k, v) { this.attributes[k] = v; if (k === 'hidden') this.hidden = true; },
    removeAttribute(k) { delete this.attributes[k]; if (k === 'hidden') this.hidden = false; }
  };
  const trigger = {
    attributes: {},
    setAttribute(k, v) { this.attributes[k] = v; }
  };
  const wrap = {
    classList: {
      classes: new Set(['open']),
      add(c) { this.classes.add(c); },
      remove(c) { this.classes.delete(c); },
      contains(c) { return this.classes.has(c); }
    }
  };
  const textEl = { innerHTML: '' };
  const selectEl = { value: 'frenzy' };

  domElements.vipSkillDropdownMenu = menu;
  domElements.vipSkillDropdownTrigger = trigger;
  domElements.vipSkillDropdownWrap = wrap;
  domElements.vipSkillDropdownText = textEl;
  domElements.vipSkillTargetSelect = selectEl;

  context.document.getElementById = (id) => domElements[id] || null;

  let detailModalOpened = false;
  let detailSkillId = null;
  Game.Officer.showSkillDetail = (id) => {
    detailModalOpened = true;
    detailSkillId = id;
  };

  Game.Vip.skillDropdownOpen = true;

  // 1. 点击 [查看详情]：调用 showSkillDetailFromDropdown
  let detailPropagated = false;
  const detailEvent = {
    stopPropagation() { detailPropagated = true; }
  };
  Game.Vip.showSkillDetailFromDropdown('blitz', detailEvent);

  assert.equal(detailPropagated, true, '点击 [查看详情] 必须阻止冒泡，防止触发整行选择');
  assert.equal(detailModalOpened, true, '应成功打开技能详情弹窗');
  assert.equal(detailSkillId, 'blitz', '详情弹窗应对应 blitz 技能');
  assert.equal(Game.Vip.skillDropdownOpen, true, '查看详情时下拉弹层不能关闭');
  assert.equal(menu.hidden, false, 'menu.hidden 仍应为 false');

  // 2. 点击选项区域（[查看详情] 之外）：调用 selectTargetSkill
  let selectPropagated = false;
  const selectEvent = {
    stopPropagation() { selectPropagated = true; }
  };
  Game.Vip.selectTargetSkill('bulwark', selectEvent);

  assert.equal(selectPropagated, true, '点击选项应阻止事件继续冒泡');
  assert.equal(Game.Vip.selectedTargetSkill, 'bulwark', '当前选择的技能应更新为 bulwark');
  assert.equal(selectEl.value, 'bulwark', '底层 select 值应同步更新以支持推演提交');
  assert.equal(Game.Vip.skillDropdownOpen, false, '选择技能后下拉弹层应关闭');
  assert.equal(menu.hidden, true, 'menu.hidden 应被置为 true');
});

test('VIP 秘籍下拉弹层与技能详情弹窗的 CSS 样式约束规范', () => {
  const cssPath = path.join(__dirname, '../css/vip.css');
  const css = fs.readFileSync(cssPath, 'utf8');

  // 验证弹层宽度与滚动保护
  assert.match(css, /\.vip-skill-dropdown-menu\s*\{[^}]*max-height:\s*240px/, '下拉菜单必须设置最大高度');
  assert.match(css, /\.vip-skill-dropdown-menu\s*\{[^}]*overflow-x:\s*hidden/, '下拉菜单必须禁止横向溢出');
  assert.match(css, /\.vip-skill-dropdown-menu\s*\{[^}]*width:\s*100%/, '下拉菜单宽度必须受限于容器');

  // 验证技能详情按钮样式
  assert.match(css, /\.vip-skill-detail-link/, '必须包含查看详情按钮样式定义');

  // 验证技能详情弹窗高于 VIP 弹窗
  assert.match(css, /\.modal-mask\.officer-skill-detail-mask\s*\{[^}]*z-index:\s*11000/, '技能详情弹窗 z-index 应为 11000');
});
