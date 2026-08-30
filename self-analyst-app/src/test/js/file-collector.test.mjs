import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";
import test from "node:test";

const read = (name) => fs.readFileSync(
  new URL(`../../main/resources/desktop-ui/${name}`, import.meta.url),
  "utf8",
);

function createSandbox(overview, getFiles) {
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
    filesLoadRequestId: 0,
    dom: {
      fileContent: elements.content,
      fileTabStatus: elements.status,
      fileTabSubtitle: elements.subtitle,
    },
  };
  const sandbox = {
    state,
    api: { getFiles: getFiles || function () { return Promise.resolve(overview); } },
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

function deferred() {
  var resolve;
  var reject;
  var promise = new Promise(function (resolvePromise, rejectPromise) {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

test("running file collector renders status, counts, roots, and metadata only", () => {
  const overview = {
    status: "running",
    latestCollectedAt: "2026-08-30T01:00:00Z",
    latestPath: "D:\\Docs\\design.md",
    totals: { collected: 12, pending: 2, failed: 0 },
    roots: [{
      path: "D:\\Docs",
      counts: { collected: 12, pending: 2, failed: 0 },
    }],
    files: [{
      name: "design.md",
      path: "D:\\Docs\\design.md",
      relativePath: "design.md",
      sizeBytes: 2048,
      fileCreatedAt: "2026-08-29T01:00:00Z",
      lastModified: "2026-08-30T00:30:00Z",
      lastCollectedAt: "2026-08-30T01:00:00Z",
    }],
  };
  const { sandbox, elements } = createSandbox(overview);

  sandbox.renderFilesTab();

  assert.match(elements.content.innerHTML, /design\.md/);
  assert.match(elements.content.innerHTML, /2\.0 KB/);
  assert.doesNotMatch(elements.content.innerHTML, /摘要|主题|summary/);
  assert.match(elements.content.innerHTML, /正在监控 1 个目录/);
  assert.match(elements.content.innerHTML, />12<\/strong><span>已采集/);
  assert.match(elements.content.innerHTML, /隐私提示/);
  assert.match(elements.content.innerHTML, /不读取文件正文/);
  assert.equal(elements.subtitle.textContent, "最近一次采集：2分钟前");
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

  assert.match(elements.content.innerHTML, /了解文件变更，不读取文件正文/);
  assert.match(elements.content.innerHTML, /data-file-action="settings"/);
  assert.match(elements.content.innerHTML, /无需重启/);
});

test("degraded reason and technical detail are escaped", () => {
  const { sandbox, elements } = createSandbox({
    status: "degraded",
    reason: "worker_start_failed",
    error: "<worker failed>",
    totals: {},
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

test("empty extension allowlist has a diagnostic degraded message", () => {
  const { sandbox, elements } = createSandbox({
    status: "degraded",
    reason: "extensions_required",
    totals: {},
    roots: [{ path: "D:\\Docs", counts: {} }],
    files: [],
  });

  sandbox.renderFilesTab();

  assert.match(elements.content.innerHTML, /扩展名白名单为空/);
  assert.match(elements.content.innerHTML, /\*/);
});

test("newer file overview wins when requests resolve out of order", async () => {
  const first = deferred();
  const second = deferred();
  let calls = 0;
  const { sandbox } = createSandbox(null, () => calls++ === 0 ? first.promise : second.promise);
  const older = { status: "running", latestPath: "older.txt", totals: {}, roots: [], files: [] };
  const newer = { status: "running", latestPath: "newer.txt", totals: {}, roots: [], files: [] };

  const firstLoad = sandbox.loadFiles();
  const secondLoad = sandbox.loadFiles();
  second.resolve(newer);
  await secondLoad;
  first.resolve(older);
  await firstLoad;

  assert.equal(sandbox.state.filesOverview.latestPath, "newer.txt");
  assert.equal(sandbox.state.filesError, null);
  assert.equal(sandbox.state.filesLoading, false);
});

test("stale file overview failure cannot overwrite a newer success", async () => {
  const first = deferred();
  const second = deferred();
  let calls = 0;
  const { sandbox } = createSandbox(null, () => calls++ === 0 ? first.promise : second.promise);
  const newer = { status: "running", latestPath: "newer.txt", totals: {}, roots: [], files: [] };

  const firstLoad = sandbox.loadFiles();
  const secondLoad = sandbox.loadFiles();
  second.resolve(newer);
  await secondLoad;
  first.reject(new Error("stale failure"));
  await firstLoad;

  assert.equal(sandbox.state.filesOverview.latestPath, "newer.txt");
  assert.equal(sandbox.state.filesError, null);
  assert.equal(sandbox.state.filesLoading, false);
});

test("stale file completion cannot finish or render while the latest request is pending", async () => {
  const first = deferred();
  const second = deferred();
  let calls = 0;
  const { sandbox } = createSandbox(null, () => calls++ === 0 ? first.promise : second.promise);
  const originalRender = sandbox.renderFilesTab;
  let renders = 0;
  sandbox.renderFilesTab = function () {
    renders++;
    return originalRender();
  };

  const firstLoad = sandbox.loadFiles();
  const secondLoad = sandbox.loadFiles();
  const rendersBeforeStaleCompletion = renders;
  first.resolve({ status: "running", latestPath: "older.txt", totals: {}, roots: [], files: [] });
  await firstLoad;

  assert.equal(sandbox.state.filesOverview, null);
  assert.equal(sandbox.state.filesLoading, true);
  assert.equal(renders, rendersBeforeStaleCompletion);

  second.resolve({ status: "running", latestPath: "newer.txt", totals: {}, roots: [], files: [] });
  await secondLoad;
  assert.equal(sandbox.state.filesLoading, false);
  assert.equal(renders, rendersBeforeStaleCompletion + 1);
});

test("desktop shell and API expose the file collector page", () => {
  const html = read("index.html");
  const api = read("api.js");
  const config = read("config.js");

  assert.match(html, /data-tab="files"/);
  assert.match(html, /id="file-status-btn"/);
  assert.match(html, /id="file-settings-modal"/);
  assert.match(html, /src="files\.js"/);
  assert.match(api, /\/desktop\/files\?limit=/);
  assert.match(api, /\/desktop\/files\/settings/);
  assert.match(config, /focusConfigEditorKey/);
});

test("file settings render one editable row per watched folder", () => {
  const { sandbox } = createSandbox(null);

  const html = sandbox.renderFileSettingsPaths(["D:\\Docs", "E:\\Notes"]);

  assert.equal((html.match(/data-file-path data-file-path-index=/g) || []).length, 2);
  assert.match(html, /D:\\Docs/);
  assert.match(html, /E:\\Notes/);
  assert.match(html, /data-file-settings-remove="1"/);

  const savingHtml = sandbox.renderFileSettingsPaths(["D:\\Docs"], true);
  assert.match(savingHtml, /data-file-path-index="0" disabled/);
  assert.match(savingHtml, /data-file-settings-remove="0"[^>]* disabled/);
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
