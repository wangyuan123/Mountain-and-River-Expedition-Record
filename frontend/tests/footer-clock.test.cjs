const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setupClock() {
  let currentTime = '2026-09-30T15:59:59.000Z';
  let intervalCount = 0;
  let tick;
  const nodes = { beijingTime: { textContent: '', dateTime: '' } };
  const context = {
    document: { getElementById: id => nodes[id] || null },
    Date: class extends Date {
      constructor(...args) { super(...(args.length ? args : [currentTime])); }
    },
    setInterval(callback, delay) {
      assert.equal(delay, 1000);
      intervalCount++;
      tick = callback;
      return intervalCount;
    }
  };
  context.window = context;
  vm.createContext(context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/core.js'), 'utf8'), context);
  return {
    core: context.Game.Core,
    nodes,
    setTime(value) { currentTime = value; },
    tick() { tick(); },
    intervalCount() { return intervalCount; }
  };
}

test('footer clock displays the Beijing date and time even across month and year boundaries', () => {
  const { core } = setupClock();
  assert.equal(core.formatBeijingTime(new Date('2026-09-30T15:59:59.000Z')), '2026年9月30日 23:59:59');
  assert.equal(core.formatBeijingTime(new Date('2026-09-30T16:00:00.000Z')), '2026年10月1日 00:00:00');
  assert.equal(core.formatBeijingTime(new Date('2026-12-31T16:00:00.000Z')), '2027年1月1日 00:00:00');
  core.route = 'login';
  const footer = core.footer();
  assert.match(footer, /<time id="beijingTime" class="footer-beijing-time" datetime="">北京时间：加载中…<\/time>/);
  assert.ok(footer.indexOf('id="beijingTime"') > footer.indexOf('footer-icp'));
});

test('footer clock updates every second without creating timers on repeated renders', () => {
  const clock = setupClock();
  clock.core.startBeijingClock();
  assert.equal(clock.nodes.beijingTime.textContent, '北京时间：2026年9月30日 23:59:59');
  assert.equal(clock.nodes.beijingTime.dateTime, '2026-09-30T15:59:59.000Z');
  clock.setTime('2026-09-30T16:00:00.000Z');
  clock.tick();
  assert.equal(clock.nodes.beijingTime.textContent, '北京时间：2026年10月1日 00:00:00');
  clock.nodes.beijingTime = { textContent: '', dateTime: '' };
  clock.core.startBeijingClock();
  assert.equal(clock.nodes.beijingTime.textContent, '北京时间：2026年10月1日 00:00:00');
  assert.equal(clock.intervalCount(), 1);
});
