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

  /**
   * 列表地图专属军工战术矢量图标库，替代粗糙原生 Emoji。
   */
  G.MapIcons = {
    city: '<svg class="map-icon map-icon-city" viewBox="0 0 16 16" aria-hidden="true"><path d="M1 14h14v1.2H1V14z" fill="currentColor" opacity="0.9"/><path d="M2 7h2v6H2V7zm10 0h2v6h-2V7z" fill="currentColor" opacity="0.85"/><path d="M4 8.5h8v4.5H9.5V10c0-.8-.7-1.5-1.5-1.5s-1.5.7-1.5 1.5v3H4V8.5z" fill="currentColor"/><path d="M1.5 5.5h3V7h-3V5.5zm10 0h3V7h-3V5.5z" fill="currentColor"/><path d="M5.5 3h5v5.5h-5V3z" fill="currentColor" opacity="0.9"/><path d="M5 3l3-2 3 2H5z" fill="currentColor"/><circle cx="8" cy="5.2" r="0.9" fill="#ffffff" opacity="0.9"/></svg>',
    view: '<svg class="map-icon map-icon-view" viewBox="0 0 16 16" aria-hidden="true"><circle cx="8" cy="8" r="6" fill="none" stroke="currentColor" stroke-width="1.4"/><circle cx="8" cy="8" r="2.2" fill="currentColor" fill-opacity="0.3" stroke="currentColor" stroke-width="1"/><circle cx="8" cy="8" r="1.1" fill="currentColor"/><path d="M8 1v3.2M8 11.8V15M1 8h3.2M11.8 8H15" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/></svg>',
    wild: '<svg class="map-icon map-icon-wild" viewBox="0 0 16 16" aria-hidden="true"><path d="M1 14h14v1.2H1V14z" fill="currentColor" opacity="0.85"/><path d="M1.5 13.5L6.2 4.2c.4-.8 1.2-.8 1.6 0l4.2 8.3c.3.6-.1 1-.8 1H2.3c-.7 0-1.1-.4-.8-1z" fill="currentColor" fill-opacity="0.8"/><path d="M7 4.2l3.8 8.8H7V4.2z" fill="#ffffff" fill-opacity="0.25"/><path d="M8.5 13.5l3.2-5.5c.3-.5.9-.5 1.2 0l2.3 4.5c.2.4-.1 1-.6 1h-5.5c-.4 0-.7-.6-.6-1z" fill="currentColor" fill-opacity="0.95"/><path d="M12.3 8l2 5h-2V8z" fill="#ffffff" fill-opacity="0.3"/><path d="M3.8 13.5l1.4-2.5h-.7l1.1-2h-.6l.9-1.6.9 1.6h-.6l1.1 2h-.7l1.4 2.5H3.8z" fill="currentColor"/></svg>',
    bandit: '<svg class="map-icon map-icon-bandit" viewBox="0 0 16 16" aria-hidden="true"><circle cx="8" cy="8" r="6.5" fill="none" stroke="currentColor" stroke-width="1.2" stroke-dasharray="2.5 1.5" opacity="0.6"/><path d="M2.2 2.2l4.8 4.8-1 1-4.8-4.8 1-1zm6.8 6.8l4.8 4.8-1 1-4.8-4.8 1-1z" fill="currentColor"/><path d="M13.8 2.2l-4.8 4.8 1 1 4.8-4.8-1-1zm-6.8 6.8l-4.8 4.8 1 1 4.8-4.8-1-1z" fill="currentColor"/><path d="M2.5 2.5L13.5 13.5M13.5 2.5L2.5 13.5" stroke="#ffffff" stroke-width="0.75" opacity="0.4"/><circle cx="8" cy="8" r="2" fill="currentColor"/><circle cx="8" cy="8" r="0.9" fill="#ffffff"/></svg>',
    player: '<svg class="map-icon map-icon-player" viewBox="0 0 16 16" aria-hidden="true"><path d="M1 14.2h14v1.2H1v-1.2z" fill="currentColor" opacity="0.85"/><path d="M1.5 5.5h3v8.5h-3V5.5zm0-2h1v1.5h-1V3.5zm2 0h1v1.5h-1V3.5z" fill="currentColor"/><path d="M11.5 5.5h3v8.5h-3V5.5zm0-2h1v1.5h-1V3.5zm2 0h1v1.5h-1V3.5z" fill="currentColor"/><path d="M4.5 7h7v7h-7V7zm1-4.5h1.2v1.5H5.5V2.5zm2 0h1v1.5h-1V2.5zm1.8 0h1.2v1.5h-1.2V2.5z" fill="currentColor"/><path d="M6.5 14v-3.5c0-.8.7-1.5 1.5-1.5s1.5.7 1.5 1.5V14H6.5z" fill="#ffffff" fill-opacity="0.35"/><rect x="2.5" y="7" width="1" height="2" fill="#ffffff" fill-opacity="0.4"/><rect x="12.5" y="7" width="1" height="2" fill="#ffffff" fill-opacity="0.4"/></svg>',
    territory: '<svg class="map-icon map-icon-territory" viewBox="0 0 16 16" aria-hidden="true"><path d="M1.5 14h13v1.2h-13V14z" fill="currentColor" opacity="0.85"/><path d="M3 14c.5-1.2 2-2 5-2s4.5.8 5 2H3z" fill="currentColor" fill-opacity="0.4"/><path d="M3.8 2.5l.8-1.7.8 1.7-.8.5-.8-.5z" fill="currentColor"/><path d="M4.2 2.5h.8v11.5h-.8V2.5z" fill="currentColor"/><path d="M5 2.8h9.5l-2.8 3.5 2.8 3.5H5V2.8z" fill="currentColor"/><path d="M7.8 4.8l.6 1.2 1.3.2-1 .9.2 1.3-1.1-.6-1.1.6.2-1.3-1-.9 1.3-.2.5-1.2z" fill="#ffffff" fill-opacity="0.85"/></svg>',
    home: '<svg class="map-icon map-icon-home" viewBox="0 0 16 16" aria-hidden="true"><path d="M1 14h14v1.2H1V14z" fill="currentColor" opacity="0.85"/><path d="M2.5 7h11v7h-11V7z" fill="currentColor" fill-opacity="0.75"/><path d="M8 1.2L1.8 6.5h12.4L8 1.2z" fill="currentColor"/><path d="M8 7.5l.7 1.4 1.5.2-1.1 1 .3 1.5-1.4-.7-1.4.7.3-1.5-1.1-1 1.5-.2.7-1.4z" fill="#ffffff"/><path d="M6.8 14v-2c0-.7.5-1.2 1.2-1.2s1.2.5 1.2 1.2v2H6.8z" fill="#ffffff" fill-opacity="0.4"/></svg>'
  };

  /**
   * 渲染战术地图图标 HTML。
   * @param {string} key - 图标键名（city, view, wild, bandit, player, territory, home）
   * @param {string} [extraClass] - 额外类名
   * @returns {string} 图标 SVG 字符串
   */
  G.mapIconHtml = function (key, extraClass) {
    var raw = G.MapIcons && G.MapIcons[key];
    if (!raw) return '';
    if (!extraClass) return raw;
    return raw.replace('class="map-icon ', 'class="map-icon ' + escapeAttribute(extraClass) + ' ');
  };

  /**
   * 渲染战区要塞主城专属军武矢量图标。
   * 采用防御要塞、中央统帅大楼、通信警讯天线与金色将星徽标设计，替代生硬且非军武风的原生城堡 Emoji。
   * @param {string} [extraClass] - 额外类名
   * @returns {string} 主城 SVG 图标 HTML
   */
  G.mainCityIconHtml = function (extraClass) {
    var cls = 'main-city-icon' + (extraClass ? ' ' + escapeAttribute(extraClass) : '');
    return '<svg class="' + cls + '" viewBox="0 0 20 20" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true" focusable="false">' +
      '<path d="M1.5 18.5h17a.5.5 0 0 0 .5-.5v-1.2a.5.5 0 0 0-.5-.5h-17a.5.5 0 0 0-.5.5V18a.5.5 0 0 0 .5.5z" class="city-icon-ground"/>' +
      '<rect x="2.5" y="8" width="4.5" height="8.3" class="city-icon-bastion"/>' +
      '<path d="M2.5 6.4h1.1v1.6H2.5V6.4zm1.7 0h1.1v1.6H4.2V6.4zm1.7 0H7v1.6H5.9V6.4z" class="city-icon-roof"/>' +
      '<rect x="4.2" y="10.2" width="1.1" height="2.5" rx="0.3" class="city-icon-slit"/>' +
      '<rect x="13" y="8" width="4.5" height="8.3" class="city-icon-bastion"/>' +
      '<path d="M13 6.4h1.1v1.6H13V6.4zm1.7 0h1.1v1.6h-1.1V6.4zm1.7 0h1.1v1.6h-1.1V6.4z" class="city-icon-roof"/>' +
      '<rect x="14.7" y="10.2" width="1.1" height="2.5" rx="0.3" class="city-icon-slit"/>' +
      '<rect x="6.5" y="9.8" width="7" height="6.5" class="city-icon-wall"/>' +
      '<rect x="6.8" y="3.6" width="6.4" height="12.7" class="city-icon-keep"/>' +
      '<path d="M6.6 2.2h1.6v1.4H6.6V2.2zm2.4 0h2v1.4H9V2.2zm2.8 0h1.6v1.4h-1.6V2.2z" class="city-icon-roof"/>' +
      '<line x1="10" y1="0.6" x2="10" y2="2.2" stroke="currentColor" stroke-width="1.1" stroke-linecap="round" class="city-icon-antenna"/>' +
      '<circle cx="10" cy="0.6" r="0.75" class="city-icon-beacon"/>' +
      '<path d="M8.4 16.3v-2.8c0-.9.7-1.6 1.6-1.6s1.6.7 1.6 1.6v2.8H8.4z" class="city-icon-gate"/>' +
      '<polygon points="10,5.2 10.6,6.8 12.3,6.9 11.0,7.9 11.4,9.5 10,8.6 8.6,9.5 9.0,7.9 7.7,6.9 9.4,6.8" class="city-icon-star"/>' +
    '</svg>';
  };
  G.renderMainCityIcon = G.mainCityIconHtml;
})(window.Game);
