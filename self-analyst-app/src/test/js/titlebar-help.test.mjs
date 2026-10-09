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
  for (const id of ['help-trigger', 'help-menu', 'help-dialog', 'help-dialog-title', 'help-dialog-body', 'help-dialog-link', 'help-update-retry', 'window-minimize', 'window-maximize', 'window-close', 'titlebar-drag', 'titlebar-help-slot', 'web-help-slot']) nodes.set(id, element(id));
  const menu = nodes.get('help-menu'); menu.hidden = true;
  const items = ['guide', 'documentation', 'knowledge', 'feedback', 'check_update', 'website', 'about'].map(action => { const el = element(action); el.setAttribute('data-help-action', action); menu.appendChild(el); return el; });
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
  s.trigger.fire('keydown', { key: 'ArrowUp' }); assert.equal(s.document.activeElement, s.items.at(-1));
  s.menu.fire('keydown', { key: 'ArrowDown' }); assert.equal(s.document.activeElement, s.items[0]);
  s.menu.fire('keydown', { key: 'End' }); assert.equal(s.document.activeElement, s.items.at(-1));
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
    for (const key of ['help.label', 'help.guide', 'help.feedback', 'help.website', 'help.documentation', 'help.knowledge', 'help.about', 'help.webVersion', 'help.dismiss', 'help.failed', 'window.minimize', 'window.maximize', 'window.restore', 'window.close']) assert.equal(typeof catalog[key], 'string', key);
  }
});

test('update check reports both versions and opens download only on explicit click', async () => {
  for (const language of ['zh', 'en']) {
    const s = setup({ native: true, language, invoke: (name, args) => args.action === 'check_update'
      ? { available: true, currentVersion: '0.2.6', latestVersion: '0.2.10', tag: 'v0.2.10' } : false });
    await settle();
    await s.ui.execute('check_update');
    assert.match(s.nodes.get('help-dialog-body').textContent, /0.2.6/);
    assert.match(s.nodes.get('help-dialog-body').textContent, /0.2.10/);
    assert.ok(s.nodes.get('help-dialog-body').textContent.startsWith(language + ':update.available'));
    assert.equal(s.calls.filter(([, args]) => args.action === 'download').length, 0);
    assert.equal(s.nodes.get('help-update-retry').hidden, true);
    assert.equal(s.nodes.get('help-dialog-link').textContent, language + ':update.download');
    s.nodes.get('help-dialog-link').click(); await settle();
    assert.deepEqual(s.calls.at(-1), ['help_action', { action: 'download', tag: 'v0.2.10' }]);
  }
});

test('up-to-date result has no download and Web mode does not invent a version', async () => {
  const s = setup({ native: true, invoke: (name, args) => args.action === 'check_update'
    ? { available: false, currentVersion: '0.2.6', latestVersion: '0.2.6' } : false });
  await settle(); await s.ui.execute('check_update');
  assert.match(s.nodes.get('help-dialog-body').textContent, /update.current/);
  assert.equal(s.nodes.get('help-dialog-link').hidden, true);
  const web = setup(); await web.ui.execute('check_update');
  assert.equal(web.nodes.get('help-dialog-body').textContent, 'zh:update.web');
  assert.equal(web.nodes.get('help-dialog-link').href, 'https://github.com/chunleik/self-analyst/releases/latest');
  assert.equal(web.calls.length, 0); assert.equal(web.links.length, 0);
});

test('update errors are localized and retry restores a successful result', async () => {
  for (const code of ['update.timeout', 'update.rateLimited', 'update.noRelease', 'update.invalid', 'private network details']) {
    let failure = true;
    const s = setup({ native: true, invoke: (name, args) => {
      if (args.action !== 'check_update') return false;
      if (failure) throw code;
      return { available: false, currentVersion: '0.2.6', latestVersion: '0.2.6' };
    } });
    await settle(); await s.ui.execute('check_update');
    assert.equal(s.nodes.get('help-dialog-body').textContent, 'zh:' + (code.startsWith('update.') ? code : 'update.failed'));
    assert.equal(s.nodes.get('help-update-retry').hidden, false);
    assert.ok(s.nodes.get('help-dialog-link').href.endsWith('/releases/latest'));
    failure = false; s.nodes.get('help-update-retry').click(); await settle();
    assert.equal(s.nodes.get('help-update-retry').hidden, true);
    assert.match(s.nodes.get('help-dialog-body').textContent, /update.current/);
  }
});

test('checks are deduplicated and a dismissed result cannot reopen the dialog', async () => {
  for (const fail of [false, true]) {
    let finish;
    const s = setup({ native: true, invoke: (name, args) => args.action === 'check_update'
      ? new Promise((resolve, reject) => { finish = () => fail ? reject('update.timeout') : resolve({ available: false }); }) : false });
    await settle();
    const first = s.ui.execute('check_update');
    const second = s.ui.execute('check_update');
    await settle();
    assert.equal(first, second);
    assert.equal(s.nodes.get('help-dialog-body').textContent, 'zh:update.checking');
    assert.equal(s.calls.filter(([, args]) => args.action === 'check_update').length, 1);
    s.dialog.close(); finish(); await first;
    assert.equal(s.dialog.open, false);
    assert.equal(s.state.chatDraft, '保留这段草稿');
  }
});

test('a late update result cannot replace a newer help message', async () => {
  let finish;
  const s = setup({ native: true, invoke: (name, args) => {
    if (args.action === 'check_update') return new Promise(resolve => { finish = resolve; });
    if (args.action === 'guide') throw Error('browser unavailable');
    return false;
  } });
  await settle();
  const checking = s.ui.execute('check_update'); await settle();
  s.dialog.close(); await s.ui.execute('guide');
  finish({ available: true, currentVersion: '0.2.6', latestVersion: '0.2.7', tag: 'v0.2.7' });
  await checking;
  assert.equal(s.nodes.get('help-dialog-body').textContent, 'zh:help.failed');
  assert.ok(s.nodes.get('help-dialog-link').href.endsWith('README.zh-CN.md'));
});

test('update labels are translated and actual menu includes all seven actions in order', () => {
  const html = fs.readFileSync(new URL('../../main/resources/desktop-ui/index.html', import.meta.url), 'utf8');
  assert.deepEqual([...html.matchAll(/data-help-action="([^"]+)"/g)].map(match => match[1]), ['guide', 'documentation', 'knowledge', 'feedback', 'check_update', 'website', 'about']);
  const keys = ['check', 'checking', 'available', 'current', 'installed', 'latest', 'download', 'releases', 'retry', 'web', 'failed', 'timeout', 'rateLimited', 'noRelease', 'invalid', 'busy'];
  for (const language of ['zh', 'en']) {
    const catalog = JSON.parse(fs.readFileSync(new URL(`../../main/resources/desktop-ui/locales/${language}.json`, import.meta.url), 'utf8'));
    for (const key of keys) assert.ok(catalog['update.' + key], key);
  }
});

const projectLinks = {
  website: 'https://github.com/chunleik/self-analyst',
  documentation: 'https://github.com/chunleik/self-analyst/blob/main/docs/README.md',
  knowledge: 'https://github.com/chunleik/self-analyst/blob/main/docs/personal-ontology.md'
};

test('new project links have exact bilingual labels and reference existing public documents', () => {
  const labels = {
    zh: { website: '项目官网', documentation: '帮助文档', knowledge: '个人知识指南' },
    en: { website: 'Project website', documentation: 'Documentation', knowledge: 'Personal knowledge guide' }
  };
  for (const [language, expected] of Object.entries(labels)) {
    const catalog = JSON.parse(fs.readFileSync(new URL(`../../main/resources/desktop-ui/locales/${language}.json`, import.meta.url), 'utf8'));
    for (const [action, label] of Object.entries(expected)) assert.equal(catalog['help.' + action], label);
  }
  for (const action of ['documentation', 'knowledge']) {
    const path = projectLinks[action].split('/blob/main/')[1];
    assert.ok(fs.statSync(new URL('../../../../' + path, import.meta.url)).isFile(), path);
  }
  const html = fs.readFileSync(new URL('../../main/resources/desktop-ui/index.html', import.meta.url), 'utf8');
  const menu = html.slice(html.indexOf('<div id="help-menu"'), html.indexOf('<dialog id="help-dialog"'));
  assert.match(menu, /data-help-action="knowledge"[^]*?role="separator"[^]*?data-help-action="feedback"/);
  assert.match(menu, /data-help-action="check_update"[^]*?role="separator"[^]*?data-help-action="website"/);
});

test('new Web links open only fixed targets in isolated tabs and preserve application state', async () => {
  for (const language of ['zh', 'en']) {
    const s = setup({ language });
    for (const [action, url] of Object.entries(projectLinks)) {
      s.ui.open();
      s.items.find(item => item.getAttribute('data-help-action') === action).click();
      await settle();
      const link = s.links.at(-1);
      assert.equal(link.href, url);
      assert.equal(link.target, '_blank');
      assert.equal(link.rel, 'noopener noreferrer');
      assert.equal(s.menu.hidden, true);
      assert.equal(s.document.activeElement, s.trigger);
      assert.equal(s.dialog.open, false);
    }
    assert.equal(s.calls.length, 0);
    assert.equal(s.host.location.origin, 'http://localhost:5701');
    assert.equal(s.state.chatDraft, '保留这段草稿');
    await s.ui.execute('website?url=https://untrusted.test');
    assert.equal(s.links.length, 3);
  }
});

test('new desktop links invoke fixed help actions with no URL or local data and allow reopening', async () => {
  for (const language of ['zh', 'en']) {
    const s = setup({ native: true, language }); await settle();
    for (const action of Object.keys(projectLinks)) {
      for (let repeat = 0; repeat < 2; repeat++) {
        s.ui.open();
        s.items.find(item => item.getAttribute('data-help-action') === action).click();
        await settle();
        assert.deepEqual(s.calls.at(-1), ['help_action', { action }]);
        assert.equal(s.menu.hidden, true);
        assert.equal(s.document.activeElement, s.trigger);
      }
    }
    assert.equal(s.links.length, 0);
    assert.equal(s.state.chatDraft, '保留这段草稿');
  }
});

test('new link failures are localized and repeated native retries never navigate the embedded page', async () => {
  for (const language of ['zh', 'en']) {
    for (const [action, url] of Object.entries(projectLinks)) {
      for (const native of [false, true]) {
        let failure = true;
        const s = setup({ native, language, openError: true, invoke: name => {
          if (name === 'help_action' && failure) throw Error('private backend details');
          return false;
        } });
        await settle(); await s.ui.execute(action);
        assert.equal(s.dialog.open, true);
        assert.equal(s.nodes.get('help-dialog-body').textContent, language + ':help.failed');
        assert.equal(s.nodes.get('help-dialog-link').href, url);
        assert.equal(s.nodes.get('help-dialog-link').hidden, false);
        if (native) {
          for (let repeat = 0; repeat < 2; repeat++) {
            const event = s.nodes.get('help-dialog-link').fire('click'); await settle();
            assert.equal(event.prevented, true);
            assert.deepEqual(s.calls.at(-1), ['help_action', { action }]);
            assert.equal(s.nodes.get('help-dialog-link').href, url);
          }
          failure = false;
          const event = s.nodes.get('help-dialog-link').fire('click'); await settle();
          assert.equal(event.prevented, true);
          assert.deepEqual(s.calls.at(-1), ['help_action', { action }]);
        }
        s.dialog.close();
        assert.equal(s.document.activeElement, s.trigger);
        s.ui.open(); assert.equal(s.menu.hidden, false);
        assert.equal(s.state.chatDraft, '保留这段草稿');
        assert.equal(s.links.length, 0);
      }
    }
  }
});

test('a delayed native link retry cannot reopen a dismissed error or replace a newer message', async () => {
  for (const newerMessage of [false, true]) {
    let rejectRetry;
    let attempt = 0;
    const s = setup({ native: true, invoke: (name, args) => {
      if (name !== 'help_action') return false;
      if (args.action === 'website') {
        if (++attempt === 1) throw Error('browser unavailable');
        return new Promise((resolve, reject) => { rejectRetry = reject; });
      }
      if (args.action === 'knowledge') throw Error('browser unavailable');
      return false;
    } });
    await settle(); await s.ui.execute('website');
    assert.equal(s.nodes.get('help-dialog-link').fire('click').prevented, true);
    await settle(); s.dialog.close();
    if (newerMessage) await s.ui.execute('knowledge');
    rejectRetry(Error('late private error')); await settle();
    assert.equal(s.dialog.open, newerMessage);
    if (newerMessage) assert.equal(s.nodes.get('help-dialog-link').href, projectLinks.knowledge);
    assert.equal(s.state.chatDraft, '保留这段草稿');
  }
});
