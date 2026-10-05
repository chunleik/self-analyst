import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
const source = fs.readFileSync(new URL('../../main/resources/desktop-ui/neo4j-sync.js', import.meta.url), 'utf8');
class Element {
  constructor(tag = 'div') { this.tag = tag; this.children = []; this.events = {}; this.classList = { toggle() {} }; }
  set textContent(value) { this.text = String(value); this.children = []; }
  get textContent() { return (this.text || '') + this.children.map(c => c.textContent).join(' '); }
  append(...items) { this.children.push(...items); }
  replaceChildren(...items) { this.children = items; }
  setAttribute(key, value) { this[key] = value; }
  addEventListener(name, action) { this.events[name] = action; }
}
const all = node => [node, ...node.children.flatMap(all)];
const ready = (fingerprint = 'target-a') => ({ state: 'ready', configured: true, targetFingerprint: fingerprint,
  target: { enabled: true, uri: 'bolt://127.0.0.1:7687', database: 'neo4j', namespace: 'synthetic-a', username: 'neo4j', passwordEnv: 'SYNTHETIC_PASSWORD', timeoutSeconds: 15 }, lastResult: {} });
const flush = () => new Promise(resolve => setImmediate(resolve));
function setup(handler = async () => ready()) {
  const root = new Element(), calls = [], confirmations = [], settings = [];
  const context = vm.createContext({ API_BASE: '', document: { createElement: tag => new Element(tag) }, t: key => key,
    window: { confirm: message => { confirmations.push(message); return true; } }, openConfigModal: key => settings.push(key),
    fetch: async (url, options) => { calls.push({ url, options }); const result = await handler(url, options);
      return result && result.httpError ? { ok: false, json: async () => result.data } : { ok: true, json: async () => result }; }
  });
  vm.runInContext(source, context); context.mountNeo4jSync(root);
  return { root, context, calls, confirmations, settings };
}

test('mount and status only read locally; disabled/missing/invalid configuration cannot send', async () => {
  for (const state of ['disabled', 'invalidConfig', 'missingPassword']) {
    const h = setup(async () => ({ ...ready(), state, configured: false }));
    assert.equal(h.calls.length, 0); await h.context.refreshNeo4jStatus();
    assert.equal(h.context.neo4jSync.syncButton.disabled, true);
    await h.context.confirmNeo4jSync(); assert.equal(h.confirmations.length, 0);
    assert.equal(h.calls.length, 1); assert.equal(h.calls[0].options.method, 'GET');
    h.context.neo4jSync.settingsButton.events.click(); assert.deepEqual(h.settings, ['neo4j.enabled']);
  }
});

test('cancel discloses exact refreshed destination and sends no POST', async () => {
  const h = setup(); await h.context.refreshNeo4jStatus();
  let confirmation;
  h.context.window.confirm = value => { confirmation = value; return false; };
  await h.context.confirmNeo4jSync();
  assert.match(confirmation, /bolt:\/\/127\.0\.0\.1:7687/); assert.match(confirmation, /synthetic-a/);
  assert.match(confirmation, /SYNTHETIC_PASSWORD/); assert.match(confirmation, /neo4j.disclosure/);
  assert.match(confirmation, /neo4j.namespaceHelp/); assert.match(h.root.textContent, /neo4j.cancelled/);
  assert.equal(h.calls.every(call => call.options.method === 'GET'), true);
  assert.equal(h.context.neo4jSync.syncButton.disabled, false);
});

test('confirmed send uses latest fingerprint and prevents duplicate clicks while pending', async () => {
  let finish, reads = 0;
  const h = setup(async (url, options) => {
    if (options.method === 'POST') return new Promise(resolve => { finish = resolve; });
    return ready(++reads === 1 ? 'stale' : 'latest');
  });
  await h.context.refreshNeo4jStatus(); const pending = h.context.confirmNeo4jSync(); await flush();
  assert.equal(h.context.neo4jSync.syncButton.disabled, true);
  assert.equal(h.context.neo4jSync.refreshButton.disabled, true);
  await h.context.confirmNeo4jSync(); await h.context.refreshNeo4jStatus();
  const writes = h.calls.filter(call => call.options.method === 'POST'); assert.equal(writes.length, 1);
  assert.deepEqual(JSON.parse(writes[0].options.body), { confirmedTarget: 'latest' });
  assert.equal(writes[0].options.credentials, 'same-origin');
  finish({ ...ready('latest'), state: 'success', lastResult: { success: true, entities: 1, assertions: 0 } }); await pending;
  assert.match(h.root.textContent, /neo4j.completed/); assert.equal(h.context.neo4jSync.syncButton.disabled, false);
});

test('stale confirmation or write failure is localized and needs fresh status', async () => {
  for (const code of ['confirmation', 'running', 'capacity', 'authentication', 'connection', 'failed']) {
    const h = setup(async (url, options) => options.method === 'POST'
      ? { httpError: true, data: { errorCode: 'neo4j.error.' + code, error: 'private graph synthetic-secret' } } : ready());
    await h.context.refreshNeo4jStatus(); await h.context.confirmNeo4jSync();
    assert.match(h.root.textContent, new RegExp('neo4j.error.' + code));
    assert.doesNotMatch(h.root.textContent, /private graph|synthetic-secret|neo4j.completed/);
    assert.equal(h.context.neo4jSync.syncButton.disabled, true);
    assert.equal(h.context.neo4jSync.refreshButton.disabled, false);
    await h.context.refreshNeo4jStatus(); assert.equal(h.context.neo4jSync.syncButton.disabled, false);
  }
});

test('target text cannot inject HTML and unexpected network errors are not echoed', async () => {
  let fail = false;
  const status = ready(); status.target.namespace = '<img src=x onerror=bad()>';
  const h = setup(async () => { if (fail) throw new Error('synthetic-secret'); return status; });
  await h.context.refreshNeo4jStatus();
  assert.match(h.root.textContent, /<img src=x onerror=bad\(\)>/);
  assert.equal(all(h.root).some(node => node.tag === 'img'), false);
  fail = true; await h.context.refreshNeo4jStatus();
  assert.match(h.root.textContent, /neo4j.error.unavailable/); assert.doesNotMatch(h.root.textContent, /synthetic-secret/);
});

test('a late status request cannot overwrite a confirmation or sync result', async () => {
  let release, reads = 0;
  const h = setup(async (url, options) => {
    if (options.method === 'POST') return { ...ready('new'), state: 'success' };
    if (++reads === 2) return new Promise(resolve => { release = resolve; });
    return ready('new');
  });
  await h.context.refreshNeo4jStatus(); const old = h.context.refreshNeo4jStatus();
  await h.context.confirmNeo4jSync(); release({ ...ready('old'), state: 'disabled', configured: false }); await old;
  assert.equal(h.context.neo4jSync.status.targetFingerprint, 'new');
  assert.equal(h.context.neo4jSync.syncButton.disabled, false);
});

test('all disclosure, states, target and error messages exist in both language catalogs', () => {
  const coreCodes = new Set(Object.keys(setup().context.neo4jCoreErrors));
  const keys = [...source.matchAll(/"(neo4j\.[\w.]+)"/g)].map(m => m[1]).filter(k => !k.endsWith('.') && k !== 'neo4j.enabled' && !coreCodes.has(k));
  for (const suffix of ['disabled', 'unconfigured', 'invalid', 'ready', 'running', 'success', 'failed', 'unavailable']) keys.push('neo4j.state.' + suffix);
  for (const suffix of ['uri', 'database', 'namespace', 'username', 'passwordEnv', 'timeoutSeconds']) keys.push('neo4j.target.' + suffix);
  for (const suffix of ['disabled','invalid','credentials','confirmation','targetChanged','running','capacity','authentication','connection','timeout','unavailable','failed']) keys.push('neo4j.error.' + suffix);
  for (const lang of ['zh', 'en']) {
    const catalog = JSON.parse(fs.readFileSync(new URL(`../../main/resources/desktop-ui/locales/${lang}.json`, import.meta.url), 'utf8'));
    for (const key of keys) assert.equal(typeof catalog[key], 'string', `${lang}: ${key}`);
    assert.equal(typeof catalog['config.runtime.next_manual_sync'], 'string');
  }
});


test('last synced generation displays counts, time and its own bounded coverage; failures retain category', async () => {
  const status = { ...ready(), state: 'success', lastResult: { success: true, at: '2026-10-04T13:00:00Z',
    entities: 12, assertions: 7, bytes: 4321, coverage: { wiki: 'truncated', memory: 'current',
      entriesRead: '50', entryLimit: '50', coarseEntriesOmitted: '3', privacyFilteredSegments: '2' } } };
  const h = setup(async () => status);
  h.context.t = (key, parameters) => key + (parameters ? JSON.stringify(parameters) : '');
  await h.context.refreshNeo4jStatus();
  assert.match(h.root.textContent, /2026-10-04T13:00:00Z/);
  assert.match(h.root.textContent, /"entities":12/); assert.match(h.root.textContent, /"assertions":7/);
  assert.match(h.root.textContent, /"bytes":4321/); assert.match(h.root.textContent, /ontology.status.truncated/);
  assert.match(h.root.textContent, /"read":50/); assert.match(h.root.textContent, /"omitted":3/);
  assert.match(h.root.textContent, /ontology.periodNote/);
  status.state = 'error'; status.lastResult = { success: false, code: 'neo4j.authenticationFailed' };
  await h.context.refreshNeo4jStatus();
  assert.match(h.root.textContent, /neo4j.error.authentication/); assert.doesNotMatch(h.root.textContent, /2026-10-04/);
});

test('leaving the panel invalidates a pending read and reopening uses only fresh saved status', async () => {
  for (const rejectOld of [false, true]) {
    let finishOld, reads = 0;
    const h = setup(() => ++reads === 1 ? new Promise((resolve, reject) => {
      finishOld = () => rejectOld ? reject(new Error('obsolete')) : resolve(ready('old'));
    }) : ready('saved-new'));
    const old = h.context.refreshNeo4jStatus();
    h.context.stopNeo4jSync(); h.root.replaceChildren(); h.context.mountNeo4jSync(h.root);
    assert.equal(h.context.neo4jSync.status, null);
    assert.equal(h.context.neo4jSync.syncButton.disabled, true);
    await h.context.refreshNeo4jStatus(); const text = h.root.textContent;
    finishOld(); await old;
    assert.equal(h.context.neo4jSync.status.targetFingerprint, 'saved-new');
    assert.equal(h.root.textContent, text);
    assert.equal(h.calls.every(call => call.options.method === 'GET'), true);
  }
});

test('closing or switching away during the preflight cannot confirm or send afterward', async () => {
  for (const reopen of [false, true]) {
    let finish, reads = 0;
    const h = setup(() => ++reads === 2 ? new Promise(resolve => { finish = resolve; }) : ready('current'));
    await h.context.refreshNeo4jStatus(); const pending = h.context.confirmNeo4jSync();
    h.context.stopNeo4jSync();
    if (reopen) { h.root.replaceChildren(); h.context.mountNeo4jSync(h.root); await h.context.refreshNeo4jStatus(); }
    finish(ready('obsolete')); await pending;
    assert.equal(h.confirmations.length, 0);
    assert.equal(h.calls.some(call => call.options.method === 'POST'), false);
    assert.equal(h.context.neo4jSync.busy, false);
    assert.equal(h.context.neo4jSync.status?.targetFingerprint || null, reopen ? 'current' : null);
  }
});

test('confirmed send keeps its lock across reopen and completion refreshes the current target', async () => {
  for (const fail of [false, true]) {
    let finish, activeTarget = 'first';
    const h = setup((url, options) => options.method === 'POST'
      ? new Promise((resolve, reject) => { finish = () => fail ? reject(new Error('old secret')) : resolve({ ...ready('first'), state: 'success' }); })
      : ready(activeTarget));
    await h.context.refreshNeo4jStatus(); const pending = h.context.confirmNeo4jSync(); await flush();
    h.context.stopNeo4jSync(); h.root.replaceChildren(); h.context.mountNeo4jSync(h.root);
    assert.match(h.root.textContent, /neo4j.syncing/);
    assert.equal(h.context.neo4jSync.syncButton.disabled, true);
    assert.equal(h.context.neo4jSync.refreshButton.disabled, true);
    await h.context.confirmNeo4jSync(); await h.context.refreshNeo4jStatus();
    assert.equal(h.calls.filter(call => call.options.method === 'POST').length, 1);
    activeTarget = 'saved-second'; finish(); await pending;
    assert.equal(h.context.neo4jSync.status.targetFingerprint, 'saved-second');
    assert.equal(h.context.neo4jSync.syncButton.disabled, false);
    assert.equal(h.context.neo4jSync.refreshButton.disabled, false);
    assert.doesNotMatch(h.root.textContent, /neo4j.completed|old secret/);
  }
});

test('confirmed send finishing after close does not reload or update a detached panel', async () => {
  let finish;
  const h = setup((url, options) => options.method === 'POST' ? new Promise(resolve => { finish = resolve; }) : ready());
  await h.context.refreshNeo4jStatus(); const pending = h.context.confirmNeo4jSync(); await flush();
  h.context.stopNeo4jSync(); const text = h.root.textContent, count = h.calls.length;
  finish({ ...ready(), state: 'success' }); await pending;
  assert.equal(h.calls.length, count); assert.equal(h.root.textContent, text);
  assert.equal(h.context.neo4jSync.busy, false); assert.equal(h.context.neo4jSync.status, null);
  await h.context.confirmNeo4jSync(); assert.equal(h.calls.length, count);
});

test('abandoned preflight does not lock a reopened panel or unlock a newer active send', async () => {
  for (const failOld of [false, true]) {
    let finishOldRead, finishSend, reads = 0;
    const h = setup((url, options) => {
      if (options.method === 'POST') return new Promise(resolve => { finishSend = resolve; });
      if (++reads === 2) return new Promise((resolve, reject) => { finishOldRead = value => failOld ? reject(new Error('obsolete')) : resolve(value); });
      return ready('current');
    });
    await h.context.refreshNeo4jStatus(); const old = h.context.confirmNeo4jSync();
    h.context.stopNeo4jSync(); h.root.replaceChildren(); h.context.mountNeo4jSync(h.root);
    assert.equal(h.context.neo4jSync.busy, false); assert.equal(h.context.neo4jSync.refreshButton.disabled, false);
    await h.context.refreshNeo4jStatus(); const current = h.context.confirmNeo4jSync(); await flush();
    assert.equal(h.context.neo4jSync.busy, true);
    finishOldRead(ready('obsolete')); await old;
    assert.equal(h.context.neo4jSync.busy, true); assert.equal(h.context.neo4jSync.syncButton.disabled, true);
    assert.equal(h.confirmations.length, 1); assert.equal(h.calls.filter(c => c.options.method === 'POST').length, 1);
    finishSend({ ...ready('current'), state: 'success' }); await current;
    assert.equal(h.context.neo4jSync.busy, false);
  }
});
