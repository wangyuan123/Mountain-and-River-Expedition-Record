const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setupMainView() {
  const domElements = [];
  const fakeDocument = {
    body: {
      appendChild: (el) => {
        domElements.push(el);
      }
    },
    createElement: (tag) => {
      const el = {
        tagName: tag.toUpperCase(),
        className: '',
        innerHTML: '',
        style: {},
        querySelector: (sel) => {
          if (sel === '#closeResourceDetail' || sel === '#closeResourceDetailX') {
            return { onclick: null };
          }
          return null;
        },
        querySelectorAll: () => [],
        addEventListener: () => {},
        remove: () => {
          const idx = domElements.indexOf(el);
          if (idx !== -1) domElements.splice(idx, 1);
        }
      };
      return el;
    },
    getElementById: () => null
  };

  const context = {
    console,
    Math,
    Date,
    parseInt,
    parseFloat,
    String,
    Boolean,
    Object,
    Array,
    document: fakeDocument
  };
  context.window = context;
  vm.createContext(context);

  for (const file of ['constants.js', 'data.js', 'core.js', 'main-view.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), context);
  }

  return { context, Game: context.Game, domElements };
}

test('showResourceDetail 生成带有 res-detail-modal 与 res-detail-body 的弹窗结构', () => {
  const { Game, domElements } = setupMainView();
  assert.equal(typeof Game.MainView.showResourceDetail, 'function', 'showResourceDetail 应暴露在 Game.MainView 上');

  Game.MainView.showResourceDetail('food', '粮食', '🌾', 5000, 10000, 20000, 100, 150, 50);
  assert.equal(domElements.length, 1, '应向 document.body 添加 1 个弹窗元素');

  const modalMask = domElements[0];
  assert.ok(modalMask.className.includes('modal-mask'), '弹窗外层应包含 modal-mask 类');
  assert.ok(modalMask.innerHTML.includes('modal-card res-detail-modal'), '弹窗卡片应包含 res-detail-modal 类名');
  assert.ok(modalMask.innerHTML.includes('modal-body res-detail-body'), '弹窗内容区应包含 res-detail-body 类名');
  assert.ok(modalMask.innerHTML.includes('粮食详情'), '弹窗标题应正确渲染资源名称');
  assert.ok(modalMask.innerHTML.includes('id="closeResourceDetailX"'), '弹窗右上角应包含 ✕ 关闭按钮');
  assert.ok(modalMask.innerHTML.includes('id="closeResourceDetail"'), '弹窗底部应包含关闭按钮');
  assert.ok(modalMask.innerHTML.includes('modal-close-btn'), '右上角关闭按钮应使用 modal-close-btn 类名');
  assert.ok(modalMask.innerHTML.includes('农田生产'), '粮食详情应包含农田生产明细');
  assert.ok(modalMask.innerHTML.includes('军队消耗'), '粮食详情应包含军队消耗明细');
});

test('style.css 严格约束资源弹窗由内容撑开且最大高度不超过手机屏幕 2/3，超出时内部滚动', () => {
  const css = fs.readFileSync(path.join(__dirname, '../css/style.css'), 'utf8');
  
  // 校验 modal-card.res-detail-modal 规则
  assert.match(css, /\.modal-card\.res-detail-modal[\s\S]*?height:\s*auto;/, 'res-detail-modal 应设置 height: auto 由内容撑开');
  assert.match(css, /\.modal-card\.res-detail-modal[\s\S]*?max-height:\s*calc\(100vh\s*\*\s*2\s*\/\s*3\);/, 'res-detail-modal 应设置 max-height 100vh * 2 / 3');
  assert.match(css, /\.modal-card\.res-detail-modal[\s\S]*?max-height:\s*calc\(100dvh\s*\*\s*2\s*\/\s*3\);/, 'res-detail-modal 应支持 100dvh 动态视口高度 2/3');
  assert.match(css, /\.modal-card\.res-detail-modal[\s\S]*?display:\s*flex;/, 'res-detail-modal 应为 flex 布局');
  assert.match(css, /\.modal-card\.res-detail-modal[\s\S]*?flex-direction:\s*column;/, 'res-detail-modal 应为纵向 flex 布局');

  // 校验 modal-body 滚动
  assert.match(css, /(\.modal-card\.res-detail-modal\s+\.modal-body|\.res-detail-body)[\s\S]*?overflow-y:\s*auto;/, '弹窗内容区超出应设置 overflow-y: auto 垂直滚动');
  assert.match(css, /(\.modal-card\.res-detail-modal\s+\.modal-body|\.res-detail-body)[\s\S]*?min-height:\s*0;/, '弹窗内容区在 flex 布局下应有 min-height: 0 保证超出时正确触发滚动');
});

test('landscape.css 与 themes.css 正确适配资源详情弹窗', () => {
  const landscapeCss = fs.readFileSync(path.join(__dirname, '../css/landscape.css'), 'utf8');
  assert.match(landscapeCss, /\.modal-card\.res-detail-modal[\s\S]*?max-height:\s*calc\(100dvh\s*\*\s*2\s*\/\s*3\);/, '横屏模式下资源弹窗仍保持不超过 2/3 屏幕高度');

  const themesCss = fs.readFileSync(path.join(__dirname, '../css/themes.css'), 'utf8');
  assert.ok(themesCss.includes('[data-theme|="blue-white"] .res-detail-modal'), '晴空蓝白主题应适配 res-detail-modal');
  assert.ok(themesCss.includes('[data-theme="paper"] .res-detail-modal'), '公文沙盘主题应适配 res-detail-modal');
  assert.ok(themesCss.includes('[data-theme="dark"] .res-detail-modal'), '夜航黑终端主题应适配 res-detail-modal');
});

test('world-map.css 严格约束地图资源地块弹窗由内容撑开且最大高度不超过屏幕 2/3', () => {
  const mapCss = fs.readFileSync(path.join(__dirname, '../css/world-map.css'), 'utf8');

  // 校验 .world-map-detail 规则：由内容撑开，最大高度为 2/3 手机屏幕
  assert.match(mapCss, /\.world-map-detail\s*\{[^}]*height:\s*auto;/, 'world-map-detail 应设置 height: auto 由内容撑开');
  assert.match(mapCss, /\.world-map-detail\s*\{[^}]*max-height:\s*min\(calc\(100vh\s*\*\s*2\s*\/\s*3\)/, 'world-map-detail 应限制最大高度为 2/3 屏幕高度');
  assert.match(mapCss, /\.world-map-detail\s*\{[^}]*max-height:\s*min\(calc\(100dvh\s*\*\s*2\s*\/\s*3\)/, 'world-map-detail 应支持 100dvh 动态视口 2/3');
  assert.match(mapCss, /\.world-map-detail\s*\{[^}]*overflow-y:\s*auto;/, 'world-map-detail 超出后应垂直滚动展示');
  assert.match(mapCss, /\.world-map-detail\s*\{[^}]*z-index:\s*6;/, 'world-map-detail 应具备足够层级覆盖控制控件');
});
