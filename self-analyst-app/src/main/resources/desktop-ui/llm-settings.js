"use strict";

// 草稿状态独立于配置窗口、TOML 和任何浏览器持久存储。
function createLlmSettingsModel(request, changed) {
  var model = { snapshot: null, presets: [], updates: {}, reset: [], credential: { action: "keep" },
    loading: false, saving: false, error: "", notice: "", candidates: [], probe: {}, busy: {} };
  var alive = true, generation = 0, controllers = {}, timer = null;
  function emit() { if (alive) changed(model); }
  function invalidate() {
    generation++;
    Object.keys(controllers).forEach(function (key) { controllers[key].abort(); });
    controllers = {}; model.probe = {}; model.busy = {}; model.candidates = [];
  }
  model.dirty = function () { return Object.keys(model.updates).length > 0 || model.reset.length > 0 || model.credential.action !== "keep"; };
  model.value = function (field) {
    return Object.prototype.hasOwnProperty.call(model.updates, field) ? model.updates[field]
      : model.snapshot ? model.snapshot.fields[field].effectiveValue : "";
  };
  model.change = function (field, value) {
    if (model.saving || !model.snapshot) return;
    model.reset = model.reset.filter(function (key) { return key !== field; });
    if (String(value) === String(model.snapshot.fields[field].effectiveValue)) delete model.updates[field];
    else model.updates[field] = value;
    model.error = ""; model.notice = ""; invalidate(); emit();
  };
  model.inherit = function (field) {
    if (model.saving || !model.snapshot) return;
    delete model.updates[field];
    if (model.reset.indexOf(field) < 0) model.reset.push(field);
    model.notice = ""; invalidate(); emit();
  };
  model.setCredential = function (action, value) {
    if (model.saving || !model.snapshot) return;
    model.credential = action === "replace" && value.trim() ? { action: action, value: value.trim() }
      : { action: action === "replace" ? "keep" : action };
    model.notice = ""; invalidate(); emit();
  };
  model.load = function () {
    invalidate(); clearTimeout(timer); var version = generation;
    model.loading = true; model.error = ""; model.snapshot = null; emit();
    return Promise.all([request(""), request("/presets").catch(function () { return { presets: [] }; })])
      .then(function (values) {
        if (!alive || version !== generation) return;
        model.snapshot = values[0]; model.presets = values[1].presets;
        model.updates = {}; model.reset = []; model.credential = { action: "keep" }; model.notice = "";
        scheduleRuntime();
      }).catch(function (error) { if (alive && version === generation) model.error = error.message; })
      .finally(function () { if (alive && version === generation) { model.loading = false; emit(); } });
  };
  model.save = function () {
    if (model.saving || !model.snapshot || !model.dirty()) return Promise.resolve();
    var updates = Object.assign({}, model.updates);
    if ("temperature" in updates) updates.temperature = updates.temperature === "" ? null : Number(updates.temperature);
    model.saving = true; model.error = ""; invalidate(); emit();
    return request("", "PUT", { updates: updates, reset: model.reset.slice(), credential: model.credential })
      .then(function (result) {
        if (!alive) return;
        model.snapshot = result.settings; model.updates = {}; model.reset = [];
        model.credential = { action: "keep" }; model.notice = "saved"; scheduleRuntime();
      }).catch(function (error) { if (alive) model.error = error.message; })
      .finally(function () { if (alive) { model.saving = false; emit(); } });
  };
  model.run = function (kind) {
    if (!model.snapshot || model.saving || model.busy[kind]) return Promise.resolve();
    var controller = new AbortController(), version = generation;
    controllers[kind] = controller; model.busy[kind] = true; delete model.probe[kind]; emit();
    var draft = { baseUrl: model.value("baseUrl"), credential: model.credential };
    if (kind === "test") draft.model = model.value("model");
    draft.reset = model.reset.slice();
    model.reset.forEach(function (key) { delete draft[key]; });
    return request(kind === "test" ? "/test" : "/discover-models", "POST", draft, controller.signal)
      .then(function (result) {
        if (!alive || version !== generation || controller.signal.aborted) return;
        model.probe[kind] = result;
        if (kind === "discover") model.candidates = result.ok ? result.models : [];
      }).catch(function (error) {
        if (alive && version === generation && !controller.signal.aborted) model.probe[kind] = { ok: false, message: error.message };
      }).finally(function () {
        if (alive && version === generation && !controller.signal.aborted) { model.busy[kind] = false; emit(); }
      });
  };
  function scheduleRuntime() {
    clearTimeout(timer);
    var application = model.snapshot && model.snapshot.runtime.application || {};
    if (Object.keys(application).some(function (key) { return application[key].activeWorkCount > 0; })) {
      timer = setTimeout(function () {
        var observed = model.snapshot;
        request("").then(function (snapshot) {
          if (!alive || model.snapshot !== observed) return;
          model.snapshot.runtime = snapshot.runtime; emit(); scheduleRuntime();
        }).catch(function () { if (alive && model.snapshot === observed) { model.error = t("config.runtimeLoadFailed"); emit(); } });
      }, 2000);
    }
  }
  model.destroy = function () { alive = false; invalidate(); clearTimeout(timer); model.credential = { action: "keep" }; };
  return model;
}

function mountLlmSettings(root) {
  var built = false, lastSnapshot = null, lastLoading = null;
  var model = createLlmSettingsModel(api.llmSettings, render);
  function button(action, label, extra) {
    return '<button type="button" class="btn btn-sm ' + (extra || 'btn-outline') + '" data-llm-action="' + action + '">' + escHtml(t(label)) + '</button>';
  }
  function render(data) {
    if (!built || lastSnapshot !== data.snapshot || (!data.snapshot && lastLoading !== data.loading)) {
      built = true; lastSnapshot = data.snapshot; lastLoading = data.loading;
      root.innerHTML = '<section class="llm-settings" aria-label="' + escHtml(t("llm.title")) + '">'
        + '<div class="config-scroll-area"><header><h3>' + escHtml(t("llm.title")) + '</h3><p>' + escHtml(t("llm.intro")) + '</p></header>'
        + (data.snapshot ? form(data) : '<p role="status">' + escHtml(t(data.loading ? "config.loading" : "llm.loadFailed")) + '</p>' + button("retry", "llm.retry"))
        + '</div><footer class="config-action-bar"><div class="config-action-meta"><span id="llm-draft-status" class="config-action-status" role="status"></span>'
        + '<div id="llm-error" role="alert" tabindex="-1"></div><div id="llm-notice" role="status" tabindex="-1"></div></div>'
        + '<div class="config-action-buttons">' + button("discard", "config.discard") + button("save", "llm.save", "btn-primary") + '</div></footer>'
        + '</section>';
    }
    root.querySelector("#llm-error").textContent = data.error;
    root.querySelector("#llm-notice").textContent = data.notice ? t("llm.saved") : "";
    state.configSaving = data.saving;
    root.querySelector('#llm-draft-status').textContent = t(data.loading ? "config.loading" : data.saving ? "config.saving"
      : data.dirty() ? "config.unsavedChanges" : "config.noUnsavedChanges");
    root.querySelector('[data-llm-action="save"]').disabled = data.saving || !data.snapshot || !data.dirty();
    root.querySelector('[data-llm-action="discard"]').disabled = data.saving || !data.snapshot || !data.dirty();
    if (!data.snapshot) {
      root.querySelector('[data-llm-action="retry"]').disabled = data.loading;
      return;
    }
    var matchedPreset = data.presets.find(function (p) { return p.baseUrl && p.baseUrl.replace(/\/+$/, "") === String(data.value("baseUrl")).replace(/\/+$/, ""); });
    root.querySelector('[data-llm-preset]').value = matchedPreset ? matchedPreset.id : "";
    root.querySelectorAll("input,select,button").forEach(function (el) { el.disabled = data.saving; });
    ["baseUrl", "model", "temperature"].forEach(function (key) {
      var input = root.querySelector('[data-llm-field="' + key + '"]');
      if (document.activeElement !== input) input.value = data.value(key);
      input.disabled = data.saving || data.reset.indexOf(key) >= 0;
      var source = root.querySelector('[data-llm-source="' + key + '"]');
      source.textContent = data.reset.indexOf(key) >= 0 ? t("llm.inheritPending") : t("config.source." + data.snapshot.fields[key].source);
    });
    if (data.credential.action !== "replace") {
      root.querySelector('[data-llm-key]').value = "";
      root.querySelector('[data-llm-key]').type = "password";
      root.querySelector('[data-llm-action="reveal-key"]').textContent = t("llm.showKey");
      root.querySelector('[data-llm-action="reveal-key"]').setAttribute("aria-pressed", "false");
    }
    root.querySelector("#llm-key-state").textContent = t("llm.credential." + data.credential.action) + " · "
      + t(data.snapshot.credential.configured ? "llm.configured" : "llm.notConfigured") + " · " + t("config.source." + data.snapshot.credential.source);
    root.querySelector('[data-llm-action="save"]').disabled = data.saving || !data.dirty();
    root.querySelector('[data-llm-action="discard"]').disabled = data.saving || !data.dirty();
    ["test", "discover"].forEach(function (kind) {
      root.querySelector('[data-llm-action="' + kind + '"]').disabled = data.saving || !!data.busy[kind];
      var result = data.probe[kind];
      root.querySelector('#llm-' + kind + '-result').textContent = data.busy[kind] ? t("config.testing") : result
        ? (result.message || t("llm.probe." + result.code)) + (result.latencyMs != null ? ' (' + result.latencyMs + ' ms)' : '')
          + (result.truncated ? ' · ' + t("llm.truncated") : '') : "";
    });
    root.querySelector('#llm-models').innerHTML = data.candidates.map(function (id) {
      return '<button type="button" class="btn btn-sm btn-outline" data-llm-action="select-model" data-model-id="' + escHtml(id) + '">' + escHtml(id) + '</button>';
    }).join("");
    root.querySelector('#llm-runtime').innerHTML = Object.keys(data.snapshot.runtime.application || {}).map(function (key) {
      var item = data.snapshot.runtime.application[key];
      return '<li><span>' + escHtml(key) + '</span><span>' + escHtml(t("config.runtime." + item.status))
        + (item.changedKeys && item.changedKeys.length ? ' (' + escHtml(item.changedKeys.join(", ")) + ')' : '') + '</span></li>';
    }).join("");
    state.configSaving = data.saving;
  }
  function form(data) {
    function field(key) {
      return '<div class="llm-field' + (key === "baseUrl" || key === "model" ? ' llm-wide' : '') + '"><label for="llm-' + key + '">' + escHtml(t("llm." + key))
        + '</label><div class="llm-input-row"><input id="llm-' + key + '" data-llm-field="' + key + '" value="' + escHtml(data.value(key)) + '"'
        + (key === "temperature" ? ' type="number" min="0" max="2" step="0.1"' : ' type="text"')
        + '>' + (key === "model" ? button("discover", "llm.discover") : '') + '</div><div class="llm-field-meta"><span data-llm-source="' + key + '"></span>'
        + button('reset-' + key, "llm.inherit", "llm-link-button") + '</div></div>';
    }
    var html = '<section class="llm-form-section"><h4><span class="config-step">01</span>' + escHtml(t("llm.connectionSection")) + '</h4>'
      + '<div class="llm-field-grid"><label class="llm-wide">' + escHtml(t("llm.preset"))
      + '<select data-llm-preset><option value="">' + escHtml(t("llm.custom")) + '</option>'
      + data.presets.filter(function (p) { return p.id !== "custom"; }).map(function (p) {
        return '<option value="' + escHtml(p.id) + '"' + (p.baseUrl === data.value("baseUrl") ? ' selected' : '') + '>' + escHtml(p.name) + '</option>';
      }).join("") + '</select></label>';
    html += field("baseUrl") + '<div class="llm-wide"><label for="llm-key">API Key</label>'
      + '<div class="llm-input-row"><input id="llm-key" data-llm-key type="password" autocomplete="new-password" placeholder="' + escHtml(t("llm.keyHint")) + '">'
      + button("reveal-key", "llm.showKey") + '</div><p id="llm-key-state"></p><div class="llm-credential-actions">'
      + button("clear-key", "llm.clearKey", "llm-link-button") + button("reset-key", "llm.resetKey", "llm-link-button") + '</div></div></div></section>'
      + '<section class="llm-form-section"><h4><span class="config-step">02</span>' + escHtml(t("llm.generationSection")) + '</h4><div class="llm-field-grid">'
      + field("model") + '<div class="llm-wide"><p id="llm-discover-result" role="status"></p><div id="llm-models" class="llm-model-candidates"></div></div>'
      + field("temperature") + '</div><p class="llm-hint">' + escHtml(t("llm.outputPolicy")) + '</p></section>'
      + '<section class="llm-probes"><p>' + escHtml(t("llm.billing")) + '</p>' + button("test", "llm.test")
      + '<p id="llm-test-result" role="status"></p></section>'
      + '<section><h4>' + escHtml(t("config.runtimeTitle")) + '</h4><ul id="llm-runtime" aria-live="polite"></ul></section>';
    return html;
  }
  function input(event) {
    var el = event.target;
    if (el.dataset.llmField) model.change(el.dataset.llmField, el.value);
    if (el.hasAttribute("data-llm-key")) model.setCredential("replace", el.value);
  }
  function change(event) {
    if (!event.target.hasAttribute("data-llm-preset")) return;
    var preset = model.presets.find(function (p) { return p.id === event.target.value; });
    if (preset && preset.baseUrl) model.change("baseUrl", preset.baseUrl);
  }
  function click(event) {
    var el = event.target.closest("[data-llm-action]"); if (!el || el.disabled) return;
    var action = el.dataset.llmAction;
    if (action === "save") model.save().then(function () {
      var target = root.querySelector(model.error ? '#llm-error' : '#llm-notice');
      if (target) target.focus();
    });
    else if (action === "reveal-key") {
      var keyInput = root.querySelector('[data-llm-key]');
      var show = keyInput.type === "password";
      keyInput.type = show ? "text" : "password";
      el.textContent = t(show ? "llm.hideKey" : "llm.showKey");
      el.setAttribute("aria-pressed", String(show));
    }
    else if (action === "select-model") {
      model.change("model", el.dataset.modelId);
      root.querySelector('[data-llm-field="model"]').focus();
    }
    else if (action === "discard" || action === "retry") model.load();
    else if (action === "test" || action === "discover") model.run(action);
    else if (action === "clear-key" || action === "reset-key") {
      if (window.confirm(t(action === "clear-key" ? "llm.confirmClear" : "llm.confirmReset"))) model.setCredential(action === "clear-key" ? "clear" : "reset");
    } else if (action.indexOf("reset-") === 0) model.inherit(action.slice(6));
  }
  root.addEventListener("input", input); root.addEventListener("change", change); root.addEventListener("click", click);
  model.load();
  return { dirty: model.dirty, saving: function () { return model.saving; }, destroy: function () {
    model.destroy(); root.removeEventListener("input", input); root.removeEventListener("change", change); root.removeEventListener("click", click); root.innerHTML = "";
  } };
}
