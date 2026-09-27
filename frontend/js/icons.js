/* global window */
window.Game = window.Game || {};

(function (G) {
  'use strict';

  function escapeAttribute(value) {
    return String(value == null ? '' : value).replace(/[&<>"']/g, function (char) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char];
    });
  }

  /**
   * 将本地图片或文字图标渲染为 HTML；仅供 innerHTML 模板，toast 等纯文本使用名称。
   * @param {string} icon - 本地图片路径或文字图标
   * @param {string} label - 图片替代文本
   * @returns {string} 已转义的图标 HTML
   */
  G.iconHtml = function (icon, label) {
    if (/^img\/.+\.(svg|png|jpe?g|gif|webp)$/i.test(icon || '')) {
      return '<img class="game-icon-img" src="' + escapeAttribute(icon) + '" alt="' +
        escapeAttribute(label) + '" draggable="false"/>';
    }
    return escapeAttribute(icon || label);
  };

  /**
   * 所有资源展示均读取首页同一份资源定义，不再维护独立 emoji 映射。
   * @param {string} key - 资源键，如 steel、food、rare
   * @returns {string} 资源图片 HTML，未知资源回退为名称
   */
  G.resourceIconHtml = function (key) {
    var resource = (G.DATA && G.DATA.resources && G.DATA.resources[key]) || {};
    return G.iconHtml(resource.icon, resource.name || key);
  };
})(window.Game);
