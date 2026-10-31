// 隔离合成状态，只调用真实前端展示函数，不发送请求或修改游戏记录。
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const context = { console, document: {}, Game: { gameNow: () => 1800000000000 } };
context.window = context;
vm.createContext(context);
for (const file of ['constants.js', 'data.js', 'core.js']) {
  vm.runInContext(fs.readFileSync(path.resolve(__dirname, '../../frontend/js', file), 'utf8'), context, { filename: file });
}
const core = context.Game.Core;
core.capacity = () => ({ food: 200000, steel: 200000, gold: 20000000 });
core.produceOf = () => 1000;
core.getOfficerByRole = () => null;
core.civilianPopulation = () => 1000;
core.mayorSkillBonus = () => 0;
core.state = { resources: { gold: 20000000, steel: 0 }, tax: 30, officers: [{ salary: 100 }], cityState: {}, world: { incoming: [] } };
const result = { cappedGoldDisplayedNet: core.resourceNetRate('gold'), taxIncomePerHour: 600, salaryPerHour: 100 };
result.peaceSteelDisplayed = core.resourceNetRate('steel');
core.state.cityState.newbieProtectionUntil = 1800003600000;
result.newbieSteelDisplayed = core.resourceNetRate('steel');
core.state.cityState = {};
core.state.world.incoming = [{ id: 'synthetic-display-only' }];
result.incomingSteelDisplayed = core.resourceNetRate('steel');
fs.writeFileSync(path.join(__dirname, 'resource-view.json'), JSON.stringify(result, null, 2) + '\n');
console.log(JSON.stringify(result, null, 2));
