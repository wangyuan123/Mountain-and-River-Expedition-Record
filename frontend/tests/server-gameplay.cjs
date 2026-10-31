const fs = require('node:fs');
const path = require('node:path');
const nativeVm = require('node:vm');
const fixture = require('./fixtures/server-gameplay.json');
const rules = fs.readFileSync(path.join(__dirname, '../js/gameplay-rules.js'), 'utf8');
const constants = fs.readFileSync(path.join(__dirname, '../js/constants.js'), 'utf8');
const coreSource = fs.readFileSync(path.join(__dirname, '../js/core.js'), 'utf8');
const accessorContext = nativeVm.createContext({ window: { Game: { DATA: {} } } });
nativeVm.runInContext(constants, accessorContext);
nativeVm.runInContext(coreSource, accessorContext);
const accessors = ['capacity', 'inventoryCapacity', 'protectCap', 'produceOf', 'foodPerHour', 'resourceNetRate',
  'armyCap', 'ordinaryArmyCap', 'maxMarches', 'firepowerMul', 'isRoutineGatherMarch'];

/** 旧页面交互测试使用后端 GameplayAuthorityTest 导出的固定只读快照，不在测试里重建业务公式。 */
function install(context) {
  const G = context.window && context.window.Game;
  if (!G) return;
  G.DATA ||= {};
  context.window.clearTimeout ||= clearTimeout;
  context.window.setTimeout ||= (callback, delay) => { const timer = setTimeout(callback, delay); timer.unref(); return timer; };
  context.localStorage ||= { getItem() { return null; }, setItem() {}, removeItem() {} };
  // 测试显式替换状态时仍提供固定服务端快照；业务场景差异由各测试覆盖字段。
  if (G.Core && !G.Core.__serverStateFixture) {
    let state = G.Core.state;
    const attach = value => {
      if (value && !value.gameplay) value.gameplay = nativeVm.runInContext('(' + JSON.stringify(fixture.gameplay) + ')', context);
      return value;
    };
    Object.defineProperty(G.Core, 'state', { configurable: true, enumerable: true, get() { return state; }, set(value) { state = attach(value); } });
    Object.defineProperty(G.Core, '__serverStateFixture', { value: true });
    state = attach(state);
  }
  if (!G.ServerRules) nativeVm.runInContext(rules, context, { filename: 'gameplay-rules.js' });
  if (!G.Constants && G.DATA && G.DATA.version) nativeVm.runInContext(constants, context);
  if (G.Core && G.Core.state && !G.Core.state.gameplay) {
    G.Core.state.gameplay = nativeVm.runInContext('(' + JSON.stringify(fixture.gameplay) + ')', context);
  }
  if (G.Core) for (const name of accessors) {
    if (!G.Core[name]) nativeVm.runInContext('(function(G) { G.Core.' + name + ' = ' +
      accessorContext.window.Game.Core[name].toString() + '; })(window.Game);', context);
  }
  if (!G.Constants) return;
  if (G.DATA && G.DATA !== context.__serverCatalogTarget) {
    const snapshot = nativeVm.runInContext('(' + JSON.stringify(fixture) + ')', context);
    // 页面小型桩保留自定义兵种；装备目录仍使用后端 fixture，避免复制属性计算器。
    if (!G.DATA.version) for (const name of ['units', 'buildings', 'technologies', 'skills', 'forts', 'items']) snapshot.gameplay.catalog[name] = {};
    for (const key of ['units', 'buildings', 'techs', 'officerSkills', 'forts', 'items', 'japaneseUnits']) G.DATA[key] ||= {};
    G.DATA.militaryRanks ||= [];
    for (const key of ['equipmentItems', 'equipmentSets', 'equipmentNames', 'equipmentBranches', 'equipmentBranchOrder', 'equipmentSlots', 'equipmentSlotOrder', 'equipmentTiers', 'equipmentTierOrder']) G.Constants[key] ||= {};
    G.Constants.shopItems ||= [];
    G.Constants.equipmentSlotIcons ||= {};
    G.ServerRules.installCatalog(snapshot);
    context.__serverCatalogTarget = G.DATA;
  }
}

module.exports = {
  fixture,
  install,
  vm: {
    ...nativeVm,
    runInContext(source, context, options) {
      const result = nativeVm.runInContext(source, context, options);
      install(context);
      return result;
    }
  }
};
