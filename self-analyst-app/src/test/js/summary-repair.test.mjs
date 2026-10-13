import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
const root = new URL('../../main/resources/desktop-ui/', import.meta.url);
const read = name => fs.readFileSync(new URL(name, root), 'utf8');
const clone = value => JSON.parse(JSON.stringify(value));
const sid = 'a'.repeat(32);
const target = { entryId: 'hour-1', level: 'HOUR', periodStart: '2026-10-12T16:00:00Z', periodEnd: '2026-10-12T17:00:00Z', timezone: 'UTC', revision: 3 };
function view(sessionId = sid) {
  return { target: clone(target), evidence: [{ id: 'title:1', app: 'Editor', title: 'Repair <script>alert(1)</script>', observationOnly: true }], failureTextAvailable: false, message: 'WIKI_EVIDENCE_UNSUPPORTED_CLAIM',
    draft: { id: 'repair-1', sessionId, targetId: target.entryId, targetRevision: 3, draftRevision: 0, state: 'unvalidated', candidate: { summary: '', primaryTask: '', taskSegments: [] }, statements: [], issues: [] } };
}
function node(tag = 'div') {
  return { tagName: tag, children: [], attrs: {}, value: '', textContent: '', disabled: false,
    appendChild(child) { this.children.push(child); return child; },
    replaceChildren(...children) { this.children = children; },
    setAttribute(key, value) { this.attrs[key] = value; }, getAttribute(key) { return this.attrs[key]; },
    querySelector() { return null; }, querySelectorAll() { return []; }, focus() {},
  };
}
const walk = root => [root, ...root.children.flatMap(walk)];
const button = (slot, key) => walk(slot).find(item => item.tagName === 'button' && item.textContent === key);
const input = (slot, label) => walk(slot).find(item => item.tagName === 'label' && item.textContent === label)?.children[0];
function environment({ response = view(), sessionId = sid } = {}) {
  const slot = node(), panel = node(); slot.attrs['data-summary-repair-session'] = sessionId;
  const session = { id: sessionId, source: 'summary_repair', messages: [], messagesLoaded: true,
    contextSnapshot: { type: 'summary_repair', repairId: 'repair-1', target: clone(target) } };
  const calls = [], alerts = [];
  let current = clone(response);
  const context = { state: { activeChatSessionId: sessionId, chatSessions: [session], status: { llm: { configured: false } }, dateLocale: 'en-US', lang: 'en',
      dom: { chatThread: { querySelectorAll(selector) { return selector === '[data-summary-repair-session]' ? [slot] : []; } }, chatTabInput: node('textarea') } },
    document: { getElementById: () => panel, createElement: node },
    t: (key, params) => key + (params ? ':' + JSON.stringify(params) : ''),
    escHtml: value => String(value).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;'),
    getActiveChatSession() { return context.state.chatSessions.find(item => item.id === context.state.activeChatSessionId); },
    api: { summaryRepair: async (path, method = 'GET', body) => {
      calls.push({ path, method, body: clone(body || {}) });
      if (path.endsWith('/draft')) { current.draft.candidate = clone(body.candidate); current.draft.draftRevision++; current.draft.state = 'unvalidated'; current.draft.statements = []; }
      if (path.endsWith('/confirm')) { current.draft.draftRevision++; current.draft.statements = body.taskIndexes.map(index => ({ id: 'user_statement:1', targetId: target.entryId, taskIndex: index, text: current.draft.candidate.taskSegments[index].summary, draftRevision: current.draft.draftRevision, confirmedAt: '2026-10-13T08:00:00Z' })); }
      if (path.endsWith('/validate')) current.draft.state = 'valid';
      if (path.endsWith('/commit')) { current.draft.state = 'committed'; current.draft.committedRevision = 4; current.target.revision = 4; }
      return clone(current);
    }, getSession: async () => clone(session), setActiveSession: async () => {}, getSummary: async () => ({ timeline: [] }) },
    setTimeout: () => 1, clearTimeout() {}, alert: text => alerts.push(text),
    applyFullSession: (cached, fresh) => Object.assign(cached, fresh),
    switchTab: tab => { context.state.tab = tab; }, renderChatTab() {}, focusChatComposer() {},
  };
  vm.createContext(context); vm.runInContext(read('agent.js') + '\n' + read('summary-repair.js'), context);
  return { context, session, slot, panel, calls, alerts, current: () => current };
}
function deferred() { let resolve, reject; const promise = new Promise((res, rej) => { resolve = res; reject = rej; }); return { promise, resolve, reject }; }
const flush = () => new Promise(resolve => setImmediate(resolve));

test('entry binding uses exact server target, restores a repair session, and prefills without sending', async () => {
  const { context, session, calls } = environment();
  let creations = 0;
  context.createChatSession = () => { creations++; throw new Error('should restore existing target'); };
  context.api.postChat = () => { throw new Error('must not call model on entry'); };
  await context.openSummaryRepair(target);
  assert.equal(creations, 0);
  assert.deepEqual(calls[0].body, { sessionId: sid, targetId: 'hour-1', expectedTargetRevision: 3 });
  assert.equal(session.contextSnapshot.type, 'summary_repair');
  assert.equal(context.state.dom.chatTabInput.value, 'repair.prefill');
  assert.equal(context.state.tab, 'chat');
});

test('opening during a send preserves input and makes no repair or model request', async () => {
  const { context, calls, alerts } = environment();
  context.state.chatSending = true; context.state.dom.chatTabInput.value = 'unfinished question';
  await context.openSummaryRepair(target);
  assert.equal(calls.length, 0); assert.equal(alerts[0], 'repair.sendBusy');
  assert.equal(context.state.dom.chatTabInput.value, 'unfinished question');
});

test('model-disabled empty-start, edit, explicit confirmation, validation and commit run entirely locally', async () => {
  const { context, slot, calls } = environment();
  context.api.postChat = context.api.postChatStream = () => { throw new Error('model disabled'); };
  await context.refreshSummaryRepair();
  button(slot, 'repair.addTask').onclick();
  input(slot, 'repair.summary').value = 'Completed the synthetic review.'; input(slot, 'repair.summary').oninput();
  input(slot, 'repair.primaryTask').value = 'Synthetic review'; input(slot, 'repair.primaryTask').oninput();
  input(slot, 'repair.taskTitle').value = 'Synthetic review'; input(slot, 'repair.taskTitle').oninput();
  input(slot, 'repair.taskSummary').value = 'Completed the synthetic review.'; input(slot, 'repair.taskSummary').oninput();
  const source = input(slot, 'repair.source'); source.value = 'user_confirmed'; source.onchange();
  assert.equal(button(slot, 'repair.save').disabled, true);
  await button(slot, 'repair.confirmTask').onclick();
  await flush();
  assert.equal(calls.filter(call => call.path.endsWith('/draft')).length, 1);
  assert.equal(calls.filter(call => call.path.endsWith('/confirm')).length, 1);
  assert.deepEqual(calls.find(call => call.path.endsWith('/confirm')).body.taskIndexes, [0]);
  assert.ok(!JSON.stringify(calls).includes('"confirmed":'));
  assert.ok(walk(slot).some(item => item.textContent.startsWith('repair.confirmed')));
  await context.repairAction(sid, 'validate');
  assert.equal(button(slot, 'repair.save').disabled, false);
  await context.repairAction(sid, 'commit');
  assert.equal(context.repairState(sid).view.draft.state, 'committed');
  assert.ok(walk(slot).some(item => item.textContent.includes('repair.saved')));
  assert.ok(calls.find(call => call.path.endsWith('/commit')).body.idempotencyKey);
});

test('reference selection uses server evidence IDs and leaves factual statistics out of the edit payload', async () => {
  const { context, slot } = environment(); await context.refreshSummaryRepair();
  button(slot, 'repair.addTask').onclick();
  const checkbox = walk(slot).find(item => item.tagName === 'input' && item.type === 'checkbox');
  checkbox.checked = true; checkbox.onchange();
  const candidate = clone(context.repairState(sid).candidate);
  assert.deepEqual(candidate.taskSegments[0].evidenceFactIds, ['title:1']);
  assert.deepEqual(candidate.taskSegments[0].apps, ['Editor']);
  assert.deepEqual(Object.keys(candidate).sort(), ['primaryTask', 'summary', 'taskSegments']);
  assert.equal(walk(slot).some(item => item.innerHTML), false);
  assert.ok(walk(slot).some(item => item.textContent.includes('<script>alert(1)</script>')));
});

test('reloading a server repair restores draft, issues and source; neither surface parses model HTML as actions', async () => {
  const saved = view(); saved.draft.state = 'invalid'; saved.draft.draftRevision = 7;
  saved.draft.candidate.summary = '<img src=x onerror=alert(1)>';
  saved.draft.issues = [{ field: 'taskSegments[0].summary', code: 'WIKI_EVIDENCE_UNSUPPORTED_CLAIM' }];
  const { context, slot, session } = environment({ response: saved }); await context.refreshSummaryRepair();
  assert.equal(input(slot, 'repair.summary').value, saved.draft.candidate.summary);
  assert.ok(walk(slot).some(item => item.textContent.startsWith('taskSegments[0].summary')));
  assert.equal(button(slot, 'repair.save').disabled, true);
  assert.ok(context.summaryRepairSlotHtml(session, -1).includes(sid));
  session.messages = [{ id: 'u1', role: 'user' }, { id: 'a1', role: 'assistant', content: 'Saved <button onclick="commit()">' }];
  assert.equal(context.summaryRepairSlotHtml(session, 0), '');
  assert.ok(context.summaryRepairSlotHtml(session, 1).includes(sid));
  for (const file of ['chat.js', 'deep-chat-adapter.js']) assert.match(read(file), /summaryRepairSlotHtml\(session, i\)/);
});

test('late reads update only their bound session; ordinary sessions cannot inherit repair controls', async () => {
  const { context, slot } = environment(); const old = deferred(); context.api.summaryRepair = () => old.promise;
  const pending = context.refreshSummaryRepair();
  context.state.chatSessions.push({ id: 'normal', messages: [], contextSnapshot: null, source: 'manual' });
  context.state.activeChatSessionId = 'normal';
  await context.refreshSummaryRepair();
  old.resolve(view()); await pending;
  assert.equal(context.state.activeChatSessionId, 'normal');
  assert.equal(context.summaryRepairSlotHtml(context.getActiveChatSession(), -1), '');
  assert.equal(slot.children.length, 2); // Old slot is never populated with an unrelated session.
  assert.equal(context.repairState('normal').view, null);
});

test('an old read cannot overwrite a newer mutation response and edits in flight stay intact', async () => {
  const { context } = environment(); await context.refreshSummaryRepair();
  const old = deferred(); context.api.summaryRepair = () => old.promise;
  const pending = context.refreshSummaryRepair(true);
  const local = context.repairState(sid); local.candidate.summary = 'new local'; context.repairChanged(sid);
  const newer = view(); newer.draft.draftRevision = 5; newer.draft.candidate.summary = 'new server';
  context.acceptSummaryRepairResponse(sid, newer);
  old.resolve(view()); await pending;
  assert.equal(local.view.draft.draftRevision, 5);
  assert.equal(local.candidate.summary, 'new local');
  assert.equal(local.dirty, true);
});

test('failed edits retain input and commit conflict exposes latest state without overriding it', async () => {
  const { context, slot } = environment(); await context.refreshSummaryRepair();
  const local = context.repairState(sid); local.candidate.summary = 'keep my draft'; context.repairChanged(sid);
  context.api.summaryRepair = async () => { throw Object.assign(new Error('revision conflict'), { status: 409 }); };
  await context.repairAction(sid, 'validate');
  assert.equal(local.candidate.summary, 'keep my draft'); assert.equal(local.dirty, true); assert.equal(local.conflict, true);
  assert.ok(button(slot, 'repair.reload'));
  const latest = view(); latest.target.revision = 4; latest.currentEntry = { summary: 'Already published result' };
  context.api.summaryRepair = async () => latest;
  await context.refreshSummaryRepair(true);
  assert.equal(local.candidate.summary, 'keep my draft'); assert.equal(local.conflict, true);
  assert.equal(button(slot, 'repair.save').disabled, true);
  assert.ok(walk(slot).some(item => item.textContent === 'Already published result'));
});

test('only a valid authoritative draft can commit and duplicate activation is suppressed', async () => {
  const { context, calls } = environment(); await context.refreshSummaryRepair();
  await context.repairAction(sid, 'commit'); assert.equal(calls.length, 1);
  context.repairState(sid).view.draft.state = 'valid';
  const pending = deferred(); context.api.summaryRepair = () => { calls.push({ path: '/commit' }); return pending.promise; };
  const first = context.repairAction(sid, 'commit'); await flush();
  await context.repairAction(sid, 'commit'); assert.equal(calls.filter(item => item.path === '/commit').length, 1);
  const saved = view(); saved.draft.state = 'committed'; saved.draft.committedRevision = 4; pending.resolve(saved); await first;
});

test('repair buildChatContext excludes unrelated current status and keeps the exact target on every turn', () => {
  const { context, session } = environment();
  vm.runInContext(read('chat.js'), context);
  context.state.chatContextToggles = { currentStatus: true, futureTasks: true };
  context.state.summary = { current: { headline: 'Unrelated current activity' } };
  const first = clone(context.buildChatContext(session)); const next = clone(context.buildChatContext(session));
  assert.deepEqual(first, next); assert.equal(first.type, 'summary_repair'); assert.equal(first.targetId, 'hour-1');
  assert.equal(first.currentStatus, undefined); assert.equal(first.recentActivity, undefined);
});

test('repair API method uses bounded local routes and ordinary JSON error semantics', () => {
  assert.match(read('api.js'), /summaryRepair:\s*function \(path, method, body\)/);
  assert.match(read('api.js'), /\/desktop\/summary-repairs/);
  assert.match(read('index.html'), /summary-repair\.js/);
});

test('explicit rebase sends unsaved edits directly and accepts a new target version without old confirmation', async () => {
  const { context, calls } = environment(); await context.refreshSummaryRepair();
  const local = context.repairState(sid); local.candidate.summary = 'Keep my unsaved rewrite'; context.repairChanged(sid); local.conflict = true;
  context.api.summaryRepair = async (path, method, body) => {
    calls.push({ path, method, body: clone(body) });
    assert.ok(path.endsWith('/refresh')); // Never PUT against the stale target first.
    const result = view(); result.target.revision = 9; result.draft.targetRevision = 9; result.draft.draftRevision = 2;
    result.draft.candidate = clone(body.candidate); return result;
  };
  await context.repairAction(sid, 'refresh');
  assert.equal(local.candidate.summary, 'Keep my unsaved rewrite'); assert.equal(local.dirty, false);
  assert.equal(local.view.draft.targetRevision, 9); assert.equal(local.conflict, false);
  assert.equal(local.view.draft.statements.length, 0); assert.equal(local.view.draft.state, 'unvalidated');
});

test('sequential task confirmations always use the returned server revision', async () => {
  const initial = view(); initial.draft.candidate.taskSegments = [0, 1].map(index => ({ title: 'Task ' + index, summary: 'Reviewed item ' + index, claimType: 'user_confirmed', evidenceFactIds: [], apps: [], evidence: [], confidence: 'medium' }));
  const { context, calls } = environment({ response: initial }); await context.refreshSummaryRepair();
  await context.repairAction(sid, 'confirm', [0]); await context.repairAction(sid, 'confirm', [1]);
  const confirmations = calls.filter(call => call.path.endsWith('/confirm'));
  assert.equal(confirmations[0].body.draftRevision, 0); assert.equal(confirmations[1].body.draftRevision, 1);
  assert.equal(context.repairState(sid).view.draft.draftRevision, 2);
});

test('synthetic screenshot runner is opt-in, uses the existing browser and writes only ignored artifacts', () => {
  const script = fs.readFileSync(new URL('../../../../scripts/check-summary-repair-ui.mjs', import.meta.url), 'utf8');
  assert.match(script, /process.argv.includes\('--run'\)/);
  assert.match(script, /Microsoft\/Edge\/Application\/msedge.exe/);
  assert.match(script, /artifacts\/summary-repair-ui/);
  assert.match(script, /modelCalls: 0/);
  assert.doesNotMatch(script, /npm install|playwright install|https:\/\//);
});

test('autosaving does not lose the focused field or its caret', async () => {
  const { context, slot } = environment(); await context.refreshSummaryRepair();
  const focused = input(slot, 'repair.summary'); focused.value = 'Continue typing'; focused.selectionStart = 8; focused.selectionEnd = 8;
  context.document.activeElement = focused;
  slot.contains = item => walk(slot).includes(item);
  slot.querySelectorAll = () => walk(slot).filter(item => item.attrs['data-repair-field']);
  let restored, selection;
  context.document.createElement = tag => {
    const item = node(tag); item.focus = () => { restored = item; };
    item.setSelectionRange = (start, end) => { selection = [start, end]; }; return item;
  };
  focused.oninput(); await context.repairAction(sid, 'draft');
  assert.equal(restored.value, 'Continue typing'); assert.deepEqual(selection, [8, 8]);
  assert.equal(restored.attrs['data-repair-field'], 'summary');
});

test('a pre-confirmation validation response cannot remove a newer explicit confirmation', async () => {
  const { context } = environment(); await context.refreshSummaryRepair();
  const confirmed = view(); confirmed.draft.draftRevision = 2; confirmed.draft.statements = [{ id: 'user_statement:a', taskIndex: 0, draftRevision: 2, confirmedAt: '2026-10-13T08:00:00Z' }];
  context.acceptSummaryRepairResponse(sid, confirmed);
  const oldValidated = view(); oldValidated.draft.draftRevision = 1; oldValidated.draft.state = 'valid';
  assert.equal(context.acceptSummaryRepairResponse(sid, oldValidated), false);
  assert.equal(context.repairState(sid).view.draft.statements.length, 1);
  assert.equal(context.repairState(sid).view.draft.state, 'unvalidated');
});
