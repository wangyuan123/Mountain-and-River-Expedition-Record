/* global window */
window.Game = window.Game || {};

(function (G) {
  'use strict';

  var WS = {
    socket: null,
    connected: false,
    status: 'disconnected',
    lastPong: 0,
    reconnectTimer: null,
    heartbeatTimer: null,
    listeners: {},  // type -> [callback]

    statusHtml: function () {
      var label = { connected: '已连接', connecting: '连接中', reconnecting: '重连中', disconnected: '已断开' }[this.status];
      return '<span class="connection-status" data-connection="' + this.status + '"><span class="online-dot"></span><span class="online-text">' + label + '</span></span>';
    },

    setStatus: function (status) {
      this.status = status;
      var nodes = document.querySelectorAll('.connection-status');
      for (var i = 0; i < nodes.length; i++) nodes[i].outerHTML = this.statusHtml();
    },

    connect: function () {
      if (G.Protection && !G.Protection.canRequest()) return;
      // Get token from API
      var token = G.API.getToken();
      if (!token) { this.setStatus('disconnected'); return; }
      if (this.socket && this.socket.readyState < 2) return;
      this.setStatus(this.status === 'reconnecting' ? 'reconnecting' : 'connecting');

      this._intentionalDisconnect = false;

      // Build WebSocket URL with token
      var protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
      var port = parseInt(location.port, 10);
      var wsHost = location.host;
      // 本地开发：前端在 8081，后端在 8080
      if (port && port !== 8080 && port !== 80) {
        wsHost = location.hostname + ':8080';
      }
      var wsUrl = protocol + '//' + wsHost + '/ws/game?token=' + encodeURIComponent(token);
      if (G.Protection) wsUrl += '&playSession=' + encodeURIComponent(G.Protection.session());

      var socket = this.socket = new WebSocket(wsUrl);

      this.socket.onopen = function () {
        if (WS.socket !== socket) return;
        WS.connected = true;
        WS.lastPong = Date.now();
        WS.setStatus('connected');
        console.log('WebSocket connected');
        WS.startHeartbeat();
        // Request fresh state on connect
        WS.emit('connected', {});
      };

      this.socket.onmessage = function (event) {
        if (WS.socket === socket) WS.handleMessage(event.data);
      };

      this.socket.onclose = function (event) {
        if (WS.socket !== socket) return;
        if (event && event.code === 4003 && G.Protection) {
          WS.disconnect(); G.Protection.denied({ message: '游戏许可已结束，请查看开放时间。' }); G.Protection.refresh(); return;
        }
        if (event && event.code === 4001) {
          WS.disconnect();
          // 本标签正在提交注销时，先让 HTTP 结果或状态查询完成。
          if (G.Account && !G.Account.submitting) G.Account.endSession();
          return;
        }
        var wasConnected = WS.connected;
        WS.connected = false;
        WS.setStatus(WS._intentionalDisconnect ? 'disconnected' : 'reconnecting');
        console.log('WebSocket disconnected');
        WS.stopHeartbeat();
        // Only emit disconnected and schedule reconnect if we were previously connected
        // (avoids spamming on intentional disconnects)
        if (wasConnected) {
          WS.emit('disconnected', {});
        }
        if (!WS._intentionalDisconnect) {
          WS.scheduleReconnect();
        }
      };

      this.socket.onerror = function (error) {
        console.error('WebSocket error:', error);
      };
    },

    disconnect: function () {
      this._intentionalDisconnect = true;
      this.stopHeartbeat();
      if (this.reconnectTimer) {
        clearTimeout(this.reconnectTimer);
        this.reconnectTimer = null;
      }
      if (this.socket) {
        this.socket.close();
        this.socket = null;
      }
      this.connected = false;
      this.setStatus('disconnected');
    },

    scheduleReconnect: function () {
      if (this.reconnectTimer) return;
      this.reconnectTimer = setTimeout(function () {
        WS.reconnectTimer = null;
        WS.connect();
      }, 5000);  // reconnect after 5 seconds
    },

    startHeartbeat: function () {
      this.stopHeartbeat();
      this.heartbeatTimer = setInterval(function () {
        if (WS.connected && Date.now() - WS.lastPong >= 90000) {
          WS.disconnect();
          WS._intentionalDisconnect = false;
          WS.setStatus('reconnecting');
          WS.emit('disconnected', {});
          WS.scheduleReconnect();
          return;
        }
        if (WS.connected && WS.socket.readyState === WebSocket.OPEN) {
          WS.socket.send(JSON.stringify({ type: 'ping' }));
        }
      }, 30000);  // heartbeat every 30 seconds
    },

    stopHeartbeat: function () {
      if (this.heartbeatTimer) {
        clearInterval(this.heartbeatTimer);
        this.heartbeatTimer = null;
      }
    },

    handleMessage: function (data) {
      if (G.Protection && !G.Protection.canRequest()) return;
      if (data === 'pong') { this.lastPong = Date.now(); return; }
      try {
        var msg = JSON.parse(data);
      } catch (e) {
        console.error('Invalid WebSocket message:', data);
        return;
      }

      var current = (G.Core && G.Core.state) || G.state;
      if (msg.citySlot != null && ((G.Cities && G.Cities.switching) ||
          (current && current.player && msg.citySlot !== (current.player.citySlot || 0)))) return;
      switch (msg.type) {
        case 'pong':
          this.lastPong = Date.now();
          break;
        case 'tick':
          this.emit('tick', msg.data);
          break;
        case 'march':
          this.emit('march', msg.data);
          break;
        case 'battle':
          this.emit('battle', msg.data);
          break;
        case 'scoutReport':
          this.emit('scoutReport', msg.data);
          break;
        case 'incoming':
          this.emit('incoming', msg.data);
          break;
        case 'mail':
          this.emit('mail', msg.data);
          break;
        case 'chat':
          this.emit('chat', msg.data);
          break;
        default:
          console.log('Unknown WS message type:', msg.type);
      }
    },

    on: function (type, callback) {
      if (!this.listeners[type]) this.listeners[type] = [];
      this.listeners[type].push(callback);
    },

    off: function (type, callback) {
      if (!this.listeners[type]) return;
      var idx = this.listeners[type].indexOf(callback);
      if (idx >= 0) this.listeners[type].splice(idx, 1);
    },

    emit: function (type, data) {
      if (!this.listeners[type]) return;
      for (var i = 0; i < this.listeners[type].length; i++) {
        try {
          this.listeners[type][i](data);
        } catch (e) {
          console.error('WS listener error:', e);
        }
      }
    },

    send: function (message) {
      if (this.connected && this.socket.readyState === WebSocket.OPEN) {
        this.socket.send(JSON.stringify(message));
      }
    }
  };

  G.WS = WS;
})(window.Game);
