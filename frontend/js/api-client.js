/* global window, document */
window.Game = window.Game || {};

(function (G) {
  'use strict';

  // GET /api/game/state 缓存有效期（毫秒）
  var STATE_CACHE_TTL = G.Constants.apiStateCacheTtl;
  // 网络错误自动重试次数
  var NETWORK_RETRY = G.Constants.apiNetworkRetry;
  // 加载指示器延迟显示时间（毫秒）——避免快速请求闪烁
  var LOADING_DELAY = G.Constants.apiLoadingDelay;

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
      this.cityId = null;
      this.cityRevision = 0;
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
      this.cityId = null; this.invalidateCityRequests();
      localStorage.setItem(this.tokenKey, token);
      if (username) localStorage.setItem(this.userKey, username);
    }

    clearToken() {
      if (G.Protection) G.Protection.reset();
      this.cityId = null; this.invalidateCityRequests();
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
    invalidateCityRequests() {
      this.cityRevision++;
      this.invalidateStateCache();
    }

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
      // 注销受理会先使并行请求失效，保留提交页面等待其响应或凭据状态查询。
      if (G.Account && G.Account.submitting) return;
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
      if (G.Protection && G.Protection.isGamePath(path) && !G.Protection.canRequest()) {
        return Promise.reject(G.Protection.error());
      }
      method = method.toUpperCase();
      // A lost response does not mean a write failed. Never replay a mutation.
      var safeToRetry = method === 'GET' || method === 'HEAD';
      var retry = safeToRetry ? ((typeof options.retry === 'number') ? options.retry : NETWORK_RETRY) : 0;
      if (!safeToRetry) this.invalidateStateCache();
      var showLoad = !options.silent;
      var self = this;

      if (showLoad) self.showLoading();

      if (G.Cities && G.Cities.switching && path !== '/game/cities/switch') {
        if (showLoad) self.hideLoading();
        return Promise.reject(new Error('正在切换城市，请稍候'));
      }
      var controller = options.timeout ? new AbortController() : null;
      var timeoutId = controller ? setTimeout(function () { controller.abort(); }, options.timeout) : null;
      var revision = self.cityRevision;
      var promise = self._doFetch(method, path, body, retry, { cityId: self.cityId, token: self.getToken(), revision: revision, preserveSession: options.preserveSession, playSession: G.Protection ? G.Protection.session() : '', signal: controller ? controller.signal : undefined }).then(function (data) {
        if (revision !== self.cityRevision) throw new Error('城市或账号已切换，已忽略旧页面响应');
        if (G.Protection && G.Protection.isGamePath(path) && !G.Protection.canRequest()) throw G.Protection.error();
        return data;
      });

      // 无论成功失败都关闭 loading
      return promise.then(function (data) {
        if (timeoutId) clearTimeout(timeoutId);
        if (showLoad) self.hideLoading();
        return data;
      }, function (err) {
        if (timeoutId) clearTimeout(timeoutId);
        if (showLoad) self.hideLoading();
        if (err.name === 'AbortError') {
          var timeout = new Error(safeToRetry ? '请求加载超时，请重试' : '请求超时，操作结果尚未确认');
          timeout.uncertain = !safeToRetry;
          throw timeout;
        }
        throw err;
      });
    }

    _doFetch(method, path, body, retryLeft, context) {
      var self = this;
      var url = this.baseURL + path;
      var headers = { 'Content-Type': 'application/json' };
      var token = context.token;
      if (token) headers['Authorization'] = 'Bearer ' + token;
      if (context.playSession) headers['X-Play-Session'] = context.playSession;
      if (context.cityId && path.indexOf('/game/') === 0) headers['X-City-Id'] = String(context.cityId);

      var opts = { method: method, headers: headers };
      if (context.signal) opts.signal = context.signal;
      if (body !== undefined && body !== null) opts.body = JSON.stringify(body);

      return fetch(url, opts).then(function (res) {
        // 始终尝试解析为 JSON（后端错误也是 JSON）
        return res.text().then(function (text) {
          if (context.revision !== self.cityRevision) throw new Error('城市或账号已切换，已忽略旧页面响应');
          var data = null;
          if (text) {
            try { data = JSON.parse(text); } catch (e) { data = null; }
          }
          if (res.status === 401) {
            if (!context.preserveSession) self.handleUnauthorized();
            var unauthorized = new Error((data && data.error) || '未登录或登录已过期');
            unauthorized.status = 401;
            throw unauthorized;
          }
          if (!res.ok) {
            var failure = new Error((data && data.error) || ('请求失败 (' + res.status + ')'));
            failure.code = data && data.code;
            failure.status = res.status;
            failure.retryAfter = data && data.retryAfter;
            failure.uncertain = res.status >= 500 && method !== 'GET';
            if (G.Protection && G.Protection.isGamePath(path) && G.Protection.isAccessError(failure.code)) G.Protection.denied(failure);
            throw failure;
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
              return self._doFetch(method, path, body, retryLeft - 1, context);
            });
          }
          if (typeof self.onNetworkError === 'function') {
            self.onNetworkError(err, method, path, body);
          }
          var message = (method === 'GET' || method === 'HEAD')
            ? '网络错误，请检查网络连接'
            : '连接中断，操作结果尚未确认，请刷新查看后再操作';
          if (G.toast) G.toast(message);
          var networkError = new Error(message);
          networkError.uncertain = method !== 'GET' && method !== 'HEAD';
          throw networkError;
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
