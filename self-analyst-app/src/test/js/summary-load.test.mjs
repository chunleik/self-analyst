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

function createSandbox(summary) {
  const timeline = { innerHTML: summary ? "cached-timeline" : '<div class="loading-placeholder">加载中...</div>' };
  const advice = { innerHTML: summary ? "cached-advice" : '<div class="loading-placeholder">分析行为数据中...</div>' };
  const tasks = { innerHTML: "" };
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
        tasksBody: tasks,
        errorOverlay: { classList: { add() {}, remove() {} } },
        errorMessage: { textContent: "" },
        backendDot: { className: "", title: "" },
        backendText: { textContent: "", title: "" },
        collectorsDot: { className: "", title: "" },
        collectorsText: { textContent: "", title: "" },
        rawDot: { className: "", title: "" },
        rawText: { textContent: "", title: "" },
        fileDot: { className: "", title: "" },
        fileText: { textContent: "", title: "" },
        fileStatusBtn: { title: "" },
        llmDot: { className: "", title: "" },
        llmText: { textContent: "", title: "" },
      },
    },
    document: { getElementById(id) {
      if (id === "behavior-advice-card") return { classList: { remove() {}, add() {} } };
      if (id === "behavior-advice-body") return advice;
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
    advice,
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
  assert.doesNotMatch(sandbox.advice.innerHTML, /分析行为数据中/);
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
