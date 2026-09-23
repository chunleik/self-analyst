import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";
import test from "node:test";

const uiSource = fs.readFileSync(
  new URL("../../main/resources/desktop-ui/ui.js", import.meta.url),
  "utf8",
);
const agentSource = fs.readFileSync(
  new URL("../../main/resources/desktop-ui/agent.js", import.meta.url),
  "utf8",
);

const version = { statisticsVersion: "activity-afk-v2", calendarVersion: "day-0400-v1", timezone: "Asia/Shanghai" };
function createSandbox(summary) {
  if (summary) summary = { ...version, ...summary };
  const timeline = { innerHTML: summary ? "cached-timeline" : '<div class="loading-placeholder">加载中...</div>' };
  let summaryCalls = 0;
  const sandbox = {
    state: {
      tab: "agent",
      summary,
      tasks: [],
      status: { llm: {} },
      loading: false,
      dom: {
        tabs: [],
        tabAgent: { classList: { toggle() {} } },
        tabChat: { classList: { toggle() {} } },
        tabFiles: { classList: { toggle() {} } },
        timelineBody: timeline,
        errorOverlay: { classList: { add() {}, remove() {} } },
        errorMessage: { textContent: "" },
        backendDot: { className: "", title: "" },
        backendText: { textContent: "", title: "" },
        collectorsDot: { className: "", title: "" },
        collectorsText: { textContent: "", title: "" },
        fileDot: { className: "", title: "" },
        fileText: { textContent: "", title: "" },
        fileStatusBtn: { title: "" },
        llmDot: { className: "", title: "" },
        llmText: { textContent: "", title: "" },
      },
    },
    document: { getElementById(id) {
      return null;
    } },
    api: {
      getStatus() { return Promise.resolve({ llm: {} }); },
      getTasks() { return Promise.resolve([]); },
      getUsage() { return Promise.resolve(null); },
      getSummary() {
        summaryCalls += 1;
        return Promise.resolve(summary);
      },
    },
    t(key) { return key; },
    escHtml(value) { return String(value == null ? "" : value); },
    formatRelativeTime() { return ""; },
    formatDate() { return ""; },
    priorityBadge() { return ""; },
    setInterval(callback) { sandbox.refresh = callback; return 1; },
    clearInterval() {},
    setTimeout,
    clearTimeout,
    hideError() {},
    showError() {},
    updateStatusBar() {},
    renderFilesTab() {},
    loadFiles() {},
    ensureActiveChatSession() { return Promise.resolve(); },
    renderChatTab() {},
    focusChatComposer() {},
    summaryCalls() { return summaryCalls; },
    timeline,
  };
  vm.createContext(sandbox);
  vm.runInContext(uiSource + "\n" + agentSource, sandbox);
  return sandbox;
}

test("loadAll keeps an existing snapshot instead of the loading placeholder", () => {
  const sandbox = createSandbox({
    behaviorAdvice: { type: "empty", title: "暂无建议" },
    timeline: [{ label: "昨天", headline: "昨天在开会" }],
  });
  sandbox.loadAll();
  assert.match(sandbox.timeline.innerHTML, /昨天在开会/);
  assert.doesNotMatch(sandbox.timeline.innerHTML, /加载中/);
});

test("budget pauses show a reason and accounting without clearing local facts", () => {
  const sandbox = createSandbox({ timeline: [{ label: "昨天", headline: "本地活动", generationProgress: {
    state: "period_budget", calls: 12, maxCalls: 12, tokens: 240000, maxTokens: 256000, reservedTokens: 4096,
    reason: "PRIVATE_SHOULD_NOT_RENDER", configurationStamp: "PRIVATE_STAMP"
  } }] });
  sandbox.renderTimeline();
  assert.match(sandbox.timeline.innerHTML, /本地活动/);
  assert.match(sandbox.timeline.innerHTML, /timeline.periodBudgetPaused/);
  assert.match(sandbox.timeline.innerHTML, /timeline.summaryBudgetUse/);
  assert.match(sandbox.timeline.innerHTML, /timeline.summaryBudgetEstimate/);
  assert.doesNotMatch(sandbox.timeline.innerHTML, /PRIVATE_/);
});

test("omitted input is visible but representative citation counts do not imply missing topics", () => {
  const sandbox = createSandbox({ timeline: [{ label: "昨天", headline: "主题摘要",
    generationCoverage: { intermediateUnreferencedFacts: 99, finalUnreferencedFacts: 50 } }] });
  sandbox.renderTimeline();
  assert.doesNotMatch(sandbox.timeline.innerHTML, /timeline.summaryPartialInput/);
  sandbox.state.summary.timeline[0].generationCoverage.omittedFacts = 1;
  sandbox.renderTimeline();
  assert.match(sandbox.timeline.innerHTML, /timeline.summaryPartialInput/);
  sandbox.state.summary.timeline[0].generationCoverage = { omittedTopicCards: 1 };
  sandbox.renderTimeline();
  assert.match(sandbox.timeline.innerHTML, /timeline.summaryPartialInput/);
  sandbox.state.summary.timeline[0].generationCoverage = { unresolvedFacts: 1 };
  sandbox.renderTimeline();
  assert.match(sandbox.timeline.innerHTML, /timeline.summaryPartialInput/);
});

test("loadAll without a snapshot leaves the loading placeholder", () => {
  const sandbox = createSandbox(null);
  sandbox.loadAll();
  assert.match(sandbox.timeline.innerHTML, /加载中/);
});

test("failed summary refresh does not clear a rendered timeline", async () => {
  const sandbox = createSandbox({
    behaviorAdvice: { type: "empty", title: "暂无建议" },
    timeline: [{ label: "昨天", headline: "昨天在开会" }],
  });
  sandbox.renderTimeline();
  const before = sandbox.timeline.innerHTML;
  sandbox.api.getSummary = function () { return Promise.reject(new Error("offline")); };
  sandbox.loadAll();
  await new Promise(function (resolve) { setTimeout(resolve, 20); });
  assert.equal(sandbox.timeline.innerHTML, before);
});

test("switching back to the agent tab does not fetch summary", () => {
  const sandbox = createSandbox({
    behaviorAdvice: { type: "empty" },
    timeline: [{ label: "昨天", headline: "昨天在开会" }],
  });
  sandbox.switchTab("chat");
  sandbox.switchTab("agent");
  assert.equal(sandbox.summaryCalls(), 0);
});

test("dashboard refresh and task loading work without removed panel nodes", async () => {
  const sandbox = createSandbox({ timeline: [{ label: "今天", headline: "旧摘要" }] });
  sandbox.api.getTasks = async () => [{ id: "task-1", status: "open", title: "待办" }];
  sandbox.api.getSummary = async () => ({
    ...version,
    behaviorAdvice: { type: "suggestion", title: "不应展示的顶部建议" },
    timeline: [{ label: "今天", headline: "新摘要", suggestion: "保留条目建议" }],
  });
  sandbox.startAutoRefresh();
  sandbox.refresh();
  await new Promise(resolve => setTimeout(resolve, 0));
  assert.match(sandbox.timeline.innerHTML, /新摘要/);
  assert.match(sandbox.timeline.innerHTML, /保留条目建议/);
  assert.doesNotMatch(sandbox.timeline.innerHTML, /不应展示的顶部建议/);
  await sandbox.loadTasks();
  assert.equal(sandbox.state.tasks[0].title, "待办");
  sandbox.api.getTasks = async () => { throw new Error("offline"); };
  await sandbox.loadTasks();
  assert.equal(sandbox.state.tasks[0].id, "task-1");
});

test("empty summary renders only the timeline empty state", () => {
  const sandbox = createSandbox({ behaviorAdvice: null, timeline: [] });
  sandbox.renderTimeline();
  assert.match(sandbox.timeline.innerHTML, /timeline.insufficient/);
  assert.doesNotMatch(sandbox.timeline.innerHTML, /behavior-advice|tasks-empty/);
});

test("event setup without task nodes preserves timeline expand and discuss", () => {
  const sandbox = createSandbox({ timeline: [{ key: "today", label: "今天", headline: "活动摘要" }] });
  const listeners = {};
  const element = () => ({
    addEventListener() {}, querySelector() { return element(); }, querySelectorAll() { return []; },
  });
  for (const node of Object.values(sandbox.state.dom)) {
    if (node && !Array.isArray(node)) Object.assign(node, element());
  }
  sandbox.state.dom = new Proxy(sandbox.state.dom, {
    get(target, key) {
      if (key === "tasksBody") throw new Error("Removed task panel must not be accessed");
      return target[key] ?? element();
    },
  });
  sandbox.timeline.addEventListener = (event, handler) => { listeners[event] = handler; };
  sandbox.document.addEventListener = () => {};
  for (const name of ["handleConfigFieldChange", "openFileStatusEntry", "openFileSettingsModal",
    "closeFileSettingsModal", "saveFileSettings", "closeChat", "sendChatMessage", "sendChatTabMessage"]) {
    sandbox[name] = () => {};
  }
  sandbox.openChatTabWithContext = context => { sandbox.discussContext = context; };
  vm.runInContext(fs.readFileSync(new URL("../../main/resources/desktop-ui/events.js", import.meta.url), "utf8"), sandbox);
  sandbox.setupEvents();
  let expanded = false;
  const entry = { classList: { toggle(name) { assert.equal(name, "expanded"); expanded = !expanded; } } };
  listeners.click({ target: { closest(selector) { return selector === ".timeline-entry" ? entry : null; } } });
  assert.equal(expanded, true);
  const button = { dataset: { entryIdx: "0" }, classList: { contains: name => name === "timeline-entry-discuss-btn" } };
  button.closest = selector => selector === "button" ? button : entry;
  listeners.click({ target: button });
  assert.equal(sandbox.discussContext.type, "timeline_entry");
  assert.equal(sandbox.discussContext.id, "today");
  assert.equal(sandbox.discussContext.headline, "活动摘要");
});

test("old statistics snapshot cannot be reused", () => {
  const sandbox = createSandbox({ timeline: [{ headline: "old" }] });
  delete sandbox.state.summary.statisticsVersion;
  sandbox.loadAll();
  assert.equal(sandbox.state.summary, null);
});

test("unknown activity and estimated coverage remain visible in details", () => {
  const sandbox = createSandbox({ timeline: [{ headline: "Known app", unknownActivitySeconds: 120, coverage: "estimated" }] });
  sandbox.renderTimeline();
  assert.match(sandbox.timeline.innerHTML, /timeline.unknownActivity/);
  assert.match(sandbox.timeline.innerHTML, /timeline.estimated/);
});
