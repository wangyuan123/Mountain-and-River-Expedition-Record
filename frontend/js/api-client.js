/* global window, document */
window.Game = window.Game || {};

(function (G) {
  'use strict';

  // GET /api/game/state 缓存有效期（毫秒）
  var STATE_CACHE_TTL = 2000;
  // 网络错误自动重试次数
  var NETWORK_RETRY = 1;
  // 加载指示器延迟显示时间（毫秒）——避免快速请求闪烁
  var LOADING_DELAY = 500;

  /**
   * 统一请求处理器：负责鉴权头注入、401 跳登录、网络错误处理、
   * 加载指示器以及 GET /api/game/state 的可选缓存。
   */
  class ApiClient {
    constructor(baseURL) {
      // 本地开发：前端在 8081，后端在 8080，自动检测跨端口
      if (!baseURL) {
        var port = parseInt(window.location.port, 10);
        if (port && port !== 8080 && port !== 80) {
          baseURL = 'http://' + window.location.hostname + ':8080/api';
        } else {
          baseURL = '/api';
        }
      }
      this.baseURL = baseURL;
      this.tokenKey = 'wargame_token';
      this.userKey = 'wargame_user';
      this._loadingCount = 0;
      this._loadingTimer = null;
      this._stateCache = null; // { data, expireAt }
      // 可被外部覆盖的回调
      this.onUnauthorized = null;
      this.onNetworkError = null;
    }

    // ===== Token 管理 =====
    getToken() {
      return localStorage.getItem(this.tokenKey) || '';
    }

    setToken(token, username) {
      localStorage.setItem(this.tokenKey, token);
      if (username) localStorage.setItem(this.userKey, username);
    }

    clearToken() {
      localStorage.removeItem(this.tokenKey);
      localStorage.removeItem(this.userKey);
    }

    isLoggedIn() {
      return !!this.getToken();
    }

    getUsername() {
      return localStorage.getItem(this.userKey) || '';
    }

    // ===== 加载指示器 =====
    // 仅在请求超过 500ms 时才显示遮罩，避免快速请求闪烁
    showLoading() {
      this._loadingCount++;
      if (this._loadingCount === 1 && !this._loadingTimer) {
        var self = this;
        this._loadingTimer = setTimeout(function () {
          self._loadingTimer = null;
          var el = document.getElementById('loadingOverlay');
          if (el) el.style.display = 'flex';
        }, LOADING_DELAY);
      }
    }

    hideLoading() {
      this._loadingCount = Math.max(0, this._loadingCount - 1);
      if (this._loadingCount === 0) {
        if (this._loadingTimer) {
          clearTimeout(this._loadingTimer);
          this._loadingTimer = null;
        }
        var el = document.getElementById('loadingOverlay');
        if (el) el.style.display = 'none';
      }
    }

    // ===== 游戏状态缓存（仅 GET /api/game/state）=====
    invalidateStateCache() {
      this._stateCache = null;
    }

    getCachedState() {
      if (this._stateCache && this._stateCache.expireAt > Date.now()) {
        return this._stateCache.data;
      }
      this._stateCache = null;
      return null;
    }

    setStateCache(data) {
      this._stateCache = { data: data, expireAt: Date.now() + STATE_CACHE_TTL };
    }

    // ===== 401 处理：清 token，跳转登录页 =====
    handleUnauthorized() {
      this.clearToken();
      this.invalidateStateCache();
      if (typeof this.onUnauthorized === 'function') {
        this.onUnauthorized();
      } else if (G.Core) {
        G.Core.state = null;
        G.Core.route = 'login';
        if (G.Core.render) G.Core.render();
      }
    }

    // ===== 核心请求方法 =====
    // options: { silent: 不显示 loading, retry: 网络错误重试次数, noCache: 忽略 state 缓存 }
    request(method, path, body, options) {
      options = options || {};
      var retry = (typeof options.retry === 'number') ? options.retry : NETWORK_RETRY;
      var showLoad = !options.silent;
      var self = this;

      if (showLoad) self.showLoading();

      var promise = self._doFetch(method, path, body, retry);

      // 无论成功失败都关闭 loading
      return promise.then(function (data) {
        if (showLoad) self.hideLoading();
        return data;
      }, function (err) {
        if (showLoad) self.hideLoading();
        throw err;
      });
    }

    _doFetch(method, path, body, retryLeft) {
      var self = this;
      var url = this.baseURL + path;
      var headers = { 'Content-Type': 'application/json' };
      var token = this.getToken();
      if (token) headers['Authorization'] = 'Bearer ' + token;

      var opts = { method: method, headers: headers };
      if (body !== undefined && body !== null) opts.body = JSON.stringify(body);

      return fetch(url, opts).then(function (res) {
        // 始终尝试解析为 JSON（后端错误也是 JSON）
        return res.text().then(function (text) {
          var data = null;
          if (text) {
            try { data = JSON.parse(text); } catch (e) { data = null; }
          }
          if (res.status === 401) {
            self.handleUnauthorized();
            throw new Error((data && data.error) || '未登录或登录已过期');
          }
          if (!res.ok) {
            throw new Error((data && data.error) || ('请求失败 (' + res.status + ')'));
          }
          // 非 GET 请求可能改变了状态，作废缓存
          if (method !== 'GET') self.invalidateStateCache();
          return data;
        });
      }).catch(function (err) {
        // fetch 抛出的 TypeError 视为网络错误
        if (err instanceof TypeError) {
          if (retryLeft > 0) {
            // 自动重试一次
            return new Promise(function (resolve) {
              setTimeout(resolve, 400);
            }).then(function () {
              return self._doFetch(method, path, body, retryLeft - 1);
            });
          }
          if (typeof self.onNetworkError === 'function') {
            self.onNetworkError(err, method, path, body);
          }
          if (G.toast) G.toast('网络错误，请检查连接');
          throw new Error('网络错误，请检查网络连接');
        }
        throw err;
      });
    }

    // ===== 便捷方法 =====
  get(path, options) {
    return this.request('GET', path, null, options);
  }

  post(path, body, options) {
    return this.request('POST', path, body, options);
  }

  put(path, body, options) {
    return this.request('PUT', path, body, options);
  }

  delete(path, options) {
    return this.request('DELETE', path, null, options);
  }
}

  G.ApiClient = ApiClient;
})(window.Game);
