import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const source = fs.readFileSync(new URL('../../main/resources/desktop-ui/titlebar-help.js', import.meta.url), 'utf8');
const settle = () => new Promise(resolve => setImmediate(resolve));
function setup({ native = false, language = 'zh', invoke, openError = false, storage, intl = Intl } = {}) {
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
  if (storage) host.localStorage = storage;
  const state = { lang: language, chatDraft: '保留这段草稿' };
  const context = vm.createContext({ window: host, document, state, Intl: intl, t: key => state.lang + ':' + key });
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

test('native about uses the guarded host operation; browser about has no fictional shell version', async () => {
  const native = setup({ native: true, invoke: (name, args) => args.action === 'about' ? { version: '0.6.1-beta.8', backendUrl: 'http://localhost:5701', development: true } : false }); await settle(); await native.ui.execute('about');
  assert.deepEqual(native.calls.filter(([name]) => name === 'help_action'), [['help_action', { action: 'about' }]]);
  assert.match(native.nodes.get('help-dialog-body').textContent, /SelfAnalyst v0.6.1-beta.8/);
  assert.match(native.nodes.get('help-dialog-body').textContent, /releaseDevelopment/);
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

const aboutInfo = { version: '0.6.1-beta.8', backendUrl: 'http://localhost:5701', development: false };
const publishedAt = '2026-10-13T10:20:30Z';
const releaseInfo = { version: aboutInfo.version, publishedAt };
function memoryStorage(initial) {
  let value = initial === undefined ? null : JSON.stringify(initial);
  return { getItem: () => value, setItem: (_, next) => { value = next; }, value: () => value };
}
function aboutHost(release = releaseInfo, info = aboutInfo) {
  return (name, args) => args.action === 'about' ? info : args.action === 'release_info' ? release : false;
}

test('about retains version, Tauri and backend and formats exact publication in local timezone', async () => {
  for (const language of ['zh', 'en']) {
    const formats = [];
    const intl = { DateTimeFormat: function (locale, options) {
      formats.push({ locale, options });
      return new Intl.DateTimeFormat(locale, { ...options, timeZone: 'Asia/Shanghai' });
    } };
    const s = setup({ native: true, language, intl, invoke: aboutHost() });
    await settle(); await s.ui.execute('about');
    const body = s.nodes.get('help-dialog-body').textContent;
    assert.match(body, /SelfAnalyst v0\.6\.1-beta\.8/);
    assert.match(body, /Tauri/); assert.match(body, /http:\/\/localhost:5701/);
    assert.match(body, /18:20:30/); assert.match(body, /GMT\+8/);
    assert.match(body, new RegExp(language + ':help.releasedAt'));
    assert.equal(formats[0].locale, language === 'zh' ? 'zh-CN' : 'en-US');
    assert.equal(formats[0].options.timeZone, undefined, 'production uses user timezone');
    assert.equal(s.nodes.get('help-dialog-link').hidden, true);
    assert.equal(s.state.chatDraft, '保留这段草稿');
  }
});

test('publication validation rejects normalized, missing and unsafe timestamps', () => {
  const s = setup();
  for (const invalid of [null, undefined, '', 'yesterday', '<script>', '2026-02-30T00:00:00Z',
    '2026-13-01T00:00:00Z', '2026-01-01T24:00:00Z', '2026-01-01T01:00:00', 0, '2025-02-29T00:00:00Z', '0000-01-01T00:00:00Z']) {
    assert.equal(s.lib.formatPublication(invalid, 'en'), null, String(invalid));
  }
  assert.ok(s.lib.formatPublication('2024-02-29T00:00:00Z', 'en'));
});

test('about opens immediately, keeps controls usable, deduplicates fetches and survives reopen', async () => {
  let finish;
  const s = setup({ native: true, invoke: (name, args) => args.action === 'release_info'
    ? new Promise(resolve => { finish = resolve; }) : aboutHost()(name, args) });
  await settle();
  const first = s.ui.execute('about');
  assert.equal(s.dialog.open, true); await settle();
  assert.match(s.nodes.get('help-dialog-body').textContent, /SelfAnalyst v0.6.1-beta.8/);
  assert.match(s.nodes.get('help-dialog-body').textContent, /releaseLoading/);
  s.dialog.close(); assert.equal(s.document.activeElement, s.trigger);
  s.ui.open(); assert.equal(s.menu.hidden, false);
  const reopened = s.ui.execute('about');
  const repeated = s.ui.execute('about'); await settle();
  assert.equal(s.calls.filter(([, args]) => args.action === 'release_info').length, 1);
  finish(releaseInfo); await Promise.all([first, reopened, repeated]);
  assert.match(s.nodes.get('help-dialog-body').textContent, /2026/);
  assert.doesNotMatch(s.nodes.get('help-dialog-body').textContent, /releaseLoading/);
  s.dialog.close(); await s.ui.execute('about');
  assert.equal(s.calls.filter(([, args]) => args.action === 'release_info').length, 1, 'reuse successful public time');
});

test('late about metadata and publication cannot reopen a closed dialog or replace a newer message', async () => {
  for (const stage of ['about', 'release_info']) {
    for (const replace of [false, true]) {
      let finish;
      const s = setup({ native: true, invoke: (name, args) => {
        if (args.action === stage) return new Promise(resolve => { finish = resolve; });
        if (args.action === 'guide') throw Error('unavailable');
        return aboutHost()(name, args);
      } });
      await settle(); const promise = s.ui.execute('about'); await settle();
      s.dialog.close();
      if (replace) await s.ui.execute('guide');
      finish(stage === 'about' ? aboutInfo : releaseInfo); await promise;
      assert.equal(s.dialog.open, replace);
      if (replace) assert.equal(s.nodes.get('help-dialog-body').textContent, 'zh:help.failed');
      if (stage === 'about') assert.equal(s.calls.filter(([, args]) => args.action === 'release_info').length, 0);
    }
  }
});

test('cache contains only exact-version public metadata, persists offline and rejects stale versions', async () => {
  const storage = memoryStorage();
  const online = setup({ native: true, storage, invoke: aboutHost() });
  await settle(); await online.ui.execute('about');
  assert.deepEqual(JSON.parse(storage.value()), releaseInfo);
  const offline = setup({ native: true, storage, invoke: (name, args) => {
    if (args.action === 'release_info') throw Error('offline');
    return aboutHost()(name, args);
  } });
  await settle(); await offline.ui.execute('about');
  assert.match(offline.nodes.get('help-dialog-body').textContent, /2026/);
  assert.equal(offline.calls.filter(([, args]) => args.action === 'release_info').length, 0);
  const newer = setup({ native: true, storage, invoke: aboutHost(null, { ...aboutInfo, version: '0.6.1-beta.9' }) });
  await settle(); await newer.ui.execute('about');
  assert.match(newer.nodes.get('help-dialog-body').textContent, /releaseUnavailable/);
  assert.doesNotMatch(newer.nodes.get('help-dialog-body').textContent, /2026/);
});

test('invalid cache, mismatched version, null time and storage failures degrade without hiding about', async () => {
  for (const release of [null, { ...releaseInfo, version: '0.6.0' }, { ...releaseInfo, publishedAt: '2026-02-30T00:00:00Z' }, { ...releaseInfo, publishedAt: null }]) {
    const storage = memoryStorage({ ...releaseInfo, publishedAt: 'invalid' });
    const s = setup({ native: true, storage, invoke: aboutHost(release) });
    await settle(); await s.ui.execute('about');
    assert.match(s.nodes.get('help-dialog-body').textContent, /releaseUnavailable/);
    assert.match(s.nodes.get('help-dialog-body').textContent, /SelfAnalyst v0.6.1-beta.8/);
    assert.equal(JSON.parse(storage.value()).publishedAt, 'invalid', 'invalid API data is not cached');
  }
  const s = setup({ native: true, storage: { getItem() { throw Error('disabled'); }, setItem() { throw Error('full'); } }, invoke: aboutHost() });
  await settle(); await s.ui.execute('about'); assert.match(s.nodes.get('help-dialog-body').textContent, /2026/);
  s.dialog.close(); await s.ui.execute('about');
  assert.equal(s.calls.filter(([, args]) => args.action === 'release_info').length, 1);
});

test('failed publication can retry after reopen and never exposes raw errors', async () => {
  let failing = true;
  const s = setup({ native: true, invoke: (name, args) => {
    if (args.action === 'release_info' && failing) throw Error('secret failure details');
    return aboutHost()(name, args);
  } });
  await settle(); await s.ui.execute('about');
  assert.match(s.nodes.get('help-dialog-body').textContent, /releaseUnavailable/);
  assert.doesNotMatch(s.nodes.get('help-dialog-body').textContent, /secret/);
  failing = false; s.dialog.close(); await s.ui.execute('about');
  assert.match(s.nodes.get('help-dialog-body').textContent, /2026/);
});

test('development skips publication fetch and cached release and both languages have complete labels', async () => {
  const s = setup({ native: true, storage: memoryStorage(releaseInfo), invoke: aboutHost(releaseInfo, { ...aboutInfo, development: true }) });
  await settle(); await s.ui.execute('about');
  assert.match(s.nodes.get('help-dialog-body').textContent, /releaseDevelopment/);
  assert.doesNotMatch(s.nodes.get('help-dialog-body').textContent, /2026/);
  assert.equal(s.calls.filter(([, args]) => args.action === 'release_info').length, 0);
  for (const language of ['en', 'zh']) {
    const catalog = JSON.parse(fs.readFileSync(new URL(`../../main/resources/desktop-ui/locales/${language}.json`, import.meta.url), 'utf8'));
    for (const key of ['aboutLoading', 'desktop', 'backend', 'releasedAt', 'releaseLoading', 'releaseUnavailable', 'releaseDevelopment']) assert.ok(catalog['help.' + key], key);
  }
});

test('reopened about formats with the current effective language', async () => {
  const locales = [];
  const intl = { DateTimeFormat: function (locale, options) { locales.push(locale); return new Intl.DateTimeFormat(locale, options); } };
  const s = setup({ native: true, intl, invoke: aboutHost() });
  await settle(); await s.ui.execute('about'); s.dialog.close();
  s.state.lang = 'en'; await s.ui.execute('about');
  assert.equal(locales.at(-1), 'en-US');
  assert.match(s.nodes.get('help-dialog-body').textContent, /en:help.releasedAt/);
});

test('late about responses cannot replace an update dialog and vice versa', async () => {
  for (const stage of ['about', 'release_info', 'check_update']) {
    for (const rejectLate of [false, true]) {
      let finish;
      const s = setup({ native: true, invoke: (name, args) => {
        if (args.action === stage) return new Promise((resolve, reject) => { finish = () => rejectLate ? reject('failure') : resolve(stage === 'about' ? aboutInfo : stage === 'release_info' ? releaseInfo : { available: false, currentVersion: aboutInfo.version, latestVersion: aboutInfo.version }); });
        if (args.action === 'check_update') return { available: false, currentVersion: aboutInfo.version, latestVersion: aboutInfo.version };
        return aboutHost()(name, args);
      } });
      await settle(); const first = s.ui.execute(stage === 'check_update' ? 'check_update' : 'about'); await settle();
      s.dialog.close(); await s.ui.execute(stage === 'check_update' ? 'about' : 'check_update');
      const expectedTitle = s.nodes.get('help-dialog-title').textContent;
      const expectedBody = s.nodes.get('help-dialog-body').textContent;
      finish(); await first;
      assert.equal(s.nodes.get('help-dialog-title').textContent, expectedTitle);
      assert.equal(s.nodes.get('help-dialog-body').textContent, expectedBody);
    }
  }
});

test('concurrent about metadata is deduplicated and storage getter failures remain harmless', async () => {
  let finish;
  const s = setup({ native: true, invoke: (name, args) => args.action === 'about'
    ? new Promise(resolve => { finish = () => resolve(aboutInfo); }) : aboutHost()(name, args) });
  Object.defineProperty(s.host, 'localStorage', { get() { throw Error('SecurityError'); } });
  await settle(); const first = s.ui.execute('about'); const repeated = s.ui.execute('about'); await settle();
  s.dialog.close(); const reopened = s.ui.execute('about'); await settle();
  assert.equal(s.calls.filter(([, args]) => args.action === 'about').length, 1);
  finish(); await Promise.all([first, repeated, reopened]);
  assert.match(s.nodes.get('help-dialog-body').textContent, /2026/);
  assert.equal(s.calls.filter(([, args]) => args.action === 'release_info').length, 1);
});
