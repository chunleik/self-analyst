import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const source = fs.readFileSync(new URL('../../main/resources/desktop-ui/runtime-storage.js', import.meta.url), 'utf8');

test('all storage labels exist in both browser catalogs', () => {
  for (const language of ['zh', 'en']) {
    const catalog = JSON.parse(fs.readFileSync(new URL(`../../main/resources/desktop-ui/locales/${language}.json`, import.meta.url), 'utf8'));
    for (const key of ['title', 'loading', 'mode', 'mode.user', 'mode.portable', 'mode.direct',
      'runtimeRoot', 'dataRoot', 'open', 'openFailed', 'loadFailed', 'nativeUnavailable']) {
      assert.equal(typeof catalog['storage.' + key], 'string', `${language}: ${key}`);
    }
  }
});
function element() {
  return { children: [], style: {}, textContent: '', appendChild(child) { this.children.push(child); },
    addEventListener(name, fn) { this[name] = fn; } };
}
function setup(invoke) {
  const container = element();
  const context = vm.createContext({ API_BASE: '', t: key => key,
    document: { getElementById: () => container, createElement: element },
    window: invoke ? { __TAURI__: { core: { invoke } } } : {},
    fetch: async () => ({ ok: true, json: async () => ({ mode: 'portable', runtimeRoot: 'D:/中文', dataRoot: 'D:/中文/data' }) }) });
  vm.runInContext(source, context);
  return { context, container };
}

test('browser shows actual paths and native unavailability', async () => {
  const { context, container } = setup();
  await context.loadRuntimeStorage();
  assert.ok(container.children.some(child => child.textContent.includes('D:/中文/data')));
  assert.equal(container.children.at(-1).textContent, 'storage.nativeUnavailable');
});

test('native open receives no path and reports failure', async () => {
  const calls = [];
  const { context, container } = setup(async (...args) => { calls.push(args); throw new Error('failed'); });
  await context.loadRuntimeStorage();
  const button = container.children.find(child => child.type === 'button');
  button.click();
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(calls, [['open_data_directory']]);
  assert.equal(container.children.at(-1).textContent, 'storage.openFailed');
  assert.equal(button.disabled, false);
});

test('native open waits for completion then restores button without error', async () => {
  let complete;
  const calls = [];
  const { context, container } = setup((...args) => {
    calls.push(args);
    return new Promise(resolve => { complete = resolve; });
  });
  await context.loadRuntimeStorage();
  const button = container.children.find(child => child.type === 'button');
  button.click();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(button.disabled, true);
  assert.deepEqual(calls, [['open_data_directory']]);
  complete();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(button.disabled, false);
  assert.equal(container.children.at(-1).textContent, '');
});


test('merged capacity panel separates backups and requires explicit cleanup confirmation', async () => {
  const container = element();
  const requests = [];
  let confirmed = false;
  const storage = { mode: 'merged', migration: 'complete', migrationId: 'test-migration',
    activeBytes: 1048576, auxiliaryBytes: 524288, backupBytes: 2097152 };
  const context = vm.createContext({ API_BASE: '', t: key => key,
    document: { createElement: element }, window: { confirm: () => confirmed },
    fetch: async (url, options) => {
      requests.push({url, options});
      return { ok: !options, json: async () => storage };
    } });
  vm.runInContext(source, context);
  await context.loadMergedStorage(container);
  const panel = container.children[0];
  assert.ok(panel.children.some(child => child.textContent === 'storage.active: 1.0 MiB'));
  assert.ok(panel.children.some(child => child.textContent === 'storage.backups: 2.0 MiB'));
  const button = panel.children.find(child => child.type === 'button');
  button.click();
  assert.equal(requests.length, 1);
  confirmed = true; button.click();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(requests[1].url, '/desktop/storage/backups/cleanup');
  assert.equal(JSON.parse(requests[1].options.body).migrationId, 'test-migration');
  assert.equal(button.disabled, false);
  assert.equal(panel.children[0].textContent, 'storage.cleanFailed');
});
