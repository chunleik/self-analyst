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
      chatSessionsLoaded: true,
      chatSessionsHasMore: false,
      chatSessionsLoadingMore: false,
      chatSending,
      chatSessionSearch: "",
      chatSessionSearchResults: null,
      chatSessionSearchHasMore: false,
      chatSessionSearchLoading: false,
      chatSessionSearchRequestId: 0,
      chatSessionSearchTimer: null,
      chatSessionMutationGeneration: 0,
      chatSessionLoadRequestId: 0,
      chatSessionLatestLoadRequestId: 0,
      chatSessionListReloadNeeded: false,
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
      if (key === "chat.reconciliationPending") return `Reconcile: ${params?.msg ?? ""}`;
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
    formatRelativeTime() { return "now"; },
    setTimeout,
    clearTimeout,
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
  assert.match(chat, /api\.postChat\(\s*userMsg\.content,[\s\S]*?,\s*session\.id,\s*userMsg\.id/);

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

test("append failure preserves unpersisted input and does not create local messages", async () => {
  let postCalls = 0;
  const api = {
    appendMessages() {
      return Promise.reject(new Error("payload rejected"));
    },
    postChat() {
      postCalls += 1;
      return Promise.resolve({ message: "unexpected" });
    },
  };
  const { sandbox, session } = createChatSandbox({ status: "sent", api });
  sandbox.state.dom.chatTabInput = { value: "keep this draft" };
  sandbox.renderChatTab = () => {};
  sandbox.alert = () => {};
  const beforeIds = session.messages.map((message) => message.id);

  sandbox.sendChatTabMessage();
  await nextTurn();
  await nextTurn();

  assert.equal(sandbox.state.dom.chatTabInput.value, "keep this draft");
  assert.deepEqual(session.messages.map((message) => message.id), beforeIds);
  assert.equal(postCalls, 0);
  assert.equal(sandbox.state.chatSending, false);
});

test("send mirrors retention and adopts the canonical updated assistant", async () => {
  const savedUser = {
    id: "user-new",
    role: "user",
    content: "new question",
    status: "sent",
    createdAt: "2026-08-23T00:00:00Z",
  };
  const savedPending = {
    id: "assistant-new",
    role: "assistant",
    content: "Thinking",
    status: "pending",
    createdAt: "2026-08-23T00:00:01Z",
  };
  const api = {
    appendMessages() {
      return Promise.resolve([savedUser, savedPending]);
    },
    postChat() {
      return Promise.resolve({ message: "client-sized answer", suggestedTasks: [] });
    },
    updateMessage() {
      return Promise.resolve({
        ...savedPending,
        status: "sent",
        content: "canonical truncated answer",
        suggestedTasks: [{ title: "canonical task" }],
      });
    },
  };
  const { sandbox, session } = createChatSandbox({ status: "sent", api });
  session.messages = Array.from({ length: 199 }, (_, index) => ({
    id: `old-${index}`,
    role: index % 2 ? "assistant" : "user",
    content: `old-${index}`,
    status: "sent",
  }));
  sandbox.state.dom.chatTabInput = { value: "new question" };
  sandbox.renderChatTab = () => {};
  sandbox.resortChatSessions = () => {};
  sandbox.refreshMemoryPanelSoon = () => {};
  sandbox.backfillSessionTitle = () => {};
  sandbox.alert = () => {};

  sandbox.sendChatTabMessage();
  await nextTurn();
  await nextTurn();
  await nextTurn();

  assert.equal(session.messages.length, 199);
  assert.equal(session.messages[0].role, "user");
  assert.equal(session.messages.at(-2).id, "user-new");
  assert.equal(session.messages.at(-1).id, "assistant-new");
  assert.equal(session.messages.at(-1).content, "canonical truncated answer");
  assert.equal(session.messages.at(-1).suggestedTasks[0].title, "canonical task");
  assert.equal(session.lastMessagePreview, "canonical truncated answer");
  assert.equal(sandbox.state.dom.chatTabInput.value, "");
});

test("send completion waits for replacement metadata and renders it", async () => {
  const replacement = deferred();
  let listCalls = 0;
  const savedUser = {
    id: "user-new",
    role: "user",
    content: "new question",
    status: "sent",
  };
  const savedPending = {
    id: "assistant-new",
    role: "assistant",
    content: "Thinking",
    status: "pending",
  };
  const api = {
    appendMessages() { return Promise.resolve([savedUser, savedPending]); },
    postChat() { return Promise.resolve({ message: "answer", suggestedTasks: [] }); },
    updateMessage() {
      return Promise.resolve({ ...savedPending, status: "sent", content: "answer" });
    },
    listSessions() { listCalls += 1; return replacement.promise; },
    listMemory() { return Promise.resolve({ memories: [] }); },
  };
  const { sandbox, session } = createChatSandbox({ status: "sent", api });
  sandbox.state.chatSessionListReloadNeeded = true;
  sandbox.state.dom.chatTabInput = { value: "new question" };
  sandbox.resortChatSessions = () => {};
  sandbox.refreshMemoryPanelSoon = () => {};
  sandbox.backfillSessionTitle = () => {};
  sandbox.alert = () => {};
  let renders = 0;
  sandbox.renderChatTab = () => { renders += 1; };

  const sending = sandbox.sendChatTabMessage();
  await nextTurn();
  assert.equal(listCalls, 1);
  assert.equal(sandbox.state.chatSending, false);
  const rendersBeforeReplacement = renders;

  replacement.resolve({
    activeSessionId: session.id,
    sessions: [
      { id: session.id, title: "refreshed", messageCount: 4 },
      { id: "history-old", title: "history" },
    ],
    hasMore: false,
  });
  await sending;

  assert.equal(sandbox.state.chatSessions[0], session);
  assert.equal(sandbox.state.chatSessions.some((row) => row.id === "history-old"), true);
  assert.equal(session.title, "refreshed");
  assert.ok(renders > rendersBeforeReplacement);
});

test("failed lazy load stays retryable and restores the bound session context", async () => {
  let calls = 0;
  const api = {
    getSession() {
      calls += 1;
      if (calls === 1) return Promise.reject(new Error("temporary read failure"));
      return Promise.resolve({
        summary: "restored",
        memoryPolicy: "smart",
        contextSnapshot: { type: "task", title: "bound task" },
        messages: [{ id: "u", role: "user", content: "old", status: "sent" }],
      });
    },
  };
  const { sandbox, session } = createChatSandbox({ api });
  session.messagesLoaded = false;
  session.messages = [];

  await assert.rejects(sandbox.ensureSessionMessagesLoaded(session), /temporary read failure/);
  assert.equal(session.messagesLoaded, false);
  assert.match(session.messagesLoadError, /temporary read failure/);

  await sandbox.ensureSessionMessagesLoaded(session);
  const context = sandbox.buildChatContext(session);
  assert.equal(session.messagesLoaded, true);
  assert.equal(session.messages[0].content, "old");
  assert.equal(context.boundContext.title, "bound task");
  assert.equal(calls, 2);
});

test("paged index keeps an active session that is outside the first page", async () => {
  let activeWrites = 0;
  const api = {
    listSessions() {
      return Promise.resolve({
        activeSessionId: "active-old",
        sessions: [{ id: "newest", title: "newest" }],
        nextCursor: "cursor-1",
        hasMore: true,
      });
    },
    getSession(id) {
      assert.equal(id, "active-old");
      return Promise.resolve({ id, title: "active", messages: [] });
    },
    setActiveSession() { activeWrites += 1; return Promise.resolve({}); },
    listMemory() { return Promise.resolve({ memories: [] }); },
  };
  const { sandbox } = createChatSandbox({ api });

  await sandbox.loadChatSessions();

  assert.equal(sandbox.state.activeChatSessionId, "active-old");
  assert.equal(sandbox.state.chatSessions[0].id, "active-old");
  assert.equal(sandbox.state.chatSessionsHasMore, true);
  assert.equal(sandbox.state.chatSessionsNextCursor, "cursor-1");
  assert.equal(activeWrites, 0);
});

test("a startup list response cannot overwrite a newly created session", async () => {
  const initialList = deferred();
  let listCalls = 0;
  const api = {
    listSessions() {
      listCalls += 1;
      if (listCalls === 1) return initialList.promise;
      return Promise.resolve({
        activeSessionId: "created-new",
        sessions: [
          { id: "created-new", title: "new" },
          { id: "history-old", title: "history" },
        ],
        hasMore: false,
      });
    },
    createSession() {
      return Promise.resolve({ id: "created-new", title: "new", messages: [] });
    },
    listMemory() { return Promise.resolve({ memories: [] }); },
  };
  const { sandbox } = createChatSandbox({ api });
  sandbox.state.chatSessions = [];
  sandbox.state.activeChatSessionId = null;
  sandbox.state.chatSessionsLoaded = false;

  const startup = sandbox.loadChatSessions();
  const creation = sandbox.createChatSession({ title: "new" });
  await creation;
  initialList.resolve({
    activeSessionId: "stale-old",
    sessions: [{ id: "stale-old", title: "stale" }],
    hasMore: false,
  });
  await startup;
  await nextTurn();

  assert.equal(sandbox.state.activeChatSessionId, "created-new");
  assert.equal(sandbox.state.chatSessions.some((row) => row.id === "created-new"), true);
  assert.equal(sandbox.state.chatSessions.some((row) => row.id === "history-old"), true);
  assert.equal(sandbox.state.chatSessionsLoaded, true);
  assert.equal(listCalls, 2);
});

test("a startup list response cannot clear a search started while it was pending", async () => {
  const initialList = deferred();
  const timers = [];
  const api = {
    listSessions() { return initialList.promise; },
    listMemory() { return Promise.resolve({ memories: [] }); },
  };
  const { sandbox } = createChatSandbox({ api });
  sandbox.state.chatSessions = [];
  sandbox.state.activeChatSessionId = null;
  sandbox.state.chatSessionsLoaded = false;
  sandbox.state.dom.chatSessionList = { innerHTML: "", onclick: null };
  sandbox.setTimeout = (callback) => { timers.push(callback); return timers.length; };
  sandbox.clearTimeout = () => {};

  const startup = sandbox.loadChatSessions();
  sandbox.scheduleChatSessionSearch("needle");
  initialList.resolve({
    activeSessionId: "stale-old",
    sessions: [{ id: "stale-old", title: "stale" }],
    hasMore: false,
  });
  await startup;

  assert.equal(timers.length, 1);
  assert.equal(sandbox.state.chatSessionSearch, "needle");
  assert.equal(sandbox.state.chatSessionSearchLoading, true);
  assert.equal(sandbox.state.activeChatSessionId, "stale-old");
  assert.equal(sandbox.state.chatSessions.some((row) => row.id === "stale-old"), true);
  assert.equal(sandbox.state.chatSessionsLoaded, true);
});

test("a startup list invalidated by sending reloads without detaching the live session", async () => {
  const initialList = deferred();
  let listCalls = 0;
  const api = {
    listSessions() {
      listCalls += 1;
      if (listCalls === 1) return initialList.promise;
      return Promise.resolve({
        activeSessionId: "live-session",
        sessions: [
          { id: "live-session", title: "fresh title", messageCount: 1 },
          { id: "history-old", title: "history" },
        ],
        hasMore: false,
      });
    },
    listMemory() { return Promise.resolve({ memories: [] }); },
  };
  const { sandbox } = createChatSandbox({ api });
  sandbox.state.chatSessions = [];
  sandbox.state.activeChatSessionId = null;
  sandbox.state.chatSessionsLoaded = false;

  const startup = sandbox.loadChatSessions();
  const live = {
    id: "live-session",
    title: "local title",
    messagesLoaded: true,
    messages: [{ id: "persisted-message", role: "user", content: "keep" }],
  };
  sandbox.invalidateChatSessionLoads();
  sandbox.state.chatSending = true;
  sandbox.state.chatSessions = [live];
  sandbox.state.activeChatSessionId = live.id;
  initialList.resolve({
    activeSessionId: "stale-old",
    sessions: [{ id: "stale-old", title: "stale" }],
    hasMore: false,
  });
  await startup;

  assert.equal(sandbox.state.chatSessionListReloadNeeded, true);
  sandbox.state.chatSending = false;
  await sandbox.refreshChatSessionListIfNeeded();

  assert.equal(listCalls, 2);
  assert.equal(sandbox.state.chatSessions[0], live);
  assert.equal(live.title, "fresh title");
  assert.equal(live.messages[0].id, "persisted-message");
  assert.equal(sandbox.state.chatSessions.some((row) => row.id === "history-old"), true);
});

test("a failed replacement page keeps the live cache and remains retryable", async () => {
  const api = {
    listSessions() { return Promise.reject(new Error("temporary list failure")); },
  };
  const { sandbox, session } = createChatSandbox({ api });
  sandbox.state.chatSessionListReloadNeeded = true;
  sandbox.state.activeChatSessionId = session.id;
  sandbox.state.chatSessionsLoaded = true;

  const reloaded = await sandbox.refreshChatSessionListIfNeeded();

  assert.equal(reloaded, false);
  assert.equal(sandbox.state.chatSessions[0], session);
  assert.equal(sandbox.state.activeChatSessionId, session.id);
  assert.equal(sandbox.state.chatSessionsLoaded, true);
  assert.equal(sandbox.state.chatSessionListReloadNeeded, true);
  assert.match(sandbox.state.chatSessionsLoadError, /temporary list failure/);
});

test("server search ignores an older out-of-order response", async () => {
  const requests = new Map();
  const timers = [];
  const api = {
    listSessions(options) {
      const request = deferred();
      requests.set(options.q, request);
      return request.promise;
    },
  };
  const { sandbox } = createChatSandbox({ api });
  sandbox.state.dom.chatSessionList = { innerHTML: "", onclick: null };
  sandbox.setTimeout = (callback) => { timers.push(callback); return timers.length; };
  sandbox.clearTimeout = () => {};

  sandbox.scheduleChatSessionSearch("old");
  timers.shift()();
  sandbox.scheduleChatSessionSearch("new");
  timers.shift()();
  requests.get("new").resolve({ sessions: [{ id: "new", title: "new" }], hasMore: false });
  await nextTurn();
  requests.get("old").resolve({ sessions: [{ id: "old", title: "old" }], hasMore: false });
  await nextTurn();

  assert.equal(sandbox.state.chatSessionSearchResults.length, 1);
  assert.equal(sandbox.state.chatSessionSearchResults[0].id, "new");
  assert.equal(sandbox.state.chatSessionSearchLoading, false);
});

test("a session mutation restarts an in-flight search and clears loading", async () => {
  const first = deferred();
  const refreshed = deferred();
  let callCount = 0;
  const api = {
    listSessions() {
      callCount += 1;
      return callCount === 1 ? first.promise : refreshed.promise;
    },
  };
  const { sandbox } = createChatSandbox({ api });
  sandbox.state.chatSessionSearch = "needle";
  sandbox.state.chatSessionSearchLoading = true;
  sandbox.state.chatSessionSearchRequestId = 1;
  sandbox.renderChatSessionList = () => {};

  const staleSearch = sandbox.runChatSessionSearch("needle", 1, 0);
  sandbox.noteChatSessionMutation();
  first.resolve({ sessions: [{ id: "stale", title: "stale" }], hasMore: false });
  await staleSearch;

  assert.equal(sandbox.state.chatSessionSearchLoading, true);
  refreshed.resolve({ sessions: [{ id: "fresh", title: "fresh" }], hasMore: false });
  await nextTurn();

  assert.equal(callCount, 2);
  assert.equal(sandbox.state.chatSessionSearchResults.map((row) => row.id).join(","), "fresh");
  assert.equal(sandbox.state.chatSessionSearchLoading, false);
});

test("stale normal cursor reloads the first page instead of getting stuck", async () => {
  const calls = [];
  const api = {
    listSessions(options) {
      calls.push(options);
      if (options.cursor) {
        const error = new Error("Cursor is stale");
        error.status = 400;
        return Promise.reject(error);
      }
      return Promise.resolve({
        activeSessionId: "session-original",
        sessions: [{ id: "fresh", title: "fresh" }],
        nextCursor: "fresh-cursor",
        hasMore: true,
      });
    },
    getSession() {
      return Promise.resolve({ id: "session-original", title: "active", messages: [] });
    },
    listMemory() { return Promise.resolve({ memories: [] }); },
  };
  const { sandbox } = createChatSandbox({ api });
  sandbox.state.chatSessionsNextCursor = "stale-cursor";
  sandbox.state.chatSessionsHasMore = true;
  sandbox.renderChatSessionList = () => {};

  await sandbox.loadMoreChatSessions();

  assert.equal(calls.length, 2);
  assert.equal(calls[0].cursor, "stale-cursor");
  assert.equal(calls[1].cursor, undefined);
  assert.equal(sandbox.state.chatSessionsNextCursor, "fresh-cursor");
  assert.equal(sandbox.state.chatSessionsHasMore, true);
});

test("stale normal cursor keeps the continuation lock through first-page recovery", async () => {
  const freshPage = deferred();
  const api = {
    listSessions(options) {
      if (options.cursor) {
        const error = new Error("Cursor is stale");
        error.status = 400;
        return Promise.reject(error);
      }
      return freshPage.promise;
    },
    listMemory() { return Promise.resolve({ memories: [] }); },
  };
  const { sandbox } = createChatSandbox({ api });
  sandbox.state.chatSessionsNextCursor = "stale-cursor";
  sandbox.state.chatSessionsHasMore = true;
  sandbox.renderChatSessionList = () => {};

  const loading = sandbox.loadMoreChatSessions();
  await nextTurn();
  assert.equal(sandbox.state.chatSessionsLoadingMore, true);

  freshPage.resolve({
    activeSessionId: "fresh",
    sessions: [{ id: "fresh", title: "fresh" }],
    hasMore: false,
  });
  await loading;
  assert.equal(sandbox.state.chatSessionsLoadingMore, false);
});

test("a stale search-cursor 400 cannot cancel a newer query", async () => {
  const oldContinuation = deferred();
  const newSearch = deferred();
  const calls = [];
  const api = {
    listSessions(options) {
      calls.push(options);
      return options.cursor ? oldContinuation.promise : newSearch.promise;
    },
  };
  const { sandbox } = createChatSandbox({ api });
  sandbox.state.chatSessionSearch = "old";
  sandbox.state.chatSessionSearchResults = [{ id: "old-1", title: "old" }];
  sandbox.state.chatSessionSearchCursor = "old-cursor";
  sandbox.state.chatSessionSearchHasMore = true;
  sandbox.state.chatSessionSearchRequestId = 1;
  sandbox.renderChatSessionList = () => {};

  const continuation = sandbox.loadMoreChatSessions();
  sandbox.state.chatSessionSearch = "new";
  sandbox.state.chatSessionSearchRequestId = 2;
  sandbox.state.chatSessionSearchLoading = true;
  const currentSearch = sandbox.runChatSessionSearch("new", 2, 0);
  const stale = new Error("Cursor is stale");
  stale.status = 400;
  oldContinuation.reject(stale);
  await continuation;

  newSearch.resolve({ sessions: [{ id: "new-1", title: "new" }], hasMore: false });
  await currentSearch;
  assert.equal(calls.length, 2);
  assert.equal(sandbox.state.chatSessionSearchResults.map((row) => row.id).join(","), "new-1");
  assert.equal(sandbox.state.chatSessionSearchLoading, false);
});

test("a stale normal-cursor 400 does not reload while a send is in progress", async () => {
  const continuation = deferred();
  let calls = 0;
  const api = {
    listSessions() {
      calls += 1;
      return continuation.promise;
    },
  };
  const { sandbox } = createChatSandbox({ api });
  sandbox.state.chatSessionsNextCursor = "old-cursor";
  sandbox.state.chatSessionsHasMore = true;
  sandbox.renderChatSessionList = () => {};

  const loading = sandbox.loadMoreChatSessions();
  sandbox.state.chatSending = true;
  const stale = new Error("Cursor is stale");
  stale.status = 400;
  continuation.reject(stale);
  await loading;

  assert.equal(calls, 1);
  assert.equal(sandbox.state.chatSessions[0].id, "session-original");
});

test("search continuation is bound to request and mutation generations", async () => {
  const continuation = deferred();
  const api = { listSessions() { return continuation.promise; } };
  const { sandbox } = createChatSandbox({ api });
  sandbox.state.chatSessionSearch = "A";
  sandbox.state.chatSessionSearchResults = [{ id: "a1", title: "A1" }];
  sandbox.state.chatSessionSearchCursor = "a-cursor";
  sandbox.state.chatSessionSearchHasMore = true;
  sandbox.state.chatSessionSearchRequestId = 1;
  sandbox.renderChatSessionList = () => {};

  const loading = sandbox.loadMoreChatSessions();
  sandbox.state.chatSessionSearchRequestId = 3;
  sandbox.state.chatSessionMutationGeneration = 1;
  sandbox.state.chatSessionSearchResults = [{ id: "a-new", title: "new A" }];
  sandbox.state.chatSessionSearchCursor = "new-cursor";
  continuation.resolve({
    sessions: [{ id: "a-old-page", title: "old continuation" }],
    nextCursor: "old-cursor",
    hasMore: false,
  });
  await loading;

  assert.deepEqual(sandbox.state.chatSessionSearchResults.map((row) => row.id), ["a-new"]);
  assert.equal(sandbox.state.chatSessionSearchCursor, "new-cursor");
});

test("in-flight page cannot resurrect a row after a local mutation", async () => {
  const continuation = deferred();
  const api = { listSessions() { return continuation.promise; } };
  const { sandbox } = createChatSandbox({ api });
  sandbox.state.chatSessionsNextCursor = "cursor";
  sandbox.state.chatSessionsHasMore = true;
  sandbox.renderChatSessionList = () => {};

  const loading = sandbox.loadMoreChatSessions();
  sandbox.noteChatSessionMutation();
  continuation.resolve({
    sessions: [{ id: "deleted", title: "must not return" }],
    hasMore: false,
  });
  await loading;

  assert.equal(sandbox.state.chatSessions.some((row) => row.id === "deleted"), false);
});

test("active-page failure cannot insert a placeholder for a newly selected session", async () => {
  const activeLoad = deferred();
  const api = {
    listSessions() {
      return Promise.resolve({
        activeSessionId: "old-active",
        sessions: [{ id: "newest", title: "newest" }],
        hasMore: false,
      });
    },
    getSession() { return activeLoad.promise; },
    listMemory() { return Promise.resolve({ memories: [] }); },
  };
  const { sandbox } = createChatSandbox({ api });

  const loading = sandbox.loadChatSessions();
  await nextTurn();
  sandbox.state.activeChatSessionId = "newest";
  activeLoad.reject(new Error("old active unavailable"));
  await loading;

  assert.equal(sandbox.state.activeChatSessionId, "newest");
  assert.equal(sandbox.state.chatSessions.filter((row) => row.id === "newest").length, 1);
  assert.equal(sandbox.state.chatSessions.some((row) => row.id === "old-active"), false);
});

test("whitespace-only search restores the normal session list", () => {
  const { sandbox } = createChatSandbox();
  sandbox.state.dom.chatSessionList = { innerHTML: "", onclick: null };
  sandbox.state.chatSessionSearchResults = [{ id: "search-only" }];

  sandbox.scheduleChatSessionSearch("   ");

  assert.equal(sandbox.state.chatSessionSearch, "");
  assert.equal(sandbox.state.chatSessionSearchResults, null);
  assert.match(sandbox.state.dom.chatSessionList.innerHTML, /session-original/);
});

test("lazy-load completion triggers a full render so the composer state refreshes", async () => {
  const load = deferred();
  const api = { getSession() { return load.promise; } };
  const { sandbox, session } = createChatSandbox({ api });
  session.messagesLoaded = false;
  session.messages = [];
  let fullRenders = 0;
  sandbox.renderChatTab = () => { fullRenders += 1; };

  sandbox.renderChatThread();
  assert.match(sandbox.state.dom.chatThread.innerHTML, /common.loadingEllipsis/);
  load.resolve({ messages: [], memoryPolicy: "smart" });
  await nextTurn();

  assert.equal(fullRenders, 1);
  assert.equal(session.messagesLoaded, true);
});

test("selecting an unloaded session renders its loading state immediately", () => {
  const selectedLoad = deferred();
  let getCalls = 0;
  const api = {
    setActiveSession() { return Promise.resolve({}); },
    getSession() { getCalls += 1; return selectedLoad.promise; },
  };
  const { sandbox, session } = createChatSandbox({ api });
  const other = {
    id: "session-other",
    title: "other",
    updatedAt: "2026-08-23T00:00:00Z",
    messages: [],
    messagesLoaded: false,
  };
  sandbox.state.chatSessions = [session, other];
  const list = { innerHTML: "", onclick: null };
  sandbox.state.dom.chatSessionList = list;
  let renders = 0;
  sandbox.renderChatTab = () => { renders += 1; };
  sandbox.renderChatSessionList();
  const item = { dataset: { sid: other.id } };

  list.onclick({
    target: {
      closest(selector) {
        return selector.includes("delete") || selector.includes("load-more") ? null : item;
      },
    },
  });

  assert.equal(sandbox.state.activeChatSessionId, other.id);
  assert.equal(renders, 1);
  assert.equal(getCalls, 1);
});

test("the composer is disabled for the whole in-flight send", () => {
  const { sandbox } = createChatSandbox({ chatSending: true });
  sandbox.state.status = { llm: { configured: true } };
  sandbox.state.dom.chatTabInput = { disabled: false, placeholder: "" };
  sandbox.state.dom.chatTabSendBtn = { disabled: false };

  sandbox.updateChatInputState();

  assert.equal(sandbox.state.dom.chatTabInput.disabled, true);
  assert.equal(sandbox.state.dom.chatTabSendBtn.disabled, true);
});

test("a lost sent-PUT response reconciles a committed canonical reply", async () => {
  const events = [];
  const api = {
    updateMessage(sessionId, messageId, patch) {
      events.push({ type: "put", patch });
      if (patch.status === "sent") return Promise.reject(new Error("response lost"));
      return Promise.resolve({ ...patch, id: messageId, role: "assistant" });
    },
    postChat() {
      events.push({ type: "post" });
      return Promise.resolve({ message: "committed answer" });
    },
    getSession() {
      events.push({ type: "get" });
      return Promise.resolve({
        messages: [
          { id: "user-msg-01", role: "user", content: "original question", status: "sent" },
          { id: "assistant-01", role: "assistant", content: "committed answer", status: "sent" },
        ],
      });
    },
  };
  const { sandbox, session, assistant } = createChatSandbox({ status: "error", api });
  sandbox.renderChatTab = () => {};
  sandbox.refreshMemoryPanelSoon = () => {};

  await sandbox.retryChatMessage(assistant.id);

  assert.deepEqual(events.map((event) => event.type), ["put", "post", "put", "get"]);
  assert.equal(session.messages.at(-1).status, "sent");
  assert.equal(session.messages.at(-1).content, "committed answer");
  assert.equal(events.filter((event) => event.patch?.status === "error").length, 0);
});

test("sent-PUT plus reconciliation-GET failure freezes the session", async () => {
  const api = {
    updateMessage(sessionId, messageId, patch) {
      if (patch.status === "sent") return Promise.reject(new Error("sent response lost"));
      return Promise.resolve({ ...patch, id: messageId, role: "assistant" });
    },
    postChat() { return Promise.resolve({ message: "uncertain answer" }); },
    getSession() { return Promise.reject(new Error("canonical reload failed")); },
  };
  const { sandbox, session, assistant } = createChatSandbox({ status: "error", api });
  sandbox.renderChatTab = () => {};
  sandbox.refreshMemoryPanelSoon = () => {};

  await sandbox.retryChatMessage(assistant.id);

  assert.equal(session.reconciliationRequired, true);
  assert.equal(session.messagesLoaded, false);
  assert.match(session.messagesLoadError, /sent response lost/);
  sandbox.state.status = { llm: { configured: true } };
  sandbox.state.dom.chatTabInput = { disabled: false, placeholder: "" };
  sandbox.state.dom.chatTabSendBtn = { disabled: false };
  sandbox.updateChatInputState();
  assert.equal(sandbox.state.dom.chatTabInput.disabled, true);
  assert.equal(sandbox.state.dom.chatTabSendBtn.disabled, true);
});

test("a persisted terminal error refreshes the full canonical session", async () => {
  const events = [];
  const api = {
    updateMessage(sessionId, messageId, patch) {
      events.push({ type: "put", patch });
      return Promise.resolve({ ...patch, id: messageId, role: "assistant" });
    },
    postChat() {
      events.push({ type: "post" });
      return Promise.reject(new Error("model unavailable"));
    },
    getSession() {
      events.push({ type: "get" });
      return Promise.resolve({
        updatedAt: "2026-08-23T01:00:00Z",
        messages: [
          { id: "user-msg-01", role: "user", content: "original question", status: "sent" },
          { id: "assistant-01", role: "assistant", content: "failed", status: "error", error: "model unavailable" },
        ],
      });
    },
  };
  const { sandbox, session, assistant } = createChatSandbox({ status: "pending", api });
  sandbox.renderChatTab = () => {};

  await sandbox.retryChatMessage(assistant.id);

  assert.deepEqual(events.map((event) => event.type), ["put", "post", "put", "get"]);
  assert.equal(session.updatedAt, "2026-08-23T01:00:00Z");
  assert.equal(session.messages.at(-1).status, "error");
  assert.equal(session.reconciliationRequired, false);
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
