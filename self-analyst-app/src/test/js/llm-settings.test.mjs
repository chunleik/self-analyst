import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const source = fs.readFileSync(new URL('../../main/resources/desktop-ui/llm-settings.js', import.meta.url), 'utf8');
const ui = fs.readFileSync(new URL('../../main/resources/desktop-ui/ui.js', import.meta.url), 'utf8');
function snapshot() {
  return { fields: Object.fromEntries(Object.entries({ baseUrl: 'https://example.org/v1', model: 'old', temperature: .7, maxTokens: 2048 })
    .map(([key, value]) => [key, { effectiveValue: value, savedValue: null, source: 'environment' }])),
    credential: { configured: true, source: 'environment', hasUserOverride: false },
    runtime: { application: { llm: { status: 'applied' } } } };
}
function setup(handler) {
  const calls = [], timers = new Map(); let next = 0;
  const context = vm.createContext({ AbortController, t: key => key,
    setTimeout: fn => { timers.set(++next, fn); return next; }, clearTimeout: key => timers.delete(key) });
  vm.runInContext(source, context);
  const request = (...args) => {
    calls.push(args);
    if (handler) { const result = handler(...args); if (result !== undefined) return result; }
    return Promise.resolve(args[0] === '/presets' ? { presets: [] } : snapshot());
  };
  const model = context.createLlmSettingsModel(request, () => {});
  return { model, calls, timers, context };
}
const plain = value => JSON.parse(JSON.stringify(value));

test('opening only queries settings and presets; saving sends changed fields and keep', async () => {
  const h = setup((path, method) => method === 'PUT' ? Promise.resolve({ settings: snapshot() }) : undefined);
  await h.model.load();
  assert.deepEqual(h.calls.map(c => c[0]), ['', '/presets']);
  assert.equal(h.model.dirty(), false);
  h.model.change('model', 'new');
  await h.model.save();
  assert.deepEqual(plain(h.calls.at(-1)[2]), { updates: { model: 'new' }, reset: [], credential: { action: 'keep' } });
  assert.equal(h.model.dirty(), false);
  assert.equal(h.model.notice, 'saved');
  assert.deepEqual(plain(h.model.probe), {});
});

test('blank password preserves key; clear and reset are distinct; success clears password', async () => {
  const h = setup((path, method) => method === 'PUT' ? Promise.resolve({ settings: snapshot() }) : undefined);
  await h.model.load();
  h.model.setCredential('replace', ''); assert.equal(h.model.dirty(), false);
  h.model.setCredential('clear'); await h.model.save();
  assert.equal(h.calls.at(-1)[2].credential.action, 'clear');
  h.model.setCredential('reset'); await h.model.save();
  assert.equal(h.calls.at(-1)[2].credential.action, 'reset');
  h.model.setCredential('replace', 'new-key'); await h.model.save();
  assert.deepEqual(plain(h.model.credential), { action: 'keep' });
});

test('save error retains draft; saving locks edits and prevents duplicate requests', async () => {
  let fail;
  const h = setup((path, method) => method === 'PUT' ? new Promise((resolve, reject) => { fail = reject; }) : undefined);
  await h.model.load(); h.model.change('maxTokens', '1.5'); h.model.setCredential('replace', 'key');
  const pending = h.model.save();
  h.model.change('model', 'should-not-change'); await h.model.save();
  assert.equal(h.calls.filter(c => c[1] === 'PUT').length, 1);
  fail(new Error('invalid maxTokens')); await pending;
  assert.equal(h.model.value('maxTokens'), '1.5'); assert.equal(h.model.value('model'), 'old');
  assert.equal(h.model.credential.value, 'key'); assert.equal(h.model.dirty(), true);
  assert.equal(h.model.error, 'invalid maxTokens'); assert.equal(h.model.saving, false);
});

test('changed connection and close discard stale probe results and abort signals', async () => {
  for (const change of ['baseUrl', 'model', 'credential', 'close']) {
    let finish;
    const h = setup(path => path === '/test' ? new Promise(resolve => { finish = resolve; }) : undefined);
    await h.model.load(); const pending = h.model.run('test');
    const signal = h.calls.at(-1)[3];
    if (change === 'close') h.model.destroy();
    else if (change === 'credential') h.model.setCredential('replace', 'new');
    else h.model.change(change, 'new');
    assert.equal(signal.aborted, true);
    finish({ ok: true, code: 'success' }); await pending;
    assert.deepEqual(plain(h.model.probe), {});
  }
});

test('discovery failure permits manual entry and reset draft reaches probe without old value', async () => {
  const h = setup(path => path === '/discover-models' ? Promise.resolve({ ok: false, code: 'unsupported_discovery' }) : undefined);
  await h.model.load(); await h.model.run('discover');
  h.model.change('model', 'manual'); assert.equal(h.model.value('model'), 'manual');
  h.model.inherit('baseUrl'); await h.model.run('discover');
  assert.equal('baseUrl' in h.calls.at(-1)[2], false);
  assert.deepEqual(plain(h.calls.at(-1)[2].reset), ['baseUrl']);
  assert.equal(h.calls.some(c => c[1] === 'PUT'), false);
});

test('runtime refresh preserves draft and load failures disable edits and save', async () => {
  let count = 0;
  const h = setup(path => {
    if (path) return;
    const value = snapshot();
    if (!count++) value.runtime.application.llm = { status: 'draining', activeWorkCount: 1 };
    return Promise.resolve(value);
  });
  await h.model.load(); h.model.change('model', 'draft');
  [...h.timers.values()][0](); await new Promise(setImmediate);
  assert.equal(h.model.value('model'), 'draft');
  assert.equal(h.model.snapshot.runtime.application.llm.status, 'applied');
  const bad = setup(path => !path ? Promise.reject(new Error('read failure')) : undefined);
  await bad.model.load(); bad.model.change('model', 'bad'); await bad.model.save();
  assert.equal(bad.model.snapshot, null); assert.equal(bad.model.error, 'read failure');
  assert.equal(bad.calls.some(c => c[1] === 'PUT'), false);
});

test('window tabs protect both drafts, reload after discard and route key navigation to raw', async () => {
  let confirm = false, mounted = 0, destroyed = 0, rawLoads = 0, focused;
  const state = { configOpen: false, configSaving: false, configDirty: false, configLoadGeneration: 0,
    dom: { configModal: { classList: { add() {}, remove() {} } }, configGrid: {} } };
  const context = vm.createContext({ state, document: { getElementById: () => null }, window: { confirm: () => confirm },
    t: key => key, mountLlmSettings: () => { mounted++; return { dirty: () => true, destroy: () => { destroyed++; } }; },
    stopConfigRuntimeRefresh() {}, focusConfigEditorKey: key => { focused = key; } });
  vm.runInContext(ui, context);
  context.loadConfig = () => { rawLoads++; return Promise.resolve(); };
  await context.openConfigModal(); assert.equal(mounted, 1); assert.equal(rawLoads, 0);
  await context.switchConfigView('raw'); assert.equal(rawLoads, 0); assert.equal(destroyed, 0);
  confirm = true; await context.switchConfigView('raw'); assert.equal(rawLoads, 1); assert.equal(destroyed, 1);
  state.configDirty = true; confirm = false;
  await context.switchConfigView('llm'); assert.equal(mounted, 1);
  context.closeConfigModal(); assert.equal(state.configOpen, true);
  confirm = true; await context.switchConfigView('llm'); assert.equal(mounted, 2);
  state.configSaving = true; context.closeConfigModal(); await context.switchConfigView('raw'); assert.equal(rawLoads, 1);
  state.configSaving = false; await context.openConfigModal('file.watch.paths');
  assert.equal(focused, 'file.watch.paths'); assert.equal(state.configView, 'raw');
});
