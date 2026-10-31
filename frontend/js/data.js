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
      food:  { name: '粮食', icon: 'img/resources/models/food.webp', },
      steel: { name: '钢铁', icon: 'img/resources/models/steel.webp', },
      oil:   { name: '石油', icon: 'img/resources/models/oil.webp', },
      rare:  { name: '稀矿', icon: 'img/resources/models/rare.webp', },
      gold:  { name: '黄金', icon: 'img/resources/models/gold.webp', },
      diamond:{ name: '钻石', icon: '💎', }
    },
    // 本地只保留名称、图标与说明；数值和限制由后端目录安装。
    buildings: {
      prisoner_camp: { name: '战俘营', desc: '每级收容2000战俘，黄金犒赏后可招募归队', cat: 'army'},
      command:      { name: '市政厅',   desc: '城市枢纽，决定建筑等级与本城野地上限（基础5＋等级）', cat: 'core'},
      house:        { name: '民居',   desc: '每级增加1200平民人口容量，平民可用于征兵；不限制已有军队数量或单队带兵上限', cat: 'core'},
      factory:      { name: '军工厂', desc: '生产步兵、卡车、装甲车、侦查装甲车、突击炮、反坦克火箭与特种兵；等级提高生产速度与并行数',             cat: 'army'},
      lightfactory: { name: '轻装战车厂', desc: '生产轻型坦克；等级提高生产速度与并行数',              cat: 'army'},
      heavyfactory: { name: '重装战车厂', desc: '生产重型坦克；等级提高生产速度与并行数',  cat: 'army'},
      airport:      { name: '空军基地',   desc: '生产侦察机、战斗机、轰炸机和运输机；等级提高生产速度与并行数', cat: 'army'},
      port:         { name: '军港',   desc: '沿海城市海军配套设施；本城造舰同时需要军港与造船厂，军港不直接加速造舰',                  cat: 'army'},
      shipyard:     { name: '造船厂',   desc: '建造海军舰船，等级提升生产速度与并行数，需本城军港', cat: 'army'},
      academy:      { name: '军校', desc: '招募军官；普通刷新整批出现五星的基础概率每级增加0.3个百分点，可与军事理论相加，每批最多1名五星',                  cat: 'core'},
      headquarters: { name: '司令部', desc: '任命司令官与提升出征队伍上限(基础5支+每级1支)，四项属性增益本城全部战斗', cat: 'core'},
      staff:        { name: '作战参谋部', desc: '军事指挥、军官管理与高级科技', cat: 'core'},
      // 一级基准产出：粮食/钢铁均为600/小时，石油/稀矿均为500/小时。
      farm:         { name: '农田',     desc: '每小时产出粮食',            cat: 'res', produces: 'food'},
      refinery:     { name: '炼钢厂',   desc: '每小时产出钢铁',            cat: 'res', produces: 'steel'},
      oilfield:     { name: '石油基地', desc: '每小时产出石油',            cat: 'res', produces: 'oil'},
      raremine:     { name: '稀矿厂',   desc: '每小时产出稀矿',            cat: 'res', produces: 'rare'},
      depot:        { name: '军需仓库', desc: '每级为粮食、钢铁、石油、稀矿各提供1000掠夺保护额度，可叠加仓储技术；不增加生产或库存上限', cat: 'res'},
      lab:          { name: '军工科技研发中心', desc: '升级解锁更多科技，并提高可研究等级上限；不加速科研', cat: 'core'},
      radar:        { name: '城防雷达', desc: '地图扫描半径每级增加1格，支撑侦察与雷达预警科研；本城基本来袭提醒无需此建筑，建筑不直接增加战斗属性或联防覆盖', cat: 'core'},
      guild_radar:  { name: '联防雷达', desc: '开启后，敌对行军路线经过覆盖区且威胁团友即预警，无需等待部队进入；覆盖半径每级1格，情报明细由雷达预警科技决定。无需加入军团即可建造和开启，未入团时暂无区域军情', cat: 'core'},
      wall:         { name: '城防',   desc: '征服战须先清除守军再破墙；每级墙体生命+250000、防御+20，四类工事共享容量+3000', cat: 'def'},
      apron:        { name: '战备机场', desc: '本城出发空军世界行军速度+3%/级，与喷气推进相加；不提高战场移动速度', cat: 'def'},
      transit:      { name: '战地兵站',   desc: '本城粮食、钢铁、石油、稀矿自然产量+3%/级；不影响黄金税收或野地采集', cat: 'res'},
      hospital:     { name: '战地医院', desc: '支撑医疗技术研发并提供伤兵营入口；医院等级不直接提高伤兵比例、治疗速度或降低费用', cat: 'army'},
      liaison:      { name: '联络中心', desc: '联防雷达建设及雷达预警等科研的配套前置；不直接解锁外交权限或增加情报明细',       cat: 'core'},
      exchange:     { name: '军需物资中转站', desc: '转换四种基础资源并进行市场交易；每级提高兑换比例与挂单数，降低交易税率',   cat: 'res'}
    },

    forts: {
      bunker:   { name: '碉堡', history: '二战防御体系中的固定掩体；英国曾在海岸部署碉堡，并配合反坦克障碍和铁丝网构筑防线。', desc: '坚固掩体,反步兵,近程高血防', strongVs: 'infantry', cat: 'fort', autoAdvance: false },
      howitzer: { name: '榴弹炮', history: '二战时期炮兵火力支援的重要装备类别；英军25磅炮兼具野战炮与榴弹炮用途，可采用高仰角射击。', desc: '远程压制,反步兵与建筑',     strongVs: 'infantry', cat: 'fort', autoAdvance: false },
      antitank: { name: '反坦克炮', history: '二战时期用于对抗坦克的专用火炮。英军先后使用6磅和17磅反坦克炮，6磅炮在步兵单位中服役至战争结束。', desc: '穿甲火力,反装甲',           strongVs: 'ltank',    cat: 'fort', autoAdvance: false },
      flak:     { name: '防空炮', history: '二战时期应对空袭的重要防御装备。博福斯防空炮由美国、澳大利亚等盟国广泛生产，用于对空防御。', desc: '对空火力,反空军',           strongVs: 'fighter',  cat: 'fort', autoAdvance: false }
    },

    // 与服务端 BattleRules 对应的编成说明；战斗由服务端结算。
    combatRoles: {
      infantry: '低成本步兵，适合人海冲锋；每人基础负重10，可少量搬运物资；基础射程160，贴脸（距离为0）时主动及余伤攻击伤害×2，反击不享受加成；攻坚需重武器支援',
      truck: '地面后勤；载重500，不主动冲锋',
      recon_car: '地面侦查与作战支援；可侦查敌情、拦截地面侦查并参与进攻和驻防。耐久与防空装甲车相当，对空仅具备基础自卫能力',
      armored: '反步兵与机动防空；对战斗机、轰炸机均×2.5，对步兵类无额外克制倍率，惧怕轻坦',
      ltank: '机动装甲支援；依靠直射火力与防护作战，面对远程反坦克火箭需协同支援',
      htank: '前排重装盾牌；基础移速4，以高耐久承受地面火力，面对反坦克火箭与轰炸机需协同支援',
      assault: '远程多用途火力与攻坚支援，惧怕轻坦、特种兵和反坦克火箭',
      rocket: '远程对地；基础射程1600，对轻坦、重坦、防空装甲车、侦查装甲车、突击炮均×3，对空攻击11.5，具备有限防空自卫能力，对其他目标无额外克制倍率',
      scout: '侦察与反侦察；对空自卫为主，其他火力极弱，不主动冲锋',
      special: '工事破袭；攻坚188略高于反坦克火箭，机动快但耐久低，接近目标时需避免远程火力压制',
      fighter: '制空拦截；依靠对空64与350射程交战，可压制防空薄弱的反坦克火箭',
      bomber: '对地、反舰与攻坚轰炸；攻坚429，空战能力弱，依赖战斗机护航',
      transport: '空中后勤；载重800，支持跨海运兵，保留微弱自卫火力',
      destroyer: '舰队护航与防空；400射程可在潜艇接近前开火，需避免战列舰远程压制',
      sub: '近距反舰；对海66、射程100，其他领域仅保留最低自卫火力',
      battleship: '远程反舰与对岸炮击；基础射程1600、对地91、对空100、对海185、防御100、生命1050，需舰队协同掩护',
      carrier: '远程制空与航空支援；基础射程1900、对地82、对空125、对海100、防御120、生命1300，侧重舰队防空与持续支援',
      bunker: '固定地面防御；高生命与近程火力阻滞接近部队，对空与对海火力极弱',
      howitzer: '远程地面压制与岸防；1850射程，对空火力极弱',
      antitank: '远程地面与岸防火力；1850射程、对地42，对空火力极弱',
      flak: '固定防空火力，对地和对海较弱，攻坚能力极弱'
    },

    // 名称采用真实二战装备/部队原型；history 仅用于介绍，数值仍由游戏独立平衡，来源见 docs/UNIT_HISTORY_20260918.md。
    // marchOil 为每单位每 100 格油耗，marchFood 为每单位每 5 分钟行军粮耗；food 为城内每小时耗粮，征召预备粮另按兵种珍贵程度定价。
    // 征召造价与后端同步；战列舰四资源（含粮食）为本轮火箭车的20倍，航母为战列舰的2倍；方案见 docs/balance/unit-resource-costs-20261019.md。
    // 2026-10-29人口定稿：重火力5，主力舰20/100；完整对照见 docs/balance/unit-population-costs-20261029.md。
    units: {
      // 普通步兵可少量自携物资，仍按战斗兵种执行作战指令；卡车负责大宗搬运。
      infantry:  { name: '步兵-加兰德步枪兵（M1）', history: '美国｜装备M1加兰德半自动步枪的步兵，二战美军的代表性步兵装备。',     cat: 'inf',  build: 'factory',  strongVs: null,           branch: 'land', logistic: false, },
      truck:     { name: '卡车-十轮大卡（CCKW-353）', history: '美国｜GMC六轮驱动运输卡车，承担盟军兵员与物资运输。',     cat: 'inf',  build: 'factory',  strongVs: null,           branch: 'land', logistic: true, autoAdvance: false },
      armored: { name: '装甲车-猎鹿犬防空型（T17E2）', history: '美国制造、英军使用｜猎鹿犬的双联重机枪防空型，为地面部队提供机动掩护。', cat: 'arm', build: 'factory', strongVs: null, branch: 'land' },
      recon_car: { name: '侦查装甲车-灰狗（M8）', history: '美国｜M8灰狗六轮装甲侦察车，配备主炮塔和无线电，用于侦察、警戒与地面作战支援。', cat: 'arm', build: 'factory', strongVs: null, branch: 'land' },
      ltank: { name: '轻型坦克-斯图亚特（M5A1）', history: '美国｜斯图亚特系列轻型坦克，以机动侦察与步兵支援为主要任务。', cat: 'arm', build: 'lightfactory', strongVs: 'armored', branch: 'land' },
      htank: { name: '重型坦克-斯大林（IS-2）', history: '苏联｜装备122毫米主炮的高机动重型坦克（速度4），用于快速突破防线与阵地掩护。', cat: 'arm', build: 'heavyfactory', strongVs: 'ltank', branch: 'land' },
      assault: { name: '突击炮-自行加榴炮（ISU-152）', history: '苏联｜装备152毫米加榴炮的重型自行火炮，用于摧毁工事和提供突击支援。', cat: 'arm', build: 'factory', strongVs: 'bunker', branch: 'land' },
      rocket: { name: '反坦克火箭-喀秋莎（BM-13）', history: '苏联｜车载多管火箭炮，1941年投入作战，以密集齐射实施火力覆盖。', cat: 'arm', build: 'factory', strongVs: 'htank', branch: 'land' },
      // 侦察机归入空军基地，避免军工厂承担空军产能。
      scout:     { name: '侦察机-闪电侦察型（F-5）', history: '美国｜由P-38闪电改装的照相侦察机，以航空摄影获取战场情报。',   cat: 'air',  build: 'airport',  strongVs: null,           branch: 'air', autoAdvance: false },
      special: { name: '特种兵-英国突击队（Commando）', history: '英国｜1940年组建的突袭部队，接受渗透、爆破与两栖突击训练。', cat: 'inf', build: 'factory', strongVs: 'howitzer', branch: 'land' },
      fighter: { name: '战斗机-野马（P-51）', history: '美国｜北美航空研制的战斗机，二战中承担远程护航与制空任务。', cat: 'air', build: 'airport', strongVs: 'bomber', branch: 'air' },
      bomber: { name: '轰炸机-飞行堡垒（B-17G）', history: '美国｜波音四发重型轰炸机，主要执行编队轰炸并以多处机枪阵位自卫。', cat: 'air', build: 'airport', strongVs: 'htank', branch: 'air' },
      transport: { name: '运输机-空中列车（C-47）', history: '美国｜由DC-3发展而来的军用运输机，执行空运、空投与伞兵运输。',   cat: 'air',  build: 'airport',  strongVs: null,           branch: 'air', logistic: true, autoAdvance: false },
      destroyer: { name: '驱逐舰-弗莱彻级（Fletcher）', history: '美国｜二战主力舰队驱逐舰，承担护航、防空、反潜与水面作战。', cat: 'nav', build: 'shipyard', strongVs: 'sub', branch: 'sea' },
      sub: { name: '潜艇-小鲨鱼级（Gato）', history: '美国｜二战远洋柴电潜艇，以鱼雷攻击敌方舰船并执行海上破交。', cat: 'nav', build: 'shipyard', strongVs: 'battleship', branch: 'sea' },
      battleship: { name: '战列舰-衣阿华级（Iowa）', history: '美国｜装备406毫米主炮的高速战列舰，承担舰队作战与对岸炮击。', cat: 'nav', build: 'shipyard', strongVs: 'destroyer', branch: 'sea' },
      carrier: { name: '航母-埃塞克斯级（Essex）', history: '美国｜二战舰队航空母舰，以舰载机执行制空、对海与对地打击。', cat: 'nav', build: 'shipyard', strongVs: 'bomber', branch: 'sea' }
    },

    // 日军专属兵种名称与历史原型（应用于 NPC 日寇城与流寇据点，属性与盟军完全一致）
    japaneseUnits: {
      infantry:  { name: '步兵-三八式步枪兵（Type 38）', history: '日本｜装备三八式步枪的日军常备步兵，二战日军核心步兵编制。' },
      truck:     { name: '卡车-九四式六轮卡车（Type 94）', history: '日本｜五十铃前身研制的九四式六轮军用卡车，主力战备运输车。' },
      armored:   { name: '装甲车-九三式装甲车（Type 93）', history: '日本｜隅田九三式轮式装甲车，配多挺机枪承担机动掩护与防空。' },
      recon_car: { name: '侦查装甲车-九二式重装甲车（Type 92）', history: '日本｜九二式重装甲车是骑兵部队使用的履带式侦察装备，承担侦察与警戒。' },
      ltank:     { name: '轻型坦克-九五式轻战（Ha-Go）', history: '日本｜三菱九五式轻型战车，二战日军产量最高、各战线广泛参战的主力轻坦。' },
      htank:     { name: '重型坦克-四式中战（Chi-To）', history: '日本｜四式中型战车（Chi-To），配备长管75毫米火炮的日军决战重装甲主力。' },
      assault:   { name: '突击炮-一式自走炮（Ho-Ni I）', history: '日本｜一式自走炮，九七式底盘搭载75毫米野炮，用于工事攻坚与突击火力。' },
      rocket:    { name: '反坦克火箭-四式喷进炮（Type 4）', history: '日本｜四式火箭喷进炮，以密集火箭齐射实施阵地压制覆盖。' },
      scout:     { name: '侦察机-百式司令部侦察机（Ki-46）', history: '日本｜三菱百式司侦（Ki-46），具备极高巡航速度的远程战略战术侦察机。' },
      special:   { name: '特种兵-挺进战队（义烈空挺队）', history: '日本｜陆军挺进联队/义烈空挺队，专职敌后机场突袭与要塞爆破特战。' },
      fighter:   { name: '战斗机-零式战斗机（A6M5）', history: '日本｜三菱零式舰上战斗机五二型，二战日军最具代表性的主力空优战机。' },
      bomber:    { name: '轰炸机-一式陆攻（G4M2）', history: '日本｜三菱一式陆上攻击机，承担远程重型轰炸与对舰攻击任务。' },
      transport: { name: '运输机-一式双发输送机（Ki-54）', history: '日本｜立川一式双发输送机，日军战线兵员与军需空运主力。' },
      destroyer: { name: '驱逐舰-阳炎级（Kagero）', history: '日本｜配备九三式氧气鱼雷的主力舰队驱逐舰，执行护航与鱼雷突击。' },
      sub:       { name: '潜艇-伊号潜舰（I-400）', history: '日本｜特型潜舰（伊-400级），具备远洋破交与特种潜航能力的大型潜艇。' },
      battleship:{ name: '战列舰-大和级（Yamato）', history: '日本｜装备460毫米巨炮的超级战列舰，象征日军舰队决战主力巨舰。' },
      carrier:   { name: '航母-翔鹤级（Shokaku）', history: '日本｜翔鹤级舰队航空母舰，具备完备的舰载机编队远程打击能力。' }
    },

    techs: {
      military_theory: { name: '军事理论', branch: '军事', desc: '普通军校刷新整批出现五星名将的概率每级增加0.3个百分点，满级额外增加3个百分点；与军校基础概率相加，每批最多1名五星，不改变已生成名单或道具保底。', affect: 'academy_chance' },
      wall_reinforcement: { name: '城墙加固', branch: '军事', desc: '当前城墙生命与防御 +5%/级，最高各 +50%；无城墙时不产生属性，不强化驻军或城防工事。', affect: 'wall_stats' },
      prisoner_education: { name: '战俘收编', branch: '后勤', desc: '讨伐其他玩家并获胜后，按敌方各兵种损失数量计算战俘。俘获比例每级增加 0.1 个百分点，最高 1%，不足 1 个向下取整。需战俘营有空位，敌方已回收的伤兵不能被俘获。', affect: 'capture_permille' },
      attack_tech:   { name: '攻击科技',   branch: '军事', desc: '全军攻击 +5%/级，最高 +50%', affect: 'atk_all' },
      defense_tech:  { name: '防御科技',   branch: '军事', desc: '全军防御 +5%/级，最高 +50%', affect: 'def_all' },
      weapon_range:  { name: '武器射程',   branch: '军事', desc: '全军武器射程 +5%/级，最高 +50%', affect: 'range_all' },
      cmd_hp:        { name: '军队生命',   branch: '军事', desc: '军队生命 +5%/级，最高 +50%', affect: 'hp_all' },
      // 行动火力独立于武器攻击，攻守双方的主动、余伤和反击共用同一科技规则。
      fire_density:  { name: '火力密度', branch: '军事', desc: '全军行动火力 +5%/级，最高 +50%；与攻击科技独立相乘，主动攻击、余伤攻击和反击均生效。', affect: 'firepower_all' },
      log_load:      { name: '负重科技',   branch: '后勤', desc: '所有兵种负重 +5%/级，最高 +50%；运输机运力同样生效。', affect: 'load' },
      arm_engine:    { name: '燃烧引擎',   branch: '机动', desc: '陆军世界行军与战场移动速度 +5%/级，最高 +50%，包含步兵与车辆', affect: 'spd_arm' },
      air_engine:    { name: '喷气推进',   branch: '机动', desc: '空军世界行军与战场移动速度 +5%/级，最高 +50%；战备机场只额外加成世界行军', affect: 'spd_air' },
      nav_engine:    { name: '舰船动力',   branch: '机动', desc: '海军世界行军与战场移动速度 +5%/级，最高 +50%', affect: 'spd_nav' },
      prod_food: { name: '粮食产量', branch: '后勤', desc: '城内粮食产量 +5%/级，最高 +50%，不影响野地采集。', affect: 'res_food' },
      prod_steel: { name: '钢铁产量', branch: '后勤', desc: '城内钢铁产量 +5%/级，最高 +50%，不影响野地采集。', affect: 'res_steel' },
      prod_oil: { name: '石油产量', branch: '后勤', desc: '城内石油产量 +5%/级，最高 +50%，不影响野地采集。', affect: 'res_oil' },
      prod_rare: { name: '稀矿产量', branch: '后勤', desc: '城内稀矿产量 +5%/级，最高 +50%，不影响野地采集。', affect: 'res_rare' },
      log_warehouse: { name: '仓储技术',   branch: '后勤', desc: '本城军需仓库掠夺保护额度 +5%/级，最高 +50%；不增加资源储量上限。', affect: 'cap' },
      log_food:      { name: '军需补给',   branch: '后勤', desc: '养兵耗粮减少 5%/级，最高减少 50%。', affect: 'food_save' },
      log_train:     { name: '训练加速',   branch: '后勤', desc: '兵种生产速度 +5%/级，最高 +50%，与生产设施的速度加成相加。征召数量仍受平民人口与资源限制。', affect: 'train' },
      log_build:     { name: '建筑加速',   branch: '后勤', desc: '建筑新建和升级的资源消耗、基础工期均减少 5%/级，满级各减少 50%。', affect: 'build' },
      log_medical:   { name: '医疗技术',   branch: '后勤', desc: '战损部队可回收为伤兵的比例每级增加 5 个百分点，本科技最高提供 50%，可叠加参战军官的急救技能。伤兵需在 7 天内消耗黄金或钻石治疗后归队。', affect: 'medical' },
      recon_level:   { name: '侦察技术',   branch: '侦察', desc: '侦查交火时，双方各按自身等级每级增加5点先手值，最高50点，每个交火回合生效；不增加地图扫描半径。成功侦查后城市情报Lv.1—10依次解锁：城防类型、城防数量等级、精确守军、守城将领、建筑等级、驻城将领人数、账号科技、可掠夺资源测算、驻城将领档案、战力评估。野地Lv.1起可查看精确守军。', affect: 'recon' },
      recon_radar:   { name: '雷达预警',   branch: '侦察', desc: '拦截敌方侦查的首回合，守方每级增加2点先手值，最高20点。Lv.1—10依次解锁来源玩家与行动、出发坐标、军种构成、兵力区间、兵种名称、精确总兵力、精确编成、带兵将领、战斗科技、司令官加成。不公开带兵将领的有效属性和技能。需对应侦查部队驻防才能拦截。', affect: 'radar' },
    },
    officerNames: [
      // 中国远征军及抗日名将
      '孙立人', '戴安澜', '卫立煌', '杜聿明', '廖耀湘', '郑洞国', '宋希濂', '霍揆彰', '齐学启', '李宗仁', '薛岳', '张自忠', '梁思成',
      // 中缅印战区 (CBI) 盟军统帅
      '史迪威', '陈纳德', '斯利姆',
      // 二战盟军将领
      '麦克阿瑟', '尼米兹', '艾森豪威尔', '巴顿', '蒙哥马利', '布雷德利', '朱可夫', '华西列夫斯基', '崔可夫', '切尔尼亚霍夫斯基', '施瓦茨科普夫',
      // 二战欧洲其他名将
      '隆美尔', '古德里安', '曼施坦因', '邓尼茨', '莫德尔', '龙德施泰特', '海因里希', '克卢格', '霍特'
    ],
    officerTitles: ['列兵', '上士', '少尉', '中尉', '上尉', '少校', '中校', '上校', '准将', '少将', '中将', '上将'],
    starColor: { 1: '#bbb', 2: '#7fc4ff', 3: '#a070ff', 4: '#ffa84a', 5: '#ffe14a' },

    wildTypes: {
      forest:     { name: '森林',   res: null,     icon: 'img/map/wild-forest-ridge-integrated.webp' },
      hill:       { name: '丘陵',   res: null,     icon: 'img/map/wild-hill-ridge-integrated.webp' },
      swamp:      { name: '沼泽',   res: null,     icon: 'img/map/wild-swamp-deep-integrated.webp' },
      grassland:  { name: '草原',   res: null,     icon: 'img/map/wild-grassland.webp' },
      plains:     { name: '平原',   res: null,     icon: 'img/map/wild-plains-integrated.webp' },
      snow:       { name: '雪地',   res: null,     icon: 'img/map/wild-snow.webp' },
      rock:       { name: '岩石',   res: null,     icon: 'img/map/wild-rock-integrated.webp' },
      grainfield: { name: '粮田',   res: 'food',   icon: 'img/map/wild-grainfield-map-v2.webp' },
      ironworks:  { name: '炼铁厂', res: 'steel',  icon: 'img/map/wild-ironworks-integrated.webp' },
      oil:        { name: '油田',   res: 'oil',    icon: 'img/map/wild-oil-integrated.webp' },
      rarefactory:{ name: '稀矿厂', res: 'rare',   icon: 'img/map/wild-rarefactory-integrated.webp' }
    },

    zones: {},

    world: {
      size: 800,
      quadrantSize: 400,
      gapSize: 0,
      viewRadius: 8,
      banditNames: ['寇'],
      playerCityNames: ['钢铁洪流', '虎式之巢', '苍穹之眼', '深海利剑', '雷霆要塞', '孤狼营地', '铁血堡垒', '风暴前线', '暗夜哨站', '烈焰军团']
    },
    // 推荐仅用于介绍搭配思路，不限制技能的自由组合。
    officerSkillCombinations: [
      { direction: '持续攻坚', skills: ['ranging', 'pierce', 'suppress'], tactics: '锁定高价值目标，越打越有利' },
      { direction: '残阵反打', skills: ['last_stand', 'counter', 'barrier'], tactics: '保住剩余兵力，承受攻击后反扑' },
      { direction: '远程牵制', skills: ['smoke', 'blitz', 'ranging'], tactics: '利用机动维持距离，持续校射' },
      { direction: '阵地消耗', skills: ['bulwark', 'borrow_armor', 'reprisal'], tactics: '扛住攻击，在周期回合集中反攻' },
      { direction: '多目标清理', skills: ['ricochet', 'execution', 'frenzy'], tactics: '分散压低兵力，再集中清残' },
      { direction: '突破追击', skills: ['blitz', 'execution', 'pursuit'], tactics: '歼灭前排后快速扩大战果' }
    ],

    officerSkills: {
      frenzy:   { name: '全军冲锋', icon: 'img/skills/frenzy.svg', desc: '首次交战回合触发，此后间隔两个回合再次触发（若第4回合首次交战，则在第4、7、10回合生效），全军攻击力额外+50%，仅本回合生效；主动攻击、余伤攻击与反击均适用，不放大师夷长技追加的攻击', cat: 'atk' },
      bulwark:  { name: '坚守阵地', icon: 'img/skills/bulwark.svg', desc: '首次交战回合触发，此后间隔两个回合再次触发（若第4回合首次交战，则在第4、7、10回合生效），全军防御力额外+50%，仅本回合生效', cat: 'def' },
      blitz:    { name: '闪电突击', icon: 'img/skills/blitz.svg', desc: '首次交战回合触发，此后间隔两个回合再次触发（若第4回合首次交战，则在第4、7、10回合生效），战场移动速度+30%，仅本回合生效；新版玩家城市战按各将领所属编队独立起算，首次接战不追溯机动或重排先手，后续生效回合影响机动与先手。野地、侦察、演习及历史战斗沿用首次接战影响同射程先手的原规则。行动顺序按射程优先、同射程速度优先；射程与速度均相同时，奇数回合进攻方先手、偶数回合防守方先手', cat: 'spd' },
      suppress: { name: '火力压制', icon: 'img/skills/suppress.svg', desc: '首次交战回合触发，此后间隔两个回合再次触发（若第4回合首次交战，则在第4、7、10回合生效），降低敌方自身攻击力30%，仅本回合生效，主动攻击、余伤攻击与反击均适用，不削减师夷长技追加的攻击', cat: 'debuff' },
      pierce:   { name: '破甲打击', icon: 'img/skills/pierce.svg', desc: '首次交战回合触发，此后间隔两个回合再次触发（若第4回合首次交战，则在第4、7、10回合生效），无视敌方有效防御30%，仅本回合生效，主动攻击、余伤攻击与反击均适用', cat: 'pierce' },
      leadership:{ name: '三军统帅',icon: 'img/skills/leadership.svg',desc: '指挥官任命时，带兵上限额外+20%', cat: 'mil' },
      supply:   { name: '三军统帅', icon: 'img/skills/supply.svg', desc: '指挥官任命时，带兵上限额外+20%', cat: 'mil' },
      medic:    { name: '战地急救', icon: 'img/skills/medic.svg', desc: '战后伤兵额外回收+15%', cat: 'medic' },
      harvest:  { name: '屯田增产', icon: 'img/skills/harvest.svg', desc: '市长任命时，基础资源产出额外+50%', cat: 'logi' },
      construct:{ name: '工程营造', icon: 'img/skills/construct.svg', desc: '市长任命时，建筑工期缩短20%', cat: 'logi' },
      finance:  { name: '精明理财', icon: 'img/skills/finance.svg', desc: '市长任命时，黄金税收产出额外+20%', cat: 'know' },
      research: { name: '格物致知', icon: 'img/skills/research.svg', desc: '市长任命时，科研速度提升20%', cat: 'know' },
      ration:   { name: '军屯自给', icon: 'img/skills/ration.svg', desc: '市长任命时，全城养兵耗粮降低80%', cat: 'logi' },
      counter:  { name: '绝境反击', icon: 'img/skills/counter.svg', desc: '首次交战回合触发，此后间隔两个回合再次触发（若第4回合首次交战，则在第4、7、10回合生效）；受击存活且射程允许时，以当前剩余兵力总伤害的100%反击；按反击兵种自身对应领域攻击与专项倍率结算，不继承来袭方倍率', cat: 'def' },
      learn:    { name: '师夷长技', icon: 'img/skills/learn.svg', desc: '首次交战回合触发，此后间隔两个回合再次触发（若第4回合首次交战，则在第4、7、10回合生效）；主动攻击时借用交火阶段开始时存活同名敌军对应攻击的30%，单次最高为敌军对应攻击的30%；参考值含攻击科技与军官综合军事属性，不复制专项倍率或临时技能，反击不生效', cat: 'atk' },
      borrow_armor: { name: '借甲御敌', icon: 'img/skills/borrow-armor.svg', desc: '首次交战回合触发，此后间隔两个回合再次触发（若第4回合首次交战，则在第4、7、10回合生效）；受击时借用交火阶段开始时存活同名敌军防御的30%，单次最高为敌军同名兵种防御的30%，仅本回合生效；参考值只含基础防御与防御科技，不包含军官属性或技能', cat: 'def' },
      ranging: { name: '三轮校射', icon: 'img/skills/ranging.svg', desc: '同一兵种连续三个回合主动命中同一编队的同一兵种，第三次攻击额外造成40%伤害；换目标或中断攻击清零，反击不计数', cat: 'atk' },
      barrier: { name: '战地屏障', icon: 'img/skills/barrier.svg', desc: '首次接战回合及此后每隔两个回合，各兵种获得当前总生命值10%的屏障；先吸收伤害再结算伤亡，仅本回合有效，不恢复士兵', cat: 'def' },
      last_stand: { name: '背水奋战', icon: 'img/skills/last-stand.svg', desc: '每回合开始时，本编队某兵种剩余数量低于其开战数量50%，该兵种自身攻击额外+20%；主动攻击和反击均适用，不放大师夷长技追加攻击', cat: 'atk' },
      execution: { name: '残阵收割', icon: 'img/skills/execution.svg', desc: '攻击前目标剩余数量不高于其所属编队本兵种开战数量30%时，本次伤害额外+25%；每次切换目标重新判断', cat: 'atk' },
      smoke: { name: '烟幕遮断', icon: 'img/skills/smoke.svg', desc: '首次接战回合及此后每隔两个回合，各兵种首次主动命中的目标，下一回合战场移动速度降低25%；多个烟幕取最高值，不影响射程、先手或大地图行军', cat: 'debuff' },
      reprisal: { name: '蓄势反攻', icon: 'img/skills/reprisal.svg', desc: '首次接战回合及此后每隔两个回合，各兵种首次主动攻击追加此前两个回合实际承伤的10%；追加伤害不超过本次普通攻击伤害40%，触发后清空，屏障吸收不计入', cat: 'atk' },
      ricochet: { name: '跳弹射击', icon: 'img/skills/ricochet.svg', desc: '首次接战回合及此后每隔两个回合，各兵种首次主动攻击后，向射程内另一合法敌军追加15%攻击强度的跳弹；优先距主目标最近者，按第二目标独立结算，不连锁触发追加效果', cat: 'atk' },
      pursuit: { name: '乘胜追击', icon: 'img/skills/pursuit.svg', desc: '参与歼灭占敌方所属编队开战总人口至少5%的兵种后，下一回合移动速度+30%，首次主动攻击伤害+15%；同回合只触发一次，不影响大地图行军', cat: 'spd' }
    },
    items: {
      expBook:   { name: '经验书',   icon: 'img/items/exp-book.svg', desc: '军官使用,获得10000经验',          cat: 'officer' },
      expBookAdv:{ name: '高级经验书',icon: 'img/items/exp-book-adv.svg', desc: '每本提供100000经验，可批量使用', cat: 'officer' },
      expBookRapid: { name: '极速经验书', icon: 'img/items/exp-book-adv.svg', desc: '每本提供500000经验，可批量使用', cat: 'officer' },
      expBookMax:{ name: '满级经验书',icon: 'img/items/exp-book-max.svg', desc: '使用1本即可将1～99级军官直接升至满级(Lv.100)，补足升级属性点', cat: 'officer' },
      skillBook: { name: '普通技能书', icon: 'img/items/skill-book.svg', desc: '军官使用，随机学习一个未掌握的技能，直接获得满级技能', cat: 'officer' },
      universalSkillBook: { name: '通用技能书', icon: 'img/items/universal-skill-book.svg', desc: '从技能池自选一个未掌握的技能，直接获得满级技能，无需升级', cat: 'officer' },
      loyaltyBox:{ name: '忠诚宝箱', icon: 'img/items/loyalty-box.svg', desc: '军官使用,忠诚度+20',              cat: 'officer' },
      renameCard:{ name: '军官改名卡', icon: 'img/items/rename-card.svg', desc: '为军官更换新名字',              cat: 'officer' },
      cityRenameCard:{ name: '城市改名卡', icon: 'img/items/city-rename-card.svg', desc: '为一座城市更换新名字',        cat: 'util' },
      commanderRenameCard: { name: '统帅改名卡', icon: 'img/items/commander-rename-card.svg', desc: '修改统帅名称，成功消耗1张；首次使用不限时间，之后每次间隔30天（现实时间），暂不在商城出售', cat: 'util' },
      teleportNormal: { name: '普通传送', icon: 'img/items/teleport-normal.svg', desc: '消耗1个，将当前城市随机传送至开放地图的四格空闲陆地', cat: 'util' },
      teleportAdvanced: { name: '高级传送', icon: 'img/items/teleport-advanced.svg', desc: '消耗1个，将当前城市传送至指定坐标，目的地须有完整2×2空闲陆地', cat: 'util' },
      recruitOrd:{ name: '征募令',   icon: 'img/items/recruit-ord.svg', desc: '刷新军校,保底出现一名五星军官',   cat: 'officer' },
      starUp:    { name: '星耀符',   icon: 'img/items/star-up.svg', desc: '升2/3/4/5星失败率10/20/30/60%，失败也消耗1枚', cat: 'officer' },
      goldBox:   { name: '黄金箱',   icon: 'img/resources/models/gold.webp', desc: '开启获得5万黄金',           cat: 'resource' },
      resBox:    { name: '资源箱',   icon: 'img/items/res-box.svg', desc: '开启获得粮钢油稀各5万',           cat: 'resource' },
      foodPack:  { name: '粮食大礼包', icon: 'img/resources/models/food.webp', desc: '开启获得60万粮食', cat: 'resource' },
      steelPack: { name: '钢铁大礼包',icon: 'img/resources/models/steel.webp',desc: '开启获得60万钢铁',               cat: 'resource' },
      oilPack:   { name: '石油大礼包', icon: 'img/resources/models/oil.webp', desc: '开启获得60万石油', cat: 'resource' },
      rarePack:  { name: '稀矿大礼包', icon: 'img/resources/models/rare.webp', desc: '开启获得60万稀矿', cat: 'resource' },
      supplyPack:{ name: '战备补给包',icon: 'img/resources/models/food.webp',desc: '开启获得粮钢油稀各40万',     cat: 'resource' },
      resourcePack100w: { name: '资源大礼包（100万）', icon: 'img/items/res-box.svg', desc: '粮食/钢铁/石油/稀矿各100万', cat: 'resource' },
      resourcePack200w: { name: '资源大礼包（200万）', icon: 'img/items/res-box.svg', desc: '粮食/钢铁/石油/稀矿各200万', cat: 'resource' },
      resourcePack350w: { name: '资源大礼包（350万）', icon: 'img/items/res-box.svg', desc: '粮食/钢铁/石油/稀矿各350万', cat: 'resource' },
      resourcePack500w: { name: '资源大礼包', icon: 'img/items/resource-pack-500w.svg', desc: '粮食/钢铁/石油/稀矿各500万', cat: 'resource' },
      newbiePack: { name: '新手礼包', icon: 'img/items/newbie-pack.svg', desc: '开启获得：粮食×20000、钢铁×20000、石油×10000、稀矿×5000、黄金×3000', cat: 'resource', isBox: true },
      stage_gift_1: { name: '新兵奠基礼包', icon: '🎁', desc: '主线第1阶段通关大奖：粮钢油各2万、稀矿5000、黄金2000、钻石50、10分加速符×5、列兵军事装备箱×1', cat: 'resource', isBox: true },
      stage_gift_2: { name: '拔寨起步礼包', icon: '🎁', desc: '主线第2阶段通关大奖：粮钢油各5万、稀矿1.5万、黄金5000、钻石100、1时加速符×3、普通技能书×2', cat: 'resource', isBox: true },
      stage_gift_3: { name: '整军备战礼包', icon: '🎁', desc: '主线第3阶段通关大奖：粮钢油各15万、稀矿5万、黄金1.2万、钻石200、1时加速符×5、征募令×2、校官军事装备箱×1', cat: 'resource', isBox: true },
      stage_gift_4: { name: '初露锋芒礼包', icon: '🎁', desc: '主线第4阶段通关大奖：粮钢油各50万、稀矿15万、黄金3万、钻石500、5时加速符×3、护盾×1', cat: 'resource', isBox: true },
      stage_gift_5: { name: '百战精锐礼包', icon: '🎁', desc: '主线第5阶段通关大奖：粮钢油各150万、稀矿50万、黄金8万、钻石1000、12时加速符×2、征募令×5、高级经验书×3', cat: 'resource', isBox: true },
      stage_gift_6: { name: '横扫千军礼包', icon: '🎁', desc: '主线第6阶段通关大奖：粮钢油各500万、稀矿150万、黄金20万、钻石2000、24时加速符×2、高级经验书×5', cat: 'resource', isBox: true },
      stage_gift_7: { name: '威震四海礼包', icon: '🎁', desc: '主线第7阶段通关大奖：粮钢油各1500万、稀矿500万、黄金50万、钻石5000、48时加速符×2、元帅军事装备箱×1', cat: 'resource', isBox: true },
      stage_gift_8: { name: '三军统帅大礼包', icon: '👑', desc: '主线第8阶段终极大奖：粮钢油各5000万、稀矿1500万、黄金150万、钻石10000、72时加速符×3、满级经验书×2', cat: 'resource', isBox: true },
      speedUp10m: { name: '10分加速符',icon: 'img/items/speedup-10m.svg', desc: '立即缩短10分钟建筑/造兵时间',     cat: 'util'},
      speedUp1h:  { name: '1时加速符', icon: 'img/items/speedup-1h.svg', desc: '立即缩短1小时建筑/造兵时间',     cat: 'util'},
      speedUp5h:  { name: '5时加速符', icon: 'img/items/speedup-5h.svg', desc: '立即缩短5小时建筑/造兵时间',     cat: 'util'},
      speedUp12h: { name: '12时加速符',icon: 'img/items/speedup-12h.svg', desc: '立即缩短12小时建筑/造兵时间',    cat: 'util'},
      speedUp24h: { name: '24时加速符',icon: 'img/items/speedup-24h.svg', desc: '立即缩短24小时建筑/造兵时间',    cat: 'util'},
      speedUp36h: { name: '36时加速符',icon: 'img/items/speedup-36h.svg', desc: '立即缩短36小时建筑/造兵时间',    cat: 'util'},
      speedUp48h: { name: '48时加速符',icon: 'img/items/speedup-48h.svg', desc: '立即缩短48小时建筑/造兵时间',    cat: 'util'},
      speedUp72h: { name: '72时加速符',icon: 'img/items/speedup-72h.svg', desc: '立即缩短72小时建筑/造兵时间',    cat: 'util'},
      shield2h: { name: '2小时护盾', icon: 'img/items/shield-2h.svg', desc: '本城2小时免受侦查、攻击和宣战，可叠加；期间不能对其他玩家发起军事操作，不保护野地', cat: 'util' },
      shield: { name: '8小时护盾', icon: 'img/items/shield-8h.svg', desc: '本城8小时免受侦查、攻击和宣战，可叠加；期间不能对其他玩家发起军事操作，不保护野地', cat: 'util' },
      shield12h: { name: '12小时护盾', icon: 'img/items/shield-12h.svg', desc: '本城12小时免受侦查、攻击和宣战，可叠加；期间不能对其他玩家发起军事操作，不保护野地', cat: 'util' },
      shield24h: { name: '24小时护盾', icon: 'img/items/shield-24h.svg', desc: '本城24小时免受侦查、攻击和宣战，可叠加；期间不能对其他玩家发起军事操作，不保护野地', cat: 'util' },
      shield72h: { name: '72小时护盾', icon: 'img/items/shield-72h.svg', desc: '本城72小时免受侦查、攻击和宣战，可叠加；期间不能对其他玩家发起军事操作，不保护野地', cat: 'util' },
      basicMarchOrd: { name: '初级行军令', icon: 'img/items/march-ord.svg', desc: '仓库启用：行军速度+10%，持续1小时；情报使用：基础缩短剩余时间10%，指挥官学识提高效果，最高40%，至少保留30秒', cat: 'util' },
      advancedMarchOrd: { name: '高级行军令', icon: 'img/items/march-ord.svg', desc: '仓库启用：行军速度+25%，持续1小时；情报使用：基础缩短剩余时间25%，指挥官学识提高效果，最高55%，至少保留30秒', cat: 'util' },
      marchOrd: { name: '极速行军令', icon: 'img/items/march-ord.svg', desc: '仓库启用：行军速度+50%，持续1小时；情报使用：基础缩短剩余时间50%，指挥官学识提高效果，最高80%，至少保留30秒', cat: 'util' },
      signalJammingOrder: { name: '通讯干扰令', icon: 'img/items/signal-jamming-order.svg', desc: '对本城来袭敌军使用，基础延长50%，学识提高效果，最高70%', cat: 'util' },
      feintOrder: { name: '撤退拖延令', icon: 'img/items/feint-order.svg', desc: '对从本城撤退的敌军使用：基础延长剩余撤退时间50%，指挥官学识提高效果，最高70%', cat: 'util' },
      warSpeedOrder: { name: '宣战加速令', icon: 'img/items/march-ord.svg', desc: '缩短本城宣战备战，基础50%，学识提高效果，最高80%，总备战至少30分钟', cat: 'util' },
      populationOrder: { name: '人口动员令', icon: 'img/items/population-order.svg', desc: '使用后立即增加500空闲人口,不超过人口上限', cat: 'util' },
      emergencyPopulationOrder: { name: '紧急人口动员令', icon: 'img/items/emergency-population-order.svg', desc: '使用后立即增加当前城市人口容量上限30%的空闲人口,不超过人口上限', cat: 'util' },
      mobilizationOrder: { name: '战争动员令', icon: 'img/items/march-ord.svg', desc: '仓库使用后，全账号各城市出征带兵上限提高10%，每张持续24小时；同档累加时长，异档取最高加成', cat: 'util' },
      advancedMobilizationOrder: { name: '高级战争动员令', icon: 'img/items/march-ord.svg', desc: '仓库使用后，全账号各城市出征带兵上限提高25%，每张持续24小时；同档累加时长，异档取最高加成', cat: 'util' },
      rapidMobilizationOrder: { name: '极速战争动员令', icon: 'img/items/march-ord.svg', desc: '仓库使用后，全账号各城市出征带兵上限提高50%，每张持续24小时；同档累加时长，异档取最高加成', cat: 'util' },

    },
    historicalOfficers: [
      // 中国远征军及抗日名将
      { name: '孙立人', fullName: '孙立人', birth: 1900, death: 1990, nation: '中国', rank: '陆军二级上将', bio: '中国远征军新一军军长。仁安羌大捷解救英军，缅北反攻打通滇缅公路，战功赫赫，被西方战地记者誉为"东方隆美尔"、"丛林之狐"。' },
      { name: '戴安澜', fullName: '戴安澜', birth: 1904, death: 1942, nation: '中国', rank: '陆军中将', bio: '中国远征军第200师师长。指挥同古保卫战，以一师抗击日军两师团围攻十二日，毙敌数千，扬威异域；后突围途中中弹殉国，国共两党同表哀悼。' },
      { name: '卫立煌', fullName: '卫立煌', birth: 1897, death: 1960, nation: '中国', rank: '陆军二级上将', bio: '中国远征军司令长官。指挥滇西大反攻战役，率部强渡怒江天险，力克松山、腾冲、龙陵等日军坚固要塞，彻底粉碎日军对大后方的封锁。' },
      { name: '杜聿明', fullName: '杜聿明', birth: 1904, death: 1981, nation: '中国', rank: '陆军中将', bio: '中国远征军第一路副司令长官。曾指挥昆仑关大捷重创日军精锐，入缅作战率主力浴血同古，后率部艰难撤出野人山。' },
      { name: '廖耀湘', fullName: '廖耀湘', birth: 1906, death: 1968, nation: '中国', rank: '陆军中将', bio: '中国远征军新22师师长、新六军军长。率部穿越野人山，缅北反攻中在胡康河谷、孟拱河谷穿插突破，血战于邦歼灭日军第18师团精锐。' },
      { name: '郑洞国', fullName: '郑洞国', birth: 1903, death: 1991, nation: '中国', rank: '陆军中将', bio: '中国驻印军副总指挥、新一军首任军长。坐镇指挥缅北反攻战役，指挥所部强攻并攻克重镇密支那与八莫，战功卓著。' },
      { name: '宋希濂', fullName: '宋希濂', birth: 1907, death: 1993, nation: '中国', rank: '陆军中将', bio: '第十一集团军总司令。远征军早期率部急行军固守怒江惠通桥天险，彻底遏制日军东侵进犯大后方；后协同指挥滇西大反攻龙陵攻坚战。' },
      { name: '霍揆彰', fullName: '霍揆彰', birth: 1901, death: 1953, nation: '中国', rank: '陆军中将', bio: '中国远征军第二十集团军总司令。滇西反攻中率部强渡怒江，翻越险峻的高黎贡山，强攻并全歼腾冲守敌，光复腾冲要塞。' },
      { name: '齐学启', fullName: '齐学启', birth: 1900, death: 1945, nation: '中国', rank: '陆军少将', bio: '中国远征军新38师副师长。仁安羌大捷后率部掩护后方野战医院撤退，遭敌重兵合围负伤被俘。在仰光战俘营坚贞不屈、大义凛然，后壮烈殉国。' },
      { name: '李宗仁', fullName: '李宗仁', birth: 1891, death: 1969, nation: '中国', rank: '陆军一级上将', bio: '第五战区司令长官。指挥台儿庄会战重创日军主力，取得抗战正面战场首次重大胜利，极大鼓舞了全民族抗战士气。' },
      { name: '薛岳', fullName: '薛岳', birth: 1896, death: 1998, nation: '中国', rank: '陆军一级上将', bio: '第九战区司令长官。自创"天炉战法"，指挥万家岭战役与四次长沙会战，歼灭日军精锐十余万人，被誉为抗日名将。' },
      { name: '张自忠', fullName: '张自忠', birth: 1891, death: 1940, nation: '中国', rank: '陆军二级上将', bio: '第33集团军总司令。枣宜会战中亲率卫队东渡襄河血战南瓜店壮烈殉国，是二战盟军阵亡将领中军衔最高者之一，民族英雄名垂千古。' },
      { name: '梁思成', fullName: '梁思成', birth: 1901, death: 1972, nation: '中国', rank: '建筑史学家', bio: '著名建筑学家。二战盟军对日战略轰炸期间，极力建议将京都、奈良从轰炸目标中剔除，保护了世界珍贵文化遗产与古建筑。' },

      // 中缅印战区 (CBI) 盟军统帅
      { name: '史迪威', fullName: '约瑟夫·史迪威', birth: 1883, death: 1946, nation: '美国', rank: '陆军四星上将', bio: '中缅印战区美军总司令兼中国战区参谋长。主持驻印军蓝姆伽基地整训，积极调拨美援装备，力主发起缅北反攻并修筑著名的"史迪威公路"。' },
      { name: '陈纳德', fullName: '克莱尔·李·陈纳德', birth: 1893, death: 1958, nation: '美国', rank: '陆军航空队中将', bio: '"飞虎队"（美国志愿援华航空队）创始人。在中国战场沉重打击日军空中力量，主导开辟世界航空史奇迹"驼峰航线"运送大量物资。' },
      { name: '斯利姆', fullName: '威廉·斯利姆', birth: 1891, death: 1970, nation: '英国', rank: '陆军元帅', bio: '英国第14集团军司令。在缅甸战场历经早期逆境后重组英印军，在英帕尔战役与科希马战役中以防守反击歼灭日军主力，全面收复缅甸。' },

      // 二战盟军将领
      { name: '麦克阿瑟', fullName: '道格拉斯·麦克阿瑟', birth: 1880, death: 1964, nation: '美国', rank: '五星上将', bio: '太平洋战区盟军最高司令。主导"蛙跳战术"逐岛反攻，战后主持日本重建。朝鲜战争中指挥仁川登陆，名垂青史。' },
      { name: '尼米兹', fullName: '切斯特·尼米兹', birth: 1885, death: 1966, nation: '美国', rank: '五星上将', bio: '美国太平洋舰队总司令。中途岛海战中以少胜多击沉四艘日军航母，扭转太平洋战局。潜艇出身的他被誉为"海上骑士"。' },
      { name: '艾森豪威尔', fullName: '德怀特·艾森豪威尔', birth: 1890, death: 1969, nation: '美国', rank: '五星上将', bio: '盟军远征军最高司令。统筹策划诺曼底登陆，协调英美联军在欧洲战场的全局战略。战后当选美国第34任总统。' },
      { name: '巴顿', fullName: '乔治·巴顿', birth: 1885, death: 1945, nation: '美国', rank: '四星上将', bio: '美国第三集团军司令。以勇猛果敢的装甲战术著称，率部横扫法国、德国，是盟军推进最快的将领。战后因车祸殉职。' },
      { name: '蒙哥马利', fullName: '伯纳德·蒙哥马利', birth: 1887, death: 1976, nation: '英国', rank: '陆军元帅', bio: '英国第八集团军司令。在阿拉曼战役中击败隆美尔的非洲军团，扭转北非战局。后参与诺曼底登陆和欧洲西北部战役。' },
      { name: '布雷德利', fullName: '奥马尔·布雷德利', birth: 1893, death: 1981, nation: '美国', rank: '五星上将', bio: '美军第12集团军群司令，统领百万人马横扫欧洲战场，以爱护部下和沉稳指挥闻名，被誉为"大兵将军"。' },
      { name: '朱可夫', fullName: '格奥尔基·朱可夫', birth: 1896, death: 1974, nation: '苏联', rank: '苏联元帅', bio: '二战苏军最高统帅部副统帅。指挥莫斯科保卫战、斯大林格勒战役、库尔斯克会战和柏林战役，被誉为"胜利元帅"，是击败纳粹德国的关键人物。' },
      { name: '华西列夫斯基', fullName: '亚历山大·华西列夫斯基', birth: 1895, death: 1977, nation: '苏联', rank: '苏联元帅', bio: '苏军总参谋长。参与策划莫斯科反攻、斯大林格勒合围和库尔斯克会战等重大战役，是苏军最高统帅部的核心智囊。' },
      { name: '崔可夫', fullName: '瓦西里·崔可夫', birth: 1900, death: 1982, nation: '苏联', rank: '苏联元帅', bio: '第62集团军司令。在斯大林格勒战役中死守城市，与德军逐屋争夺，被誉为"斯大林格勒的救星"。后率部攻入柏林。' },
      { name: '切尔尼亚霍夫斯基', fullName: '伊万·切尔尼亚霍夫斯基', birth: 1906, death: 1945, nation: '苏联', rank: '大将', bio: '二战苏军最年轻的方面军司令员。指挥白俄罗斯第三方面军在"巴格拉季昂"行动中势如破竹，成功挺进东普鲁士。' },
      { name: '施瓦茨科普夫', fullName: '诺曼·施瓦茨科普夫', birth: 1934, death: 2012, nation: '美国', rank: '上将', bio: '现代联合作战与装甲纵深大迂回的杰出代表，以大胆果决的指挥风格著称，擅长多兵种协同作战。' },

      // 二战欧洲其他名将
      { name: '隆美尔', fullName: '埃尔温·隆美尔', birth: 1891, death: 1944, nation: '德国', rank: '陆军元帅', bio: '绰号"沙漠之狐"。二战期间率领非洲军团在北非战场屡创英军，以机动战术闻名于世。后因卷入刺杀希特勒事件被迫服毒自尽。' },
      { name: '古德里安', fullName: '海因茨·古德里安', birth: 1888, death: 1954, nation: '德国', rank: '上将', bio: '"闪击战之父"，德国装甲兵创始人。著有《注意！坦克》，奠定了现代装甲战理论。率部在波兰和法国战役中大放异彩。' },
      { name: '曼施坦因', fullName: '埃里希·冯·曼施坦因', birth: 1887, death: 1973, nation: '德国', rank: '陆军元帅', bio: '二战德军最杰出的战略家之一。策划了入侵法国的"黄色方案"（曼施坦因计划），在东线指挥克里米亚战役和哈尔科夫反击战。' },
      { name: '邓尼茨', fullName: '卡尔·邓尼茨', birth: 1891, death: 1980, nation: '德国', rank: '海军元帅', bio: '德国海军潜艇部队创始人，首创"狼群战术"。希特勒自杀后曾短暂担任德国总统，主导投降。' },
      { name: '莫德尔', fullName: '瓦尔特·莫德尔', birth: 1891, death: 1945, nation: '德国', rank: '陆军元帅', bio: '德军"防御之狮"。在东线多次组织成功防御，延迟苏军推进。1945年鲁尔包围战中兵败自尽，希特勒称之为"最忠诚的元帅"。' },
      { name: '龙德施泰特', fullName: '格特·冯·龙德施泰特', birth: 1875, death: 1953, nation: '德国', rank: '陆军元帅', bio: '二战德军资历最老的统帅之一。曾任西线总司令，先后指挥波兰战役、法国战役、巴巴罗萨行动南部战线及阿登反击战。' },
      { name: '海因里希', fullName: '戈特哈德·海因里希', birth: 1886, death: 1971, nation: '德国', rank: '一级上将', bio: '二战德军最卓越的防御大师。擅长在敌军炮火准备前秘密撤退至次防线，在泽洛高地构筑坚固防御重创进逼柏林的苏军。' },
      { name: '克卢格', fullName: '汉斯·京特·冯·克卢格', birth: 1882, death: 1944, nation: '德国', rank: '陆军元帅', bio: '德军名将，代号"聪明的汉斯"。先后指挥第4集团军和中央集团军群，在东线战场多次组织艰苦攻防战。' },
      { name: '霍特', fullName: '赫尔曼·霍特', birth: 1885, death: 1971, nation: '德国', rank: '一级上将', bio: '德军著名装甲战指挥官。先后率领第3装甲集群和第4装甲集团军，在明斯克、基辅、哈尔科夫和库尔斯克等战役中担任装甲突击主力。' }
    ],

    // 累计声望、累计剿寇胜场与含主城的城市上限均与 MilitaryRankDef 一致；只用于展示，实际授衔及建城以服务端为准。
    militaryRanks: [
      { tier: 1,  name: '列兵',   },
      { tier: 2,  name: '上等兵', },
      { tier: 3,  name: '下士',   },
      { tier: 4,  name: '中士',   },
      { tier: 5,  name: '上士',   },
      { tier: 6,  name: '军士长', },
      { tier: 7,  name: '准尉',   },
      { tier: 8,  name: '少尉',   },
      { tier: 9,  name: '中尉',   },
      { tier: 10, name: '上尉',   },
      { tier: 11, name: '少校',   },
      { tier: 12, name: '中校',   },
      { tier: 13, name: '上校',   },
      { tier: 14, name: '大校',   },
      { tier: 15, name: '少将',   },
      { tier: 16, name: '中将',   },
      { tier: 17, name: '上将',   }
    ]
  };

  // 仓库文案与凭证档位对应；实际晋升条件以服务端 rankInfo 为准。
  G.DATA.militaryRanks.forEach(function (rank) {
    if (rank.tier === 1) return;
    G.DATA.items['rank_certificate_' + rank.tier] = {
      name: rank.name + '晋升凭证', icon: 'img/items/officer-gear-box.svg', cat: 'util',
      desc: '仅用于晋升' + rank.name + '，授衔消耗1张；商城「晋升凭证」购买，' + rank.certificatePrice + ' 钻石/张；NPC 不再掉落'
    };
  });

  G.getMilitaryRankTierInfo = function (tier) {
    var ranks = G.DATA.militaryRanks;
    var idx = Math.max(1, Math.min(ranks.length, parseInt(tier, 10) || 1)) - 1;
    var cur = ranks[idx];
    var next = idx < ranks.length - 1 ? ranks[idx + 1] : null;
    return {
      tier: cur.tier,
      name: cur.name,
      baseCap: cur.baseCap,
      cityCap: cur.cityCap,
      minPrestige: cur.prestige,
      nextTier: next ? next.tier : null,
      nextName: next ? next.name : '',
      nextBaseCap: next ? next.baseCap : null,
      nextPrestige: next ? next.prestige : null,
      reqNpcVictories: next ? next.npcVictories : null,
      certificateKey: next ? 'rank_certificate_' + next.tier : null,
      isMax: !next
    };
  };

  /** 用同一套徽记规则表现各阶军衔，避免页面各自定义不一致的图标。 */
  G.renderMilitaryRankIcon = function (tier) {
    var rank = G.DATA.militaryRanks[Math.max(1, Math.min(G.DATA.militaryRanks.length, parseInt(tier, 10) || 1)) - 1];
    var level = rank.tier;
    var group = level <= 2 ? 'enlisted' : level <= 6 ? 'sergeant' : level === 7 ? 'warrant' : level <= 10 ? 'officer' : level <= 14 ? 'field' : 'general';
    var marks = '';
    var index;
    if (level <= 6) {
      var chevrons = level <= 2 ? level : level === 6 ? 3 : level - 2;
      for (index = 0; index < chevrons; index++) {
        var y = 22 - (chevrons - 1) * 3 + index * 6;
        marks += '<path d="M11 ' + y + ' L20 ' + (y + 5) + ' L29 ' + y + '" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"/>';
      }
      if (level >= 3) marks += level === 6
        ? '<circle cx="16" cy="11" r="2"/><circle cx="24" cy="11" r="2"/>'
        : '<circle cx="20" cy="11" r="2.5"/>';
    } else if (level === 7) {
      marks = '<path d="M20 8 L30 20 L20 32 L10 20 Z" fill="none" stroke="currentColor" stroke-width="2.5"/><circle cx="20" cy="20" r="3"/>';
    } else if (level <= 10) {
      for (index = 0; index < level - 7; index++) {
        marks += '<rect x="12" y="' + (20 - (level - 7) * 4 + index * 8) + '" width="16" height="4" rx="1"/>';
      }
    } else {
      var stars = level <= 14 ? level - 10 : level - 14;
      var positions = stars === 1 ? [[20, 20]] : stars === 2 ? [[15, 20], [25, 20]]
        : stars === 3 ? [[14, 16], [26, 16], [20, 26]] : [[14, 15], [26, 15], [14, 26], [26, 26]];
      if (level >= 15) marks += '<path d="M13 10 Q6 20 13 31 M27 10 Q34 20 27 31" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>';
      for (index = 0; index < positions.length; index++) {
        marks += '<path transform="translate(' + positions[index][0] + ' ' + positions[index][1] + ')" d="M0 -5 L1.6 -1.6 L5 -1.6 L2.5 1 L3.5 5 L0 3 L-3.5 5 L-2.5 1 L-5 -1.6 L-1.6 -1.6 Z"/>';
      }
    }
    return '<svg class="rank-insignia rank-insignia-' + group + '" viewBox="0 0 40 40" aria-hidden="true" focusable="false">'
      + '<rect x="1" y="1" width="38" height="38" rx="8" class="rank-insignia-plate"/>' + marks + '</svg>';
  };

  // 指定技能书由技能定义派生，旧 supply 仅为兼容别名，不单独生成商品。
  Object.keys(G.DATA.officerSkills).forEach(function (skillId) {
    if (skillId === 'supply') return;
    var skill = G.DATA.officerSkills[skillId];
    G.DATA.items['skillBook_' + skillId] = {
      name: skill.name + '技能书',
      icon: 'img/items/skill-book.svg',
      desc: '军官使用，直接学习「' + skill.name + '」满级',
      cat: 'officer',
      skillId: skillId
    };
  });

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
  };

  // 全局兵种实体图标映射 (结合3D真实实物渲染图与无白边高清军武特征矢量)
  G.UNIT_ICON = {
    infantry: 'img/units/infantry.svg',
    truck: 'img/units/truck.svg',
    armored: 'img/units/armored.svg',
    recon_car: 'img/units/models/recon_car.webp',
    ltank: 'img/units/ltank.svg',
    htank: 'img/units/htank.png',
    assault: 'img/units/assault.png',
    rocket: 'img/units/rocket.svg',
    scout: 'img/units/scout.svg',
    special: 'img/units/special.svg',
    fighter: 'img/units/fighter.png',
    bomber: 'img/units/bomber.svg',
    transport: 'img/units/transport.svg',
    destroyer: 'img/units/destroyer.svg',
    sub: 'img/units/sub.svg',
    battleship: 'img/units/battleship.png',
    carrier: 'img/units/carrier.svg',
    bunker: 'img/units/models/bunker.webp',
    howitzer: 'img/units/models/howitzer.webp',
    antitank: 'img/units/models/antitank.webp',
    flak: 'img/units/models/flak.webp'
  };

  // 首页、军队页与城防总览统一使用的写实兵种与工事模型。
  G.UNIT_MODEL = {
    infantry: 'img/units/models/infantry.webp',
    truck: 'img/units/models/truck.webp',
    armored: 'img/units/models/armored.webp',
    recon_car: 'img/units/models/recon_car.webp',
    ltank: 'img/units/models/ltank.webp',
    htank: 'img/units/models/htank.webp',
    assault: 'img/units/models/assault.webp',
    rocket: 'img/units/models/rocket.webp',
    scout: 'img/units/models/scout.webp',
    special: 'img/units/models/special.webp',
    fighter: 'img/units/models/fighter.webp',
    bomber: 'img/units/models/bomber.webp',
    transport: 'img/units/models/transport.webp',
    destroyer: 'img/units/models/destroyer.webp',
    sub: 'img/units/models/sub.webp',
    battleship: 'img/units/models/battleship.webp',
    carrier: 'img/units/models/carrier.webp',
    bunker: 'img/units/models/bunker.webp',
    howitzer: 'img/units/models/howitzer.webp',
    antitank: 'img/units/models/antitank.webp',
    flak: 'img/units/models/flak.webp'
  };

  // 通用兵种图标渲染辅助函数
  G.getUnitIconHtml = function (id, name, extraCls) {
    var raw = (G.UNIT_ICON && G.UNIT_ICON[id]) || '⚔';
    var cls = extraCls ? (' ' + extraCls) : '';
    if (/\.svg$|\.png$|\.jpg$|\.webp$/i.test(raw)) {
      var alt = G.escapeHtml ? G.escapeHtml(name || id) : (name || id);
      return '<span class="unit-icon-wrap' + cls + '"><img class="unit-icon-img" src="' + raw + '" alt="' + alt + '"/></span>';
    }
    return '<span class="unit-icon-wrap' + cls + '">' + raw + '</span>';
  };

  G.getUnitModelIconHtml = function (id, name, extraCls) {
    var raw = (G.UNIT_MODEL && G.UNIT_MODEL[id]);
    if (!raw) return G.getUnitIconHtml(id, name, extraCls);
    var cls = extraCls ? (' ' + extraCls) : '';
    var alt = G.escapeHtml ? G.escapeHtml(name || id) : (name || id);
    return '<span class="unit-icon-wrap unit-model-icon' + cls + '"><img class="unit-icon-img" src="' + raw + '" alt="' + alt + '"/></span>';
  };

  // 补齐日军兵种的数值与设定（除名称与历史外，攻防血速、消耗、相克等属性 100% 继承盟军兵种）
  if (G.DATA && G.DATA.units && G.DATA.japaneseUnits) {
    Object.keys(G.DATA.units).forEach(function (uid) {
      var allied = G.DATA.units[uid];
      var jpn = G.DATA.japaneseUnits[uid];
      if (jpn && allied) {
        Object.keys(allied).forEach(function (k) {
          if (k !== 'name' && k !== 'history') {
            jpn[k] = allied[k];
          }
        });
      }
    });
  }

  G.getUnit = function (unitId, isJapanese) {
    if (isJapanese && G.DATA && G.DATA.japaneseUnits && G.DATA.japaneseUnits[unitId]) {
      return G.DATA.japaneseUnits[unitId];
    }
    return (G.DATA && G.DATA.units && G.DATA.units[unitId]) || null;
  };

  G.getUnitName = function (unitId, isJapanese) {
    var u = G.getUnit(unitId, isJapanese);
    return u ? u.name : unitId;
  };
})(window.Game);

// 整套箱与单件直接读取共享目录，名称和描述不另维护副本。
(function (G) {
  if (G && G.DATA && G.DATA.items) {
    if (G.Constants && G.Constants.equipmentItems) {
      Object.keys(G.Constants.equipmentItems).forEach(function (key) { G.DATA.items[key] = G.Constants.equipmentItems[key]; });
    }
    if (G.Constants && Array.isArray(G.Constants.shopItems)) {
      G.Constants.shopItems.forEach(function (item) {
        if (item.equipmentSet) G.DATA.items[item.id] = { name: item.name, icon: item.icon, desc: item.desc, cat: 'officer', isBox: true };
      });
    }
  }
})(window.Game);
