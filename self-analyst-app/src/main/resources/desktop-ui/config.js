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
    + renderLanguageSetting(state.configRawText || "")
    + renderConfigActionBar()
    + '<div id="config-runtime-status" class="config-runtime-status" aria-live="polite">' + renderConfigRuntime() + "</div>";

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

function focusConfigEditorKey(key) {
  var editor = document.getElementById("config-raw-editor");
  if (!editor || !key) return;
  var table = "";
  var lineStart = 0;
  while (lineStart <= editor.value.length) {
    var newline = editor.value.indexOf("\n", lineStart);
    var lineEnd = newline >= 0 ? newline : editor.value.length;
    var line = editor.value.slice(lineStart, lineEnd).replace(/\r$/, "");
    var tableMatch = /^\s*\[([^\]]+)]\s*(?:#.*)?$/.exec(line);
    if (tableMatch) {
      table = stripTomlKey(tableMatch[1].trim());
    } else {
      // Generated templates comment assignments, for example:
      // [file] + "# watch.enabled = false".
      var assignment = /^(\s*#?\s*)([A-Za-z0-9_.-]+)\s*=/.exec(line);
      if (assignment) {
        var assignmentKey = stripTomlKey(assignment[2]);
        var fullKey = table ? table + "." + assignmentKey : assignmentKey;
        if (fullKey === key) {
          var index = lineStart + assignment[1].length;
          editor.focus();
          editor.setSelectionRange(index, index + assignment[2].length);
          return;
        }
      }
    }
    if (newline < 0) break;
    lineStart = newline + 1;
  }
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
  if (target && target.id === "config-language") {
    var editor = document.getElementById("config-raw-editor");
    var updated = editLanguageDraft(editor.value, target.value);
    if (updated != null && !state.configSaving && !state.configLoadError) {
      editor.value = updated;
      updateConfigDirtyState();
    }
    return;
  }
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
  var language = document.getElementById("config-language-setting");
  if (language) language.outerHTML = renderLanguageSetting(state.configRawText);
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

  var languageSelect = document.getElementById("config-language");
  if (languageSelect) {
    languageSelect.disabled = state.configSaving || state.configLoadError || !languageDraft(currentEditorText());
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
      if (resp.restartRequired.indexOf("app.language") >= 0) msg += " " + t("config.languageRestart");
    }
    if (resp && resp.unknownKeys && resp.unknownKeys.length > 0) {
      msg += t("config.unknownKeysSuffix", { keys: resp.unknownKeys.join(", ") });
    }
    state.configSaveResult = { type: "success", msg: msg };
    if (resp && resp.application) state.configRuntime = { application: resp.application };
    renderConfigTab();
    refreshConfigRuntime();
  }).catch(function (err) {
    // Save failed: keep dirty state and baseline untouched. SPEC-CFGUI-UI-003f.
    state.configSaving = false;
    state.configSaveResult = { type: "error", msg: t("config.saveFailed", { msg: err.message || t("common.unknownError") }) };
    updateConfigActionBar();
  });
}


// 只刷新只读运行状态，绝不重新渲染或覆盖正在编辑的 TOML。
var configRuntimeTimer = null;
var configRuntimeRequest = 0;

function stopConfigRuntimeRefresh() {
  if (configRuntimeTimer !== null) clearTimeout(configRuntimeTimer);
  configRuntimeTimer = null;
  configRuntimeRequest++;
}

function renderConfigRuntime() {
  if (state.configRuntimeError) return '<p>' + escHtml(t("config.runtimeLoadFailed")) + '</p>';
  var data = state.configRuntime;
  if (!data) return "";
  var application = data.application || {};
  var html = '<strong>' + escHtml(t("config.runtimeTitle")) + '</strong><ul>';
  Object.keys(application).forEach(function (component) {
    var item = application[component];
    html += '<li>' + escHtml(component) + ': ' + escHtml(t("config.runtime." + item.status));
    if (item.changedKeys && item.changedKeys.length) html += ' (' + escHtml(item.changedKeys.join(", ")) + ')';
    html += '</li>';
  });
  html += '</ul>';
  if (data.configured) {
    html += '<details><summary>' + escHtml(t("config.sourcesTitle")) + '</summary><table><thead><tr>'
      + '<th>' + escHtml(t("config.key")) + '</th><th>' + escHtml(t("config.savedValue")) + '</th>'
      + '<th>' + escHtml(t("config.runningValue")) + '</th><th>' + escHtml(t("config.source")) + '</th></tr></thead><tbody>';
    ["llm.api-key", "llm.base-url", "llm.model", "llm.temperature", "llm.max-tokens"].forEach(function (key) {
      var value = data.configured[key];
      if (!value) return;
      html += '<tr><td>' + escHtml(key) + '</td><td>' + escHtml(value.value) + '</td><td>'
        + escHtml(data.running && data.running[key] != null ? String(data.running[key]) : "")
        + '</td><td>' + escHtml(t("config.source." + value.source)) + '</td></tr>';
    });
    html += '</tbody></table></details>';
  }
  return html;
}

function refreshConfigRuntime(remaining) {
  if (!api.getEffectiveConfig) return Promise.resolve();
  stopConfigRuntimeRefresh();
  var request = configRuntimeRequest;
  var attempts = typeof remaining === "number" ? remaining : 300;
  return api.getEffectiveConfig().then(function (data) {
    if (request !== configRuntimeRequest || !state.configOpen) return;
    var changed = state.configRuntimeError || JSON.stringify(state.configRuntime) !== JSON.stringify(data);
    state.configRuntime = data;
    state.configRuntimeError = false;
    var target = document.getElementById("config-runtime-status");
    if (target && changed) {
      var details = target.querySelector ? target.querySelector("details") : null;
      var expanded = details && details.open;
      target.innerHTML = renderConfigRuntime();
      if (expanded && target.querySelector) {
        details = target.querySelector("details");
        if (details) details.open = true;
      }
    }
    var application = data.application || {};
    var draining = Object.keys(application).some(function (key) {
      return application[key].activeWorkCount > 0;
    });
    if (draining && attempts > 0) {
      configRuntimeTimer = setTimeout(function () { refreshConfigRuntime(attempts - 1); }, 2000);
    }
  }).catch(function () {
    if (request !== configRuntimeRequest || !state.configOpen) return;
    state.configRuntimeError = true;
    var target = document.getElementById("config-runtime-status");
    if (target) target.innerHTML = renderConfigRuntime();
  });
}
// 保守识别可无损修改的单行 TOML；复杂合法语法继续由原始编辑器处理。
function languageDraft(text) {
  var table = "", seen = Object.create(null), tables = Object.create(null), implicit = Object.create(null);
  var found = null, insertion = null, offset = 0;
  var lines = text.split(/(?<=\n)/);
  var scalar = /^(?:"(?:[^"\\\x00-\x08\x0a-\x1f]|\\["\\btnfr])*"|'[^'\x00-\x08\x0a-\x1f]*'|true|false|[+-]?(?:0|[1-9](?:_?[0-9])*)(?:\.[0-9](?:_?[0-9])*)?(?:[eE][+-]?[0-9](?:_?[0-9])*)?)\s*(?:#.*)?$/;
  for (var i = 0; i < lines.length; i++) {
    var raw = lines[i], line = raw.replace(/[\r\n]+$/, "");
    if (/^\s*(?:#.*)?$/.test(line)) { offset += raw.length; continue; }
    var header = /^\s*\[([A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*)\]\s*(?:#.*)?$/.exec(line);
    if (header) {
      table = header[1];
      if (tables[table] || implicit[table] || Object.keys(seen).some(function (key) { return table === key || table.indexOf(key + ".") === 0; })) return null;
      tables[table] = true;
      if (table === "app") insertion = offset + raw.length;
      offset += raw.length; continue;
    }
    var assignment = /^(\s*)([A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*)(\s*=\s*)(.*)$/.exec(line);
    if (!assignment || !scalar.test(assignment[4]) || /"""|'''/.test(assignment[4])) return null;
    var key = (table ? table + "." : "") + assignment[2];
    if (Object.keys(seen).some(function (previous) { return previous === key || key.indexOf(previous + ".") === 0 || previous.indexOf(key + ".") === 0; })) return null;
    var parts = assignment[2].split(".");
    for (var part = 1; part < parts.length; part++) implicit[(table ? table + "." : "") + parts.slice(0, part).join(".")] = true;
    seen[key] = true;
    if (key === "app.language") {
      var value = /^("(?:[^"\\]|\\.)*"|'[^']*')/.exec(assignment[4]);
      if (!value) return null;
      found = { start: offset + assignment[1].length + assignment[2].length + assignment[3].length,
        length: value[0].length, value: value[0].slice(1, -1) };
    }
    offset += raw.length;
  }
  if (seen.app || implicit["app.language"] || Object.keys(tables).some(function (key) {
    return key === "app.language" || key.indexOf("app.language.") === 0;
  })) return null;
  return { found: found, insertion: insertion, implicitApp: !!implicit.app };
}

function editLanguageDraft(text, code) {
  var languages = state.status && state.status.languages || [];
  if (code !== "auto" && !languages.some(function (lang) { return lang.code === code; })) return null;
  var draft = languageDraft(text);
  if (!draft) return null;
  if (draft.found) return text.slice(0, draft.found.start) + '"' + code + '"' + text.slice(draft.found.start + draft.found.length);
  var newline = text.indexOf("\r\n") >= 0 ? "\r\n" : "\n";
  if (draft.implicitApp && draft.insertion == null) return 'app.language = "' + code + '"' + newline + text;
  if (draft.insertion != null) {
    var prefix = text.slice(0, draft.insertion);
    return prefix + (prefix.endsWith("\n") ? "" : newline) + 'language = "' + code + '"' + newline + text.slice(draft.insertion);
  }
  return text + (text.endsWith("\n") ? "" : newline) + '[app]' + newline + 'language = "' + code + '"' + newline;
}

function renderLanguageSetting(text) {
  var draft = languageDraft(text);
  var selected = draft && draft.found ? draft.found.value : "auto";
  var disabled = !draft || state.configSaving || state.configLoadError;
  var languages = state.status && state.status.languages || [];
  var options = [{ code: "auto", displayName: t("config.languageAuto") }].concat(languages);
  return '<div id="config-language-setting"><label>' + escHtml(t("config.language"))
    + ' <select id="config-language"' + (disabled ? ' disabled' : '') + '>'
    + options.map(function (lang) { return '<option value="' + escHtml(lang.code) + '"' + (lang.code === selected ? ' selected' : '') + '>' + escHtml(lang.displayName) + '</option>'; }).join("")
    + '</select></label> <span>' + escHtml(t("config.languageCurrent", { language: state.lang })) + '</span>'
    + (!draft ? '<p>' + escHtml(t("config.languageUnsafe")) + '</p>' : '') + '</div>';
}
