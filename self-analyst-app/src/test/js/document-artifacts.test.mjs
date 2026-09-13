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
