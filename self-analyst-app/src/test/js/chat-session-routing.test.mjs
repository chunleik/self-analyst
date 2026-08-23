import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const ui = path.resolve(here, "../../main/resources/desktop-ui");
const read = (name) => fs.readFileSync(path.join(ui, name), "utf8");
const chatJs = read("chat.js");

function createThreadSlot() {
  return {
    innerHTML: "",
    scrollHeight: 0,
    scrollTop: 0,
    retryElements: [],
    querySelectorAll(selector) {
      if (selector !== ".msg-retry") return [];
      const ids = [...this.innerHTML.matchAll(/class="msg-retry" data-mid="([^"]+)"/g)]
        .map((match) => match[1]);
      this.retryElements = ids.map((id) => ({
        dataset: { mid: id },
        clickHandler: null,
        addEventListener(event, handler) {
          if (event === "click") this.clickHandler = handler;
        },
      }));
      return this.retryElements;
    },
  };
}

function createChatSandbox({ status = "pending", chatSending = false, api = {} } = {}) {
  const thread = createThreadSlot();
  const user = {
    id: "user-msg-01",
    role: "user",
    content: "original question",
    status: "sent",
    contextSnapshot: { type: "original-context" },
  };
  const assistant = {
    id: "assistant-01",
    role: "assistant",
    content: status === "error" ? "send failed" : "thinking",
    status,
  };
  const session = {
    id: "session-original",
    title: "session",
    messagesLoaded: true,
    messages: [user, assistant],
  };
  const sandbox = {
    api,
    state: {
      activeChatSessionId: session.id,
      chatSessions: [session],
      chatSending,
      chatContextToggles: {},
      summary: null,
      tasks: [],
      dom: {
        chatThread: thread,
        chatSessionTitle: { textContent: "" },
      },
    },
    t(key, params) {
      if (key === "chat.retry") return "Retry";
      if (key === "chat.thinking") return "Thinking";
      if (key === "chat.sendFailed") return `Send failed: ${params?.msg ?? ""}`;
      if (key === "chat.agentNoContent") return "No content";
      return key;
    },
    escHtml(value) {
      return String(value ?? "")
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#39;");
    },
  };
  vm.createContext(sandbox);
  vm.runInContext(chatJs, sandbox);
  return { sandbox, session, user, assistant, thread };
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

const nextTurn = () => new Promise((resolve) => setImmediate(resolve));

test("chat requests route by server-issued session id", () => {
  const api = read("api.js");
  const chat = chatJs;
  const drawer = read("chat-drawer.js");
  const state = read("state.js");
  const html = read("index.html");

  assert.match(api, /postChat:\s*function\s*\(msg,\s*ctx,\s*sessionId,\s*userMessageId\)/);
  assert.match(api, /sessionId:\s*sessionId/);
  assert.match(api, /userMessageId:\s*userMessageId/);
  assert.match(chat, /api\.postChat\(text,\s*context,\s*session\.id,\s*savedUser\.id\)/);
  assert.match(chat, /api\.postChat\(userMsg\.content,[\s\S]*?,\s*session\.id,\s*userMsg\.id\)/);

  // AgentState is the model-history authority; UI transcript must not be re-injected each turn.
  assert.doesNotMatch(chat, /ctx\.history\s*=/);
  assert.doesNotMatch(state, /chatContextToggles:[^\n]*history/);
  assert.doesNotMatch(html, /data-toggle="history"/);

  // Legacy drawer remains backward-compatible and intentionally omits a session id.
  assert.match(drawer, /api\s*\.postChat\(msg,\s*context\)/);
});

test("desktop turn context uses bounded projections instead of whole task and timeline objects", () => {
  const { sandbox, session } = createChatSandbox();
  sandbox.state.chatContextToggles = { currentStatus: true, futureTasks: true };
  sandbox.state.summary = {
    current: { headline: "focus", evidence: ["e".repeat(500), "two", "three", "four"] },
    timeline: Array.from({ length: 5 }, (_, i) => ({
      key: `k${i}`,
      label: `l${i}`,
      headline: "h".repeat(500),
      evidence: ["x".repeat(500), "two", "three", "four"],
      hugeInternalPayload: "must-not-leak",
    })),
  };
  sandbox.state.tasks = Array.from({ length: 12 }, (_, i) => ({
    id: `t${i}`,
    title: "t".repeat(250),
    notes: "n".repeat(500),
    priority: "medium",
    status: "open",
    hugeInternalPayload: "must-not-leak",
  }));

  const context = sandbox.buildChatContext(session);

  assert.equal(context.currentStatus.evidence.length, 3);
  assert.equal(context.currentStatus.evidence[0].length, 300);
  assert.equal(context.futureTasks.length, 10);
  assert.equal(context.futureTasks[0].title.length, 200);
  assert.equal(context.futureTasks[0].notes.length, 300);
  assert.equal(context.futureTasks[0].hugeInternalPayload, undefined);
  assert.equal(context.recentActivity.length, 4);
  assert.equal(context.recentActivity[0].headline.length, 300);
  assert.equal(context.recentActivity[0].hugeInternalPayload, undefined);
});

test("a persisted pending assistant is retryable only when no local request is in flight", () => {
  const idle = createChatSandbox({ status: "pending", chatSending: false });
  let clickedMessageId = null;
  idle.sandbox.retryChatMessage = (messageId) => {
    clickedMessageId = messageId;
  };
  idle.sandbox.renderChatThread();

  assert.match(idle.thread.innerHTML, /class="msg-retry" data-mid="assistant-01"/);
  assert.equal(typeof idle.thread.retryElements[0]?.clickHandler, "function");
  idle.thread.retryElements[0].clickHandler.call(idle.thread.retryElements[0]);
  assert.equal(clickedMessageId, "assistant-01");

  const inFlight = createChatSandbox({ status: "pending", chatSending: true });
  inFlight.sandbox.renderChatThread();

  assert.doesNotMatch(inFlight.thread.innerHTML, /class="msg-retry"/);
  assert.equal(inFlight.thread.retryElements.length, 0);
});

test("an older failed turn cannot be retried after a newer user turn", () => {
  let apiCalls = 0;
  const api = {
    updateMessage() {
      apiCalls += 1;
      return Promise.resolve({});
    },
    postChat() {
      apiCalls += 1;
      return Promise.resolve({ message: "unexpected" });
    },
  };
  const { sandbox, session, assistant, thread } = createChatSandbox({ status: "error", api });
  session.messages.push({
    id: "user-msg-02",
    role: "user",
    content: "newer question",
    status: "sent",
  });

  sandbox.renderChatThread();
  assert.doesNotMatch(thread.innerHTML, /data-mid="assistant-01"/);
  assert.equal(sandbox.retryChatMessage(assistant.id), undefined);
  assert.equal(apiCalls, 0);
  assert.equal(sandbox.state.chatSending, false);
});

test("starting a local send immediately hides a recovered pending retry", async () => {
  const append = deferred();
  let appendCalls = 0;
  const api = {
    appendMessages() {
      appendCalls += 1;
      return append.promise;
    },
  };
  const { sandbox, thread } = createChatSandbox({ status: "pending", api });
  sandbox.state.dom.chatTabInput = { value: "new question" };
  sandbox.renderChatTab = sandbox.renderChatThread;
  sandbox.alert = () => {};

  sandbox.renderChatThread();
  assert.match(thread.innerHTML, /class="msg-retry"/);

  sandbox.sendChatTabMessage();
  assert.equal(sandbox.state.chatSending, true);
  assert.doesNotMatch(thread.innerHTML, /class="msg-retry"/);

  await nextTurn();
  assert.equal(appendCalls, 1);
  append.reject(new Error("stop test send"));
  await nextTurn();
});

test("retry persists pending before posting and reuses the original routing ids", async () => {
  const pendingPut = deferred();
  const chatReply = deferred();
  const sentPut = deferred();
  const events = [];
  const api = {
    updateMessage(sessionId, messageId, patch) {
      events.push({ type: "put", sessionId, messageId, patch });
      if (patch.status === "pending") return pendingPut.promise;
      if (patch.status === "sent") return sentPut.promise;
      return Promise.resolve(patch);
    },
    postChat(message, context, sessionId, userMessageId) {
      events.push({ type: "post", message, context, sessionId, userMessageId });
      return chatReply.promise;
    },
  };
  const { sandbox, assistant } = createChatSandbox({ status: "error", api });
  sandbox.renderChatTab = () => {};
  sandbox.refreshMemoryPanelSoon = () => {};

  const retry = sandbox.retryChatMessage(assistant.id);
  assert.equal(typeof retry?.then, "function", "retry should expose its async chain");
  assert.deepEqual(events.map((event) => event.type), ["put"]);
  assert.equal(events[0].patch.status, "pending");
  assert.equal(events[0].patch.content, assistant.content);
  assert.equal(sandbox.state.chatSending, true);

  pendingPut.resolve({ status: "pending" });
  await nextTurn();
  assert.deepEqual(events.map((event) => event.type), ["put", "post"]);
  assert.deepEqual(events[1], {
    type: "post",
    message: "original question",
    context: { type: "original-context" },
    sessionId: "session-original",
    userMessageId: "user-msg-01",
  });

  chatReply.resolve({ message: "recovered answer", suggestedTasks: [] });
  await nextTurn();
  assert.deepEqual(events.map((event) => event.type), ["put", "post", "put"]);
  assert.equal(events[2].patch.status, "sent");
  assert.equal(sandbox.state.chatSending, true);

  sentPut.resolve({ status: "sent" });
  await retry;
  assert.deepEqual(events.map((event) => event.type), ["put", "post", "put"]);
  assert.equal(events[2].patch.content, "recovered answer");
  assert.equal(assistant.status, "sent");
  assert.equal(assistant.content, "recovered answer");
  assert.equal(assistant.error, null);
  assert.equal(sandbox.state.chatSending, false);
});

test("retry failures return the assistant to an error state with retry available", async () => {
  const events = [];
  const api = {
    updateMessage(sessionId, messageId, patch) {
      events.push({ type: "put", sessionId, messageId, patch });
      return Promise.resolve(patch);
    },
    postChat() {
      events.push({ type: "post" });
      return Promise.reject(new Error("network down"));
    },
  };
  const { sandbox, assistant, thread } = createChatSandbox({ status: "pending", api });
  sandbox.renderChatTab = () => {};

  await sandbox.retryChatMessage(assistant.id);

  assert.deepEqual(events.map((event) => event.type), ["put", "post", "put"]);
  assert.equal(events[2].patch.status, "error");
  assert.equal(events[2].patch.content, assistant.content);
  assert.equal(assistant.status, "error");
  assert.match(assistant.content, /network down/);
  assert.equal(sandbox.state.chatSending, false);

  sandbox.renderChatThread();
  assert.match(thread.innerHTML, /class="msg-retry" data-mid="assistant-01"/);
  assert.equal(typeof thread.retryElements[0]?.clickHandler, "function");
});

test("a failed pending PUT does not post and still releases the retry state", async () => {
  const events = [];
  const api = {
    updateMessage(sessionId, messageId, patch) {
      events.push({ type: "put", sessionId, messageId, patch });
      return Promise.reject(new Error(
        patch.status === "pending" ? "pending save failed" : "error save failed",
      ));
    },
    postChat() {
      events.push({ type: "post" });
      return Promise.resolve({ message: "must not run" });
    },
  };
  const { sandbox, assistant, thread } = createChatSandbox({ status: "error", api });
  sandbox.renderChatTab = () => {};

  await sandbox.retryChatMessage(assistant.id);

  assert.deepEqual(events.map((event) => event.type), ["put", "put"]);
  assert.deepEqual(events.map((event) => event.patch.status), ["pending", "error"]);
  assert.equal(events[1].patch.content, assistant.content);
  assert.equal(assistant.status, "error");
  assert.match(assistant.content, /pending save failed/);
  assert.equal(sandbox.state.chatSending, false);

  sandbox.renderChatThread();
  assert.match(thread.innerHTML, /class="msg-retry" data-mid="assistant-01"/);
});

test("a failed sent PUT returns to error and remains retryable", async () => {
  const events = [];
  const api = {
    updateMessage(sessionId, messageId, patch) {
      events.push({ type: "put", sessionId, messageId, patch });
      return patch.status === "sent"
        ? Promise.reject(new Error("sent save failed"))
        : Promise.resolve(patch);
    },
    postChat() {
      events.push({ type: "post" });
      return Promise.resolve({ message: "answer awaiting persistence" });
    },
  };
  const { sandbox, assistant, thread } = createChatSandbox({ status: "pending", api });
  sandbox.renderChatTab = () => {};
  sandbox.refreshMemoryPanelSoon = () => {};

  await sandbox.retryChatMessage(assistant.id);

  assert.deepEqual(events.map((event) => event.type), ["put", "post", "put", "put"]);
  assert.deepEqual(
    events.filter((event) => event.type === "put").map((event) => event.patch.status),
    ["pending", "sent", "error"],
  );
  assert.equal(events[3].patch.content, assistant.content);
  assert.equal(assistant.status, "error");
  assert.match(assistant.content, /sent save failed/);
  assert.equal(sandbox.state.chatSending, false);

  sandbox.renderChatThread();
  assert.match(thread.innerHTML, /class="msg-retry" data-mid="assistant-01"/);
});
