import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const code = fs.readFileSync(new URL('../../main/resources/desktop-ui/documents.js', import.meta.url), 'utf8');
function environment() {
  function node() { return { children: [], appendChild(n) { this.children.push(n); }, replaceChildren(...items) { this.children = items; }, setAttribute() {}, click() {}, remove() {} }; }
  const panel = node(), requests = [];
  const context = { Map, Set, Promise, Date, console, API_BASE: '', t: key => key,
    state: { activeChatSessionId: 'a'.repeat(32), chatSending: false }, window: {},
    document: { getElementById: () => panel, createElement: node, body: node() },
    setTimeout: () => 1, clearTimeout() {},
    fetch: url => new Promise(resolve => requests.push({ url, resolve })),
    chatJsonResponse: response => response.json()
  };
  vm.createContext(context); vm.runInContext(code, context);
  return { context, panel, requests };
}
const artifact = (sessionId, id = '1'.repeat(32)) => ({ sessionId, id, name: '<script>unsafe</script>.pdf', status: 'READY', format: 'PDF', size: 100, version: 1 });

test('HTML and SVG remain download-only cards with literal filenames', async () => {
  for (const format of ['HTML', 'SVG']) {
    const { context, panel } = environment();
    const name = '<svg onload="alert(1)">.' + format.toLowerCase();
    context.documentView.documents = [{ ...artifact(context.state.activeChatSessionId), format, name }];
    context.renderDocumentCards(panel);
    assert.equal(panel.children[0].children[0].textContent, name);
    assert.equal(panel.children[0].children[0].innerHTML, undefined);
    assert.match(panel.children[0].children[1].textContent, new RegExp('^' + format));
    assert.equal(context.document.body.children.length, 0);
    panel.children[0].children[2].onclick();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(context.document.body.children[0].download, name);
    assert.match(context.document.body.children[0].href, /\/content$/);
    assert.equal(context.document.body.children[0].target, undefined);
  }
});

test('document references are constrained and versions deduplicate by server id', () => {
  const { context } = environment();
  assert.throws(() => context.documentContentUrl('../secret', 'x'));
  assert.equal(context.documentContentUrl('a'.repeat(32), '1'.repeat(32)), '/desktop/chat/sessions/' + 'a'.repeat(32) + '/documents/' + '1'.repeat(32) + '/content');
  const first = artifact('a'.repeat(32));
  assert.equal(context.documentMerge([first], [first]).length, 1);
});

test('late results from another session cannot replace active file cards', async () => {
  const { context, requests, panel } = environment();
  const a = context.refreshChatDocuments(true);
  context.state.activeChatSessionId = 'b'.repeat(32);
  const b = context.refreshChatDocuments(true);
  requests[1].resolve({ json: async () => ({ documents: [artifact('b'.repeat(32))], nextOffset: 1 }) }); await b;
  requests[0].resolve({ json: async () => ({ documents: [artifact('a'.repeat(32), '2'.repeat(32))], nextOffset: 1 }) }); await a;
  assert.equal(context.documentView.documents[0].sessionId, 'b'.repeat(32));
  assert.equal(panel.children[0].children[0].textContent, '<script>unsafe</script>.pdf');
  assert.equal(panel.children[0].children[0].innerHTML, undefined);
});

test('native cancel and failure retain the file and restore save button', async () => {
  const { context, panel } = environment();
  context.window.__TAURI__ = { core: { invoke: async () => 'cancelled' } };
  context.documentView.documents = [artifact('a'.repeat(32))]; context.renderDocumentCards(panel);
  const button = panel.children[0].children[2], status = panel.children[0].children[3];
  button.onclick(); await new Promise(resolve => setImmediate(resolve));
  assert.equal(status.textContent, 'document.cancelled'); assert.equal(button.disabled, false);
  context.window.__TAURI__.core.invoke = async () => { throw new Error('disk'); };
  button.onclick(); await new Promise(resolve => setImmediate(resolve));
  assert.equal(status.textContent, 'document.saveFailed'); assert.equal(context.documentView.documents.length, 1);
});

test('pagination retains older files after refresh and retry restores the list', async () => {
  const { context, requests, panel } = environment();
  const sid = context.state.activeChatSessionId;
  async function page(more, documents, nextOffset, hasMore) {
    const pending = context.refreshChatDocuments(true, more);
    requests.at(-1).resolve({ json: async () => ({ documents, nextOffset, hasMore }) });
    await pending;
  }
  await page(false, [artifact(sid)], 1, true);
  await page(true, [artifact(sid, '2'.repeat(32))], 2, false);
  await page(false, [artifact(sid)], 1, true);
  assert.equal(context.documentView.documents.length, 2);
  assert.equal(context.documentView.offset, 2);
  assert.equal(context.documentView.hasMore, false);
  const failed = context.refreshChatDocuments(true);
  requests.at(-1).resolve({ json: async () => { throw new Error('offline'); } }); await failed;
  assert.equal(panel.children[0].textContent, 'document.loadFailed');
  await page(false, [artifact(sid)], 1, true);
  assert.equal(panel.children.length, 2);
});

test('inline cards keep turn ownership and do not reset during unchanged polling', () => {
  const { context } = environment();
  const sid = context.state.activeChatSessionId;
  const session = { id: sid, messages: [
    { id: 'u1', role: 'user' }, { id: 'a1', role: 'assistant', status: 'error' },
    { id: 'u2', role: 'user' }, { id: 'a2', role: 'assistant', status: 'pending' }
  ] };
  context.getActiveChatSession = () => session;
  const slots = ['u1', 'u2'].map(turn => ({
    children: [], getAttribute: () => turn,
    replaceChildren() { this.children = []; }, appendChild(child) { this.children.push(child); }
  }));
  context.state.dom = { chatThread: { querySelectorAll: () => slots } };
  context.documentView.sessionId = sid;
  context.documentView.documents = [
    { ...artifact(sid), userMessageId: 'u1' },
    { ...artifact(sid, '2'.repeat(32)), userMessageId: 'u2', status: 'RUNNING' },
    { ...artifact(sid, '3'.repeat(32)), userMessageId: 'u2' },
    { ...artifact(sid, '4'.repeat(32)), userMessageId: 'trimmed' }
  ];
  assert.equal(context.documentTurnId(session, 0), null);
  assert.equal(context.documentTurnId(session, 3), 'u2');
  context.mountChatDocuments();
  assert.equal(slots[0].children.length, 1);
  assert.equal(slots[1].children.length, 2);
  const card = slots[0].children[0];
  context.mountChatDocuments();
  assert.equal(slots[0].children[0], card);
  assert.equal(slots[1].children[0].children[1].textContent, 'document.status.RUNNING');
});

test('saving feedback survives remount and browser download uses the protected route', async () => {
  const { context, panel } = environment();
  context.documentView.documents = [artifact(context.state.activeChatSessionId)];
  context.renderDocumentCards(panel);
  panel.children[0].children[2].onclick();
  await new Promise(resolve => setImmediate(resolve));
  panel.documentSignature = null; context.renderDocumentCards(panel);
  assert.equal(panel.children[0].children[3].textContent, 'document.downloadStarted');
  assert.match(context.document.body.children[0].href, /^\/desktop\/chat\/sessions\/[a-f0-9]+\/documents\/[a-f0-9]+\/content$/);
});

test('live attachments follow the streaming bubble without replacing message text', () => {
  const { context } = environment();
  const sid = context.state.activeChatSessionId;
  const session = { id: sid, messages: [{ id: 'u1', role: 'user' }, { id: 'a1', role: 'assistant' }] };
  let slot, placedAfter;
  const user = { after(node) { slot = node; placedAfter = this; this.nextElementSibling = node; } };
  const assistant = { textContent: 'streamed text', after: user.after };
  let bubbles = [user];
  context.document.createElement = () => ({ children: [], attrs: {},
    setAttribute(k, v) { this.attrs[k] = v; }, getAttribute(k) { return this.attrs[k]; },
    replaceChildren() { this.children = []; }, appendChild(n) { this.children.push(n); }
  });
  context.deepChatElement = () => ({ shadowRoot: { querySelectorAll(selector) {
    return selector === '[data-document-turn]' ? (slot ? [slot] : []) : bubbles;
  } } });
  context.deepChatAdapterState = { requestSessionId: sid };
  context.getActiveChatSession = () => session;
  context.documentView.sessionId = sid;
  context.documentView.documents = [{ ...artifact(sid), userMessageId: 'u1' }];
  context.mountChatDocuments();
  assert.equal(placedAfter, user);
  const original = slot;
  bubbles = [user, assistant]; context.mountChatDocuments();
  assert.equal(slot, original);
  assert.equal(placedAfter, assistant);
  assert.equal(assistant.textContent, 'streamed text');
  assert.equal(slot.children.length, 1);
});
