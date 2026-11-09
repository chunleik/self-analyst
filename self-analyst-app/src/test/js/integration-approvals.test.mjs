import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const base = new URL('../../main/resources/desktop-ui/', import.meta.url);
const source = fs.readFileSync(new URL('integration-approvals.js', base), 'utf8');
const integrationSource = fs.readFileSync(new URL('integrations.js', base), 'utf8');
const plain = v => JSON.parse(JSON.stringify(v));
const deferred = () => { let resolve, reject; const promise = new Promise((ok, fail) => { resolve = ok; reject = fail; }); return { promise, resolve, reject }; };
const item = extra => ({ id: 'approval-one', sessionId: 'session-a', turnId: 'user-message-a', kind: 'mcp-call',
  target: 'https://mcp.example/tools', toolName: 'lookup', arguments: { arguments: { query: 'hello' }, definitionDigest: 'digest', revision: 2 }, expiresAt: Date.now() + 10000, ...extra });
function setup(handler, options = { sessionId: 'session-a', turnId: 'user-message-a' }) {
  const calls = [], timers = new Map(); let next = 0;
  const context = vm.createContext({ AbortController, Date, t: key => key, setTimeout: fn => { timers.set(++next, fn); return next; }, clearTimeout: key => timers.delete(key) });
  vm.runInContext(integrationSource, context); vm.runInContext(source, context);
  const request = (...args) => { calls.push(args); return handler?.(...args) ?? Promise.resolve({ approvals: [item()] }); };
  return { model: context.createIntegrationApprovals(request, () => {}, options), context, calls, timers, request };
}

test('only authenticated endpoint supplies approvals; filter exact session/turn and expiry and duplicate IDs', async () => {
  const rows = [item(), item(), item({ id: 'other-session', sessionId: 'other' }), item({ id: 'other-turn', turnId: 'other' }),
    item({ id: 'expired', expiresAt: Date.now() - 1 }), item({ id: 'invalid-expiry', expiresAt: 'invalid' })];
  const h = setup(() => Promise.resolve({ approvals: rows })); await h.model.refresh();
  assert.equal(h.calls[0][0], '/approvals?sessionId=session-a'); assert.deepEqual(h.model.approvals.map(v => v.id), ['approval-one']);
  assert.doesNotMatch(source, /parseChatSseFrame|parseStructuredChatContent|onDelta|innerHTML/);
});

test('decisions send server-issued binding once; duplicate click and stale server replay cannot resubmit', async () => {
  const pending = deferred(); const h = setup((path, method) => method === 'POST' ? pending.promise : undefined);
  await h.model.refresh(); const approved = h.model.decide('approval-one', 'approve'); await h.model.decide('approval-one', 'approve');
  assert.equal(h.calls.filter(c => c[1] === 'POST').length, 1);
  assert.deepEqual(plain(h.calls.at(-1)[2]), { decision: 'approve', sessionId: 'session-a', turnId: 'user-message-a' });
  pending.resolve({ ok: true }); await approved; await h.model.refresh();
  assert.equal(h.model.approvals.length, 0); await h.model.decide('approval-one', 'approve');
  assert.equal(h.calls.filter(c => c[1] === 'POST').length, 1);
});

test('uncertain decision response never auto-retries or reopens the same one-time request', async () => {
  const h = setup((path, method) => method === 'POST' ? Promise.reject({ code: 'timeout', message: 'secret remote error' }) : undefined);
  await h.model.refresh(); await h.model.decide('approval-one', 'approve');
  assert.equal(h.model.error, 'integrations.error.timeout'); await h.model.refresh();
  assert.equal(h.model.approvals.length, 0); assert.equal(h.calls.filter(c => c[1] === 'POST').length, 1);
});

test('expired, invalid, and unknown approval IDs cannot be accepted', async () => {
  const h = setup(); await h.model.refresh(); h.model.approvals[0].expiresAt = Date.now() - 1;
  await h.model.decide('approval-one', 'approve'); await h.model.decide('unknown', 'approve'); await h.model.decide('approval-one', 'arbitrary');
  assert.equal(h.calls.filter(c => c[1] === 'POST').length, 0);
});

test('closing rejects pending records, cancels polling, and ignores a late server response', async () => {
  const pending = deferred(); let delayed = false;
  const h = setup((path, method) => method !== 'POST' && delayed ? pending.promise : undefined);
  await h.model.refresh(); delayed = true; const refresh = h.model.refresh(); const signal = h.calls.at(-1)[3];
  h.model.destroy(true); assert.equal(signal.aborted, true); assert.equal(h.timers.size, 0);
  assert.deepEqual(plain(h.calls.at(-1)[2]), { decision: 'reject', sessionId: 'session-a', turnId: 'user-message-a' });
  pending.resolve({ approvals: [item({ id: 'late' })] }); await refresh; assert.equal(h.model.approvals.length, 0);
  h.model.destroy(true); assert.equal(h.calls.filter(c => c[1] === 'POST').length, 1);
});

test('approval polling is serialized and a cancelled old poll cannot resurrect a decided row', async () => {
  const pending = deferred(); let delayed = false;
  const h = setup((path, method) => method !== 'POST' && delayed ? pending.promise : undefined);
  await h.model.refresh(); delayed = true; const refresh = h.model.refresh(); await h.model.refresh();
  assert.equal(h.calls.filter(c => c[1] === 'GET').length, 2);
  await h.model.decide('approval-one', 'reject'); pending.resolve({ approvals: [item()] }); await refresh;
  assert.equal(h.model.approvals.length, 0);
});

function dom() {
  const document = { activeElement: null };
  class Element {
    constructor(tag) { this.tagName = tag.toUpperCase(); this.children = []; this.dataset = {}; this.attributes = {}; this.events = {}; this.hidden = false; this.disabled = false; this.textContent = ''; }
    appendChild(child) { this.children.push(child); return child; }
    replaceChildren(...children) { this.children = children; }
    setAttribute(key, value) { this.attributes[key] = value; }
    addEventListener(key, handler) { this.events[key] = handler; }
    removeEventListener(key) { delete this.events[key]; }
    querySelectorAll(selector) { return this.children.flatMap(child => [...(child.tagName === selector.toUpperCase() ? [child] : []), ...child.querySelectorAll(selector)]); }
    focus() { document.activeElement = this; }
  }
  document.createElement = tag => new Element(tag);
  return { document, Element };
}
const tick = async () => { for (let i = 0; i < 8; i++) await Promise.resolve(); };

test('approval renderer displays hostile HTML as text, keeps focused buttons, rejects with Escape, and supports reopening', async () => {
  const fake = dom(); const root = new fake.Element('section'); const h = setup(() => Promise.resolve({ approvals: [item({
    target: '<img src=x onerror=alert(1)>', toolName: '<script>alert(1)</script>', arguments: { html: '<svg/onload=alert(1)>' }
  })] }));
  h.context.document = fake.document; h.context.api = { integrations: h.request };
  let view = h.context.mountIntegrationApprovals(root, { sessionId: 'session-a', turnId: 'user-message-a' }); await tick();
  const allText = node => node.textContent + node.children.map(allText).join('');
  assert.match(allText(root), /<img src=x/); assert.match(allText(root), /<svg\/onload/);
  assert.equal(root.querySelectorAll('img').length, 0); assert.equal(root.querySelectorAll('script').length, 0);
  const approve = root.querySelectorAll('button').find(button => button.dataset.decision === 'approve'); approve.focus();
  await view.model.refresh(); assert.equal(fake.document.activeElement.dataset.approvalId, approve.dataset.approvalId);
  assert.equal(fake.document.activeElement.dataset.decision, 'approve', 'poll must preserve keyboard focus on the same action');
  let prevented = false; root.events.keydown({ key: 'Escape', preventDefault() { prevented = true; }, stopPropagation() {} }); await tick();
  assert.equal(prevented, true); assert.equal(h.calls.filter(c => c[1] === 'POST').at(-1)[2].decision, 'reject');
  view.destroy(true); assert.equal(root.hidden, true);
  view = h.context.mountIntegrationApprovals(root, { sessionId: 'other', turnId: 'other' }); await tick();
  assert.equal(view.model.approvals.length, 0); view.destroy(true);
});

test('oversized arguments are never silently approved; model recipient cards use same bound endpoint', async () => {
  const fake = dom(); const root = new fake.Element('section'); const h = setup(() => Promise.resolve({ approvals: [item({
    kind: 'mail-recipient', arguments: 'x'.repeat(65537)
  })] })); h.context.document = fake.document; h.context.api = { integrations: h.request };
  const view = h.context.mountIntegrationApprovals(root, { sessionId: 'session-a', turnId: 'user-message-a' }); await tick();
  assert.equal(root.querySelectorAll('button').find(button => button.dataset.decision === 'approve').disabled, true);
  assert.equal(root.querySelectorAll('pre')[0].textContent, 'integrations.approvals.tooLarge'); view.destroy(true);
});
