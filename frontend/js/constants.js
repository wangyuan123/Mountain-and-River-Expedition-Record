/* global window */
window.Game = window.Game || {};

(function (G) {
  'use strict';

  // 活动只跟随服务器已提交的时钟；失联时保留最后样本，不能按本机时间提前完成。
  var gameTime = null;
  var animationFrom = null, animationStartedAt = 0, animationDuration = 0, animationInterval = 1000;
  function animationClockNow() {
    return typeof performance !== 'undefined' && typeof performance.now === 'function' ? performance.now() : Date.now();
  }
  /** 仅用于地图运动：缓冲已确认样本并逐帧插值，绝不外推服务器尚未确认的进度。 */
  function animationNow() {
    if (gameTime == null) return Date.now();
    if (!animationDuration) return gameTime;
    var ratio = Math.max(0, Math.min(1, (animationClockNow() - animationStartedAt) / animationDuration));
    return animationFrom + (gameTime - animationFrom) * ratio;
  }
  G.GameTime = {
    /** 同步游戏时间，忽略并行请求中较早的响应。切换大区或账号需先 reset。 */
    sync: function (value) {
      if (value == null || value === '') return;
      var time = Number(value);
      if (!Number.isFinite(time) || time <= 0 || (gameTime != null && time <= gameTime)) return;
      var sampledAt = animationClockNow();
      if (gameTime != null) {
        var interval = sampledAt - animationStartedAt;
        // 保留有效检查点的最长到达间隔，避免 HTTP/WS 密集响应挤掉缓冲；长断线不计入采样。
        if (interval <= 5000) animationInterval = Math.max(animationInterval, Math.min(2000, interval));
      }
      // 相同检查点不能重新开始插值，否则频繁 HTTP/WS 响应会让地图一直停在起点。
      animationFrom = gameTime == null ? time : animationNow();
      gameTime = time;
      animationStartedAt = sampledAt;
      // 多留 25% 播放余量（1.25～2.5 秒），不再恰好一秒追平后停等下一次推送。
      animationDuration = time > animationFrom ? animationInterval * 1.25 : 0;
    },
    reset: function () { gameTime = animationFrom = null; animationStartedAt = animationDuration = 0; animationInterval = 1000; },
    now: function () { return gameTime == null ? Date.now() : gameTime; },
    animationNow: animationNow,
    /** 将活动截止点映射成当前预计真实完成时刻；停服后预计时刻随剩余时长顺延。 */
    toWallTime: function (deadline) { return Date.now() + Number(deadline) - this.now(); }
  };
  // 未连接服务器的本地试玩继续使用真实时间；联网后所有计时统一读取服务器样本。
  G.gameNow = function () { return G.GameTime.now(); };

  /**
   * 统一时长展示，省略前导零单位，保留最高有效单位之后的全部单位；正数不足一秒按一秒展示。
   * @param {number} seconds - 时长或倒计时剩余秒数
   * @returns {string} 如1天0小时0分钟5秒、2小时0分钟0秒、3分钟8秒或0秒
   */
  G.formatDuration = function (seconds) {
    var value = Number(seconds);
    var total = Number.isFinite(value) ? Math.max(0, Math.ceil(value)) : 0;
    var days = Math.floor(total / 86400);
    var hours = Math.floor(total % 86400 / 3600);
    var minutes = Math.floor(total % 3600 / 60);
    var remainingSeconds = total % 60;
    return (days > 0 ? days + '天' : '') +
      (days > 0 || hours > 0 ? hours + '小时' : '') +
      (days > 0 || hours > 0 || minutes > 0 ? minutes + '分钟' : '') + remainingSeconds + '秒';
  };
  G.formatGatherDuration = G.formatDuration;

  /** 累计时长取已完成的整秒，展示复用统一格式，仅省略前导零单位。 */
  G.formatGatherElapsed = function (seconds) {
    var value = Number(seconds);
    return G.formatDuration(Number.isFinite(value) ? Math.max(0, Math.floor(value)) : 0);
  };

  /**
   * 只累计实际采集阶段；采满负重或资源后封顶，缺少开始时间的旧记录不虚构时长。
   * @param {Object} task - 驻军或出征采集任务，含gatherStartAt与gatherEndAt
   * @param {number} now - 当前游戏时间（毫秒）
   * @returns {string} 累计采集文案；没有有效开始时间时返回空串
   */
  G.gatherElapsedText = function (task, now) {
    var start = Number(task.gatherStartAt), end = Number(task.gatherEndAt);
    if (!Number.isFinite(start) || start <= 0) return '';
    var finish = Number.isFinite(end) && end > 0 ? Math.min(now, end) : now;
    return '累计采集：' + G.formatGatherElapsed(Math.max(0, finish - start) / 1000);
  };

  /**
   * 分别汇总正在采集的估算量与已收获待回城的货物，剩余资源以服务端最新储量为准。
   * @param {Object} tile - 含总资源、累计开采量和驻军采集信息的野地
   * @param {number} now - 当前游戏时间（毫秒）
   * @param {Array} marches - 当前可见的本方行军
   * @returns {Object} 按野地总资源计算的数量、进度和负重状态
   */
  G.wildGatherProgress = function (tile, now, marches) {
    var total = Math.max(0, Number(tile.totalRes) || 0);
    var recorded = Math.max(0, Math.min(total, Number(tile.mined) || 0));
    var pool = Math.max(0, total - recorded);
    var tasks = [];
    var harvestedCount = tile.gatherHarvested != null ? 1 : 0;
    var harvested = Math.max(0, Number(tile.gatherHarvested) || 0);
    if (tile.gathering) tasks.push({
      startAt: tile.gatherStartAt, endAt: tile.gatherEndAt,
      amount: tile.gatherLoad
    });
    (marches || []).forEach(function (march) {
      if (march.targetKind !== 'wild_gather' || march.targetId == null || String(march.targetId) !== String(tile.id) || march.returning) return;
      if (march.gatherStopped) {
        // 已收获的货物已结算过野地消耗，不能再次从当前储量扣减；零收获也属于待回城任务。
        harvested += Math.max(0, Number(march.gatherAmount) || 0);
        harvestedCount++;
        return;
      }
      if (!march.gathering) return;
      tasks.push({
        startAt: march.gatherStartAt, endAt: march.gatherEndAt,
        amount: march.gatherAmount != null ? march.gatherAmount : march.gatherLoad
      });
    });
    var current = 0, latestEnd = 0, capacity = 0;
    // 服务端核定的采集量已受负重限制；时间比例封顶后数量与进度都不再增长。
    tasks.forEach(function (task) {
      var start = Number(task.startAt) || now, end = Number(task.endAt) || now;
      var amount = Math.max(0, Number(task.amount) || 0);
      // 先乘资源量再除时长，避免浮点比例把整秒应得的110误算成109。
      current += end > start ? Math.floor(amount * Math.max(0, Math.min(end - start, now - start)) / (end - start)) : amount;
      capacity += amount;
      latestEnd = Math.max(latestEnd, end);
    });
    var sessionGathered = Math.min(pool, current);
    var atCapacity = tasks.length > 0 && capacity > 0 && current >= capacity;
    var tip;
    if (tasks.length) {
      if (sessionGathered >= pool && pool > 0) tip = '已采满，请收获';
      else if (atCapacity) tip = '已达到负重上限，请收获';
      else tip = '采集中，剩余 ' + G.formatGatherDuration((latestEnd - now) / 1000);
    } else {
      tip = harvestedCount > 0 ? '已收获，等待回城命令' : pool <= 0 ? '资源已耗尽' : '尚未开始采集';
    }
    return {
      percent: pool > 0 ? Math.min(100, Math.round(sessionGathered / pool * 100)) : 0,
      mined: sessionGathered,
      total: pool,
      remaining: Math.max(0, pool - sessionGathered),
      current: sessionGathered,
      load: capacity,
      atCapacity: atCapacity,
      active: tasks.length > 0,
      activeCount: tasks.length,
      harvested: harvested,
      harvestedCount: harvestedCount,
      hasHarvested: harvestedCount > 0,
      tip: tip
    };
  };

  /** 已收获与正在采集分别展示，避免收获后显示零采量或把已结算货物再次计入野地剩余量。 */
  G.wildGatherAmountText = function (progress) {
    var activeText = '已采集：' + G.fmt(progress.mined) + ' / ' + G.fmt(progress.total);
    if (!progress.hasHarvested) return activeText;
    return '已收获：' + G.fmt(progress.harvested) + (progress.active ? ' · ' + activeText : '');
  };

  /**
   * 统一驻军与独立采集队的废弃限制；零收获也必须单独下达回城命令。
   * @param {Object} tile - 当前野地及驻军采集状态
   * @param {Array} marches - 本方各城市的可见行军
   * @returns {string} 当前阶段的阻止原因；空串表示可以确认废弃
   */
  G.wildAbandonReason = function (tile, marches) {
    var pending = (marches || []).filter(function (march) {
      return march.targetKind === 'wild_gather' && march.targetId != null &&
        String(march.targetId) === String(tile.id) && !march.returning;
    });
    if (tile.gathering || pending.some(function (march) { return march.gathering && !march.gatherStopped; })) {
      return '有采集任务尚未收获，请先收获并下达部队回城命令后再放弃领地';
    }
    if (tile.gatherHarvested != null || pending.some(function (march) { return march.gatherStopped; })) {
      return '有部队已收获并留守，请先下达部队回城命令后再放弃领地';
    }
    return pending.length ? '采集部队尚未召回，请先下达部队回城命令后再放弃领地' : '';
  };

  G.Constants = {
    siteInfo: {
      operator: '山河远征网络科技有限公司',
      icpNumber: '京ICP备00000000号-1',
      isPlaceholder: true
    },
    staticRoutes: {
      shop: 1, depot: 1, depotUse: 1, depotRename: 1,
      officer: 1, officerDetail: 1, academy: 1,
      tech: 1, prisoners: 1, settings: 1, battleDefaults: 1,
      reports: 1, reportDetail: 1, battle: 1, report: 1,
      mail: 1, chat: 1, recharge: 1, login: 1,
      guild: 1, rankings: 1, map: 1, wild: 1, dispatch: 1
    },
    // 单队行军令与服务端 WorldConfig 一致，去程和回程各可使用一次。
    marchAcceleration: { minRemainingMs: 30000 },
    // 每座城市独立计算领地名额，服务端 wilds.cap 返回基础5块加本城市政厅等级。
    wildOccupation: { baseCap: 5 },
    // 按钮音效是当前设备的听觉偏好，不随游戏账号或服务端存档变化。
    uiSound: {
      storageKey: 'wargame_ui_sound_v1',
      defaultVolume: 0.4,
      defaultSound: 'confirm',
      cooldownMs: 65,
      maxDelayMs: 250,
      maxVoices: 3,
      cues: {
        confirm: { label: '确认', layers: [
          { wave: 'metal', at: 0, duration: 0.07, from: 280, to: 210, gain: 0.26 },
          { wave: 'sine', at: 0.065, duration: 0.11, from: 720, to: 690, gain: 0.17 }
        ] },
        // 硬质零件碰撞的锐利起音，加少量非整数倍金属泛音与轻回弹，余响快速收住。
        mechanicalImpact: { label: '清脆机械碰撞', layers: [
          { wave: 'noise', at: 0, duration: 0.018, from: 5600, to: 2400, gain: 0.32 },
          { wave: 'metal', at: 0, duration: 0.13, from: 1480, to: 1460, gain: 0.24 },
          { wave: 'sine', at: 0, duration: 0.2, from: 920, to: 920, gain: 0.1 },
          { wave: 'sine', at: 0, duration: 0.065, from: 3650, to: 3650, gain: 0.075 },
          { wave: 'metal', at: 0.02, duration: 0.06, from: 2140, to: 2110, gain: 0.11 }
        ] }
      }
    },
    // 游戏情境音效（部队出征、拦截交火、战斗指挥战胜、建筑升级完成）
    // 严格遵循用户在设置中的音效开关与音量偏好，关闭时全静音。
    gameSound: {
      cues: {
        dispatch: {
          label: '部队出征',
          isEvent: true,
          file: 'audio/dispatch.wav',
          layers: [
            // 中国三军仪仗队正步四连踏 (116 BPM: 0.05s, 0.56s, 1.07s, 1.58s, 总长约 2.00s)
            { wave: 'noise', at: 0.05, duration: 0.09, from: 2600, to: 380, gain: 0.36, envelope: 'punch' },
            { wave: 'sine',  at: 0.05, duration: 0.12, from: 110,  to: 46,  gain: 0.38, envelope: 'punch' },
            { wave: 'metal', at: 0.06, duration: 0.07, from: 3800, to: 2900, gain: 0.16, envelope: 'decay' },
            { wave: 'noise', at: 0.56, duration: 0.09, from: 2550, to: 360, gain: 0.35, envelope: 'punch' },
            { wave: 'sine',  at: 0.56, duration: 0.12, from: 108,  to: 45,  gain: 0.38, envelope: 'punch' },
            { wave: 'metal', at: 0.57, duration: 0.07, from: 3750, to: 2850, gain: 0.15, envelope: 'decay' },
            { wave: 'noise', at: 1.07, duration: 0.09, from: 2620, to: 380, gain: 0.36, envelope: 'punch' },
            { wave: 'sine',  at: 1.07, duration: 0.12, from: 112,  to: 46,  gain: 0.39, envelope: 'punch' },
            { wave: 'metal', at: 1.08, duration: 0.07, from: 3820, to: 2920, gain: 0.16, envelope: 'decay' },
            { wave: 'noise', at: 1.58, duration: 0.11, from: 2650, to: 350, gain: 0.38, envelope: 'punch' },
            { wave: 'sine',  at: 1.58, duration: 0.15, from: 115,  to: 44,  gain: 0.40, envelope: 'punch' },
            { wave: 'metal', at: 1.59, duration: 0.09, from: 3850, to: 2800, gain: 0.17, envelope: 'decay' }
          ]
        },
        firefight: {
          label: '拦截战交火',
          isEvent: true,
          file: 'audio/firefight.wav',
          layers: [
            { wave: 'noise', at: 0.00, duration: 0.045, from: 6500, to: 1800, gain: 0.45, envelope: 'punch' },
            { wave: 'metal', at: 0.00, duration: 0.06,  from: 1850, to: 450,  gain: 0.35, envelope: 'decay' },
            { wave: 'sine',  at: 0.00, duration: 0.08,  from: 260,  to: 80,   gain: 0.40, envelope: 'punch' },
            { wave: 'noise', at: 0.09, duration: 0.045, from: 6500, to: 1800, gain: 0.45, envelope: 'punch' },
            { wave: 'metal', at: 0.09, duration: 0.06,  from: 1850, to: 450,  gain: 0.35, envelope: 'decay' },
            { wave: 'sine',  at: 0.09, duration: 0.08,  from: 260,  to: 80,   gain: 0.40, envelope: 'punch' },
            { wave: 'noise', at: 0.18, duration: 0.045, from: 6500, to: 1800, gain: 0.45, envelope: 'punch' },
            { wave: 'metal', at: 0.18, duration: 0.06,  from: 1850, to: 450,  gain: 0.35, envelope: 'decay' },
            { wave: 'sine',  at: 0.18, duration: 0.08,  from: 260,  to: 80,   gain: 0.40, envelope: 'punch' },
            { wave: 'sine',  at: 0.15, duration: 0.25,  from: 2200, to: 750,  gain: 0.18, envelope: 'sustain' },
            { wave: 'noise', at: 0.26, duration: 0.65,  from: 2200, to: 150,  gain: 0.45, envelope: 'explosion' },
            { wave: 'sine',  at: 0.26, duration: 0.55,  from: 130,  to: 32,   gain: 0.50, envelope: 'explosion' },
            { wave: 'metal', at: 0.28, duration: 0.20,  from: 980,  to: 350,  gain: 0.15, envelope: 'decay' }
          ]
        },
        victory: {
          label: '战斗指挥战胜',
          isEvent: true,
          file: 'audio/victory.wav',
          layers: [
            { wave: 'brass', at: 0.00, duration: 0.18, from: 523.25, to: 523.25, gain: 0.30, envelope: 'brass' },
            { wave: 'brass', at: 0.00, duration: 0.18, from: 659.25, to: 659.25, gain: 0.28, envelope: 'brass' },
            { wave: 'brass', at: 0.16, duration: 0.18, from: 659.25, to: 659.25, gain: 0.32, envelope: 'brass' },
            { wave: 'brass', at: 0.16, duration: 0.18, from: 783.99, to: 783.99, gain: 0.30, envelope: 'brass' },
            { wave: 'brass', at: 0.32, duration: 0.20, from: 783.99, to: 783.99, gain: 0.35, envelope: 'brass' },
            { wave: 'brass', at: 0.32, duration: 0.20, from: 987.77, to: 987.77, gain: 0.32, envelope: 'brass' },
            { wave: 'brass', at: 0.50, duration: 0.85, from: 261.63, to: 261.63, gain: 0.30, envelope: 'brass' },
            { wave: 'brass', at: 0.50, duration: 0.85, from: 523.25, to: 523.25, gain: 0.38, envelope: 'brass' },
            { wave: 'brass', at: 0.50, duration: 0.85, from: 659.25, to: 659.25, gain: 0.32, envelope: 'brass' },
            { wave: 'brass', at: 0.50, duration: 0.85, from: 1046.5, to: 1046.5, gain: 0.35, envelope: 'brass' },
            { wave: 'sine',  at: 0.50, duration: 0.60, from: 2093.0, to: 2093.0, gain: 0.18, envelope: 'chime' },
            { wave: 'sine',  at: 0.56, duration: 0.55, from: 2637.0, to: 2637.0, gain: 0.15, envelope: 'chime' },
            { wave: 'metal', at: 0.50, duration: 0.40, from: 1568.0, to: 1568.0, gain: 0.14, envelope: 'decay' },
            { wave: 'sine',  at: 0.50, duration: 0.45, from: 130,    to: 55,    gain: 0.35, envelope: 'punch' }
          ]
        },
        upgrade: {
          label: '建筑升级完成',
          isEvent: true,
          file: 'audio/upgrade.wav',
          layers: [
            { wave: 'noise', at: 0.00, duration: 0.025, from: 6800, to: 2400, gain: 0.35, envelope: 'punch' },
            { wave: 'metal', at: 0.00, duration: 0.16,  from: 1480, to: 1400, gain: 0.38, envelope: 'decay' },
            { wave: 'sine',  at: 0.00, duration: 0.10,  from: 320,  to: 180,  gain: 0.20, envelope: 'punch' },
            { wave: 'noise', at: 0.12, duration: 0.025, from: 7200, to: 2600, gain: 0.38, envelope: 'punch' },
            { wave: 'metal', at: 0.12, duration: 0.18,  from: 1760, to: 1680, gain: 0.42, envelope: 'decay' },
            { wave: 'sine',  at: 0.12, duration: 0.10,  from: 440,  to: 220,  gain: 0.22, envelope: 'punch' },
            { wave: 'sine',  at: 0.26, duration: 0.50,  from: 659.25, to: 659.25, gain: 0.30, envelope: 'chime' },
            { wave: 'metal', at: 0.26, duration: 0.30,  from: 1318.5, to: 1318.5, gain: 0.12, envelope: 'decay' },
            { wave: 'sine',  at: 0.38, duration: 0.55,  from: 830.61, to: 830.61, gain: 0.32, envelope: 'chime' },
            { wave: 'metal', at: 0.38, duration: 0.32,  from: 1661.2, to: 1661.2, gain: 0.14, envelope: 'decay' },
            { wave: 'sine',  at: 0.50, duration: 0.55,  from: 987.77, to: 987.77, gain: 0.34, envelope: 'chime' },
            { wave: 'metal', at: 0.50, duration: 0.35,  from: 1975.5, to: 1975.5, gain: 0.15, envelope: 'decay' },
            { wave: 'sine',  at: 0.62, duration: 0.50,  from: 1318.51, to: 1318.51, gain: 0.38, envelope: 'chime' },
            { wave: 'sine',  at: 0.62, duration: 0.45,  from: 2637.02, to: 2637.02, gain: 0.16, envelope: 'chime' },
            { wave: 'metal', at: 0.62, duration: 0.40,  from: 3955.5,  to: 3955.5,  gain: 0.10, envelope: 'decay' }
          ]
        },
        coins: {
          label: '领取道具/资源/附件',
          isEvent: true,
          file: 'audio/coins.wav',
          layers: [
            { wave: 'metal', at: 0.00, duration: 0.18, from: 3820, to: 3820, gain: 0.35, envelope: 'decay' },
            { wave: 'sine',  at: 0.00, duration: 0.22, from: 5840, to: 5840, gain: 0.25, envelope: 'chime' },
            { wave: 'metal', at: 0.08, duration: 0.16, from: 3100, to: 3100, gain: 0.30, envelope: 'decay' },
            { wave: 'sine',  at: 0.14, duration: 0.20, from: 4180, to: 4180, gain: 0.28, envelope: 'chime' },
            { wave: 'sine',  at: 0.22, duration: 0.15, from: 220,  to: 140,  gain: 0.20, envelope: 'punch' }
          ]
        },
        chest: {
          label: '打开各类宝箱',
          isEvent: true,
          file: 'audio/chest.wav',
          layers: [
            { wave: 'metal', at: 0.00, duration: 0.12, from: 1850, to: 950, gain: 0.40, envelope: 'punch' },
            { wave: 'noise', at: 0.00, duration: 0.03, from: 4500, to: 1200, gain: 0.30, envelope: 'punch' },
            { wave: 'metal', at: 0.09, duration: 0.10, from: 2200, to: 1100, gain: 0.35, envelope: 'decay' },
            { wave: 'sine',  at: 0.18, duration: 0.35, from: 180,  to: 85,   gain: 0.30, envelope: 'sustain' },
            { wave: 'noise', at: 0.20, duration: 0.30, from: 900,  to: 350,  gain: 0.25, envelope: 'decay' }
          ]
        },
        send_mail: {
          label: '发送邮件',
          isEvent: true,
          file: 'audio/send_mail.wav',
          layers: [
            { wave: 'noise', at: 0.00, duration: 0.35, from: 900,  to: 4500, gain: 0.42, envelope: 'sustain' },
            { wave: 'sine',  at: 0.08, duration: 0.25, from: 1800, to: 3600, gain: 0.20, envelope: 'sustain' },
            { wave: 'noise', at: 0.22, duration: 0.20, from: 4200, to: 1200, gain: 0.35, envelope: 'decay' }
          ]
        }
      }
    },
    // 游戏背景音乐（BGM）配置：循环慢节奏轻音乐
    // 默认启用玩家选定的 套系 1 · 《山河行思》，支持在设置中调节音量、开关及切换候选曲目
    bgm: {
      storageKey: 'wargame_bgm_v1',
      defaultEnabled: true,
      defaultVolume: 0.5,
      defaultTrack: 'bgm_strings',
      tracks: {
        bgm_strings: {
          id: 'bgm_strings',
          name: '套系 1 · 《山河行思》（巴赫·室内弦乐）',
          file: 'audio/bgm_strings.mp3',
          wav: 'audio/bgm_strings.wav',
          bpm: 58,
          loopDuration: 179.8,
          desc: 'J.S. 巴赫《G 弦上的咏叹调》，美国空军室内弦乐团实录，庄严从容、深谋远虑'
        },
        bgm_piano: {
          id: 'bgm_piano',
          name: '套系 2 · 《平原微光》（萨蒂·沉思钢琴）',
          file: 'audio/bgm_piano.mp3',
          wav: 'audio/bgm_piano.wav',
          bpm: 66,
          loopDuration: 180.1,
          desc: '埃里克·萨蒂《裸体歌舞第一号》，极简沉思木质钢琴，纯净降噪抗疲劳'
        },
        bgm_evening: {
          id: 'bgm_evening',
          name: '套系 3 · 《烽烟晚照》（肖邦·静谧夜色）',
          file: 'audio/bgm_evening.mp3',
          wav: 'audio/bgm_evening.wav',
          bpm: 54,
          loopDuration: 120.8,
          desc: '肖邦《E 小调前奏曲》，广板弱音踏板独奏，塞外残阳与历史沧桑感'
        }
      }
    },
    defaultTheme: 'blue-white-classic',
    themes: [
      { id: 'blue-white-classic', name: '战术经典蓝白风（推荐）', tag: '怀旧文字风', desc: '白底蓝字、渐变导航与浅蓝分隔线，重温早期手机家园的简洁排版' },
      { id: 'paper', name: '战术公文沙盘风', tag: '护眼米白', desc: '米白档案纸底 + 1px极细墨线 + 战备橄榄绿与印章红' },
      { id: 'dark', name: '战术夜航终端黑', tag: '极简冷黑', desc: '高纯度冷墨黑 + 雷达天青微光，夜间游玩省电护眼' }
    ],
    pvp: { minPrestige: 10000, heartbeatMs: 10000, queuePollMs: 3000, battlePollMs: 2000 },
    // 主线入口按稳定任务 ID 对应 QuestCatalog；改名与晋升直接定位现有操作入口。
    mainQuestDestinations: {
      q1_1: 'buildArmy', q1_2: 'buildRes', q1_3: 'buildRes', q1_4: 'army',
      q1_5: 'commanderName', q1_6: 'cityName', q1_7: 'officer', q1_8: 'forum', q1_9: 'rank',
      q2_1: 'buildRes', q2_2: 'buildRes', q2_3: 'wild', q2_4: 'campaign', q2_5: 'campaign',
      q2_6: 'tech', q2_7: 'army', q2_8: 'rank', q2_9: 'cities',
      q3_1: 'army', q3_2: 'army', q3_3: 'academy', q3_4: 'npc', q3_5: 'npc',
      q3_6: 'npc', q3_7: 'rank', q3_8: 'cities',
      q4_1: 'player', q4_2: 'player', q4_3: 'campaign', q4_4: 'campaign',
      q4_5: 'army', q4_6: 'npc', q4_7: 'buildArmy', q4_8: 'cities',
      q5_1: 'tech', q5_2: 'tech', q5_3: 'army', q5_4: 'army', q5_5: 'army', q5_6: 'rank', q5_7: 'cities',
      q6_1: 'army', q6_2: 'army', q6_3: 'npc', q6_4: 'tech', q6_5: 'buildArmy', q6_6: 'rank', q6_7: 'cities',
      q7_1: 'army', q7_2: 'army', q7_3: 'tech', q7_4: 'buildArmy', q7_5: 'buildArmy',
      q7_6: 'rank', q7_7: 'cities', q7_8: 'npc',
      q8_1: 'buildArmy', q8_2: 'tech', q8_3: 'army', q8_4: 'army', q8_5: 'cities', q8_6: 'npc', q8_7: 'rank'
    },
    navItems: [
      { key: '1', label: '后勤', route: 'buildRes' },
      { key: '2', label: '军工', route: 'buildArmy' },
      { key: '·', label: '军官', route: 'officer' },
      { key: '3', label: '军队', route: 'army' },
      { key: '4', label: '地图', route: 'world' },
      { key: '5', label: '警报', route: 'alerts' },
      { key: '6', label: '战报', route: 'reports' },
      { key: '·', label: '聊天', route: 'chat' },
      { key: '7', label: '邮件', route: 'mail' },
      { key: '8', label: '任务', route: 'mainQuest' },
      { key: '9', label: '军团', route: 'guild' },
      { key: '0', label: '仓库', route: 'depot' },
      { key: '·', label: '科技', route: 'tech' },
      { key: '·', label: '军校', route: 'academy' },
      { key: '·', label: '对战', route: 'pvp' },
      { key: '·', label: '战术', route: 'battleDefaults' },
      { key: '·', label: '排名', route: 'rankings' },
      { key: '·', label: '商城', route: 'shop' }
    ],
    rankingBoards: {
      players: { label: '玩家排名', metrics: [
        { id: 'prestige', label: '声望榜' },
        { id: 'militaryRank', label: '军衔榜' },
        { id: 'formationPower', label: '编队战力榜' },
        { id: 'overallPower', label: '综合实力榜' }
      ] },
      guilds: { label: '军团排名', metrics: [
        { id: 'prestige', label: '总声望榜' },
        { id: 'members', label: '人数榜' }
      ] }
    },
    footerHints: {
      // TODO：恢复防沉迷后改回“实名注册 · 健康游戏”。
      login: '登录账号 · 开启远征',
      protection: '账号服务在休息期间仍可办理',
      home: '',
      buildRes: '[1-9]升级 [0]返回',
      buildArmy: '[1-9]升级 [0]返回',
      fort: '修筑/拆除城防 [0]返回',
      army: '点击征召/解散 [0]返回',
      officer: '点击招募/任命/查看详情 [0]返回',
      officerDetail: '查看军官详情 [0]返回',
      academy: '点击招募/刷新名单 [0]返回',
      tech: '[1-6]研究 [0]返回',
      map: '点击挑战 [0]返回',
      wild: '点击占领/废弃 [0]返回',
      world: '拖动浏览 · 双指缩放 · 点击目标查看详情',
      dispatch: '选配兵力/军官/辎重 [0]返回',
      alerts: '查看军情 [0]返回',
      reports: '点击展开 [0]返回',
      reportDetail: '返回战报列表/主菜单',
      battle: '战斗中配置命令 · 结束后查看战果',
      report: '[1]再战 [0]返回地图',
      depot: '查看和使用道具 [0]返回',
      depotUse: '选择军官使用道具 [0]返回',
      settings: '游戏设置与账号管理 [0]返回'
    },
    footerNavItems: [
      { route: 'home', label: '首页', icon: '⌂' },
      { route: 'world', label: '地图', icon: '◎' },
      { route: 'mainQuest', label: '任务', icon: '⚑' },
      { route: 'mail', label: '邮件', icon: '✉' },
      { route: 'settings', label: '设置', icon: '⚙' }
    ],
    armyUnitOrder: [
      'infantry', 'truck', 'armored', 'recon_car', 'ltank', 'htank', 'assault', 'rocket',
      'scout', 'special', 'fighter', 'bomber', 'transport',
      'destroyer', 'sub', 'battleship', 'carrier'
    ],
    presetAvatars: [
      { id: 'rank-private', name: '列兵', src: 'img/avatars/historical/rank-private-v1.webp' },
      { id: 'rank-corporal', name: '下士', src: 'img/avatars/historical/rank-corporal-v1.webp' },
      { id: 'rank-sergeant', name: '中士', src: 'img/avatars/historical/rank-sergeant-v1.webp' },
      { id: 'rank-lieutenant', name: '中尉', src: 'img/avatars/historical/rank-lieutenant-v1.webp' },
      { id: 'rank-captain', name: '上尉', src: 'img/avatars/historical/rank-captain-v1.webp' },
      { id: 'rank-major', name: '少校', src: 'img/avatars/historical/rank-major-v1.webp' },
      { id: 'rank-colonel', name: '上校', src: 'img/avatars/historical/rank-colonel-v1.webp' },
      { id: 'rank-general', name: '将军', src: 'img/avatars/historical/rank-general-v1.webp' }
    ],
    depotCategories: { equipment: '军官装备', unitEquipment: '兵种装备', officer: '军官道具', resource: '资源道具', util: '功能道具' },
    depotCategoryOrder: ['equipment', 'unitEquipment', 'officer', 'resource', 'util'],
    commandBonusRate: 0.001,
    officerRoles: { mayor: '市长', commander: '指挥官', commander_in_chief: '司令官', march: '行军中', idle: '闲置' },
    officerAttributes: { logistics: '后勤', military: '军事', defense: '防御', knowledge: '学识' },
    resourceNames: { food: '粮食', steel: '钢铁', oil: '石油', rare: '稀矿', gold: '黄金' },
    connectionStatusNames: { connected: '已连接', connecting: '连接中', reconnecting: '重连中', disconnected: '已断开' },
    battleReportTitles: { conquer: '讨伐报告', plunder: '讨伐报告', feint: '佯攻报告', harass: '袭扰报告', guerrilla: '游击报告', scout: '侦查报告' },
    battleSkillBonusLabels: {
      '全军冲锋': '全军攻击力 +',
      '坚守阵地': '全军防御力 +',
      '闪电突击': '战场移动速度 +',
      '火力压制': '敌方自身攻击力 -',
      '破甲打击': '无视敌方有效防御 ',
      '三军统帅': '带兵上限 +',
      '战地急救': '伤兵额外回收 +',
      '屯田增产': '基础资源产出 +',
      '工程营造': '建筑工期 -',
      '精明理财': '黄金税收产出 +',
      '格物致知': '科研速度 +',
      '军屯自给': '全城养兵耗粮 -',
      '绝境反击': '反击伤害为剩余兵力总伤害的 ',
      '师夷长技': '借用同名敌军攻击的 ',
      '借甲御敌': '借用同名敌军防御的 '
    },
    battleResourceNames: { food: '粮', steel: '钢', oil: '油', rare: '稀矿', gold: '金', diamond: '钻' },
    // 全部陆军共享燃烧引擎，步兵系的其他战斗属性仍按 inf 分类。
    unitSpeedTechKeys: { truck: 'arm_engine' },
    unitTechKeys: { inf: 'arm_engine', arm: 'arm_engine', air: 'air_engine', nav: 'nav_engine' },
    resourceKeys: ['food', 'steel', 'oil', 'rare'],
    resourceKeysWithGold: ['food', 'steel', 'oil', 'rare', 'gold'],
    terrainPalette: { soil: [83, 73, 61], grass: [157, 169, 126] },
    mapOwnershipStyles: {
      own: { fill: 0x163f58, edge: 0x76ccea, ink: 0xf1fbff },
      other: { fill: 0x55391e, edge: 0xe7b76d, ink: 0xfff1d9 },
      neutral: { fill: 0x343b36, edge: 0xaeb8ad, ink: 0xf1f3ea }
    },
    techBranches: ['军事', '机动', '后勤', '侦察'],
    // 与服务端 TechnologyRules 一致：中心只控制准入，学识按每点连续计入速度。
    // 按目标等级查表，舰船动力末级对齐终局项目28天，与服务端和设计文档一致。
    researchDuration: {"normal":[180,600,1800,10800,43200,85320,168420,332460,656400,1296000],"resource":[120,480,1500,5400,21600,46860,101640,220380,478020,1036800],"arm_engine":[300,900,2700,10800,43200,90360,188940,395100,826260,1728000],"nav_engine":[300,900,2700,10800,43200,96660,216180,483480,1081500,2419200],"air_engine":[300,900,2700,10800,43200,96660,216180,483480,1081500,2419200]},
    researchSpeed: { knowledgePerPoint: 0.001 },
    // 仅列实际生效的百分比科技；情报、配方、俘获比例及未生效科技由科技页单独说明。
    techEffectPercent: { wall_stats: 5, atk_all: 5, def_all: 5, range_all: 5, hp_all: 5, firepower_all: 5, spd_arm: 5, spd_air: 5, spd_nav: 5, res_food: 5, res_steel: 5, res_oil: 5, res_rare: 5, load: 5, cap: 5, food_save: -5, train: 5, build: -5, medical: 5 },
    worldTargetKeys: { wild: 'wildTiles', player: 'playerCities', simulated_npc: 'simulatedNpcCities', bandit: 'bandits' },
    battleRoundLimits: { plunder: 25, conquer: 35, feint: 10, harass: 15, guerrilla: 5 },
    campaignModes: [
      { action: 'conquer', name: '征服' },
      { action: 'plunder', name: '掠夺' },
      { action: 'feint', name: '佯攻', description: '以短促进攻骚扰、打击敌军，作战方式类似掠夺；即使获胜也不获得资源、不占领目标。最多战斗 10 回合，适合不喜欢冗长回合作战的玩家；到达上限仍未获胜时自动撤退。' },
      { action: 'harass', name: '袭扰', description: '以持续袭击骚扰、打击敌军，作战方式类似掠夺；即使获胜也不获得资源、不占领目标。最多战斗 15 回合，适合不喜欢冗长回合作战的玩家；到达上限仍未获胜时自动撤退。' },
      { action: 'guerrilla', name: '游击', description: '快速袭击敌军，以骚扰打击为目的，作战方式类似掠夺；即使获胜也不获得资源、不占领目标。最多战斗 5 回合，到达上限仍未获胜时自动撤退，适合速战速撤。' }
    ],
    dispatchActionNames: { campaign: '讨伐', conquer: '征服', plunder: '掠夺', feint: '佯攻', harass: '袭扰', guerrilla: '游击', scout: '侦查', gather: '采集', station: '派遣进驻', intercept_scout: '出城拦截' },
    cityStateNames: { peace: '和平', war: '战争', shield: '护盾' },
    shieldPricePerCity: 200,
    battlefields: [
      { id: 'northwest', name: '西北战场', column: 0, row: 0 },
      { id: 'northeast', name: '东北战场', column: 1, row: 0 },
      { id: 'southwest', name: '西南战场', column: 0, row: 1 },
      { id: 'southeast', name: '东南战场', column: 1, row: 1 }
    ],
    mapRadiusOptions: [
      { v: 3, label: '范围：3格' }, { v: 5, label: '范围：5格' },
      { v: 8, label: '范围：8格' }, { v: 10, label: '范围：10格' },
      { v: 0, label: '范围：全图' }
    ],
    speedUpOrder: ['speedUp10m', 'speedUp1h', 'speedUp5h', 'speedUp12h', 'speedUp24h', 'speedUp36h', 'speedUp48h', 'speedUp72h'],
    landscapeNavStorageKey: 'wargame_landscape_nav_collapsed',
    apiStateCacheTtl: 2000,
    apiNetworkRetry: 1,
    apiLoadingDelay: 500,
    mailHistoryMax: 20,
    chatMax: 80,
    chatCooldownSec: 5,
    chatMinPrestige: 10000,
    chatTimeGapMs: 5 * 60 * 1000,
    homeModuleIds: ['officers', 'army', 'resources', 'chat'],
    homeModuleNames: { officers: '军官将领', army: '军队总览', resources: '后勤', chat: '世界聊天' },
    protectionAccessErrorCodes: [
      'REAL_NAME_REQUIRED', 'IDENTITY_UNAVAILABLE', 'GUARDIAN_CONSENT_REQUIRED', 'GUARDIAN_RESTRICTED',
      'CALENDAR_UNAVAILABLE', 'PLAY_WINDOW_CLOSED', 'PLAY_TIME_EXHAUSTED', 'PLAY_SESSION_EXPIRED'
    ],
    identityStatusNames: { VERIFIED: '已核验', EXPIRED: '需要重新核验', UNVERIFIED: '未核验' },
    buildMaxConcurrent: 3,
    saveAttrMax: 219,
    officerMaxLevel: 100,
    // 当前星级 1～4 升到下一星的失败率；失败仍消耗一枚星耀符。
    starUpFailureRates: [10, 20, 30, 60],
    shopCategories: [
      { id: 'all',     name: '全部' },
      { id: 'officer', name: '军官' },
      { id: 'resource',name: '资源' },
      { id: 'util',    name: '功能' },
      { id: 'gift',    name: '礼包' }
    ],
    shopItems: [

      // —— 军官道具 ——
      { id: 'expBook',    cat: 'officer',  name: '经验书',     icon: 'img/items/exp-book.svg', desc: '军官使用,获得10000经验',          price: 30,   stock: null, tag: '热销' },
      { id: 'expBookAdv', cat: 'officer',  name: '高级经验书', icon: 'img/items/exp-book-adv.svg', desc: '每本提供100000经验，可批量使用', price: 150,  stock: null, tag: '推荐' },
      { id: 'expBookMax', cat: 'officer',  name: '满级经验书', icon: 'img/items/exp-book-max.svg', desc: '使用1本即可将1～99级军官直接升至满级(Lv.100)，补足升级属性点', price: 1000, stock: null, tag: '极品' },
      { id: 'skillBook',  cat: 'officer',  name: '普通技能书', icon: 'img/items/skill-book.svg', desc: '选择军官使用，随机学习一个未掌握且不互斥的技能，初始等级受军官星级影响', price: 80, stock: null, tag: '随机' },
      { id: 'universalSkillBook', cat: 'officer', name: '通用技能书', icon: 'img/items/universal-skill-book.svg', desc: '从技能池自选一个未掌握且不互斥的技能，学习后为 Lv.1；仅用于学习，不用于升级', price: 200, stock: null, tag: '自选' },
      { id: 'loyaltyBox', cat: 'officer',  name: '忠诚宝箱',   icon: 'img/items/loyalty-box.svg', desc: '军官忠诚度+20,提升留任意愿',     price: 50,   stock: null, tag: '' },
      { id: 'renameCard', cat: 'officer',  name: '军官改名卡', icon: 'img/items/rename-card.svg', desc: '为军官更换新名字',               price: 60,   stock: null, tag: '' },
      { id: 'recruitOrd', cat: 'officer',  name: '征募令',     icon: 'img/items/recruit-ord.svg', desc: '刷新军校,保底出现一名五星军官',   price: 500,  stock: null, tag: '稀有' },
      { id: 'starUp',     cat: 'officer',  name: '星耀符',     icon: 'img/items/star-up.svg', desc: '升2/3/4/5星失败率10/20/30/60%，失败也消耗1枚', price: 300, stock: null, tag: '' },

      // —— 资源道具 ——
      { id: 'goldBox',    cat: 'resource', name: '黄金箱',     icon: 'img/resources/models/gold.webp', desc: '开启获得5万黄金',        price: 80,   stock: null, tag: '' },
      { id: 'resBox',     cat: 'resource', name: '资源箱',     icon: 'img/items/res-box.svg', desc: '开启获得粮钢油稀各5万',        price: 80,  stock: null, tag: '热销' },
      { id: 'foodPack', cat: 'resource', name: '粮食大礼包', icon: 'img/resources/models/food.webp', desc: '开启获得60万粮食', price: 200, stock: null, tag: '' },
      { id: 'steelPack',  cat: 'resource', name: '钢铁大礼包', icon: 'img/resources/models/steel.webp', desc: '开启获得60万钢铁',            price: 200,  stock: null, tag: '' },
      { id: 'oilPack', cat: 'resource', name: '石油大礼包', icon: 'img/resources/models/oil.webp', desc: '开启获得60万石油', price: 200, stock: null, tag: '' },
      { id: 'rarePack', cat: 'resource', name: '稀矿大礼包', icon: 'img/resources/models/rare.webp', desc: '开启获得60万稀矿', price: 200, stock: null, tag: '' },
      { id: 'supplyPack', cat: 'resource', name: '战备补给包', icon: 'img/resources/models/food.webp', desc: '开启获得粮钢油稀各40万',  price: 500,  stock: null, tag: '超值' },
      { id: 'resourcePack100w', cat: 'resource', name: '资源大礼包（100万）', icon: 'img/items/res-box.svg', desc: '粮食/钢铁/石油/稀矿各100万', price: 1150, stock: null, tag: '' },
      { id: 'resourcePack200w', cat: 'resource', name: '资源大礼包（200万）', icon: 'img/items/res-box.svg', desc: '粮食/钢铁/石油/稀矿各200万', price: 2200, stock: null, tag: '' },
      { id: 'resourcePack350w', cat: 'resource', name: '资源大礼包（350万）', icon: 'img/items/res-box.svg', desc: '粮食/钢铁/石油/稀矿各350万', price: 3700, stock: null, tag: '' },
      { id: 'resourcePack500w', cat: 'resource', name: '资源大礼包', icon: 'img/items/resource-pack-500w.svg', desc: '粮食/钢铁/石油/稀矿各500万', price: 5000, stock: null, tag: '豪华' },

      // —— 功能道具 - 加速符（建筑施工 / 军队生产通用）——
      { id: 'speedUp10m', cat: 'util', name: '10分加速符',icon: 'img/items/speedup-10m.svg', desc: '立即缩短10分钟建筑/造兵时间',     price: 30,   stock: null, tag: '' },
      { id: 'speedUp1h',  cat: 'util', name: '1时加速符', icon: 'img/items/speedup-1h.svg', desc: '立即缩短1小时建筑/造兵时间',     price: 100,  stock: null, tag: '热销' },
      { id: 'speedUp5h',  cat: 'util', name: '5时加速符', icon: 'img/items/speedup-5h.svg', desc: '立即缩短5小时建筑/造兵时间',     price: 400,  stock: null, tag: '' },
      { id: 'speedUp12h', cat: 'util', name: '12时加速符',icon: 'img/items/speedup-12h.svg', desc: '立即缩短12小时建筑/造兵时间',    price: 800,  stock: null, tag: '' },
      { id: 'speedUp24h', cat: 'util', name: '24时加速符',icon: 'img/items/speedup-24h.svg', desc: '立即缩短24小时建筑/造兵时间',    price: 1500, stock: null, tag: '推荐' },
      { id: 'speedUp36h', cat: 'util', name: '36时加速符',icon: 'img/items/speedup-36h.svg', desc: '立即缩短36小时建筑/造兵时间',    price: 2000, stock: null, tag: '' },
      { id: 'speedUp48h', cat: 'util', name: '48时加速符',icon: 'img/items/speedup-48h.svg', desc: '立即缩短48小时建筑/造兵时间',    price: 2500, stock: null, tag: '超值' },
      { id: 'speedUp72h', cat: 'util', name: '72时加速符',icon: 'img/items/speedup-72h.svg', desc: '立即缩短72小时建筑/造兵时间',    price: 3500, stock: null, tag: '限时' },
      { id: 'shield2h', cat: 'util', name: '2小时护盾', icon: 'img/items/shield-2h.svg', desc: '本城2小时免受侦查、攻击和宣战，可叠加；期间不能对其他玩家发起军事操作，不保护野地', price: 60, stock: null, tag: '短休' },
      { id: 'shield', cat: 'util', name: '8小时护盾', icon: 'img/items/shield-8h.svg', desc: '本城8小时免受侦查、攻击和宣战，可叠加；期间不能对其他玩家发起军事操作，不保护野地', price: 200, stock: null, tag: '过夜' },
      { id: 'shield12h', cat: 'util', name: '12小时护盾', icon: 'img/items/shield-12h.svg', desc: '本城12小时免受侦查、攻击和宣战，可叠加；期间不能对其他玩家发起军事操作，不保护野地', price: 280, stock: null, tag: '半日' },
      { id: 'shield24h', cat: 'util', name: '24小时护盾', icon: 'img/items/shield-24h.svg', desc: '本城24小时免受侦查、攻击和宣战，可叠加；期间不能对其他玩家发起军事操作，不保护野地', price: 500, stock: null, tag: '全天' },
      { id: 'shield72h', cat: 'util', name: '72小时护盾', icon: 'img/items/shield-72h.svg', desc: '本城72小时免受侦查、攻击和宣战，可叠加；期间不能对其他玩家发起军事操作，不保护野地', price: 1350, stock: null, tag: '长假' },
      { id: 'marchOrd',  cat: 'util', name: '行军令', icon: 'img/items/march-ord.svg', desc: '仓库启用：行军速度+50%，持续1小时；情报使用：基础缩短剩余时间50%，指挥官学识提高效果，最高80%，至少保留30秒', price: 100, stock: null, tag: '' },
      { id: 'signalJammingOrder', cat: 'util', name: '通讯干扰令', icon: 'img/items/signal-jamming-order.svg', desc: '对来袭敌军使用：基础延长剩余时间50%，指挥官学识提高效果，最高70%', price: 150, stock: null, tag: '' },
      { id: 'feintOrder', cat: 'util', name: '佯动诱敌令', icon: 'img/items/feint-order.svg', desc: '对从本城撤退的敌军使用：基础延长剩余时间50%，指挥官学识提高效果，最高70%', price: 250, stock: null, tag: '' },
      { id: 'warSpeedOrder', cat: 'util', name: '宣战加速令', icon: 'img/items/march-ord.svg', desc: '缩短本城发起的宣战备战时间：基础50%，学识提高效果，最高80%，总备战至少30分钟', price: 250, stock: null, tag: '' },
      { id: 'populationOrder', cat: 'util', name: '人口动员令', icon: 'img/items/population-order.svg', desc: '使用后立即增加500空闲人口,不超过人口上限', price: 100, stock: null, tag: '推荐' },
      { id: 'emergencyPopulationOrder', cat: 'util', name: '紧急人口动员令', icon: 'img/items/emergency-population-order.svg', desc: '使用后立即增加当前城市人口容量上限30%的空闲人口,不超过人口上限', price: 100, stock: null, tag: '' },
      { id: 'cityRenameCard', cat: 'util', name: '城市改名卡', icon: 'img/items/city-rename-card.svg', desc: '为一座城市更换新名字', price: 60, stock: null, tag: '' },
      { id: 'teleportNormal', cat: 'util', name: '普通传送', icon: 'img/items/teleport-normal.svg', desc: '消耗1个，将当前城市随机传送至开放地图的四格空闲陆地', price: 100, stock: null, tag: '' },
      { id: 'teleportAdvanced', cat: 'util', name: '高级传送', icon: 'img/items/teleport-advanced.svg', desc: '消耗1个，将当前城市传送至指定坐标，目的地须有完整2×2空闲陆地', price: 500, stock: null, tag: '' },

      // —— 礼包 ——
      { id: 'newbiePack', cat: 'gift', name: '新手礼包',   icon: 'img/items/newbie-pack.svg', desc: '开启获得：粮食×20000、钢铁×20000、石油×10000、稀矿×5000、黄金×3000', price: 99, stock: 1, tag: '新手' }
    ],
    rechargeCategories: [
      { id: 'diamond', name: '钻石充值' },
      { id: 'marshal', name: '元帅礼包' }
    ],
    rechargePackages: [
      { id: 'p6', cat: 'diamond', name: '试玩补给', icon: '💎', rmb: 6, diamond: 60, desc: '适合首次体验充值' },
      { id: 'p30', cat: 'diamond', name: '少将补给', icon: '💎', rmb: 30, diamond: 330, desc: '额外赠送30钻石', bonus: '赠30' },
      { id: 'p98', cat: 'diamond', name: '中将补给', icon: '💎', rmb: 98, diamond: 1080, desc: '额外赠送100钻石', bonus: '赠100' },
      { id: 'p198', cat: 'diamond', name: '上将补给', icon: '💎', rmb: 198, diamond: 2230, desc: '额外赠送250钻石', bonus: '赠250' },
      { id: 'p328', cat: 'diamond', name: '大将补给', icon: '💎', rmb: 328, diamond: 3780, desc: '额外赠送500钻石', bonus: '赠500' },
      { id: 'p648', cat: 'diamond', name: '统帅补给', icon: '💎', rmb: 648, diamond: 7680, desc: '额外赠送1200钻石', bonus: '赠1200' },
      { id: 'p1280', cat: 'marshal', name: '元帅礼包', icon: '🎖️', rmb: 1280, diamond: 15800, desc: '钻石、资源与稀有道具组合礼包', bonus: '豪华' },
      { id: 'p3000', cat: 'marshal', name: '战区副令礼包', icon: '🎖️', rmb: 3000, diamond: 38000, desc: '战区特勤高阶装备与海量战略储备', bonus: '赠8000' },
      { id: 'p6000', cat: 'marshal', name: '战区总长礼包', icon: '🎖️', rmb: 6000, diamond: 78000, desc: '特勤统帅最高规制战略资源配给', bonus: '赠18000' },
      { id: 'p10000', cat: 'marshal', name: '五星统帅礼包', icon: '🎖️', rmb: 10000, diamond: 135000, desc: '最高军事委员会五星将官终极军需配给', bonus: '尊荣' }
    ]
  };
  /** 统计中心已开放及下级新增的科技种类；配套设施仍由前置服务校验，不代表可立即开工。 */
  G.researchAccess = function (labLevel) {
    var level = Math.max(0, Math.min(10, labLevel || 0));
    var techs = (G.DATA && G.DATA.techs) || {};
    var unlocked = Object.keys(techs).filter(function (id) { return techs[id].labReq <= level; });
    var newlyUnlocked = Object.keys(techs).filter(function (id) { return level < 10 && techs[id].labReq === level + 1; });
    return { level: level, unlocked: unlocked, newlyUnlocked: newlyUnlocked };
  };
  // OFFICER_EQUIPMENT_CATALOG_START
  var C = G.Constants;
  C.officerEquipmentConfig = {"branches":[{"key":"military","name":"军事"},{"key":"defense","name":"防御"},{"key":"logistics","name":"后勤"},{"key":"knowledge","name":"学识"}],"slots":[{"key":"weapon","name":"武器","group":"core","budget":"core"},{"key":"badge","name":"徽章","group":"core","budget":"core"},{"key":"coat","name":"外套","group":"core","budget":"core"},{"key":"helmet","name":"头盔","group":"clothing","budget":"major"},{"key":"gloves","name":"手套","group":"clothing","budget":"minor"},{"key":"belt","name":"腰带","group":"clothing","budget":"minor"},{"key":"trousers","name":"裤装","group":"clothing","budget":"medium"},{"key":"boots","name":"军靴","group":"clothing","budget":"major"},{"key":"backpack","name":"背包","group":"kit","budget":"minor"},{"key":"radio","name":"通讯器","group":"kit","budget":"major"},{"key":"map","name":"图囊","group":"kit","budget":"medium"},{"key":"watch","name":"军表","group":"kit","budget":"medium"}],"tiers":[{"key":"recruit","name":"列兵","persisted_tier_id":1,"progression_order":1,"required_level":1,"box_diamonds":200,"base_main":{"core":5,"major":1,"medium":1,"minor":1},"core_each_secondary":1,"new_slot_each_secondary":0,"resonance_increment_main":[3,1,1,1],"resonance_increment_all":[0,0,0,0],"effective_preparation_cap":1,"expected_full_set":{"main":30,"each_secondary":3},"core_names":{"military":["列兵军刀","列兵臂章","列兵作训服"],"defense":["列兵护身盾","列兵坚守勋章","列兵防弹背心"],"logistics":["列兵工具包","列兵通行证","列兵工作服"],"knowledge":["列兵笔记本","列兵学员章","列兵学员服"]}},{"key":"sergeant","name":"士官","persisted_tier_id":4,"progression_order":2,"required_level":20,"box_diamonds":500,"base_main":{"core":9,"major":1,"medium":1,"minor":1},"core_each_secondary":2,"new_slot_each_secondary":0,"resonance_increment_main":[8,2,2,2],"resonance_increment_all":[0,0,0,0],"effective_preparation_cap":2,"expected_full_set":{"main":50,"each_secondary":6},"core_names":{"military":["士官战刀","士官突击章","士官作战服"],"defense":["士官防护盾","士官坚守章","士官防护甲"],"logistics":["士官维修包","士官勤务章","士官勤务服"],"knowledge":["士官观测镜","士官研习章","士官参谋服"]}},{"key":"officer","name":"校官","persisted_tier_id":2,"progression_order":3,"required_level":40,"box_diamonds":1200,"base_main":{"core":15,"major":3,"medium":2,"minor":1},"core_each_secondary":3,"new_slot_each_secondary":0,"resonance_increment_main":[15,3,3,3],"resonance_increment_all":[0,0,0,0],"effective_preparation_cap":3,"expected_full_set":{"main":87,"each_secondary":9},"core_names":{"military":["校官军刀","校官勋章","校官军服"],"defense":["校官防暴盾","校官铁壁勋章","校官重装防弹甲"],"logistics":["校官补给箱","校官调度章","校官军需服"],"knowledge":["校官战术罗盘","校官参谋章","校官参谋服"]}},{"key":"brigadier","name":"准将","persisted_tier_id":5,"progression_order":4,"required_level":60,"box_diamonds":2400,"base_main":{"core":20,"major":3,"medium":2,"minor":1},"core_each_secondary":4,"new_slot_each_secondary":0,"resonance_increment_main":[20,4,4,4],"resonance_increment_all":[0,0,0,0],"effective_preparation_cap":4,"expected_full_set":{"main":110,"each_secondary":12},"core_names":{"military":["准将指挥刀","准将先锋勋章","准将指挥服"],"defense":["准将卫戍盾","准将壁垒勋章","准将卫戍甲"],"logistics":["准将军需箱","准将统筹勋章","准将军需服"],"knowledge":["准将观测仪","准将谋略勋章","准将参谋服"]}},{"key":"general","name":"上将","persisted_tier_id":6,"progression_order":5,"required_level":80,"box_diamonds":3600,"base_main":{"core":25,"major":4,"medium":3,"minor":2},"core_each_secondary":4,"new_slot_each_secondary":0,"resonance_increment_main":[25,5,5,5],"resonance_increment_all":[0,0,0,0],"effective_preparation_cap":5,"expected_full_set":{"main":142,"each_secondary":12},"core_names":{"military":["上将统御佩剑","上将决胜勋章","上将统御军服"],"defense":["上将要塞盾","上将磐石勋章","上将要塞护甲"],"logistics":["上将补给总箱","上将军需勋章","上将勤务礼服"],"knowledge":["上将测距仪","上将战略勋章","上将战略军服"]}},{"key":"marshal","name":"元帅","persisted_tier_id":3,"progression_order":6,"required_level":100,"box_diamonds":5000,"base_main":{"core":30,"major":4,"medium":3,"minor":2},"core_each_secondary":5,"new_slot_each_secondary":0,"resonance_increment_main":[30,6,6,6],"resonance_increment_all":[5,0,0,2],"effective_preparation_cap":5,"expected_full_set":{"main":172,"each_secondary":22},"core_names":{"military":["元帅佩剑","元帅将星","元帅礼服"],"defense":["元帅重装盾","元帅不屈之星","元帅钛金铠"],"logistics":["元帅辎重车","元帅军需印","元帅长袍"],"knowledge":["元帅望远镜","元帅军师印","元帅军礼服"]}}],"new_slot_branch_names":{"military":{"helmet":"突击头盔","gloves":"射击手套","belt":"战术腰带","trousers":"突击军裤","boots":"突击军靴","backpack":"弹药背包","radio":"突击电台","map":"作战图囊","watch":"战术军表"},"defense":{"helmet":"守备钢盔","gloves":"防护手套","belt":"守备腰带","trousers":"防护军裤","boots":"防护军靴","backpack":"应急背包","radio":"守备电台","map":"工事图囊","watch":"值勤军表"},"logistics":{"helmet":"工勤头盔","gloves":"作业手套","belt":"工具腰带","trousers":"工勤军裤","boots":"勤务军靴","backpack":"补给背包","radio":"调度电台","map":"补给图囊","watch":"运输军表"},"knowledge":{"helmet":"参谋头盔","gloves":"测绘手套","belt":"参谋腰带","trousers":"参谋军裤","boots":"参谋军靴","backpack":"文书背包","radio":"指挥电台","map":"测绘图囊","watch":"校时军表"}},"preparation":{"binding":"player_officer_slot","main_attribute_per_effective_level":1,"empty_slot_bonus":0,"persist_on_gear_change":true,"persist_on_officer_dismissal":false,"success_rate":1,"gold_source":"player_main_city_gold","material_key":"officerPreparationParts","material_name":"军备零件","steps":[{"target_level":1,"required_officer_level":1,"parts":20,"gold":1000},{"target_level":2,"required_officer_level":20,"parts":40,"gold":2000},{"target_level":3,"required_officer_level":40,"parts":60,"gold":3000},{"target_level":4,"required_officer_level":60,"parts":80,"gold":4000},{"target_level":5,"required_officer_level":80,"parts":100,"gold":5000}],"daily":{"available_tasks":4,"max_claims":3,"parts_per_claim":40,"timezone":"Asia/Shanghai","reset":"00:00"},"weekly":{"daily_claims_required":15,"parts":240,"max_claims":1,"timezone":"Asia/Shanghai","reset":"monday_00:00"},"parts_sold_in_shop":false,"gear_dismantles_into_parts":false,"expected_full_officer_cost":{"parts":3600,"gold":180000},"pacing_example":{"assumptions":"120 parts daily; 240 extra on days 7,14,21,...; enough gold and officer level; one officer and no other spending","all_slots_level_1_day":2,"all_slots_level_2_day":6,"all_slots_level_3_day":10,"all_slots_level_4_day":16,"all_slots_level_5_day":24,"four_officers_all_slots_level_5_day":94}}};
  C.equipmentNames = {}; C.equipmentItems = {}; C.equipmentSets = {};
  C.equipmentTiers = {}; C.equipmentTierOrder = {}; C.equipmentBranches = {}; C.equipmentBranchOrder = {};
  C.equipmentSlots = {}; C.equipmentSlotOrder = {};
  C.equipmentSlotIcons = { weapon: '🗡️', badge: '🎖️', coat: '🦺', helmet: '🪖', gloves: '🧤', belt: '🎒', trousers: '👖', boots: '🥾', backpack: '🎒', radio: '📻', map: '🗺️', watch: '⌚' };
  var cfg = C.officerEquipmentConfig;
  cfg.branches.forEach(function (b, i) { C.equipmentBranches[b.key] = b.name; C.equipmentBranchOrder[b.key] = i + 1; });
  cfg.slots.forEach(function (s, i) { C.equipmentSlots[s.key] = s.name; C.equipmentSlotOrder[s.key] = i + 1; });
  cfg.tiers.forEach(function (t) {
    C.equipmentTiers[t.key] = { name: t.name, level: t.required_level, main: t.base_main.core, sub: t.core_each_secondary, cap: t.effective_preparation_cap };
    C.equipmentTierOrder[t.key] = t.progression_order;
    cfg.branches.forEach(function (b) {
      var set = t.key + '_' + b.key, keys = [], setName = t.name + b.name + '套装';
      cfg.slots.forEach(function (s, i) {
        var key = set + '_' + s.key, core = s.group === 'core';
        var name = core ? t.core_names[b.key][i] : t.name + cfg.new_slot_branch_names[b.key][s.key];
        var main = t.base_main[s.budget], sub = core ? t.core_each_secondary : t.new_slot_each_secondary;
        var attrs = {}; cfg.branches.forEach(function (attr) { attrs[attr.key] = attr.key === b.key ? main : sub; });
        C.equipmentNames[key] = name; keys.push(key);
        C.equipmentItems[key] = { id: key, name: name, cat: 'equipment', tier: t.key, tierName: t.name, branch: b.key, branchName: b.name,
          slot: s.key, slotName: s.name, level: t.required_level, setKey: set, cap: t.effective_preparation_cap,
          icon: C.equipmentSlotIcons[s.key], attrs: attrs,
          desc: setName + '·' + s.name + '：Lv.' + t.required_level + '可穿戴，' + b.name + '+' + main + '，其余属性各+' + sub + '；同套3/6/9/12件累计激活共鸣' };
      });
      C.equipmentSets[set] = { id: set, name: setName, tier: t.key, branch: b.key, keys: keys, level: t.required_level, config: t };
    });
  });
  C.shopItems = C.shopItems.filter(function (i) { return i.id.indexOf('box_') !== 0 || !C.equipmentSets[i.id.slice(4)]; });
  Object.keys(C.equipmentSets).forEach(function (key) {
    var set = C.equipmentSets[key];
    C.shopItems.push({ id: 'box_' + key, cat: 'officer', name: set.name.replace('套装', '装备箱'), icon: 'img/items/officer-gear-box.svg',
      desc: '固定获得十二件装备，每部位一件；Lv.' + set.level + '可穿戴；同套3/6/9/12件共鸣',
      price: set.config.box_diamonds, stock: null, tag: '十二件整套', equipmentSet: key });
  });
  /** 预览与后端相同的基础属性、部位整备和累计共鸣；只计算选择，不扣库存。 */
  function bonuses(keys, prep) {
    var result = { military: 0, defense: 0, logistics: 0, knowledge: 0 }, sets = {}, slots = {};
    keys.forEach(function (key) {
      var d = C.equipmentItems[key]; if (!d || slots[d.slot]) return;
      slots[d.slot] = true; sets[d.setKey] = (sets[d.setKey] || 0) + 1;
      cfg.branches.forEach(function (b) { result[b.key] += d.attrs[b.key]; });
      result[d.branch] += Math.min((prep || {})[d.slot] || 0, d.cap);
    });
    Object.keys(sets).forEach(function (key) {
      var set = C.equipmentSets[key], t = set.config;
      for (var i = 0; i < 4; i++) if (sets[key] >= (i + 1) * 3) {
        cfg.branches.forEach(function (b) { result[b.key] += t.resonance_increment_all[i]; });
        result[set.branch] += t.resonance_increment_main[i];
      }
    });
    return result;
  }
  G.EquipmentCatalog = {
    bonuses: bonuses,
    preview: function (officer, keys) {
      var current = bonuses((officer.equipment || []).map(function (e) { return e.itemId || e.itemKey; }), officer.preparation);
      var next = bonuses(keys, officer.preparation), net = {};
      cfg.branches.forEach(function (b) { net[b.key] = next[b.key] - current[b.key]; });
      return { bonus: next, net: net };
    }
  };
  // OFFICER_EQUIPMENT_CATALOG_END
})(window.Game);
