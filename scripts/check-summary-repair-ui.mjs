/** Opt-in synthetic browser smoke test. Uses existing Edge/Chromium; never downloads a browser. */
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { fileURLToPath } from 'node:url';
import { startRepairReviewServer } from '../self-analyst-app/src/test/js/summary-repair-review-server.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(check, description, timeout = 25000) {
  const deadline = Date.now() + timeout;
  let last;
  while (Date.now() < deadline) {
    try { const value = await check(); if (value) return value; } catch (error) { last = error; }
    await sleep(100);
  }
  throw new Error('Timed out: ' + description + (last ? ': ' + last.message : ''));
}
async function executable() {
  const candidates = [process.env.SUMMARY_REPAIR_BROWSER,
    process.env['ProgramFiles(x86)'] && path.join(process.env['ProgramFiles(x86)'], 'Microsoft/Edge/Application/msedge.exe'),
    process.env.ProgramFiles && path.join(process.env.ProgramFiles, 'Microsoft/Edge/Application/msedge.exe')].filter(Boolean);
  for (const candidate of candidates) { try { await fs.access(candidate); return candidate; } catch {} }
  throw new Error('Installed Microsoft Edge was not found. Set SUMMARY_REPAIR_BROWSER to an existing browser executable.');
}
async function connect(url) {
  const socket = new WebSocket(url);
  await new Promise((resolve, reject) => { socket.addEventListener('open', resolve, { once: true }); socket.addEventListener('error', reject, { once: true }); });
  let sequence = 0;
  const pending = new Map();
  const errors = [];
  socket.addEventListener('message', event => {
    const message = JSON.parse(event.data);
    if (message.method === 'Runtime.exceptionThrown') {
      errors.push(message.params.exceptionDetails); if (errors.length > 10) errors.shift();
    }
    const request = pending.get(message.id);
    if (!request) return;
    pending.delete(message.id); clearTimeout(request.timer);
    if (message.error) request.reject(new Error(JSON.stringify(message.error))); else request.resolve(message.result);
  });
  socket.addEventListener('close', () => { for (const request of pending.values()) { clearTimeout(request.timer); request.reject(new Error('Browser closed')); } pending.clear(); });
  return { errors, close: () => socket.close(), send(method, params = {}) {
    const id = ++sequence;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { pending.delete(id); reject(new Error('CDP timeout: ' + method)); }, 15000);
      pending.set(id, { resolve, reject, timer }); socket.send(JSON.stringify({ id, method, params }));
    });
  } };
}

export async function checkSummaryRepairUi() {
  const output = path.join(root, 'artifacts/summary-repair-ui'); await fs.mkdir(output, { recursive: true });
  const profile = await fs.mkdtemp(path.join(os.tmpdir(), 'selfanalyst-summary-repair-'));
  const server = startRepairReviewServer(); if (!server.listening) await once(server, 'listening');
  const port = server.address().port;
  let child, cdp;
  const log = [], reports = [];
  try {
    child = spawn(await executable(), ['--headless=new', '--disable-gpu', '--disable-extensions', '--no-first-run', '--no-default-browser-check',
      '--remote-debugging-port=0', '--remote-debugging-address=127.0.0.1', '--user-data-dir=' + profile, 'about:blank'], { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
    child.stdout.on('data', chunk => log.push(chunk.toString())); child.stderr.on('data', chunk => log.push(chunk.toString()));
    let spawnError; child.on('error', error => { spawnError = error; });
    const debugPort = await until(async () => {
      if (spawnError) throw spawnError;
      const text = await fs.readFile(path.join(profile, 'DevToolsActivePort'), 'utf8'); return Number(text.split('\n')[0]);
    }, 'Edge debugging port');
    const created = await fetch('http://127.0.0.1:' + debugPort + '/json/new?about:blank', { method: 'PUT' }).then(response => response.json());
    cdp = await connect(created.webSocketDebuggerUrl);
    await cdp.send('Page.enable'); await cdp.send('Runtime.enable');
    const evaluate = async expression => {
      const result = await cdp.send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
      if (result.exceptionDetails) throw new Error(result.exceptionDetails.text + ': ' + JSON.stringify(result.exceptionDetails.exception));
      return result.result.value;
    };
    const surface = `document.querySelector('#chat-fallback') && !document.querySelector('#chat-fallback').classList.contains('hidden') ? document.querySelector('#chat-fallback-thread') : document.querySelector('#deep-chat').shadowRoot`;
    async function open(name, query, width = 1440) {
      await cdp.send('Emulation.setDeviceMetricsOverride', { width, height: 1000, deviceScaleFactor: 1, mobile: false });
      await cdp.send('Page.navigate', { url: 'http://127.0.0.1:' + port + '/?' + query });
      await until(() => evaluate(`location.search === ${JSON.stringify("?" + query)} && document.readyState === "complete" && !!document.getElementById('fixture-ready')`), name + ' fixture ready');
      if (!query.includes('page=dashboard')) {
        const expectedRoot = query.includes('surface=fallback') ? "document.querySelector('#chat-fallback-thread')" : "document.querySelector('#deep-chat').shadowRoot";
        await until(() => evaluate(`!!((${expectedRoot}) && (${expectedRoot}).querySelector('.summary-repair-card'))`), name + ' repair card');
        if (!query.includes('surface=fallback')) assert.equal(await evaluate('deepChatAdapterState.upgradeFailed'), false, name + ' unexpectedly fell back');
      }
      await until(() => evaluate('document.fonts.status === "loaded"'), name + ' fonts');
    }
    async function screenshot(name, bottom = false) {
      await evaluate(`(() => { const root = ${surface}; if (!root) return true; const scroll = root === document.querySelector('#chat-fallback-thread') ? root : root.querySelector('#messages'); if (scroll) scroll.scrollTop = ${bottom ? "scroll.scrollHeight" : "0"}; return true; })()`);
      const image = await cdp.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false });
      await fs.writeFile(path.join(output, name + '.png'), Buffer.from(image.data, 'base64'));
      const size = await evaluate(`({viewport:innerWidth, page:document.documentElement.scrollWidth})`);
      assert.ok(size.page <= size.viewport + 2, name + ' has document-level horizontal overflow');
    }
    async function click(text) {
      const clicked = await evaluate(`(() => { const root = ${surface}; const button = Array.from(root.querySelectorAll('button')).find(item => item.textContent === ${JSON.stringify(text)}); if (!button || button.disabled) return false; button.click(); return true; })()`);
      assert.ok(clicked, 'Missing or disabled button: ' + text);
    }
    async function waitText(text) {
      return until(() => evaluate(`(${surface}).textContent.includes(${JSON.stringify(text)})`), text);
    }
    async function report(name) {
      const data = await fetch('http://127.0.0.1:' + port + '/fixture/report').then(response => response.json());
      assert.equal(data.calls.filter(call => /^\/desktop\/chat(?:\/stream)?$/.test(call.path)).length, 0, name + ' called a model');
      reports.push({ name, draftState: data.repair.draft.state, calls: data.calls.map(({ path, method }) => ({ path, method })) });
      return data;
    }
    for (const fixture of [
      ['dashboard-en', 'page=dashboard&lang=en'], ['dashboard-zh-narrow', 'page=dashboard&lang=zh', 680],
      ['quality-enhanced-en', 'scenario=quality&lang=en'], ['quality-fallback-zh', 'scenario=quality&surface=fallback&lang=zh'],
      ['budget-fallback-narrow', 'scenario=budget&surface=fallback&lang=en', 680],
      ['success-enhanced-zh', 'scenario=success&lang=zh'],
    ]) {
      await open(...fixture); await screenshot(fixture[0]);
      if (fixture[1].includes('page=dashboard')) {
        const text = await evaluate('document.getElementById("timeline-body").textContent');
        assert.ok(!text.includes('Unidentified application activity') && !text.includes('Incomplete AFK coverage'));
        assert.ok(text.includes('AFK detector')); assert.ok(text.includes('16:00'));
      }
      await report(fixture[0]);
    }
    for (const lang of ['zh', 'en']) {
      const name = 'blocked-day-' + lang;
      await open(name, 'page=dashboard&scenario=half-budget&collapsed=true&lang=' + lang, 680);
      const button = await evaluate(`(() => { const element = document.querySelector('.timeline-repair-actions button');
        const rect = element.getBoundingClientRect(); return { visible: rect.width > 0 && rect.height > 0,
          right: rect.right, width: innerWidth, expanded: element.closest('.timeline-entry').classList.contains('expanded'),
          text: element.textContent }; })()`);
      assert.equal(button.visible, true); assert.equal(button.expanded, false);
      assert.ok(button.right <= button.width); assert.ok(button.text.includes('12:00'));
      await screenshot(name); await report(name);
      await evaluate(`document.querySelector('.timeline-repair-actions button').click()`);
      await until(() => evaluate(`state.tab === 'chat' && state.chatSessions.some(session => session.contextSnapshot && session.contextSnapshot.target && session.contextSnapshot.target.entryId === 'synthetic-half')`), 'half-day repair route');
      const data = await report(name + '-opened');
      const request = data.calls.find(call => call.path === '/desktop/summary-repairs' && call.method === 'POST');
      assert.equal(request.body.targetId, 'synthetic-half');
    }
    await open('local-empty', 'scenario=empty&surface=fallback&lang=en'); await screenshot('local-empty');
    await click('Add task');
    await evaluate(`(() => { const root = ${surface}; for (const [label, text] of [['Overall summary','Completed the synthetic review.'],['Primary topic','Synthetic review'],['Topic title','Synthetic review'],['Task description','Completed the synthetic review.']]) { const wrapper = Array.from(root.querySelectorAll('label')).find(item => item.firstChild && item.firstChild.textContent === label); const input = wrapper && wrapper.querySelector('textarea,input'); if (!input) throw new Error('Missing field ' + label); input.value = text; input.dispatchEvent(new Event('input',{bubbles:true})); } const select = root.querySelector('select'); select.value='user_confirmed'; select.dispatchEvent(new Event('change',{bubbles:true})); return true; })()`);
    await until(() => evaluate(`Array.from((${surface}).querySelectorAll('button')).some(item => item.textContent==='Confirm this exact statement' && !item.disabled)`), 'local edit completed');
    await click('Confirm this exact statement'); await waitText('Confirmed by you');
    await click('Validate locally'); await waitText('Validation passed.'); await screenshot('local-valid', true);
    await click('Save revision'); await waitText('Saved revision 4.'); await screenshot('local-saved', true);
    const saved = await report('local-saved');
    for (const action of ['draft', 'confirm', 'validate', 'commit']) assert.ok(saved.calls.some(call => call.path.endsWith('/' + action)), action + ' API was not exercised');
    assert.equal(saved.repair.draft.state, 'committed');
    await open('commit-conflict', 'scenario=commit-conflict&surface=fallback&lang=en', 680);
    await click('Validate locally'); await waitText('Validation passed.'); await click('Save revision');
    await waitText('The target or draft changed.'); await screenshot('save-conflict-narrow', true);
    await click('Read latest state'); await waitText('Latest saved summary'); await screenshot('conflict-latest', true); await report('conflict');
    await fs.writeFile(path.join(output, 'verification.json'), JSON.stringify({ syntheticOnly: true, modelCalls: 0, reports }, null, 2));
    console.log('Synthetic summary-repair UI smoke passed; screenshots: ' + output);
  } catch (error) {
    if (cdp) {
      try {
        const result = await cdp.send('Runtime.evaluate', { returnByValue: true, expression: `(() => {
          const element = document.querySelector('#deep-chat'); const root = element && element.shadowRoot;
          return { url: location.href, ready: document.readyState, body: document.body.innerText.slice(0, 2500),
            fallback: document.querySelector('#chat-fallback').className,
            deepRoot: root ? root.innerHTML.slice(0, 2500) : null,
            messages: root && !!root.querySelector('#messages'),
            slots: root && root.querySelectorAll('[data-summary-repair-session]').length,
            cards: root && root.querySelectorAll('.summary-repair-card').length,
            session: typeof state !== 'undefined' && state.activeChatSessionId,
            upgradeFailed: typeof deepChatAdapterState !== 'undefined' && deepChatAdapterState.upgradeFailed };
        })()` });
        const diagnostics = { failure: error.message, dom: result.result.value, errors: cdp.errors };
        await fs.writeFile(path.join(output, 'failure.json'), JSON.stringify(diagnostics, null, 2));
        console.error('SUMMARY_REPAIR_UI_FAILURE ' + JSON.stringify(diagnostics));
        const image = await cdp.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false });
        await fs.writeFile(path.join(output, 'failure.png'), Buffer.from(image.data, 'base64'));
      } catch (diagnosticError) { console.error('UI diagnostic capture failed: ' + diagnosticError.message); }
    }
    throw error;
  } finally {
    await fs.writeFile(path.join(output, 'browser.log'), log.join(''));
    if (cdp) {
      await cdp.send('Browser.close').catch(() => {});
      cdp.close();
      if (child && child.exitCode == null) await Promise.race([once(child, 'exit'), sleep(5000)]);
    }
    if (child && child.exitCode == null) { child.kill(); await Promise.race([once(child, 'exit'), sleep(5000)]); }
    await new Promise(resolve => server.close(resolve));
    await fs.rm(profile, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 });
  }
}

if (!process.env.NODE_TEST_CONTEXT && process.argv.includes('--run')) await checkSummaryRepairUi();
else if (path.resolve(process.argv[1] || '') === fileURLToPath(import.meta.url)) console.log('Opt-in: node scripts/check-summary-repair-ui.mjs --run');
