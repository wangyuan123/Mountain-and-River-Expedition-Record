const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup() {
  const context = vm.createContext({ document: { getElementById: () => null } });
  context.window = context;
  for (const file of ['constants.js', 'data.js', 'core.js']) {
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../js', file), 'utf8'), context, { filename: file });
  }
  return context.Game;
}

test('时长统一省略前导零单位，保留最高有效单位之后的小时、分钟和秒', () => {
  const game = setup();
  for (const [seconds, expected] of [
    [0, '0秒'], [1, '1秒'], [59, '59秒'], [60, '1分钟0秒'], [61, '1分钟1秒'],
    [3599, '59分钟59秒'], [3600, '1小时0分钟0秒'], [3605, '1小时0分钟5秒'],
    [3665, '1小时1分钟5秒'], [86399, '23小时59分钟59秒'], [86400, '1天0小时0分钟0秒'],
    [86405, '1天0小时0分钟5秒'], [90061, '1天1小时1分钟1秒'], [180501, '2天2小时8分钟21秒']
  ]) {
    assert.equal(game.formatDuration(seconds), expected);
    assert.equal(game.formatGatherDuration(seconds), expected);
    assert.equal(game.fmtTime(seconds), expected);
  }
});

test('倒计时正数向上取整到秒，结束和无效输入不显示负数或NaN', () => {
  const game = setup();
  for (const [seconds, expected] of [
    [0.001, '1秒'], [59.001, '1分钟0秒'], [3599.001, '1小时0分钟0秒'],
    [-1, '0秒'], [NaN, '0秒'], [Infinity, '0秒'], [-Infinity, '0秒'],
    [undefined, '0秒'], [null, '0秒'], ['invalid', '0秒'], ['60', '1分钟0秒']
  ]) assert.equal(game.formatDuration(seconds), expected);
});
