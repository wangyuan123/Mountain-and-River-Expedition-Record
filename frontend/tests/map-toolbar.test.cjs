const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../js/world-map.js'), 'utf8');
const styles = fs.readFileSync(path.join(__dirname, '../css/world-map.css'), 'utf8');
const toolbarExpression = source.match(/v\.innerHTML = ([\s\S]*?)\+\s*'<div class="world-map-filters"/)[1];

test('map toolbar keeps compact navigation and coordinate controls without a refresh button', () => {
  const html = vm.runInNewContext(toolbarExpression, { G: {} });
  assert.match(html, /data-map="back" aria-label="返回上一步"><span>‹ 返回上一步<\/span>/);
  assert.match(html, /aria-label="定位坐标" placeholder="100,100"/);
  assert.match(html, /type="submit"><span>定位<\/span>/);
  assert.match(html, /data-map="list" aria-label="查看列表地图"><span>查看列表地图&gt;<\/span>/);
  assert.match(html, /data-map="full"/);
  assert.doesNotMatch(html, /data-map="refresh"|\[刷新\]/);
});

test('map toolbar retains the radar toggle when radar is available', () => {
  const radarHtml = '<button data-map="radar">雷达：开</button>';
  const html = vm.runInNewContext(toolbarExpression, { G: { Radar: { buttonHtml: () => radarHtml } } });
  assert.ok(html.includes('<span data-radar-tools>' + radarHtml + '</span>'));
});

test('portrait toolbar wraps without squeezing radar or reducing the small-screen font', () => {
  const portraitStyles = styles.slice(styles.indexOf('@media (orientation:portrait) {'));
  assert.match(portraitStyles, /\.world-map-toolbar\s*\{[^}]*flex-wrap:wrap;/);
  assert.match(portraitStyles, /\.world-map-search\s*\{[^}]*flex:0 1 104px;[^}]*min-width:88px;/);
  assert.match(portraitStyles, /\.world-map-search input\s*\{[^}]*width:72px;[^}]*min-width:56px;/);
  assert.match(portraitStyles, /\[data-radar-tools\]\s*\{[^}]*flex:0 0 auto;/);
  assert.doesNotMatch(portraitStyles, /font-size:11px|min-width:40px/);
});
