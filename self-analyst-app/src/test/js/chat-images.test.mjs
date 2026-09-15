import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
const ui = new URL('../../main/resources/desktop-ui/', import.meta.url);
function setup() {
  const revoked = [], warnings = [], uploads = [];
  const session = { id: 'a'.repeat(32), messagesLoaded: true, messages: [] };
  const box = vm.createContext({
    console, Promise, Date, state: { activeChatSessionId: session.id, chatSending: false, dom: { chatTabInput: { value: '' } } },
    api: {}, API_BASE: '', document: { getElementById() { return null; } },
    URL: { createObjectURL(f) { return 'blob:' + f.name; }, revokeObjectURL(url) { revoked.push(url); } },
    Image: class { naturalWidth = 3; naturalHeight = 2; set src(value) { queueMicrotask(() => this.onload()); } },
    alert(message) { warnings.push(message); }, t(key) { return key; }, escHtml: String,
  });
  vm.runInContext(fs.readFileSync(new URL('chat-images.js', ui), 'utf8'), box);
  box.api.uploadChatImage = async (id, file) => { uploads.push([id, file.name]); return { id: file.name }; };
  box.api.deleteChatImage = async () => {};
  return { box, revoked, warnings, uploads, session };
}
const file = (name) => ({ name, type: 'image/png', size: 100 });
test('image drafts stay with their original session, enforce limits and release previews', async () => {
  const { box, revoked, warnings } = setup();
  await box.addChatImages([file('one')]);
  box.state.activeChatSessionId = 'b'.repeat(32);
  assert.equal(box.chatHasImages(), false);
  await box.addChatImages([file('two')]);
  box.clearChatImages('a'.repeat(32), true);
  assert.deepEqual(revoked, ['blob:one']);
  assert.equal(box.chatImageDraft()[0].file.name, 'two');
  await box.addChatImages([{ ...file('large'), size: 6 * 1024 * 1024 }]);
  assert.equal(warnings.length, 1);
  assert.equal(box.chatImageDraft().length, 1);
});
test('partial upload retry reuses successful IDs and keeps the complete draft', async () => {
  const { box } = setup();
  await box.addChatImages([file('one'), file('two')]);
  let calls = 0;
  box.api.uploadChatImage = async () => { if (++calls === 2) throw Error('offline'); return { id: 'id-' + calls }; };
  const draft = box.chatImageDraft();
  await assert.rejects(box.uploadChatImages('a'.repeat(32), draft));
  assert.equal(draft.length, 2);
  const result = await box.uploadChatImages('a'.repeat(32), draft);
  assert.deepEqual(Array.from(result), ['id-1', 'id-3']);
  assert.equal(calls, 3);
});
test('only server-owned image routes are rendered', () => {
  const { box } = setup();
  assert.equal(box.chatImageHtml({ images: [{ url: 'javascript:evil()' }, { url: 'https://remote/img' }] }), '');
  assert.match(box.chatImageHtml({ images: [{ url: '/desktop/chat/sessions/' + 'a'.repeat(32) + '/images/' + 'b'.repeat(32) }] }), /<img/);
});
test('failed first send keeps its image draft under the newly created session', async () => {
  const { box, session } = setup();
  vm.runInContext(fs.readFileSync(new URL('chat.js', ui), 'utf8'), box);
  box.state.activeChatSessionId = null;
  Object.assign(box, {
    renderChatTab() {}, invalidateChatSessionLoads() {},
    ensureActiveChatSession: async () => { box.state.activeChatSessionId = session.id; return session; },
    ensureSessionMessagesLoaded: async () => session, buildChatContext: () => null,
    refreshChatSessionListIfNeeded: async () => {},
  });
  await box.addChatImages([file('one')]);
  box.api.appendMessages = async () => { throw Error('disk full'); };
  const result = await box.sendChatTabMessage();
  assert.equal(result.inputPersisted, false);
  assert.equal(box.chatImageDraft().length, 1);
  assert.equal(box.chatImageDraft()[0].file.name, 'one');
  assert.equal(box.chatImageDraft('new').length, 0);
});
test('image-only sends upload before append and clear only the originating draft', async () => {
  const { box, session } = setup();
  vm.runInContext(fs.readFileSync(new URL('chat.js', ui), 'utf8'), box);
  Object.assign(box, {
    renderChatTab() {}, invalidateChatSessionLoads() {}, noteChatSessionMutation() {}, resortChatSessions() {},
    ensureActiveChatSession: async () => session, ensureSessionMessagesLoaded: async () => session,
    buildChatContext: () => null, syncSessionMessageCache() {}, backfillSessionTitle() {},
    refreshCanonicalSession: async () => session, refreshChatSessionListIfNeeded: async () => {},
    findSessionMessage: () => null,
  });
  await box.addChatImages([file('one')]);
  let appended;
  box.api.appendMessages = async (id, payload) => {
    appended = payload.messages;
    box.state.activeChatSessionId = 'b'.repeat(32);
    box.chatImageDrafts[box.state.activeChatSessionId] = [{ file: file('other'), url: 'blob:other' }];
    return payload.messages.map((m, i) => ({ ...m, id: 'id-' + i }));
  };
  box.api.postChat = async () => ({ response: 'done' });
  box.api.updateMessage = async (id, mid, m) => ({ ...m, id: mid });
  box.requireChatExecutionResponse = async () => ({ payload: {}, content: 'done' });
  const result = await box.sendChatTabMessage();
  assert.equal(result.inputPersisted, true);
  assert.equal(appended[0].content, '');
  assert.deepEqual(Array.from(appended[0].imageIds), ['one']);
  assert.equal(box.chatImageDraft('a'.repeat(32)).length, 0);
  assert.equal(box.chatImageDraft('b'.repeat(32)).length, 1);
});
