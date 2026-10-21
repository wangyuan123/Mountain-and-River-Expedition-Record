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
    marchAcceleration: { speedMultiplier: 1.5, minRemainingMs: 10000 },
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
            { wave: 'sine',  at: 0.00, duration: 0.12, from: 120, to: 45, gain: 0.35, envelope: 'punch' },
            { wave: 'noise', at: 0.00, duration: 0.08, from: 3200, to: 800, gain: 0.25, envelope: 'decay' },
            { wave: 'sine',  at: 0.14, duration: 0.10, from: 110, to: 45, gain: 0.30, envelope: 'punch' },
            { wave: 'noise', at: 0.14, duration: 0.07, from: 3400, to: 900, gain: 0.22, envelope: 'decay' },
            { wave: 'brass', at: 0.22, duration: 0.14, from: 196, to: 196, gain: 0.32, envelope: 'brass' },
            { wave: 'brass', at: 0.36, duration: 0.14, from: 261.6, to: 261.6, gain: 0.35, envelope: 'brass' },
            { wave: 'brass', at: 0.50, duration: 0.14, from: 329.6, to: 329.6, gain: 0.38, envelope: 'brass' },
            { wave: 'brass', at: 0.64, duration: 0.42, from: 392.0, to: 392.0, gain: 0.42, envelope: 'brass' },
            { wave: 'sine',  at: 0.64, duration: 0.40, from: 196.0, to: 196.0, gain: 0.20, envelope: 'brass' },
            { wave: 'sine',  at: 0.64, duration: 0.22, from: 140, to: 40, gain: 0.40, envelope: 'punch' },
            { wave: 'noise', at: 0.64, duration: 0.15, from: 4000, to: 600, gain: 0.20, envelope: 'decay' }
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
      { key: '5', label: '军情', route: 'alerts' },
      { key: '6', label: '战报', route: 'reports' },
      { key: '·', label: '聊天', route: 'chat' },
      { key: '7', label: '电报', route: 'mail' },
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
        { id: 'militaryRank', label: '军衔榜' }
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
      battle: '[1]立即结算/下一回合 [0]撤退',
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
    equipmentNames: {
      recruit_military_weapon: '列兵军刀', recruit_military_badge: '列兵臂章', recruit_military_coat: '列兵作训服',
      recruit_defense_weapon: '列兵护身盾', recruit_defense_badge: '列兵坚守勋章', recruit_defense_coat: '列兵防弹背心',
      recruit_logistics_weapon: '列兵工具包', recruit_logistics_badge: '列兵通行证', recruit_logistics_coat: '列兵工作服',
      recruit_knowledge_weapon: '列兵笔记本', recruit_knowledge_badge: '列兵学员章', recruit_knowledge_coat: '列兵学员服',
      officer_military_weapon: '校官军刀', officer_military_badge: '校官勋章', officer_military_coat: '校官军服',
      officer_defense_weapon: '校官防暴盾', officer_defense_badge: '校官铁壁勋章', officer_defense_coat: '校官重装防弹甲',
      officer_logistics_weapon: '校官补给箱', officer_logistics_badge: '校官调度章', officer_logistics_coat: '校官军需服',
      officer_knowledge_weapon: '校官战术罗盘', officer_knowledge_badge: '校官参谋章', officer_knowledge_coat: '校官参谋服',
      marshal_military_weapon: '元帅佩剑', marshal_military_badge: '元帅将星', marshal_military_coat: '元帅礼服',
      marshal_defense_weapon: '元帅重装盾', marshal_defense_badge: '元帅不屈之星', marshal_defense_coat: '元帅钛金铠',
      marshal_logistics_weapon: '元帅辎重车', marshal_logistics_badge: '元帅军需印', marshal_logistics_coat: '元帅长袍',
      marshal_knowledge_weapon: '元帅望远镜', marshal_knowledge_badge: '元帅军师印', marshal_knowledge_coat: '元帅军礼服'
    },
    equipmentSlotIcons: { weapon: '🗡️', badge: '🎖️', coat: '🦺' },
    equipmentTiers: {
      recruit: { name: '列兵', level: 1, main: 5, sub: 1 },
      officer: { name: '校官', level: 40, main: 15, sub: 3 },
      marshal: { name: '元帅', level: 100, main: 30, sub: 5 }
    },
    equipmentBranches: { military: '军事', defense: '防御', logistics: '后勤', knowledge: '学识' },
    equipmentSlots: { weapon: '武器', badge: '徽章', coat: '外套' },
    equipmentTierOrder: { recruit: 1, officer: 2, marshal: 3 },
    equipmentBranchOrder: { military: 1, defense: 2, logistics: 3, knowledge: 4 },
    equipmentSlotOrder: { weapon: 1, badge: 2, coat: 3 },
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
    // 与服务端 TechnologyRules 一致：中心从 Lv.1 起加速，学识按每点连续计入。
    // 按目标等级查表，舰船动力末级对齐终局项目28天，与服务端和设计文档一致。
    researchDuration: {"normal":[180,600,1800,10800,43200,85320,168420,332460,656400,1296000],"resource":[120,480,1500,5400,21600,46860,101640,220380,478020,1036800],"arm_engine":[300,900,2700,10800,43200,90360,188940,395100,826260,1728000],"nav_engine":[300,900,2700,10800,43200,96660,216180,483480,1081500,2419200],"air_engine":[300,900,2700,10800,43200,96660,216180,483480,1081500,2419200]},
    researchSpeed: { labPerLevel: 0.01, labMaxLevel: 10, knowledgePerPoint: 0.001 },
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
    buildMaxConcurrent: 6,
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
      { id: 'expBook',    cat: 'officer',  name: '经验书',     icon: '📘', desc: '军官使用,获得10000经验',          price: 30,   stock: null, tag: '热销' },
      { id: 'expBookAdv', cat: 'officer',  name: '高级经验书', icon: '📕', desc: '军官使用,获得100000经验',         price: 150,  stock: null, tag: '推荐' },
      { id: 'expBookMax', cat: 'officer',  name: '满级经验书', icon: '📙', desc: '军官使用,直接升至满级(Lv.100)',   price: 1000, stock: null, tag: '极品' },
      { id: 'skillBook',  cat: 'officer',  name: '通用技能书', icon: '📗', desc: '选择军官使用，随机学习一个未掌握技能', price: 80, stock: null, tag: '随机' },
      { id: 'loyaltyBox', cat: 'officer',  name: '忠诚宝箱',   icon: '🎁', desc: '军官忠诚度+20,提升留任意愿',     price: 50,   stock: null, tag: '' },
      { id: 'renameCard', cat: 'officer',  name: '军官改名卡', icon: '🏷️', desc: '为军官更换新名字',               price: 60,   stock: null, tag: '' },
      { id: 'recruitOrd', cat: 'officer',  name: '征募令',     icon: '🎖️', desc: '刷新军校,保底出现一名五星军官',   price: 500,  stock: 3,    tag: '稀有' },
      { id: 'starUp',     cat: 'officer',  name: '星耀符',     icon: '✨', desc: '升2/3/4/5星失败率10/20/30/60%，失败也消耗1枚', price: 300, stock: null, tag: '' },

      // —— 军官装备宝箱（整套装备，打开直接获得3件装备并激活套装属性）——
      { id: 'box_recruit_military',  cat: 'officer', name: '列兵军事装备箱', icon: '📦', desc: '开启获得整套列兵军事装备(军刀/臂章/作训服)，激活军事+3', price: 200,  stock: null, tag: '低级套装' },
      { id: 'box_recruit_defense',   cat: 'officer', name: '列兵防御装备箱', icon: '📦', desc: '开启获得整套列兵防御装备(护身盾/坚守勋章/防弹背心)，激活防御+3', price: 200,  stock: null, tag: '低级套装' },
      { id: 'box_recruit_logistics', cat: 'officer', name: '列兵后勤装备箱', icon: '📦', desc: '开启获得整套列兵后勤装备(工具包/通行证/工作服)，激活后勤+3', price: 200,  stock: null, tag: '低级套装' },
      { id: 'box_recruit_knowledge', cat: 'officer', name: '列兵学识装备箱', icon: '📦', desc: '开启获得整套列兵学识装备(笔记本/学员章/学员服)，激活学识+3', price: 200,  stock: null, tag: '低级套装' },
      { id: 'box_officer_military',  cat: 'officer', name: '校官军事装备箱', icon: '🎁', desc: '开启获得整套校官军事装备(军刀/勋章/军服)，激活军事+15',     price: 1200, stock: null, tag: '中级套装' },
      { id: 'box_officer_defense',   cat: 'officer', name: '校官防御装备箱', icon: '🎁', desc: '开启获得整套校官防御装备(防暴盾/铁壁勋章/重装防弹甲)，激活防御+15', price: 1200, stock: null, tag: '中级套装' },
      { id: 'box_officer_logistics', cat: 'officer', name: '校官后勤装备箱', icon: '🎁', desc: '开启获得整套校官后勤装备(补给箱/调度章/军需服)，激活后勤+15', price: 1200, stock: null, tag: '中级套装' },
      { id: 'box_officer_knowledge', cat: 'officer', name: '校官学识装备箱', icon: '🎁', desc: '开启获得整套校官学识装备(战术罗盘/参谋章/参谋服)，激活学识+15', price: 1200, stock: null, tag: '中级套装' },
      { id: 'box_marshal_military',  cat: 'officer', name: '元帅军事装备箱', icon: '👑', desc: '开启获得整套元帅军事装备(佩剑/将星/礼服)，激活军事+30+全属性+5', price: 5000, stock: null, tag: '满级套装' },
      { id: 'box_marshal_defense',   cat: 'officer', name: '元帅防御装备箱', icon: '👑', desc: '开启获得整套元帅防御装备(重装盾/不屈之星/钛金铠)，激活防御+30+全属性+5', price: 5000, stock: null, tag: '满级套装' },
      { id: 'box_marshal_logistics', cat: 'officer', name: '元帅后勤装备箱', icon: '👑', desc: '开启获得整套元帅后勤装备(辎重车/军需印/长袍)，激活后勤+30+全属性+5', price: 5000, stock: null, tag: '满级套装' },
      { id: 'box_marshal_knowledge', cat: 'officer', name: '元帅学识装备箱', icon: '👑', desc: '开启获得整套元帅学识装备(望远镜/军师印/军礼服)，激活学识+30+全属性+5', price: 5000, stock: null, tag: '满级套装' },

      // —— 资源道具 ——
      { id: 'goldBox',    cat: 'resource', name: '黄金箱',     icon: 'img/resources/models/gold.webp', desc: '开启获得1000~5000黄金',        price: 80,   stock: null, tag: '' },
      { id: 'resBox',     cat: 'resource', name: '资源箱',     icon: '📦', desc: '开启获得粮钢油稀各500',        price: 120,  stock: null, tag: '热销' },
      { id: 'steelPack',  cat: 'resource', name: '钢铁大礼包', icon: 'img/resources/models/steel.webp', desc: '立即获得20000钢铁',            price: 200,  stock: null, tag: '' },
      { id: 'supplyPack', cat: 'resource', name: '战备补给包', icon: 'img/resources/models/food.webp', desc: '粮钢油稀各8000,适合长期发展',  price: 350,  stock: null, tag: '超值' },
      { id: 'resourcePack500w', cat: 'resource', name: '资源大礼包', icon: '🎁', desc: '粮食/钢铁/石油/稀矿各500万', price: 5000, stock: null, tag: '豪华' },

      // —— 功能道具 - 加速符（建筑施工 / 军队生产通用）——
      { id: 'speedUp10m', cat: 'util', name: '10分加速符',icon: '⚡', desc: '立即缩短10分钟建筑/造兵时间',     price: 30,   stock: null, tag: '' },
      { id: 'speedUp1h',  cat: 'util', name: '1时加速符', icon: '⚡', desc: '立即缩短1小时建筑/造兵时间',     price: 100,  stock: null, tag: '热销' },
      { id: 'speedUp5h',  cat: 'util', name: '5时加速符', icon: '⚡', desc: '立即缩短5小时建筑/造兵时间',     price: 400,  stock: null, tag: '' },
      { id: 'speedUp12h', cat: 'util', name: '12时加速符',icon: '⚡', desc: '立即缩短12小时建筑/造兵时间',    price: 800,  stock: null, tag: '' },
      { id: 'speedUp24h', cat: 'util', name: '24时加速符',icon: '⚡', desc: '立即缩短24小时建筑/造兵时间',    price: 1500, stock: null, tag: '推荐' },
      { id: 'speedUp36h', cat: 'util', name: '36时加速符',icon: '⚡', desc: '立即缩短36小时建筑/造兵时间',    price: 2000, stock: null, tag: '' },
      { id: 'speedUp48h', cat: 'util', name: '48时加速符',icon: '⚡', desc: '立即缩短48小时建筑/造兵时间',    price: 2500, stock: null, tag: '超值' },
      { id: 'speedUp72h', cat: 'util', name: '72时加速符',icon: '⚡', desc: '立即缩短72小时建筑/造兵时间',    price: 3500, stock: null, tag: '限时' },
      { id: 'shield',    cat: 'util', name: '护盾',     icon: '🛡️', desc: '本城8小时免受侦查、攻击和宣战，可叠加；期间不能对其他玩家发起军事操作，不保护野地',         price: 200,  stock: null, tag: '' },
      { id: 'marchOrd',  cat: 'util', name: '行军令',   icon: '🚩', desc: '仓库启用：行军速度+50%，持续1小时；情报使用：本队当前路段剩余时间缩短约1/3，去程与回程各限一次', price: 100, stock: null, tag: '' },
      { id: 'signalJammingOrder', cat: 'util', name: '通讯干扰令', icon: '📡', desc: '对指向己方城市的敌军行军使用：延长剩余行军时间20%，最多2分钟；每支行军单程限用一次', price: 150, stock: null, tag: '' },
      { id: 'feintOrder', cat: 'util', name: '佯动诱敌令', icon: '🎭', desc: '对指向己方城市的敌军行军使用：延长剩余行军时间40%，最多4分钟；每支行军单程限用一次', price: 250, stock: null, tag: '' },
      { id: 'populationOrder', cat: 'util', name: '人口动员令', icon: '👥', desc: '使用后立即增加500空闲人口,不超过人口上限', price: 100, stock: null, tag: '推荐' },
      { id: 'emergencyPopulationOrder', cat: 'util', name: '紧急人口动员令', icon: '📣', desc: '使用后立即增加当前城市人口容量上限30%的空闲人口,不超过人口上限', price: 100, stock: null, tag: '' },
      { id: 'cityRenameCard', cat: 'util', name: '城市改名卡', icon: '🏷️', desc: '为一座城市更换新名字', price: 60, stock: null, tag: '' },
      { id: 'teleportNormal', cat: 'util', name: '普通传送', icon: '🌀', desc: '消耗1个，将当前城市随机传送至开放地图的四格空闲陆地', price: 100, stock: null, tag: '' },
      { id: 'teleportAdvanced', cat: 'util', name: '高级传送', icon: '📍', desc: '消耗1个，将当前城市传送至指定坐标，目的地须有完整2×2空闲陆地', price: 500, stock: null, tag: '' },

      // —— 礼包 ——
      { id: 'newbiePack', cat: 'gift', name: '新手礼包',   icon: '🎁', desc: '开启获得：粮食×20000、钢铁×20000、石油×10000、稀矿×5000、黄金×3000', price: 99, stock: 1, tag: '新手' }
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
      { id: 'p1280', cat: 'marshal', name: '元帅礼包', icon: '🎖️', rmb: 1280, diamond: 15800, desc: '钻石、资源与稀有道具组合礼包', bonus: '豪华' }
    ]
  };
})(window.Game);
