const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

test('topbar retains forum button and does not get overwritten by diamond refreshTop', () => {
  const topbarElement = {
    _html: '',
    set innerHTML(val) {
      this._html = val;
    },
    get innerHTML() {
      return this._html;
    },
    querySelector(selector) {
      if (selector === '.forum-entry-btn') {
        if (!this._html.includes('forum-entry-btn')) return null;
        return {
          textContent: '🎖️ 论坛',
          className: 'forum-entry-btn'
        };
      }
      if (selector === '.diamond' || selector === '.player-bar-right .diamond') {
        const match = this._html.match(/<span class="diamond"[^>]*>([^<]+)<\/span>/);
        if (!match) return null;
        const el = {
          textContent: match[1],
          className: 'diamond'
        };
        return el;
      }
      return null;
    }
  };

  const document = {
    getElementById(id) {
      if (id === 'topbar') return topbarElement;
      return null;
    },
    querySelector() { return null; }
  };

  const context = {
    console,
    Math,
    document,
    $: (id) => document.getElementById(id),
    fmt: (n) => String(n),
    escapeHtml: (s) => String(s == null ? '' : s),
    Game: {
      WS: { statusHtml: () => '<span>在线</span>' },
      Constants: { presetAvatars: [{ src: 'avatar.png' }], siteInfo: { title: '山河远征录' } }
    }
  };
  context.G = context.Game;
  context.window = context;
  context.self = context;

  vm.createContext(context);

  const coreCode = fs.readFileSync(path.join(__dirname, '../js/core.js'), 'utf8');
  vm.runInContext(coreCode, context);

  const Core = context.Game.Core;
  Core.state = {
    player: { name: '深入山林开荒种地' },
    resources: { diamond: 190960 },
    world: { marches: [], incoming: [] }
  };

  // 1. Initial renderTop
  Core.renderTop();
  assert.ok(topbarElement.innerHTML.includes('forum-entry-btn'), 'Should render forum-entry-btn');
  assert.ok(topbarElement.innerHTML.includes('🎖️ 论坛'), 'Should render forum text');
  assert.ok(topbarElement.innerHTML.includes('💎 190960'), 'Should render diamond text');

  // 2. Periodic refreshTop should NOT overwrite forum-entry-btn
  Core.state.resources.diamond = 190960;
  Core.refreshTop();
  assert.ok(topbarElement.innerHTML.includes('forum-entry-btn'), 'Should still retain forum-entry-btn after refresh');
  assert.ok(topbarElement.innerHTML.includes('🎖️ 论坛'), 'Should still retain 🎖️ 论坛 text');

  // Verify diamond selector specifically found diamond element, not forum button
  const forum = topbarElement.querySelector('.forum-entry-btn');
  assert.equal(forum.textContent, '🎖️ 论坛');
});
