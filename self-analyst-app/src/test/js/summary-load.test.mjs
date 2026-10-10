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

test("blocked daily history shows saved child narratives and the failing hour's diagnostics", () => {
  const sandbox = createSandbox({ timeline: [{ key: "yesterday", label: "昨天", headline: "应用统计",
    source: "wiki-partial", incomplete: true,
    partialSummaries: [{ periodLabel: "10-09 04:00 – 10-09 12:00", summary: "上午原文<script>",
      taskSegments: [{ title: "主题", summary: "完整主题叙述" }] }],
    summaryStatus: { state: "waiting_dependencies", dependencies: [{ periodLabel: "10-09 19:00 – 10-09 20:00",
      state: "failed", generationProgress: { state: "quality", calls: 7, maxCalls: 12, tokens: 31505, maxTokens: 256000,
        nextRetryAt: "2026-10-10T03:42:00Z", configurationStamp: "PRIVATE" } }] }
  }] });
  sandbox.escHtml = value => String(value).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
  sandbox.renderTimeline();
  const html = sandbox.timeline.innerHTML;
  assert.ok(html.indexOf('timeline.historyPartial') < html.indexOf('timeline-entry-detail'));
  assert.ok(html.indexOf('上午原文&lt;script&gt;') < html.indexOf('timeline-entry-detail'));
  assert.equal(html.split('上午原文').length - 1, 1);
  for (const text of ['timeline.historyWaitingDependencies', '上午原文&lt;script&gt;', '完整主题叙述',
    '10-09 19:00', 'timeline.historyQuality', 'timeline.summaryBudgetUse', 'timeline.historyRetryAt']) assert.ok(html.includes(text), text);
  assert.doesNotMatch(html, /<script>|PRIVATE/);
  sandbox.state.summary.timeline[0] = { key: "yesterday", label: "昨天", headline: "完整标题", insight: "完整日概览", source: "wiki" };
  sandbox.renderTimeline();
  assert.match(sandbox.timeline.innerHTML, /完整日概览/);
  assert.doesNotMatch(sandbox.timeline.innerHTML, /historyPartial|上午原文|historyQuality/);
});

test("statistics-only historical fallback is labeled without pretending the model is unconfigured", () => {
  for (const status of ['missing', 'pending', 'skipped', 'unavailable']) {
    const sandbox = createSandbox({ timeline: [{ headline: "统计", source: "local", incomplete: true,
      partialSummaries: [], summaryStatus: { state: status, dependencies: [] } }] });
    sandbox.renderTimeline();
    assert.match(sandbox.timeline.innerHTML, /timeline.historyStatisticsOnly/);
    assert.doesNotMatch(sandbox.timeline.innerHTML, /timeline.llmNotConfigured|historyPartial/);
  }
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

test("hidden dashboard does not poll summaries", async () => {
  const sandbox = createSandbox({ timeline: [] });
  sandbox.document.hidden = true;
  sandbox.startAutoRefresh();
  sandbox.refresh();
  sandbox.refresh();
  await new Promise(resolve => setTimeout(resolve, 0));
  assert.equal(sandbox.summaryCalls(), 0);
});

test("initial load and repeated polling share a pending summary request", async () => {
  const sandbox = createSandbox({ timeline: [] });
  let calls = 0;
  let finish;
  sandbox.api.getSummary = () => {
    calls += 1;
    return new Promise(resolve => { finish = resolve; });
  };
  sandbox.loadAll();
  sandbox.startAutoRefresh();
  sandbox.refresh();
  sandbox.refresh();
  assert.equal(calls, 1);
  finish({ ...version, timeline: [] });
  await new Promise(resolve => setTimeout(resolve, 0));
});

test("restoring visibility refreshes once and shares subsequent polls", async () => {
  const sandbox = createSandbox({ timeline: [] });
  let visibility;
  sandbox.document.addEventListener = (event, listener) => { if (event === "visibilitychange") visibility = listener; };
  sandbox.document.hidden = true;
  sandbox.startAutoRefresh();
  sandbox.startAutoRefresh();
  assert.equal(sandbox.summaryCalls(), 0);
  sandbox.document.hidden = false;
  visibility();
  sandbox.refresh();
  assert.equal(sandbox.summaryCalls(), 1);
  await new Promise(resolve => setTimeout(resolve, 0));
});

test("token usage labels actual, estimated, reserved and legacy amounts separately", () => {
  const sandbox = createSandbox(null);
  const panel = { innerHTML: "" };
  sandbox.document.getElementById = id => id === "token-usage-detail" ? panel : null;
  sandbox.state.usage = { totalTokens: 5000, actualTokens: 100, estimatedTokens: 200, reservedTokens: 4000 };
  sandbox.renderTokenUsage();
  for (const key of ["accounted", "actual", "estimated", "reserved", "unclassified", "explanation"]) {
    assert.match(panel.innerHTML, new RegExp("usage\\." + key));
  }
  assert.match(panel.innerHTML, /usage\.reserved<\/dt><dd>4,000/);
  assert.match(panel.innerHTML, /usage\.unclassified<\/dt><dd>700/);
  sandbox.state.usage = { totalTokens: 5000 };
  sandbox.renderTokenUsage();
  assert.match(panel.innerHTML, /usage\.actual<\/dt><dd>0/);
  assert.match(panel.innerHTML, /usage\.unclassified<\/dt><dd>5,000/);
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
  listeners.click({ target: { closest(selector) { return selector === ".timeline-entry" ? entry : null; } } });
  assert.equal(expanded, false);
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

test("full overview is visible before collapsed details and the topic is secondary", () => {
  const overview = "完整概览第一行。\n" + "其他活动和协作也保留。".repeat(40);
  const sandbox = createSandbox({ timeline: [{ key: "yesterday", label: "昨天", headline: "主要主题", insight: overview,
    summary: "额外信息", evidence: "依据", suggestion: "建议" }] });
  sandbox.renderTimeline();
  const html = sandbox.timeline.innerHTML;
  assert.ok(html.includes('<div class="timeline-entry-overview">' + overview + '</div>'));
  assert.ok(html.indexOf(overview) < html.indexOf('timeline-entry-detail'));
  assert.ok(html.indexOf(overview) < html.indexOf('timeline-entry-topic'));
  assert.equal(html.split(overview).length - 1, 1);
  for (const text of ["主要主题", "额外信息", "依据", "建议", "timeline-entry-discuss-btn"]) assert.ok(html.includes(text));
});

test("overview safely escapes markup and does not duplicate equal headline or summary", () => {
  const sandbox = createSandbox({ timeline: [{ headline: "<img src=x onerror=alert(1)>", insight: "<img src=x onerror=alert(1)>",
    summary: "<img src=x onerror=alert(1)>" }] });
  sandbox.escHtml = value => String(value).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
  sandbox.renderTimeline();
  const html = sandbox.timeline.innerHTML;
  assert.doesNotMatch(html, /<img/);
  assert.equal(html.split('&lt;img').length - 1, 1);
  assert.doesNotMatch(html, /timeline-entry-headline|timeline-entry-summary/);
});

test("empty and legacy nontext insights retain fallback without an empty overview", () => {
  for (const insight of [undefined, null, "", "  ", { legacy: "详情" }]) {
    const sandbox = createSandbox({ timeline: [{ headline: "本地活动", insight, summary: "统计信息" }] });
    sandbox.renderTimeline();
    assert.doesNotMatch(sandbox.timeline.innerHTML, /timeline-entry-overview|timeline-entry-topic/);
    assert.match(sandbox.timeline.innerHTML, /timeline-entry-headline.*本地活动/);
    assert.match(sandbox.timeline.innerHTML, /统计信息/);
    if (insight && typeof insight === "object") assert.match(sandbox.timeline.innerHTML, /legacy/);
  }
});

test("overview refresh uses new payload and retains all period ordering", () => {
  const sandbox = createSandbox({ entries: [
    { key: "current", label: "当前", headline: "当前主题", insight: "当前概览" },
    { key: "yesterday", label: "昨天", headline: "日主题", insight: "日概览" },
    { key: "week", label: "本周", headline: "周主题", insight: "周概览" },
  ] });
  sandbox.renderTimeline();
  assert.ok(sandbox.timeline.innerHTML.indexOf("当前概览") < sandbox.timeline.innerHTML.indexOf("日概览"));
  assert.ok(sandbox.timeline.innerHTML.indexOf("日概览") < sandbox.timeline.innerHTML.indexOf("周概览"));
  sandbox.state.summary.entries[1].insight = "更新后的概览";
  sandbox.renderTimeline();
  assert.match(sandbox.timeline.innerHTML, /更新后的概览/);
  assert.doesNotMatch(sandbox.timeline.innerHTML, />日概览</);
});

test("overview styles preserve full multiline text and wrap long tokens", () => {
  const css = fs.readFileSync(new URL("../../main/resources/desktop-ui/styles.css", import.meta.url), "utf8");
  const rule = css.match(/\.timeline-entry-overview\s*\{([^}]+)\}/)[1];
  assert.match(rule, /white-space:\s*pre-wrap/);
  assert.match(rule, /overflow-wrap:\s*anywhere/);
  assert.doesNotMatch(rule, /line-clamp|overflow:\s*hidden|text-overflow/);
});

test("stored topics render in order with complete escaped multiline narratives inside details", () => {
  const narrative = "第一行\n" + "完整主题内容".repeat(300);
  const sandbox = createSandbox({ timeline: [{ headline: "主题", insight: "概览", taskSegments: [
    { title: "<img src=x>", summary: narrative }, { title: "第二主题", summary: "<script>not executable</script>" },
  ], evidence: [] }] });
  sandbox.escHtml = value => String(value).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
  sandbox.renderTimeline();
  const html = sandbox.timeline.innerHTML;
  assert.ok(html.indexOf('timeline-entry-detail') < html.indexOf('timeline-topic-card'));
  assert.ok(html.indexOf('&lt;img src=x&gt;') < html.indexOf('第二主题'));
  assert.ok(html.includes(narrative));
  assert.ok(html.includes('&lt;script&gt;not executable&lt;/script&gt;'));
  assert.doesNotMatch(html, /<img|<script|timeline.evidence/);
  assert.equal(html.split('timeline-topic-card').length - 1, 2);
});

test("missing and malformed topic collections preserve legacy details", () => {
  for (const taskSegments of [undefined, null, {}, "bad", 7, []]) {
    const sandbox = createSandbox({ timeline: [{ headline: "旧标题", taskSegments, suggestion: "建议", evidence: "依据" }] });
    sandbox.renderTimeline();
    assert.doesNotMatch(sandbox.timeline.innerHTML, /timeline-topic-card/);
    for (const text of ["旧标题", "建议", "依据", "timeline-entry-discuss-btn"]) assert.ok(sandbox.timeline.innerHTML.includes(text));
  }
});

test("malformed topics are skipped and partial text fields remain visible", () => {
  const sandbox = createSandbox({ timeline: [{ headline: "标题", taskSegments: [null, false, [], "bad", {},
    { title: " ", summary: {} }, { title: "仅标题", summary: 12 }, { title: {}, summary: "仅叙述" },
  ] }] });
  sandbox.renderTimeline();
  assert.equal(sandbox.timeline.innerHTML.split('timeline-topic-card').length - 1, 2);
  assert.match(sandbox.timeline.innerHTML, /仅标题/);
  assert.match(sandbox.timeline.innerHTML, /仅叙述/);
  assert.doesNotMatch(sandbox.timeline.innerHTML, /\[object Object\]|>12</);
});

test("empty evidence is hidden while nonempty legacy forms remain escaped", () => {
  for (const evidence of [undefined, null, [], {}, "", " \n", false, 0]) {
    const sandbox = createSandbox({ timeline: [{ headline: "标题", evidence }] });
    sandbox.renderTimeline();
    assert.doesNotMatch(sandbox.timeline.innerHTML, /timeline.evidence|>\[\]</);
    assert.match(sandbox.timeline.innerHTML, /timeline-entry-discuss-btn/);
  }
  for (const evidence of ["依据", ["依据"], { fact: "依据" }]) {
    const sandbox = createSandbox({ timeline: [{ headline: "标题", evidence }] });
    sandbox.renderTimeline();
    assert.match(sandbox.timeline.innerHTML, /timeline.evidence/);
    assert.match(sandbox.timeline.innerHTML, /依据/);
  }
});

test("refresh replaces topic cards and removes newly empty evidence", () => {
  const sandbox = createSandbox({ timeline: [{ headline: "标题", taskSegments: [{ title: "旧主题", summary: "旧叙述" }], evidence: ["旧证据"] }] });
  sandbox.renderTimeline();
  sandbox.state.summary.timeline[0] = { headline: "标题", taskSegments: [{ title: "新主题", summary: "新叙述" }], evidence: [] };
  sandbox.renderTimeline();
  assert.match(sandbox.timeline.innerHTML, /新主题/);
  assert.match(sandbox.timeline.innerHTML, /新叙述/);
  assert.doesNotMatch(sandbox.timeline.innerHTML, /旧主题|旧叙述|旧证据|timeline.evidence/);
});

test("topic styles wrap full titles and narratives without truncation", () => {
  const css = fs.readFileSync(new URL("../../main/resources/desktop-ui/styles.css", import.meta.url), "utf8");
  const rule = css.match(/\.timeline-topic-card \.timeline-detail-label,\s*\.timeline-topic-card \.timeline-detail-text\s*\{([^}]+)\}/)[1];
  assert.match(rule, /white-space:\s*pre-wrap/);
  assert.match(rule, /overflow-wrap:\s*anywhere/);
  assert.doesNotMatch(rule, /line-clamp|overflow:\s*hidden|text-overflow/);
});

// Minimal DOM nodes are reconstructed from each render, like innerHTML in a browser.
function renderedEntries(sandbox) {
  return [...sandbox.timeline.innerHTML.matchAll(/<div class="timeline-entry( expanded)?" data-entry-id="([^"]+)" data-entry-idx="(\d+)">/g)]
    .map(match => {
      let expanded = !!match[1];
      return {
        dataset: { entryId: match[2], entryIdx: match[3] },
        classList: { toggle(name) { assert.equal(name, "expanded"); return expanded = !expanded; } },
        isExpanded: () => expanded,
      };
    });
}
function bindTimelineEvents(sandbox) {
  const listeners = {};
  const element = () => ({ addEventListener() {}, querySelector() { return element(); }, querySelectorAll() { return []; } });
  for (const node of Object.values(sandbox.state.dom)) {
    if (node && !Array.isArray(node)) Object.assign(node, element());
  }
  sandbox.state.dom = new Proxy(sandbox.state.dom, { get(target, key) { return target[key] ?? element(); } });
  sandbox.timeline.addEventListener = (event, handler) => { listeners[event] = handler; };
  sandbox.document.addEventListener = () => {};
  for (const name of ["handleConfigFieldChange", "openFileStatusEntry", "openFileSettingsModal", "closeFileSettingsModal",
    "saveFileSettings", "closeChat", "sendChatMessage", "sendChatTabMessage"]) sandbox[name] = () => {};
  sandbox.openChatTabWithContext = context => { sandbox.discussContext = context; };
  vm.runInContext(fs.readFileSync(new URL("../../main/resources/desktop-ui/events.js", import.meta.url), "utf8"), sandbox);
  sandbox.setupEvents();
  return entry => listeners.click({ target: { closest(selector) { return selector === ".timeline-entry" ? entry : null; } } });
}
const readingSummary = () => ({ assembledAt: "2026-10-09T02:00:00Z", timeline: [
  { key: "current", label: "当前", headline: "本地活动" },
  { key: "yesterday", label: "昨天", headline: "主题", insight: "完整概览", taskSegments: [{ title: "主题一", summary: "完整详情" }] },
] });

test("automatic polling preserves expanded details across changing snapshots and user collapse", async () => {
  const sandbox = createSandbox(readingSummary());
  const click = bindTimelineEvents(sandbox);
  sandbox.renderTimeline();
  click(renderedEntries(sandbox)[1]);
  sandbox.startAutoRefresh();
  for (let cycle = 0; cycle < 5; cycle++) {
    sandbox.api.getSummary = async () => ({ ...version, ...readingSummary(), assembledAt: `2026-10-09T02:0${cycle}:30Z`,
      timeline: readingSummary().timeline.map(entry => ({ ...entry, insight: `更新概览 ${cycle}`, source: `source-${cycle}` })) });
    sandbox.refresh();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(renderedEntries(sandbox)[1].isExpanded(), true);
    assert.match(sandbox.timeline.innerHTML, new RegExp(`更新概览 ${cycle}`));
  }
  click(renderedEntries(sandbox)[1]);
  sandbox.refresh();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(renderedEntries(sandbox)[1].isExpanded(), false);
});

test("repeated delegated clicks alternate reliably without refresh reopening a collapsed item", () => {
  const sandbox = createSandbox(readingSummary());
  const click = bindTimelineEvents(sandbox);
  sandbox.renderTimeline();
  const entry = renderedEntries(sandbox)[1];
  for (let i = 0; i < 10; i++) {
    click(entry);
    assert.equal(entry.isExpanded(), i % 2 === 0);
  }
  sandbox.renderTimeline();
  assert.equal(renderedEntries(sandbox)[1].isExpanded(), false);
});

test("stable identity survives reordering and clears removed entries", () => {
  const sandbox = createSandbox(readingSummary());
  sandbox.renderTimeline();
  sandbox.toggleTimelineEntry(renderedEntries(sandbox)[1]);
  sandbox.state.summary.timeline.reverse();
  sandbox.renderTimeline();
  assert.deepEqual(renderedEntries(sandbox).map(entry => entry.isExpanded()), [true, false]);
  const removed = sandbox.state.summary.timeline.shift();
  sandbox.renderTimeline();
  assert.equal(sandbox.state.timelineExpanded.size, 0);
  sandbox.state.summary.timeline.push(removed);
  sandbox.renderTimeline();
  assert.ok(renderedEntries(sandbox).every(entry => !entry.isExpanded()));
});

test("reading state is scoped to the 04:00 activity date and timezone", () => {
  const sandbox = createSandbox({ ...readingSummary(), assembledAt: "2026-10-08T15:59:00Z" });
  sandbox.renderTimeline();
  sandbox.toggleTimelineEntry(renderedEntries(sandbox)[1]);
  // Local midnight does not change the statistics day.
  sandbox.state.summary.assembledAt = "2026-10-08T19:59:59Z";
  sandbox.renderTimeline();
  assert.equal(renderedEntries(sandbox)[1].isExpanded(), true);
  sandbox.state.summary.assembledAt = "2026-10-08T20:00:00Z";
  sandbox.renderTimeline();
  assert.equal(renderedEntries(sandbox)[1].isExpanded(), false);
  sandbox.toggleTimelineEntry(renderedEntries(sandbox)[1]);
  sandbox.state.summary.timezone = "UTC";
  sandbox.renderTimeline();
  assert.equal(renderedEntries(sandbox)[1].isExpanded(), false);
  assert.notEqual(sandbox.timelineReadingScope({ timezone: "America/New_York", assembledAt: "2026-11-01T08:59:59Z" }),
    sandbox.timelineReadingScope({ timezone: "America/New_York", assembledAt: "2026-11-01T09:00:00Z" }));
});

test("legacy entries without keys retain reading state when fallback text changes", () => {
  const sandbox = createSandbox({ timeline: [{ label: "昨天", headline: "旧标题", insight: { legacy: "详情" } }] });
  sandbox.renderTimeline();
  sandbox.toggleTimelineEntry(renderedEntries(sandbox)[0]);
  sandbox.state.summary.timeline[0].headline = "新标题";
  sandbox.renderTimeline();
  assert.equal(renderedEntries(sandbox)[0].isExpanded(), true);
  assert.match(sandbox.timeline.innerHTML, /legacy/);
  sandbox.state.summary.timeline = [];
  sandbox.renderTimeline();
  assert.equal(sandbox.state.timelineExpanded.size, 0);
});

test("fallback headlines and saved overviews share typography without truncation", () => {
  const css = fs.readFileSync(new URL("../../main/resources/desktop-ui/styles.css", import.meta.url), "utf8");
  const rule = css.match(/\.timeline-entry-headline,\s*\.timeline-entry-overview\s*\{([^}]+)\}/)[1];
  for (const style of [/font-size:\s*14px/, /font-weight:\s*400/, /line-height:\s*1.65/,
    /color:\s*var\(--text-primary\)/, /white-space:\s*pre-wrap/, /overflow-wrap:\s*anywhere/]) assert.match(rule, style);
  assert.doesNotMatch(rule, /line-clamp|overflow:\s*hidden|text-overflow/);
});

test("Java fixed-offset timezone IDs respect the local 04:00 boundary", () => {
  const sandbox = createSandbox(readingSummary());
  for (const zone of ["GMT+08:00", "UTC+08:00", "+08:00", "+0800"]) {
    const scope = assembledAt => sandbox.timelineReadingScope({ timezone: zone, assembledAt });
    assert.notEqual(scope("2026-10-08T19:59:59Z"), scope("2026-10-08T20:00:00Z"));
    assert.equal(scope("2026-10-08T20:00:00Z"), scope("2026-10-09T00:00:00Z"));
  }
  const scope = assembledAt => sandbox.timelineReadingScope({ timezone: "GMT-05:30", assembledAt });
  assert.notEqual(scope("2026-10-09T09:29:59Z"), scope("2026-10-09T09:30:00Z"));
});

test("poll errors keep an expanded snapshot and empty payload clears obsolete state", async () => {
  const sandbox = createSandbox(readingSummary());
  sandbox.renderTimeline();
  sandbox.toggleTimelineEntry(renderedEntries(sandbox)[1]);
  sandbox.renderTimeline();
  sandbox.api.getSummary = async () => { throw new Error("offline"); };
  sandbox.refreshVisiblePage();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(renderedEntries(sandbox)[1].isExpanded(), true);
  sandbox.state.summary = null;
  sandbox.renderTimeline();
  assert.equal(sandbox.state.timelineExpanded.size, 0);
});
