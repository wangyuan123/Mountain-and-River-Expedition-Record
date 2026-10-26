const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../js/constants.js'), 'utf8');
const icons = fs.readFileSync(path.join(__dirname, '../js/icons.js'), 'utf8');
const durationContext = vm.createContext({ window: {} });
vm.runInContext(source, durationContext, { filename: 'constants.js' });
const durationSource = '(function (G) { G.formatDuration = ' +
  durationContext.window.Game.formatDuration.toString() +
  '; G.formatGatherDuration = G.formatDuration; })(window.Game);';
const gatherSource = '(function (G) { G.wildGatherProgress = ' +
  durationContext.window.Game.wildGatherProgress.toString() + '; G.formatGatherElapsed = ' +
  durationContext.window.Game.formatGatherElapsed.toString() + '; G.gatherElapsedText = ' +
  durationContext.window.Game.gatherElapsedText.toString() + '; G.wildGatherAmountText = ' +
  durationContext.window.Game.wildGatherAmountText.toString() + '; })(window.Game);';
const abandonSource = '(function (G) { G.wildAbandonReason = ' +
  durationContext.window.Game.wildAbandonReason.toString() + '; })(window.Game);';

function ensureConstants(context) {
  if (!context.window.Game || !context.window.Game.Constants) vm.runInContext(source, context, { filename: 'constants.js' });
  if (!context.window.Game.formatDuration) vm.runInContext(durationSource, context, { filename: 'duration-formatter.js' });
  if (!context.window.Game.wildGatherProgress) vm.runInContext(gatherSource, context, { filename: 'gather-progress.js' });
  if (!context.window.Game.wildAbandonReason) vm.runInContext(abandonSource, context, { filename: 'wild-abandon.js' });
  if (!context.window.Game.iconHtml) vm.runInContext(icons, context, { filename: 'icons.js' });
}

module.exports = ensureConstants;
