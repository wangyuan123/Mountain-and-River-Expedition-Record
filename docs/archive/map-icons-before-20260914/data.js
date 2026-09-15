/* global window */
window.Game = window.Game || {};

(function (G) {
  'use strict';

  G.DATA = {
    version: '2.0.0',
    saveKey: 'ww2_wistone_v1',

    factions: {
      allies: { name: '同盟国', color: '#4aa3ff' },
      axis:   { name: '轴心国', color: '#ff7a4a' }
    },

    resources: {
      food:  { name: '粮食', icon: 'img/res-food.svg', baseCap: 2000, capGrowth: 1.0 },
      steel: { name: '钢铁', icon: 'img/res-steel.svg', baseCap: 2000, capGrowth: 1.0 },
      oil:   { name: '石油', icon: 'img/res-oil.svg', baseCap: 1500, capGrowth: 0.9 },
      rare:  { name: '稀矿', icon: 'img/res-rare.svg', baseCap: 800,  capGrowth: 0.7 },
      gold:  { name: '黄金', icon: 'img/gold.svg', baseCap: 0,    capGrowth: 0 },
      diamond:{ name: '钻石', icon: '💎', baseCap: 0,   capGrowth: 0 }
    },
    // 资源成本/快捷显示用的 emoji 表
    // (按钮、邮件、弹窗等纯文本场景统一用 emoji, 不再用 img 路径,
    // 避免 img/oil.svg160 这种字符串泄露问题)
    resEmoji: {
      food: '🌾', steel: '🔩', oil: '🛢️', rare: '💠', gold: '🪙', diamond: '💎'
    },

    groupSlots: {
      res: 12,
      army: 12
    },
    buildings: {
      command:      { name: '市政厅',   desc: '主城,决定其他建筑等级上限', baseCost: { steel: 400, food: 200 },            growth: 1.6, cat: 'core', slots: 1 },
      house:        { name: '民居',     desc: '提供人口上限,每级+1200人口', baseCost: { steel: 120, food: 60 },             growth: 1.5, cat: 'core', popPer: 1200, slots: 32 },
      factory:      { name: '军工厂',   desc: '生产步兵、装甲车辆与战机',   baseCost: { steel: 240, oil: 100 },             growth: 1.6, cat: 'army', slots: 32 },
      lightfactory: { name: '轻工厂',   desc: '生产轻型坦克',              baseCost: { steel: 260, oil: 110, rare: 10 },   growth: 1.6, cat: 'army', slots: 1 },
      heavyfactory: { name: '重工厂',   desc: '生产重型坦克/突击炮/火箭',  baseCost: { steel: 320, oil: 140, rare: 30 },   growth: 1.6, cat: 'army', slots: 1 },
      airport:      { name: '机场',     desc: '生产空军',                  baseCost: { steel: 280, oil: 120, rare: 30 },   growth: 1.6, cat: 'army', slots: 1 },
      port:         { name: '港口',     desc: '生产海军',                  baseCost: { steel: 360, oil: 160, rare: 50 },   growth: 1.7, cat: 'army', slots: 1 },
      academy:      { name: '军校',     desc: '招募军官',                  baseCost: { steel: 200, food: 120, gold: 200 }, growth: 1.6, cat: 'core', slots: 1 },
      staff:        { name: '参谋部',   desc: '军官槽位与野地上限,带兵上限 +10%/级', baseCost: { steel: 220, food: 100 },            growth: 1.6, cat: 'core', slots: 1 },
      farm:         { name: '农田',     desc: '每小时产出粮食',            baseCost: { steel: 80 },                        growth: 1.5, cat: 'res', produces: 'food',  baseProduce: 40, slots: 32 },
      refinery:     { name: '炼钢厂',   desc: '每小时产出钢铁',            baseCost: { steel: 80 },                        growth: 1.5, cat: 'res', produces: 'steel', baseProduce: 40, slots: 32 },
      oilfield:     { name: '石油基地', desc: '每小时产出石油',            baseCost: { steel: 80 },                        growth: 1.5, cat: 'res', produces: 'oil',   baseProduce: 25, slots: 32 },
      raremine:     { name: '稀矿厂',   desc: '每小时产出稀矿',            baseCost: { steel: 120, oil: 40 },              growth: 1.6, cat: 'res', produces: 'rare',  baseProduce: 12, slots: 32 },
      depot:        { name: '仓库',     desc: '提升资源上限,被掠夺时保护资源', baseCost: { steel: 100 },                  growth: 1.5, cat: 'res', capPer: 1500, protectPer: 1000, slots: 32 },
      lab:          { name: '科研中心', desc: '解锁与加速科技研究',        baseCost: { steel: 200, food: 100, rare: 20 },  growth: 1.6, cat: 'core', slots: 1 },
      radar:        { name: '雷达站',   desc: '侦察野地与敌方兵力',        baseCost: { steel: 180, oil: 60, rare: 20 },    growth: 1.6, cat: 'core', slots: 1 },
      wall:         { name: '围墙',     desc: '城防,提升守城部队防御',     baseCost: { steel: 200, food: 80 },             growth: 1.5, cat: 'def', defBonus: 5, slots: 1 },
      apron:        { name: '停机坪',   desc: '空军调度,提升空军出击上限', baseCost: { steel: 220, oil: 80, rare: 20 },    growth: 1.6, cat: 'def', airCap: 20, slots: 1 },
      transit:      { name: '运输站',   desc: '资源调度,全资源产出 +3%/级', baseCost: { steel: 160, food: 80 },            growth: 1.6, cat: 'res', resBonus: 3, slots: 1 },
      liaison:      { name: '联络中心', desc: '外交,军官刷新更优质',       baseCost: { steel: 200, food: 120, gold: 200 }, growth: 1.6, cat: 'core', slots: 1 },
      exchange:     { name: '交易所',   desc: '资源互换,按比例转换资源',   baseCost: { steel: 180, food: 100, gold: 100 }, growth: 1.5, cat: 'res', slots: 1 }
    },

    forts: {
      bunker:   { name: '碉堡',     desc: '坚固掩体,反步兵,近程高血防', atk: 8,  def: 14, hp: 260, range: 60,  spd: 0, cost: { steel: 30, oil: 0,  rare: 0  }, strongVs: 'infantry', cat: 'fort', autoAdvance: false },
      howitzer: { name: '榴弹炮',   desc: '远程压制,反步兵与建筑',     atk: 50, def: 6,  hp: 80,  range: 300, spd: 0, cost: { steel: 60, oil: 10, rare: 5  }, strongVs: 'infantry', cat: 'fort', autoAdvance: false },
      antitank: { name: '反坦克炮', desc: '穿甲火力,反装甲',           atk: 45, def: 6,  hp: 70,  range: 250, spd: 0, cost: { steel: 70, oil: 10, rare: 10 }, strongVs: 'ltank',    cat: 'fort', autoAdvance: false },
      flak:     { name: '防空炮',   desc: '对空火力,反空军',           atk: 35, def: 5,  hp: 60,  range: 280, spd: 0, cost: { steel: 55, oil: 15, rare: 15 }, strongVs: 'fighter',  cat: 'fort', autoAdvance: false }
    },

    units: {
      infantry:  { name: '步兵',     cat: 'inf',  atk: 6,   def: 4,   hp: 30,  spd: 3, range: 100, food: 1,  pop: 1, build: 'factory',  cost: { steel: 20,  oil: 0,   rare: 0  }, strongVs: null,           branch: 'land' },
      motor:     { name: '摩托兵',   cat: 'inf',  atk: 10,  def: 4,   hp: 30,  spd: 7, range: 100, food: 2,  pop: 1, build: 'factory',  cost: { steel: 40,  oil: 10,  rare: 0  }, strongVs: 'infantry',     branch: 'land' },
      truck:     { name: '卡车',     cat: 'inf',  atk: 2,   def: 6,   hp: 50,  spd: 8, range: 0,   food: 2,  pop: 1, build: 'factory',  cost: { steel: 60,  oil: 20,  rare: 0  }, strongVs: null,           branch: 'land', logistic: true, load: 50, autoAdvance: false },
      armored:   { name: '装甲车',   cat: 'arm',  atk: 18,  def: 12,  hp: 80,  spd: 7, range: 120, food: 4,  pop: 2, build: 'factory',  cost: { steel: 120, oil: 40,  rare: 10 }, strongVs: 'fighter',      branch: 'land' },
      ltank:     { name: '轻型坦克', cat: 'arm',  atk: 28,  def: 22,  hp: 120, spd: 6, range: 130, food: 5,  pop: 2, build: 'lightfactory', cost: { steel: 200, oil: 60, rare: 20 }, strongVs: 'armored',      branch: 'land' },
      htank:     { name: '重型坦克', cat: 'arm',  atk: 50,  def: 40,  hp: 220, spd: 4, range: 140, food: 8,  pop: 4, build: 'heavyfactory', cost: { steel: 400, oil: 120, rare: 50 }, strongVs: 'ltank',        branch: 'land' },
      assault:   { name: '突击炮',   cat: 'arm',  atk: 60,  def: 18,  hp: 120, spd: 4, range: 300, food: 7,  pop: 3, build: 'heavyfactory', cost: { steel: 360, oil: 100, rare: 60 }, strongVs: 'htank',        branch: 'land' },
      rocket:    { name: '火箭',     cat: 'arm',  atk: 90,  def: 14,  hp: 100, spd: 4, range: 350, food: 9,  pop: 4, build: 'heavyfactory', cost: { steel: 500, oil: 160, rare: 100}, strongVs: 'htank',        branch: 'land' },
      scout:     { name: '侦察机',   cat: 'air',  atk: 4,   def: 6,   hp: 30,  spd: 14,range: 200, food: 3,  pop: 1, build: 'factory',  cost: { steel: 80,  oil: 40,  rare: 10 }, strongVs: null,           branch: 'air', autoAdvance: false },
      special:   { name: '特种兵',   cat: 'air',  atk: 24,  def: 14,  hp: 60,  spd: 13,range: 180, food: 4,  pop: 2, build: 'factory',  cost: { steel: 160, oil: 60,  rare: 30 }, strongVs: null,           branch: 'air' },
      fighter:   { name: '战斗机',   cat: 'air',  atk: 35,  def: 22,  hp: 90,  spd: 12,range: 260, food: 5,  pop: 2, build: 'factory',  cost: { steel: 200, oil: 80,  rare: 30 }, strongVs: 'bomber',       branch: 'air' },
      bomber:    { name: '轰炸机',   cat: 'air',  atk: 70,  def: 16,  hp: 110, spd: 9, range: 280, food: 7,  pop: 3, build: 'factory',  cost: { steel: 320, oil: 140, rare: 60 }, strongVs: 'htank',        branch: 'air' },
      transport: { name: '运输机',   cat: 'air',  atk: 2,   def: 12,  hp: 120, spd: 8, range: 0,   food: 5,  pop: 2, build: 'factory',  cost: { steel: 240, oil: 100, rare: 30 }, strongVs: null,           branch: 'air', logistic: true, load: 80 },
      destroyer: { name: '驱逐舰',   cat: 'nav',  atk: 40,  def: 28,  hp: 160, spd: 6, range: 250, food: 7,  pop: 3, build: 'port',     cost: { steel: 300, oil: 120, rare: 60 }, strongVs: 'sub',          branch: 'sea' },
      sub:       { name: '潜艇',     cat: 'nav',  atk: 65,  def: 18,  hp: 110, spd: 5, range: 230, food: 6,  pop: 3, build: 'port',     cost: { steel: 360, oil: 100, rare: 80 }, strongVs: 'battleship',   branch: 'sea' },
      battleship:{ name: '战列舰',   cat: 'nav',  atk: 100, def: 60,  hp: 360, spd: 4, range: 320, food: 12, pop: 6, build: 'port',     cost: { steel: 700, oil: 240, rare: 160}, strongVs: 'destroyer',    branch: 'sea' },
      carrier:   { name: '航母',     cat: 'nav',  atk: 130, def: 40,  hp: 280, spd: 4, range: 400, food: 15, pop: 8, build: 'port',     cost: { steel: 900, oil: 300, rare: 240}, strongVs: null,           branch: 'sea' }
    },

    techs: {
      attack_tech:   { name: '攻击科技',   branch: '军事', desc: '全军攻击 +5%/级',       max: 10, labReq: 1, baseCost: { steel: 240, food: 120 }, growth: 1.7, affect: 'atk_all' },
      defense_tech:  { name: '防御科技',   branch: '军事', desc: '全军防御 +5%/级',       max: 10, labReq: 1, baseCost: { steel: 240, food: 120 }, growth: 1.7, affect: 'def_all' },
      weapon_range:  { name: '武器射程',   branch: '军事', desc: '全军武器射程 +5%/级',   max: 10, labReq: 2, baseCost: { steel: 280, food: 140, rare: 20 }, growth: 1.8, affect: 'range_all' },
      cmd_hp:        { name: '军队生命',   branch: '军事', desc: '军队生命 +5%/级',       max: 10, labReq: 3, baseCost: { steel: 300, food: 160, rare: 30 }, growth: 1.8, affect: 'hp_all' },
      inf_load:      { name: '步兵负重',   branch: '后勤', desc: '步兵负重 +20%/级(掠夺)', max: 5, labReq: 2, baseCost: { steel: 200, food: 100 }, growth: 1.6, affect: 'load' },
      arm_engine:    { name: '燃烧引擎',   branch: '机动', desc: '装甲系移动 +5%/级',     max: 10, labReq: 3, baseCost: { steel: 320, oil: 120, rare: 40 }, growth: 1.8, affect: 'spd_arm' },
      air_engine:    { name: '喷气推进',   branch: '机动', desc: '空军移动 +5%/级',         max: 10, labReq: 4, baseCost: { steel: 360, oil: 160, rare: 70 }, growth: 1.9, affect: 'spd_air' },
      nav_engine:    { name: '舰船动力',   branch: '机动', desc: '海军移动 +5%/级',         max: 10, labReq: 5, baseCost: { steel: 400, oil: 200, rare: 100 }, growth: 2.0, affect: 'spd_nav' },
      log_production:{ name: '资源采集',   branch: '后勤', desc: '资源产出 +5%/级',         max: 10, labReq: 1, baseCost: { steel: 320, food: 160 }, growth: 1.8, affect: 'res' },
      log_warehouse: { name: '仓储技术',   branch: '后勤', desc: '资源上限 +10%/级',        max: 5, labReq: 2, baseCost: { steel: 280, food: 140 }, growth: 1.7, affect: 'cap' },
      log_food:      { name: '军需补给',   branch: '后勤', desc: '养兵耗粮 -5%/级',         max: 10, labReq: 3, baseCost: { steel: 360, food: 200 }, growth: 1.8, affect: 'food_save' },
      log_train:     { name: '训练加速',   branch: '后勤', desc: '征召批量 +10%/级',         max: 10, labReq: 2, baseCost: { steel: 300, food: 180, gold: 100 }, growth: 1.8, affect: 'train' },
      log_build:     { name: '建筑加速',   branch: '后勤', desc: '建筑升级资源 -5%/级',     max: 10, labReq: 2, baseCost: { steel: 340, food: 160, gold: 120 }, growth: 1.8, affect: 'build' },
      log_medical:   { name: '医疗技术',   branch: '后勤', desc: '伤兵可回收 +5%/级，最高50%',   max: 10, labReq: 3, baseCost: { steel: 320, food: 220, gold: 150 }, growth: 1.8, affect: 'medical' },
      recon_level:   { name: '侦察技术',   branch: '侦察', desc: '侦察情报深度 +1 阶/级，逐级探明城防、守军、建筑、科技与将领', max: 5, labReq: 1, baseCost: { steel: 180, oil: 60 }, growth: 1.6, affect: 'recon' },
      recon_radar:   { name: '雷达预警',   branch: '侦察', desc: '提前发现敌方 +1 回合',    max: 3, labReq: 2, baseCost: { steel: 240, oil: 100, rare: 20 }, growth: 1.7, affect: 'radar' },
    },
    officerNames: [
      '隆美尔', '朱可夫', '巴顿', '蒙哥马利', '古德里安', '曼施坦因', '麦克阿瑟', '尼米兹',
      '山本五十六', '邓尼茨', '崔可夫', '艾森豪威尔', '布雷德利', '莫德尔', '龙德施泰特', '华西列夫斯基',
      '海因里希', '克卢格', '霍特', '切尔尼亚霍夫斯基', '梁思成', '施瓦茨科普夫', '李宗仁', '孙立人'
    ],
    officerTitles: ['列兵', '上士', '少尉', '中尉', '上尉', '少校', '中校', '上校', '准将', '少将', '中将', '上将'],
    starColor: { 1: '#bbb', 2: '#7fc4ff', 3: '#a070ff', 4: '#ffa84a', 5: '#ffe14a' },

    wildTypes: {
      forest:     { name: '森林',   res: null,     icon: 'img/forest.svg' },
      hill:       { name: '丘陵',   res: null,     icon: 'img/mount.svg' },
      swamp:      { name: '沼泽',   res: null,     icon: 'img/swamp.svg' },
      grainfield: { name: '粮田',   res: 'food',   icon: 'img/grainfield.svg' },
      ironworks:  { name: '炼铁厂', res: 'steel',  icon: 'img/ironworks.svg' },
      oil:        { name: '油田',   res: 'oil',    icon: 'img/wild-oil.svg' },
      rarefactory:{ name: '稀矿厂', res: 'rare',   icon: 'img/rarefactory.svg' }
    },

    zones: {
      normandy: {
        name: '诺曼底战役',
        desc: '滩头登陆,撕开大西洋壁垒。',
        unlock: null,
        stages: [
          { id: 'no_1', name: '奥马哈滩头', enemy: { infantry: 40 },                                          reward: { food: 200, steel: 300, oil: 150, rare: 30,  gold: 50,  exp: 30 } },
          { id: 'no_2', name: '滨海小镇',   enemy: { infantry: 60, motor: 15 },                               reward: { food: 240, steel: 360, oil: 180, rare: 40,  gold: 60,  exp: 50 } },
          { id: 'no_3', name: '桥头堡',     enemy: { motor: 20, armored: 10, ltank: 6 },                      reward: { food: 300, steel: 460, oil: 240, rare: 60,  gold: 80,  exp: 80 } },
          { id: 'no_4', name: '敌军反扑',   enemy: { ltank: 14, assault: 6, fighter: 8 },                     reward: { food: 380, steel: 580, oil: 320, rare: 90,  gold: 110, exp: 120 } },
          { id: 'no_5', name: '司令部突袭', enemy: { htank: 8, assault: 10, bomber: 6, destroyer: 4 },        reward: { food: 500, steel: 800, oil: 460, rare: 140, gold: 160, exp: 180 } }
        ]
      },
      africa: {
        name: '北非战场',
        desc: '黄沙漫天,坦克洪流对决。',
        unlock: { zone: 'normandy', stage: 3 },
        stages: [
          { id: 'af_1', name: '沙漠哨所',   enemy: { motor: 30, ltank: 10 },                                  reward: { food: 360, steel: 560, oil: 320, rare: 80,  gold: 90,  exp: 100 } },
          { id: 'af_2', name: '补给线截击', enemy: { ltank: 16, assault: 8 },                                 reward: { food: 440, steel: 680, oil: 400, rare: 100, gold: 120, exp: 140 } },
          { id: 'af_3', name: '装甲对决',   enemy: { htank: 10, assault: 10, fighter: 10 },                   reward: { food: 540, steel: 820, oil: 500, rare: 130, gold: 160, exp: 180 } },
          { id: 'af_4', name: '海岸炮台',   enemy: { assault: 12, battleship: 3, destroyer: 6 },              reward: { food: 660, steel: 1000, oil: 620, rare: 170, gold: 210, exp: 230 } },
          { id: 'af_5', name: '隆美尔之影', enemy: { htank: 16, rocket: 8, bomber: 10, sub: 4 },              reward: { food: 880, steel: 1320, oil: 820, rare: 230, gold: 300, exp: 320 } }
        ]
      },
      eastern: {
        name: '东线战场',
        desc: '凛冬将至,钢铁洪流碰撞。',
        unlock: { zone: 'africa', stage: 3 },
        stages: [
          { id: 'es_1', name: '边境遭遇',   enemy: { infantry: 100, ltank: 20 },                             reward: { food: 600, steel: 900, oil: 560, rare: 140, gold: 160, exp: 180 } },
          { id: 'es_2', name: '钢铁洪流',   enemy: { htank: 20, assault: 12 },                                reward: { food: 720, steel: 1080, oil: 680, rare: 180, gold: 200, exp: 230 } },
          { id: 'es_3', name: '库尔斯克',   enemy: { htank: 28, rocket: 10, fighter: 16, bomber: 8 },        reward: { food: 880, steel: 1320, oil: 840, rare: 230, gold: 260, exp: 300 } },
          { id: 'es_4', name: '城市巷战',   enemy: { infantry: 200, motor: 60, assault: 16, special: 12 },      reward: { food: 1040, steel: 1560, oil: 1000, rare: 290, gold: 330, exp: 380 } },
          { id: 'es_5', name: '柏林外围',   enemy: { htank: 36, rocket: 16, fighter: 20, bomber: 12, battleship: 4, special: 20 }, reward: { food: 1400, steel: 2100, oil: 1340, rare: 400, gold: 480, exp: 520 } }
        ]
      },
      pacific: {
        name: '太平洋战场',
        desc: '海天之间,谁主沉浮。',
        unlock: { zone: 'eastern', stage: 3 },
        stages: [
          { id: 'pa_1', name: '环礁侦察',   enemy: { scout: 10, special: 8, fighter: 16, sub: 4 },                       reward: { food: 800, steel: 1200, oil: 800, rare: 200, gold: 220, exp: 220 } },
          { id: 'pa_2', name: '航母编队',   enemy: { fighter: 24, bomber: 12, carrier: 1, destroyer: 6, special: 12 },    reward: { food: 1000, steel: 1500, oil: 1000, rare: 260, gold: 300, exp: 300 } },
          { id: 'pa_3', name: '夜袭港口',   enemy: { sub: 12, battleship: 4, destroyer: 8, special: 16 },                 reward: { food: 1200, steel: 1800, oil: 1200, rare: 320, gold: 380, exp: 380 } },
          { id: 'pa_4', name: '决战中途',   enemy: { carrier: 3, fighter: 40, bomber: 20, battleship: 6, special: 24 },   reward: { food: 1500, steel: 2250, oil: 1500, rare: 420, gold: 500, exp: 480 } },
          { id: 'pa_5', name: '东京湾',     enemy: { carrier: 6, battleship: 10, fighter: 60, bomber: 30, special: 40 },  reward: { food: 2200, steel: 3300, oil: 2200, rare: 640, gold: 800, exp: 700 } }
        ]
      }
    },

    world: {
      size: 200,
      viewRadius: 3,
      marchSecPerGrid: 9,
      banditNames: ['流寇营地', '残兵游勇', '马匪哨所', '叛军据点', '山贼窝点', '溃兵残部', '武装走私队', '雇佣兵营'],
      banditLevels: [
        { lv: 1, army: { infantry: 20 },                              reward: { food: 80,   steel: 120, oil: 60,  rare: 10, gold: 15, exp: 15 } },
        { lv: 2, army: { infantry: 30, motor: 8 },                    reward: { food: 120,  steel: 180, oil: 90,  rare: 15, gold: 20, exp: 25 } },
        { lv: 3, army: { motor: 15, armored: 6 },                     reward: { food: 180,  steel: 260, oil: 140, rare: 25, gold: 30, exp: 40 } },
        { lv: 4, army: { ltank: 10, armored: 8 },                     reward: { food: 240,  steel: 360, oil: 200, rare: 40, gold: 45, exp: 60 } },
        { lv: 5, army: { htank: 6, assault: 4, fighter: 4 },          reward: { food: 320,  steel: 480, oil: 280, rare: 60, gold: 70, exp: 90 } },
        { lv: 6, army: { htank: 10, rocket: 6, bomber: 4 },           reward: { food: 440,  steel: 660, oil: 400, rare: 90, gold: 100, exp: 130 } },
        { lv: 7, army: { htank: 16, rocket: 10, fighter: 10, sub: 4 }, reward: { food: 600,  steel: 900, oil: 560, rare: 130, gold: 150, exp: 180 } },
        { lv: 8, army: { battleship: 4, carrier: 1, fighter: 20 },    reward: { food: 800,  steel: 1200, oil: 760, rare: 180, gold: 220, exp: 250 } }
      ],
      npcCityNames: ['汉堡', '华沙', '维也纳', '布鲁塞尔', '阿姆斯特丹', '斯德哥尔摩', '奥斯陆', '哥本哈根', '布拉格', '布达佩斯', '贝尔格莱德', '索菲亚', '布加勒斯特', '赫尔辛基', '都柏林', '里斯本'],
      playerCityNames: ['钢铁洪流', '虎式之巢', '苍穹之眼', '深海利剑', '雷霆要塞', '孤狼营地', '铁血堡垒', '风暴前线', '暗夜哨站', '烈焰军团']
    },
    officerSkills: {
      frenzy:   { name: '猛攻',   desc: '攻击力额外+10%/级',           max: 5, cat: 'atk' },
      bulwark:  { name: '铁壁',   desc: '防御力额外+10%/级',           max: 5, cat: 'def' },
      blitz:    { name: '闪电战', desc: '行军速度+15%/级',             max: 5, cat: 'spd' },
      suppress: { name: '压制',   desc: '降低敌方攻击力8%/级',         max: 5, cat: 'debuff' },
      pierce:   { name: '破甲',   desc: '无视敌方防御12%/级',          max: 5, cat: 'pierce' },
      supply:   { name: '补给',   desc: '粮食消耗-20%/级',             max: 5, cat: 'logi' },
      medic:    { name: '急救',   desc: '战后伤兵额外回收+3%/级，最高15%', max: 5, cat: 'medic' },
      combo:    { name: '连击',   desc: '8%/级概率额外攻击一次',       max: 5, cat: 'combo' }
    },
    items: {
      expBook:   { name: '经验书',   icon: '📘', desc: '军官使用,获得10000经验',          cat: 'officer' },
      expBookAdv:{ name: '高级经验书',icon: '📕', desc: '军官使用,获得100000经验',         cat: 'officer' },
      expBookMax:{ name: '满级经验书',icon: '📙', desc: '军官使用,直接升至满级(Lv.100)',    cat: 'officer' },
      skillBook: { name: '技能书',   icon: '📗', desc: '军官学习新技能',                 cat: 'officer' },
      loyaltyBox:{ name: '忠诚宝箱', icon: '🎁', desc: '军官使用,忠诚度+20',              cat: 'officer' },
      renameCard:{ name: '改名卡',   icon: '🏷️', desc: '为军官更换新名字',                cat: 'officer' },
      recruitOrd:{ name: '征募令',   icon: '🎖️', desc: '刷新军校,保底出现一名五星军官',   cat: 'officer' },
      starUp:    { name: '星耀符',   icon: '✨', desc: '军官升星,属性大幅成长',           cat: 'officer' },
      box_recruit_military:  { name: '列兵军事装备箱', icon: '📦', desc: '开启获得整套列兵军事装备(军刀/臂章/作训服)', cat: 'officer', isBox: true },
      box_recruit_logistics: { name: '列兵后勤装备箱', icon: '📦', desc: '开启获得整套列兵后勤装备(工具包/通行证/工作服)', cat: 'officer', isBox: true },
      box_recruit_knowledge: { name: '列兵学识装备箱', icon: '📦', desc: '开启获得整套列兵学识装备(笔记本/学员章/学员服)', cat: 'officer', isBox: true },
      box_officer_military:  { name: '校官军事装备箱', icon: '🎁', desc: '开启获得整套校官军事装备(军刀/勋章/军服)', cat: 'officer', isBox: true },
      box_officer_logistics: { name: '校官后勤装备箱', icon: '🎁', desc: '开启获得整套校官后勤装备(补给箱/调度章/军需服)', cat: 'officer', isBox: true },
      box_officer_knowledge: { name: '校官学识装备箱', icon: '🎁', desc: '开启获得整套校官学识装备(战术罗盘/参谋章/参谋服)', cat: 'officer', isBox: true },
      box_marshal_military:  { name: '元帅军事装备箱', icon: '👑', desc: '开启获得整套元帅军事装备(佩剑/将星/礼服)', cat: 'officer', isBox: true },
      box_marshal_logistics: { name: '元帅后勤装备箱', icon: '👑', desc: '开启获得整套元帅后勤装备(辎重车/军需印/长袍)', cat: 'officer', isBox: true },
      box_marshal_knowledge: { name: '元帅学识装备箱', icon: '👑', desc: '开启获得整套元帅学识装备(望远镜/军师印/军礼服)', cat: 'officer', isBox: true },
      goldBox:   { name: '黄金箱',   icon: '🪙', desc: '开启获得1000-5000黄金',           cat: 'resource' },
      resBox:    { name: '资源箱',   icon: '📦', desc: '开启获得粮钢油稀各500',           cat: 'resource' },
      steelPack: { name: '钢铁大礼包',icon: '🔩',desc: '立即获得20000钢铁',               cat: 'resource' },
      supplyPack:{ name: '战备补给包',icon: '🌾',desc: '粮钢油稀各8000,适合长期发展',     cat: 'resource' },
      resourcePack500w: { name: '资源大礼包', icon: '🎁', desc: '粮食/钢铁/石油/稀矿各500万', cat: 'resource' },
      speedUp10m: { name: '10分加速符',icon: '⚡', desc: '立即缩短10分钟建筑/造兵时间',     cat: 'util', seconds: 600   },
      speedUp1h:  { name: '1时加速符', icon: '⚡', desc: '立即缩短1小时建筑/造兵时间',     cat: 'util', seconds: 3600  },
      speedUp5h:  { name: '5时加速符', icon: '⚡', desc: '立即缩短5小时建筑/造兵时间',     cat: 'util', seconds: 18000 },
      speedUp12h: { name: '12时加速符',icon: '⚡', desc: '立即缩短12小时建筑/造兵时间',    cat: 'util', seconds: 43200 },
      speedUp24h: { name: '24时加速符',icon: '⚡', desc: '立即缩短24小时建筑/造兵时间',    cat: 'util', seconds: 86400 },
      speedUp36h: { name: '36时加速符',icon: '⚡', desc: '立即缩短36小时建筑/造兵时间',    cat: 'util', seconds: 129600},
      speedUp48h: { name: '48时加速符',icon: '⚡', desc: '立即缩短48小时建筑/造兵时间',    cat: 'util', seconds: 172800},
      speedUp72h: { name: '72时加速符',icon: '⚡', desc: '立即缩短72小时建筑/造兵时间',    cat: 'util', seconds: 259200},
      shield:    { name: '护盾',     icon: '🛡️', desc: '使用后8小时免受玩家攻击',         cat: 'util' },
      marchOrd:  { name: '行军令',   icon: '🚩', desc: '行军速度+50%,持续1小时',           cat: 'util' },
      populationOrder: { name: '人口动员令', icon: '👥', desc: '使用后立即增加500空闲人口,不超过人口上限', cat: 'util' },
      annivPack: { name: '周年庆大礼', icon: '🎉', desc: '周年庆礼包',                         cat: 'gift' },

      // —— 晋升珠宝（9 种珠宝，野地采集产出，用于军衔任务晋升）——
      gem_pearl:      { name: '珍珠',   icon: '⚪', desc: '稀有天然珍珠，野地采集获得，用于晋升军衔', cat: 'jewelry' },
      gem_coral:      { name: '珊瑚',   icon: '🪸', desc: '红润天然珊瑚，野地采集获得，用于晋升军衔', cat: 'jewelry' },
      gem_glaze:      { name: '琉璃',   icon: '🔮', desc: '晶莹剔透琉璃，野地采集获得，用于晋升军衔', cat: 'jewelry' },
      gem_amber:      { name: '琥珀',   icon: '🍯', desc: '温润千年琥珀，野地采集获得，用于晋升军衔', cat: 'jewelry' },
      gem_agate:      { name: '玛瑙',   icon: '🟤', desc: '珍贵斑斓玛瑙，野地采集获得，用于晋升军衔', cat: 'jewelry' },
      gem_crystal:    { name: '水晶',   icon: '💎', desc: '璀璨高纯水晶，野地采集获得，用于晋升军衔', cat: 'jewelry' },
      gem_jadeite:    { name: '翡翠',   icon: '🟢', desc: '翠绿极品翡翠，野地采集获得，用于晋升军衔', cat: 'jewelry' },
      gem_jade:       { name: '玉石',   icon: '🪨', desc: '温润无瑕美玉，野地采集获得，用于晋升军衔', cat: 'jewelry' },
      gem_nightpearl: { name: '夜明珠', icon: '🌟', desc: '绝世璀璨夜明珠，高级野地采集获得，用于晋升将官军衔', cat: 'jewelry' }
    },
    historicalOfficers: [
      { name: '隆美尔', fullName: '埃尔温·隆美尔', birth: 1891, death: 1944, nation: '德国', rank: '陆军元帅', bio: '绰号"沙漠之狐"。二战期间率领非洲军团在北非战场屡创英军，以机动战术闻名于世。后因卷入刺杀希特勒事件被迫服毒自尽。' },
      { name: '朱可夫', fullName: '格奥尔基·朱可夫', birth: 1896, death: 1974, nation: '苏联', rank: '苏联元帅', bio: '二战苏军最高统帅部副统帅。指挥莫斯科保卫战、斯大林格勒战役、库尔斯克会战和柏林战役，被誉为"胜利元帅"，是击败纳粹德国的关键人物。' },
      { name: '巴顿', fullName: '乔治·巴顿', birth: 1885, death: 1945, nation: '美国', rank: '四星上将', bio: '美国第三集团军司令。以勇猛果敢的装甲战术著称，率部横扫法国、德国，是盟军推进最快的将领。战后因车祸殉职。' },
      { name: '曼施坦因', fullName: '埃里希·冯·曼施坦因', birth: 1887, death: 1973, nation: '德国', rank: '陆军元帅', bio: '二战德军最杰出的战略家之一。策划了入侵法国的"黄色方案"（曼施坦因计划），在东线指挥克里米亚战役和哈尔科夫反击战。' },
      { name: '古德里安', fullName: '海因茨·古德里安', birth: 1888, death: 1954, nation: '德国', rank: '上将', bio: '"闪击战之父"，德国装甲兵创始人。著有《注意！坦克》，奠定了现代装甲战理论。率部在波兰和法国战役中大放异彩。' },
      { name: '麦克阿瑟', fullName: '道格拉斯·麦克阿瑟', birth: 1880, death: 1964, nation: '美国', rank: '五星上将', bio: '太平洋战区盟军最高司令。主导"蛙跳战术"逐岛反攻，战后主持日本重建。朝鲜战争中指挥仁川登陆，名垂青史。' },
      { name: '蒙哥马利', fullName: '伯纳德·蒙哥马利', birth: 1887, death: 1976, nation: '英国', rank: '陆军元帅', bio: '英国第八集团军司令。在阿拉曼战役中击败隆美尔的非洲军团，扭转北非战局。后参与诺曼底登陆和欧洲西北部战役。' },
      { name: '山本五十六', fullName: '山本五十六', birth: 1884, death: 1943, nation: '日本', rank: '海军大将', bio: '日本联合舰队司令长官。策划偷袭珍珠港，重创美国太平洋舰队。1943年座机被美军击落殒命，是日本海军的灵魂人物。' },
      { name: '邓尼茨', fullName: '卡尔·邓尼茨', birth: 1891, death: 1980, nation: '德国', rank: '海军元帅', bio: '德国海军潜艇部队创始人，首创"狼群战术"。希特勒自杀后曾短暂担任德国总统，主导投降。' },
      { name: '艾森豪威尔', fullName: '德怀特·艾森豪威尔', birth: 1890, death: 1969, nation: '美国', rank: '五星上将', bio: '盟军远征军最高司令。统筹策划诺曼底登陆，协调英美联军在欧洲战场的全局战略。战后当选美国第34任总统。' },
      { name: '崔可夫', fullName: '瓦西里·崔可夫', birth: 1900, death: 1982, nation: '苏联', rank: '苏联元帅', bio: '第62集团军司令。在斯大林格勒战役中死守城市，与德军逐屋争夺，被誉为"斯大林格勒的救星"。后率部攻入柏林。' },
      { name: '莫德尔', fullName: '瓦尔特·莫德尔', birth: 1891, death: 1945, nation: '德国', rank: '陆军元帅', bio: '德军"防御之狮"。在东线多次组织成功防御，延迟苏军推进。1945年鲁尔包围战中兵败自尽，希特勒称之为"最忠诚的元帅"。' },
      { name: '尼米兹', fullName: '切斯特·尼米兹', birth: 1885, death: 1966, nation: '美国', rank: '五星上将', bio: '美国太平洋舰队总司令。中途岛海战中以少胜多击沉四艘日军航母，扭转太平洋战局。潜艇出身的他被誉为"海上骑士"。' },
      { name: '华西列夫斯基', fullName: '亚历山大·华西列夫斯基', birth: 1895, death: 1977, nation: '苏联', rank: '苏联元帅', bio: '苏军总参谋长。参与策划莫斯科反攻、斯大林格勒合围和库尔斯克会战等重大战役，是苏军最高统帅部的核心智囊。' },
      { name: '隆美尔', fullName: '埃尔温·隆美尔', birth: 1891, death: 1944, nation: '德国', rank: '陆军元帅', bio: '绰号"沙漠之狐"。二战期间率领非洲军团在北非战场屡创英军，以机动战术闻名于世。后因卷入刺杀希特勒事件被迫服毒自尽。' }
    ],

    militaryRanks: [
      { tier: 1,  name: '列兵',   prestige: 0,       baseCap: 1000,  reqGems: {} },
      { tier: 2,  name: '上等兵', prestige: 200,     baseCap: 1500,  reqGems: { gem_pearl: 3 } },
      { tier: 3,  name: '下士',   prestige: 500,     baseCap: 2000,  reqGems: { gem_pearl: 5, gem_coral: 2 } },
      { tier: 4,  name: '中士',   prestige: 1000,    baseCap: 2600,  reqGems: { gem_pearl: 8, gem_coral: 4, gem_glaze: 2 } },
      { tier: 5,  name: '上士',   prestige: 2000,    baseCap: 3300,  reqGems: { gem_coral: 6, gem_glaze: 4, gem_amber: 2 } },
      { tier: 6,  name: '军士长', prestige: 3500,    baseCap: 4100,  reqGems: { gem_glaze: 8, gem_amber: 5, gem_agate: 2 } },
      { tier: 7,  name: '准尉',   prestige: 5500,    baseCap: 5000,  reqGems: { gem_amber: 8, gem_agate: 5, gem_crystal: 2 } },
      { tier: 8,  name: '少尉',   prestige: 8000,    baseCap: 6000,  reqGems: { gem_agate: 8, gem_crystal: 5, gem_jadeite: 2 } },
      { tier: 9,  name: '中尉',   prestige: 15000,   baseCap: 7200,  reqGems: { gem_crystal: 8, gem_jadeite: 5, gem_jade: 2 } },
      { tier: 10, name: '上尉',   prestige: 25000,   baseCap: 8600,  reqGems: { gem_jadeite: 8, gem_jade: 5, gem_nightpearl: 1 } },
      { tier: 11, name: '少校',   prestige: 45000,   baseCap: 10200, reqGems: { gem_jade: 8, gem_nightpearl: 2, gem_pearl: 15 } },
      { tier: 12, name: '中校',   prestige: 80000,   baseCap: 12000, reqGems: { gem_nightpearl: 4, gem_coral: 15, gem_glaze: 12 } },
      { tier: 13, name: '上校',   prestige: 150000,  baseCap: 14000, reqGems: { gem_amber: 15, gem_agate: 12, gem_crystal: 10 } },
      { tier: 14, name: '大校',   prestige: 300000,  baseCap: 16200, reqGems: { gem_crystal: 15, gem_jadeite: 12, gem_jade: 10 } },
      { tier: 15, name: '少将',   prestige: 600000,  baseCap: 17500, reqGems: { gem_jadeite: 18, gem_jade: 15, gem_nightpearl: 6 } },
      { tier: 16, name: '中将',   prestige: 1200000, baseCap: 18800, reqGems: { gem_jade: 20, gem_nightpearl: 10, gem_crystal: 15, gem_pearl: 20 } },
      { tier: 17, name: '上将',   prestige: 2500000, baseCap: 20000, reqGems: { gem_nightpearl: 15, gem_jade: 25, gem_jadeite: 25, gem_agate: 20 } }
    ]
  };

  G.getMilitaryRankTierInfo = function (tier) {
    var ranks = G.DATA.militaryRanks;
    var idx = Math.max(1, Math.min(ranks.length, parseInt(tier, 10) || 1)) - 1;
    var cur = ranks[idx];
    var next = idx < ranks.length - 1 ? ranks[idx + 1] : null;
    return {
      tier: cur.tier,
      name: cur.name,
      baseCap: cur.baseCap,
      minPrestige: cur.prestige,
      nextTier: next ? next.tier : null,
      nextName: next ? next.name : '',
      nextBaseCap: next ? next.baseCap : null,
      nextPrestige: next ? next.prestige : null,
      reqGems: next ? next.reqGems : {},
      isMax: !next
    };
  };

  G.getMilitaryRankInfo = function (tierOrPrestige) {
    var ranks = G.DATA.militaryRanks;
    if (typeof tierOrPrestige === 'number' && tierOrPrestige >= 1 && tierOrPrestige <= ranks.length && Number.isInteger(tierOrPrestige)) {
      return G.getMilitaryRankTierInfo(tierOrPrestige);
    }
    // Fallback if given prestige
    var pts = tierOrPrestige || 0;
    var curIdx = 0;
    for (var i = ranks.length - 1; i >= 0; i--) {
      if (pts >= ranks[i].prestige) {
        curIdx = i;
        break;
      }
    }
    return G.getMilitaryRankTierInfo(curIdx + 1);
  };
})(window.Game);
