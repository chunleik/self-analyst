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
    { id: 'mail-one', provider: 'qq', email: 'test@qq.com', displayName: 'Test', allowedFolders: ['INBOX'], bodyAccess: false,
      enabled: false, credentialRef: 'session:00000000-0000-0000-0000-000000000000', storageMode: 'session', disclosureVersion: 0 }
  ] }, statuses: {}, persistentCredentialsSupported: false, modelTarget: 'https://model.example/v1' };
}
function setup(handler) {
  const calls = [];
  const context = vm.createContext({ AbortController, crypto: webcrypto, t: key => key });
  vm.runInContext(source, context);
  const request = (...args) => { calls.push(args); return handler?.(...args) ?? Promise.resolve(snapshot()); };
  return { model: context.createIntegrationsModel(request, () => {}), context, calls };
}

test('local load is side-effect free; new mail and MCP metadata starts disabled with strict fields', async () => {
  const h = setup(); await h.model.load();
  assert.deepEqual(h.calls.map(c => [c[0], c[1]]), [['', 'GET']]);
  h.model.add('mcp'); const mcp = plain(h.model.entry());
  assert.equal(mcp.transport, 'streamable-http'); assert.equal(mcp.authType, 'anonymous'); assert.equal(mcp.enabled, false);
  assert.equal(mcp.credentialRef, null); assert.equal(mcp.budget.maxTools, 64);
  h.model.add('mail'); assert.equal(h.model.entry().disclosureVersion, 0); assert.equal(h.model.entry().enabled, false);
  assert.deepEqual(plain(h.model.entry().allowedFolders), ['INBOX']);
  assert.equal(h.calls.length, 1);
});

test('saving sends revision and metadata only; duplicate save and edits are locked', async () => {
  const pending = deferred(); const h = setup((path, method) => method === 'PUT' ? pending.promise : undefined);
  await h.model.load(); h.model.select('mail', 'mail-one'); h.model.change('displayName', 'Changed'); h.model.setSecret('SYNTHETIC-CODE');
  const saved = h.model.save(); await h.model.save(); h.model.change('displayName', 'Too late');
  assert.equal(h.calls.filter(c => c[1] === 'PUT').length, 1);
  const body = h.calls.at(-1)[2]; assert.equal(body.expectedRevision, 4); assert.equal(body.config.mailAccounts[0].displayName, 'Changed');
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

test('mail enable requires explicit retention consent; body read stays opt-in', async () => {
  const h = setup(); await h.model.load(); h.model.select('mail', 'mail-one');
  h.model.change('enabled', true); await h.model.save();
  assert.equal(h.calls.length, 1); assert.equal(h.model.error, 'integrations.consentRequired');
  assert.equal(h.model.entry().bodyAccess, false); h.model.change('disclosureVersion', 1); await h.model.save();
  assert.equal(h.calls.at(-1)[1], 'PUT');
});

test('credential is write-only, session-only on unsupported vault, and cleared even on failure', async () => {
  const pending = deferred(); const h = setup(path => path === '/credential' ? pending.promise : undefined);
  await h.model.load(); h.model.select('mail', 'mail-one'); h.model.setSecret('SYNTHETIC-CODE'); h.model.setPersist(true);
  const saved = h.model.action('credential'); assert.equal(h.model.secret, ''); assert.equal(h.model.persist, false);
  assert.equal(h.calls.at(-1)[2].persist, false); assert.equal(h.calls.at(-1)[2].secret, 'SYNTHETIC-CODE');
  await h.model.action('credential'); assert.equal(h.calls.filter(c => c[0] === '/credential').length, 1);
  pending.reject({ code: 'authentication_failed' }); await saved;
  assert.equal(h.model.secret, ''); assert.equal(h.calls.at(-1)[2].secret, undefined);
  assert.equal(h.model.error, 'integrations.error.authentication');
});

test('account/endpoint edits clear old target credentials; cancel, selection and destroy clear secrets', async () => {
  const h = setup(); await h.model.load(); h.model.select('mail', 'mail-one'); h.model.setSecret('synthetic');
  h.model.change('email', 'other@qq.com'); assert.equal(h.model.secret, ''); assert.equal(h.model.entry().credentialRef, null);
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
  const live = snapshot(); live.config.revision = 7; live.statuses = { 'mail-one': { state: 'ready' } };
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
  assert.equal(h.calls.length, 2); pending.resolve(snapshot()); await refresh; assert.equal(h.model.entry().displayName, 'Draft');
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
  assert.equal(h.model.metadataDirty(), false); await h.model.action('disconnect'); assert.equal(h.calls.length, 1);
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
  assert.equal(h.model.metadataDirty(), true); assert.equal(h.calls.length, 1); await h.model.save();
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

function deletionSnapshot() {
  const data = snapshot();
  data.config.mcpServers = [
    { id: 'first', name: 'First', endpoint: 'https://first.example/mcp', enabled: false, authType: 'bearer', credentialRef: 'session:synthetic', storageMode: 'session', transport: 'streamable-http', budget: {} },
    { id: 'second', name: 'Second', endpoint: 'https://second.example/mcp', enabled: false, authType: 'anonymous', credentialRef: null, storageMode: 'session', transport: 'streamable-http', budget: {} }
  ];
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
  assert.equal(h.calls.length, 1); assert.equal(h.model.notice, 'draftDeleted'); assert.equal(h.model.selected, null);
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
  assert.equal(h.model.notice, 'deleted'); assert.equal(h.calls.length, 2);
});

test('saved deletion cancellation and revision conflict preserve selected metadata and other entries', async () => {
  const h = setup(path => path === '/delete' ? Promise.reject({ code: 'conflict', status: 409 }) : Promise.resolve(deletionSnapshot()));
  await h.model.load(); h.model.select('mcp', 'first'); h.model.change('name', 'Draft');
  await h.model.remove(() => false); assert.equal(h.calls.length, 1);
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
    assert.equal(h.calls.length, 2); assert.equal(h.model.entry().name, 'Keep draft'); assert.equal(h.model.secret, '');
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
  await h.model.remove(() => { throw new Error('legacy not deletable'); }); assert.equal(h.calls.length, 1);
  h.model.select('mail', 'mail-one'); await h.model.remove(() => true);
  assert.equal(h.model.draft.mailAccounts.length, 0); assert.equal(h.model.draft.mcpServers.length, 2);
});
