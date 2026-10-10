const assert = require('node:assert/strict');
const fs = require('node:fs');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

// Run from backend/ after BrowserSmokeApplication writes its readiness file.
(async () => {
  const ready = JSON.parse(fs.readFileSync('target/browser-smoke.json', 'utf8'));
  assert.deepEqual(Object.keys(ready), ['baseUrl']);
  assert.match(ready.baseUrl, /^http:\/\/127\.0\.0\.1:\d+$/);
  const browser = await chromium.launch({ headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1050 } });
    const errors = [];
    const resources = new Set();
    page.on('pageerror', error => errors.push(error.message));
    page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
    page.on('requestfailed', request => errors.push(`${request.url()}: ${request.failure()?.errorText}`));
    page.on('response', response => {
      if (response.status() >= 400) errors.push(`${response.status()} ${response.url()}`);
      if (/\.(css|js)(\?|$)/.test(response.url()) && response.status() === 200) resources.add(response.url());
    });
    const documentResponse = await page.goto(`${ready.baseUrl}/doc.html`);
    assert.equal(documentResponse.status(), 200);
    await page.getByText('学院科创空间管理系统 API', { exact: true }).first().waitFor();
    assert.match(await page.locator('body').innerText(), /innovation-space/);
    await page.getByText('仅测试探针', { exact: true }).click();
    await page.getByText('测试连通性', { exact: true }).click();
    await page.getByText('调试', { exact: true }).click();
    const received = page.waitForResponse(response =>
      response.url() === `${ready.baseUrl}/api/v1/test-probe/ping` && response.request().method() === 'GET');
    await page.getByRole('button', { name: /发\s*送/ }).click();
    const response = await received;
    assert.equal(response.status(), 200);
    assert.deepEqual(await response.json(), { code: 'OK', message: 'Success', data: 'pong' });
    await page.waitForFunction(() => document.body.innerText.includes('pong'));
    assert.ok([...resources].some(url => url.endsWith('.css')));
    assert.ok([...resources].some(url => url.endsWith('.js')));
    assert.deepEqual(errors, [], 'Knife4j must have no JS, failed-request or HTTP errors');
    fs.mkdirSync('target/verification', { recursive: true });
    await page.screenshot({ path: 'target/verification/knife4j-dev.png', fullPage: true });
    const result = { page: 'PASS', group: 'innovation-space', browserPing: 'PASS', status: 200,
      code: 'OK', data: 'pong', errors, resourceCount: resources.size };
    fs.writeFileSync('target/verification/knife4j-browser.json', JSON.stringify(result, null, 2));
    console.log(JSON.stringify(result));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
