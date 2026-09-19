import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const source = fs.readFileSync(new URL('../../main/resources/desktop-ui/runtime-storage.js', import.meta.url), 'utf8');
class Element {
  constructor(tag = 'div') { this.tag = tag; this.children = []; this.style = {}; this.classList = { add() {}, remove() {} }; }
  set textContent(value) { this.text = value; this.children = []; }
  get textContent() { return (this.text || '') + this.children.map(c => c.textContent).join(' '); }
  appendChild(child) { this.children.push(child); child.parent = this; return child; }
  remove() { this.parent.children = this.parent.children.filter(c => c !== this); }
  setAttribute(key, value) { this[key] = value; }
  addEventListener(name, fn) { this[name] = fn; }
}
const all = node => [node, ...node.children.flatMap(all)];
const button = (node, label) => all(node).find(c => c.tag === 'button' && c.textContent === label);
const findClass = (node, name) => all(node).find(c => (c.className || '').split(' ').includes(name));
const flush = () => new Promise(resolve => setImmediate(resolve));
const capacity = () => ({ mode: 'merged', migration: 'complete', migrationId: 'test-migration',
  activeBytes: 1048576, auxiliaryBytes: 524288, backupBytes: 2097152, stagingBytes: 0 });
function setup({ invoke, handler, confirm = () => true } = {}) {
  const container = new Element(), requests = [];
  const context = vm.createContext({ API_BASE: '', t: key => key,
    document: { getElementById: () => container, createElement: tag => new Element(tag) },
    window: { confirm, ...(invoke ? { __TAURI__: { core: { invoke } } } : {}) },
    fetch: async (url, options) => {
      requests.push({ url, options });
      if (handler) { const response = handler(url, options); if (response !== undefined) return response; }
      return { ok: true, json: async () => url.endsWith('runtime-storage')
        ? { mode: 'portable', runtimeRoot: 'D:/中文', dataRoot: 'D:/中文/data' } : capacity() };
    } });
  vm.runInContext(source, context);
  return { context, container, requests };
}

test('all runtime storage labels exist in both browser catalogs', () => {
  for (const language of ['zh', 'en']) {
    const catalog = JSON.parse(fs.readFileSync(new URL(`../../main/resources/desktop-ui/locales/${language}.json`, import.meta.url), 'utf8'));
    for (const key of [...source.matchAll(/t\("(storage\.[^"]+)"\)/g)].map(m => m[1]).filter(k => !k.endsWith('.'))) {
      assert.equal(typeof catalog[key], 'string', `${language}: ${key}`);
    }
  }
});
test('browser shows actual paths, capacity cards and native unavailability', async () => {
  const h = setup(); await h.context.loadRuntimeStorage();
  assert.match(h.container.textContent, /D:\/中文\/data/);
  assert.match(h.container.textContent, /storage.nativeUnavailable/);
  assert.equal(button(h.container, 'storage.open'), undefined);
  assert.match(findClass(h.container, 'storage-total').textContent, /^3.5/);
  assert.deepEqual(findClass(h.container, 'storage-stats').children.map(c => c.textContent),
    ['storage.active 1.0 MiB', 'storage.auxiliary 0.5 MiB', 'storage.backups 2.0 MiB', 'storage.staging 0.0 MiB']);
});
test('native open passes no path, blocks duplicates and reports failure with retry', async () => {
  let reject; const calls = [];
  const h = setup({ invoke: (...args) => { calls.push(args); return new Promise((_, fail) => { reject = fail; }); } });
  await h.context.loadRuntimeStorage();
  const open = button(h.container, 'storage.open'); open.click(); open.click(); await flush();
  assert.deepEqual(calls, [['open_data_directory']]); assert.equal(open.disabled, true);
  reject(new Error('failed')); await flush();
  assert.match(h.container.textContent, /storage.openFailed/); assert.equal(open.disabled, false);
});
test('native completion restores button without a false error', async () => {
  let done; const h = setup({ invoke: () => new Promise(resolve => { done = resolve; }) });
  await h.context.loadRuntimeStorage(); const open = button(h.container, 'storage.open');
  open.click(); await flush(); assert.equal(open.disabled, true);
  done(); await flush(); assert.equal(open.disabled, false); assert.doesNotMatch(h.container.textContent, /storage.openFailed/);
});
test('cleanup requires confirmation, sends migration id and keeps data on failure', async () => {
  let confirmed = false;
  const h = setup({ confirm: () => confirmed, handler: (_, options) => options ? { ok: false } : undefined });
  await h.context.loadRuntimeStorage(); const clean = button(h.container, 'storage.cleanBackups');
  clean.click(); assert.equal(h.requests.length, 2);
  confirmed = true; clean.click(); clean.click(); await flush();
  assert.equal(h.requests.length, 3);
  assert.equal(h.requests[2].url, '/desktop/storage/backups/cleanup');
  assert.deepEqual(JSON.parse(h.requests[2].options.body), { migrationId: 'test-migration' });
  assert.match(h.container.textContent, /storage.cleanFailed/); assert.equal(clean.disabled, false);
  assert.match(findClass(h.container, 'storage-total').textContent, /^3.5/);
});
test('successful cleanup reloads real statistics and removes obsolete cleanup action', async () => {
  let cleaned = false;
  const h = setup({ handler: (url, options) => {
    if (options) { cleaned = true; return { ok: true }; }
    if (url.endsWith('/status')) return { ok: true, json: async () => ({ ...capacity(), backupBytes: cleaned ? 0 : 2097152 }) };
  } });
  await h.context.loadRuntimeStorage(); button(h.container, 'storage.cleanBackups').click(); await flush();
  assert.match(findClass(h.container, 'storage-total').textContent, /^1.5/);
  assert.equal(findClass(h.container, 'storage-backup'), undefined);
  assert.match(findClass(h.container, 'storage-stats').textContent, /storage.backups 0.0 MiB/);
  assert.equal(button(h.container, 'storage.cleanBackups'), undefined);
});
test('location and capacity failures are explicit; missing capacity is not zero', async () => {
  for (const variant of ['location', 'capacity', 'missing']) {
    const h = setup({ handler: url => {
      if (variant === 'location') return { ok: false };
      if (url.endsWith('/status')) return variant === 'capacity' ? { ok: false } : { ok: true, json: async () => ({ mode: 'merged' }) };
    } });
    await h.context.loadRuntimeStorage();
    assert.match(h.container.textContent, variant === 'location' ? /storage.loadFailed/ : /storage.capacityFailed/);
    assert.equal(findClass(h.container, 'storage-total'), undefined);
    assert.ok(button(h.container, 'storage.refresh'));
  }
});
test('non-merged storage and incomplete migration never offer cleanup', async () => {
  for (const status of [{ mode: 'legacy' }, { ...capacity(), migration: 'pending' }]) {
    const h = setup({ handler: url => url.endsWith('/status') ? { ok: true, json: async () => status } : undefined });
    await h.context.loadRuntimeStorage();
    assert.equal(button(h.container, 'storage.cleanBackups'), undefined);
    if (status.mode === 'legacy') assert.equal(findClass(h.container, 'storage-usage'), undefined);
  }
});

test('zero backups hides the entire cleanup notice but retains zero capacity', async () => {
  const h = setup({ handler: url => url.endsWith('/status')
    ? { ok: true, json: async () => ({ ...capacity(), backupBytes: 0 }) } : undefined });
  await h.context.loadRuntimeStorage();
  assert.equal(findClass(h.container, 'storage-backup'), undefined);
  assert.match(findClass(h.container, 'storage-stats').textContent, /storage.backups 0.0 MiB/);
  assert.doesNotMatch(h.container.textContent, /storage.noBackups|storage.activeRetained/);
});
test('late location response cannot populate a closed or newer view', async () => {
  let finish;
  const h = setup({ handler: url => url.endsWith('runtime-storage') ? new Promise(resolve => { finish = resolve; }) : undefined });
  const pending = h.context.loadRuntimeStorage(); h.context.stopRuntimeStorage();
  finish({ ok: true, json: async () => ({ mode: 'portable', runtimeRoot: 'stale', dataRoot: 'stale' }) });
  await pending; assert.doesNotMatch(h.container.textContent, /stale/); assert.equal(h.requests.length, 1);
});
test('late capacity response does not append cards after leaving the view', async () => {
  let finish;
  const h = setup({ handler: url => url.endsWith('/status') ? new Promise(resolve => { finish = resolve; }) : undefined });
  const pending = h.context.loadRuntimeStorage(); await flush(); h.context.stopRuntimeStorage();
  finish({ ok: true, json: async () => capacity() }); await pending;
  assert.equal(findClass(h.container, 'storage-stats'), undefined);
});
