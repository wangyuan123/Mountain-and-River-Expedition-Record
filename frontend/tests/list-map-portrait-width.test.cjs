const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const styleCss = fs.readFileSync(path.join(__dirname, '../css/style.css'), 'utf8');
const themesCss = fs.readFileSync(path.join(__dirname, '../css/themes.css'), 'utf8');

test('style.css enforces anti-horizontal overflow constraints for mobile portrait viewport', () => {
  // 1. html, body, #screen, #view, .world-main overflow containment
  assert.match(styleCss, /html,\s*body\s*\{[^}]*overflow-x:\s*hidden;/);
  assert.match(styleCss, /#screen\s*\{[^}]*overflow-x:\s*hidden;/);
  assert.match(styleCss, /#view\s*\{[^}]*overflow-x:\s*hidden;/);
  assert.match(styleCss, /\.world-main\s*\{[^}]*overflow-x:\s*hidden;/);
  assert.match(styleCss, /\.world-main\s*\{[^}]*max-width:\s*100%;/);

  // 2. .tcard-grid uses single-column minmax(0, 1fr) by default in portrait mode
  assert.match(styleCss, /\.tcard-grid\s*\{[^}]*grid-template-columns:\s*minmax\(0,\s*1fr\);/);
  // Only allows 2 columns on landscape screens >= 768px
  assert.match(styleCss, /@media\s*\(min-width:\s*768px\)\s*and\s*\(orientation:\s*landscape\)\s*\{\s*\.tcard-grid\s*\{\s*grid-template-columns:\s*repeat\(2,\s*minmax\(0,\s*1fr\)\);/);

  // 3. .tcard enforces min-width: 0 and max-width: 100% to allow grid tracks to shrink properly
  assert.match(styleCss, /\.tcard\s*\{[^}]*min-width:\s*0;/);
  assert.match(styleCss, /\.tcard\s*\{[^}]*max-width:\s*100%;/);
  assert.match(styleCss, /\.tcard\s*\{[^}]*overflow:\s*hidden;/);

  // 4. .tcard-collapsible .tcard-mini-row layout and overflow safeguards
  assert.match(styleCss, /\.tcard-collapsible\s*\.tcard-mini-row\s*\{[^}]*min-width:\s*0;/);
  assert.match(styleCss, /\.tcard-collapsible\s*\.tcard-mini-row\s*\{[^}]*max-width:\s*100%;/);
  assert.match(styleCss, /\.tcard-collapsible\s*\.tcard-mini-row\s*\{[^}]*overflow:\s*hidden;/);
  assert.match(styleCss, /\.tcard-collapsible\s*\.tcard-mini-dist\s*\{[^}]*flex-shrink:\s*0;/);
  assert.match(styleCss, /\.tcard-collapsible\s*\.tcard-mini-caret\s*\{[^}]*margin-left:\s*auto;/);
  assert.match(styleCss, /\.tcard-collapsible\s*\.tcard-mini-caret\s*\{[^}]*flex-shrink:\s*0;/);

  // 5. .map-tabs scrollable instead of overflowing
  assert.match(styleCss, /\.map-tabs\s*\{[^}]*overflow-x:\s*auto;/);

  // 6. .map-toolbar-quick minmax track safety
  assert.match(styleCss, /\.map-toolbar-quick\s*\{[^}]*grid-template-columns:\s*repeat\(4,\s*minmax\(0,\s*1fr\)\);/);

  // 7. .map-control-bar box-sizing and max-width
  assert.match(styleCss, /\.map-control-bar\s*\{[^}]*max-width:\s*calc\(100%\s*\+\s*24px\);/);
  assert.match(styleCss, /\.map-control-bar\s*\{[^}]*box-sizing:\s*border-box;/);
});

test('themes.css blue-white theme does not override with overflow: visible and respects single column', () => {
  // 1. #screen and #view prevent overflow in blue-white theme
  assert.match(themesCss, /\[data-theme\|="blue-white"\]\s*#screen\s*\{[^}]*overflow-x:\s*hidden\s*!important;/);
  assert.match(themesCss, /\[data-theme\|="blue-white"\]\s*#view\s*\{[^}]*overflow-x:\s*hidden\s*!important;/);

  // 2. .map-tabs does NOT have overflow: visible !important
  assert.doesNotMatch(themesCss, /\[data-theme\|="blue-white"\]\s*\.map-tabs\s*\{[^}]*overflow:\s*visible/);
  assert.match(themesCss, /\[data-theme\|="blue-white"\]\s*\.map-tabs\s*\{[^}]*overflow-x:\s*auto\s*!important;/);

  // 3. .tcard-grid in blue-white theme defaults to single column minmax(0, 1fr)
  assert.match(themesCss, /\[data-theme\|="blue-white"\]\s*\.tcard-grid\s*\{[^}]*grid-template-columns:\s*minmax\(0,\s*1fr\)\s*!important;/);
  assert.match(themesCss, /@media\s*\(min-width:\s*768px\)\s*and\s*\(orientation:\s*landscape\)\s*\{\s*\[data-theme\|="blue-white"\]\s*\.tcard-grid\s*\{\s*grid-template-columns:\s*repeat\(2,\s*minmax\(0,\s*1fr\)\)\s*!important;/);

  // 4. .tcard has min-width: 0, max-width: 100%, and overflow: hidden
  assert.match(themesCss, /\[data-theme\|="blue-white"\]\s*\.tcard\s*\{[^}]*min-width:\s*0\s*!important;/);
  assert.match(themesCss, /\[data-theme\|="blue-white"\]\s*\.tcard\s*\{[^}]*max-width:\s*100%\s*!important;/);
  assert.match(themesCss, /\[data-theme\|="blue-white"\]\s*\.tcard\s*\{[^}]*overflow:\s*hidden\s*!important;/);

  // 5. mini-row and its elements safely elide long text and pin caret
  assert.match(themesCss, /\[data-theme\|="blue-white"\]\s*\.tcard-collapsible\s*\.tcard-mini-row,\s*\[data-theme\|="blue-white"\]\s*\.tcard-player-mini\s*\.tcard-mini-row\s*\{[^}]*overflow:\s*hidden\s*!important;/);
  assert.match(themesCss, /\[data-theme\|="blue-white"\]\s*\.tcard-mini-name\s*\{[^}]*text-overflow:\s*ellipsis\s*!important;/);
  assert.match(themesCss, /\[data-theme\|="blue-white"\]\s*\.tcard-mini-caret\s*\{[^}]*margin-left:\s*auto\s*!important;/);
  assert.match(themesCss, /\[data-theme\|="blue-white"\]\s*\.tcard-mini-caret\s*\{[^}]*flex-shrink:\s*0\s*!important;/);

  // 6. map-control-bar box-sizing and max-width in theme
  assert.match(themesCss, /\[data-theme\|="blue-white"\]\s*\.map-control-bar\s*\{[^}]*max-width:\s*calc\(100%\s*\+\s*24px\)\s*!important;/);
});
