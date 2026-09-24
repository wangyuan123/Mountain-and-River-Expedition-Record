/* global window */
window.Game = window.Game || {};

(function (G) {
  'use strict';

  G.Constants = {
    siteInfo: {
      operator: '山河远征网络科技有限公司',
      icpNumber: '京ICP备00000000号-1',
      isPlaceholder: true
    },
    staticRoutes: {
      shop: 1, depot: 1, depotUse: 1, depotRename: 1,
      officer: 1, officerDetail: 1, academy: 1,
      tech: 1, settings: 1, battleDefaults: 1,
      reports: 1, reportDetail: 1, battle: 1, report: 1,
      mail: 1, recharge: 1, login: 1,
      guild: 1, map: 1, wild: 1, dispatch: 1
    },
    themeStorageKey: 'wargame_theme_pref',
    defaultTheme: 'blue-white-classic',
    themes: [
      { id: 'blue-white-classic', name: '战术经典蓝白风（推荐）', tag: '怀旧文字风', desc: '白底蓝字、渐变导航与浅蓝分隔线，重温早期手机家园的简洁排版' },
      { id: 'paper', name: '战术公文沙盘风', tag: '护眼米白', desc: '米白档案纸底 + 1px极细墨线 + 战备橄榄绿与印章红' },
      { id: 'dark', name: '战术夜航终端黑', tag: '极简冷黑', desc: '高纯度冷墨黑 + 雷达天青微光，夜间游玩省电护眼' }
    ],
    navItems: [
      { key: '1', label: '资源', route: 'buildRes' },
      { key: '2', label: '军事', route: 'buildArmy' },
      { key: '3', label: '军队', route: 'army' },
      { key: '·', label: '战术', route: 'battleDefaults' },
      { key: '4', label: '地图', route: 'world' },
      { key: '5', label: '情报', route: 'alerts' },
      { key: '6', label: '战报', route: 'reports' },
      { key: '7', label: '邮件', route: 'mail' },
      { key: '8', label: '任务', route: 'mainQuest' },
      { key: '9', label: '军团', route: 'guild' },
      { key: '0', label: '仓库', route: 'depot' },
      { key: '·', label: '科技', route: 'tech' }
    ],
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
      tech: '[1-6]研究 [0]返回',
      map: '点击挑战 [0]返回',
      wild: '点击占领/废弃 [0]返回',
      world: '拖动浏览 · 双指缩放 · 点击目标查看详情',
      dispatch: '选配兵力/军官/辎重 [0]返回',
      alerts: '查看情报 [0]返回',
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
      'infantry', 'motor', 'truck', 'armored', 'ltank', 'htank', 'assault', 'rocket',
      'scout', 'special', 'fighter', 'bomber', 'transport',
      'destroyer', 'sub', 'battleship', 'carrier'
    ],
    presetAvatars: [
      { id: 'commander-8', name: '萌系指挥官', role: '休闲', src: 'img/avatars/commander-8.svg' },
      { id: 'commander-1', name: '陆军上将', role: '全军统帅', src: 'img/avatars/commander-1.svg' },
      { id: 'commander-2', name: '装甲指挥官', role: '装甲先锋', src: 'img/avatars/commander-2.svg' },
      { id: 'commander-3', name: '王牌飞行员', role: '空中制霸', src: 'img/avatars/commander-3.svg' },
      { id: 'commander-4', name: '海军提督', role: '深海巨舰', src: 'img/avatars/commander-4.svg' },
      { id: 'commander-5', name: '战术参谋长', role: '战役规划', src: 'img/avatars/commander-5.svg' },
      { id: 'commander-6', name: '特战先锋', role: '敌后奇袭', src: 'img/avatars/commander-6.svg' },
      { id: 'commander-7', name: '最高元帅', role: '荣誉勋章', src: 'img/avatars/commander-7.svg' }
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
    equipmentBranchOrder: { military: 1, logistics: 2, knowledge: 3 },
    equipmentSlotOrder: { weapon: 1, badge: 2, coat: 3 },
    depotCategories: { jewelry: '珠宝珍品', equipment: '军官装备', officer: '军官道具', resource: '资源道具', util: '功能道具' },
    depotCategoryOrder: ['jewelry', 'equipment', 'officer', 'resource', 'util'],
    officerRoles: { mayor: '市长', commander: '指挥官', march: '行军中', idle: '闲置' },
    officerAttributes: { logistics: '后勤', military: '军事', defense: '防御', knowledge: '学识' },
    resourceNames: { food: '粮食', steel: '钢铁', oil: '石油', rare: '稀矿', gold: '黄金' },
    connectionStatusNames: { connected: '已连接', connecting: '连接中', reconnecting: '重连中', disconnected: '已断开' },
    battleReportTitles: { conquer: '征服报告', plunder: '掠夺报告', scout: '侦查报告' },
    battleResourceNames: { food: '粮', steel: '钢', oil: '油', rare: '稀矿', gold: '金' },
    unitTechKeys: { inf: null, arm: 'arm_engine', air: 'air_engine', nav: 'nav_engine' },
    resourceKeys: ['food', 'steel', 'oil', 'rare'],
    resourceKeysWithGold: ['food', 'steel', 'oil', 'rare', 'gold'],
    terrainPalette: { soil: [83, 73, 61], grass: [157, 169, 126] },
    mapOwnershipStyles: {
      own: { fill: 0x163f58, edge: 0x76ccea, ink: 0xf1fbff },
      other: { fill: 0x55391e, edge: 0xe7b76d, ink: 0xfff1d9 },
      neutral: { fill: 0x343b36, edge: 0xaeb8ad, ink: 0xf1f3ea }
    },
    onboardingPlans: { economy: ['稳固经济', 'buildRes'], expansion: ['继续扩张', 'world'], military: ['发展军备', 'army'] },
    techBranches: ['军事', '机动', '后勤', '侦察'],
    techEffectPercent: { cap: 10, load: 20, food_save: -5, train: 10, build: -5, medical: 5, range_all: 5 },
    worldTargetKeys: { wild: 'wildTiles', player: 'playerCities', npc: 'npcCities', simulated_npc: 'simulatedNpcCities', bandit: 'bandits' },
    dispatchActionNames: { conquer: '征服', plunder: '掠夺', scout: '侦查', gather: '采集', station: '派遣进驻' },
    cityStateNames: { peace: '和平', war: '战争', shield: '护盾' },
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
    buildMaxConcurrent: 6,
    saveAttrMax: 219,
    officerMaxLevel: 100,
    shopCategories: [
      { id: 'all',     name: '全部' },
      { id: 'jewelry', name: '珠宝' },
      { id: 'officer', name: '军官' },
      { id: 'resource',name: '资源' },
      { id: 'util',    name: '功能' },
      { id: 'gift',    name: '礼包' }
    ],
    shopItems: [
      // —— 军衔珠宝宝箱（开启直接获得晋升军衔所需各类珠宝）——
      { id: 'box_gem',         cat: 'jewelry', name: '军衔珠宝宝箱', icon: '🗃️', desc: '开启获得晋升必备珠宝：珍珠×5、珊瑚×3、琉璃×3、琥珀×2、玛瑙×2', price: 200,  stock: null, tag: '热销' },
      { id: 'box_gem_primary', cat: 'jewelry', name: '初级珠宝宝箱', icon: '🧰', desc: '开启获得士官晋升基础珠宝：珍珠×8、珊瑚×6、琉璃×5',             price: 150,  stock: null, tag: '士官晋升' },
      { id: 'box_gem_medium',  cat: 'jewelry', name: '中级珠宝宝箱', icon: '🧰', desc: '开启获得尉官晋升进阶珠宝：琥珀×8、玛瑙×6、水晶×5、翡翠×2',      price: 400,  stock: null, tag: '尉官晋升' },
      { id: 'box_gem_senior',  cat: 'jewelry', name: '高级珠宝宝箱', icon: '🎁', desc: '开启获得校官晋升精选珠宝：水晶×8、翡翠×8、玉石×6、夜明珠×3',      price: 1000, stock: null, tag: '校官晋升' },
      { id: 'box_gem_supreme', cat: 'jewelry', name: '特级夜明珠宝箱', icon: '🌟', desc: '开启获得将官晋升极品珍宝：夜明珠×8、玉石×10、翡翠×10',         price: 1800, stock: null, tag: '将官极品' },
      { id: 'box_gem_grand',   cat: 'jewelry', name: '璀璨珠宝全集箱', icon: '💎', desc: '开启获得全部9种晋升珠宝各5颗(共45颗珠宝)，助统帅连升数阶！',     price: 2500, stock: null, tag: '豪华全集' },

      // —— 军官道具 ——
      { id: 'expBook',    cat: 'officer',  name: '经验书',     icon: '📘', desc: '军官使用,获得10000经验',          price: 30,   stock: null, tag: '热销' },
      { id: 'expBookAdv', cat: 'officer',  name: '高级经验书', icon: '📕', desc: '军官使用,获得100000经验',         price: 150,  stock: null, tag: '推荐' },
      { id: 'expBookMax', cat: 'officer',  name: '满级经验书', icon: '📙', desc: '军官使用,直接升至满级(Lv.100)',   price: 1000, stock: null, tag: '极品' },
      { id: 'skillBook',  cat: 'officer',  name: '通用技能书', icon: '📗', desc: '选择军官使用，随机学习一个未掌握技能', price: 80, stock: null, tag: '随机' },
      { id: 'loyaltyBox', cat: 'officer',  name: '忠诚宝箱',   icon: '🎁', desc: '军官忠诚度+20,提升留任意愿',     price: 50,   stock: null, tag: '' },
      { id: 'renameCard', cat: 'officer',  name: '改名卡',     icon: '🏷️', desc: '为军官更换新名字',               price: 60,   stock: null, tag: '' },
      { id: 'recruitOrd', cat: 'officer',  name: '征募令',     icon: '🎖️', desc: '刷新军校,保底出现一名五星军官',   price: 500,  stock: 3,    tag: '稀有' },
      { id: 'starUp',     cat: 'officer',  name: '星耀符',     icon: '✨', desc: '军官升星,属性大幅成长',          price: 300,  stock: null, tag: '' },

      // —— 军官装备宝箱（整套装备，打开直接获得3件装备并激活套装属性）——
      { id: 'box_recruit_military',  cat: 'officer', name: '列兵军事装备箱', icon: '📦', desc: '开启获得整套列兵军事装备(军刀/臂章/作训服)，激活军事+3', price: 200,  stock: null, tag: '低级套装' },
      { id: 'box_recruit_logistics', cat: 'officer', name: '列兵后勤装备箱', icon: '📦', desc: '开启获得整套列兵后勤装备(工具包/通行证/工作服)，激活后勤+3', price: 200,  stock: null, tag: '低级套装' },
      { id: 'box_recruit_knowledge', cat: 'officer', name: '列兵学识装备箱', icon: '📦', desc: '开启获得整套列兵学识装备(笔记本/学员章/学员服)，激活学识+3', price: 200,  stock: null, tag: '低级套装' },
      { id: 'box_officer_military',  cat: 'officer', name: '校官军事装备箱', icon: '🎁', desc: '开启获得整套校官军事装备(军刀/勋章/军服)，激活军事+15',     price: 1200, stock: null, tag: '中级套装' },
      { id: 'box_officer_logistics', cat: 'officer', name: '校官后勤装备箱', icon: '🎁', desc: '开启获得整套校官后勤装备(补给箱/调度章/军需服)，激活后勤+15', price: 1200, stock: null, tag: '中级套装' },
      { id: 'box_officer_knowledge', cat: 'officer', name: '校官学识装备箱', icon: '🎁', desc: '开启获得整套校官学识装备(战术罗盘/参谋章/参谋服)，激活学识+15', price: 1200, stock: null, tag: '中级套装' },
      { id: 'box_marshal_military',  cat: 'officer', name: '元帅军事装备箱', icon: '👑', desc: '开启获得整套元帅军事装备(佩剑/将星/礼服)，激活军事+30+全属性+5', price: 5000, stock: null, tag: '满级套装' },
      { id: 'box_marshal_logistics', cat: 'officer', name: '元帅后勤装备箱', icon: '👑', desc: '开启获得整套元帅后勤装备(辎重车/军需印/长袍)，激活后勤+30+全属性+5', price: 5000, stock: null, tag: '满级套装' },
      { id: 'box_marshal_knowledge', cat: 'officer', name: '元帅学识装备箱', icon: '👑', desc: '开启获得整套元帅学识装备(望远镜/军师印/军礼服)，激活学识+30+全属性+5', price: 5000, stock: null, tag: '满级套装' },

      // —— 资源道具 ——
      { id: 'goldBox',    cat: 'resource', name: '黄金箱',     icon: '🪙', desc: '开启获得1000~5000黄金',        price: 80,   stock: null, tag: '' },
      { id: 'resBox',     cat: 'resource', name: '资源箱',     icon: '📦', desc: '开启获得粮钢油稀各500',        price: 120,  stock: null, tag: '热销' },
      { id: 'steelPack',  cat: 'resource', name: '钢铁大礼包', icon: '🔩', desc: '立即获得20000钢铁',            price: 200,  stock: null, tag: '' },
      { id: 'supplyPack', cat: 'resource', name: '战备补给包', icon: '🌾', desc: '粮钢油稀各8000,适合长期发展',  price: 350,  stock: null, tag: '超值' },
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
      { id: 'shield',    cat: 'util', name: '护盾',     icon: '🛡️', desc: '使用后8小时免受玩家攻击',         price: 200,  stock: null, tag: '' },
      { id: 'marchOrd',  cat: 'util', name: '行军令',   icon: '🚩', desc: '行军速度+50%,持续1小时',          price: 100,  stock: null, tag: '' },
      { id: 'populationOrder', cat: 'util', name: '人口动员令', icon: '👥', desc: '使用后立即增加500空闲人口,不超过人口上限', price: 100, stock: null, tag: '推荐' },

      // —— 礼包 ——
      { id: 'newbiePack', cat: 'gift', name: '新手礼包',   icon: '🎁', desc: '开7倍:粮20000/钢20000/油10000/稀5000/金3000', price: 99,   stock: 1, tag: '限时' },
      { id: 'monthCard',  cat: 'gift', name: '钻石月卡',   icon: '💳', desc: '立即得300钻,30天内每日登录送100钻',           price: 1500, stock: 1, tag: '推荐' },
      { id: 'warChest',   cat: 'gift', name: '战备月卡',   icon: '🎖️', desc: '立即得500钻+15个常用道具组合',                price: 888,  stock: 1, tag: '超值' },
      { id: 'annivPack',  cat: 'gift', name: '周年庆大礼', icon: '🎉', desc: '钻石×2000 + 道具×20 + 限定头衔',              price: 1999, stock: 1, tag: '限定' }
    ],
    rechargeCategories: [
      { id: 'diamond', name: '钻石充值' },
      { id: 'marshal', name: '元帅礼包' },
      { id: 'monthly', name: '月卡特权' }
    ],
    rechargePackages: [
      { id: 'p6', cat: 'diamond', name: '试玩补给', icon: '💎', rmb: 6, diamond: 60, desc: '适合首次体验充值' },
      { id: 'p30', cat: 'diamond', name: '少将补给', icon: '💠', rmb: 30, diamond: 330, desc: '额外赠送30钻石', bonus: '赠30' },
      { id: 'p98', cat: 'diamond', name: '中将补给', icon: '💠', rmb: 98, diamond: 1080, desc: '额外赠送100钻石', bonus: '赠100' },
      { id: 'p198', cat: 'diamond', name: '上将补给', icon: '💎', rmb: 198, diamond: 2230, desc: '额外赠送250钻石', bonus: '赠250' },
      { id: 'p328', cat: 'diamond', name: '大将补给', icon: '💎', rmb: 328, diamond: 3780, desc: '额外赠送500钻石', bonus: '赠500' },
      { id: 'p648', cat: 'diamond', name: '统帅补给', icon: '💎', rmb: 648, diamond: 7680, desc: '额外赠送1200钻石', bonus: '赠1200' },
      { id: 'p1280', cat: 'marshal', name: '元帅礼包', icon: '🎖️', rmb: 1280, diamond: 15800, desc: '钻石、资源与稀有道具组合礼包', bonus: '豪华' },
      { id: 'mk30', cat: 'monthly', name: '钻石月卡', icon: '💳', rmb: 30, diamond: 300, desc: '立即获得300钻石，持续领取月卡福利' },
      { id: 'wk98', cat: 'monthly', name: '战备月卡', icon: '🎖️', rmb: 98, diamond: 980, desc: '立即获得980钻石，享受战备补给特权' }
    ]
  };
})(window.Game);
