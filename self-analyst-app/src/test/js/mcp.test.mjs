import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const base = new URL('../../main/resources/desktop-ui/', import.meta.url);
const read = name => fs.readFileSync(new URL(name, base), 'utf8');
const source = read('mcp.js');
const recv = ['synthetic@example.test'];
const mail = { id: 'mail-1', label: 'Inbox <img>', enabled: true, expose: false, allowDraft: false, allowSend: false, smtpConfigured: true };
const pending = { id: 'draft-1', operation: 'create_draft', account: 'account@example.test', to: recv, subject: '<b>Subject</b>', body: '<script>First line</script>\nSecond line' };
const snapshot = (updates = {}) => ({ revision: 2, accounts: [mail], baseUrl: 'http://127.0.0.1:5700',
  tokenConfigured: false, persistentSupported: true, pending: [], ...updates });
const nextTurn = () => new Promise(resolve => setImmediate(resolve));
function fixture() {
  class Element {
    constructor(tag, text, className) { this.tagName = tag.toUpperCase(); this.children = []; this.textContent = text ?? ''; this.className = className || ''; this.value = ''; this.attributes = {}; this.events = {}; }
    appendChild(child) { this.children.push(child); return child; }
    replaceChildren(...children) { this.children = children; }
    setAttribute(key, value) { this.attributes[key] = value; }
    addEventListener(type, fn) { this.events[type] = fn; }
    fire(type) { this.events[type]?.(); }
  }
  const requests = [], confirmed = [], copied = [];
  let pull = snapshot(), handled = () => Promise.reject({ status: 500 }), answer = true;
  let tick, stopped = false, embeddedDestroyed = false;
  const document = { hidden: false };
  const context = vm.createContext({
    URL, AbortController, document, t: key => key,
    setInterval: fn => { tick = fn; return 1; }, clearInterval: () => { stopped = true; },
    window: { confirm: message => { confirmed.push(message); return answer; } },
    navigator: { clipboard: { writeText: text => { copied.push(text); return Promise.resolve(); } } },
    api: { mcpManagement(path, method, payload, signal) {
      requests.push({ path, method, body: payload === undefined ? undefined : JSON.parse(JSON.stringify(payload)), signal });
      return method === 'GET' ? Promise.resolve(structuredClone(pull)) : handled(path, method, payload);
    } },
    integrationElement: (tag, text, className) => new Element(tag, text, className),
    integrationButton: (text, fn) => { const node = new Element('button', text); node.addEventListener('click', fn); return node; },
    mountIntegrations: root => {
      root.appendChild(new Element('p', 'Embedded connections'));
      return { dirty: () => false, saving: () => false, destroy: () => { embeddedDestroyed = true; } };
    }
  });
  vm.runInContext(source, context);
  const root = new Element('section'), view = context.mountMcpManagement(root);
  const nodes = (node = root) => [node, ...node.children.flatMap(nodes)];
  const button = key => nodes().find(node => node.tagName === 'BUTTON' && node.textContent === key);
  const field = key => nodes().find(node => node.tagName === 'LABEL' && node.textContent === 'mcp.' + key)?.children[0];
  const set = (key, value) => { const node = field(key); node.checked = value; node.fire('change'); };
  return { context, root, view, document, requests, confirmed, copied, nodes, button, field, set, tick: () => tick(),
    stopped: () => stopped, embeddedDestroyed: () => embeddedDestroyed,
    handle: fn => { handled = fn; }, status: value => { pull = value; }, answer: value => { answer = value; },
    texts: () => nodes().map(node => node.textContent).join('\n') };
}

test('MCP is a top-level tab with one editor and Settings remains a guarded shortcut', () => {
  const html = read('index.html'), ui = read('ui.js');
  assert.match(html, /data-tab="mcp" aria-controls="tab-mcp"/);
  assert.match(html, /id="tab-mcp"[^>]*aria-labelledby="mcp-nav-tab"/);
  assert.match(html, /data-config-view="integrations"/);
  assert.match(ui, /view === "integrations"[\s\S]{0,110}closeConfigModal\(\)[\s\S]{0,110}switchTab\("mcp"\)/);
  assert.match(ui, /state\.mcpView\.saving\(\) \|\| state\.mcpView\.dirty\(\) && !window\.confirm/);
  assert.match(ui, /state\.mcpView\.destroy\(\)/);
  assert.doesNotMatch(ui, /mountIntegrations\(/);
});

test('opening/polling is read only, defaults are off and dirty policy survives polling', async () => {
  const h = fixture(); await nextTurn();
  assert.match(h.texts(), /Embedded connections/);
  assert.equal(h.field('expose').checked, false);
  assert.equal(h.field('allowSend').checked, false);
  h.set('expose', true); h.tick(); await nextTurn();
  assert.equal(h.view.dirty(), true); assert.equal(h.field('expose').checked, true);
  h.document.hidden = true; h.tick(); await nextTurn();
  assert.deepEqual(h.requests.map(r => r.method), ['GET', 'GET']);
  h.view.destroy(); assert.equal(h.stopped(), true); assert.equal(h.embeddedDestroyed(), true);
  assert.equal(h.nodes().length, 1);
});

test('disclosure confirmation and exact account settings precede authorization; stale edits do not save', async () => {
  const h = fixture(); await nextTurn();
  h.set('expose', true); h.set('allowDraft', true); h.answer(false);
  h.button('config.saveChanges').fire('click');
  assert.equal(h.requests.length, 1);
  h.answer(true);
  h.handle(() => { h.status(snapshot({ revision: 3, accounts: [{ ...mail, expose: true, allowDraft: true }] })); return Promise.resolve(snapshot({ revision: 3, accounts: [{ ...mail, expose: true, allowDraft: true }] })); });
  h.button('config.saveChanges').fire('click'); await nextTurn();
  assert.deepEqual(h.requests[1].body, { revision: 2, accounts: [{ id: 'mail-1', expose: true, allowDraft: true, allowSend: false }] });
  assert.equal(h.view.dirty(), false);
  h.set('allowDraft', false); h.status(snapshot({ revision: 4 })); h.tick(); await nextTurn();
  assert.match(h.texts(), /mcp.conflict/);
  assert.equal(h.field('allowDraft').checked, false); assert.equal(h.button('config.saveChanges').disabled, true);
  h.button('config.saveChanges').fire('click');
  assert.equal(h.requests.filter(r => r.method === 'PUT').length, 1);
});

test('a token is requested only on explicit rotation and never rendered; explicit copy uses loopback config', async () => {
  const h = fixture(); await nextTurn();
  assert.equal(h.button('mcp.copyConfig').disabled, true);
  const rotated = snapshot({ revision: 3, tokenConfigured: true });
  h.handle(() => { h.status(rotated); return Promise.resolve({ token: 'fake-private_token', status: rotated }); });
  h.button('mcp.createToken').fire('click'); await nextTurn();
  assert.deepEqual(h.requests[1].body, { revision: 2 });
  assert.equal(h.requests[1].path, '/token');
  assert.equal(h.copied.length, 0); assert.doesNotMatch(h.texts(), /fake-private_token/);
  h.button('mcp.copyConfig').fire('click'); await nextTurn();
  assert.deepEqual(JSON.parse(h.copied[0]), { mcpServers: { 'self-analyst': {
    url: 'http://127.0.0.1:5700/mcp/all', headers: { Authorization: 'Bearer fake-private_token' }
  } } });
  assert.equal(h.context.mcpLoopbackUrl('http://127.0.0.1:5700@evil.invalid'), '');
  h.view.destroy();
});

test('SMTP is a separate write-only operation and enabling send requires configured SMTP', async () => {
  const h = fixture(); h.status(snapshot({ accounts: [{ ...mail, smtpConfigured: false }] })); await nextTurn();
  assert.equal(h.field('allowSend').disabled, true);
  const field = h.field('smtpSecret'); field.value = 'fake-code'; field.fire('input');
  h.handle(() => { const result = snapshot({ revision: 3 }); h.status(result); return Promise.resolve(result); });
  h.button('mcp.storeSmtp').fire('click');
  assert.equal(field.value, '');
  await nextTurn();
  assert.equal(h.requests[1].path, '/smtp/mail-1');
  assert.deepEqual(h.requests[1].body, { revision: 2, secret: 'fake-code' });
  assert.equal(h.field('allowSend').disabled, false); assert.equal(h.field('allowSend').checked, false);
  assert.doesNotMatch(h.texts(), /fake-code/);
});

test('the exact full approval is plain text and no action is sent until the user presses approve and confirms', async () => {
  const h = fixture(); h.status(snapshot({ pending: [pending] })); await nextTurn();
  assert.match(h.texts(), /account@example.test/);
  assert.match(h.texts(), /synthetic@example.test/);
  assert.match(h.texts(), /<b>Subject<\/b>/);
  assert.match(h.texts(), /<script>First line<\/script>\nSecond line/);
  assert.equal(h.nodes().some(node => node.tagName === 'SCRIPT'), false);
  const captured = h.button('mcp.approve'); h.tick(); await nextTurn();
  assert.equal(h.button('mcp.approve'), captured);
  h.answer(false); captured.fire('click'); await nextTurn();
  assert.equal(h.requests.filter(r => r.method === 'POST').length, 0);
  h.answer(true); h.handle(() => { h.status(snapshot()); return Promise.resolve({}); });
  captured.fire('click'); await nextTurn();
  assert.deepEqual(h.requests.find(r => r.method === 'POST').body, { allowed: true });
  assert.equal(h.requests.find(r => r.method === 'POST').path, '/approval/draft-1');
  assert.match(h.texts(), /mcp.approved/); assert.doesNotMatch(h.texts(), /Second line/);
});

test('all referenced labels are localized and responses use no-store on the desktop path', () => {
  for (const language of ['en','zh']) {
    const dict = JSON.parse(read('locales/' + language + '.json'));
    for (const key of new Set(Array.from(source.matchAll(/"(mcp\.[\w]+)"/g), item => item[1]))) assert.ok(dict[key], key);
    for (const key of ['expose','allowDraft','allowSend']) assert.ok(dict['mcp.' + key]);
  }
  assert.match(read('api.js'), /"\/desktop\/mcp-management"[\s\S]{0,125}cache: "no-store"/);
  assert.doesNotMatch(source, /innerHTML|outerHTML|localStorage|sessionStorage|console\./);
});
