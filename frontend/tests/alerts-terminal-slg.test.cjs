const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup(initialState = {}) {
  const routes = [];
  const toasts = [];
  const game = {
    DATA: {
      units: {
        infantry: { name: '步兵-加兰德', cat: 'inf', atkAir: 5, spd: 2 },
        fighter: { name: '战斗机-野马', cat: 'air', atkAir: 64, spd: 8 },
        armored: { name: '装甲车-猎鹿犬防空型', cat: 'arm', atkAir: 33.5, spd: 5 },
        htank: { name: '重型坦克-斯大林', cat: 'arm', atkAir: 10, spd: 4 },
        truck: { name: '运输车', cat: 'inf', spd: 4 }
      },
      techs: { attack_tech: { name: '攻击科技' } },
      officerSkills: {},
      resources: { food: { name: '粮食' }, oil: { name: '石油' } }
    },
    Core: {
      route: 'alerts',
      views: {},
      state: {
        player: { username: '司令员', cityName: '主基地' },
        buildings: { radar: 10 },
        cityState: { shieldUntil: 0 },
        unitEquipment: { catalog: {} },
        army: { infantry: 500, fighter: 20, armored: 15, htank: 10 },
        resources: { food: 100000, oil: 50000 },
        world: {
          cityPos: { x: 342, y: 681 },
          marches: [],
          incoming: [],
          reinforcements: []
        },
        ...initialState
      },
      render: () => {}
    },
    go: (r) => routes.push(r),
    toast: (m) => toasts.push(m),
    escapeHtml: v => String(v).replace(/</g, '&lt;'),
    fmt: String,
    gameNow: () => Date.now(),
    Constants: {
      battleRoundLimits: { conquer: 30, plunder: 10, feint: 5, harass: 5, guerrilla: 5 },
      dispatchActionNames: { conquer: '征服', plunder: '掠夺', scout: '侦查', campaign: '讨伐' },
      marchAcceleration: { minRemainingMs: 10000 }
    },
    Battle: {
      openTactical: () => {}
    },
    API: {
      client: {
        post: (url, body) => {
          if (url === '/game/cities/shield') {
            const cityIds = (body && body.cityIds) || [];
            const cities = game.World.getPlayerCities();
            const now = Date.now();
            const shieldDuration = 8 * 3600 * 1000;
            const updatedCities = cities.map(c => {
              if (cityIds.some(id => String(id) === String(c.id))) {
                const base = (c.shieldUntil && c.shieldUntil > now) ? c.shieldUntil : now;
                return { ...c, shieldUntil: base + shieldDuration };
              }
              return c;
            });
            const selectedNames = cities.filter(c => cityIds.some(id => String(id) === String(c.id))).map(c => c.name);
            const cost = cityIds.length * 200;
            const state = JSON.parse(JSON.stringify(game.Core.state));
            state.resources.diamond = Math.max(0, (state.resources.diamond || 0) - cost);
            if (state.cityOverview && state.cityOverview.cities) {
              state.cityOverview.cities = updatedCities;
            } else {
              state.cityState = { ...state.cityState, shieldUntil: updatedCities[0]?.shieldUntil || 0 };
            }
            return Promise.resolve({
              success: true,
              count: cityIds.length,
              cost,
              cities: selectedNames,
              state
            });
          }
          return Promise.resolve({ success: true });
        }
      },
      applyState: (s) => {
        Object.assign(game.Core.state, s);
      }
    }
  };

  const nodes = new Map();
  const context = vm.createContext({
    Game: game,
    Date,
    console,
    localStorage: { getItem: () => null },
    document: {
      getElementById: id => nodes.get(id) || null,
      querySelector: selector => selector && selector.includes('dpOfficer') ? nodes.get('dpOfficer') : null,
      querySelectorAll: () => []
    }
  });
  context.window = context;
  require('./load-constants.cjs')(context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../js/world.js'), 'utf8'), context);

  return { game, routes, toasts, nodes, context };
}

test('军情监控头渲染全域监控状态、120KM雷达范围与防务状态', () => {
  const { game } = setup();
  const view = { innerHTML: '', querySelectorAll: () => [] };
  game.World.renderAlerts(view);

  assert.match(view.innerHTML, /\[全域预警监控\]/);
  assert.match(view.innerHTML, /正常运行/);
  assert.match(view.innerHTML, /雷达范围: 120KM/);
  assert.match(view.innerHTML, /防务: 无护盾保护/);
});

test('纯文本微型进度条按比例精确输出字符级方块', () => {
  const { game } = setup();
  assert.equal(game.World.renderTextProgressBar(75), '[■■■■■■■□□□] 75%');
  assert.equal(game.World.renderTextProgressBar(40), '[■■■■□□□□□□] 40%');
  assert.equal(game.World.renderTextProgressBar(100), '[■■■■■■■■■■] 100%');
  assert.equal(game.World.renderTextProgressBar(0), '[□□□□□□□□□□] 0%');
  assert.equal(game.World.renderTextProgressBar(85), '[■■■■■■■■□□] 85%');
});

test('敌袭卡片展示 [敌袭!] 字符Badge、进度条、测距、目标与开罩布阵求援闭环', () => {
  const now = Date.now();
  const { game } = setup({
    world: {
      cityPos: { x: 342, y: 681 },
      incoming: [{
        id: 'inc-1',
        attackerName: '狂怒战神',
        guildName: '黑鹰帝国',
        action: 'conquer',
        fromX: 300,
        fromY: 650,
        targetX: 342,
        targetY: 681,
        targetName: '主基地',
        startAt: now - 298000,
        arriveAt: now + 102000,
        totalTroops: 152000,
        strengthBand: '15.2w',
        expanded: false
      }],
      marches: [],
      reinforcements: []
    }
  });

  const view = { innerHTML: '', querySelectorAll: () => [] };
  game.World.renderAlerts(view);

  assert.match(view.innerHTML, /\[敌袭!\]/);
  assert.match(view.innerHTML, /狂怒战神 \(黑鹰帝国\) 来袭/);
  assert.match(view.innerHTML, /抵达/);
  assert.match(view.innerHTML, /\[■■■■■■■□□□\] 75%/);
  assert.match(view.innerHTML, /距:/);
  assert.match(view.innerHTML, /~15\.2w/);
  assert.match(view.innerHTML, /主基地\[342,681\]/);
  assert.match(view.innerHTML, />开罩</);
  assert.match(view.innerHTML, />布阵</);
  assert.match(view.innerHTML, />呼叫救援</);
});

test('侦察卡片展示 [侦察] 字符Badge与出城拦截、呼叫救援操作闭环，精简移除无效的电子干扰与击落按钮', () => {
  const now = Date.now();
  const { game } = setup({
    world: {
      cityPos: { x: 342, y: 681 },
      incoming: [{
        id: 'inc-2',
        attackerName: '极光要塞 · 影刃',
        action: 'scout',
        fromX: 320,
        fromY: 670,
        targetX: 342,
        targetY: 681,
        targetName: '城防情报',
        arriveAt: now + 46000,
        duration: 180000,
        expanded: false
      }],
      marches: [],
      reinforcements: []
    }
  });

  const view = { innerHTML: '', querySelectorAll: () => [] };
  game.World.renderAlerts(view);

  assert.match(view.innerHTML, /\[侦察\]/);
  assert.match(view.innerHTML, /极光要塞 · 影刃/);
  assert.match(view.innerHTML, /抵近/);
  assert.match(view.innerHTML, /侦察机 1架 \(高空\)/);
  assert.match(view.innerHTML, /目标: 城防情报/);
  assert.match(view.innerHTML, />出城拦截</);
  assert.match(view.innerHTML, />呼叫救援</);
  assert.doesNotMatch(view.innerHTML, />电子干扰</);
  assert.doesNotMatch(view.innerHTML, />击落</);
});

test('出征卡片展示 [出征] 字符Badge、进度条与加速召回按钮', () => {
  const now = Date.now();
  const { game } = setup({
    world: {
      cityPos: { x: 342, y: 681 },
      incoming: [],
      marches: [{
        id: 201,
        action: 'campaign',
        targetKind: 'wild',
        targetName: '8级叛军要塞',
        targetX: 390,
        targetY: 710,
        fromX: 342,
        fromY: 681,
        distance: 38,
        startAt: now - 100000,
        arriveAt: now + 150000,
        version: 1,
        army: { infantry: 50000, fighter: 35000 }
      }],
      reinforcements: []
    }
  });

  const view = { innerHTML: '', querySelectorAll: () => [] };
  game.World.renderAlerts(view);

  assert.match(view.innerHTML, /\[出征\]/);
  assert.match(view.innerHTML, /讨伐->8级叛军要塞/);
  assert.match(view.innerHTML, /到达/);
  assert.match(view.innerHTML, /\[■■■■□□□□□□\] 40%/);
  assert.match(view.innerHTML, /85,000兵力/);
  assert.match(view.innerHTML, /\[⚡加速50%\]/);
  assert.match(view.innerHTML, />召回</);
});

test('底部保留老页游玩家熟悉的 [0] 返回指挥大厅、[R] 刷新雷达信号与响应耗时', () => {
  const { game, routes } = setup();
  const view = { innerHTML: '', querySelectorAll: () => [] };
  game.World.renderAlerts(view);

  assert.match(view.innerHTML, /\[0\] 返回指挥大厅/);
  assert.match(view.innerHTML, /\[R\] 刷新雷达信号/);
  assert.match(view.innerHTML, /响应: 12ms/);
  assert.match(view.innerHTML, /自动轮询: 3s/);

  game.go('home');
  assert.deepEqual(routes, ['home']);
});

test('过滤标签支持全部、敌袭、侦察、我军与驻防的分流切换', () => {
  const now = Date.now();
  const { game } = setup({
    world: {
      cityPos: { x: 342, y: 681 },
      incoming: [
        { id: 'inc-1', attackerName: '敌军A', action: 'conquer', arriveAt: now + 10000 },
        { id: 'inc-2', attackerName: '侦察机B', action: 'scout', arriveAt: now + 10000 }
      ],
      marches: [
        { id: 201, action: 'campaign', arriveAt: now + 20000, army: { infantry: 100 } }
      ],
      reinforcements: [
        { id: 301, senderName: '盟友C', army: { infantry: 50 } }
      ]
    }
  });

  const view = { innerHTML: '', querySelectorAll: () => [] };

  // 1. 全部视图
  game.World.setAlertsFilter('all');
  game.World.renderAlerts(view);
  assert.match(view.innerHTML, /敌军A/);
  assert.match(view.innerHTML, /侦察机B/);
  assert.match(view.innerHTML, /讨伐/);
  assert.match(view.innerHTML, /盟友C/);

  // 2. 敌袭视图
  game.World.setAlertsFilter('enemy');
  game.World.renderAlerts(view);
  assert.match(view.innerHTML, /敌军A/);
  assert.doesNotMatch(view.innerHTML, /侦察机B/);
  assert.doesNotMatch(view.innerHTML, /讨伐/);
  assert.doesNotMatch(view.innerHTML, /盟友C/);

  // 3. 侦察视图
  game.World.setAlertsFilter('scout');
  game.World.renderAlerts(view);
  assert.match(view.innerHTML, /侦察机B/);
  assert.doesNotMatch(view.innerHTML, /敌军A/);
  assert.doesNotMatch(view.innerHTML, /讨伐/);

  // 4. 我军视图
  game.World.setAlertsFilter('mine');
  game.World.renderAlerts(view);
  assert.match(view.innerHTML, /讨伐/);
  assert.doesNotMatch(view.innerHTML, /敌军A/);
  assert.doesNotMatch(view.innerHTML, /盟友C/);

  // 5. 驻防视图
  game.World.setAlertsFilter('garrison');
  game.World.renderAlerts(view);
  assert.match(view.innerHTML, /盟友C/);
  assert.doesNotMatch(view.innerHTML, /敌军A/);
  assert.doesNotMatch(view.innerHTML, /讨伐/);
});

test('快捷操作闭环调用能正确弹出对应战术反馈', () => {
  const { game, toasts, routes } = setup();

  game.World.applyElectronicJamming(0);
  assert.ok(toasts.some(t => t.includes('电子干扰')));

  game.World.interceptScoutAircraft(0);
  assert.ok(toasts.some(t => t.includes('锁定敌方高空侦察机')));

  game.World.thankAlly('盟友甲');
  assert.ok(toasts.some(t => t.includes('已向盟友 盟友甲 发送答谢战讯')));

  game.World.refreshAlerts();
  assert.ok(toasts.some(t => t.includes('雷达信号已刷新')));

  game.World.openDefenseFormation();
  assert.ok(routes.includes('battleDefaults'), '点击布阵应当跳转至 battleDefaults 战术布阵页');
});

test('侦察卡片展示 [出城拦截] 按钮，点击进入拦截出征界面', () => {
  const now = Date.now();
  const { game, routes } = setup({
    world: {
      cityPos: { x: 342, y: 681 },
      incoming: [{
        id: 'inc-scout-1',
        attackerName: '黑鹰侦察队',
        action: 'scout',
        fromX: 300,
        fromY: 650,
        targetX: 342,
        targetY: 681,
        targetName: '主基地',
        arriveAt: now + 50000,
        duration: 120000,
        expanded: false
      }],
      marches: [],
      reinforcements: []
    }
  });

  const view = { innerHTML: '', querySelectorAll: () => [] };
  game.World.renderAlerts(view);

  // 验证卡片包含出城拦截按钮
  assert.match(view.innerHTML, />出城拦截</);

  // 点击出城拦截
  game.World.openScoutInterception(0);

  // 验证路由进入 dispatch 界面
  assert.equal(routes.at(-1), 'dispatch');

  // 验证出征目标配置为 intercept_scout
  const dt = game.Core.state.world._dispatchTarget;
  assert.ok(dt, '出征目标应存在');
  assert.equal(dt.action, 'intercept_scout');
  assert.equal(dt.kind, 'incoming_scout');
  assert.match(dt.targetName, /黑鹰侦察队/);
});

test('普通敌军来袭行军卡片也提供出城拦截，并关联真实行军目标', () => {
  const now = Date.now();
  const { game, routes } = setup({
    world: {
      cityPos: { x: 342, y: 681 },
      incoming: [{ id: 'march-91', marchId: 91, attackerName: '来袭军团', action: 'plunder',
        fromX: 300, fromY: 650, targetX: 342, targetY: 681, targetCityId: 12, arriveAt: now + 50000,
        startAt: now - 10000, expanded: false }],
      marches: [],
      reinforcements: []
    }
  });

  const view = { innerHTML: '', querySelectorAll: () => [] };
  game.World.renderAlerts(view);

  assert.match(view.innerHTML, /openScoutInterception\(0\)\">出城拦截/);
  game.World.openScoutInterception(0);

  assert.equal(routes.at(-1), 'dispatch');
  assert.equal(game.Core.state.world._dispatchTarget.action, 'intercept_scout');
  assert.equal(game.Core.state.world._dispatchTarget.marchId, 91);
  assert.match(game.Core.state.world._dispatchTarget.targetName, /来袭军团.*行军部队/);
});

test('普通敌军来袭拦截出征可配置并提交任意可用兵种', async () => {
  const now = Date.now();
  const { game, nodes } = setup({
    world: {
      cityPos: { x: 342, y: 681 },
      marches: [],
      incoming: [],
      _dispatchTarget: {
        kind: 'incoming_scout',
        action: 'intercept_scout',
        marchId: 91,
        targetName: '来袭军团的行军部队',
        target: { id: 'march-91', name: '来袭军团的行军部队', x: 300, y: 650 },
        interceptTarget: { arriveAt: now + 50000 }
      }
    }
  });

  const view = { innerHTML: '', querySelectorAll: () => [] };
  game.World.renderDispatch(view);

  assert.match(view.innerHTML, /id="dqty_infantry"/);
  assert.match(view.innerHTML, /id="dqty_htank"/);
  assert.match(view.innerHTML, /任意可用兵种组成截击编队/);
  assert.doesNotMatch(view.innerHTML, /侦查拦截仅/);

  let request;
  game.API.worldDispatch = async body => { request = body; };
  nodes.set('dqty_infantry', { value: '10' });
  nodes.set('dqty_htank', { value: '2' });
  game.World._currentRoute = game.World.calcInterceptionPlan({ infantry: 10, htank: 2 }, game.Core.state.world._dispatchTarget);
  game.World._currentRoute.plannedAt = game.gameNow();
  game.World._interceptionPreviewKey = JSON.stringify(game.World.interceptionPreviewRequest({ infantry: 10, htank: 2 }, game.Core.state.world._dispatchTarget));
  await game.World.launchDispatch();
  assert.equal(request.targetKind, 'incoming_march');
  assert.equal(request.targetId, 91);
  assert.equal(request.army.infantry, 10);
  assert.equal(request.army.htank, 2);
});

test('真实侦查行军的拦截仍只展示空军和装甲车，且拒绝提交步兵', () => {
  const now = Date.now();
  const { game, nodes, routes, toasts } = setup({
    world: {
      cityPos: { x: 342, y: 681 },
      marches: [],
      incoming: [{ id: 'march-92', marchId: 92, attackerName: '侦查部队', action: 'scout',
        fromX: 300, fromY: 650, arriveAt: now + 50000, startAt: now - 10000 }]
    }
  });
  game.World.openScoutInterception(0);
  const view = { innerHTML: '', querySelectorAll: () => [] };
  game.World.renderDispatch(view);

  assert.match(view.innerHTML, /id="dqty_fighter"/);
  assert.match(view.innerHTML, /id="dqty_armored"/);
  assert.doesNotMatch(view.innerHTML, /id="dqty_infantry"|id="dqty_htank"/);
  nodes.set('dqty_infantry', { value: '1' });
  game.World.launchDispatch();

  assert.match(toasts.at(-1), /侦查拦截仅支持配置空军或装甲车/);
  assert.equal(game.Core.state.army.infantry, 500);
  assert.equal(game.Core.state.world.marches.length, 0);
  assert.equal(routes.at(-1), 'dispatch');
});

test('侦查拦截出征界面仅筛选展示空军与装甲车，步兵等非拦截兵种被排除', () => {
  const now = Date.now();
  const { game } = setup({
    army: {
      infantry: 500, // 步兵（非防空对空）
      fighter: 20,   // 空军战机
      armored: 15,   // 装甲车
      htank: 10      // 重型坦克（非防空对空）
    },
    world: {
      cityPos: { x: 342, y: 681 },
      _dispatchTarget: {
        kind: 'incoming_scout',
        action: 'intercept_scout',
        incomingIdx: 0,
        targetName: '敌方高空侦察机',
        targetX: 300,
        targetY: 650
      },
      incoming: [{
        id: 'inc-scout-1',
        attackerName: '敌方侦查',
        action: 'scout',
        fromX: 300,
        fromY: 650,
        arriveAt: now + 50000
      }],
      marches: []
    }
  });

  const view = { innerHTML: '', querySelectorAll: () => [] };
  game.World.renderDispatch(view);

  // 标题与说明
  assert.match(view.innerHTML, /出城拦截 · 截击出征/);
  assert.match(view.innerHTML, /可选配空军或装甲车/);

  // 空军与装甲车应出现在列表中
  assert.match(view.innerHTML, /dqty_fighter/);
  assert.match(view.innerHTML, /战斗机/);
  assert.match(view.innerHTML, /dqty_armored/);
  assert.match(view.innerHTML, /装甲车/);
  assert.match(view.innerHTML, /对空 64/);

  // 步兵与重型坦克等非对空兵种必须被排除
  assert.doesNotMatch(view.innerHTML, /dqty_infantry/);
  assert.doesNotMatch(view.innerHTML, /dqty_htank/);
});

test('出城拦截出征界面更新防空战力，胜负以实际交战为准', () => {
  const { game, nodes } = setup({
    world: {
      cityPos: { x: 342, y: 681 },
      _dispatchTarget: {
        kind: 'incoming_scout',
        action: 'intercept_scout',
        incomingIdx: 0,
        target: { x: 300, y: 650 }
      }
    }
  });

  const powerEl = { textContent: '0' };
  const rateEl = { textContent: '--', style: { color: '' } };
  const troopsEl = { textContent: '0 / 0', style: { color: '' } };
  const queuesEl = { textContent: '0 / 0', style: { color: '' } };
  const statsCard = {};

  nodes.set('estInterceptPower', powerEl);
  nodes.set('estInterceptRate', rateEl);
  nodes.set('estDispatchTroops', troopsEl);
  nodes.set('estDispatchQueues', queuesEl);
  nodes.set('dispatchStatsCard', statsCard);

  // 1. 未选兵力
  nodes.set('dqty_fighter', { value: '0' });
  nodes.set('dqty_armored', { value: '0' });
  game.World.updateDispatchStats();
  assert.equal(powerEl.textContent, '0');
  assert.equal(rateEl.textContent, '--');

  // 2. 选择 2 架战斗机 (每架对空火力 64，总计 128)
  nodes.set('dqty_fighter', { value: '2' });
  game.World.updateDispatchStats();
  assert.equal(powerEl.textContent, '128');
  assert.equal(rateEl.textContent, '以实际交战结果为准');
});

test('calcInterceptionPlan: 根据敌机到达剩余时间与拦截兵种移速，精确计算迎头相遇拦截坐标与所需耗时', () => {
  const now = Date.now();
  const { game } = setup({
    world: {
      cityPos: { x: 342, y: 681 }
    }
  });

  const dt = {
    targetX: 300,
    targetY: 650,
    interceptTarget: {
      fromX: 300,
      fromY: 650,
      startAt: now - 30000,
      arriveAt: now + 60000,
      duration: 90000
    }
  };

  // 派遣空军战机 (spd = 8)
  const planFighter = game.World.calcInterceptionPlan({ fighter: 5 }, dt);
  assert.ok(planFighter.targetX < 342 && planFighter.targetX >= 300);
  assert.ok(planFighter.targetY < 681 && planFighter.targetY >= 650);
  assert.ok(planFighter.seconds > 0 && planFighter.seconds <= 60);
  assert.equal(planFighter.roundSeconds, planFighter.seconds * 2);
  assert.equal(planFighter.totalAntiAir, 64 * 5);

  // 派遣移速较慢的防空装甲车 (spd = 5)
  const planArmored = game.World.calcInterceptionPlan({ armored: 5 }, dt);
  assert.ok(planArmored.targetX < 342 && planArmored.targetX >= 300);
  // 移速较慢时出城距离更短，相遇点更偏向我方城池
  assert.ok(planArmored.distance <= planFighter.distance);
});

test('执行出城拦截：计算迎头截击点并生成出城行军路线，导航至大地图且敌机交火前不提前结算', async () => {
  const now = Date.now();
  const { game, toasts, routes, nodes } = setup({
    resources: { oil: 10000 },
    army: { fighter: 20, armored: 10 },
    world: {
      cityPos: { x: 342, y: 681 },
      incoming: [{
        id: 'scout-threat-1',
        attackerName: '极光要塞 · 影刃',
        action: 'scout',
        fromX: 300,
        fromY: 650,
        startAt: now - 20000,
        arriveAt: now + 40000,
        duration: 60000
      }],
      _dispatchTarget: {
        kind: 'incoming_scout',
        action: 'intercept_scout',
        incomingIdx: 0,
        incomingId: 'scout-threat-1',
        target: { id: 'scout-threat-1', x: 300, y: 650, arriveAt: now + 40000, duration: 60000 },
        interceptTarget: { id: 'scout-threat-1', fromX: 300, fromY: 650, startAt: now - 20000, arriveAt: now + 40000, duration: 60000 }
      },
      marches: []
    }
  });

  // 配置 5 架野马战机出战拦截
  nodes.set('dqty_fighter', { value: '5' });
  nodes.set('dqty_armored', { value: '0' });

  game.API.client = null;
  await game.World.launchDispatch();

  // 1. 验证出征兵力从城内扣除
  assert.equal(game.Core.state.army.fighter, 15, '5架战机已扣除并起飞出征');

  // 2. 验证生成了真实的截击行军记录（未结算）
  assert.equal(game.Core.state.world.marches.length, 1, '大地图应生成1支截击行军编队');
  const march = game.Core.state.world.marches[0];
  assert.equal(march.action, 'intercept_scout');
  assert.equal(march.returning, false);
  assert.equal(march.fromX, 342);
  assert.equal(march.fromY, 681);
  // 验证拦截点根据相对速度计算出在城市与敌机航线之间
  assert.ok(march.targetX < 342 && march.targetX >= 300, '截击点X坐标应在城池与敌军航线区间');
  assert.ok(march.targetY < 681 && march.targetY >= 650, '截击点Y坐标应在城池与敌军航线区间');

  // 3. 验证此时敌方侦察机仍然保留在 incoming 列表中（绝不能点击出征瞬间提前结算击落）
  assert.equal(game.Core.state.world.incoming.length, 1, '敌军侦察机在交火前仍应在航向城池的来袭列表中');

  // 4. 验证跳转至大地图视图，供玩家查看迎头截击动画
  assert.equal(routes.at(-1), 'world', '应跳转至世界大地图查看行军');
  assert.ok(toasts.some(t => t.includes('截击部队已出发') && t.includes('拦截')));

  // 5. 到达拦截空域触发交火：判定防空火力击落敌机，解除侦查警报并掉头返航
  game.World.resolveInterceptionArrival(march);
  assert.equal(game.Core.state.world.incoming.length, 0, '交火后敌方高空侦察机被成功击落，来袭瓦解');
  assert.equal(march.returning, true, '截击机群交火后应掉头返航');
  assert.equal(march.targetX, 342, '返航终点为本城基地');
  assert.equal(march.targetY, 681, '返航终点为本城基地');
  assert.ok(toasts.some(t => t.includes('【截击空战大捷】') && t.includes('击落敌方高空侦察机')));

  // 6. 返航抵达基地归仓：恢复城池兵力
  game.World.resolveInterceptionArrival(march);
  assert.equal(game.Core.state.army.fighter, 20, '返航归建后5架战机恢复入库');
  assert.equal(game.Core.state.world.marches.length, 0, '返航完成后大地图行军移除');
  assert.ok(toasts.some(t => t.includes('截击机群已完成拦截巡航任务') && t.includes('安全返航归建基地')));
});

test('联网出城拦截采用后端状态，前端不重复扣兵和结算敌军行军', async () => {
  const now = Date.now();
  const { game, nodes } = setup({
    army: { fighter: 20 }, resources: { oil: 10000 },
    world: { cityPos: { x: 342, y: 681 }, marches: [], incoming: [{ id: 'march-42', marchId: 42, action: 'scout' }],
      _dispatchTarget: { kind: 'incoming_scout', action: 'intercept_scout', marchId: 42,
        target: { id: 'march-42' }, interceptTarget: { fromX: 300, fromY: 650, startAt: now - 10000, arriveAt: now + 40000 } } }
  });
  nodes.set('dqty_fighter', { value: '5' });
  let request;
  game.API.worldDispatch = async body => {
    request = body;
    game.Core.state = { player: {}, army: { fighter: 15 }, resources: { oil: 9000 },
      world: { cityPos: { x: 342, y: 681 }, incoming: [{ id: 'march-42', marchId: 42, action: 'scout' }],
        marches: [{ id: 99, action: 'intercept_scout', targetId: '42', targetKind: 'incoming_march',
          army: { fighter: 5 }, fromX: 342, fromY: 681, targetX: 320, targetY: 670, startAt: now - 10000, arriveAt: now - 1, returning: false }] } };
    return game.Core.state;
  };
  game.World._currentRoute = game.World.calcInterceptionPlan({ fighter: 5 }, game.Core.state.world._dispatchTarget);
  game.World._currentRoute.plannedAt = game.gameNow();
  game.World._interceptionPreviewKey = JSON.stringify(game.World.interceptionPreviewRequest({ fighter: 5 }, game.Core.state.world._dispatchTarget));
  await game.World.launchDispatch();
  assert.equal(request.targetKind, 'incoming_march');
  assert.equal(request.targetId, 42);
  assert.equal(game.Core.state.army.fighter, 15);
  assert.equal(game.Core.state.resources.oil, 9000);
  assert.equal(game.Core.state.world.marches.length, 1);
  const march = game.Core.state.world.marches[0];
  game.World.resolveInterceptionArrival(march);
  game.World.checkMarchArrivals();
  assert.equal(march.returning, false);
  assert.equal(game.Core.state.world.incoming.length, 1);
  assert.equal(game.Core.state.army.fighter, 15);
});

test('出城拦截界面点击返回安全返回军情监控', () => {
  const { game, routes } = setup({
    world: {
      _dispatchTarget: {
        kind: 'incoming_scout',
        action: 'intercept_scout'
      }
    }
  });

  game.World.cancelDispatch();
  assert.equal(routes.at(-1), 'alerts');
  assert.equal(game.Core.state.world._dispatchTarget, undefined);
});

test('一键呼叫救援：按 player.guild 分享指定侦查军情，无需访问军团大厅', async () => {
  const now = Date.now();
  const sentMessages = [];
  const { game, toasts } = setup({
    player: { id: 101, username: '朱可夫', guild: { id: 88, name: '铁血战旗', role: 'member' } },
    world: {
      cityPos: { x: 342, y: 681 },
      incoming: [{
        id: 'inc-scout-1',
        attackerName: '极光要塞 · 影刃',
        action: 'scout',
        fromX: 300,
        fromY: 650,
        targetX: 342,
        targetY: 681,
        arriveAt: now + 46000
      }]
    }
  });

  game.Chat = {
    shareIncoming: (msg) => {
      sentMessages.push(msg);
      return Promise.resolve({ id: 1, content: msg });
    }
  };

  const result = await game.World.requestGuildAid(0);
  assert.ok(result.success);
  assert.equal(sentMessages.length, 1);
  const sent = sentMessages[0];

  // 客户端不拼接或截断详细情报，只提交本条来袭 ID。
  assert.equal(sent, 'inc-scout-1');
  assert.ok(toasts.some(t => t.includes('分享侦察求援战报')));
  assert.ok(toasts.every(t => !t.includes('尚未加入任何军团')));
});

test('一键呼叫救援：敌袭只提交军情标识，由服务端生成完整求援战报', async () => {
  const now = Date.now();
  const sentMessages = [];
  const { game, toasts } = setup({
    player: { id: 101, username: '朱可夫', guild: { id: 88, name: '铁血战旗', role: 'member' } },
    world: {
      cityPos: { x: 342, y: 681 },
      incoming: [{
        id: 'inc-enemy-1',
        attackerName: '狂怒战神',
        action: 'conquer',
        fromX: 300,
        fromY: 650,
        targetX: 342,
        targetY: 681,
        totalTroops: 152000,
        arriveAt: now + 102000
      }]
    }
  });

  game.Chat = {
    shareIncoming: (msg) => {
      sentMessages.push(msg);
      return Promise.resolve({ id: 2, content: msg });
    }
  };

  const result = await game.World.requestGuildAid(0);
  assert.ok(result.success);
  assert.equal(sentMessages.length, 1);
  const sent = sentMessages[0];

  assert.equal(sent, 'inc-enemy-1');
  assert.ok(toasts.some(t => t.includes('分享敌袭求援战报')));
});

test('一键呼叫救援：玩家未加入军团时阻断发送，忽略军团大厅旧缓存且不产生未处理拒绝', async () => {
  const { game, toasts } = setup({
    player: { id: 101, username: '独行侠', guildId: null },
    guild: {},
    world: {
      incoming: [{ id: 'inc-1', action: 'conquer' }]
    }
  });

  const sentMessages = [];
  game.Guild = { mine: { id: 88, joined: true } };
  game.Chat = {
    shareIncoming: msg => { sentMessages.push(msg); return Promise.resolve({ id: 1 }); }
  };

  const result = await game.World.requestGuildAid(0);
  assert.equal(result.success, false);
  assert.equal(result.reason, '未加入军团');
  assert.equal(sentMessages.length, 0);

  assert.ok(toasts.some(t => t.includes('尚未加入任何军团')));
});

test('一键呼叫救援：聊天模块未加载时通过军团聊天接口发送并等待确认', async () => {
  const { game, toasts } = setup({
    player: { id: 101, guild: { id: 88 } },
    world: { incoming: [{ id: 'scout-1', action: 'scout' }] }
  });
  const sentMessages = [];
  let confirmSend;
  game.API = {
    shareIncomingReport: msg => {
      sentMessages.push(msg);
      return new Promise(resolve => { confirmSend = resolve; });
    }
  };

  const pending = game.World.requestGuildAid(0);
  assert.equal(sentMessages.length, 1);
  assert.equal(toasts.length, 0);
  confirmSend({ id: 1, content: sentMessages[0] });
  const result = await pending;
  assert.equal(result.success, true);
  assert.equal(result.res.id, 1);
  assert.ok(toasts.some(t => t.includes('已向军团频道分享侦察求援战报')));
});

test('一键呼叫救援：发送失败保留真实原因，不显示发送成功', async t => {
  for (const channel of ['chat', 'api']) {
    for (const reason of ['网络连接失败', '尚未加入任何军团', '发言过于频繁，请等待冷却']) {
      await t.test(channel + ': ' + reason, async () => {
        const { game, toasts } = setup({
          player: { id: 101, guild: { id: 88 } },
          world: { incoming: [{ id: 'scout-1', action: 'scout' }] }
        });
        const send = () => Promise.reject(new Error(reason));
        if (channel === 'chat') game.Chat = { shareIncoming: send };
        else game.API = { shareIncomingReport: send };

        const result = await game.World.requestGuildAid(0);
        assert.equal(result.success, false);
        assert.equal(result.reason, reason);
        assert.ok(toasts.some(t => t.includes(reason)));
        assert.ok(toasts.every(t => !t.includes('已向军团频道分享')));
      });
    }
  }
});

test('一键呼叫救援：军团聊天服务不可用时提示失败', async () => {
  const { game, toasts } = setup({
    player: { id: 101, guild: { id: 88 } },
    world: { incoming: [{ id: 'scout-1', action: 'scout' }] }
  });

  const result = await game.World.requestGuildAid(0);
  assert.equal(result.success, false);
  assert.match(result.reason, /军团军情分享服务暂未就绪/);
  assert.ok(toasts.some(t => t.includes('军团军情分享服务暂未就绪')));
  assert.ok(toasts.every(t => !t.includes('已向军团频道分享')));
});

test('一键呼叫救援：未指定索引时自动检索最紧急的军情进行求援', async () => {
  const now = Date.now();
  const sentMessages = [];
  const { game } = setup({
    player: { id: 101, username: '朱可夫', guildId: 88 },
    guild: { id: 88, name: '铁血战旗' },
    world: {
      cityPos: { x: 342, y: 681 },
      incoming: [
        { id: 'scout-1', attackerName: '远方侦查机', action: 'scout', arriveAt: now + 90000 },
        { id: 'enemy-1', attackerName: '急行突击队', action: 'conquer', arriveAt: now + 15000, totalTroops: 3000 }
      ]
    }
  });

  game.Chat = {
    shareIncoming: (msg) => {
      sentMessages.push(msg);
      return Promise.resolve({ ok: true });
    }
  };

  // 不带参数调用，应优先选中即将来袭的急行突击队
  const res = await game.World.requestGuildAid();
  assert.ok(res.success);
  assert.equal(sentMessages.length, 1);
  assert.equal(sentMessages[0], 'enemy-1');
});

test('一键呼叫救援：当雷达未探测到外部威胁时提示无需呼叫救援', async () => {
  const { game, toasts } = setup({
    player: { id: 101, username: '朱可夫', guildId: 88 },
    guild: { id: 88 },
    world: { incoming: [] }
  });

  const res = await game.World.requestGuildAid();
  assert.ok(res.noThreat);
  assert.ok(toasts.some(t => t.includes('未检测到来袭或侦查威胁')));
});

test('军情界面底部渲染 [S] 一键呼叫救援 经典快捷指令', () => {
  const { game } = setup();
  const view = { innerHTML: '', querySelectorAll: () => [] };
  game.World.renderAlerts(view);

  assert.match(view.innerHTML, /\[S\] 一键呼叫救援/);
});

function shieldApiResponse(game, ids, untilById, diamond) {
  const state = structuredClone(game.Core.state);
  state.resources.diamond = diamond;
  state.cityOverview.cities.forEach(city => {
    if (untilById[city.id] != null) city.shieldUntil = untilById[city.id];
    if (city.current) state.cityState.shieldUntil = city.shieldUntil;
  });
  const cities = state.cityOverview.cities.filter(city => ids.includes(city.id)).map(city => city.name);
  const result = { success: true, count: ids.length, cost: ids.length * 200, remainingDiamond: diamond, cities, state };
  game.API = {
    client: { post: async (url, request) => {
      assert.equal(url, '/game/cities/shield');
      assert.deepEqual(Array.from(request.cityIds), ids);
      assert.equal(request.cost, undefined, '服务端按固定价格计算，客户端不传可信费用');
      return result;
    } },
    applyState: next => { game.Core.state = next; }
  };
  return result;
}

test('城防护盾：获取玩家全部城市（主城与分城）列表及独立护盾状态', () => {
  const now = Date.now();
  const { game } = setup({
    cityOverview: {
      cities: [
        { id: 1, citySlot: 0, name: '主基地', x: 342, y: 681, main: true, current: true, shieldUntil: 0 },
        { id: 2, citySlot: 1, name: '分城·铁壁要塞', x: 350, y: 690, main: false, current: false, shieldUntil: now + 3600000 }
      ]
    }
  });

  const cities = game.World.getPlayerCities();
  assert.equal(cities.length, 2);
  assert.equal(cities[0].name, '主基地');
  assert.equal(cities[0].shieldUntil, 0);
  assert.equal(cities[1].name, '分城·铁壁要塞');
  assert.ok(cities[1].shieldUntil > now);
});

test('城防护盾：选择开启单个城市护盾，扣除单城 200 钻石并生效 8 小时护盾', async () => {
  const now = Date.now();
  const { game, toasts } = setup({
    resources: { diamond: 500 },
    cityOverview: {
      cities: [
        { id: 1, citySlot: 0, name: '主基地', x: 342, y: 681, main: true, current: true, shieldUntil: 0 },
        { id: 2, citySlot: 1, name: '分城·铁壁要塞', x: 350, y: 690, main: false, current: false, shieldUntil: 0 }
      ]
    }
  });

  shieldApiResponse(game, [1], { 1: now + 8 * 3600 * 1000 }, 300);
  // 仅选择主城 (id: 1)
  const res = await game.World.confirmCityShield([1]);
  assert.ok(res.success);
  assert.equal(res.count, 1);
  assert.equal(res.cost, 200);

  // 验证钻石扣除: 500 - 200 = 300
  assert.equal(game.Core.state.resources.diamond, 300);

  // 验证主城护盾生效约 8 小时
  const cities = game.World.getPlayerCities();
  assert.ok(cities[0].shieldUntil >= now + 8 * 3600 * 1000 - 2000);

  // 验证分城未受影响，依然无护盾
  assert.equal(cities[1].shieldUntil, 0);

  assert.ok(toasts.some(t => t.includes('成功为 主基地 共 1 座城市开启') && t.includes('200 钻石')));
});

test('城防护盾：选择开启所有城市护盾，按城市数量精确扣除钻石 (3座扣除600钻石)', async () => {
  const now = Date.now();
  const { game } = setup({
    resources: { diamond: 1000 },
    cityOverview: {
      cities: [
        { id: 1, citySlot: 0, name: '主基地', main: true, shieldUntil: 0 },
        { id: 2, citySlot: 1, name: '分城A', main: false, shieldUntil: 0 },
        { id: 3, citySlot: 2, name: '分城B', main: false, shieldUntil: 0 }
      ]
    }
  });

  shieldApiResponse(game, [1, 2, 3], { 1: now + 8 * 3600 * 1000, 2: now + 8 * 3600 * 1000, 3: now + 8 * 3600 * 1000 }, 400);
  // 全选 3 座城市
  const res = await game.World.confirmCityShield([1, 2, 3]);
  assert.ok(res.success);
  assert.equal(res.count, 3);
  assert.equal(res.cost, 600);

  // 验证钻石扣除: 1000 - 600 = 400
  assert.equal(game.Core.state.resources.diamond, 400);

  // 验证 3 座城市均被赋予护盾
  const cities = game.World.getPlayerCities();
  cities.forEach(c => {
    assert.ok(c.shieldUntil >= now + 8 * 3600 * 1000 - 2000);
  });
});

test('城防护盾：钻石不足时拒绝开启护盾并提示差额', async () => {
  const { game, toasts } = setup({
    resources: { diamond: 150 }, // 只有 150，开启单城需 200
    cityOverview: {
      cities: [{ id: 1, name: '主城', shieldUntil: 0 }]
    }
  });

  const res = await game.World.confirmCityShield([1]);
  assert.equal(res.success, false);
  assert.equal(res.reason, 'insufficient_diamond');

  // 钻石未被扣除
  assert.equal(game.Core.state.resources.diamond, 150);
  assert.ok(toasts.some(t => t.includes('钻石余额不足') && t.includes('需 200 钻石')));
});

test('城防护盾：生效中再次开启支持时间顺延叠加', async () => {
  const now = Date.now();
  const existingShield = now + 4 * 3600 * 1000; // 已有 4 小时
  const { game } = setup({
    resources: { diamond: 300 },
    cityOverview: {
      cities: [{ id: 1, name: '主城', main: true, shieldUntil: existingShield }]
    }
  });

  shieldApiResponse(game, [1], { 1: existingShield + 8 * 3600 * 1000 }, 100);
  const res = await game.World.confirmCityShield([1]);
  assert.ok(res.success);

  // 叠加 8 小时: 原有 4 小时 + 8 小时 = 12 小时
  const cities = game.World.getPlayerCities();
  assert.ok(cities[0].shieldUntil >= existingShield + 8 * 3600 * 1000 - 2000);
});




test('城防护盾：等待服务端成功后才更新状态，连续点击共用一个请求', async () => {
  const { game } = setup({ resources: { diamond: 500 }, cityOverview: { cities: [{ id: 1, name: '主城', current: true, shieldUntil: 0 }] } });
  const result = shieldApiResponse(game, [1], { 1: 1234567890000 }, 300);
  let resolve, calls = 0;
  game.API.client.post = () => { calls++; return new Promise(done => { resolve = done; }); };
  const first = game.World.confirmCityShield([1]);
  const second = game.World.confirmCityShield([1]);
  assert.equal(first, second);
  assert.equal(calls, 1);
  assert.equal(game.Core.state.resources.diamond, 500);
  assert.equal(game.Core.state.cityState.shieldUntil, 0);
  resolve(result);
  await first;
  assert.equal(game.Core.state.resources.diamond, 300);
  assert.equal(game.Core.state.cityState.shieldUntil, 1234567890000);
});

test('城防护盾：请求失败不扣本地钻石、不产生虚假护盾或成功提示', async () => {
  const { game, toasts } = setup({ resources: { diamond: 500 }, cityOverview: { cities: [{ id: 1, name: '主城', current: true, shieldUntil: 0 }] } });
  game.API = { client: { post: async () => { throw new Error('护盾接口失败'); } } };
  assert.equal((await game.World.confirmCityShield([1])).success, false);
  assert.equal(game.Core.state.resources.diamond, 500);
  assert.equal(game.Core.state.cityState.shieldUntil, 0);
  assert.ok(toasts.includes('护盾接口失败'));
  assert.ok(toasts.every(text => !text.includes('已成功')));
});

test('城防护盾：当前分城护盾不会误用到主城，采用后端 slot 字段', () => {
  const { game } = setup({ cityState: { shieldUntil: 999999 }, cityOverview: { cities: [
    { id: 1, slot: 0, main: true, current: false, shieldUntil: 0 },
    { id: 2, slot: 3, main: false, current: true, shieldUntil: 999999 }
  ] } });
  const cities = game.World.getPlayerCities();
  assert.equal(cities[0].shieldUntil, 0);
  assert.equal(cities[1].slot, 3);
});

function interceptionPreviewFixture() {
  const f = setup({ world: { cityPos: { x: 342, y: 681 }, marches: [], incoming: [],
    _dispatchTarget: { action: 'intercept_scout', marchId: 91, target: { id: 91 }, interceptTarget: { action: 'plunder' } } } });
  const timers = new Map(), listeners = new Map(), requests = [];
  let next = 0, army = {};
  f.context.setTimeout = (run, delay) => { timers.set(++next, { run, delay }); return next; };
  f.context.clearTimeout = id => timers.delete(id);
  const hint = { isConnected: true, textContent: '', setAttribute() {}, querySelector: () => null };
  const container = { addEventListener: (event, fn) => listeners.set(event, fn), removeEventListener: event => listeners.delete(event) };
  f.game.API.client.post = (url, body) => new Promise((resolve, reject) => requests.push({ url, body, resolve, reject }));
  const dt = f.game.Core.state.world._dispatchTarget;
  f.game.World.bindRoutePreview(container, hint, () => f.game.World.interceptionPreviewRequest(army, dt), { interception: true, updateOnInput: true });
  return Object.assign(f, { hint, container, requests, timers,
    select(value) { army = value; for (const id of Object.keys(f.game.DATA.units)) f.nodes.delete('dqty_' + id);
      for (const [id, n] of Object.entries(value)) f.nodes.set('dqty_' + id, { value: String(n) }); listeners.get('input')(); },
    run(delay) { for (const [id, timer] of timers) if (timer.delay === delay) { timers.delete(id); timer.run(); return; } },
    route: { distance: 2.5, seconds: 8, plannedAt: f.game.gameNow(), deployAt: f.game.gameNow() + 8000,
      readyAt: f.game.gameNow() + 13000, clashAt: f.game.gameNow() + 16000, opening: 'ambush', oil: 123, targetX: 340, targetY: 680 } });
}
const settlePreview = async () => { await Promise.resolve(); await Promise.resolve(); };

test('拦截权威预览不为空编队发请求，数量变化重算，旧异步结果不能覆盖新计划', async () => {
  const f = interceptionPreviewFixture();
  f.run(300);
  assert.equal(f.requests.length, 0);
  f.select({ infantry: 10 }); f.run(300);
  assert.equal(f.game.World._dispatchRoutePending, true);
  f.select({ infantry: 20 }); f.run(300);
  assert.equal(f.requests.length, 2);
  f.requests[1].resolve(f.route); await settlePreview();
  assert.equal(f.game.World._currentRoute.oil, 123);
  assert.match(f.hint.textContent, /服务端部署计划.*伏击拦截.*部署到达.*预计交火/);
  f.requests[0].resolve({ ...f.route, oil: 999 }); await settlePreview();
  assert.equal(f.game.World._currentRoute.oil, 123);
  assert.equal(JSON.parse(f.game.World._interceptionPreviewKey).army.infantry, 20);
  f.container._disposeRoutePreview();
});

test('预览中或失败时禁止派遣，失败显示实际原因，关闭页面后忽略未完成响应', async () => {
  const f = interceptionPreviewFixture();
  let dispatched = 0;
  f.game.API.worldDispatch = async () => { dispatched++; };
  f.select({ infantry: 10 }); f.run(300);
  await f.game.World.launchDispatch();
  assert.equal(dispatched, 0);
  f.requests[0].reject(new Error('敌军即将抵城，无法完成出城拦截')); await settlePreview();
  assert.match(f.hint.textContent, /敌军即将抵城/);
  assert.match(f.game.World._dispatchRouteError, /无法完成出城拦截/);
  // 点击派遣可能启动一次新预览，旧失败仍不能放行出征。
  await f.game.World.launchDispatch();
  assert.equal(dispatched, 0);
  f.run(300);
  const pending = f.requests.at(-1);
  f.container._disposeRoutePreview(); f.hint.isConnected = false;
  pending.resolve(f.route); await settlePreview();
  assert.equal(f.game.World._currentRoute, null);
});

test('只有匹配当前兵力且未过期的服务端计划可派遣，过期后重新预览', async () => {
  const f = interceptionPreviewFixture();
  let dispatched = 0;
  f.game.API.worldDispatch = async () => { dispatched++; };
  f.select({ infantry: 10 }); f.run(300);
  f.requests[0].resolve(f.route); await settlePreview();
  f.game.World._currentRoute.plannedAt = f.game.gameNow() - 5001;
  await f.game.World.launchDispatch();
  assert.equal(dispatched, 0);
  f.run(300);
  f.requests.at(-1).resolve({ ...f.route, plannedAt: f.game.gameNow() }); await settlePreview();
  await f.game.World.launchDispatch();
  assert.equal(dispatched, 1);
  f.container._disposeRoutePreview();
});

test('拦截等待准备不会显示可进入城市战场或继续消耗行军令', () => {
  const { game } = setup();
  const now = game.gameNow();
  const march = { id: 400, action: 'intercept_scout', targetKind: 'incoming_march', targetX: 340, targetY: 680,
    fromX: 342, fromY: 681, army: { infantry: 10 }, startAt: now - 20000, deployAt: now - 2000,
    readyAt: now + 3000, arriveAt: now + 10000, interceptionOpening: 'ambush', version: 1 };
  game.Core.state.world.marches.push(march);
  const view = { innerHTML: '' }; game.World.renderAlerts(view);
  assert.match(view.innerHTML, /设伏准备/);
  assert.doesNotMatch(view.innerHTML, /openTactical|data-accelerate-id="400"/);
  assert.equal(game.World.canAccelerateMarch(march, now), false);
});
