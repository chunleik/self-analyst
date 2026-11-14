import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import { webcrypto } from 'node:crypto';

const base = new URL('../../main/resources/desktop-ui/', import.meta.url);
const source = fs.readFileSync(new URL('integrations.js', base), 'utf8');
const ui = fs.readFileSync(new URL('ui.js', base), 'utf8');
const plain = v => JSON.parse(JSON.stringify(v));
const deferred = () => { let resolve, reject; const promise = new Promise((ok, fail) => { resolve = ok; reject = fail; }); return { promise, resolve, reject }; };
function snapshot() {
  return { config: { schemaVersion: 1, revision: 4, mcpServers: [], toolApprovals: [], mailAccounts: [
    { id: 'mail-one', provider: 'qq', email: 'test@qq.com', displayName: 'Test', folderScope: 'selected', allowedFolders: ['INBOX'], bodyAccess: false,
      enabled: false, credentialRef: 'session:00000000-0000-0000-0000-000000000000', storageMode: 'session', disclosureVersion: 0 }
  ] }, statuses: { 'mail-one': { state: 'disabled', credentialConfigured: false } }, persistentCredentialsSupported: false, modelTarget: 'https://model.example/v1' };
}
function setup(handler) {
  const calls = [];
  const context = vm.createContext({ AbortController, crypto: webcrypto, t: key => key });
  vm.runInContext(source, context);
  const request = (...args) => { calls.push(args); return handler?.(...args) ?? Promise.resolve(snapshot()); };
  return { model: context.createIntegrationsModel(request, () => {}), context, calls, request };
}

test('local load is side-effect free; new mail and MCP metadata starts disabled with strict fields', async () => {
  const h = setup(); await h.model.load();
  assert.deepEqual(h.calls.map(c => [c[0], c[1]]), [['', 'GET']]);
  h.model.add('mcp'); const mcp = plain(h.model.entry());
  assert.equal(mcp.transport, 'streamable-http'); assert.equal(mcp.authType, 'anonymous'); assert.equal(mcp.enabled, false);
  assert.equal(mcp.credentialRef, null); assert.equal(mcp.budget.maxTools, 64);
  h.model.add('mail'); assert.equal(h.model.entry().disclosureVersion, 0); assert.equal(h.model.entry().enabled, false);
  assert.equal(h.model.entry().folderScope, 'all'); assert.equal(h.model.entry().bodyAccess, false);
  assert.deepEqual(plain(h.model.entry().allowedFolders), ['INBOX']);
  assert.equal(h.calls.filter(call => !call[0].startsWith('/mail/folders')).length, 1);
});

test('saving sends revision and metadata only; duplicate save and edits are locked', async () => {
  const pending = deferred(); const h = setup((path, method) => method === 'PUT' ? pending.promise : undefined);
  await h.model.load(); h.model.select('mail', 'mail-one'); h.model.change('displayName', 'Changed'); h.model.setSecret('SYNTHETIC-CODE');
  const saved = h.model.save(); await h.model.save(); h.model.change('displayName', 'Too late');
  assert.equal(h.calls.filter(c => c[1] === 'PUT').length, 1);
  const body = h.calls.at(-1)[2]; assert.equal(body.expectedRevision, 4); assert.equal(body.config.mailAccounts[0].displayName, 'Changed');
  assert.equal(body.config.mailAccounts[0].folderScope, 'selected');
  assert.doesNotMatch(JSON.stringify(body), /SYNTHETIC-CODE|statuses|persistentCredentialsSupported/);
  const result = snapshot(); result.config = plain(body.config); result.config.revision = 5; pending.resolve(result); await saved;
  assert.equal(h.model.snapshot.config.revision, 5); assert.equal(h.model.metadataDirty(), false);
});

test('revision conflicts preserve draft and suppress raw error/secret content', async () => {
  const h = setup((path, method) => method === 'PUT' ? Promise.reject({ code: 'revision_conflict', message: '<img> SYNTHETIC-CODE' }) : undefined);
  await h.model.load(); h.model.select('mail', 'mail-one'); h.model.change('displayName', 'Draft'); await h.model.save();
  assert.equal(h.model.entry().displayName, 'Draft'); assert.equal(h.model.dirty(), true);
  assert.equal(h.model.error, 'integrations.error.conflict'); assert.equal(h.model.busy, '');
});

test('mail hidden authorization and storage fields cannot be edited; body remains independently opt-in', async () => {
  const h = setup(); await h.model.load(); h.model.select('mail', 'mail-one');
  h.model.change('enabled', true); h.model.change('disclosureVersion', 1); h.model.change('persist', true); h.model.setPersist(true);
  await h.model.save(); assert.equal(h.calls.filter(call => !call[0].startsWith('/mail/folders')).length, 1); assert.equal(h.model.metadataDirty(), false);
  assert.equal(h.model.entry().enabled, false); assert.equal(h.model.entry().disclosureVersion, 0); assert.equal(h.model.persist, false);
  assert.equal(h.model.entry().bodyAccess, false); h.model.change('bodyAccess', true); await h.model.save();
  assert.equal(h.calls.at(-1)[1], 'PUT'); assert.equal(h.calls.at(-1)[2].config.mailAccounts[0].disclosureVersion, 0);
});

test('MCP credential remains write-only, session-only on unsupported vault, and cleared even on failure', async () => {
  const pending = deferred(); const h = setup(path => path === '/credential' ? pending.promise : Promise.resolve(mcpSnapshot()));
  await h.model.load(); h.model.select('mcp', 'mcp-one'); h.model.setSecret('SYNTHETIC-CODE'); h.model.setPersist(true);
  const saved = h.model.action('credential'); assert.equal(h.model.secret, ''); assert.equal(h.model.persist, false);
  assert.equal(h.calls.at(-1)[2].persist, false); assert.equal(h.calls.at(-1)[2].secret, 'SYNTHETIC-CODE');
  await h.model.action('credential'); assert.equal(h.calls.filter(c => c[0] === '/credential').length, 1);
  pending.reject({ code: 'authentication_failed' }); await saved;
  assert.equal(h.model.secret, ''); assert.equal(h.calls.at(-1)[2].secret, undefined);
  assert.equal(h.model.error, 'integrations.error.authentication');
});

test('account edits clear secret input but leave metadata-owned refs for server validation; cancel, selection and destroy clear secrets', async () => {
  const h = setup(); await h.model.load(); h.model.select('mail', 'mail-one'); h.model.setSecret('synthetic');
  h.model.change('email', 'other@qq.com'); assert.equal(h.model.secret, '');
  assert.equal(h.model.entry().credentialRef, snapshot().config.mailAccounts[0].credentialRef);
  h.model.setSecret('synthetic'); h.model.cancel(); assert.equal(h.model.secret, '');
  h.model.setSecret('synthetic'); h.model.select('mail', 'mail-one'); assert.equal(h.model.secret, '');
  h.model.setSecret('synthetic'); h.model.destroy(); assert.equal(h.model.secret, ''); assert.equal(h.model.draft, null);
});

test('cancel and close abort tests; late results never update a newer selection', async () => {
  for (const action of ['cancel', 'destroy']) {
    const pending = deferred(); const h = setup(path => path === '/test' ? pending.promise : undefined);
    await h.model.load(); h.model.select('mail', 'mail-one'); const tested = h.model.action('test');
    const signal = h.calls.at(-1)[3]; h.model[action](); assert.equal(signal.aborted, true);
    pending.resolve({ ok: true }); await tested; assert.equal(h.model.diagnostic, null); assert.equal(h.model.notice, '');
  }
});

test('late loads cannot restore a closed draft; runtime refresh does not overwrite unsaved fields', async () => {
  const pending = deferred(); let delayed = false;
  const h = setup(() => delayed ? pending.promise : undefined); await h.model.load(); h.model.select('mail', 'mail-one');
  h.model.change('displayName', 'Keep this'); delayed = true; const refresh = h.model.refresh();
  const live = snapshot(); live.config.revision = 7; live.statuses = { 'mail-one': { state: 'ready', credentialConfigured: true } };
  pending.resolve(live); await refresh;
  assert.equal(h.model.entry().displayName, 'Keep this'); assert.equal(h.model.snapshot.config.revision, 4);
  assert.equal(h.model.snapshot.statuses['mail-one'].state, 'ready');
  const closed = deferred(); const other = setup(() => closed.promise); const loading = other.model.load(); other.model.destroy();
  closed.resolve(snapshot()); await loading; assert.equal(other.model.snapshot, null);
});

test('metadata edits invalidate old status replies and require save before network diagnostics', async () => {
  const pending = deferred(); let delayed = false; const h = setup(() => delayed ? pending.promise : undefined);
  await h.model.load(); h.model.select('mail', 'mail-one'); delayed = true; const refresh = h.model.refresh();
  h.model.change('displayName', 'Draft'); await h.model.action('test'); await h.model.action('discover');
  assert.equal(h.calls.filter(call => !call[0].startsWith('/mail/folders')).length, 2); pending.resolve(snapshot()); await refresh; assert.equal(h.model.entry().displayName, 'Draft');
});

test('connections is lazy, preserves default model entry, and protects draft across cancel/switch/close/reopen', async () => {
  let approved = false, destroyed = 0, mounts = 0, llmMounts = 0, busy = false;
  const state = { configOpen: false, configSaving: false, configDirty: false,
    dom: { configModal: { dataset: {}, classList: { add() {}, remove() {} } }, configGrid: { classList: { toggle() {} } } } };
  const context = vm.createContext({ state, document: { getElementById: () => null }, window: { confirm: () => approved }, t: key => key,
    mountLlmSettings: () => { llmMounts++; return { dirty: () => false, destroy() {} }; },
    mountIntegrations: () => { mounts++; return { dirty: () => true, saving: () => busy, destroy: () => destroyed++ }; } });
  vm.runInContext(ui, context); await context.openConfigModal(); assert.equal(llmMounts, 1); assert.equal(mounts, 0);
  await context.switchConfigView('integrations'); assert.equal(mounts, 1);
  await context.switchConfigView('llm'); context.closeConfigModal(); assert.equal(destroyed, 0); assert.equal(state.configOpen, true);
  approved = true; busy = true; await context.switchConfigView('llm'); context.closeConfigModal(); assert.equal(destroyed, 0);
  busy = false; await context.switchConfigView('llm'); assert.equal(destroyed, 1);
  await context.switchConfigView('integrations'); context.closeConfigModal(); assert.equal(destroyed, 2);
  await context.openConfigModal(); assert.equal(state.configView, 'llm'); assert.equal(llmMounts, 3);
});

test('new views never use HTML injection or browser persistence; catalogs cover literal keys and mobile layout', () => {
  const approvals = fs.readFileSync(new URL('integration-approvals.js', base), 'utf8');
  assert.doesNotMatch(source + approvals, /innerHTML|outerHTML|insertAdjacentHTML|localStorage|sessionStorage/);
  assert.match(source, /\.textContent\s*=/);
  const en = JSON.parse(fs.readFileSync(new URL('locales/en.json', base)));
  for (const m of (source + approvals).matchAll(/t\("(integrations\.[^"]+)"\)/g)) assert.ok(en[m[1]], m[1]);
  const html = fs.readFileSync(new URL('index.html', base), 'utf8');
  assert.ok(html.indexOf('id="integration-chat-approvals"') < html.indexOf('id="chat-thread"'));
  assert.match(html, /integrations\.css/); assert.match(fs.readFileSync(new URL('integrations.css', base), 'utf8'), /max-width: 600px/);
});

test('legacy source is read-only in metadata but supports discovery and version-bound tool approval', async () => {
  const h = setup(path => path === '/discover' ? Promise.resolve({ tools: [{ name: '<img>', digest: 'd'.repeat(64), inputSchema: {} }] }) : undefined);
  await h.model.load(); h.model.select('legacy', 'legacy-websearch'); h.model.change('endpoint', 'https://other.example/mcp');
  assert.equal(h.model.metadataDirty(), false); await h.model.action('disconnect'); assert.equal(h.calls.filter(call => !call[0].startsWith('/mail/folders')).length, 1);
  await h.model.action('discover'); assert.deepEqual(plain(h.calls.at(-1)[2]), { serverId: 'legacy-websearch' });
  assert.equal(h.model.tools[0].name, '<img>'); await h.model.action('tools/approve', h.model.tools[0]);
  const call = h.calls.find(c => c[0] === '/tools/approve');
  assert.deepEqual(plain(call[2]), { serverId: 'legacy-websearch', toolName: '<img>', digest: 'd'.repeat(64), expectedRevision: 4 });
});

test('revocation only removes the selected tool approval and needs an explicit metadata save', async () => {
  const data = snapshot(); data.config.toolApprovals = [
    { sourceId: 'legacy-websearch', toolName: 'one', definitionDigest: 'a'.repeat(64), approvedRevision: 4 },
    { sourceId: 'legacy-websearch', toolName: 'two', definitionDigest: 'b'.repeat(64), approvedRevision: 4 }
  ];
  const h = setup(() => Promise.resolve(data)); await h.model.load(); h.model.select('legacy', 'legacy-websearch');
  h.model.revoke({ name: 'one' }); assert.deepEqual(plain(h.model.draft.toolApprovals.map(v => v.toolName)), ['two']);
  assert.equal(h.model.metadataDirty(), true); assert.equal(h.calls.filter(call => !call[0].startsWith('/mail/folders')).length, 1); await h.model.save();
  assert.equal(h.calls.at(-1)[1], 'PUT'); assert.deepEqual(plain(h.calls.at(-1)[2].config.toolApprovals.map(v => v.toolName)), ['two']);
});

test('optional mailbox display name defaults to email without adding a required onboarding field', async () => {
  const h = setup(); await h.model.load(); h.model.add('mail'); h.model.change('email', 'synthetic@qq.com'); await h.model.save();
  assert.equal(h.calls.at(-1)[2].config.mailAccounts.at(-1).displayName, 'synthetic@qq.com');
});

test('controller snapshot response means a successful test and serves the discovered status catalog', async () => {
  const data = snapshot(); data.statuses['mail-one'] = { state: 'tested', credentialConfigured: true, tools: [] };
  const h = setup(() => Promise.resolve(data)); await h.model.load(); h.model.select('mail', 'mail-one');
  await h.model.action('test'); assert.equal(h.model.diagnostic.ok, true); assert.equal(h.model.notice, 'tested');
  assert.equal(h.model.snapshot.statuses['mail-one'].state, 'tested');
});

test('integration API accepts categorized error codes without exposing raw response details', async () => {
  const apiSource = fs.readFileSync(new URL('api.js', base), 'utf8');
  const context = vm.createContext({ window: { location: { origin: 'http://localhost:5700' } },
    fetch: () => Promise.resolve({ ok: false, status: 403, json: () => Promise.resolve({ error: 'integration_authorization_required', detail: 'SYNTHETIC SECRET' }) }) });
  vm.runInContext(apiSource, context);
  await assert.rejects(context.api.integrations('/test', 'POST', { kind: 'mail', id: 'mail-one' }), error => {
    assert.equal(error.code, 'integration_authorization_required'); assert.equal(error.message, 'Integration request failed');
    assert.equal(error.body, undefined); return true;
  });
});

test('committed disconnect reports a pending operating-system credential cleanup instead of false success', async () => {
  const result = snapshot(); result.credentialCleanupPending = true;
  const h = setup(path => path === '/disconnect' ? Promise.resolve(result) : undefined);
  await h.model.load(); h.model.select('mail', 'mail-one'); await h.model.action('disconnect');
  assert.equal(h.model.notice, 'cleanupPending'); assert.equal(h.model.snapshot.credentialCleanupPending, true);
});


test('provider errors keep distinct safe localized diagnoses', () => {
  const { context } = setup();
  for (const [code, category] of Object.entries({ tls_failure: 'tls', provider_restricted: 'unsafeLogin', authentication_required: 'authentication' }))
    assert.equal(context.integrationErrorText({ code, message: 'SYNTHETIC-SECRET' }), `integrations.error.${category}`);
});

test('snapshots without explicit mailbox scope are rejected instead of migrated', async () => {
  const original = snapshot(); delete original.config.mailAccounts[0].folderScope;
  const h = setup(() => Promise.resolve(original)); await h.model.load();
  assert.equal(h.model.snapshot, null); assert.equal(h.model.draft, null);
  assert.equal(h.model.error, 'integrations.error.unavailable');
});

test('repeated folder scope changes preserve exact selected names and independent authorizations until saved', async () => {
  const original = snapshot(); Object.assign(original.config.mailAccounts[0], {
    folderScope: 'selected', allowedFolders: ['INBOX', 'Projects/2026', '*'], enabled: true, bodyAccess: true, disclosureVersion: 1
  });
  const h = setup(() => Promise.resolve(plain(original))); await h.model.load(); h.model.select('mail', 'mail-one');
  for (let i = 0; i < 3; i++) {
    h.model.change('folderScope', 'all'); assert.equal(h.model.metadataDirty(), true);
    assert.equal(h.model.snapshot.config.mailAccounts[0].folderScope, 'selected');
    assert.deepEqual(plain(h.model.entry().allowedFolders), original.config.mailAccounts[0].allowedFolders);
    h.model.change('folderScope', 'selected'); assert.equal(h.model.metadataDirty(), false);
  }
  assert.equal(h.model.entry().enabled, true); assert.equal(h.model.entry().bodyAccess, true);
  assert.equal(h.model.entry().disclosureVersion, 1); assert.equal(h.calls.filter(call => !call[0].startsWith('/mail/folders')).length, 1);
});

test('all scope can save an empty backup list; rejected selected scope keeps runtime scope and draft separate', async () => {
  let saved = snapshot(); saved.config.mailAccounts[0].folderScope = 'all'; saved.config.mailAccounts[0].allowedFolders = [];
  const h = setup((path, method, body) => {
    if (method === 'PUT') {
      if (body.config.mailAccounts[0].folderScope === 'selected') return Promise.reject({ code: 'invalid_config' });
      saved = { ...saved, config: plain(body.config) }; saved.config.revision++;
    }
    return Promise.resolve(plain(saved));
  });
  await h.model.load(); h.model.select('mail', 'mail-one'); h.model.change('displayName', 'All folders'); await h.model.save();
  assert.equal(h.calls.at(-1)[2].config.mailAccounts[0].folderScope, 'all');
  assert.deepEqual(plain(h.calls.at(-1)[2].config.mailAccounts[0].allowedFolders), []);
  h.model.change('folderScope', 'selected'); await h.model.save();
  assert.equal(h.model.error, 'integrations.error.folderSelection'); assert.equal(h.model.dirty(), true);
  assert.equal(h.model.entry().folderScope, 'selected'); assert.equal(h.model.snapshot.config.mailAccounts[0].folderScope, 'all');
  assert.deepEqual(plain(h.model.entry().allowedFolders), []); assert.equal(h.model.entry().bodyAccess, false);
});

test('failed widening preserves the selected runtime scope and can be discarded or retried explicitly', async () => {
  let rejectSave = true; const saved = snapshot(); saved.config.mailAccounts[0].folderScope = 'selected';
  const h = setup((path, method, body) => {
    if (method === 'PUT') {
      if (rejectSave) return Promise.reject({ code: 'revision_conflict' });
      saved.config = plain(body.config); saved.config.revision++;
    }
    return Promise.resolve(plain(saved));
  });
  await h.model.load(); h.model.select('mail', 'mail-one'); h.model.change('folderScope', 'all'); await h.model.save();
  assert.equal(h.model.entry().folderScope, 'all'); assert.equal(h.model.snapshot.config.mailAccounts[0].folderScope, 'selected');
  assert.equal(h.model.error, 'integrations.error.conflict'); assert.equal(h.model.dirty(), true);
  await h.model.load(); h.model.select('mail', 'mail-one'); assert.equal(h.model.entry().folderScope, 'selected');
  assert.equal(h.model.dirty(), false); h.model.change('folderScope', 'all'); rejectSave = false; await h.model.save();
  assert.equal(h.model.entry().folderScope, 'all'); assert.equal(h.model.metadataDirty(), false);
  h.model.change('folderScope', 'selected'); await h.model.save();
  assert.equal(h.model.snapshot.config.mailAccounts[0].folderScope, 'selected');
  assert.deepEqual(plain(h.model.entry().allowedFolders), ['INBOX']);
});

test('busy save locks folder edits and duplicate actions; late saves cannot restore a destroyed view', async () => {
  const pending = deferred(); const h = setup((path, method) => method === 'PUT' ? pending.promise : undefined);
  await h.model.load(); h.model.select('mail', 'mail-one'); h.model.change('folderScope', 'all');
  const saving = h.model.save(); h.model.change('folderScope', 'selected'); h.model.change('allowedFolders', []);
  h.model.cancel(); await h.model.load(); await h.model.save(); await h.model.action('test');
  assert.equal(h.model.busy, 'save'); assert.equal(h.model.entry().folderScope, 'all');
  assert.deepEqual(plain(h.model.entry().allowedFolders), ['INBOX']);
  assert.deepEqual(h.calls.map(call => call[1]), ['GET', 'PUT']);
  const saved = snapshot(); saved.config = plain(h.calls.at(-1)[2].config); h.model.destroy();
  pending.resolve(saved); await saving; assert.equal(h.model.draft, null); assert.equal(h.model.snapshot, null);
});

test('late refresh and cancelled diagnostic replies cannot undo a newer folder scope', async () => {
  const pending = deferred(); let delayed = false;
  const h = setup(() => delayed ? pending.promise : undefined);
  await h.model.load(); h.model.select('mail', 'mail-one'); delayed = true;
  const refresh = h.model.refresh(); h.model.change('folderScope', 'all'); pending.resolve(snapshot()); await refresh;
  assert.equal(h.model.entry().folderScope, 'all'); assert.equal(h.model.dirty(), true);
  const tested = deferred(); const other = setup(path => path === '/test' ? tested.promise : undefined);
  await other.model.load(); other.model.select('mail', 'mail-one'); const testing = other.model.action('test');
  other.model.cancel(); other.model.change('folderScope', 'all'); tested.resolve(snapshot()); await testing;
  assert.equal(other.model.entry().folderScope, 'all'); assert.equal(other.model.diagnostic, null);
  assert.equal(other.model.notice, ''); assert.equal(other.model.busy, '');
});

function dom() {
  const document = { activeElement: null };
  class Element {
    constructor(tag) {
      this.tagName = tag.toUpperCase(); this.children = []; this.attributes = {}; this.events = {};
      this.classList = {add: () => {}};
      this.type = this.tagName === 'INPUT' ? 'text' : ''; this.value = ''; this.disabled = false; this.checked = false; this.textContent = '';
    }
    appendChild(child) { this.children.push(child); return child; }
    replaceChildren(...children) { this.children = children; }
    setAttribute(key, value) { this.attributes[key] = value; }
    getAttribute(key) { return this.attributes[key] ?? null; }
    addEventListener(key, handler) { this.events[key] = handler; }
    querySelectorAll(selector) {
      const matches = child => selector.startsWith('#') ? child.id === selector.slice(1) : child.tagName === selector.toUpperCase();
      return this.children.flatMap(child => [...(matches(child) ? [child] : []), ...child.querySelectorAll(selector)]);
    }
    querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
    focus() { document.activeElement = this; }
    fire(type) { if (!this.disabled) this.events[type]?.({ target: this }); }
  }
  document.createElement = tag => new Element(tag);
  return { document, Element };
}
const tick = async () => { for (let i = 0; i < 8; i++) await Promise.resolve(); };
const allText = node => node.textContent + node.children.map(allText).join('');
async function setupView(handler, locale) {
  const fake = dom(); const root = new fake.Element('section'); const h = setup(handler);
  h.context.createIntegrationToolsDialog = () => ({open(){},close(){},update(){},destroy(){}});
  h.context.document = fake.document; h.context.api = { integrations: h.request };
  h.approveDiscard = false; h.confirmations = 0; h.reviews = [];
  h.context.window = { confirm: review => { h.confirmations++; h.reviews.push(review); return h.approveDiscard; } };
  if (locale) h.context.t = key => locale[key] || key;
  const create = h.context.createIntegrationsModel;
  h.context.createIntegrationsModel = (...args) => { h.model = create(...args); return h.model; };
  h.view = h.context.mountIntegrations(root); await tick();
  h.root = root; h.document = fake.document;
  h.field = name => root.querySelector(`#integration-${name}`);
  h.button = label => root.querySelectorAll('button').find(button => button.textContent === label);
  h.change = (name, value) => {
    const control = h.field(name);
    if (control.type === 'checkbox') control.checked = value; else control.value = value;
    control.fire(control.type === 'checkbox' || control.tagName === 'SELECT' ? 'change' : 'input');
  };
  return h;
}

test('folder picker replaces raw names, defaults new mail to all, and preserves selected names across toggles', async () => {
  const h = await setupView(); h.button('Test').fire('click');
  assert.equal(h.field('folderScope').value, 'selected'); assert.equal(h.field('allowedFolders'), null);
  assert.equal(h.view.dirty(), false); assert.equal(h.button('config.saveChanges').disabled, true);
  h.model.change('allowedFolders', ['INBOX', 'Work/Nested', '*']);
  for (let i = 0; i < 3; i++) {
    h.change('folderScope', 'all'); assert.equal(h.field('folder-scope-hint').textContent, 'integrations.folderScope.allHint');
    h.change('folderScope', 'selected'); assert.deepEqual(plain(h.model.entry().allowedFolders), ['INBOX', 'Work/Nested', '*']);
  }
  assert.equal(h.field('folderScope').attributes['aria-describedby'], 'integration-folder-scope-hint');
  h.button('integrations.add.mail').fire('click'); assert.equal(h.field('folderScope').value, 'all');
  assert.equal(h.field('bodyAccess').checked, false);
  for (const name of ['disclosureVersion', 'enabled', 'persist', 'allowedFolders']) assert.equal(h.field(name), null);
  h.view.destroy();
});

test('cancelled discard preserves all-folder draft; confirmed discard and reopening restore saved explicit scope', async () => {
  const h = await setupView(); h.button('Test').fire('click'); h.change('folderScope', 'all');
  h.button('config.discard').fire('click'); await tick();
  assert.equal(h.confirmations, 1); assert.equal(h.field('folderScope').value, 'all'); assert.equal(h.view.dirty(), true);
  h.approveDiscard = true; h.button('config.discard').fire('click'); await tick(); h.button('Test').fire('click');
  assert.equal(h.field('folderScope').value, 'selected'); assert.equal(h.field('allowedFolders'), null);
  assert.equal(h.view.dirty(), false); h.change('folderScope', 'all'); h.view.destroy();
  assert.equal(h.root.children.length, 0); h.view = h.context.mountIntegrations(h.root); await tick(); h.button('Test').fire('click');
  assert.equal(h.field('folderScope').value, 'selected'); assert.equal(h.view.dirty(), false); h.view.destroy();
});

test('folder controls are disabled during save and rejected widening remains a recoverable draft', async () => {
  const pending = deferred(); const h = await setupView((path, method) => method === 'PUT' ? pending.promise : undefined);
  h.button('Test').fire('click'); h.change('folderScope', 'all'); h.button('config.saveChanges').fire('click');
  assert.equal(h.field('folderScope').disabled, true); assert.equal(h.field('allowedFolders'), null);
  assert.equal(h.button('config.discard').disabled, true); assert.equal(h.button('config.saveChanges').disabled, true);
  h.button('config.saveChanges').fire('click'); assert.equal(h.calls.filter(call => call[1] === 'PUT').length, 1);
  pending.reject({ code: 'revision_conflict', message: '<script>secret</script>' }); await tick();
  assert.equal(h.field('folderScope').disabled, false); assert.equal(h.field('allowedFolders'), null);
  assert.equal(h.field('folderScope').value, 'all'); assert.equal(h.view.dirty(), true);
  assert.equal(h.document.activeElement.textContent, 'integrations.error.conflict');
  h.change('folderScope', 'selected'); assert.deepEqual(plain(h.model.entry().allowedFolders), ['INBOX']); h.view.destroy();
});

test('both localized folder scopes retain model, body and retention disclosures and render hostile names only as text', async () => {
  const hostile = '<img src=x onerror=alert(1)>';
  for (const language of ['en', 'zh']) {
    const locale = JSON.parse(fs.readFileSync(new URL(`locales/${language}.json`, base)));
    const saved = snapshot(); saved.config.mailAccounts[0].displayName = hostile;
    saved.config.mailAccounts[0].allowedFolders = ['INBOX', 'Work/<script>alert(1)</script>', '*'];
    saved.modelTarget = 'https://model.example/<svg/onload=alert(1)>';
    const h = await setupView(() => Promise.resolve(plain(saved)), locale); h.button(hostile).fire('click');
    for (const name of saved.config.mailAccounts[0].allowedFolders) assert.ok(allText(h.root).includes(name));
    h.change('folderScope', 'all');
    assert.ok(allText(h.root).includes(locale['integrations.folderScope.allHint']));
    for (const key of ['integrations.bodyAccess', 'integrations.mailRetention', 'integrations.mailDisclosure'])
      assert.ok(allText(h.root).includes(locale[key]), `${language}: ${key}`);
    assert.ok(allText(h.root).includes(saved.modelTarget));
    assert.equal(h.root.querySelectorAll('img').length, 0); assert.equal(h.root.querySelectorAll('script').length, 0);
    assert.equal(h.root.querySelectorAll('svg').length, 0); assert.equal(h.field('bodyAccess').checked, false);
    for (const key of ['integrations.folderScope', 'integrations.folderScope.all', 'integrations.folderScope.selected',
      'integrations.folderScope.allHint', 'integrations.folderScope.selectedHint']) assert.ok(locale[key], `${language}: ${key}`);
    if (language === 'en') {
      assert.match(locale['integrations.folderScope.allHint'], /current, future and nested/);
      assert.match(locale['integrations.folderScope.allHint'], /does not start automatic syncing.*all historical mail/);
    } else {
      assert.match(locale['integrations.folderScope.allHint'], /当前、以后新增及嵌套/);
      assert.match(locale['integrations.folderScope.allHint'], /不会自动同步.*全部历史邮件/);
    }
    h.view.destroy();
  }
});

function activationSnapshot({ stored = false, enabled = false, supported = true } = {}) {
  const data = snapshot(); data.persistentCredentialsSupported = supported;
  Object.assign(data.config.mailAccounts[0], { folderScope: 'all', enabled, disclosureVersion: enabled ? 1 : 0,
    credentialRef: stored ? 'windows:00000000-0000-0000-0000-000000000000' : null, storageMode: stored ? 'windows' : 'session' });
  data.statuses['mail-one'] = { state: 'not_connected', credentialConfigured: stored };
  return data;
}
function mcpSnapshot(supported = false) {
  const data = snapshot(); data.persistentCredentialsSupported = supported;
  data.config.mcpServers = [{ id: 'mcp-one', name: 'MCP', endpoint: 'https://mcp.example/api', authType: 'bearer', enabled: false,
    transport: 'streamable-http', credentialRef: null, storageMode: 'session', budget: {} }];
  data.statuses['mcp-one'] = { state: 'disabled', credentialConfigured: false };
  return data;
}

test('mail activation sends the confirmed revision and model target only to the dedicated endpoint and clears its code', async () => {
  const before = activationSnapshot(); const pending = deferred(); let submitted;
  const h = setup((path, method, body) => {
    if (path === '/mail/activate') { submitted = plain(body); return pending.promise; }
    return Promise.resolve(plain(before));
  });
  await h.model.load(); h.model.select('mail', 'mail-one'); h.model.setSecret('SYNTHETIC-AUTH-CODE');
  let review; const saving = h.model.activateMail(value => { review = value; return true; });
  assert.deepEqual(submitted, { id: 'mail-one', expectedRevision: 4, disclosureVersion: 1,
    modelTarget: before.modelTarget, secret: 'SYNTHETIC-AUTH-CODE' });
  assert.equal(h.model.secret, ''); assert.equal(h.model.persist, false); assert.equal(h.model.busy, 'mail/activate');
  assert.ok(review.includes(before.modelTarget)); assert.ok(review.includes('test@qq.com')); assert.doesNotMatch(review, /SYNTHETIC/);
  await h.model.activateMail(() => { assert.fail('second confirmation while busy'); });
  h.model.change('bodyAccess', true); h.model.select('legacy', 'legacy-websearch'); h.model.cancel(); await h.model.refresh();
  assert.equal(h.model.entry().bodyAccess, false); assert.equal(h.model.selected.id, 'mail-one'); assert.equal(h.calls.filter(call => !call[0].startsWith('/mail/folders')).length, 2);
  const after = activationSnapshot({ stored: true, enabled: true }); after.config.revision = 5;
  pending.resolve(after); await saving;
  assert.equal(h.model.notice, 'mailActivated'); assert.equal(h.model.entry().enabled, true); assert.equal(h.model.entry().storageMode, 'windows');
  assert.equal(h.model.diagnostic, null); assert.equal(h.model.busy, ''); assert.equal(h.calls[1][2].secret, undefined);
  assert.deepEqual(h.calls.map(call => [call[0], call[1]]), [['', 'GET'], ['/mail/activate', 'POST'], ['/mail/folders', 'POST']]);
});

test('cancelled or missing activation confirmation sends nothing and clears secret input', async () => {
  for (const confirm of [undefined, () => false, () => { throw Error('dialog unavailable'); }]) {
    const h = setup(() => Promise.resolve(activationSnapshot())); await h.model.load(); h.model.select('mail', 'mail-one');
    h.model.setSecret('SYNTHETIC-AUTH-CODE'); await h.model.activateMail(confirm);
    assert.equal(h.calls.filter(call => !call[0].startsWith('/mail/folders')).length, 1); assert.equal(h.model.secret, ''); assert.equal(h.model.busy, '');
    assert.equal(h.model.entry().enabled, false); assert.equal(h.model.dirty(), false);
  }
});

test('only readable persisted Windows credentials permit activation without a new code', async () => {
  for (const stored of [false, true]) {
    const data = activationSnapshot({ stored }); let confirmed = 0, submitted;
    const h = setup((path, method, body) => {
      if (path === '/mail/activate') {
        submitted = plain(body); const after = activationSnapshot({ stored: true, enabled: true }); after.config.revision = 5; return Promise.resolve(after);
      }
      return Promise.resolve(plain(data));
    });
    await h.model.load(); h.model.select('mail', 'mail-one');
    await h.model.activateMail(() => { confirmed++; return true; });
    if (stored) {
      assert.equal(confirmed, 1); assert.deepEqual(submitted, { id: 'mail-one', expectedRevision: 4, disclosureVersion: 1, modelTarget: data.modelTarget });
    } else { assert.equal(confirmed, 0); assert.equal(h.calls.filter(call => !call[0].startsWith('/mail/folders')).length, 1); assert.equal(h.model.error, 'integrations.error.mailCredential'); }
  }
  for (const storageMode of ['session', 'windows']) {
    const data = activationSnapshot({ stored: true }); data.config.mailAccounts[0].storageMode = storageMode;
    if (storageMode === 'windows') data.statuses['mail-one'].credentialConfigured = false;
    else data.config.mailAccounts[0].credentialRef = 'session:00000000-0000-0000-0000-000000000000';
    const h = setup(() => Promise.resolve(plain(data))); await h.model.load(); h.model.select('mail', 'mail-one');
    await h.model.activateMail(() => assert.fail('non-reusable credential must not be confirmed'));
    assert.equal(h.calls.filter(call => !call[0].startsWith('/mail/folders')).length, 1); assert.equal(h.model.error, 'integrations.error.mailCredential');
  }
});

test('unsupported persistent storage, unset model target and unsaved metadata cannot activate or fall back', async () => {
  for (const failure of ['storage', 'target', 'draft']) {
    const data = activationSnapshot({ supported: failure !== 'storage' }); if (failure === 'target') data.modelTarget = '';
    const h = setup(() => Promise.resolve(plain(data))); await h.model.load(); h.model.select('mail', 'mail-one');
    h.model.setSecret('SYNTHETIC-AUTH-CODE'); if (failure === 'draft') h.model.change('folderScope', 'selected');
    await h.model.activateMail(() => assert.fail('not ready to confirm')); await h.model.action('credential');
    assert.equal(h.calls.filter(call => !call[0].startsWith('/mail/folders')).length, 1); assert.equal(h.model.entry().enabled, false);
    if (failure !== 'draft') assert.equal(h.model.error, failure === 'storage' ? 'integrations.error.mailStorage' : 'integrations.error.modelTarget');
  }
});

test('activation confirmation stays bound to the saved revision and displayed model target', async () => {
  for (const field of ['revision', 'target']) {
    const h = setup(() => Promise.resolve(activationSnapshot({ stored: true })));
    await h.model.load(); h.model.select('mail', 'mail-one'); h.model.setSecret('SYNTHETIC-AUTH-CODE');
    await h.model.activateMail(() => {
      assert.equal(h.model.busy, 'mail/review'); h.model.change('bodyAccess', true); assert.equal(h.model.entry().bodyAccess, false);
      if (field === 'revision') h.model.snapshot.config.revision++;
      else h.model.snapshot.modelTarget = 'https://different-model.example/v1';
      return true;
    });
    assert.equal(h.calls.filter(call => !call[0].startsWith('/mail/folders')).length, 1); assert.equal(h.model.error, 'integrations.error.reviewRequired'); assert.equal(h.model.secret, '');
  }
});

test('activation failures retain saved metadata, clear codes and show localized errors without false success', async () => {
  const errors = { persistent_credentials_unavailable: 'mailStorage', credential_write_failed: 'mailStorage',
    credential_verification_failed: 'mailStorage', credential_read_failed: 'mailStorage', mail_credential_required: 'mailCredential',
    mail_activation_confirmation_required: 'reviewRequired', model_target_changed: 'reviewRequired', model_target_unavailable: 'modelTarget', revision_conflict: 'conflict' };
  for (const [code, category] of Object.entries(errors)) {
    const before = activationSnapshot({ stored: true, enabled: true });
    const h = setup(path => path === '/mail/activate' ? Promise.reject({ code, message: '<script>SYNTHETIC-AUTH-CODE</script>' }) : Promise.resolve(plain(before)));
    await h.model.load(); h.model.select('mail', 'mail-one'); h.model.setSecret('SYNTHETIC-AUTH-CODE');
    await h.model.activateMail(() => true);
    assert.deepEqual(plain(h.model.draft), before.config); assert.equal(h.model.error, `integrations.error.${category}`);
    assert.equal(h.model.notice, ''); assert.equal(h.model.secret, ''); assert.equal(h.model.busy, '');
    assert.doesNotMatch(JSON.stringify(h.model), /SYNTHETIC-AUTH-CODE|<script>/);
  }
});

test('failed activation reports pending credential cleanup safely, including synchronous request errors', async () => {
  const h = setup(path => { if (path === '/mail/activate') throw { code: 'credential_write_failed', credentialCleanupPending: true }; return Promise.resolve(activationSnapshot()); });
  await h.model.load(); h.model.select('mail', 'mail-one'); h.model.setSecret('SYNTHETIC-AUTH-CODE'); await h.model.activateMail(() => true);
  assert.equal(h.model.notice, 'cleanupPending'); assert.equal(h.model.error, 'integrations.error.mailStorage');
  assert.equal(h.model.secret, ''); assert.equal(h.model.busy, ''); assert.equal(h.model.entry().enabled, false);
});

test('late activation results cannot restore a closed or newly reopened view', async () => {
  const pending = deferred(); const h = await setupView(path => path === '/mail/activate' ? pending.promise : Promise.resolve(activationSnapshot()));
  h.button('Test').fire('click'); h.change('secret', 'SYNTHETIC-AUTH-CODE'); h.approveDiscard = true;
  h.button('integrations.action.activateMail').fire('click'); const old = h.model, signal = h.calls.at(-1)[3];
  assert.equal(h.view.saving(), true); h.view.destroy(); assert.equal(signal.aborted, true); assert.equal(h.root.children.length, 0);
  h.view = h.context.mountIntegrations(h.root); await tick(); h.button('Test').fire('click');
  pending.resolve(activationSnapshot({ stored: true, enabled: true })); await tick();
  assert.equal(old.snapshot, null); assert.equal(old.draft, null); assert.equal(h.model.entry().enabled, false);
  assert.equal(h.model.notice, ''); assert.equal(h.field('secret').value, ''); h.view.destroy();
});

test('mail metadata edits preserve hidden loaded state until the server resets permissions; display-name edits stay enabled', async () => {
  for (const [field, value] of Object.entries({ provider: '163', email: 'other@qq.com', folderScope: 'selected', allowedFolders: ['Other/Nested'], bodyAccess: true, displayName: 'Renamed' })) {
    const before = activationSnapshot({ stored: true, enabled: true }); let submitted;
    const h = setup((path, method, body) => {
      if (method !== 'PUT') return Promise.resolve(plain(before));
      submitted = plain(body.config.mailAccounts[0]); const after = plain(before); after.config = plain(body.config); after.config.revision++;
      if (field !== 'displayName') { after.config.mailAccounts[0].enabled = false; after.config.mailAccounts[0].disclosureVersion = 0; }
      return Promise.resolve(after);
    });
    await h.model.load(); h.model.select('mail', 'mail-one'); h.model.change(field, value); await h.model.save();
    assert.equal(submitted.enabled, true); assert.equal(submitted.disclosureVersion, 1);
    assert.equal(submitted.credentialRef, before.config.mailAccounts[0].credentialRef); assert.equal(submitted.storageMode, 'windows');
    assert.equal(h.model.entry().enabled, field === 'displayName'); assert.equal(h.model.entry().disclosureVersion, field === 'displayName' ? 1 : 0);
  }
});

test('mail has no equivalent authorization/storage toggles; MCP retains enabled/persist and credential action', async () => {
  const h = await setupView(() => Promise.resolve(mcpSnapshot(true))); h.button('Test').fire('click');
  const checkboxes = () => h.root.querySelectorAll('input').filter(input => input.type === 'checkbox' && !input.id.startsWith('integration-folder-choice-'));
  assert.deepEqual(checkboxes().map(input => input.id), ['integration-bodyAccess']);
  assert.equal(h.button('integrations.action.credential'), undefined); assert.ok(h.button('integrations.action.activateMail'));
  for (const name of ['enabled', 'disclosureVersion', 'persist']) assert.equal(h.field(name), null);
  h.button('MCP').fire('click'); assert.ok(h.field('enabled')); assert.ok(h.field('persist'));
  assert.deepEqual(checkboxes().map(input => input.id), ['integration-enabled', 'integration-persist']);
  h.change('enabled', true); assert.equal(h.model.entry().enabled, true);
  h.change('persist', true); assert.equal(h.model.persist, true);
  assert.ok(h.button('integrations.action.credential')); assert.equal(h.button('integrations.action.activateMail'), undefined);
  assert.ok(allText(h.root).includes('integrations.sessionOnly')); h.view.destroy();
});

test('mail activation requires secure storage and an explicit authorization code', async () => {
  for (const supported of [false, true]) {
    const data = snapshot(); data.persistentCredentialsSupported = supported;
    data.statuses['mail-one'] = { state: 'not_connected', credentialConfigured: true };
    const h = await setupView(() => Promise.resolve(plain(data))); h.button('Test').fire('click');
    assert.ok(!allText(h.root).includes('integrations.mailLegacySession'));
    assert.ok(!allText(h.root).includes('integrations.mailStoredCredential'));
    assert.ok(!allText(h.root).includes('integrations.sessionOnly'));
    assert.equal(h.button('integrations.action.activateMail').disabled, true);
    h.change('secret', 'SYNTHETIC-AUTH-CODE'); assert.equal(h.button('integrations.action.activateMail').disabled, !supported);
    if (!supported) assert.ok(allText(h.root).includes('integrations.error.mailStorage'));
    h.button('action.cancel').fire('click'); assert.equal(h.field('secret').value, ''); h.view.destroy();
  }
});

test('stored-mail activation uses a distinct action label without exposing a persistence choice', async () => {
  const h = await setupView(() => Promise.resolve(activationSnapshot({ stored: true, enabled: true }))); h.button('Test').fire('click');
  assert.equal(h.button('integrations.action.activateStoredMail').disabled, false);
  assert.ok(allText(h.root).includes('integrations.mailEnabled')); assert.ok(allText(h.root).includes('integrations.mailNotTested'));
  h.change('secret', 'SYNTHETIC-AUTH-CODE'); assert.ok(h.button('integrations.action.activateMail'));
  h.change('secret', ''); assert.ok(h.button('integrations.action.activateStoredMail'));
  assert.ok(h.button('integrations.action.disconnect')); h.view.destroy();
});

test('localized activation confirmation fully discloses saved scope, body, target, retention and secure storage as plain text', async () => {
  for (const lang of ['zh', 'en']) for (const scope of ['all', 'selected']) for (const bodyAccess of [false, true]) {
    const locale = JSON.parse(fs.readFileSync(new URL(`locales/${lang}.json`, base)));
    const data = activationSnapshot(); Object.assign(data.config.mailAccounts[0], { folderScope: scope, bodyAccess, allowedFolders: ['INBOX', 'Work/<script>alert(1)</script>', '*'] });
    data.modelTarget = 'https://model.example/<svg/onload=alert(1)>';
    const h = await setupView(() => Promise.resolve(plain(data)), locale); h.button('Test').fire('click'); h.change('secret', 'SYNTHETIC-AUTH-CODE');
    h.button(locale['integrations.action.activateMail']).fire('click'); await tick();
    assert.equal(h.confirmations, 1); assert.equal(h.calls.filter(call => !call[0].startsWith('/mail/folders')).length, 1); assert.equal(h.field('secret').value, '');
    const review = h.reviews[0];
    for (const text of ['test@qq.com', data.modelTarget, locale['integrations.mailDisclosure'], locale['integrations.mailPersistence'], locale['integrations.mailRetention'],
      locale['integrations.activation.effects'], locale[bodyAccess ? 'integrations.activation.bodyYes' : 'integrations.activation.bodyNo'],
      locale[scope === 'all' ? 'integrations.activation.allFolders' : 'integrations.activation.selectedFolders']]) assert.ok(review.includes(text), `${lang}/${scope}: ${text}`);
    if (scope === 'selected') for (const folder of data.config.mailAccounts[0].allowedFolders) assert.ok(review.includes(folder));
    assert.doesNotMatch(review, /SYNTHETIC-AUTH-CODE/); assert.equal(h.root.querySelectorAll('svg').length, 0); assert.equal(h.root.querySelectorAll('script').length, 0);
    h.view.destroy();
  }
});

test('model target refresh updates displayed and reviewed recipient even with a pending secret', async () => {
  let data = activationSnapshot(); const h = await setupView(() => Promise.resolve(plain(data)));
  h.button('Test').fire('click'); h.change('secret', 'SYNTHETIC-AUTH-CODE'); data.modelTarget = 'https://new-model.example/v1';
  await h.model.refresh(); assert.ok(h.field('model-target').textContent.includes(data.modelTarget));
  h.button('integrations.action.activateMail').fire('click'); assert.ok(h.reviews[0].includes(data.modelTarget)); h.view.destroy();
});

test('integration API retains review-required error code and safe cleanup flag without exposing raw payload', async () => {
  const apiSource = fs.readFileSync(new URL('api.js', base), 'utf8');
  const context = vm.createContext({ window: { location: { origin: 'http://localhost:5700' } },
    fetch: () => Promise.resolve({ ok: false, status: 409, json: () => Promise.resolve({ errorCode: 'model_target_changed', credentialCleanupPending: true,
      secret: 'SYNTHETIC-AUTH-CODE', detail: 'RAW DETAIL' }) }) });
  vm.runInContext(apiSource, context);
  await assert.rejects(context.api.integrations('/mail/activate', 'POST', {}), error => {
    assert.equal(error.code, 'model_target_changed'); assert.equal(error.credentialCleanupPending, true);
    assert.equal(error.status, 409); assert.equal(error.message, 'Integration request failed');
    assert.doesNotMatch(JSON.stringify(error), /SYNTHETIC|RAW DETAIL/); return true;
  });
});

test('activation success with pending cleanup still distinguishes enabled from connection-tested', async () => {
  const after = activationSnapshot({ stored: true, enabled: true }); after.config.revision = 5; after.credentialCleanupPending = true;
  const h = await setupView(path => Promise.resolve(path === '/mail/activate' ? plain(after) : activationSnapshot()));
  h.button('Test').fire('click'); h.change('secret', 'SYNTHETIC-AUTH-CODE'); h.approveDiscard = true;
  h.button('integrations.action.activateMail').fire('click'); await tick();
  assert.equal(h.model.notice, 'mailActivated'); assert.ok(allText(h.root).includes('integrations.notice.mailActivated'));
  assert.ok(allText(h.root).includes('integrations.notice.cleanupPending')); assert.equal(h.model.diagnostic, null); h.view.destroy();
});

test('status-only MCP credential responses still refresh public configuration while request is locked', async () => {
  const data = mcpSnapshot(true); let get = 0, submitted;
  const h = setup((path, method, body) => {
    if (path === '/credential') { submitted = plain(body); return Promise.resolve({ ok: true }); }
    get++; return Promise.resolve(plain(data));
  });
  await h.model.load(); h.model.select('mcp', 'mcp-one'); h.model.setSecret('SYNTHETIC-MCP-CODE'); h.model.setPersist(true);
  await h.model.action('credential'); assert.equal(get, 2);
  assert.deepEqual(submitted, { kind: 'mcp', id: 'mcp-one', secret: 'SYNTHETIC-MCP-CODE', persist: true, expectedRevision: 4 });
  assert.equal(h.model.secret, ''); assert.equal(h.model.busy, '');
});

test('missing model target explains why mail activation is disabled before any request', async () => {
  const data = activationSnapshot({ stored: true }); data.modelTarget = '';
  const h = await setupView(() => Promise.resolve(plain(data))); h.button('Test').fire('click');
  assert.equal(h.button('integrations.action.activateStoredMail').disabled, true);
  assert.ok(allText(h.root).includes('integrations.error.modelTarget')); assert.equal(h.calls.filter(call => !call[0].startsWith('/mail/folders')).length, 1); h.view.destroy();
});

test('uncertain activation never retries or claims failure and requires a fresh saved snapshot', async () => {
  for (const code of ['mail_activation_outcome_uncertain']) {
    let data = activationSnapshot({ stored: true }); let confirmed = 0;
    const h = setup(path => path === '/mail/activate' ? Promise.reject({ code }) : Promise.resolve(plain(data)));
    await h.model.load(); h.model.select('mail', 'mail-one'); h.model.setSecret('SYNTHETIC-AUTH-CODE');
    await h.model.activateMail(() => { confirmed++; return true; });
    assert.equal(h.model.error, 'integrations.error.mailOutcomeUncertain'); assert.equal(h.model.notice, '');
    assert.equal(h.model.activationNeedsRefresh, true); assert.equal(h.model.secret, ''); assert.equal(h.calls.filter(call => !call[0].startsWith('/mail/folders')).length, 2);
    await h.model.activateMail(() => { confirmed++; return true; }); assert.equal(confirmed, 1); assert.equal(h.calls.filter(call => !call[0].startsWith('/mail/folders')).length, 2);
    data = activationSnapshot({ stored: true, enabled: true }); data.config.revision = 5;
    await h.model.refresh(); assert.equal(h.calls.filter(call => !call[0].startsWith('/mail/folders')).length, 3); assert.equal(h.model.activationNeedsRefresh, false);
    assert.equal(h.model.entry().enabled, true); assert.equal(h.model.snapshot.config.revision, 5); assert.equal(h.model.error, '');
  }
});

test('uncertain activation disables repeat UI action until the user explicitly reloads', async () => {
  const h = await setupView(path => path === '/mail/activate' ? Promise.reject({ code: 'mail_activation_outcome_uncertain' })
    : Promise.resolve(activationSnapshot({ stored: true })));
  h.button('Test').fire('click'); h.approveDiscard = true; h.button('integrations.action.activateStoredMail').fire('click'); await tick();
  assert.equal(h.button('integrations.action.activateStoredMail').disabled, true);
  assert.ok(allText(h.root).includes('integrations.error.mailOutcomeUncertain')); assert.equal(h.calls.filter(call => !call[0].startsWith('/mail/folders')).length, 2);
  h.button('integrations.action.activateStoredMail').fire('click'); await tick(); assert.equal(h.calls.filter(call => !call[0].startsWith('/mail/folders')).length, 2);
  assert.equal(h.button('integrations.action.refreshMail').disabled, false); assert.equal(h.field('secret').disabled, true);
  h.change('secret', 'SYNTHETIC-NOT-ACCEPTED'); h.change('bodyAccess', true); assert.equal(h.model.secret, ''); assert.equal(h.model.entry().bodyAccess, false);
  h.button('integrations.action.refreshMail').fire('click'); await tick();
  assert.equal(h.button('integrations.action.activateStoredMail').disabled, false); assert.equal(h.model.activationNeedsRefresh, false);
  assert.equal(h.field('secret').disabled, false); assert.equal(h.model.error, ''); assert.equal(h.calls.filter(call => call[0] === '/mail/activate').length, 1); h.view.destroy();
});

test('MCP storage errors keep generic session-capable guidance rather than mail activation messages', async () => {
  for (const code of ['storage_unavailable', 'persistent_credentials_unavailable', 'credential_write_failed', 'credential_verification_failed', 'credential_read_failed']) {
    const h = setup(path => path === '/credential' ? Promise.reject({ code }) : Promise.resolve(mcpSnapshot(true)));
    await h.model.load(); h.model.select('mcp', 'mcp-one'); h.model.setSecret('SYNTHETIC-MCP-CODE'); h.model.setPersist(true);
    await h.model.action('credential'); assert.equal(h.model.error, 'integrations.error.storage'); assert.equal(h.model.secret, '');
    assert.equal(h.context.integrationErrorText({ code }, true), 'integrations.error.mailStorage');
    assert.equal(h.context.integrationErrorText({ code }), 'integrations.error.storage');
  }
});

function committedActivationSnapshot() {
  const data = activationSnapshot({ stored: true, enabled: true }); data.config.revision = 5; return data;
}

test('committed activation with lost response is unknown and never retried until explicit validated GET', async () => {
  let persisted = activationSnapshot(), posts = 0;
  const h = setup(path => {
    if (path === '/mail/activate') { posts++; persisted = committedActivationSnapshot(); return Promise.reject(new TypeError('Failed to fetch SYNTHETIC-AUTH-CODE')); }
    return Promise.resolve(plain(persisted));
  });
  await h.model.load(); h.model.select('mail', 'mail-one'); const before = plain(h.model.snapshot);
  h.model.setSecret('SYNTHETIC-AUTH-CODE'); await h.model.activateMail(() => true);
  assert.equal(persisted.config.mailAccounts[0].enabled, true); assert.deepEqual(plain(h.model.snapshot), before);
  assert.deepEqual(plain(h.model.draft), before.config); assert.equal(h.model.activationNeedsRefresh, true);
  assert.equal(h.model.error, 'integrations.error.mailOutcomeUncertain'); assert.equal(h.model.notice, ''); assert.equal(h.model.secret, '');
  await h.model.activateMail(() => assert.fail('must not reconfirm before refresh')); await h.model.action('disconnect');
  h.model.change('email', 'other@qq.com'); assert.equal(h.model.entry().email, 'test@qq.com'); assert.equal(posts, 1); assert.equal(h.calls.filter(call => !call[0].startsWith('/mail/folders')).length, 2);
  await h.model.refresh(); assert.equal(h.model.entry().enabled, true); assert.equal(h.model.activationNeedsRefresh, false);
  assert.equal(h.model.snapshot.config.revision, 5); assert.equal(h.model.error, ''); assert.equal(posts, 1);
  assert.doesNotMatch(JSON.stringify(h.model), /SYNTHETIC-AUTH-CODE|Failed to fetch/);
});

test('timeouts, generic server errors and unrecognized activation errors require refresh rather than asserting failure', async () => {
  const failures = [new TypeError('Network lost'), Object.assign(new Error('timed out'), { name: 'TimeoutError' }),
    { name: 'AbortError' }, { code: 'timeout' }, { status: 408, code: 'timeout' }, { status: 500, code: 'credential_write_failed' },
    { status: 502, code: 'unavailable' }, { status: 400, code: 'integration_operation_failed' }, { status: 409, code: 'unknown_error' }, {}];
  for (const failure of failures) {
    const h = setup(path => path === '/mail/activate' ? Promise.reject(failure) : Promise.resolve(activationSnapshot()));
    await h.model.load(); h.model.select('mail', 'mail-one'); const before = plain(h.model.snapshot);
    h.model.setSecret('SYNTHETIC-AUTH-CODE'); await h.model.activateMail(() => true);
    assert.equal(h.model.activationNeedsRefresh, true); assert.equal(h.model.error, 'integrations.error.mailOutcomeUncertain');
    assert.deepEqual(plain(h.model.snapshot), before); assert.deepEqual(plain(h.model.draft), before.config);
    assert.equal(h.model.secret, ''); assert.equal(h.model.busy, ''); assert.equal(h.model.notice, ''); assert.equal(h.calls.filter(call => !call[0].startsWith('/mail/folders')).length, 2);
  }
});

test('unverifiable activation successes never replace any part of the last complete snapshot', async () => {
  const changes = [
    () => ({}), () => null, () => [], () => 'ok', data => { delete data.config; }, data => { data.config = {}; },
    data => { data.config.revision = 4; }, data => { data.config.revision = Number.MAX_SAFE_INTEGER + 1; },
    data => { data.config.schemaVersion = 2; }, data => { data.config.mailAccounts = []; },
    data => { data.config.mailAccounts.push(plain(data.config.mailAccounts[0])); }, data => { data.config.mcpServers = null; },
    data => { data.config.toolApprovals = [{}]; }, data => { data.statuses = []; }, data => { data.statuses['mail-one'] = null; },
    data => { data.statuses['mail-one'].credentialConfigured = false; }, data => { data.config.mailAccounts[0].enabled = false; },
    data => { data.config.mailAccounts[0].disclosureVersion = 0; }, data => { data.config.mailAccounts[0].storageMode = 'session'; },
    data => { data.config.mailAccounts[0].credentialRef = null; }, data => { data.config.mailAccounts[0].allowedFolders = 'INBOX'; },
    data => { data.config.mailAccounts[0].allowedFolders = ['Other']; }, data => { data.config.mailAccounts[0].folderScope = 'selected'; },
    data => { data.config.mailAccounts[0].bodyAccess = true; }, data => { data.config.mailAccounts[0].email = 'other@qq.com'; },
    data => { data.modelTarget = 'https://other.example/v1'; }, data => { data.persistentCredentialsSupported = false; },
    data => { data.credentialCleanupPending = 'true'; }
  ];
  for (const mutate of changes) {
    let result = committedActivationSnapshot(); const changed = mutate(result); if (changed !== undefined) result = changed;
    const h = setup(path => path === '/mail/activate' ? Promise.resolve(result) : Promise.resolve(activationSnapshot()));
    await h.model.load(); h.model.select('mail', 'mail-one'); const before = plain(h.model.snapshot);
    h.model.setSecret('SYNTHETIC-AUTH-CODE'); await h.model.activateMail(() => true);
    assert.equal(h.model.activationNeedsRefresh, true, String(mutate)); assert.equal(h.model.error, 'integrations.error.mailOutcomeUncertain');
    assert.deepEqual(plain(h.model.snapshot), before); assert.deepEqual(plain(h.model.draft), before.config);
    assert.equal(h.model.notice, ''); assert.equal(h.model.secret, ''); assert.equal(h.calls.filter(call => !call[0].startsWith('/mail/folders')).length, 2);
  }
});

test('failed or malformed recovery GET cannot replace the saved snapshot or unlock activation', async () => {
  for (const reload of ['refresh', 'load']) for (const response of [{}, { config: {} }, [], null, 'network']) {
    let refreshing = false;
    const h = setup(path => path === '/mail/activate' ? Promise.reject(new TypeError('Response lost'))
      : refreshing ? response === 'network' ? Promise.reject(new TypeError('Offline')) : Promise.resolve(response) : Promise.resolve(activationSnapshot()));
    await h.model.load(); h.model.select('mail', 'mail-one'); const before = plain(h.model.snapshot);
    h.model.setSecret('SYNTHETIC-AUTH-CODE'); await h.model.activateMail(() => true); refreshing = true;
    await h.model[reload](); assert.equal(h.model.activationNeedsRefresh, true); assert.equal(h.model.error, 'integrations.error.mailOutcomeUncertain');
    assert.deepEqual(plain(h.model.snapshot), before); assert.deepEqual(plain(h.model.draft), before.config);
    await h.model.activateMail(() => assert.fail('invalid GET must not unlock activation'));
    assert.equal(h.calls.filter(call => call[0] === '/mail/activate').length, 1);
  }
});

test('switching accounts and reopening preserve unknown state until a fresh valid snapshot', async () => {
  let validRead = true, saved = activationSnapshot(); saved.config.mailAccounts.push({ ...plain(saved.config.mailAccounts[0]), id: 'mail-two', email: 'second@qq.com', displayName: 'Second' });
  saved.statuses['mail-two'] = { state: 'disabled', credentialConfigured: false };
  const h = await setupView(path => path === '/mail/activate' ? Promise.reject(new TypeError('Lost response'))
    : Promise.resolve(validRead ? plain(saved) : {}));
  h.button('Test').fire('click'); h.change('secret', 'SYNTHETIC-AUTH-CODE'); h.approveDiscard = true;
  h.button('integrations.action.activateMail').fire('click'); await tick();
  assert.ok(allText(h.root).includes('integrations.mailStateUncertain')); assert.ok(!allText(h.root).includes('integrations.mailDisabled'));
  h.button('Second').fire('click'); h.button('Test').fire('click'); assert.equal(h.model.activationNeedsRefresh, true);
  assert.equal(h.button('integrations.action.activateMail').disabled, true); validRead = false;
  h.view.destroy(); h.view = h.context.mountIntegrations(h.root); await tick(); h.button('Test').fire('click');
  assert.equal(h.model.activationNeedsRefresh, true); assert.deepEqual(plain(h.model.snapshot), saved);
  assert.ok(allText(h.root).includes('integrations.mailStateUncertain')); assert.ok(!allText(h.root).includes('integrations.mailDisabled'));
  assert.equal(h.field('secret').value, ''); assert.equal(h.button('integrations.action.refreshMail').disabled, false);
  saved.config.revision = 5; Object.assign(saved.config.mailAccounts[0], committedActivationSnapshot().config.mailAccounts[0]);
  saved.statuses['mail-one'] = committedActivationSnapshot().statuses['mail-one']; validRead = true;
  h.button('integrations.action.refreshMail').fire('click'); await tick();
  assert.equal(h.model.activationNeedsRefresh, false); assert.equal(h.model.entry().enabled, true);
  assert.equal(h.calls.filter(call => call[0] === '/mail/activate').length, 1); h.view.destroy();
});

test('a GET started before an abandoned activation settles does not count as its recovery check', async () => {
  const activation = deferred(); const h = await setupView(path => path === '/mail/activate' ? activation.promise : Promise.resolve(activationSnapshot()));
  h.button('Test').fire('click'); h.change('secret', 'SYNTHETIC-AUTH-CODE'); h.approveDiscard = true;
  h.button('integrations.action.activateMail').fire('click'); h.view.destroy(); h.view = h.context.mountIntegrations(h.root); await tick(); h.button('Test').fire('click');
  assert.equal(h.model.activationNeedsRefresh, true); assert.ok(allText(h.root).includes('integrations.mailStateUncertain'));
  activation.reject(new TypeError('Aborted response')); await tick();
  assert.equal(h.model.activationNeedsRefresh, true); assert.equal(h.button('integrations.action.activateMail').disabled, true);
  h.button('integrations.action.refreshMail').fire('click'); await tick(); assert.equal(h.model.activationNeedsRefresh, false);
  assert.equal(h.calls.filter(call => call[0] === '/mail/activate').length, 1); h.view.destroy();
});

test('activation API rejects truncated JSON instead of converting it to empty success; MCP parsing stays unchanged', async () => {
  const apiSource = fs.readFileSync(new URL('api.js', base), 'utf8');
  for (const ok of [true, false]) {
    const context = vm.createContext({ window: { location: { origin: 'http://localhost:5700' } },
      fetch: () => Promise.resolve({ ok, status: ok ? 200 : 502, json: () => Promise.reject(new SyntaxError('truncated SYNTHETIC')) }) });
    vm.runInContext(apiSource, context);
    await assert.rejects(context.api.integrations('/mail/activate', 'POST', {}), error => {
      assert.equal(error.code, 'mail_activation_outcome_uncertain'); assert.doesNotMatch(JSON.stringify(error), /SYNTHETIC|truncated/); return true;
    });
    if (ok) assert.deepEqual(plain(await context.api.integrations('/credential', 'POST', {})), {});
    else await assert.rejects(context.api.integrations('/credential', 'POST', {}), error => error.code === 'unavailable');
  }
});

test('truncated HTTP 200 activation response passes through API as unknown without corrupting model or retrying', async () => {
  const apiSource = fs.readFileSync(new URL('api.js', base), 'utf8'); let persisted = activationSnapshot(), posts = 0;
  const context = vm.createContext({ AbortController, crypto: webcrypto, t: key => key,
    window: { location: { origin: 'http://localhost:5700' } }, fetch: (url, options) => {
      if (options.method === 'POST') {
        posts++; persisted = committedActivationSnapshot();
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.reject(new SyntaxError('Truncated response')) });
      }
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(plain(persisted)) });
    } });
  vm.runInContext(apiSource, context); vm.runInContext(source, context);
  const model = context.createIntegrationsModel(context.api.integrations, () => {}); await model.load(); model.select('mail', 'mail-one');
  const before = plain(model.snapshot); model.setSecret('SYNTHETIC-AUTH-CODE'); await model.activateMail(() => true);
  assert.equal(persisted.config.mailAccounts[0].enabled, true); assert.deepEqual(plain(model.snapshot), before);
  assert.deepEqual(plain(model.draft), before.config); assert.equal(model.activationNeedsRefresh, true);
  assert.equal(model.secret, ''); assert.equal(model.error, 'integrations.error.mailOutcomeUncertain'); assert.equal(posts, 1);
  assert.doesNotMatch(JSON.stringify(context.integrationMailActivationRecovery), /SYNTHETIC-AUTH-CODE/);
  await model.activateMail(() => assert.fail('must refresh first')); assert.equal(posts, 1);
  await model.refresh(); assert.equal(model.activationNeedsRefresh, false); assert.equal(model.entry().enabled, true); assert.equal(posts, 1);
});

test('known explicit activation rejections remain actionable failures without an uncertainty lock', async () => {
  for (const [status, code, category] of [[409, 'revision_conflict', 'conflict'], [409, 'model_target_changed', 'reviewRequired'],
    [400, 'credential_verification_failed', 'mailStorage'], [403, 'managed_desktop_required', 'permission']]) {
    const h = setup(path => path === '/mail/activate' ? Promise.reject({ status, code }) : Promise.resolve(activationSnapshot()));
    await h.model.load(); h.model.select('mail', 'mail-one'); h.model.setSecret('SYNTHETIC-AUTH-CODE'); await h.model.activateMail(() => true);
    assert.equal(h.model.activationNeedsRefresh, false); assert.equal(h.model.error, `integrations.error.${category}`);
    assert.equal(h.context.integrationMailActivationRecovery, null); assert.equal(h.model.entry().enabled, false);
    assert.equal(h.model.notice, ''); assert.equal(h.model.secret, ''); assert.equal(h.calls.filter(call => !call[0].startsWith('/mail/folders')).length, 2);
  }
});

test('a recovery snapshot missing any configured mail or MCP status cannot unlock activation', async () => {
  for (const mutate of [data => { data.statuses = {}; }, data => { delete data.statuses['mail-one'].credentialConfigured; },
    data => { data.statuses['mail-one'].state = ''; }, data => { data.config.mcpServers = mcpSnapshot().config.mcpServers; }]) {
    let refreshing = false; const broken = committedActivationSnapshot(); mutate(broken);
    const h = setup(path => path === '/mail/activate' ? Promise.reject(new TypeError('Lost response'))
      : Promise.resolve(refreshing ? plain(broken) : activationSnapshot()));
    await h.model.load(); h.model.select('mail', 'mail-one'); const before = plain(h.model.snapshot);
    h.model.setSecret('SYNTHETIC-AUTH-CODE'); await h.model.activateMail(() => true); refreshing = true;
    await h.model.refresh(); assert.equal(h.model.activationNeedsRefresh, true); assert.deepEqual(plain(h.model.snapshot), before);
    assert.deepEqual(plain(h.model.draft), before.config); assert.equal(h.model.error, 'integrations.error.mailOutcomeUncertain');
    assert.equal(h.calls.filter(call => call[0] === '/mail/activate').length, 1);
  }
});

test('unknown activation blocks adding mail without changing the draft or obstructing refresh; MCP add remains available', async () => {
  const h = await setupView(path => path === '/mail/activate' ? Promise.reject(new TypeError('Lost activation response'))
    : Promise.resolve(activationSnapshot({ stored: true })));
  h.button('Test').fire('click'); h.approveDiscard = true; h.button('integrations.action.activateStoredMail').fire('click'); await tick();
  const before = plain(h.model.draft), selected = plain(h.model.selected);
  assert.equal(h.model.activationNeedsRefresh, true); assert.equal(h.button('integrations.add.mail').disabled, true);
  assert.equal(h.button('integrations.add.mcp').disabled, false);
  h.button('integrations.add.mail').fire('click'); h.model.add('mail');
  assert.deepEqual(plain(h.model.draft), before); assert.deepEqual(plain(h.model.selected), selected);
  assert.equal(h.model.metadataDirty(), false); assert.equal(h.button('integrations.action.refreshMail').disabled, false);
  h.button('integrations.action.refreshMail').fire('click'); await tick();
  assert.equal(h.model.activationNeedsRefresh, false); assert.equal(h.button('integrations.add.mail').disabled, false);
  h.approveDiscard = true; h.button('integrations.action.activateStoredMail').fire('click'); await tick();
  h.button('integrations.add.mcp').fire('click'); assert.equal(h.model.selected.kind, 'mcp');
  assert.equal(h.model.draft.mcpServers.length, before.mcpServers.length + 1); h.view.destroy();
});

function deletionSnapshot() {
  const data = snapshot();
  data.config.mcpServers = [
    { id: 'first', name: 'First', endpoint: 'https://first.example/mcp', enabled: false, authType: 'bearer', credentialRef: 'session:00000000-0000-0000-0000-000000000001', storageMode: 'session', transport: 'streamable-http', budget: {} },
    { id: 'second', name: 'Second', endpoint: 'https://second.example/mcp', enabled: false, authType: 'anonymous', credentialRef: null, storageMode: 'session', transport: 'streamable-http', budget: {} }
  ];
  data.statuses.first = {state: 'disabled', credentialConfigured: true}; data.statuses.second = {state: 'disabled', credentialConfigured: false};
  data.config.toolApprovals = [{ sourceId: 'first', toolName: 'one', definitionDigest: 'a'.repeat(64), approvedRevision: 4 },
    { sourceId: 'second', toolName: 'two', definitionDigest: 'b'.repeat(64), approvedRevision: 4 }];
  return data;
}
function deletedSnapshot(id = 'first') {
  const result = deletionSnapshot(); result.config.revision++;
  result.config.mcpServers = result.config.mcpServers.filter(item => item.id !== id);
  result.config.toolApprovals = result.config.toolApprovals.filter(item => item.sourceId !== id);
  return result;
}

test('draft deletion requires confirmation, is local and preserves every other draft', async () => {
  const h = setup(); await h.model.load(); h.model.add('mcp'); const id = h.model.entry().id;
  h.model.add('mcp'); const other = h.model.entry().id; h.model.select('mcp', id);
  h.model.setSecret('synthetic-private'); await h.model.remove(() => false);
  assert.equal(h.model.draft.mcpServers.length, 2); assert.equal(h.model.secret, 'synthetic-private');
  await h.model.remove(value => { assert.equal(value.saved, false); assert.equal(value.entry.id, id); return true; });
  assert.deepEqual(plain(h.model.draft.mcpServers.map(item => item.id)), [other]);
  assert.equal(h.model.draft.mailAccounts.length, 1); assert.equal(h.model.secret, '');
  assert.equal(h.calls.filter(call => !call[0].startsWith('/mail/folders')).length, 1); assert.equal(h.model.notice, 'draftDeleted'); assert.equal(h.model.selected, null);
});

test('saved deletion uses saved identity, only id/kind/revision and preserves unrelated unsaved edits', async () => {
  const pending = deferred(); const h = setup(path => path === '/delete' ? pending.promise : Promise.resolve(deletionSnapshot()));
  await h.model.load(); h.model.select('mcp', 'second'); h.model.change('name', 'Keep unsaved');
  h.model.add('mail'); const draftId = h.model.entry().id;
  h.model.select('mcp', 'first'); h.model.change('name', 'Changed identity'); h.model.setSecret('never-send');
  const deleting = h.model.remove(value => { assert.equal(value.saved, true); assert.equal(value.entry.name, 'First'); return true; });
  await h.model.remove(() => { throw new Error('must not confirm twice'); });
  h.model.add('mcp'); h.model.change('name', 'must not edit'); await h.model.refresh();
  assert.deepEqual(plain(h.calls.at(-1).slice(0, 3)), ['/delete', 'POST', { kind: 'mcp', id: 'first', expectedRevision: 4 }]);
  assert.equal(h.model.secret, ''); assert.equal(h.model.busy, 'delete');
  pending.resolve(deletedSnapshot()); await deleting;
  assert.equal(h.model.snapshot.config.revision, 5); assert.equal(h.model.draft.revision, 5);
  assert.equal(h.model.draft.mcpServers[0].name, 'Keep unsaved');
  assert.equal(h.model.draft.mailAccounts.at(-1).id, draftId); assert.equal(h.model.metadataDirty(), true);
  assert.equal(h.model.draft.toolApprovals.length, 1); assert.equal(h.model.draft.toolApprovals[0].sourceId, 'second');
  assert.equal(h.model.snapshot.config.mcpServers[0].name, 'Second');
  assert.equal(h.model.notice, 'deleted'); assert.equal(h.calls.filter(call => !call[0].startsWith('/mail/folders')).length, 2);
});

test('saved deletion cancellation and revision conflict preserve selected metadata and other entries', async () => {
  const h = setup(path => path === '/delete' ? Promise.reject({ code: 'conflict', status: 409 }) : Promise.resolve(deletionSnapshot()));
  await h.model.load(); h.model.select('mcp', 'first'); h.model.change('name', 'Draft');
  await h.model.remove(() => false); assert.equal(h.calls.filter(call => !call[0].startsWith('/mail/folders')).length, 1);
  await h.model.remove(() => true);
  assert.equal(h.model.entry().name, 'Draft'); assert.equal(h.model.draft.mcpServers.length, 2);
  assert.equal(h.model.snapshot.config.revision, 4); assert.equal(h.model.error, 'integrations.error.conflict');
  assert.equal(h.model.outcomeUnknown, false);
});

test('unknown deletion outcomes lock writes and keep drafts until successful explicit reload', async () => {
  for (const outcome of [() => Promise.reject(new TypeError('response lost')), () => Promise.reject({ status: 400, code: 'integration_operation_failed' }),
    () => Promise.resolve({}), () => { const r = deletedSnapshot(); r.config.mcpServers[0].name = 'unexpected'; return Promise.resolve(r); }]) {
    let reading = deletionSnapshot(); const h = setup(path => path === '/delete' ? outcome() : Promise.resolve(reading));
    await h.model.load(); h.model.select('mcp', 'first'); h.model.change('name', 'Keep draft');
    await h.model.remove(() => true); assert.equal(h.model.outcomeUnknown, true); assert.equal(h.model.entry().name, 'Keep draft');
    await h.model.remove(() => { throw new Error('do not repeat confirmation'); });
    await h.model.save(); await h.model.action('test'); h.model.add('mail'); h.model.change('name', 'Wrong'); h.model.setSecret('forbidden');
    assert.equal(h.calls.filter(call => !call[0].startsWith('/mail/folders')).length, 2); assert.equal(h.model.entry().name, 'Keep draft'); assert.equal(h.model.secret, '');
    reading = deletedSnapshot(); await h.model.load();
    assert.equal(h.model.outcomeUnknown, false); assert.equal(h.model.draft.mcpServers.length, 1); assert.equal(h.model.metadataDirty(), false);
  }
});

test('cleanup pending is not reported as complete secret deletion; closed late replies cannot resurrect drafts', async () => {
  const result = deletedSnapshot(); result.credentialCleanupPending = true;
  const h = setup(path => path === '/delete' ? Promise.resolve(result) : Promise.resolve(deletionSnapshot()));
  await h.model.load(); h.model.select('mcp', 'first'); await h.model.remove(() => true);
  assert.equal(h.model.notice, 'cleanupPending'); assert.equal(h.model.snapshot.credentialCleanupPending, true);
  const pending = deferred(); const closed = setup(path => path === '/delete' ? pending.promise : Promise.resolve(deletionSnapshot()));
  await closed.model.load(); closed.model.select('mcp', 'first'); const deleting = closed.model.remove(() => true);
  closed.model.destroy(); pending.resolve(deletedSnapshot()); await deleting;
  assert.equal(closed.model.draft, null); assert.equal(closed.model.snapshot, null);
});

test('legacy cannot be deleted and deleting a saved mailbox preserves MCP servers', async () => {
  const result = deletionSnapshot(); result.config.mailAccounts = []; result.config.revision++;
  const h = setup(path => path === '/delete' ? Promise.resolve(result) : Promise.resolve(deletionSnapshot()));
  await h.model.load(); h.model.select('legacy', 'legacy-websearch');
  await h.model.remove(() => { throw new Error('legacy not deletable'); }); assert.equal(h.calls.filter(call => !call[0].startsWith('/mail/folders')).length, 1);
  h.model.select('mail', 'mail-one'); await h.model.remove(() => true);
  assert.equal(h.model.draft.mailAccounts.length, 0); assert.equal(h.model.draft.mcpServers.length, 2);
});

function folderResult(names = ['INBOX', 'Work/客户', '<script>not-code</script>'], revision = 4) {
  return { accountId: 'mail-one', revision, complete: true, folders: names.map(name => ({ name, separator: '/', selectable: true })) };
}
function folderSetup({ stored = true, names, handler } = {}) {
  let data = activationSnapshot({ stored });
  const h = setup((path, method, body, signal) => {
    if (handler) { const value = handler(path, method, body, signal); if (value !== undefined) return value; }
    if (path === '/mail/folders') return Promise.resolve(folderResult(names, data.config.revision));
    if (path === '/mail/folders/cancel') return Promise.resolve({ cancelled: true });
    if (method === 'PUT') { data.config = plain(body.config); data.config.revision++; }
    return Promise.resolve(plain(data));
  });
  return h;
}

test('opening configured mail automatically discovers complete folders once without model/body authorization', async () => {
  const h = folderSetup(); await h.model.load(); await h.model.select('mail', 'mail-one');
  assert.equal(h.calls.filter(c => c[0] === '/mail/folders').length, 1);
  const call = h.calls.find(c => c[0] === '/mail/folders');
  assert.deepEqual(Object.keys(call[2]).sort(), ['expectedRevision', 'id', 'requestId']);
  assert.match(call[2].requestId, /^[\da-f-]{36}$/);
  assert.equal(h.model.folders.items.length, 3); assert.equal(h.model.folders.stale, false);
  assert.equal(h.model.entry().enabled, false); assert.equal(h.model.entry().bodyAccess, false);
  assert.equal(h.model.dirty(), false); assert.equal(h.model.notice, '');
});

test('unsaved and missing-credential accounts do not auto-connect; explicit temporary discovery is not activation', async () => {
  let submitted; const h = folderSetup({ stored: false, handler(path, method, body) {
    if (path === '/mail/folders') { submitted = plain(body); return Promise.resolve(folderResult()); }
  } });
  await h.model.load(); await h.model.select('mail', 'mail-one'); assert.equal(h.calls.length, 1);
  h.model.setSecret('TEMP-CODE'); assert.equal(h.calls.length, 1);
  await h.model.loadFolders(); assert.equal(submitted.secret, 'TEMP-CODE');
  assert.equal(h.calls.find(c => c[0] === '/mail/folders')[2].secret, undefined);
  assert.equal(h.model.entry().enabled, false); assert.equal(h.model.entry().credentialRef, null);
  assert.equal(h.calls.filter(c => c[0] === '/mail/activate' || c[0] === '/credential').length, 0);
  h.model.add('mail'); await h.model.loadFolders(); assert.equal(h.calls.filter(c => c[0] === '/mail/folders').length, 1);
  h.model.destroy(); assert.equal(h.model.secret, '');
});

test('saving successfully triggers discovery, and discovery failure cannot turn committed save into failure', async () => {
  let rejectFolders = false;
  const h = folderSetup({ handler(path) { if (path === '/mail/folders' && rejectFolders) return Promise.reject({ code: 'authentication_failed' }); } });
  await h.model.load(); await h.model.select('mail', 'mail-one');
  h.model.change('displayName', 'Saved name'); rejectFolders = true; await h.model.save();
  assert.equal(h.model.snapshot.config.revision, 5); assert.equal(h.model.metadataDirty(), false);
  assert.equal(h.model.notice, 'saved'); assert.equal(h.model.error, '');
  assert.equal(h.model.folders.error, 'integrations.error.authentication');
  assert.equal(h.calls.filter(c => c[1] === 'PUT').length, 1);
  rejectFolders = false; await h.model.loadFolders(); assert.equal(h.model.folders.items.length, 3);
  assert.equal(h.calls.filter(c => c[1] === 'PUT').length, 1);
});

test('range save retains unchecked folders as stale display data when automatic rediscovery fails', async () => {
  let fail = false;
  const h = folderSetup({handler(path) {
    if (path === '/mail/folders' && fail) return Promise.reject({code: 'timeout'});
  }});
  await h.model.load(); await h.model.select('mail', 'mail-one');
  h.model.toggleFolder('Work/客户', false);
  const selected = plain(h.model.entry().allowedFolders);
  fail = true; await h.model.save();
  assert.equal(h.model.notice, 'saved'); assert.equal(h.model.error, '');
  assert.deepEqual(plain(h.model.folders.items), folderResult().folders);
  assert.equal(h.model.folders.stale, true); assert.equal(h.model.folders.error, 'integrations.error.timeout');
  assert.deepEqual(plain(h.model.entry().allowedFolders), selected);
  h.model.selectCurrentFolders(false);
  assert.deepEqual(plain(h.model.entry().allowedFolders), selected);
  fail = false; await h.model.loadFolders();
  assert.equal(h.model.folders.stale, false);
  assert.deepEqual(plain(h.model.entry().allowedFolders), selected);
  h.model.change('email', 'other@163.com');
  assert.equal(h.model.folders.items, null); // A different identity must not inherit this snapshot.
});

test('dynamic all uncheck becomes exact selection; search and refresh preserve absent selected names', async () => {
  let result = folderResult(); const h = folderSetup({ handler(path) { if (path === '/mail/folders') return Promise.resolve(plain(result)); } });
  await h.model.load(); await h.model.select('mail', 'mail-one'); h.model.toggleFolder('Work/客户', false);
  assert.equal(h.model.entry().folderScope, 'selected');
  assert.deepEqual(plain(h.model.entry().allowedFolders), ['INBOX', '<script>not-code</script>']);
  h.model.filterFolders('no matches'); assert.equal(h.model.entry().allowedFolders.length, 2);
  result = folderResult(['INBOX'], 5); await h.model.save();
  assert.deepEqual(plain(h.model.entry().allowedFolders), ['INBOX', '<script>not-code</script>']);
  h.model.toggleFolder('<script>not-code</script>', false); assert.deepEqual(plain(h.model.entry().allowedFolders), ['INBOX']);
  h.model.selectCurrentFolders(true); assert.equal(h.model.entry().folderScope, 'selected');
  await h.model.save(); assert.equal(h.model.error, 'integrations.error.folderSelection');
  assert.equal(h.model.snapshot.config.revision, 5);
});

test('current selection supports 256 exact names but never accepts 257 or a partial/malformed directory response', async () => {
  const names = Array.from({ length: 256 }, (_, i) => `Folder/${i}`); const h = folderSetup({ names });
  await h.model.load(); await h.model.select('mail', 'mail-one'); h.model.selectCurrentFolders(false);
  assert.equal(h.model.entry().allowedFolders.length, 256); await h.model.save();
  assert.equal(h.model.snapshot.config.mailAccounts[0].allowedFolders.length, 256);
  h.model.change('allowedFolders', [...names, 'Extra']); await h.model.save(); assert.equal(h.model.error, 'integrations.error.folderSelection');
  for (const mutate of [r => r.complete = false, r => r.accountId = 'other', r => r.revision++, r => r.folders.push(r.folders[0]),
      r => r.folders[0].name = '\u0000bad', r => r.folders = [...folderResult(names).folders, {name: 'extra', separator: '/', selectable: true}]]) {
    const value = folderResult(); mutate(value); const bad = folderSetup({handler(path) {if (path === '/mail/folders') return Promise.resolve(value);}});
    await bad.model.load(); await bad.model.select('mail', 'mail-one'); assert.equal(bad.model.folders.items, null);
    assert.equal(bad.model.folders.error, 'integrations.error.validation');
    bad.model.toggleFolder('INBOX', false); assert.equal(bad.model.entry().folderScope, 'all');
  }
});

test('discovery is deduplicated and cancellation aborts plus targets the exact backend request', async () => {
  const pending = deferred(); const h = folderSetup({ handler(path) { if (path === '/mail/folders') return pending.promise; } });
  await h.model.load(); const loading = h.model.select('mail', 'mail-one'); await h.model.loadFolders();
  assert.equal(h.calls.filter(c => c[0] === '/mail/folders').length, 1);
  const call = h.calls.find(c => c[0] === '/mail/folders'); h.model.cancel();
  assert.equal(call[3].aborted, true);
  assert.deepEqual(plain(h.calls.find(c => c[0] === '/mail/folders/cancel')[2]), { id: 'mail-one', requestId: call[2].requestId });
  pending.resolve(folderResult()); await loading; assert.equal(h.model.folders.items, null); assert.equal(h.model.folders.loading, false);
});

test('late discovery cannot cross identity changes, deleted accounts or a closed view', async () => {
  for (const action of ['identity', 'select', 'delete', 'destroy']) {
    const pending = deferred(); const h = folderSetup({ handler(path, method, body) {
      if (path === '/mail/folders') return pending.promise;
      if (path === '/delete') { const data = activationSnapshot({stored: true}); data.config.mailAccounts = []; data.config.revision++; delete data.statuses['mail-one']; return Promise.resolve(data); }
    } });
    await h.model.load(); const loading = h.model.select('mail', 'mail-one');
    if (action === 'identity') h.model.change('email', 'other@qq.com');
    if (action === 'select') h.model.select('legacy', 'legacy-websearch');
    if (action === 'delete') await h.model.remove(() => true);
    if (action === 'destroy') h.model.destroy();
    pending.resolve(folderResult()); await loading; assert.equal(h.model.folders.items, null);
    assert.equal(h.calls.find(c => c[0] === '/mail/folders')[3].aborted, true);
  }
});

test('failed refresh retains selected choices and previous list marked stale until explicit retry', async () => {
  let fail = false; const h = folderSetup({handler(path) {if (path === '/mail/folders' && fail) return Promise.reject({code:'timeout'});}});
  await h.model.load(); await h.model.select('mail', 'mail-one'); fail = true; await h.model.loadFolders();
  assert.equal(h.model.folders.items.length, 3); assert.equal(h.model.folders.stale, true); assert.equal(h.model.folders.error, 'integrations.error.timeout');
  h.model.toggleFolder('INBOX', false); assert.equal(h.model.entry().folderScope, 'all');
  fail = false; await h.model.loadFolders(); assert.equal(h.model.folders.stale, false);
});

test('folder DOM renders names safely and preserves checkbox keyboard focus after updating the selection', async () => {
  const h = await setupView(path => Promise.resolve(path === '/mail/folders' ? folderResult() : activationSnapshot({stored: true})));
  h.button('Test').fire('click'); await tick();
  assert.equal(h.field('allowedFolders'), null); assert.equal(h.root.querySelectorAll('script').length, 0);
  const folder = () => h.root.querySelectorAll('input').find(el => el.getAttribute('data-folder-name') === 'Work/客户');
  folder().focus(); folder().checked = false; folder().fire('change');
  assert.equal(h.model.entry().folderScope, 'selected'); assert.equal(h.document.activeElement.getAttribute('data-folder-name'), 'Work/客户');
  h.model.filterFolders('INBOX'); assert.equal(h.model.entry().allowedFolders.length, 2);
  h.model.filterFolders(''); assert.ok(allText(h.root).includes('<script>not-code</script>'));
  h.view.destroy();
});

test('disconnect cancels in-flight folder loading and ignores a late result', async () => {
  const pending = deferred(); const h = folderSetup({handler(path) {
    if (path === '/mail/folders') return pending.promise;
    if (path === '/disconnect') { const data = activationSnapshot(); data.config.revision = 5; return Promise.resolve(data); }
  }});
  await h.model.load(); const loading = h.model.select('mail', 'mail-one');
  await h.model.action('disconnect'); pending.resolve(folderResult()); await loading;
  assert.equal(h.model.folders.items, null); assert.equal(h.model.entry().credentialRef, null);
  assert.equal(h.model.entry().enabled, false); assert.equal(h.model.notice, 'disconnected');
  assert.equal(h.calls.find(call => call[0] === '/mail/folders')[3].aborted, true);
  assert.equal(h.calls.filter(call => call[0] === '/mail/folders/cancel').length, 1);
});

test('activation stays committed when its automatic folder loading fails', async () => {
  let fail = false;
  const h = folderSetup({stored: false, handler(path) {
    if (path === '/mail/activate') { const data = activationSnapshot({stored: true, enabled: true}); data.config.revision = 5; return Promise.resolve(data); }
    if (path === '/mail/folders' && fail) return Promise.reject({code: 'timeout'});
  }});
  await h.model.load(); await h.model.select('mail', 'mail-one'); h.model.setSecret('TEMP-CODE');
  await h.model.loadFolders(); fail = true;
  await h.model.activateMail(() => true);
  assert.equal(h.model.notice, 'mailActivated'); assert.equal(h.model.error, '');
  assert.equal(h.model.entry().enabled, true); assert.equal(h.model.entry().storageMode, 'windows');
  assert.equal(h.model.folders.error, 'integrations.error.timeout'); assert.equal(h.model.activationNeedsRefresh, false);
  assert.deepEqual(plain(h.model.folders.items), folderResult().folders); assert.equal(h.model.folders.stale, true);
  assert.equal(h.calls.filter(call => call[0] === '/mail/activate').length, 1);
});
