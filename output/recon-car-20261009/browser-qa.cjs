const { chromium } = require('/Users/chenjuan/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

// 真实前端、模拟接口；验证用户交互和不同画幅，不连接真实游戏服务。
(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' });
  const results = [];
  try {
    for (const viewport of [{ width: 1280, height: 900 }, { width: 390, height: 844 }]) {
      const page = await browser.newPage({ viewport });
      const errors = [];
      page.on('pageerror', error => errors.push(error.message));
      await page.goto(pathToFileURL(path.join(__dirname, 'preview.html')).href);
      await page.waitForFunction(() => Game.Core.state.world._dispatchTarget.groundRecon.available);
      await page.locator('input[name="dpReconMode"][value="land"]').check();
      assert.equal(await page.locator('#dqty_scout').count(), 0);
      await page.locator('#dqty_recon_car').fill('10');
      await page.locator('#dqty_recon_car').dispatchEvent('input');
      await page.waitForFunction(() => !!Game.World._currentRoute);
      await page.screenshot({ path: path.join(__dirname, 'dispatch-' + viewport.width + '.png'), fullPage: true });
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
      await page.getByRole('button', { name: '派出侦查部队', exact: true }).click();
      await page.waitForFunction(() => !!lastDispatch);
      assert.deepEqual(await page.evaluate(() => lastDispatch.army), { recon_car: 10 });
      await page.getByRole('button', { name: '陆路不通', exact: true }).click();
      await page.waitForFunction(() => !!Game.Core.state.world._dispatchTarget.groundRecon.error);
      assert.equal(await page.locator('input[value="land"]').isDisabled(), true);
      assert.match(await page.locator('[data-ground-recon-status]').innerText(), /地面侦查无法抵达/);
      await page.locator('#dqty_recon_car').fill('12');
      await page.evaluate(() => { blocked = false; });
      await page.getByRole('button', { name: '重新检查陆路', exact: true }).click();
      await page.waitForFunction(() => Game.Core.state.world._dispatchTarget.groundRecon.available);
      assert.equal(await page.locator('#dqty_recon_car').inputValue(), '12');
      await page.getByRole('button', { name: '仅装甲车', exact: true }).click();
      await page.waitForFunction(() => Game.Core.state.world._dispatchTarget.groundRecon.available);
      assert.equal(await page.locator('input[value="land"]').isChecked(), true);
      await page.getByRole('button', { name: '车辆报告', exact: true }).click();
      assert.match(await page.locator('#view').innerText(), /地面侦查/);
      assert.match(await page.locator('#view').innerText(), /地面遭遇记录/);
      assert.doesNotMatch(await page.locator('#view').innerText(), /击落| 架/);
      await page.screenshot({ path: path.join(__dirname, 'report-' + viewport.width + '.png'), fullPage: true });
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
      assert.deepEqual(errors, []);
      results.push({ viewport, dispatch: true, blockedGround: true, routeRetry: true, carOnlyDefault: true, report: true, noOverflow: true, errors });
      await page.close();
    }
    fs.writeFileSync(path.join(__dirname, 'browser-checks.json'), JSON.stringify(results, null, 2) + '\n');
    process.stdout.write(JSON.stringify(results, null, 2) + '\n');
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
