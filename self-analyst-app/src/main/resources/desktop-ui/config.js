/* ============================================================
   SelfAnalyst Desktop - Config Tab (plain-text editor)
   ============================================================ */
"use strict";

// The config modal edits the user config.toml file as raw text.
// SPEC-CFGUI-UI-001 / SPEC-TOML-UI-001: a single monospaced textarea; the file
// name/path shown comes from the API and reports config.toml.

function renderConfigTab() {
  var grid = state.dom.configGrid;
  var readOnly = state.configLoadError ? " readonly" : "";

  var historyCount = (state.configHistory && state.configHistory.length) || 0;
  var historyLabel = t("config.historyVersions") + (historyCount ? " (" + historyCount + ")" : "")
    + (state.configHistoryOpen ? " ▴" : " ▾");

  var allKeysCount = (state.configSupportedKeys && state.configSupportedKeys.length) || 0;
  var allKeysLabel = t("config.allConfigurableKeys") + (allKeysCount ? " (" + allKeysCount + ")" : "")
    + (state.configAllKeysOpen ? " ▴" : " ▾");

  var html = ""
    + '<div class="config-editor-toolbar">'
    +   '<button id="config-allkeys-btn" class="btn btn-sm btn-outline" type="button">' + escHtml(allKeysLabel) + '</button>'
    +   '<button id="config-history-btn" class="btn btn-sm btn-outline" type="button">' + escHtml(historyLabel) + '</button>'
    +   '<span class="config-toolbar-spacer"></span>'
    +   '<button id="test-llm-btn" class="btn btn-sm btn-outline" type="button">' + escHtml(t("config.testLlm")) + '</button>'
    +   '<button id="test-embedding-btn" class="btn btn-sm btn-outline" type="button">' + escHtml(t("config.testEmbedding")) + '</button>'
    + '</div>';

  if (state.configAllKeysOpen) {
    html += renderSupportedKeysPanel();
  }

  if (state.configHistoryOpen) {
    html += renderConfigHistory();
  }

  if (state.configLoadError) {
    html += '<div class="config-load-error">' + escHtml(t("config.loadErrorReadonly")) + '</div>';
  }

  html += '<textarea id="config-raw-editor" class="config-raw-editor" spellcheck="false" wrap="off"'
    + readOnly + '>'
    + escHtml(state.configRawText || "")
    + '</textarea>'
    + renderConfigActionBar();

  html += '<section class="config-section memory-manager-section">' +
    '<div class="config-section-header"><div class="config-section-title">' + escHtml(t("memory.managerTitle")) + '</div></div>' +
    '<div class="config-section-body">' +
    '<input id="memory-manager-search" class="memory-manager-search" type="text" placeholder="' + escHtml(t("memory.searchPlaceholder")) + '">' +
    '<div id="memory-manager-list" class="memory-manager-list"></div>' +
    '</div></section>';

  grid.innerHTML = html;
  updateConfigActionBar();
  renderMemoryManager();
}

function renderConfigActionBar() {
  var result = state.configSaveResult;
  var resultClass = result ? " " + result.type : " hidden";
  var resultText = result ? escHtml(result.msg) : "";

  return (
    '<div class="config-action-bar" id="config-action-bar">' +
    '<div class="config-action-meta">' +
    '<span class="config-action-title">' + escHtml(t("config.changesTitle")) + '</span>' +
    '<span class="config-action-status" id="config-action-status"></span>' +
    '<span class="config-save-result' +
    resultClass +
    '" id="config-save-result">' +
    resultText +
    "</span>" +
    "</div>" +
    '<div class="config-action-buttons">' +
    '<button id="discard-config-btn" class="btn btn-sm btn-outline" type="button">' + escHtml(t("config.discard")) + '</button>' +
    '<button id="save-all-config-btn" class="btn btn-sm btn-primary" type="button">' + escHtml(t("config.saveChanges")) + '</button>' +
    "</div>" +
    "</div>"
  );
}

// ---- All configurable keys reference panel (SPEC-TOML-UI-004) ----

// Read-only reference of every supported key + default. The editor only shows
// user overrides (SPEC-TOML-FMT-001b), so this panel is how a user discovers the
// rest. "Insert" prepends the key as a top-level dotted assignment; nothing here
// writes to disk — the default only becomes an override once the user saves.
function renderSupportedKeysPanel() {
  var keys = state.configSupportedKeys || [];
  if (keys.length === 0) {
    return '<div class="config-allkeys-panel"><div class="config-history-empty">' + escHtml(t("config.noConfigurableKeys")) + '</div></div>';
  }
  // Detect already-present keys from state text (source of truth), not the DOM,
  // since this renders before the textarea is (re)written. SPEC-TOML-UI-004d.
  var present = parseTomlText(state.configRawText || "");

  var rows = keys.map(function (k) {
    var exists = present[k.key] !== undefined;
    var btn = exists
      ? '<span class="config-allkeys-present">' + escHtml(t("config.configured")) + '</span>'
      : '<button class="btn btn-sm btn-outline config-allkeys-insert" data-assignment="'
        + escHtml(k.assignment) + '" type="button">' + escHtml(t("config.insert")) + '</button>';
    return (
      '<div class="config-allkeys-item">' +
      '<div class="config-allkeys-meta">' +
      '<code class="config-allkeys-assign">' + escHtml(k.assignment) + "</code>" +
      '<span class="config-allkeys-type">' + escHtml(k.type || "") + "</span>" +
      "</div>" +
      '<div class="config-allkeys-actions">' + btn + "</div>" +
      "</div>"
    );
  }).join("");

  return '<div class="config-allkeys-panel">' + rows + "</div>";
}

function toggleSupportedKeys() {
  state.configAllKeysOpen = !state.configAllKeysOpen;
  renderConfigTab();
}

// Insert a supported key into its [section] table, mark dirty, and re-render so
// the panel flips this row to "已配置". The key is placed as a dotted in-table key
// under [section] (matching how the backend generator groups keys), because a
// top-level dotted `llm.model` above an existing `[llm]` table is a TOML
// duplicate-table error. When no [section] exists, a fresh table is appended.
// SPEC-TOML-UI-004c.
function insertSupportedKey(assignment) {
  if (!assignment) return;
  var eq = assignment.indexOf("=");
  if (eq < 0) return;
  var dottedKey = assignment.slice(0, eq).trim();
  var valueText = assignment.slice(eq + 1).trim();
  var segs = dottedKey.split(".");
  var section = segs[0];
  var line = segs.slice(1).join(".") + " = " + valueText; // key relative to [section]

  var text = currentEditorText();
  var lines = text.split(/\r?\n/);
  var headerIdx = -1;
  for (var i = 0; i < lines.length; i++) {
    var t = lines[i].replace(/^\s+/, "").replace(/\s+$/, "");
    if (t.charAt(0) === "[") {
      var close = t.indexOf("]");
      if (close > 0 && stripTomlKey(t.slice(1, close).trim()) === section) {
        headerIdx = i;
        break;
      }
    }
  }
  if (headerIdx >= 0) {
    lines.splice(headerIdx + 1, 0, line);
    text = lines.join("\n");
  } else {
    // No such section: append a fresh table at the end (always valid — it is the
    // last table, so no top-level-key-after-table ordering problem).
    var sep = text.length && text.charAt(text.length - 1) !== "\n" ? "\n" : "";
    text = text + sep + "\n[" + section + "]\n" + line + "\n";
  }
  state.configRawText = text;
  state.configDirty = state.configRawText !== state.configRawBaseline;
  state.configSaveResult = null;
  renderConfigTab();
}

// ---- Version history (SPEC-CFGUI-VER) ----

function renderConfigHistory() {
  var list = state.configHistory || [];
  if (state.configHistoryError) {
    return '<div class="config-history-panel"><div class="config-history-empty">' + escHtml(t("config.historyLoadFailed")) + '</div></div>';
  }
  if (list.length === 0) {
    return '<div class="config-history-panel"><div class="config-history-empty">' + escHtml(t("config.historyEmpty")) + '</div></div>';
  }

  var rows = list.map(function (v) {
    var expanded = state.configHistoryExpandedId === v.id;
    var summary = v.summary ? escHtml(v.summary) : escHtml(t("config.summaryGenerating"));
    // Pre-migration snapshots (format !== "toml") are view-only. SPEC-TOML-VER-002.
    var isLegacy = v.format && v.format !== "toml";
    var legacyBadge = isLegacy
      ? '<span class="config-version-legacy">' + escHtml(t("config.legacyPropertiesViewOnly")) + '</span>'
      : "";
    var switchBtn = isLegacy
      ? '<button class="btn btn-sm btn-primary config-history-switch" disabled type="button" title="'
        + escHtml(t("config.legacyPropertiesSwitchDisabled")) + '">' + escHtml(t("config.switch")) + '</button>'
      : '<button class="btn btn-sm btn-primary config-history-switch" data-version-id="' + escHtml(v.id) + '" type="button">'
        + escHtml(t("config.switch")) + '</button>';
    var preview = "";
    if (expanded && typeof v.text === "string") {
      preview = '<pre class="config-history-preview">' + escHtml(v.text) + "</pre>";
    }
    return (
      '<div class="config-history-item" data-version-id="' + escHtml(v.id) + '">' +
      '<div class="config-history-row">' +
      '<div class="config-history-meta">' +
      '<span class="config-history-name">' + escHtml(v.name || "") + "</span>" +
      '<span class="config-history-summary">' + summary + "</span>" +
      legacyBadge +
      "</div>" +
      '<div class="config-history-actions">' +
      '<button class="btn btn-sm btn-outline config-history-view" data-version-id="' + escHtml(v.id) + '" type="button">' +
      (expanded ? escHtml(t("config.collapse")) : escHtml(t("config.view"))) + "</button>" +
      switchBtn +
      "</div>" +
      "</div>" +
      preview +
      "</div>"
    );
  }).join("");

  return '<div class="config-history-panel">' + rows + "</div>";
}

function loadConfigHistory() {
  return api.getConfigHistory()
    .then(function (resp) {
      state.configHistory = (resp && resp.versions) || [];
      state.configHistoryError = false;
    })
    .catch(function () {
      state.configHistory = [];
      state.configHistoryError = true;
    });
}

function toggleConfigHistory() {
  state.configHistoryOpen = !state.configHistoryOpen;
  if (state.configHistoryOpen) {
    loadConfigHistory().then(renderConfigTab);
  } else {
    state.configHistoryExpandedId = null;
    renderConfigTab();
  }
}

function findVersion(id) {
  var list = state.configHistory || [];
  for (var i = 0; i < list.length; i++) {
    if (list[i].id === id) return list[i];
  }
  return null;
}

function toggleVersionPreview(id) {
  if (state.configHistoryExpandedId === id) {
    state.configHistoryExpandedId = null;
    renderConfigTab();
    return;
  }
  var v = findVersion(id);
  if (v && typeof v.text === "string") {
    state.configHistoryExpandedId = id;
    renderConfigTab();
    return;
  }
  // Fetch full text on demand, then expand.
  api.getConfigVersion(id).then(function (detail) {
    if (v) v.text = detail.text;
    state.configHistoryExpandedId = id;
    renderConfigTab();
  }).catch(function (err) {
    state.configSaveResult = { type: "error", msg: t("config.loadVersionFailed", { msg: err.message || t("common.unknownError") }) };
    updateConfigActionBar();
  });
}

// Load a historical version into the editor as unsaved changes; user must save
// to make it take effect. SPEC-CFGUI-VER-UI-002, SPEC-CFGUI-VER-NON-001.
function switchToVersion(id) {
  api.getConfigVersion(id).then(function (detail) {
    state.configRawText = detail.text || "";
    state.configDirty = state.configRawText !== state.configRawBaseline;
    state.configSaveResult = {
      type: "success",
      msg: t("config.versionLoaded", { name: detail.name || "" }),
    };
    renderConfigTab();
  }).catch(function (err) {
    state.configSaveResult = { type: "error", msg: t("config.switchVersionFailed", { msg: err.message || t("common.unknownError") }) };
    updateConfigActionBar();
  });
}

// ---- Editor helpers ----

function currentEditorText() {
  var el = document.getElementById("config-raw-editor");
  return el ? el.value : "";
}

// Lightweight TOML line parser for the test buttons. Tracks the current `[table]`
// header, skips blank/`#` lines, splits `key = value` on the first `=`, strips
// surrounding quotes from keys and values, and composes `table.key` dotted names.
// Top-level dotted keys (`llm.base-url = ...`) pass through unchanged.
// SPEC-TOML-UI-003.
function parseEditorToml() {
  return parseTomlText(currentEditorText());
}

function parseTomlText(text) {
  var map = {};
  var lines = (text || "").split(/\r?\n/);
  var table = "";
  for (var i = 0; i < lines.length; i++) {
    var line = lines[i];
    var trimmed = line.replace(/^\s+/, "").replace(/\s+$/, "");
    if (trimmed === "" || trimmed.charAt(0) === "#") continue;
    if (trimmed.charAt(0) === "[") {
      var close = trimmed.indexOf("]");
      if (close > 0) table = stripTomlKey(trimmed.slice(1, close).trim());
      continue;
    }
    var eq = line.indexOf("=");
    if (eq < 0) continue;
    var key = stripTomlKey(line.slice(0, eq).trim());
    var val = stripTomlValue(line.slice(eq + 1).trim());
    if (!key) continue;
    map[table ? table + "." + key : key] = val;
  }
  return map;
}

function stripQuotesToken(s) {
  if (s.length >= 2) {
    var f = s.charAt(0), l = s.charAt(s.length - 1);
    if ((f === '"' && l === '"') || (f === "'" && l === "'")) return s.slice(1, -1);
  }
  return s;
}

// Strip surrounding quotes from each dotted segment of a key.
function stripTomlKey(k) {
  var parts = k.split(".");
  for (var i = 0; i < parts.length; i++) parts[i] = stripQuotesToken(parts[i].trim());
  return parts.join(".");
}

// Strip surrounding quotes from a value; for unquoted scalars drop inline comments.
function stripTomlValue(v) {
  var s = v;
  if (s.length >= 2) {
    var f = s.charAt(0), l = s.charAt(s.length - 1);
    if ((f === '"' && l === '"') || (f === "'" && l === "'")) return s.slice(1, -1);
  }
  var hash = s.indexOf("#");
  if (hash >= 0) s = s.slice(0, hash).replace(/\s+$/, "");
  return s;
}

function readLlmConfigFromEditor() {
  var m = parseEditorToml();
  var cfg = {};
  if (m["llm.base-url"] !== undefined) cfg.baseUrl = m["llm.base-url"];
  if (m["llm.model"] !== undefined) cfg.model = m["llm.model"];
  if (m["llm.api-key"] !== undefined) cfg.apiKey = m["llm.api-key"];
  return cfg;
}

function readEmbeddingConfigFromEditor() {
  var m = parseEditorToml();
  var cfg = {};
  if (m["embedding.base-url"] !== undefined) cfg.embeddingBaseUrl = m["embedding.base-url"];
  if (m["embedding.model"] !== undefined) cfg.embeddingModel = m["embedding.model"];
  if (m["embedding.api-key"] !== undefined) cfg.embeddingApiKey = m["embedding.api-key"];
  return cfg;
}

// ---- Dirty state ----

function handleConfigFieldChange(e) {
  var target = e.target;
  if (target && target.id === "config-raw-editor") {
    updateConfigDirtyState();
  }
}

function updateConfigDirtyState() {
  state.configRawText = currentEditorText();
  state.configDirty = state.configRawText !== state.configRawBaseline;
  if (state.configDirty) {
    state.configSaveResult = null;
  }
  updateConfigActionBar();
}

function updateConfigActionBar() {
  var status = document.querySelector("#config-action-status");
  var result = document.querySelector("#config-save-result");
  var discardBtn = document.querySelector("#discard-config-btn");
  var saveBtn = document.querySelector("#save-all-config-btn");

  if (status) {
    if (state.configSaving) {
      status.textContent = t("config.saving");
    } else if (state.configDirty) {
      status.textContent = t("config.unsavedChanges");
    } else {
      status.textContent = t("config.noUnsavedChanges");
    }
  }

  if (result) {
    if (state.configSaveResult) {
      result.className = "config-save-result " + state.configSaveResult.type;
      result.textContent = state.configSaveResult.msg;
    } else {
      result.className = "config-save-result hidden";
      result.textContent = "";
    }
  }

  if (discardBtn) {
    discardBtn.disabled = state.configSaving || !state.configDirty;
  }

  if (saveBtn) {
    saveBtn.disabled = state.configSaving || !state.configDirty || state.configLoadError;
    saveBtn.textContent = state.configSaving ? t("config.savingShort") : t("config.saveChanges");
  }
}

function discardConfigChanges() {
  state.configRawText = state.configRawBaseline;
  state.configDirty = false;
  state.configSaveResult = null;
  renderConfigTab();
}

function saveAllConfig() {
  if (state.configSaving || !state.configDirty || state.configLoadError) return;

  var text = currentEditorText();
  state.configRawText = text;
  state.configSaving = true;
  state.configSaveResult = null;
  updateConfigActionBar();

  api.saveRawConfig(text).then(function (resp) {
    state.configRawText = text;
    state.configRawBaseline = text;
    state.configDirty = false;
    state.configSaving = false;

    var msg = t("config.saveSuccess");
    if (resp && resp.restartRequired && resp.restartRequired.length > 0) {
      msg += t("config.restartSuffix", { keys: resp.restartRequired.join(", ") });
    }
    if (resp && resp.unknownKeys && resp.unknownKeys.length > 0) {
      msg += t("config.unknownKeysSuffix", { keys: resp.unknownKeys.join(", ") });
    }
    state.configSaveResult = { type: "success", msg: msg };
    renderConfigTab();
    // A save creates a new version — refresh the list so it shows up.
    loadConfigHistory().then(renderConfigTab);
  }).catch(function (err) {
    // Save failed: keep dirty state and baseline untouched. SPEC-CFGUI-UI-003f.
    state.configSaving = false;
    state.configSaveResult = { type: "error", msg: t("config.saveFailed", { msg: err.message || t("common.unknownError") }) };
    updateConfigActionBar();
  });
}

function renderMemoryManager() {
  var list = document.getElementById("memory-manager-list");
  if (!list) return;
  var search = document.getElementById("memory-manager-search");
  var q = search ? search.value : "";
  var lower = q.toLowerCase();
  var items = state.memoryItems.filter(function (m) {
    return !lower || (m.content || "").toLowerCase().indexOf(lower) >= 0 ||
      (m.evidence || "").toLowerCase().indexOf(lower) >= 0;
  });
  if (items.length === 0) {
    list.innerHTML = '<div class="memory-empty">' + escHtml(t("memory.empty")) + '</div>';
    bindMemoryManager();
    return;
  }
  var html = "";
  items.forEach(function (m) {
    var statusKey = "memory.status." + (m.status || "disabled");
    html += '<div class="memory-manager-item" data-mid="' + escHtml(m.id || "") + '">' +
      '<div class="memory-content">' + escHtml(m.content || "") + '</div>' +
      '<div class="memory-evidence">' + escHtml(t(statusKey)) + ' · ' + escHtml(m.evidence || "") + '</div>' +
      '<div class="memory-manager-actions">' +
      (m.status === "active" ? '<button class="btn btn-sm btn-outline memory-disable">' + escHtml(t("memory.disable")) + '</button>' :
        '<button class="btn btn-sm btn-outline memory-enable">' + escHtml(t("memory.enable")) + '</button>') +
      '<button class="btn btn-sm btn-outline memory-delete">' + escHtml(t("memory.delete")) + '</button>' +
      '</div></div>';
  });
  list.innerHTML = html;
  bindMemoryManager();
}

function bindMemoryManager() {
  var search = document.getElementById("memory-manager-search");
  if (search) search.oninput = renderMemoryManager;
  Array.prototype.forEach.call(document.querySelectorAll(".memory-disable"), function (btn) {
    btn.onclick = function () {
      api.updateMemory(this.closest(".memory-manager-item").dataset.mid, { status: "disabled" })
        .then(loadMemoryForChat)
        .then(renderConfigTab)
        .catch(function (err) { alert(t("memory.saveFailed", { msg: err.message })); });
    };
  });
  Array.prototype.forEach.call(document.querySelectorAll(".memory-enable"), function (btn) {
    btn.onclick = function () {
      api.updateMemory(this.closest(".memory-manager-item").dataset.mid, { status: "active" })
        .then(loadMemoryForChat)
        .then(renderConfigTab)
        .catch(function (err) { alert(t("memory.saveFailed", { msg: err.message })); });
    };
  });
  Array.prototype.forEach.call(document.querySelectorAll(".memory-delete"), function (btn) {
    btn.onclick = function () {
      if (!confirm(t("memory.confirmDelete"))) return;
      api.deleteMemory(this.closest(".memory-manager-item").dataset.mid)
        .then(loadMemoryForChat)
        .then(renderConfigTab)
        .catch(function (err) { alert(t("memory.saveFailed", { msg: err.message })); });
    };
  });
}
