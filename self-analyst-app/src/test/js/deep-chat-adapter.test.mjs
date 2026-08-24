import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import crypto from "node:crypto";
import path from "node:path";
import vm from "node:vm";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const ui = path.resolve(here, "../../main/resources/desktop-ui");
const adapterSource = fs.readFileSync(path.join(ui, "deep-chat-adapter.js"), "utf8");
const html = fs.readFileSync(path.join(ui, "index.html"), "utf8");

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function fakeElement() {
  return {
    style: {},
    added: [],
    clears: 0,
    disabled: [],
    addMessage(message, isUpdate) { this.added.push({ message, isUpdate }); },
    clearMessages() { this.clears += 1; this.added = []; },
    scrollToBottom() {},
    disableSubmitButton(value) { this.disabled.push(value); },
    focusInput() { this.focused = true; },
  };
}

function sandbox(overrides = {}) {
  const element = overrides.element || fakeElement();
  const session = overrides.session || {
    id: "session-1",
    title: "Chat",
    messagesLoaded: true,
    messages: [],
  };
  const box = {
    console,
    Promise,
    Number,
    JSON,
    String,
    Object,
    Array,
    window: { customElements: { whenDefined: () => Promise.resolve() } },
    state: {
      chatSending: false,
      activeChatSessionId: session.id,
      dom: { deepChat: element, chatTabInput: null },
    },
    t(key, values) { return values?.msg ? `${key}:${values.msg}` : key; },
    escHtml: escapeHtml,
    normalizeSuggestedTasks(items) { return Array.isArray(items) ? items : []; },
    parseStructuredChatContent() { return null; },
    renderStructuredChatContent() { return '<div class="chat-structured-response">safe</div>'; },
    getLatestAssistantTurnIndex(messages) {
      for (let i = messages.length - 1; i >= 0; i--) {
        if (messages[i].role === "assistant") return i;
      }
      return -1;
    },
    getActiveChatSession() { return session; },
    findSessionMessage(active, id) { return active.messages.find((message) => message.id === id); },
    retryChatMessage() {},
    createSuggestedTask() {},
    formatChatErrorMessage(error) { return `failed:${error?.message || error || "unknown"}`; },
    renderChatTab() { box.renderCount += 1; },
    renderCount: 0,
    sendChatTabMessage() { return Promise.resolve({ skipped: true }); },
    ...overrides.globals,
  };
  vm.createContext(box);
  vm.runInContext(adapterSource, box);
  return { box, element, session };
}

test("desktop UI loads pinned Deep Chat and keeps a hidden legacy fallback composer", () => {
  assert.match(html, /<script type="module" src="deep-chat\.bundle\.js"><\/script>/);
  assert.match(html, /<deep-chat id="deep-chat"/);
  assert.match(html, /<script src="deep-chat-adapter\.js"><\/script>/);
  assert.match(html, /id="chat-fallback" class="chat-fallback hidden"/);
  assert.match(html, /id="chat-tab-input"/);
  assert.match(html, /id="chat-tab-send-btn"/);
  const bundle = fs.readFileSync(path.join(ui, "deep-chat.bundle.js"));
  assert.equal(
    crypto.createHash("sha256").update(bundle).digest("hex").toUpperCase(),
    "12E0B5352E26E257C4D80BCA9FCFB75DC382608EE9B15CF920A469D51C3496EA"
  );
  const license = fs.readFileSync(path.resolve(ui, "../third-party/deep-chat-LICENSE.txt"), "utf8");
  assert.match(license, /MIT License/);
  assert.match(license, /Copyright \(c\) 2024 Ovidijus Parsiunas/);
});

test("Deep Chat is configured for a local custom handler with raw model HTML disabled", () => {
  const { box, element } = sandbox();
  box.configureDeepChat(element);

  assert.equal(typeof element.connect.handler, "function");
  assert.equal(element.connect.stream, true);
  assert.equal(element.requestBodyLimits.maxMessages, 1);
  assert.equal(element.requestBodyLimits.totalMessagesMaxCharLength, 20000);
  assert.equal(element.remarkable.html, false);
  assert.equal(element.browserStorage, undefined);
  assert.equal(element.directConnection, undefined);
  assert.equal(element.maxVisibleMessages, 200);
  assert.match(element.auxiliaryStyle, /sa-chat-action/);
  assert.equal(
    typeof element.htmlClassUtilities["sa-chat-create-task"].events.click,
    "function"
  );
});

test("the first canonical render is retried after the web component finishes rendering", async () => {
  const { box, element } = sandbox();
  box.configureDeepChat(element);
  box.deepChatAdapterState.signature = "too-early";

  element.onComponentRender();
  await Promise.resolve();
  assert.equal(box.renderCount, 1);
  assert.equal(box.deepChatAdapterState.signature, null);

  element.onComponentRender();
  await Promise.resolve();
  assert.equal(box.renderCount, 1);
});

test("a deferred sync closes the custom-element upgrade race", () => {
  let deferred;
  const { box, element } = sandbox({
    globals: {
      window: {
        customElements: { whenDefined: () => Promise.resolve() },
        setTimeout(callback, delay) { deferred = { callback, delay }; },
      },
    },
  });
  box.configureDeepChat(element);
  box.deepChatAdapterState.signature = "placeholder-call";

  assert.equal(deferred.delay, 50);
  deferred.callback();
  assert.equal(box.deepChatAdapterState.signature, null);
  assert.equal(box.renderCount, 1);
});

test("component upgrade failure activates the real legacy composer fallback", () => {
  const { box, element } = sandbox();
  const hostClasses = new Set();
  const fallbackClasses = new Set(["hidden"]);
  element.parentElement = { classList: { add(value) { hostClasses.add(value); } } };
  const fallbackThread = { innerHTML: "" };
  box.state.dom.chatFallback = {
    classList: { remove(value) { fallbackClasses.delete(value); } },
  };
  box.state.dom.chatFallbackThread = fallbackThread;

  box.activateDeepChatFallback(element);

  assert.equal(box.deepChatAdapterState.upgradeFailed, true);
  assert.equal(box.state.dom.deepChat, null);
  assert.equal(box.state.dom.chatThread, fallbackThread);
  assert.equal(hostClasses.has("hidden"), true);
  assert.equal(fallbackClasses.has("hidden"), false);
});

test("failed drafts are isolated by session id", () => {
  const { box } = sandbox();
  box.setDeepChatTransientError("session-a", { text: "A failed", draft: "draft A" });
  box.setDeepChatTransientError("session-b", { text: "B failed", draft: "draft B" });

  box.setDeepChatTransientError("session-b", null);

  assert.equal(box.deepChatTransientError("session-a").draft, "draft A");
  assert.equal(box.deepChatTransientError("session-b"), null);
});

test("canonical messages retain server ids and model markup stays in markdown text", () => {
  const { box } = sandbox();
  const mapped = box.deepChatMessage({
    id: "m-1",
    role: "assistant",
    content: '<img src=x onerror="alert(1)">',
    status: "sent",
    suggestedTasks: [],
  }, false);

  assert.equal(mapped.role, "ai");
  assert.equal(mapped.custom.id, "m-1");
  assert.equal(mapped.text, '<img src=x onerror="alert(1)">');
  assert.equal(mapped.html, undefined);
});

test("rendering mirrors canonical messages once and pauses resync during its own request", () => {
  const active = {
    id: "session-1",
    title: "Chat",
    messagesLoaded: true,
    messages: [{ id: "u1", role: "user", content: "hello", status: "sent" }],
  };
  const { box, element } = sandbox({ session: active });

  assert.equal(box.renderDeepChatThread(), true);
  assert.equal(element.clears, 1);
  assert.equal(element.added.length, 1);
  assert.equal(element.added[0].message.custom.id, "u1");
  box.renderDeepChatThread();
  assert.equal(element.clears, 1);

  box.deepChatAdapterState.requestSessionId = active.id;
  active.messages.push({ id: "a1", role: "assistant", content: "thinking", status: "pending" });
  box.renderDeepChatThread();
  assert.equal(element.clears, 1);
});

test("custom handler delegates to the existing send transaction and restores an unpersisted draft", async () => {
  const calls = [];
  const responses = [];
  let opened = 0;
  let closed = 0;
  const stopClicked = {};
  const { box, element } = sandbox({
    globals: {
      sendChatTabMessage(request) {
        calls.push(request);
        return Promise.resolve({
          message: null,
          error: new Error("append failed"),
          inputPersisted: false,
          sessionId: "session-1",
        });
      },
    },
  });

  await box.handleDeepChatRequest(
    { messages: [{ role: "user", text: " keep me " }] },
    {
      stopClicked,
      onOpen() { opened += 1; },
      onClose() { closed += 1; },
      onResponse(response) { responses.push(response); },
    }
  );

  assert.equal(calls.length, 1);
  assert.equal(calls[0].source, "deep-chat");
  assert.equal(calls[0].text, "keep me");
  assert.equal(calls[0].streaming, true);
  assert.equal(typeof calls[0].onDelta, "function");
  assert.equal(typeof stopClicked.listener, "function");
  assert.equal(opened, 1);
  assert.equal(closed, 1);
  assert.match(responses[0].error, /append failed/);
  assert.equal(element.defaultInput.text, "keep me");
  assert.equal(element.focused, true);
});

test("custom handler forwards real deltas then overwrites with the canonical result", async () => {
  const responses = [];
  const lifecycle = [];
  const { box } = sandbox({
    globals: {
      sendChatTabMessage(request) {
        request.onDelta("Hello");
        request.onDelta(" world");
        return Promise.resolve({
          message: { id: "assistant-1", status: "sent", content: "Hello world" },
          error: null,
          inputPersisted: true,
          sessionId: "session-1",
        });
      },
    },
  });
  box.renderChatTab = () => lifecycle.push("render");

  await box.handleDeepChatRequest(
    { messages: [{ role: "user", text: "stream" }] },
    {
      stopClicked: {},
      onOpen() {},
      onClose() { lifecycle.push("close"); },
      onResponse(response) { responses.push(response); },
    }
  );

  assert.equal(responses[0].text, "Hello");
  assert.equal(responses[1].text, " world");
  assert.equal(responses[2].text, "Hello world");
  assert.equal(responses[2].overwrite, true);
  assert.equal(responses[2].custom.id, "assistant-1");
  assert.deepEqual(lifecycle, ["close", "render"]);
});

test("stream stop requests backend cancellation and aborts the fetch signal", async () => {
  let resolveSend;
  let request;
  const cancelledTurns = [];
  const stopClicked = {};
  const { box } = sandbox({
    globals: {
      AbortController,
      setTimeout,
      clearTimeout,
      api: {
        cancelChat(sessionId, userMessageId) {
          cancelledTurns.push([sessionId, userMessageId]);
          return Promise.resolve({ cancelRequested: true });
        },
      },
      sendChatTabMessage(value) {
        request = value;
        value.onExecutionStart("session-1", "1".repeat(12));
        return new Promise((resolve) => { resolveSend = resolve; });
      },
    },
  });

  const handling = box.handleDeepChatRequest(
    { messages: [{ role: "user", text: "stop me" }] },
    { stopClicked, onOpen() {}, onClose() {}, onResponse() {} }
  );
  await stopClicked.listener();

  assert.deepEqual(cancelledTurns, [["session-1", "1".repeat(12)]]);
  assert.equal(request.signal.aborted, true);
  resolveSend({
    message: { id: "assistant-1", status: "error", content: "cancelled" },
    error: new Error("cancelled"),
    inputPersisted: true,
    sessionId: "session-1",
  });
  await handling;
});
