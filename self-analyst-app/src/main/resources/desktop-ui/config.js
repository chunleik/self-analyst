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

  var html = ""
    + '<div class="config-file-heading">'
    +   '<code>config.toml</code>'
    +   '<span title="' + escHtml(state.configPath || "config.toml") + '">' + escHtml(state.configPath || "config.toml") + '</span>'
    + '</div>'
    + '<div class="config-editor-toolbar">'
    +   '<button id="test-llm-btn" class="btn btn-sm btn-outline" type="button">' + escHtml(t("config.testLlm")) + '</button>'
    +   '<button id="test-embedding-btn" class="btn btn-sm btn-outline" type="button">' + escHtml(t("config.testEmbedding")) + '</button>'
    + '</div>'
    + renderConfigActionBar();

  if (state.configLoadError) {
    html += '<div class="config-load-error">' + escHtml(t("config.loadErrorReadonly")) + '</div>';
  }

  html += '<textarea id="config-raw-editor" class="config-raw-editor" spellcheck="false" wrap="off"'
    + readOnly + '>'
    + escHtml(state.configRawText || "")
    + '</textarea>';

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
  }).catch(function (err) {
    // Save failed: keep dirty state and baseline untouched. SPEC-CFGUI-UI-003f.
    state.configSaving = false;
    state.configSaveResult = { type: "error", msg: t("config.saveFailed", { msg: err.message || t("common.unknownError") }) };
    updateConfigActionBar();
  });
}
