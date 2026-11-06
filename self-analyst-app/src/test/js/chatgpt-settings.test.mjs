import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const uiRoot = new URL('../../main/resources/desktop-ui/', import.meta.url);
const source = fs.readFileSync(new URL('llm-settings.js', uiRoot), 'utf8');
const plain = value => JSON.parse(JSON.stringify(value));
const flush = () => new Promise(setImmediate);
const authorizationUrl = () => {
  const url = new URL('https://auth.openai.com/api/accounts/authorize');
  for (const [key, value] of Object.entries({ client_id: 'dynamic_agent_client', response_type: 'code',
    redirect_uri: 'http://127.0.0.1:1455/auth/callback', state: 's'.repeat(32),
    code_challenge: 'c'.repeat(43), code_challenge_method: 'S256', scope: 'openid profile',
    resource: 'https://api.openai.com/v1', ext_agent_host_id: 'urn:uuid:12345678-1234-1234-1234-123456789abc', agent_name_hint: 'SelfAnalyst' })) url.searchParams.set(key, value);
  return url.href;
};
function settings() {
  return { fields: Object.fromEntries(Object.entries({ provider: 'api-key', baseUrl: 'https://example.org/v1',
    model: 'api-model', temperature: .7, chatgptAccount: '', chatgptModel: 'chatgpt-model' })
    .map(([key, value]) => [key, { effectiveValue: value, savedValue: null, source: 'environment' }])),
    credential: { configured: true, source: 'environment', hasUserOverride: false },
    runtime: { application: { llm: { status: 'applied' } } } };
}
function setup(handler) {
  const calls = [], timers = new Map(); let next = 0, changes = 0;
  const status = { status: 'signed_out', accounts: [], activeAccountId: null };
  const context = vm.createContext({ URL, AbortController, t: key => key,
    setTimeout: fn => { timers.set(++next, fn); return next; }, clearTimeout: key => timers.delete(key) });
  vm.runInContext(source, context);
  const request = (...args) => {
    calls.push(args);
    if (handler) { const result = handler(...args); if (result !== undefined) return result; }
    if (args[0] === '/chatgpt/status') return Promise.resolve(structuredClone(status));
    if (args[0] === '/presets') return Promise.resolve({ presets: [] });
    if (args[0] === '/chatgpt/cancel') return Promise.resolve({ cancelled: true });
    return Promise.resolve(settings());
  };
  const model = context.createLlmSettingsModel(request, () => changes++);
  return { model, calls, timers, context, status, changes: () => changes,
    tick: async () => { const entry = timers.entries().next().value; assert.ok(entry, 'expected scheduled poll');
      timers.delete(entry[0]); entry[1](); await flush(); } };
}
function connected(h, id = 'account-a') {
  h.status.status = 'connected'; h.status.activeAccountId = id;
  h.status.accounts = [{ id, label: 'Example account', email: 'example@example.invalid', connected: true }];
}

const login = { attemptId: 'attempt-a', authorizationUrl: authorizationUrl(), expiresAt: '2099-01-01T00:00:00Z' };

test('authorization browser target requires exact OpenAI origin, PKCE and loopback callback', () => {
  const h = setup(), valid = authorizationUrl();
  assert.equal(h.context.validChatgptAuthorizationUrl(valid), true);
  const returning = new URL(valid); returning.searchParams.set('client_id', 'oaiapp_registered');
  assert.equal(h.context.validChatgptAuthorizationUrl(returning.href), false);
  returning.searchParams.delete('agent_name_hint');
  assert.equal(h.context.validChatgptAuthorizationUrl(returning.href), true);
  for (const value of [null, 'javascript:alert(1)', valid + '#secret', valid.replace('auth.openai.com', 'auth.openai.com.evil.test'),
    valid.replace('https:', 'http:'), valid.replace('auth.openai.com', 'user@auth.openai.com'),
    valid.replace('/api/accounts/authorize', '/oauth/token'), valid + '&id_token_hint=secret', valid + '&code_verifier=secret',
    valid + '&client_id=other', valid + '&request_uri=https://example.org', valid.replace('api.openai.com', 'evil.test'),
    valid.replace('agent_name_hint=SelfAnalyst', 'agent_name_hint=Other'), valid.replace('S256', 'plain'),
    valid.replace('127.0.0.1', 'localhost'), valid.replace('%3A1455', '%3A0'), valid.replace('%3A1455', '%3A65536'),
    valid.replace('%2Fauth%2Fcallback', '%2Fother'), valid.replace('response_type=code', 'response_type=token')]) {
    assert.equal(h.context.validChatgptAuthorizationUrl(value), false, String(value));
  }
});

test('opening settings only reads local metadata, preserves API defaults and never initiates authorization', async () => {
  const h = setup(); await h.model.load();
  assert.deepEqual(h.calls.map(call => call[0]), ['', '/presets', '/chatgpt/status']);
  assert.equal(h.calls.every(call => !call[1]), true);
  assert.equal(h.model.value('provider'), 'api-key'); assert.equal(h.model.dirty(), false);
  assert.equal(h.timers.size, 0);
});

test('login blocks duplicate clicks and save; completion selects only a draft account', async () => {
  let finish;
  const h = setup(path => path === '/chatgpt/login' ? new Promise(resolve => { finish = resolve; }) : undefined);
  await h.model.load(); h.model.change('provider', 'chatgpt'); h.model.change('chatgptModel', 'unsaved-chatgpt');
  h.model.change('model', 'unsaved-api'); h.model.setCredential('replace', 'synthetic-key');
  const pending = h.model.startLogin(); await h.model.startLogin(); await h.model.save();
  assert.equal(h.calls.filter(call => call[0] === '/chatgpt/login').length, 1);
  assert.equal(h.calls.some(call => call[1] === 'PUT'), false);
  finish(login); assert.equal(await pending, login.authorizationUrl);
  await h.model.save(); assert.equal(h.calls.some(call => call[1] === 'PUT'), false);
  connected(h); await h.tick();
  assert.equal(h.model.login, null); assert.equal(h.model.authNotice, 'connected');
  assert.equal(h.model.value('chatgptAccount'), 'account-a');
  assert.equal(h.model.value('chatgptModel'), 'unsaved-chatgpt'); assert.equal(h.model.value('model'), 'unsaved-api');
  assert.equal(h.model.credential.value, 'synthetic-key'); assert.equal(h.model.dirty(), true);
  assert.deepEqual(plain(h.model.probe), {}); assert.equal(h.calls.some(call => call[1] === 'PUT'), false);
});

test('cancel, close, reload and provider changes clean up a late login start without reopening UI', async () => {
  for (const action of ['cancel', 'close', 'reload', 'provider']) {
    let finish;
    const h = setup(path => path === '/chatgpt/login' ? new Promise(resolve => { finish = resolve; }) : undefined);
    await h.model.load(); h.model.change('provider', 'chatgpt');
    const pending = h.model.startLogin();
    if (action === 'cancel') await h.model.cancelLogin();
    else if (action === 'close') h.model.destroy();
    else if (action === 'reload') await h.model.load();
    else h.model.change('provider', 'api-key');
    const changes = h.changes(); finish(login); assert.equal(await pending, null);
    assert.equal(h.model.login, null); assert.equal(h.timers.size, 0);
    assert.deepEqual(plain(h.calls.find(call => call[0] === '/chatgpt/cancel')[2]), { attemptId: 'attempt-a' });
    if (action === 'close') assert.equal(h.changes(), changes);
  }
});

test('cancelled status polls cannot restore a pending URL, account, or success notice', async () => {
  let finish, delay = false;
  const h = setup(path => path === '/chatgpt/login' ? Promise.resolve(login)
    : path === '/chatgpt/status' && delay ? new Promise(resolve => { finish = resolve; }) : undefined);
  await h.model.load(); await h.model.startLogin(); delay = true;
  const pending = h.model.refreshAuth(); await h.model.cancelLogin();
  finish({ status: 'connected', activeAccountId: 'late-account', accounts: [{ id: 'late-account', connected: true }] }); await pending;
  assert.equal(h.model.login, null); assert.equal(h.model.value('chatgptAccount'), '');
  assert.equal(h.model.authNotice, 'cancelled'); assert.equal(h.timers.size, 0);
});

test('latest status read wins and manual refresh can finish the pending login', async () => {
  let first, reads = 0;
  const h = setup(path => path === '/chatgpt/login' ? Promise.resolve(login)
    : path === '/chatgpt/status' && ++reads === 2 ? new Promise(resolve => { first = resolve; }) : undefined);
  await h.model.load(); await h.model.startLogin();
  const old = h.model.refreshAuth(); connected(h); await h.model.refreshAuth();
  first({ status: 'pending', accounts: [], loginAttemptId: 'attempt-a' }); await old;
  assert.equal(h.model.chatgpt.status, 'connected'); assert.equal(h.model.login, null);
  assert.equal(h.model.value('chatgptAccount'), 'account-a'); assert.equal(h.timers.size, 0);
});

test('pending status reattaches bounded local polling and cancels only the current attempt', async () => {
  const h = setup(); h.status.status = 'pending'; h.status.loginAttemptId = 'existing-attempt';
  await h.model.load(); assert.equal(h.model.login.attemptId, 'existing-attempt');
  assert.equal(h.model.login.authorizationUrl, undefined); assert.equal(h.timers.size, 1);
  await h.model.cancelLogin();
  assert.equal(h.calls.at(-1)[0], '/chatgpt/cancel'); assert.equal(h.calls.at(-1)[2].attemptId, 'existing-attempt');
  assert.equal(h.timers.size, 0);
});

test('invalid authorization URL cancels the backend attempt and never exposes it', async () => {
  const h = setup(path => path === '/chatgpt/login' ? Promise.resolve({ ...login, authorizationUrl: 'https://evil.test/?token=secret' }) : undefined);
  await h.model.load(); assert.equal(await h.model.startLogin(), null);
  assert.equal(h.model.login, null); assert.match(h.model.authError, /unavailable/);
  assert.equal(h.calls.at(-1)[0], '/chatgpt/cancel'); assert.equal(h.timers.size, 0);
});

test('auth failure stays local and sanitized; expired login permits a new attempt', async () => {
  const h = setup(path => path === '/chatgpt/login' ? Promise.resolve(login) : undefined);
  await h.model.load(); h.model.change('chatgptModel', 'draft'); await h.model.startLogin();
  h.status.status = 'error'; h.status.errorCode = 'chatgpt_expired'; await h.tick();
  assert.equal(h.model.login, null); assert.match(h.model.authError, /expired/);
  assert.equal(h.model.value('chatgptModel'), 'draft'); await h.model.startLogin();
  assert.equal(h.calls.filter(call => call[0] === '/chatgpt/login').length, 2);
  const bad = setup(path => path === '/chatgpt/login' ? Promise.reject(Object.assign(new Error('secret full response'), { code: 'unknown secret' })) : undefined);
  await bad.model.load(); await bad.model.startLogin();
  assert.equal(bad.model.authError, 'llm.chatgpt.error.unavailable'); assert.doesNotMatch(bad.model.authError, /secret/);
});

test('ChatGPT discovery and test never send API credentials or Base URL', async () => {
  const h = setup(path => path === '/chatgpt/models' ? Promise.resolve({ ok: true, models: [{ slug: 'discovered-model', displayName: 'Example' }] })
    : path === '/chatgpt/test' ? Promise.resolve({ ok: true, code: 'success' }) : undefined);
  connected(h); await h.model.load(); h.model.change('provider', 'chatgpt'); h.model.change('chatgptAccount', 'account-a');
  h.model.setCredential('replace', 'synthetic-api-key'); await h.model.run('discover');
  assert.deepEqual(plain(h.calls.at(-1)[2]), { accountId: 'account-a' }); assert.deepEqual(plain(h.model.candidates), ['discovered-model']);
  h.model.change('chatgptModel', 'manual'); await h.model.run('test');
  assert.deepEqual(plain(h.calls.find(call => call[0] === '/chatgpt/test')[2]), { accountId: 'account-a', model: 'manual' });
  assert.equal(h.calls.some(call => call[1] === 'PUT'), false); assert.equal(h.model.credential.value, 'synthetic-api-key');
});

test('account/provider changes abort old model results and keep independent model drafts', async () => {
  for (const field of ['provider', 'chatgptAccount', 'chatgptModel']) {
    let finish;
    const h = setup(path => path === '/chatgpt/models' ? new Promise(resolve => { finish = resolve; }) : undefined);
    connected(h); await h.model.load(); h.model.change('provider', 'chatgpt'); h.model.change('chatgptAccount', 'account-a');
    const pending = h.model.run('discover'); const signal = h.calls.at(-1)[3];
    h.model.change(field, field === 'provider' ? 'api-key' : 'new-value');
    assert.equal(signal.aborted, true); finish({ ok: true, models: [{ slug: 'old' }] }); await pending;
    assert.deepEqual(plain(h.model.candidates), []); assert.deepEqual(plain(h.model.probe), {});
    assert.equal(h.model.value('model'), 'api-model');
  }
});

test('logout removes no API fields, blocks duplicate requests, and does not claim remote revocation', async () => {
  let finish;
  const h = setup(path => path === '/chatgpt/logout' ? new Promise(resolve => { finish = resolve; }) : undefined);
  connected(h); await h.model.load(); h.model.change('chatgptAccount', 'account-a'); h.model.change('model', 'api-draft');
  const pending = h.model.logout(); await h.model.logout(); h.model.change('provider', 'chatgpt');
  assert.equal(h.calls.filter(call => call[0] === '/chatgpt/logout').length, 1);
  assert.equal(h.model.value('provider'), 'api-key');
  h.status.accounts = []; h.status.status = 'signed_out'; finish({ remoteRevocationConfirmed: false }); await pending; await flush();
  assert.equal(h.model.authNotice, 'disconnectedLocal'); assert.equal(h.model.selectedAccount(), undefined);
  assert.equal(h.model.value('model'), 'api-draft'); assert.equal(h.model.credential.action, 'keep');
});

test('ChatGPT inheritance resets require saving before probes rather than using stale account/model values', async () => {
  const h = setup(); connected(h); await h.model.load();
  h.model.change('provider', 'chatgpt'); h.model.change('chatgptAccount', 'account-a'); h.model.inherit('chatgptModel');
  await h.model.run('test'); assert.equal(h.calls.some(call => call[0] === '/chatgpt/test'), false);
});

test('all authentication states and safe errors have both production translations; credentials are not persisted in browser storage', () => {
  const codes = ['unsupported', 'network', 'timeout', 'cancelled', 'expired', 'authentication', 'permission', 'storage', 'identity', 'protocol', 'registration', 'callback', 'scope', 'unavailable'];
  const keys = [...source.matchAll(/"(llm\.chatgpt\.[\w.-]+)"/g)].map(m => m[1]).filter(key => !key.endsWith('.'));
  for (const code of codes) keys.push('llm.chatgpt.error.' + code);
  for (const state of ['signed_out', 'connected', 'pending', 'working']) keys.push('llm.chatgpt.state.' + state);
  for (const notice of ['connected', 'cancelled', 'disconnected', 'disconnectedLocal']) keys.push('llm.chatgpt.notice.' + notice);
  for (const lang of ['en', 'zh']) {
    const catalog = JSON.parse(fs.readFileSync(new URL('locales/' + lang + '.json', uiRoot), 'utf8'));
    for (const key of keys) assert.equal(typeof catalog[key], 'string', lang + ': ' + key);
  }
  assert.doesNotMatch(source, /localStorage|sessionStorage|document\.cookie/);
});

// Minimal DOM harness for production mount/event wiring. Actual layout still needs a browser.
function mountHarness(handler, invoke = async () => {}) {
  const h = setup(handler), doc = { activeElement: null };
  class Element {
    constructor(tag = 'div', attrs = {}) {
      this.tagName = tag.toUpperCase(); this.attrs = attrs; this.dataset = {}; this.events = {};
      for (const [key, value] of Object.entries(attrs)) if (key.startsWith('data-')) this.dataset[key.slice(5).replace(/-([a-z])/g, (_, c) => c.toUpperCase())] = value;
      this.value = attrs.value || ''; this.type = attrs.type || ''; this.hidden = 'hidden' in attrs; this.children = [];
    }
    set innerHTML(value) {
      this.html = value; this.children = [];
      for (const match of value.matchAll(/<([a-z][a-z0-9-]*)\b([^>]*)>/gi)) {
        const attrs = {};
        for (const attr of match[2].matchAll(/([\w-]+)(?:="([^"]*)")?/g)) attrs[attr[1]] = (attr[2] || '').replaceAll('&quot;', '"').replaceAll('&lt;', '<').replaceAll('&gt;', '>').replaceAll('&amp;', '&');
        this.children.push(new Element(match[1], attrs));
      }
    }
    get innerHTML() { return this.html || ''; }
    matches(selector) {
      if (selector.startsWith('#')) return this.attrs.id === selector.slice(1);
      const attr = /^\[([^=\]]+)(?:="([^"]*)")?\]$/.exec(selector);
      if (attr) return attr[1] in this.attrs && (attr[2] === undefined || this.attrs[attr[1]] === attr[2]);
      return this.tagName.toLowerCase() === selector.toLowerCase();
    }
    querySelectorAll(selector) { return this.children.filter(el => selector.split(',').some(part => el.matches(part))); }
    querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
    closest(selector) { return this.matches(selector) ? this : null; }
    hasAttribute(key) { return key in this.attrs; }
    setAttribute(key, value) { this.attrs[key] = value; }
    removeAttribute(key) { delete this.attrs[key]; if (key === 'href') this.href = ''; }
    addEventListener(key, callback) { this.events[key] = callback; }
    removeEventListener(key) { delete this.events[key]; }
    focus() { doc.activeElement = this; }
  }
  const root = new Element(); let confirm = true;
  h.context.document = doc;
  h.context.state = {};
  h.context.escHtml = value => String(value ?? '').replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;');
  h.context.window = { confirm: () => confirm, __SELF_ANALYST_DESKTOP__: true, __TAURI__: { core: { invoke } } };
  h.context.api = { llmSettings: (...args) => {
    h.calls.push(args);
    if (handler) { const value = handler(...args); if (value !== undefined) return value; }
    if (args[0] === '/chatgpt/status') return Promise.resolve(structuredClone(h.status));
    if (args[0] === '/presets') return Promise.resolve({ presets: [] });
    if (args[0] === '/chatgpt/cancel') return Promise.resolve({});
    if (args[1] === 'PUT') return Promise.resolve({ settings: settings() });
    return Promise.resolve(settings());
  } };
  const factory = h.context.createLlmSettingsModel;
  h.context.createLlmSettingsModel = (...args) => (h.model = factory(...args));
  h.view = h.context.mountLlmSettings(root); h.root = root;
  h.node = selector => { const node = root.querySelector(selector); assert.ok(node, 'missing node: ' + selector); return node; };
  h.click = async action => { const node = h.node('[data-llm-action="' + action + '"]'); let prevented = false;
    root.events.click({ target: node, preventDefault() { prevented = true; } }); await flush(); return prevented; };
  h.select = async (field, value) => { const node = h.node('[data-llm-field="' + field + '"]'); node.value = value;
    root.events.change({ target: node }); await flush(); };
  h.confirm = value => { confirm = value; };
  return h;
}

test('mounted provider switch hides unrelated controls, escapes accounts and preserves independent fields', async () => {
  const h = mountHarness(); connected(h); h.status.accounts[0].label = '<img src=x onerror=alert(1)>'; await flush();
  await h.select('provider', 'chatgpt'); await h.select('chatgptAccount', 'account-a');
  assert.equal(h.node('[data-llm-provider-section="api-key"]').hidden, true);
  assert.equal(h.node('[data-llm-provider-section="chatgpt"]').hidden, false);
  assert.match(h.node('[data-llm-field="chatgptAccount"]').innerHTML, /&lt;img/);
  assert.equal(h.node('[data-llm-field="chatgptAccount"]').querySelectorAll('img').length, 0);
  assert.equal(h.node('[data-llm-field="chatgptModel"]').value, 'chatgpt-model');
  assert.equal(h.node('[data-llm-field="model"]').value, 'api-model');
  await h.select('provider', 'api-key'); assert.equal(h.node('[data-llm-provider-section="api-key"]').hidden, false);
  h.view.destroy(); assert.equal(h.root.innerHTML, '');
});

test('mounted native browser failures allow retry, deduplicate opening and ignore failures from cancelled attempts', async () => {
  let rejectOpen, serial = 0; const opens = [];
  const h = mountHarness(path => path === '/chatgpt/login' ? Promise.resolve({ ...login, attemptId: 'attempt-' + (++serial) }) : undefined,
    (command, args) => { opens.push({ command, args }); return new Promise((resolve, reject) => { rejectOpen = reject; }); });
  await flush(); await h.select('provider', 'chatgpt'); await h.click('login');
  assert.equal(opens.length, 1); assert.equal(opens[0].command, 'help_action'); assert.equal(opens[0].args.action, 'chatgpt_login');
  assert.equal(h.node('#llm-auth-open').hidden, false); assert.equal(await h.click('open-login'), true);
  assert.equal(opens.length, 1, 'duplicate link click does not launch twice');
  rejectOpen(Error('private raw native error')); await flush();
  assert.equal(h.node('#llm-auth-error').textContent, 'llm.chatgpt.browserFailed');
  await h.click('open-login'); assert.equal(opens.length, 2);
  const oldReject = rejectOpen; await h.click('cancel-login'); await h.click('login');
  assert.equal(opens.length, 3); oldReject(Error('old request')); await flush();
  assert.equal(h.model.login.attemptId, 'attempt-2'); assert.equal(h.model.authError, '');
  h.view.destroy(); assert.equal(h.root.innerHTML, '');
});

test('mounted web sign-in link stays isolated and disappears on cancel without changing current location', async () => {
  const h = mountHarness(path => path === '/chatgpt/login' ? Promise.resolve(login) : undefined);
  h.context.window.__SELF_ANALYST_DESKTOP__ = false;
  await flush(); await h.select('provider', 'chatgpt'); await h.click('login');
  const link = h.node('#llm-auth-open');
  assert.equal(link.href, login.authorizationUrl); assert.equal(link.attrs.target, '_blank');
  assert.equal(link.attrs.rel, 'noopener noreferrer'); assert.equal(await h.click('open-login'), false);
  await h.click('cancel-login'); assert.equal(link.hidden, true); assert.equal(link.href, '');
  assert.equal(await h.click('open-login'), true); h.view.destroy();
});

test('mounted logout requires explicit confirmation and late completion cannot recreate closed settings', async () => {
  let finish;
  const h = mountHarness(path => path === '/chatgpt/logout' ? new Promise(resolve => { finish = resolve; }) : undefined);
  connected(h); await flush(); await h.select('provider', 'chatgpt'); await h.select('chatgptAccount', 'account-a');
  h.confirm(false); await h.click('logout'); assert.equal(h.calls.some(call => call[0] === '/chatgpt/logout'), false);
  h.confirm(true); await h.click('logout'); assert.equal(h.calls.filter(call => call[0] === '/chatgpt/logout').length, 1);
  assert.equal(h.node('[data-llm-action="cancel-login"]').hidden, true);
  h.view.destroy(); finish({ remoteRevocationConfirmed: false }); await flush();
  assert.equal(h.root.innerHTML, '');
});

test('successful identity login without inference scope selects the draft and shows permission limits', async () => {
  const h = setup(path => path === '/chatgpt/login' ? Promise.resolve(login) : undefined);
  await h.model.load(); await h.model.startLogin();
  h.status.status = 'error'; h.status.activeAccountId = 'limited-account'; h.status.errorCode = 'chatgpt_plan_permission_required';
  h.status.accounts = [{ id: 'limited-account', connected: false }]; await h.tick();
  assert.equal(h.model.value('chatgptAccount'), 'limited-account'); assert.equal(h.model.authNotice, 'authorizedLimited');
  assert.equal(h.model.authError, 'llm.chatgpt.error.permission'); assert.equal(h.model.selectedAccount(), undefined);
  assert.equal(h.model.login, null); assert.deepEqual(plain(h.model.probe), {});
});

test('logout failures remain visible after refreshing local account state and do not claim success', async () => {
  const h = setup(path => path === '/chatgpt/logout' ? Promise.reject(Object.assign(new Error('private details'), { code: 'chatgpt_storage_unavailable' })) : undefined);
  connected(h); await h.model.load(); h.model.change('chatgptAccount', 'account-a');
  await h.model.logout();
  assert.equal(h.model.authError, 'llm.chatgpt.error.storage'); assert.equal(h.model.authNotice, '');
  assert.equal(h.model.authBusy, false);
});

test('browser-open error survives pending status refresh and clears on retry', async () => {
  const h = setup(path => path === '/chatgpt/login' ? Promise.resolve(login) : undefined);
  await h.model.load(); await h.model.startLogin(); h.model.browserFailed();
  h.status.status = 'pending'; h.status.loginAttemptId = login.attemptId; await h.model.refreshAuth();
  assert.equal(h.model.authError, 'llm.chatgpt.browserFailed'); h.model.retryBrowser();
  assert.equal(h.model.authError, '');
});

test('provider changes while initial account status is loading do not discard useful local metadata', async () => {
  let finish;
  const h = setup(path => path === '/chatgpt/status' ? new Promise(resolve => { finish = resolve; }) : undefined);
  await h.model.load(); h.model.change('provider', 'chatgpt'); h.model.change('chatgptModel', 'pending-model');
  finish({ status: 'connected', activeAccountId: 'account-a', accounts: [{ id: 'account-a', connected: true }] }); await flush();
  assert.equal(h.model.chatgpt.accounts.length, 1); assert.equal(h.model.value('chatgptModel'), 'pending-model');
  assert.equal(h.model.value('chatgptAccount'), '', 'reading metadata does not silently select an account');
});

test('mounted usage pause leaves explicit test enabled and a test refreshes account pause state', async () => {
  const h = mountHarness(path => path === '/chatgpt/test' ? Promise.resolve({ ok: true, code: 'success' }) : undefined);
  connected(h); h.status.accounts[0].usagePaused = true; await flush();
  await h.select('provider', 'chatgpt'); await h.select('chatgptAccount', 'account-a');
  assert.equal(h.node('#llm-auth-paused').hidden, false); assert.equal(h.node('[data-llm-action="test"]').disabled, false);
  h.status.accounts[0].usagePaused = false;
  const before = h.calls.filter(call => call[0] === '/chatgpt/status').length; await h.click('test');
  assert.equal(h.calls.filter(call => call[0] === '/chatgpt/status').length, before + 1);
  assert.equal(h.node('#llm-auth-paused').hidden, true); assert.equal(h.model.value('chatgptAccount'), 'account-a');
  h.view.destroy();
});

test('settings API retains only the structured error code for safe authentication presentation', async () => {
  const context = vm.createContext({ window: { location: { origin: 'http://localhost' } },
    t: key => key, fetch: async () => ({ ok: false, json: async () => ({ errorCode: 'chatgpt_storage_unavailable' }) }) });
  vm.runInContext(fs.readFileSync(new URL('api.js', uiRoot), 'utf8'), context);
  await assert.rejects(context.api.llmSettings('/chatgpt/login', 'POST', {}), error => error.code === 'chatgpt_storage_unavailable');
});


test('authentication error aliases distinguish temporary refresh failures from required sign-in', () => {
  const h = setup();
  for (const [code, category] of Object.entries({
    chatgpt_refresh_failed: 'network', chatgpt_refresh_not_yet_allowed: 'network',
    chatgpt_request_timeout: 'timeout', chatgpt_sign_in_required: 'authentication',
    chatgpt_unknown_account: 'authentication', chatgpt_plan_permission_required: 'permission',
  })) {
    assert.equal(h.context.chatgptErrorText(code), 'llm.chatgpt.error.' + category, code);
    assert.equal(h.context.chatgptProbeText({ code }), 'llm.chatgpt.error.' + category, code);
  }
});

test('native authorization validator uses a correctly escaped Rust backslash literal', () => {
  const native = fs.readFileSync(new URL('../../../../self-analyst-desktop/src-tauri/src/titlebar.rs', import.meta.url), 'utf8');
  const guard = native.match(/value\s*\.\s*chars\s*\(\s*\)\s*\.\s*any\s*\(\s*\|c\|\s*c\.is_ascii_control\(\)\s*\|\|\s*c\s*==\s*' '\s*\|\|\s*c\s*==\s*('[^'\r\n]*')\s*\)/);
  assert.ok(guard, 'native authorization URL character guard exists regardless of line wrapping');
  const literal = guard[1];
  assert.deepEqual([...Buffer.from(literal)], [39, 92, 92, 39], 'Rust needs two source backslashes inside the character literal');
});
