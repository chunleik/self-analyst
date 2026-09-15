import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const source = fs.readFileSync(new URL('../../main/resources/desktop-ui/titlebar-help.js', import.meta.url), 'utf8');
const settle = () => new Promise(resolve => setImmediate(resolve));
function setup({ native = false, language = 'zh', invoke, openError = false } = {}) {
  const nodes = new Map(), links = [], calls = [];
  let document;
  function element(id = '') {
    const handlers = {}, attributes = {}, classes = new Set();
    const el = { id, hidden: false, open: false, style: {}, children: [], parent: null,
      textContent: '', offsetWidth: 196, offsetHeight: 120,
      classList: { toggle(name, value) { value ? classes.add(name) : classes.delete(name); }, contains: name => classes.has(name) },
      addEventListener(type, fn) { (handlers[type] ||= []).push(fn); },
      fire(type, details = {}) { const event = { target: el, preventDefault() { this.prevented = true; }, stopPropagation() {}, ...details }; for (const fn of handlers[type] || []) fn(event); return event; },
      setAttribute(k, v) { attributes[k] = v; }, getAttribute(k) { return attributes[k]; }, removeAttribute(k) { delete attributes[k]; },
      appendChild(child) { child.parent = this; this.children.push(child); },
      contains(child) { return child === this || this.children.some(c => c.contains(child)); },
      focus() { document.activeElement = this; document.fire('focusin', { target: this }); },
      getBoundingClientRect() { return { x: 160, y: 0, left: 160, top: 0, right: 215, bottom: 34, width: 55, height: 34 }; },
      showModal() { this.open = true; }, close() { this.open = false; this.fire('close'); },
      click() { this.fire('click'); }
    };
    return el;
  }
  document = element();
  document.documentElement = { lang: language };
  document.activeElement = null;
  document.getElementById = id => nodes.get(id);
  document.createElement = () => { const link = element(); link.click = () => { if (openError) throw Error('blocked'); links.push(link); }; return link; };
  for (const id of ['help-trigger', 'help-menu', 'help-dialog', 'help-dialog-title', 'help-dialog-body', 'help-dialog-link', 'window-minimize', 'window-maximize', 'window-close', 'titlebar-drag', 'titlebar-help-slot', 'web-help-slot']) nodes.set(id, element(id));
  const menu = nodes.get('help-menu'); menu.hidden = true;
  const items = ['guide', 'feedback', 'about'].map(action => { const el = element(action); el.setAttribute('data-help-action', action); menu.appendChild(el); return el; });
  menu.querySelectorAll = () => items;
  nodes.get('web-help-slot').appendChild(nodes.get('help-trigger'));
  const host = element(); Object.assign(host, { innerWidth: 800, innerHeight: 600, devicePixelRatio: 1.5, location: { origin: 'http://localhost:5701' } });
  if (native) {
    host.__SELF_ANALYST_DESKTOP__ = true;
    host.__TAURI__ = { core: { invoke: async (name, args) => { calls.push([name, JSON.parse(JSON.stringify(args))]); return invoke ? invoke(name, args) : false; } } };
  }
  const state = { lang: language, chatDraft: '保留这段草稿' };
  const context = vm.createContext({ window: host, document, state, t: key => language + ':' + key });
  vm.runInContext(source, context);
  return { ui: host.SelfAnalystTitlebar, lib: host.SelfAnalystHelp, host, document, state, nodes, items, menu, calls, links, trigger: nodes.get('help-trigger'), dialog: nodes.get('help-dialog') };
}

test('desktop mounts help in titlebar, browser keeps its own navigation entry', async () => {
  const web = setup(), native = setup({ native: true }); await settle();
  assert.equal(web.trigger.parent.id, 'web-help-slot');
  assert.equal(native.trigger.parent.id, 'titlebar-help-slot');
  assert.equal(web.calls.length, 0);
  assert.ok(native.calls.some(([name]) => name === 'titlebar_layout'));
});

test('menu toggles, closes outside and on blur without stealing outside focus', () => {
  const s = setup();
  s.trigger.click(); assert.equal(s.menu.hidden, false); assert.equal(s.trigger.getAttribute('aria-expanded'), 'true');
  s.trigger.click(); assert.equal(s.menu.hidden, true);
  s.ui.open(); const outside = s.nodes.get('window-close'); outside.focus();
  s.document.fire('pointerdown', { target: outside });
  assert.equal(s.menu.hidden, true); assert.equal(s.document.activeElement, outside);
  s.ui.open(); s.host.fire('blur'); assert.equal(s.menu.hidden, true);
  assert.equal(s.state.chatDraft, '保留这段草稿');
});

test('keyboard navigation wraps, Esc returns to trigger and Tab allows normal traversal', () => {
  const s = setup();
  s.trigger.fire('keydown', { key: 'ArrowUp' }); assert.equal(s.document.activeElement, s.items[2]);
  s.menu.fire('keydown', { key: 'ArrowDown' }); assert.equal(s.document.activeElement, s.items[0]);
  s.menu.fire('keydown', { key: 'End' }); assert.equal(s.document.activeElement, s.items[2]);
  s.menu.fire('keydown', { key: 'Escape' }); assert.equal(s.menu.hidden, true); assert.equal(s.document.activeElement, s.trigger);
  s.ui.open(); const event = s.menu.fire('keydown', { key: 'Tab' });
  assert.equal(s.menu.hidden, true); assert.equal(event.prevented, undefined);
});

test('menu position stays within the small viewport', () => {
  const s = setup(); s.host.innerWidth = 300; s.host.innerHeight = 130;
  s.trigger.getBoundingClientRect = () => ({ left: 290, bottom: 128 });
  s.ui.open(); assert.equal(s.menu.style.left, '96px'); assert.equal(s.menu.style.top, '2px');
});

test('browser guide and feedback links suppress referrer and never contain local data', async () => {
  for (const language of ['zh', 'en']) {
    const s = setup({ language }); await s.ui.execute('guide'); await s.ui.execute('feedback');
    assert.ok(s.links[0].href.endsWith(language === 'zh' ? 'README.zh-CN.md' : 'README.md'));
    assert.ok(s.links[1].href.endsWith('/issues'));
    for (const link of s.links) {
      assert.equal(link.target, '_blank'); assert.equal(link.rel, 'noopener noreferrer');
      assert.equal(new URL(link.href).search, ''); assert.equal(new URL(link.href).host, 'github.com');
    }
    await s.ui.execute('https://untrusted.test'); assert.equal(s.links.length, 2);
  }
});

test('browser open exception and native rejection expose a localized error and safe link', async () => {
  for (const options of [{ openError: true }, { native: true, invoke: name => { if (name === 'help_action') throw Error('secret details'); return false; } }]) {
    const s = setup(options); await settle(); await s.ui.execute('guide');
    assert.equal(s.dialog.open, true);
    assert.equal(s.nodes.get('help-dialog-body').textContent, 'zh:help.failed');
    assert.ok(s.nodes.get('help-dialog-link').href.endsWith('README.zh-CN.md'));
    assert.equal(s.state.chatDraft, '保留这段草稿');
  }
});

test('native about uses the same host operation; browser about has no fictional shell version', async () => {
  const native = setup({ native: true }); await settle(); await native.ui.execute('about');
  assert.deepEqual(native.calls.filter(([name]) => name === 'help_action'), [['help_action', { action: 'about' }]]);
  const web = setup(); await web.ui.execute('about');
  assert.equal(web.nodes.get('help-dialog-body').textContent, 'SelfAnalyst\nzh:help.webVersion\nhttp://localhost:5701');
  web.dialog.close(); assert.equal(web.document.activeElement, web.trigger); assert.equal(web.calls.length, 0);
});

test('actual maximize state updates caption and geometry after external resize', async () => {
  let maximized = false;
  const s = setup({ native: true, invoke: () => maximized }); await settle();
  maximized = true; s.host.fire('resize'); await settle();
  assert.equal(s.nodes.get('window-maximize').getAttribute('aria-label'), 'zh:window.restore');
  assert.equal(s.nodes.get('window-maximize').classList.contains('is-maximized'), true);
  assert.equal(s.calls.filter(([name]) => name === 'titlebar_layout').at(-1)[1].layout.scale, 1.5);
});

test('window failures are shown, help controls do not issue drag commands', async () => {
  const s = setup({ native: true, invoke: (_, args) => { if (args.action === 'minimize') throw Error('denied'); return false; } }); await settle();
  s.trigger.click(); assert.equal(s.calls.some(([, args]) => args.action === 'drag'), false);
  s.nodes.get('window-minimize').click(); await settle();
  assert.equal(s.dialog.open, true); assert.equal(s.nodes.get('help-dialog-body').textContent, 'zh:help.failed');
});

test('every help and window label is present in both catalogs', () => {
  for (const language of ['en', 'zh']) {
    const catalog = JSON.parse(fs.readFileSync(new URL(`../../main/resources/desktop-ui/locales/${language}.json`, import.meta.url), 'utf8'));
    for (const key of ['help.label', 'help.guide', 'help.feedback', 'help.about', 'help.webVersion', 'help.dismiss', 'help.failed', 'window.minimize', 'window.maximize', 'window.restore', 'window.close']) assert.equal(typeof catalog[key], 'string', key);
  }
});
