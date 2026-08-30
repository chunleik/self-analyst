import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";
import test from "node:test";

const read = (name) => fs.readFileSync(
  new URL(`../../main/resources/desktop-ui/${name}`, import.meta.url),
  "utf8",
);

function createSandbox(overview) {
  const elements = {
    content: { innerHTML: "" },
    status: { innerHTML: "" },
    subtitle: { textContent: "" },
  };
  const state = {
    lang: "zh",
    filesOverview: overview,
    filesLoading: false,
    filesError: null,
    dom: {
      fileContent: elements.content,
      fileTabStatus: elements.status,
      fileTabSubtitle: elements.subtitle,
    },
  };
  const sandbox = {
    state,
    api: { getFiles() { return Promise.resolve(overview); } },
    escHtml(value) {
      return String(value == null ? "" : value)
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;");
    },
    formatRelativeTime() { return "2分钟前"; },
    statusBadge(status) { return `<span>${status}</span>`; },
    console,
  };
  vm.createContext(sandbox);
  vm.runInContext(read("i18n.js"), sandbox);
  vm.runInContext(read("files.js"), sandbox);
  return { sandbox, elements };
}

test("running file collector renders status, counts, roots, and recent summaries", () => {
  const overview = {
    status: "running",
    latestIndexedAt: "2026-08-30T01:00:00Z",
    latestPath: "D:\\Docs\\design.md",
    totals: { indexed: 12, pending: 2, failed: 0 },
    semantic: { configured: true, available: true },
    roots: [{
      path: "D:\\Docs",
      counts: { indexed: 12, pending: 2, failed: 0 },
    }],
    files: [{
      path: "D:\\Docs\\design.md",
      relativePath: "design.md",
      summary: "文件采集界面设计",
      mainTopics: ["文件采集", "界面"],
      lastIndexedAt: "2026-08-30T01:00:00Z",
    }],
  };
  const { sandbox, elements } = createSandbox(overview);

  sandbox.renderFilesTab();

  assert.match(elements.content.innerHTML, /design\.md/);
  assert.match(elements.content.innerHTML, /文件采集界面设计/);
  assert.match(elements.content.innerHTML, /正在监控 1 个目录/);
  assert.match(elements.content.innerHTML, />12<\/strong><span>已索引/);
  assert.match(elements.content.innerHTML, /隐私提示/);
  assert.equal(elements.subtitle.textContent, "最近一次索引：2分钟前");
});

test("disabled file collector remains discoverable with a configuration action", () => {
  const { sandbox, elements } = createSandbox({
    status: "disabled",
    reason: "disabled_by_config",
    totals: {},
    roots: [],
    files: [],
  });

  sandbox.renderFilesTab();

  assert.match(elements.content.innerHTML, /让 Agent 理解你的本地文档/);
  assert.match(elements.content.innerHTML, /data-file-action="settings"/);
  assert.match(elements.content.innerHTML, /重启 SelfAnalyst/);
});

test("degraded reason and technical detail are escaped", () => {
  const { sandbox, elements } = createSandbox({
    status: "degraded",
    reason: "worker_start_failed",
    error: "<worker failed>",
    totals: {},
    semantic: { configured: false, available: false },
    roots: [],
    files: [],
  });

  sandbox.renderFilesTab();

  assert.match(elements.content.innerHTML, /文件采集后台任务启动失败/);
  assert.match(elements.content.innerHTML, /&lt;worker failed&gt;/);
  assert.doesNotMatch(elements.content.innerHTML, /<worker failed>/);
  assert.match(elements.content.innerHTML, /data-file-action="retry"/);
  assert.match(elements.content.innerHTML, /已配置 0 个目录/);
});

test("desktop shell and API expose the file collector page", () => {
  const html = read("index.html");
  const api = read("api.js");
  const config = read("config.js");

  assert.match(html, /data-tab="files"/);
  assert.match(html, /id="file-status-btn"/);
  assert.match(html, /src="files\.js"/);
  assert.match(api, /\/desktop\/files\?limit=/);
  assert.match(config, /focusConfigEditorKey/);
});

test("file settings focus supports the generated parent-table template syntax", () => {
  const editor = {
    value: '[file]\n# watch.enabled = false\n# watch.paths = ""\n',
    selectionStart: 0,
    selectionEnd: 0,
    focused: false,
    focus() { this.focused = true; },
    setSelectionRange(start, end) {
      this.selectionStart = start;
      this.selectionEnd = end;
    },
  };
  const sandbox = {
    document: { getElementById() { return editor; } },
    state: {},
    t(key) { return key; },
    escHtml(value) { return String(value || ""); },
    console,
  };
  vm.createContext(sandbox);
  vm.runInContext(read("config.js"), sandbox);

  sandbox.focusConfigEditorKey("file.watch.enabled");

  assert.equal(editor.value.slice(editor.selectionStart, editor.selectionEnd), "watch.enabled");
  assert.equal(editor.focused, true);
});

test("file settings focus also supports an explicit nested table", () => {
  const editor = {
    value: '[file.watch]\nenabled = true\npaths = "D:/Docs"\n',
    selectionStart: 0,
    selectionEnd: 0,
    focus() {},
    setSelectionRange(start, end) {
      this.selectionStart = start;
      this.selectionEnd = end;
    },
  };
  const sandbox = {
    document: { getElementById() { return editor; } },
    state: {},
    t(key) { return key; },
    escHtml(value) { return String(value || ""); },
    console,
  };
  vm.createContext(sandbox);
  vm.runInContext(read("config.js"), sandbox);

  sandbox.focusConfigEditorKey("file.watch.enabled");

  assert.equal(editor.value.slice(editor.selectionStart, editor.selectionEnd), "enabled");
});
