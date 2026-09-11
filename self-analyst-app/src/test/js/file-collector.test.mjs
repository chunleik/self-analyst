import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";
import test from "node:test";

const read = (name) => fs.readFileSync(
  new URL(`../../main/resources/desktop-ui/${name}`, import.meta.url),
  "utf8",
);

function createClassList(initial) {
  const classes = new Set(initial || []);
  return {
    add(name) { classes.add(name); },
    remove(name) { classes.delete(name); },
    toggle(name, force) {
      if (force === true) classes.add(name);
      else if (force === false) classes.delete(name);
      else if (classes.has(name)) classes.delete(name);
      else classes.add(name);
      return classes.has(name);
    },
    contains(name) { return classes.has(name); },
  };
}

function createVisibilitySandbox(overview, status) {
  const fileNavTab = { dataset: { tab: "files" }, classList: createClassList(["hidden"]) };
  const fileStatusBtn = { classList: createClassList(["hidden"]) };
  const fileSettingsModal = { classList: createClassList(["hidden"]) };
  const state = {
    lang: "zh",
    tab: "agent",
    status: status || null,
    filesOverview: overview,
    filesLoading: false,
    filesError: null,
    filesLoadRequestId: 0,
    fileSettingsOpen: false,
    fileSettingsPaths: [],
    fileSettingsEnabled: false,
    fileSettingsSaving: false,
    fileSettingsError: null,
    switchedTo: null,
    settingsOpened: 0,
    dom: {
      fileNavTab,
      fileStatusBtn,
      fileContent: { innerHTML: "" },
      fileTabStatus: { innerHTML: "" },
      fileTabSubtitle: { textContent: "" },
      fileSettingsModal,
      fileSettingsEnabled: { checked: false, disabled: false },
      fileSettingsPaths: {
        innerHTML: "",
        querySelector() { return null; },
        querySelectorAll() { return []; },
      },
      fileSettingsAddBtn: { disabled: false },
      fileSettingsCancelBtn: { disabled: false },
      fileSettingsSaveBtn: { disabled: false, textContent: "" },
      fileSettingsError: { textContent: "", classList: createClassList(["hidden"]) },
    },
  };
  const sandbox = {
    state,
    api: {
      getFiles() { return Promise.resolve(overview); },
    },
    escHtml(value) {
      return String(value == null ? "" : value)
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;");
    },
    formatRelativeTime() { return "2分钟前"; },
    statusBadge(statusValue) { return `<span>${statusValue}</span>`; },
    switchTab(tab) { state.tab = tab; state.switchedTo = tab; },
    console,
  };
  vm.createContext(sandbox);
  vm.runInContext(read("i18n.js"), sandbox);
  sandbox.MESSAGES = Object.fromEntries(["zh", "en"].map(lang => [lang, JSON.parse(fs.readFileSync(new URL("../../main/resources/desktop-ui/locales/" + lang + ".json", import.meta.url), "utf8"))]));
  vm.runInContext(read("files.js"), sandbox);
  const originalOpen = sandbox.openFileSettingsModal;
  sandbox.openFileSettingsModal = function () {
    state.settingsOpened += 1;
    if (typeof originalOpen === "function") return originalOpen();
  };
  return { sandbox, state, fileNavTab, fileStatusBtn };
}

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
  sandbox.MESSAGES = Object.fromEntries(["zh", "en"].map(lang => [lang, JSON.parse(fs.readFileSync(new URL("../../main/resources/desktop-ui/locales/" + lang + ".json", import.meta.url), "utf8"))]));
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

test("file collection tab stays hidden until enabled", () => {
  const html = read("index.html");
  const filesTab = html.match(/<button\b[^>]*data-tab="files"[^>]*>/);
  assert.ok(filesTab, "files tab button exists");
  assert.match(filesTab[0], /\bhidden\b/);
  const statusBtn = html.match(/<button\b[^>]*id="file-status-btn"[^>]*>/);
  assert.ok(statusBtn, "file status button exists");
  assert.match(statusBtn[0], /\bhidden\b/);

  const { sandbox, fileNavTab, fileStatusBtn } = createVisibilitySandbox({
    enabled: false,
    status: "disabled",
    roots: [],
    files: [],
  });

  assert.equal(sandbox.isFileCollectionEnabled(sandbox.state.filesOverview, sandbox.state.status), false);
  sandbox.syncFileUiVisibility();
  assert.equal(fileNavTab.classList.contains("hidden"), true);
  assert.equal(fileStatusBtn.classList.contains("hidden"), true);
  assert.equal(sandbox.state.switchedTo, null);
});

test("enabled file collection reveals the files tab", () => {
  const { sandbox, fileNavTab, fileStatusBtn } = createVisibilitySandbox({
    enabled: true,
    status: "running",
    roots: [{ path: "D:\\Docs" }],
    files: [],
  });

  assert.equal(sandbox.isFileCollectionEnabled(sandbox.state.filesOverview, sandbox.state.status), true);
  sandbox.syncFileUiVisibility();
  assert.equal(fileNavTab.classList.contains("hidden"), false);
  assert.equal(fileStatusBtn.classList.contains("hidden"), false);
});

test("disabling file collection leaves the files page", () => {
  const { sandbox, fileNavTab, fileStatusBtn, state } = createVisibilitySandbox({
    enabled: false,
    status: "disabled",
    roots: [],
    files: [],
  });
  state.tab = "files";

  sandbox.syncFileUiVisibility();

  assert.equal(fileNavTab.classList.contains("hidden"), true);
  assert.equal(fileStatusBtn.classList.contains("hidden"), true);
  assert.equal(state.tab, "agent");
  assert.equal(state.switchedTo, "agent");
});

test("file status entry opens settings when collection is disabled", () => {
  const { sandbox, state } = createVisibilitySandbox({
    enabled: false,
    status: "disabled",
    roots: [],
    files: [],
  });

  sandbox.openFileStatusEntry();

  assert.equal(state.settingsOpened, 1);
  assert.notEqual(state.switchedTo, "files");
});

test("settings modal only manages the collection toggle", () => {
  const html = read("index.html");
  const modal = html.match(/id="file-settings-modal"[\s\S]*?<\/div>\s*<\/div>\s*<\/div>/)[0];
  assert.match(modal, /id="file-settings-enabled"/);
  assert.doesNotMatch(modal, /id="file-settings-paths"/);
  assert.doesNotMatch(modal, /id="file-settings-add-btn"/);
  assert.doesNotMatch(modal, /file.watchedFolders/);
});

test("enabled file page hosts watched-folder editing", () => {
  const { sandbox, elements } = createSandbox({
    enabled: true,
    status: "degraded",
    reason: "paths_unavailable",
    totals: {},
    roots: [],
    files: [],
  });

  sandbox.renderFilesTab();

  assert.match(elements.content.innerHTML, /data-file-action="add-folder"/);
  assert.match(elements.content.innerHTML, /data-file-path/);
  assert.match(elements.content.innerHTML, /data-file-action="save-folders"/);
  assert.doesNotMatch(elements.content.innerHTML, /data-file-action="settings"/);
});

test("saving the toggle keeps current watched folders", async () => {
  const calls = [];
  const { sandbox, state } = createVisibilitySandbox({
    enabled: false,
    status: "disabled",
    roots: [{ path: "D:\\Docs" }],
    files: [],
  });
  state.dom.fileSettingsEnabled.checked = true;
  state.fileSettingsEnabled = false;
  sandbox.api.saveFileSettings = function (body) {
    calls.push(body);
    return Promise.resolve({
      enabled: true,
      status: "running",
      roots: [{ path: "D:\\Docs" }],
      files: [],
    });
  };
  sandbox.api.getStatus = function () { return Promise.resolve({ collectors: { file: "running" } }); };
  sandbox.updateStatusBar = function () {};

  await sandbox.saveFileSettings();

  assert.equal(calls.length, 1);
  assert.equal(calls[0].enabled, true);
  assert.deepEqual(Array.from(calls[0].paths || []), ["D:\\Docs"]);
});

test("file status entry opens the files tab when collection is enabled", () => {
  const { sandbox, state } = createVisibilitySandbox({
    enabled: true,
    status: "running",
    roots: [{ path: "D:\\Docs" }],
    files: [],
  });

  sandbox.openFileStatusEntry();

  assert.equal(state.switchedTo, "files");
  assert.equal(state.settingsOpened, 0);
});
