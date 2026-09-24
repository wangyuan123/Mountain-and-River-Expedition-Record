const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../js/constants.js'), 'utf8');

function ensureConstants(context) {
  if (!context.Game || !context.Game.Constants) vm.runInContext(source, context, { filename: 'constants.js' });
}

module.exports = ensureConstants;
