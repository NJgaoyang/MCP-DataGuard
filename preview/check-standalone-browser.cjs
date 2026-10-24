const { chromium } = require('/tmp/dataguard-browser-check/node_modules/playwright');
const fs = require('fs');

const base = process.env.DG_STANDALONE_TEST_URL || 'http://127.0.0.1:18081';
const output = '/root/MCP-DataGuard/preview/standalone-ui-verified.png';

(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1080 }, deviceScaleFactor: 1 });
  const errors = [];
  page.on('pageerror', error => errors.push(String(error)));
  await page.goto(base + '/login.html', { waitUntil: 'networkidle' });
  await page.locator('#username').fill('admin');
  await page.locator('#password').fill(process.env.DG_MONOLITH_TEST_NEW_PASSWORD || 'ChangeMe_2026!NoReuse');
  await page.locator('button[type=submit]').click();
  await page.waitForURL(base + '/index.html', { timeout: 15000 });
  await page.getByRole('heading', { name: '数据概览' }).waitFor();
  await page.screenshot({ path: output, fullPage: true });

  for (const name of ['Agent 管理', '审批中心']) {
    await page.getByRole('button', { name: new RegExp(name) }).first().click();
    await page.waitForTimeout(250);
    if (!(await page.locator('main').innerText()).includes(name)) throw new Error('Navigation failed: ' + name);
  }
  await page.getByRole('button', { name: /数据源管理/ }).first().click();
  await page.waitForTimeout(250);
  await page.getByRole('button', { name: '新建数据源' }).click();
  await page.getByRole('dialog', { name: '新建 MySQL 数据源' }).waitFor();
  for (const label of ['名称', '编码', 'IP 或主机名', '端口', '数据库', '用户名', '密码']) {
    if (!(await page.getByRole('dialog').innerText()).includes(label)) throw new Error('Missing source form field: ' + label);
  }
  await page.keyboard.press('Escape');
  if (errors.length) throw new Error('Browser errors: ' + errors.join('; '));
  if (!fs.existsSync(output)) throw new Error('Screenshot not produced');
  await browser.close();
  console.log('STANDALONE_UI_BROWSER_CHECK_PASS');
})().catch(async error => { console.error(error); process.exitCode = 1; });
