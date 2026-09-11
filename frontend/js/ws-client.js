/* global window */
window.Game = window.Game || {};

(function (G) {
  'use strict';

  var WS = {
    socket: null,
    connected: false,
    reconnectTimer: null,
    heartbeatTimer: null,
    listeners: {},  // type -> [callback]

    connect: function () {
      // Get token from API
      var token = G.API.getToken();
      if (!token) return;

      this._intentionalDisconnect = false;

      // Build WebSocket URL with token
      var protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
      var port = parseInt(location.port, 10);
      var wsHost = location.host;
      // 本地开发：前端在 8081，后端在 8080
      if (port && port !== 8080 && port !== 80) {
        wsHost = location.hostname + ':8080';
      }
      var wsUrl = protocol + '//' + wsHost + '/ws/game?token=' + token;

      this.socket = new WebSocket(wsUrl);

      this.socket.onopen = function () {
        WS.connected = true;
        console.log('WebSocket connected');
        WS.startHeartbeat();
        // Request fresh state on connect
        WS.emit('connected', {});
      };

      this.socket.onmessage = function (event) {
        WS.handleMessage(event.data);
      };

      this.socket.onclose = function () {
        var wasConnected = WS.connected;
        WS.connected = false;
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
    },

    scheduleReconnect: function () {
      if (this.reconnectTimer) return;
      this.reconnectTimer = setTimeout(function () {
        WS.reconnectTimer = null;
        WS.connect();
      }, 5000);  // reconnect after 5 seconds
    },

    startHeartbeat: function () {
      this.heartbeatTimer = setInterval(function () {
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
      try {
        var msg = JSON.parse(data);
      } catch (e) {
        console.error('Invalid WebSocket message:', data);
        return;
      }

      switch (msg.type) {
        case 'pong':
          // Heartbeat response, ignore
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
