/* ============================================================
   SelfAnalyst Desktop - Config Tab (plain-text editor)
   ============================================================ */
"use strict";

// The config modal edits the user config.properties file as raw text.
// SPEC-CFGUI-UI-001: a single monospaced textarea replaces the structured form.

function renderConfigTab() {
  var grid = state.dom.configGrid;
  var readOnly = state.configLoadError ? " readonly" : "";

  var historyCount = (state.configHistory && state.configHistory.length) || 0;
  var historyLabel = t("config.historyVersions") + (historyCount ? " (" + historyCount + ")" : "")
    + (state.configHistoryOpen ? " ▴" : " ▾");

  var html = ""
    + '<div class="config-editor-toolbar">'
    +   '<button id="config-history-btn" class="btn btn-sm btn-outline" type="button">' + escHtml(historyLabel) + '</button>'
    +   '<span class="config-toolbar-spacer"></span>'
    +   '<button id="test-llm-btn" class="btn btn-sm btn-outline" type="button">' + escHtml(t("config.testLlm")) + '</button>'
    +   '<button id="test-embedding-btn" class="btn btn-sm btn-outline" type="button">' + escHtml(t("config.testEmbedding")) + '</button>'
    + '</div>';

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

  grid.innerHTML = html;
  updateConfigActionBar();
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
      "</div>" +
      '<div class="config-history-actions">' +
      '<button class="btn btn-sm btn-outline config-history-view" data-version-id="' + escHtml(v.id) + '" type="button">' +
      (expanded ? escHtml(t("config.collapse")) : escHtml(t("config.view"))) + "</button>" +
      '<button class="btn btn-sm btn-primary config-history-switch" data-version-id="' + escHtml(v.id) + '" type="button">' + escHtml(t("config.switch")) + '</button>' +
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

// Lightweight .properties line parser for the test buttons. Skips blank lines
// and `#`/`!` comments; splits on the first `=` or `:`. SPEC-CFGUI-UI-005b.
function parseEditorProps() {
  var map = {};
  var lines = currentEditorText().split(/\r?\n/);
  for (var i = 0; i < lines.length; i++) {
    var line = lines[i];
    var trimmed = line.replace(/^\s+/, "");
    if (trimmed === "" || trimmed.charAt(0) === "#" || trimmed.charAt(0) === "!") continue;
    var eq = line.indexOf("=");
    var colon = line.indexOf(":");
    var sep = eq;
    if (sep < 0 || (colon >= 0 && colon < sep)) sep = colon;
    if (sep < 0) continue;
    var key = line.slice(0, sep).trim();
    var val = line.slice(sep + 1).trim();
    if (key) map[key] = val;
  }
  return map;
}

function readLlmConfigFromEditor() {
  var m = parseEditorProps();
  var cfg = {};
  if (m["llm.base-url"] !== undefined) cfg.baseUrl = m["llm.base-url"];
  if (m["llm.model"] !== undefined) cfg.model = m["llm.model"];
  if (m["llm.api-key"] !== undefined) cfg.apiKey = m["llm.api-key"];
  return cfg;
}

function readEmbeddingConfigFromEditor() {
  var m = parseEditorProps();
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
