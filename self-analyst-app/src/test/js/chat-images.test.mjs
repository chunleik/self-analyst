import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
const ui = new URL('../../main/resources/desktop-ui/', import.meta.url);
function setup(options = {}) {
  const revoked = [], warnings = [], uploads = [], listeners = {}, pendingImages = [];
  let composerUpdates = 0;
  const session = { id: 'a'.repeat(32), messagesLoaded: true, messages: [] };
  const box = vm.createContext({
    console, Promise, Date, state: { tab: 'chat', activeChatSessionId: session.id, chatSending: false, dom: { chatTabInput: { value: '' } } },
    api: {}, API_BASE: '', document: { getElementById() { return null; }, addEventListener(name, handler) { (listeners[name] ||= []).push(handler); } },
    updateChatComposerState() { composerUpdates++; },
    URL: { createObjectURL(f) { return 'blob:' + f.name; }, revokeObjectURL(url) { revoked.push(url); } },
    Image: class { naturalWidth = 3; naturalHeight = 2; set src(value) { if (options.deferred) pendingImages.push(this); else queueMicrotask(() => this.onload()); } },
    alert(message) { warnings.push(message); }, t(key) { return key; }, escHtml: String,
  });
  vm.runInContext(fs.readFileSync(new URL('chat-images.js', ui), 'utf8'), box);
  box.api.uploadChatImage = async (id, file) => { uploads.push([id, file.name]); return { id: file.name }; };
  box.api.deleteChatImage = async () => {};
  return { box, revoked, warnings, uploads, session, listeners, pendingImages, composerUpdates: () => composerUpdates };
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
  assert.equal(warnings.length, 0);
  assert.match(box.chatImageErrors[box.chatImageKey()], /chat.imageLimits/);
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

test('mixed imports retain valid pictures, reject unsupported types and release invalid previews', async () => {
  const { box, revoked } = setup();
  await box.addChatImages([file('existing')]);
  await box.addChatImages([{ ...file('bad'), type: 'text/html' }, file('next')]);
  assert.deepEqual(Array.from(box.chatImageDraft(), (item) => item.file.name), ['existing', 'next']);
  assert.match(box.chatImageErrors[box.chatImageKey()], /chat.imageDropInvalid/);
  box.Image = class { naturalWidth = 5001; naturalHeight = 4000; set src(value) { queueMicrotask(() => this.onload()); } };
  await box.addChatImages([file('pixels')]);
  assert.deepEqual(revoked, ['blob:pixels']);
  assert.equal(box.chatImageDraft().length, 2);
  box.Image = class { set src(value) { queueMicrotask(() => this.onerror()); } };
  await box.addChatImages([file('broken')]);
  assert.deepEqual(revoked, ['blob:pixels', 'blob:broken']);
  assert.match(box.chatImageErrors[box.chatImageKey()], /chat.imageInvalid/);
  assert.equal(box.chatImageLoading, 0);
});

test('size boundaries and serial imports enforce four image and total byte budgets', async () => {
  const { box } = setup();
  await box.addChatImages([{ ...file('empty'), size: 0 }, { ...file('huge'), size: 5 * 1024 * 1024 + 1 }]);
  assert.equal(box.chatImageDraft().length, 0);
  const max = (name) => ({ ...file(name), size: 5 * 1024 * 1024 });
  await Promise.all([box.addChatImages([max('1'), max('2'), max('3')]), box.addChatImages([max('4'), max('5')])]);
  assert.equal(box.chatImageDraft().length, 4);
  assert.equal(box.chatImageDraft().reduce((total, item) => total + item.file.size, 0), 20 * 1024 * 1024);
  assert.match(box.chatImageErrors[box.chatImageKey()], /chat.imageLimits/);
  assert.equal(box.chatImageLoading, 0);
});

test('decode result remains with the originating session after switching', async () => {
  const { box, pendingImages, composerUpdates } = setup({ deferred: true });
  const key = box.chatImageKey();
  const adding = box.addChatImages([file('slow')]);
  await Promise.resolve();
  assert.equal(box.chatImageLoading, 1);
  box.state.activeChatSessionId = 'b'.repeat(32);
  pendingImages[0].onload();
  await adding;
  assert.equal(box.chatHasImages(), false);
  assert.equal(box.chatImageDraft(key)[0].file.name, 'slow');
  assert.equal(box.chatImageLoading, 0);
  assert.ok(composerUpdates() >= 2);
});

test('clearing a decoding draft invalidates pending and queued imports without resurrection', async () => {
  const { box, pendingImages, revoked } = setup({ deferred: true });
  const key = box.chatImageKey();
  const first = box.addChatImages([file('slow')]);
  const second = box.addChatImages([file('queued')]);
  await Promise.resolve();
  box.clearChatImages(key, false);
  pendingImages[0].onload();
  await Promise.all([first, second]);
  assert.equal(box.chatImageDraft(key).length, 0);
  assert.deepEqual(revoked, ['blob:slow']);
  assert.equal(pendingImages.length, 1);
  assert.equal(box.chatImageLoading, 0);
});

test('removing a picture invalidates an outstanding decode and releases both previews', async () => {
  const { box, pendingImages, revoked } = setup({ deferred: true });
  const first = box.addChatImages([file('first')]);
  await Promise.resolve();
  pendingImages[0].onload();
  await first;
  const second = box.addChatImages([file('slow')]);
  await Promise.resolve();
  box.removeChatImage(box.chatImageKey(), 0);
  pendingImages[1].onload();
  await second;
  assert.equal(box.chatHasImages(), false);
  assert.deepEqual(revoked, ['blob:first', 'blob:slow']);
});

function inputEvent(id, transfer) {
  return {
    target: { id }, composedPath() { return [this.target]; },
    dataTransfer: transfer, clipboardData: transfer, prevented: false, stopped: false,
    preventDefault() { this.prevented = true; },
    stopPropagation() { this.stopped = true; }, stopImmediatePropagation() { this.stopped = true; },
  };
}

test('paste and drop bind once and accept files only in their intended input surfaces', async () => {
  const { box, listeners } = setup();
  box.renderChatImages(); box.renderChatImages();
  assert.equal(listeners.paste.length, 1);
  assert.equal(listeners.drop.length, 1);
  assert.equal(listeners.keydown, undefined, 'shared composer owns Enter, not image module');
  const outside = inputEvent('elsewhere', { files: [file('outside')], types: ['Files'] });
  listeners.drop[0](outside);
  assert.equal(outside.prevented, false);
  const drop = inputEvent('chat-composer', { files: [file('dropped')], types: ['Files'] });
  listeners.dragover[0](drop);
  assert.equal(drop.dataTransfer.dropEffect, 'copy');
  listeners.drop[0](drop);
  await box.chatImageQueues[box.chatImageKey()];
  assert.equal(drop.prevented, true);
  const paste = inputEvent('chat-tab-input', { files: [file('pasted')] });
  listeners.paste[0](paste);
  await box.chatImageQueues[box.chatImageKey()];
  assert.equal(paste.prevented, true);
  assert.deepEqual(Array.from(box.chatImageDraft(), (item) => item.file.name), ['dropped', 'pasted']);
  const text = inputEvent('chat-tab-input', { files: [], types: ['text/plain'] });
  listeners.paste[0](text);
  assert.equal(text.prevented, false, 'normal text paste remains native');
});

test('drop rejects directories and remote links without navigation or automatic imports', () => {
  const { box, listeners } = setup();
  box.bindChatImageInputs();
  const directory = inputEvent('chat-composer', {
    files: [file('fake-directory')], types: ['Files'],
    items: [{ webkitGetAsEntry() { return { isDirectory: true }; } }],
  });
  listeners.drop[0](directory);
  assert.equal(directory.prevented, true);
  assert.equal(box.chatHasImages(), false);
  assert.match(box.chatImageErrors[box.chatImageKey()], /chat.imageDropInvalid/);
  const remote = inputEvent('chat-composer', { files: [], types: ['text/uri-list'] });
  listeners.drop[0](remote);
  assert.equal(remote.prevented, true);
  assert.equal(box.chatHasImages(), false);
  box.state.chatSending = true;
  const busy = inputEvent('chat-composer', { files: [file('busy')], types: ['Files'] });
  listeners.drop[0](busy);
  assert.equal(busy.prevented, true);
  assert.equal(box.chatHasImages(), false);
});

function installBar(box) {
  let html = '', renders = 0;
  const elements = {};
  const control = (name) => ({ focus() { box.document.activeElement = this; }, click() { this.clicked = true; }, name });
  const input = control('input'), add = control('add'), help = control('details'), summary = control('summary');
  elements.input = input; elements['[data-add-image]'] = add; elements.details = help; elements.summary = summary;
  const bar = {
    get innerHTML() { return html; },
    set innerHTML(value) { html = value; renders++; },
    querySelector(selector) { return elements[selector] || null; }, querySelectorAll() { return []; },
  };
  const composer = control('composer');
  box.document.getElementById = (id) => id === 'chat-image-composer' ? bar : id === 'chat-tab-input' ? composer : null;
  return { bar, input, add, help, summary, composer, renders: () => renders };
}

test('integrated controls expose safe labels, inline errors, hidden picker, and no duplicate send', async () => {
  const { box } = setup();
  box.escHtml = (value) => String(value).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;');
  const { bar, input, add, composer } = installBar(box);
  await box.addChatImages([file('one')]);
  assert.match(bar.innerHTML, /type="file"[^>]*hidden/);
  assert.match(bar.innerHTML, /aria-label="chat.addImage"/);
  assert.match(bar.innerHTML, /aria-label="chat.removeImage 1"/);
  assert.doesNotMatch(bar.innerHTML, /data-send-images/);
  box.chatImageErrors[box.chatImageKey()] = '<script>bad</script>';
  box.renderChatImages();
  assert.match(bar.innerHTML, /&lt;script&gt;/);
  add.onclick();
  assert.equal(input.clicked, true);
  input.oncancel();
  assert.equal(box.document.activeElement, composer);
  assert.equal(box.chatImageDraft().length, 1);
});

test('streaming renders retain help state and focus without replacing unchanged controls', () => {
  const { box } = setup();
  const { help, summary, renders } = installBar(box);
  box.renderChatImages();
  help.open = true;
  summary.focus();
  const count = renders();
  box.renderChatImages(); box.renderChatImages();
  assert.equal(renders(), count);
  assert.equal(box.document.activeElement, summary);
  box.state.chatSending = true;
  box.renderChatImages();
  assert.equal(help.open, true);
  assert.equal(box.document.activeElement, summary);
});
