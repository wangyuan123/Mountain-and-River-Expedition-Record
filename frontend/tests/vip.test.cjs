const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup(api) {
  const elements = {};
  const toasts = [];
  const game = {
    API: api,
    escapeHtml: value => String(value ?? '').replace(/[&<>"']/g, character => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    })[character]),
    toast: message => toasts.push(message)
  };
  const body = {
    appendChild(element) {
      elements[element.id] = element;
      element.parentNode = body;
      elements.vipHeroWrap = { innerHTML: '加载军令信息中...' };
      elements.vipModalBody = { innerHTML: '' };
    },
    removeChild(element) {
      delete elements[element.id];
      delete elements.vipHeroWrap;
      delete elements.vipModalBody;
    }
  };
  const context = vm.createContext({
    window: { Game: game },
    document: {
      body,
      createElement: () => ({ setAttribute() {} }),
      getElementById: id => elements[id] || null,
      querySelectorAll: () => []
    }
  });
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/vip.js'), 'utf8'), context);
  return { vip: game.Vip, elements, toasts };
}

test('VIP modal loads and renders using window.Game without a global G', async () => {
  const status = { level: 0, titleName: '候补参谋', nextLevelRequiredCents: 600 };
  let requests = 0;
  const { vip, elements } = setup({ vipStatus: async () => { requests++; return status; } });
  vip.openModal();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(requests, 1);
  assert.equal(vip.cachedStatus.level, status.level);
  assert.match(elements.vipHeroWrap.innerHTML, /VIP 0/);
  assert.doesNotMatch(elements.vipHeroWrap.innerHTML, /加载军令信息中/);
  assert.match(elements.vipModalBody.innerHTML, /每日军饷/);
});

test('VIP request failure replaces loading with escaped feedback and supports retry', async () => {
  let requests = 0;
  const { vip, elements, toasts } = setup({ vipStatus: async () => {
    requests++;
    if (requests === 1) throw new Error('服务异常 <script>');
    return { level: 1, titleName: '特勤参谋' };
  } });
  vip.openModal();
  await new Promise(resolve => setImmediate(resolve));
  assert.match(elements.vipHeroWrap.innerHTML, /服务异常 &lt;script&gt;/);
  assert.match(elements.vipHeroWrap.innerHTML, /Game\.Vip\.refreshStatus\(\)/);
  assert.doesNotMatch(elements.vipHeroWrap.innerHTML, /加载军令信息中/);
  assert.deepEqual(toasts, ['服务异常 <script>']);
  await vip.refreshStatus();
  assert.match(elements.vipHeroWrap.innerHTML, /VIP 1/);
  assert.match(elements.vipModalBody.innerHTML, /每日军饷/);
});

test('VIP modal reports an unavailable API instead of staying in loading state', () => {
  const { vip, elements } = setup({});
  vip.openModal();
  assert.match(elements.vipHeroWrap.innerHTML, /特勤统帅部接口未就绪/);
  assert.doesNotMatch(elements.vipHeroWrap.innerHTML, /加载军令信息中/);
});

test('VIP modal uses server vipLevel, title and recharge thresholds rather than defaulting to VIP 0', async () => {
  const status = {
    vipLevel: 1, title: '特遣队指挥官', totalRechargeCents: 1800,
    levels: [{ level: 1, requiredCents: 600 }, { level: 2, requiredCents: 3000 }]
  };
  const { vip, elements } = setup({ vipStatus: async () => status });
  vip.openModal();
  await new Promise(resolve => setImmediate(resolve));
  assert.match(elements.vipHeroWrap.innerHTML, /VIP 1/);
  assert.match(elements.vipHeroWrap.innerHTML, /特遣队指挥官/);
  assert.match(elements.vipHeroWrap.innerHTML, /升级进度 50%/);
  assert.match(elements.vipHeroWrap.innerHTML, /¥12/);
  assert.doesNotMatch(elements.vipModalBody.innerHTML, /前往充值激活 VIP 1/);
});

test('头像框选项使用服务端标准标识和对应 VIP 解锁等级', () => {
  const { vip } = setup({});
  const html = vip.renderCosmeticsTab({ level: 10, avatarFrame: 'frame-supreme-v1' });
  assert.match(html, /Game\.Vip\.selectFrame\('default'\)/);
  assert.match(html, /Game\.Vip\.selectFrame\('frame-bronze-v1'\)/);
  assert.match(html, /Game\.Vip\.selectFrame\('frame-silver-v1'\)/);
  assert.match(html, /Game\.Vip\.selectFrame\('frame-gold-v1'\)/);
  assert.match(html, /Game\.Vip\.selectFrame\('frame-jade-v1'\)/);
  assert.match(html, /Game\.Vip\.selectFrame\('frame-supreme-v1'\)/);
  assert.match(html, /class="vip-frame-card selected" onclick="Game\.Vip\.selectFrame\('frame-supreme-v1'\)"/);
  assert.match(html, /黄金战纪[\s\S]*?VIP 5/);
  assert.match(html, /翡翠寒魄[\s\S]*?VIP 7/);
  assert.match(html, /星钻蓝光[\s\S]*?VIP 9/);
});

test('VIP 弹层与标签栏在 flex 约束与横屏模式下防止挤压变形，具备滚动保护与自适应紧凑样式', () => {
  const vipCss = fs.readFileSync(path.join(__dirname, '../css/vip.css'), 'utf8');
  const landscapeCss = fs.readFileSync(path.join(__dirname, '../css/landscape.css'), 'utf8');

  // 1. 弹窗布局：纵向 flex，最大高度支持 dvh
  assert.match(vipCss, /\.vip-modal-dialog\s*\{[\s\S]*?display:\s*flex;/, 'VIP 弹窗应为 flex 布局');
  assert.match(vipCss, /\.vip-modal-dialog\s*\{[\s\S]*?flex-direction:\s*column;/, 'VIP 弹窗应为纵向 flex 布局');
  assert.match(vipCss, /\.vip-modal-dialog\s*\{[\s\S]*?max-height:\s*min\(90vh,\s*calc\(100dvh\s*-\s*24px\)\);/, 'VIP 弹窗默认应适配 100dvh');

  // 2. 固定头部、特权概览与标签栏禁止 flex 挤压变形
  assert.match(vipCss, /\.vip-modal-header\s*\{[\s\S]*?flex-shrink:\s*0;/, 'VIP 弹窗头部 flex-shrink 应为 0');
  assert.match(vipCss, /\.vip-hero\s*\{[\s\S]*?flex-shrink:\s*0;/, 'VIP 统帅部特权概览 flex-shrink 应为 0');
  assert.match(vipCss, /\.vip-tabs\s*\{[\s\S]*?flex-shrink:\s*0;/, 'VIP 标签栏 flex-shrink 应为 0 避免横屏矮屏下文字被上下腰斩裁切');
  assert.match(vipCss, /\.vip-tabs\s*\{[\s\S]*?overflow-y:\s*hidden;/, 'VIP 标签栏纵向禁止溢出滚动或裁剪');

  // 3. 弹窗内容区：必须设置 min-height: 0 并允许垂直滚动
  assert.match(vipCss, /\.vip-modal-body\s*\{[\s\S]*?overflow-y:\s*auto;/, 'VIP 弹窗内容区应纵向滚动');
  assert.match(vipCss, /\.vip-modal-body\s*\{[\s\S]*?min-height:\s*0;/, 'VIP 弹窗内容区在 flex 布局下必须有 min-height: 0 保证超出时正确触发滚动');

  // 4. 横屏适配规则
  assert.match(landscapeCss, /\.vip-modal-dialog\s*\{\s*max-height:\s*calc\(100dvh\s*-\s*24px\);/, 'landscape.css 应适配 vip-modal-dialog 最大高度');
  assert.match(vipCss, /@media\s*\(orientation:\s*landscape\)[\s\S]*?\.vip-modal-dialog\s*\{[\s\S]*?max-height:\s*calc\(100dvh\s*-\s*24px\);/, 'vip.css 横屏模式下最大高度不超过 100dvh - 24px');
  assert.match(vipCss, /@media\s*\(orientation:\s*landscape\)\s*and\s*\(max-height:\s*680px\)[\s\S]*?\.vip-hero\s*\{[\s\S]*?padding:\s*8px\s*14px;/, 'vip.css 横屏矮屏下统帅部特权概览紧凑收敛内边距');
  assert.match(vipCss, /@media\s*\(orientation:\s*landscape\)\s*and\s*\(max-height:\s*420px\)[\s\S]*?\.vip-tab-item\s*\{[\s\S]*?padding:\s*5px\s*6px;/, 'vip.css 极限矮屏下标签栏紧凑适配');
});

test('VIP 城市选择下拉框与下拉菜单自定义样式与浮层规范', () => {
  const vipCss = fs.readFileSync(path.join(__dirname, '../css/vip.css'), 'utf8');

  // 1. 验证下拉触发框与下拉菜单选择器存在
  assert.match(vipCss, /\.vip-city-dropdown-wrap/, '应包含 vip-city-dropdown-wrap');
  assert.match(vipCss, /\.vip-city-dropdown-trigger/, '应包含 vip-city-dropdown-trigger 下拉触发框');
  assert.match(vipCss, /\.vip-city-dropdown-menu/, '应包含 vip-city-dropdown-menu 下拉浮层');
  assert.match(vipCss, /\.vip-city-dropdown-item/, '应包含 vip-city-dropdown-item 选项');

  // 2. 验证下拉浮层绝对定位在触发框下方，具有阴影与层级保护
  assert.match(vipCss, /\.vip-city-dropdown-menu\s*\{[\s\S]*?position:\s*absolute;/, '下拉菜单应使用绝对定位');
  assert.match(vipCss, /\.vip-city-dropdown-menu\s*\{[\s\S]*?top:\s*calc\(100%\s*\+\s*4px\);/, '下拉菜单应定位在触发框正下方');
  assert.match(vipCss, /\.vip-city-dropdown-menu\s*\{[\s\S]*?z-index:\s*1001;/, '下拉菜单应有足够高的 z-index');
  assert.match(vipCss, /\.vip-city-dropdown-menu\[hidden\]\s*\{[\s\S]*?display:\s*none\s*!important;/, '隐藏时应强制 display none');

  // 3. 原生 select 使用 vip-sr-only 隐藏交互但保留表单值与无障碍能力
  assert.match(vipCss, /\.vip-sr-only\s*\{[\s\S]*?pointer-events:\s*none/, 'vip-sr-only 应禁止鼠标交互');
});

test('VIP 城市受领下拉框 DOM 生成与主城/分城展示逻辑', () => {
  const status = { level: 1, titleName: '特遣队指挥官' };
  const { vip, elements } = setup({ vipStatus: async () => status });

  // 1. 单主城情况 (slot 0)
  const singleHtml = vip.getCitySelectorHtml();
  assert.match(singleHtml, /class="vip-city-dropdown-wrap"/, '应生成自定义下拉容器');
  assert.match(singleHtml, /id="vipCityDropdownTrigger"/, '应生成下拉触发按钮');
  assert.match(singleHtml, /id="vipCityDropdownMenu"/, '应生成下拉菜单浮层');
  assert.match(singleHtml, /id="vipTargetCitySlot"/, '应保留隐藏的 select 用于状态同步');
  assert.match(singleHtml, /主城/, '单城默认 slot 0 应当正确标为主城而非分城#1');
  assert.doesNotMatch(singleHtml, /分城#0/, '不应出现分城#0');

  // 2. 多城情况支持主城与分城
  const game = {
    World: {
      getPlayerCities: () => [
        { id: 101, name: '战神殿_总舵', slot: 0, main: true, current: true },
        { id: 102, name: '战神殿_弑神门', slot: 1, main: false }
      ]
    },
    Core: { state: { player: { citySlot: 0, cityName: '战神殿_总舵' } } },
    escapeHtml: str => str
  };
  const context = vm.createContext({
    window: { Game: game },
    document: {
      body: { appendChild() {}, removeChild() {} },
      createElement: () => ({ setAttribute() {} }),
      getElementById: () => null
    }
  });
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/vip.js'), 'utf8'), context);
  const multiHtml = game.Vip.getCitySelectorHtml();
  assert.match(multiHtml, /战神殿_总舵/, '应包含主城名');
  assert.match(multiHtml, /战神殿_弑神门/, '应包含分城名');
  assert.match(multiHtml, /分城#1/, '分城应当标记为分城#1');
});

test('VIP 城市选择下拉框交互切换与状态同步', () => {
  const domElements = {};
  const game = {
    World: {
      getPlayerCities: () => [
        { id: 1, name: '主基地', slot: 0, main: true, current: true },
        { id: 2, name: '前哨站', slot: 1, main: false }
      ]
    },
    escapeHtml: str => str
  };

  const menu = {
    hidden: true,
    attributes: { hidden: '' },
    setAttribute(k, v) { this.attributes[k] = v; if (k === 'hidden') this.hidden = true; },
    removeAttribute(k) { delete this.attributes[k]; if (k === 'hidden') this.hidden = false; }
  };
  const trigger = {
    attributes: {},
    setAttribute(k, v) { this.attributes[k] = v; }
  };
  const wrap = {
    classList: {
      classes: new Set(),
      add(c) { this.classes.add(c); },
      remove(c) { this.classes.delete(c); },
      contains(c) { return this.classes.has(c); }
    }
  };
  const textEl = { textContent: '' };
  const selectEl = { value: '0' };

  domElements.vipCityDropdownMenu = menu;
  domElements.vipCityDropdownTrigger = trigger;
  domElements.vipCityDropdownWrap = wrap;
  domElements.vipCityDropdownText = textEl;
  domElements.vipTargetCitySlot = selectEl;

  const item1 = {
    attributes: { 'data-slot': '0' },
    getAttribute(k) { return this.attributes[k]; },
    setAttribute(k, v) { this.attributes[k] = v; },
    classList: { toggle(c, val) { this[c] = val; } },
    querySelector() { return { innerHTML: '' }; }
  };
  const item2 = {
    attributes: { 'data-slot': '1' },
    getAttribute(k) { return this.attributes[k]; },
    setAttribute(k, v) { this.attributes[k] = v; },
    classList: { toggle(c, val) { this[c] = val; } },
    querySelector() { return { innerHTML: '' }; }
  };

  const context = vm.createContext({
    window: { Game: game },
    document: {
      body: { appendChild() {}, removeChild() {} },
      createElement: () => ({ setAttribute() {} }),
      getElementById: id => domElements[id] || null,
      querySelectorAll: () => [item1, item2]
    }
  });
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/vip.js'), 'utf8'), context);

  const vip = game.Vip;
  assert.equal(vip.cityDropdownOpen, false);

  // 1. 打开下拉菜单
  vip.toggleCityDropdown();
  assert.equal(vip.cityDropdownOpen, true);
  assert.equal(menu.hidden, false);
  assert.equal(trigger.attributes['aria-expanded'], 'true');
  assert.equal(wrap.classList.contains('open'), true);

  // 2. 选择城市分城#1
  vip.selectTargetCity(1, '前哨站 (分城#1)');
  assert.equal(vip.selectedCitySlot, 1);
  assert.equal(vip.cityDropdownOpen, false);
  assert.equal(menu.hidden, true);
  assert.equal(textEl.textContent, '前哨站 (分城#1)');
  assert.equal(selectEl.value, '1');
  assert.equal(vip.getSelectedTargetCitySlot(), 1);

  // 3. 再次切换回主城
  vip.selectTargetCity(0, '主基地 (主城)');
  assert.equal(vip.selectedCitySlot, 0);
  assert.equal(selectEl.value, '0');
  assert.equal(vip.getSelectedTargetCitySlot(), 0);

  // 4. 关闭方法安全幂等
  vip.closeCityDropdown();
  assert.equal(vip.cityDropdownOpen, false);
});

test('VIP 0~10 全等级特权与奖励查看器支持全等级胶囊横向切换与门槛物资预览', async () => {
  const status = {
    vipLevel: 2,
    title: '前线先锋官',
    totalRechargeCents: 3000,
    dailyClaimed: false,
    levels: [
      {
        level: 0, requiredCents: 0, title: '预备指挥官',
        buildQueuePerCity: 3, techQueuePerCity: 1,
        dailyRewards: { gold: 10000, food: 10000, steel: 10000, oil: 10000, rare: 10000 }
      },
      {
        level: 1, requiredCents: 600, title: '特遣队指挥官',
        buildQueuePerCity: 5, techQueuePerCity: 1,
        dailyRewards: { gold: 20000, food: 20000 },
        levelPackRewards: { recruitOrd: 1, gold: 400000 }
      },
      {
        level: 2, requiredCents: 3000, title: '前线先锋官',
        buildQueuePerCity: 5, techQueuePerCity: 1,
        dailyRewards: { gold: 30000, food: 50000 },
        levelPackRewards: { recruitOrd: 2, teleportNormal: 2 }
      },
      {
        level: 6, requiredCents: 64800, title: '兵团总司令官',
        buildQueuePerCity: 5, techQueuePerCity: 2,
        dailyRewards: { gold: 70000, food: 400000 },
        levelPackRewards: { recruitOrd: 6, supplyPack: 3 }
      }
    ]
  };

  const { vip, elements } = setup({ vipStatus: async () => status });
  vip.openModal();
  await new Promise(resolve => setImmediate(resolve));

  // 1. 验证渲染了全等级选择胶囊
  assert.match(elements.vipModalBody.innerHTML, /vip-level-pills-row/);
  assert.match(elements.vipModalBody.innerHTML, /VIP 0/);
  assert.match(elements.vipModalBody.innerHTML, /VIP 2 \(当前\)/);
  assert.match(elements.vipModalBody.innerHTML, /VIP 10/);

  // 2. 默认打开当前等级 VIP 2，验证门槛金额与物资显示
  assert.match(elements.vipModalBody.innerHTML, /VIP 2 · 前线先锋官/);
  assert.match(elements.vipModalBody.innerHTML, /达成门槛: 累充 <b>¥30<\/b>/);
  assert.match(elements.vipModalBody.innerHTML, /您已达成该等级/);

  // 3. 点击切换至 VIP 6 预览
  vip.selectInspectedLevel(6);
  assert.equal(vip.inspectedLevel, 6);
  assert.match(elements.vipModalBody.innerHTML, /VIP 6 · 兵团总司令官/);
  assert.match(elements.vipModalBody.innerHTML, /达成门槛: 累充 <b>¥648<\/b>/);
  assert.match(elements.vipModalBody.innerHTML, /未达成 \(还需 ¥618\)/);
  assert.match(elements.vipModalBody.innerHTML, /双科技并行研发/);
  assert.match(elements.vipModalBody.innerHTML, /全疆域一键安抚/);

  // 4. 切换至专属晋升礼包 Tab，验证礼包卡片显式标注充值门槛金额
  vip.switchTab('packs');
  assert.match(elements.vipModalBody.innerHTML, /累充 ¥6 达成/);
  assert.match(elements.vipModalBody.innerHTML, /累充 ¥30 达成/);
  assert.match(elements.vipModalBody.innerHTML, /累充 ¥648 达成/);
});

test('VIP 每日军饷道具丰富度：VIP 0 包含技能书/人口动员/加速，每日军饷取消星耀符与改名卡，高级VIP包含战术令且晋级大礼赠送高额星耀符', async () => {
  const { vip, elements } = setup({
    vipStatus: async () => ({
      vipLevel: 0,
      title: '预备指挥官',
      totalRechargeCents: 0
    })
  });
  vip.openModal();
  await new Promise(resolve => setImmediate(resolve));

  // 1. VIP 0 每日军饷包含普通技能书、人口动员令、10分加速符；星耀符已从每日军饷移除，改名卡已移至任务每日签到
  assert.match(elements.vipModalBody.innerHTML, /普通技能书/);
  assert.match(elements.vipModalBody.innerHTML, /人口动员令/);
  assert.match(elements.vipModalBody.innerHTML, /10分加速/);
  assert.doesNotMatch(elements.vipModalBody.innerHTML, /星耀符/);
  assert.doesNotMatch(elements.vipModalBody.innerHTML, /军官改名卡/);
  assert.doesNotMatch(elements.vipModalBody.innerHTML, /城市改名卡/);

  // 2. 查看 VIP 5 每日军饷，验证战术令与高危道具杜绝，且每日军饷同样不含星耀符
  vip.selectInspectedLevel(5);
  const vip5Sections = elements.vipModalBody.innerHTML.split('每日战备特勤军饷配给');
  const vip5DailyHtml = vip5Sections[1] ? vip5Sections[1].split('专属终身晋升大礼包')[0] : '';
  assert.match(vip5DailyHtml, /行军令/);
  assert.match(vip5DailyHtml, /通讯干扰令/);
  assert.match(vip5DailyHtml, /撤退拖延令/);
  assert.match(vip5DailyHtml, /紧急动员令/);
  assert.doesNotMatch(vip5DailyHtml, /星耀符/);
  assert.doesNotMatch(vip5DailyHtml, /军官改名卡/);
  assert.doesNotMatch(vip5DailyHtml, /城市改名卡/);
  // 杜绝传送与满级经验书
  assert.doesNotMatch(vip5DailyHtml, /普通传送/);
  assert.doesNotMatch(vip5DailyHtml, /高级传送/);
  assert.doesNotMatch(vip5DailyHtml, /满级经验书/);
  // 验证晋升大礼包中大幅赠送星耀符 (VIP 5: 150 枚)
  assert.match(elements.vipModalBody.innerHTML, /星耀符.*150/);

  // 3. 查看最高级 VIP 10 每日军饷，验证改名卡与星耀符已彻底取消，避免背包冗余与长线通胀
  vip.selectInspectedLevel(10);
  const vip10Sections = elements.vipModalBody.innerHTML.split('每日战备特勤军饷配给');
  const vip10DailyHtml = vip10Sections[1] ? vip10Sections[1].split('专属终身晋升大礼包')[0] : '';
  assert.doesNotMatch(vip10DailyHtml, /星耀符/);
  assert.doesNotMatch(vip10DailyHtml, /军官改名卡/);
  assert.doesNotMatch(vip10DailyHtml, /城市改名卡/);
  assert.match(vip10DailyHtml, /24时护盾/);
  // 验证 VIP 10 晋升大礼包中赠送 600 枚星耀符
  assert.match(elements.vipModalBody.innerHTML, /星耀符.*600/);

  // 4. 切换至专属晋升礼包 Tab，验证晋级大礼包中高额赠送星耀符（一次性晋升重奖）
  vip.switchTab('packs');
  assert.match(elements.vipModalBody.innerHTML, /星耀符/);
});

test('VIP默认预览按最终资源表且每日不叠加资源包', async () => {
  const { vip } = setup({ vipStatus: async () => ({ vipLevel: 0, totalRechargeCents: 0 }) });
  vip.openModal();
  await new Promise(resolve => setImmediate(resolve));
  const levels = vip.cachedStatus.levels;
  const gold = [10000, 20000, 30000, 40000, 50000, 60000, 70000, 80000, 90000, 100000, 110000];
  const material = [10000, 20000, 50000, 100000, 200000, 300000, 400000, 500000, 600000, 700000, 800000];
  levels.forEach((level, index) => {
    assert.equal(level.dailyRewards.gold, gold[index]);
    for (const key of ['food', 'steel', 'oil', 'rare']) assert.equal(level.dailyRewards[key], material[index]);
    assert.equal(level.dailyRewards.supplyPack, undefined);
    assert.ok(level.dailyRewards.skillBook > 0);
    assert.ok(level.dailyRewards.populationOrder > 0);
  });
});
