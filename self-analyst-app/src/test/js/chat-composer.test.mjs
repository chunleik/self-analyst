import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const source = fs.readFileSync(new URL('../../main/resources/desktop-ui/chat-composer.js', import.meta.url), 'utf8');

function node() {
  const listeners = {};
  return {
    value: '', style: {}, scrollHeight: 72, attributes: {}, disabled: false, textContent: '',
    selectionStart: 2, selectionEnd: 2, focusCount: 0, listeners,
    setAttribute(name, value) { this.attributes[name] = value; },
    addEventListener(name, callback) { (listeners[name] ||= []).push(callback); },
    dispatch(name, event = {}) { for (const callback of listeners[name] || []) callback(event); },
    focus() { this.focusCount++; },
  };
}
function setup() {
  const input = node(), button = node(), status = node();
  const sessions = {
    a: { id: 'a', messagesLoaded: true, composerDraft: '', messages: [] },
    b: { id: 'b', messagesLoaded: true, composerDraft: 'draft b', messages: [] },
  };
  let imageCount = 0, renders = 0, now = 1000, finish;
  const calls = [], cancellations = [], windowListeners = {};
  const box = vm.createContext({
    Promise, AbortController, setTimeout, clearTimeout, console,
    Date: { now: () => now },
    state: { activeChatSessionId: 'a', chatSending: false, status: { llm: { configured: true } }, dom: { chatTabInput: input, chatTabSendBtn: button } },
    window: { innerHeight: 800, addEventListener(name, fn) { (windowListeners[name] ||= []).push(fn); } },
    document: { getElementById: id => id === 'chat-composer-status' ? status : null },
    t: (key, args) => args?.msg ? `${key}:${args.msg}` : key,
    formatChatErrorMessage: error => error?.message || String(error),
    getActiveChatSession() { return sessions[box.state.activeChatSessionId]; },
    chatHasImages: () => imageCount > 0, chatImageLoading: 0,
    renderChatTab() { renders++; box.updateChatComposerState(); },
    api: { cancelChat: async (session, user) => { cancellations.push([session, user]); } },
    sendChatTabMessage(request) {
      calls.push(request);
      box.state.chatSending = true;
      box.updateChatComposerState();
      return new Promise(resolve => { finish = outcome => { box.state.chatSending = false; resolve(outcome); }; });
    },
  });
  vm.runInContext(source, box);
  box.bindChatComposer();
  return {
    box, input, button, status, sessions, calls, cancellations, windowListeners,
    images(count) { imageCount = count; box.updateChatComposerState(); },
    finish(outcome = {}) { finish(outcome); },
    renders: () => renders,
    advance(ms) { now += ms; },
  };
}
function enter(input, extra = {}) {
  const event = { key: 'Enter', prevented: false, preventDefault() { this.prevented = true; }, ...extra };
  input.dispatch('keydown', event);
  return event;
}

 test('text grows to bounded height, scrolls internally and shrinks after clearing', () => {
  const { box, input } = setup();
  assert.equal(input.style.height, '72px');
  input.scrollHeight = 120;
  input.dispatch('input');
  assert.equal(input.style.height, '120px');
  input.scrollHeight = 400;
  input.dispatch('input');
  assert.equal(input.style.height, '200px');
  assert.equal(input.style.overflowY, 'auto');
  box.window.innerHeight = 600;
  box.resizeChatComposer();
  assert.equal(input.style.height, '150px');
  input.scrollHeight = 72;
  box.setChatComposerDraft('');
  assert.equal(input.style.height, '72px');
  assert.equal(input.style.overflowY, 'hidden');
});

test('empty and whitespace drafts cannot send while a valid image enables image-only send', async () => {
  const ctx = setup();
  ctx.box.setChatComposerDraft('  \n ');
  assert.equal(ctx.button.disabled, true);
  assert.equal((await ctx.box.submitChatComposer()).skipped, true);
  assert.equal(ctx.calls.length, 0);
  ctx.images(1);
  assert.equal(ctx.button.disabled, false);
  const send = ctx.box.submitChatComposer();
  assert.equal(ctx.calls.length, 1);
  assert.equal(ctx.calls[0].text, '  \n ');
  assert.equal(ctx.calls[0].streaming, true);
  assert.equal(ctx.calls[0].source, 'deep-chat');
  ctx.finish();
  await send;
});

test('binding is idempotent and state refresh never steals focus or selection', () => {
  const { box, input, button, windowListeners } = setup();
  box.bindChatComposer(); box.bindChatComposer();
  assert.equal(input.listeners.input.length, 1);
  assert.equal(input.listeners.keydown.length, 1);
  assert.equal(button.listeners.click.length, 1);
  assert.equal(windowListeners.resize.length, 1);
  box.setChatComposerDraft('hello');
  box.updateChatComposerState();
  assert.equal(input.focusCount, 0);
  assert.equal(input.selectionStart, 2);
  assert.equal(input.selectionEnd, 2);
  assert.equal(input.value, 'hello');
  assert.equal(input.attributes['aria-label'], 'chat.composerLabel');
  assert.equal(button.attributes['aria-label'], 'chat.composerSend');
});

test('IME candidate Enter, keyCode 229, and immediate composition-end Enter never submit', () => {
  const { box, input, calls, advance } = setup();
  box.setChatComposerDraft('中文');
  assert.equal(enter(input, { isComposing: true }).prevented, false);
  assert.equal(enter(input, { keyCode: 229 }).prevented, false);
  input.dispatch('compositionstart');
  assert.equal(enter(input).prevented, false);
  input.dispatch('compositionend');
  assert.equal(enter(input).prevented, false);
  assert.equal(calls.length, 0);
  advance(51);
  assert.equal(enter(input).prevented, true);
  assert.equal(calls.length, 1);
});

test('Shift+Enter preserves native newline and plain Enter sends once', async () => {
  const ctx = setup();
  ctx.box.setChatComposerDraft('hello');
  assert.equal(enter(ctx.input, { shiftKey: true }).prevented, false);
  assert.equal(ctx.calls.length, 0);
  enter(ctx.input); enter(ctx.input);
  assert.equal(ctx.calls.length, 1);
  assert.equal(ctx.button.disabled, true);
  assert.equal(ctx.button.textContent, 'chat.composerPreparing');
  ctx.finish();
  await new Promise(resolve => setImmediate(resolve));
});

test('image preparation blocks both click and keyboard sends without losing text', async () => {
  const ctx = setup();
  ctx.box.setChatComposerDraft('keep this');
  ctx.box.chatImageLoading = 1;
  ctx.box.updateChatComposerState();
  assert.equal(ctx.button.textContent, 'chat.composerPreparing');
  assert.equal(ctx.button.disabled, true);
  assert.equal((await ctx.box.submitChatComposer()).skipped, true);
  enter(ctx.input);
  assert.equal(ctx.calls.length, 0);
  assert.equal(ctx.input.value, 'keep this');
});

test('preparing locks duplicate submits, generating exposes stop and Enter does not stop', async () => {
  const ctx = setup();
  ctx.box.setChatComposerDraft('question');
  const running = ctx.box.submitChatComposer();
  assert.equal((await ctx.box.submitChatComposer()).skipped, true);
  assert.equal(ctx.calls.length, 1);
  ctx.calls[0].onExecutionStart('a', 'u1', { content: '' });
  assert.equal(ctx.button.textContent, 'chat.composerStop');
  assert.equal(ctx.button.disabled, false);
  enter(ctx.input);
  assert.equal(ctx.cancellations.length, 0);
  await ctx.box.submitChatComposer();
  assert.deepEqual(ctx.cancellations, [['a', 'u1']]);
  assert.equal(ctx.calls[0].signal.aborted, true);
  assert.equal(ctx.button.textContent, 'chat.composerStopping');
  assert.equal(ctx.button.disabled, true);
  await ctx.box.submitChatComposer();
  assert.equal(ctx.cancellations.length, 1);
  ctx.finish(); await running;
});

test('switching sessions keeps deltas and cancellation bound to original turn', async () => {
  const ctx = setup();
  ctx.box.setChatComposerDraft('a question');
  const running = ctx.box.submitChatComposer();
  const pending = { content: '' };
  ctx.calls[0].onExecutionStart('a', 'u1', pending);
  ctx.calls[0].onDelta('first');
  assert.equal(pending.content, 'first');
  assert.equal(ctx.renders(), 1);
  ctx.box.state.activeChatSessionId = 'b';
  ctx.box.updateChatComposerState();
  assert.equal(ctx.input.value, 'draft b');
  ctx.calls[0].onDelta(' second');
  assert.equal(pending.content, 'first second');
  assert.equal(ctx.renders(), 1);
  await ctx.box.stopChatComposerRequest();
  assert.deepEqual(ctx.cancellations, [['a', 'u1']]);
  ctx.calls[0].onDelta(' ignored');
  assert.equal(pending.content, 'first second');
  ctx.finish(); await running;
  assert.equal(ctx.input.value, 'draft b');
});

test('stop still aborts locally when cancellation fails', async () => {
  const ctx = setup();
  ctx.box.setChatComposerDraft('hello');
  const running = ctx.box.submitChatComposer();
  ctx.calls[0].onExecutionStart('a', 'u1', {});
  ctx.box.api.cancelChat = () => { throw Error('offline'); };
  await ctx.box.stopChatComposerRequest();
  assert.equal(ctx.calls[0].signal.aborted, true);
  ctx.finish(); await running;
});

test('stop remains available when selected session or LLM becomes unavailable', async () => {
  const ctx = setup();
  ctx.box.setChatComposerDraft('hello');
  const running = ctx.box.submitChatComposer();
  ctx.calls[0].onExecutionStart('a', 'u1', {});
  ctx.box.state.status.llm.configured = false;
  ctx.box.state.activeChatSessionId = 'missing';
  ctx.box.updateChatComposerState();
  assert.equal(ctx.button.disabled, false);
  await ctx.box.submitChatComposer();
  assert.deepEqual(ctx.cancellations, [['a', 'u1']]);
  ctx.finish(); await running;
});

for (const [name, update, reason] of [
  ['LLM unavailable', ctx => { ctx.box.state.status.llm.configured = false; }, 'chat.composerUnavailable'],
  ['session loading', ctx => { ctx.sessions.a.messagesLoading = true; }, 'chat.composerLoading'],
  ['reconciliation required', ctx => { ctx.sessions.a.reconciliationRequired = true; }, 'chat.composerReconciliation'],
]) {
  test(`${name} disables send and displays textual reason while preserving draft`, async () => {
    const ctx = setup();
    ctx.box.setChatComposerDraft('keep me');
    update(ctx); ctx.box.updateChatComposerState();
    assert.equal(ctx.button.disabled, true);
    assert.equal(ctx.status.textContent, reason);
    assert.equal((await ctx.box.submitChatComposer()).skipped, true);
    assert.equal(ctx.input.value, 'keep me');
    assert.equal(ctx.calls.length, 0);
  });
}

test('pre-persistence failure retains draft and exposes a local error cleared by editing', async () => {
  const ctx = setup();
  ctx.box.setChatComposerDraft('keep me');
  const running = ctx.box.submitChatComposer();
  ctx.finish({ error: Error('upload failed'), inputPersisted: false });
  await running;
  assert.equal(ctx.input.value, 'keep me');
  assert.equal(ctx.sessions.a.composerDraft, 'keep me');
  assert.match(ctx.status.textContent, /upload failed/);
  assert.equal(ctx.button.disabled, false);
  ctx.input.value = 'edited'; ctx.input.dispatch('input');
  assert.equal(ctx.sessions.a.composerDraft, 'edited');
  assert.equal(ctx.status.textContent, '');
});

test('persisted-turn failure does not create a draft-level resend error', async () => {
  const ctx = setup();
  ctx.box.setChatComposerDraft('persisted');
  const running = ctx.box.submitChatComposer();
  // Existing sendChatTabMessage owns canonical persistence and clears on append.
  ctx.input.value = ''; ctx.sessions.a.composerDraft = '';
  ctx.finish({ error: Error('model failed'), inputPersisted: true });
  await running;
  assert.equal(ctx.status.textContent, '');
  assert.equal(ctx.input.value, '');
  assert.equal(ctx.button.disabled, true);
});

test('old-session failure is not shown on the newly selected session', async () => {
  const ctx = setup();
  ctx.box.setChatComposerDraft('old draft');
  const running = ctx.box.submitChatComposer();
  ctx.box.state.activeChatSessionId = 'b'; ctx.box.updateChatComposerState();
  ctx.finish({ error: Error('old failure'), inputPersisted: false });
  await running;
  assert.equal(ctx.status.textContent, '');
  assert.equal(ctx.input.value, 'draft b');
  ctx.box.state.activeChatSessionId = 'a'; ctx.box.updateChatComposerState();
  assert.equal(ctx.input.value, 'old draft');
  assert.match(ctx.status.textContent, /old failure/);
});

test('reconciliation reason takes precedence over an unloaded session body', () => {
  const ctx = setup();
  ctx.sessions.a.messagesLoaded = false;
  ctx.sessions.a.reconciliationRequired = true;
  ctx.box.updateChatComposerState();
  assert.equal(ctx.status.textContent, 'chat.composerReconciliation');
  assert.equal(ctx.button.disabled, true);
});

test('retry preserves original message identity and supplies streaming cancellation hooks', async () => {
  const ctx = setup();
  let retryArgs, complete;
  ctx.box.retryChatMessage = (id, options) => {
    retryArgs = { id, options };
    ctx.box.state.chatSending = true;
    ctx.box.updateChatComposerState();
    return new Promise(resolve => { complete = () => { ctx.box.state.chatSending = false; resolve(); }; });
  };
  ctx.box.setChatComposerDraft('new unsent draft');
  const retry = ctx.box.retryChatComposer('pending-original');
  assert.equal(retryArgs.id, 'pending-original');
  assert.equal(retryArgs.options.composerOwned, true);
  assert.equal(retryArgs.options.streaming, true);
  assert.equal(ctx.button.textContent, 'chat.composerPreparing');
  assert.equal(ctx.box.retryChatComposer('pending-original'), undefined);
  retryArgs.options.onExecutionStart('a', 'original-user');
  ctx.box.state.activeChatSessionId = 'b'; ctx.box.updateChatComposerState();
  await ctx.box.stopChatComposerRequest();
  assert.deepEqual(ctx.cancellations, [['a', 'original-user']]);
  assert.equal(retryArgs.options.signal.aborted, true);
  complete(); await retry;
  assert.equal(ctx.box.chatComposer.request, null);
  assert.equal(ctx.calls.length, 0, 'retry must not create a new send');
  assert.equal(ctx.sessions.a.composerDraft, 'new unsent draft');
});

test('rejected retry unlocks composer and keeps unsent draft', async () => {
  const ctx = setup();
  ctx.box.setChatComposerDraft('keep draft');
  ctx.box.retryChatMessage = () => Promise.reject(Error('retry unavailable'));
  await assert.rejects(ctx.box.retryChatComposer('pending'), /retry unavailable/);
  assert.equal(ctx.box.chatComposer.request, null);
  assert.equal(ctx.input.value, 'keep draft');
  assert.equal(ctx.button.disabled, false);
});
