import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
const root = new URL('../../main/resources/desktop-ui/', import.meta.url);
const source = fs.readFileSync(new URL('privacy-settings.js', root), 'utf8');
class Element {
  constructor(tag = 'div') { this.tag = tag; this.children = []; this.events = {}; this.value = ''; this.dataset = {}; this.classList = { toggle() {}, add() {}, remove() {} }; }
  set textContent(value) { this.text = String(value); this.children = []; }
  get textContent() { return (this.text || '') + this.children.map(child => child.textContent).join(' '); }
  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this.children = children; }
  setAttribute(key, value) { this[key] = value; }
  removeAttribute(key) { delete this[key]; }
  addEventListener(name, action) { this.events[name] = action; }
  querySelectorAll(selector) { const tags = selector.split(',').map(s => s.trim()); return all(this).filter(n => tags.includes(n.tag)); }
  focus() {}
}
const all = node => [node, ...node.children.flatMap(all)];
const payload = () => ({ apps: [{ app: 'Editor.exe', mode: 'model' }], sites: ['example.com'], candidates: [{ app: 'PotPlayer.exe', seconds: 600 }, { app: 'Browser.exe', seconds: 300 }] });
const flush = () => new Promise(resolve => setImmediate(resolve));
async function setup(handler = async () => payload()) {
  const root = new Element(), calls = [];
  const context = vm.createContext({ document: { createElement: tag => new Element(tag) }, state: { configDirty: false }, API_BASE: '', t: (key, args) => key + (args ? JSON.stringify(args) : ''),
    fetch: async (url, options) => { calls.push({url, options}); const result = await handler(options); return result?.httpError ? { ok: false, json: async () => result } : { ok: true, json: async () => result }; } });
  vm.runInContext(source, context);
  const pending = context.mountPrivacySettings(root); await flush();
  return { root, calls, context, pending, find: id => all(root).find(n => n.id === id), button: key => all(root).find(n => n.tag === 'button' && n.textContent === key) };
}
function input(element, value) { element.value = value; element.events.input(); }
function press(element, key) { element.events.keydown({ key, preventDefault() {}, stopPropagation() {} }); }
function snapshot(h) { return JSON.parse(JSON.stringify(h.context.privacySettings)); }

test('renders rules and keyboard suggestions, adds manual names, changes modes and removes rules', async () => {
  const h = await setup(); await h.pending;
  assert.match(h.root.textContent, /Editor.exe/); assert.match(h.root.textContent, /privacy.historyHelp/);
  const app = h.find('privacy-app-input'); input(app, 'pot'); press(app, 'ArrowDown');
  assert.equal(app['aria-activedescendant'], 'privacy-option-0'); press(app, 'Enter');
  assert.deepEqual(snapshot(h).apps.at(-1), { app: 'PotPlayer.exe', mode: 'model' });
  input(app, 'vlc.exe'); press(app, 'Enter');
  const mode = all(h.root).filter(n => n.tag === 'select').at(-1); mode.value = 'capture'; mode.events.change();
  assert.equal(snapshot(h).apps.at(-1).mode, 'capture');
  const remove = all(h.root).find(n => n['aria-label']?.includes('vlc.exe') && n.tag === 'button'); remove.events.click();
  assert.equal(snapshot(h).apps.length, 2); assert.equal(h.context.state.configDirty, true);
  input(app, 'EDITOR.EXE'); press(app, 'Enter'); assert.match(h.root.textContent, /privacy.duplicate/); assert.equal(snapshot(h).apps.length, 2);
  input(app, 'bad;app'); press(app, 'Enter'); assert.match(h.root.textContent, /privacy.error.invalidApp/);
});

test('site chips deduplicate, remove, and save exact payload with success reset', async () => {
  const h = await setup(async options => options.method === 'PUT' ? {...JSON.parse(options.body), candidates: [], applied: true} : payload());
  const site = h.find('privacy-site-input'); input(site, 'private.example'); press(site, 'Enter');
  input(site, 'PRIVATE.EXAMPLE'); press(site, 'Enter'); assert.match(h.root.textContent, /privacy.duplicate/);
  input(site, ''); all(h.root).find(n => n.tag === 'button' && n['aria-label']?.includes('example.com')).events.click();
  await h.button('privacy.save').events.click();
  assert.deepEqual(JSON.parse(h.calls.at(-1).options.body), { apps: payload().apps, sites: ['private.example'] });
  assert.equal(h.calls.at(-1).options.credentials, 'same-origin'); assert.equal(h.context.state.configDirty, false);
  assert.match(h.root.textContent, /privacy.saved/);
});

test('candidate failure and empty results allow manual input; load failure forbids saving and supports retry', async () => {
  for (const candidatesError of ['unavailable', 'partial', 'future-code', undefined]) {
    const h = await setup(async () => ({ ...payload(), candidates: [], candidatesError }));
    input(h.find('privacy-app-input'), 'manual.exe'); press(h.find('privacy-app-input'), 'Enter');
    assert.equal(snapshot(h).apps.at(-1).app, 'manual.exe'); assert.equal(h.button('privacy.save').disabled, false);
    assert.match(h.root.textContent, /privacy.candidates(Unavailable|Partial|Empty)/);
  }
  let fail = true;
  const h = await setup(async () => { if (fail) throw new Error('private exception'); return payload(); });
  assert.equal(h.button('privacy.save').disabled, true); assert.equal(h.find('privacy-app-input').disabled, true);
  await h.button('privacy.save').events.click(); assert.equal(h.calls.length, 1); assert.doesNotMatch(h.root.textContent, /private exception/);
  fail = false; await h.button('privacy.retry').events.click(); assert.equal(h.find('privacy-app-input').disabled, false);
});

test('save locks mutations, failure retains draft, and pending typed text cannot be silently discarded', async () => {
  let finish;
  const h = await setup(options => options.method === 'PUT' ? new Promise(resolve => { finish = resolve; }) : payload());
  const app = h.find('privacy-app-input'); input(app, 'pending.exe'); await h.button('privacy.save').events.click();
  assert.equal(h.calls.length, 1); assert.match(h.root.textContent, /privacy.pendingInput/);
  press(app, 'Enter'); const save = h.button('privacy.save').events.click(); await flush();
  assert.equal(h.context.state.configSaving, true);
  assert.equal(all(h.root).filter(n => ['input', 'select', 'button'].includes(n.tag)).every(n => n.disabled), true);
  finish({ httpError: true, errorCode: 'saveFailed', error: 'private error' }); await save;
  assert.equal(snapshot(h).apps.at(-1).app, 'pending.exe'); assert.equal(h.context.state.configDirty, true);
  assert.match(h.root.textContent, /privacy.error.saveFailed/); assert.doesNotMatch(h.root.textContent, /private error/);
  assert.equal(h.context.state.configSaving, false);
});

test('late response from an abandoned mount cannot overwrite a fresh mount or dirty state', async () => {
  let release, count = 0;
  const h = await setup(() => ++count === 1 ? new Promise(resolve => { release = resolve; }) : payload());
  h.context.stopPrivacySettings(); await h.context.mountPrivacySettings(h.root);
  input(h.find('privacy-app-input'), 'new.exe'); press(h.find('privacy-app-input'), 'Enter');
  release({ apps: [], sites: [], candidates: [] }); await h.pending;
  assert.equal(snapshot(h).apps.at(-1).app, 'new.exe'); assert.equal(h.context.state.configDirty, true);
});

test('case-insensitive duplicate load honors capture and names render only as text', async () => {
  const h = await setup(async () => ({ apps: [{app:'App.exe',mode:'capture'}, {app:'APP.EXE',mode:'model'}, {app:'<img src=x>',mode:'model'}], sites: ['EXAMPLE.COM', 'example.com'], candidates: [] }));
  assert.equal(snapshot(h).apps.length, 2); assert.equal(snapshot(h).apps[0].mode, 'capture'); assert.equal(snapshot(h).sites.length, 1);
  assert.equal(all(h.root).some(n => n.tag === 'img'), false);
});

test('real switch and close guards preserve dirty privacy draft when discard is cancelled', async () => {
  const h = await setup();
  vm.runInContext(fs.readFileSync(new URL('ui.js', root), 'utf8'), h.context);
  h.context.window = { confirm: () => false };
  h.context.state.dom = { configModal: new Element(), configGrid: h.root };
  h.context.state.configView = 'privacy'; h.context.state.configOpen = true; h.context.state.dom.configModal.dataset.view = 'privacy';
  input(h.find('privacy-app-input'), 'unsaved.exe');
  await h.context.switchConfigView('raw'); h.context.closeConfigModal();
  assert.equal(h.context.state.configView, 'privacy'); assert.equal(h.context.state.configOpen, true);
  assert.equal(h.find('privacy-app-input').value, 'unsaved.exe');
  h.context.window.confirm = () => true; h.context.closeConfigModal();
  assert.equal(h.context.state.configOpen, false); assert.equal(h.context.privacySettings, null);
});

test('all privacy text keys exist in both catalogs and tab imports are wired', () => {
  const keys = [...source.matchAll(/"(privacy\.[\w.]+)"/g)].map(m => m[1]).filter(k => !k.endsWith('.'));
  for (const suffix of ['model','capture']) keys.push('privacy.mode.' + suffix);
  for (const suffix of ['invalidPayload','invalidApp','invalidMode','invalidSite','saveFailed']) keys.push('privacy.error.' + suffix);
  for (const lang of ['en','zh']) { const catalog = JSON.parse(fs.readFileSync(new URL('locales/' + lang + '.json', root), 'utf8')); for (const key of keys) assert.equal(typeof catalog[key], 'string', lang + ': ' + key); }
  const html = fs.readFileSync(new URL('index.html', root), 'utf8');
  assert.match(html, /data-config-view="privacy"/); assert.match(html, /privacy-settings\.js/); assert.match(html, /privacy-settings\.css/);
});

test('keyboard navigation wraps, Escape closes only the suggestions, and composition does not add', async () => {
  const h = await setup(); const app = h.find('privacy-app-input');
  press(app, 'ArrowUp'); assert.equal(app['aria-activedescendant'], 'privacy-option-1');
  press(app, 'ArrowDown'); assert.equal(app['aria-activedescendant'], 'privacy-option-0');
  let stopped = false;
  app.events.keydown({key: 'Escape', preventDefault() {}, stopPropagation() { stopped = true; }});
  assert.equal(stopped, true); assert.equal(h.find('privacy-candidates').hidden, true);
  input(app, 'composing.exe'); app.events.keydown({ key: 'Enter', isComposing: true });
  assert.equal(snapshot(h).apps.length, 1);
});

test('malformed loads stay locked and returning to baseline clears dirty state', async () => {
  const invalid = await setup(async () => ({ apps: [], sites: 'invalid', candidates: [] }));
  assert.equal(invalid.button('privacy.save').disabled, true);
  assert.equal(invalid.context.privacySettings.loaded, false);
  const h = await setup(); const mode = all(h.root).find(n => n.tag === 'select');
  mode.value = 'capture'; mode.events.change(); assert.equal(h.context.state.configDirty, true);
  mode.value = 'model'; mode.events.change(); assert.equal(h.context.state.configDirty, false);
  assert.equal(h.button('privacy.save').disabled, true);
});
