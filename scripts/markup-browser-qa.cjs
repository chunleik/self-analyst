// 手动验收：先以 -Ddocument.visual.samples=true 运行 MarkupDocumentTest。
// NODE_PATH 指向已安装的 playwright；传入 self-analyst-app/target/document-samples。
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

(async () => {
  const samples = path.resolve(process.argv[2] || 'target/document-samples');
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  try {
    const context = await browser.newContext({ offline: true, viewport: { width: 1000, height: 760 } });
    const page = await context.newPage();
    const requests = [], errors = [], consoleErrors = [];
    page.on('request', request => { if (/^https?:/.test(request.url())) requests.push(request.url()); });
    page.on('pageerror', error => errors.push(error.message));
    page.on('console', message => { if (message.type() === 'error') consoleErrors.push(message.text()); });
    await page.goto(pathToFileURL(path.join(samples, 'interactive.html')).href);
    await page.locator('#value').fill('8');
    await page.locator('#calculate').click();
    assert.equal(await page.locator('#result').textContent(), '16');
    assert.equal(await page.locator('#bar').getAttribute('width'), '160');
    assert.equal(await page.locator('#pending').isVisible(), true);
    await page.locator('#filter').click();
    assert.equal(await page.locator('#pending').isVisible(), false);
    await page.screenshot({ path: path.join(samples, 'interactive.png'), fullPage: true });
    await page.goto(pathToFileURL(path.join(samples, 'diagram.svg')).href);
    assert.equal(await page.locator('svg').count(), 1);
    assert.equal(await page.locator('use').count(), 2);
    assert.equal(await page.locator('text').first().textContent(), '输入数据');
    const bounds = await page.locator('svg').evaluate(svg => {
      const box = svg.getBBox(); return { width: box.width, height: box.height };
    });
    assert.ok(bounds.width > 0 && bounds.height > 0);
    console.log('SVG viewport', await page.evaluate(() => ({ width: innerWidth, height: innerHeight,
      scrollWidth: document.documentElement.scrollWidth, scrollHeight: document.documentElement.scrollHeight })));
    await page.screenshot({ path: path.join(samples, 'diagram.png') });
    assert.deepEqual(requests, []);
    assert.deepEqual(errors, []);
    assert.deepEqual(consoleErrors, []);
    const result = { browser: browser.version(), offline: true, calculation: 16, barWidth: 160,
      filter: 'passed', svgBounds: bounds, externalRequests: requests, pageErrors: errors, consoleErrors };
    fs.writeFileSync(path.join(samples, 'browser-qa.json'), JSON.stringify(result, null, 2));
    console.log(JSON.stringify(result, null, 2));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
