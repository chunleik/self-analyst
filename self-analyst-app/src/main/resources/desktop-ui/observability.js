"use strict";

// Developer observability is an independent, explicitly authorized data destination.
function observabilityErrorText(error) {
  var code = String(error && error.code || "unavailable");
  if (code === "invalid_ca_certificate") code = "invalid_certificate";
  if (code === "incomplete_config") code = "invalid_config";
  if (code === "endpoint_changed") code = "destination_changed";
  if (code === "conflict") code = "revision_conflict";
  var allowed = ["revision_conflict", "invalid_config", "invalid_endpoint", "unsafe_endpoint", "invalid_certificate", "tls_failure",
    "persistent_credentials_unavailable", "credential_required", "credential_cleanup_pending", "managed_desktop_required", "authentication_required",
    "invalid_scope", "invalid_categories", "unsafe_destination", "destination_changed", "destination_confirmation_required", "timeout", "config_unavailable", "unavailable"];
  return t("observability.error." + (allowed.indexOf(code) >= 0 ? code : "unavailable"));
}
function createObservabilityModel(request, changed, sourceRequest) {
  var model = { snapshot: null, draft: null, secret: "", loading: false, busy: "", unknown: false, error: "", notice: "", sources: null, sourcesError: false };
  var alive = true, generation = 0, controller = null;
  function copy(value) { return JSON.parse(JSON.stringify(value)); }
  function emit() { if (alive) changed(model); }
  function invalidate() { generation++; if (controller) controller.abort(); controller = null; }
  var fields = ["schemaVersion", "revision", "endpoint", "publicKey", "sampleRate", "captureMode", "categories", "toolSources", "mailAccounts", "scope", "sessionId", "caCertificatePem"];
  function configValid(config) {
    return config && config.schemaVersion === 1 && Number.isSafeInteger(config.revision) && config.revision >= 0
      && ["endpoint", "publicKey", "sessionId", "caCertificatePem"].every(function (key) { return typeof config[key] === "string"; })
      && Number.isFinite(config.sampleRate) && config.sampleRate >= 0 && config.sampleRate <= 1
      && ["metadata", "content"].indexOf(config.captureMode) >= 0 && ["session", "all-runtime"].indexOf(config.scope) >= 0
      && ["categories", "toolSources", "mailAccounts"].every(function (key) { return Array.isArray(config[key]) && config[key].every(function (value) { return typeof value === "string"; }); });
  }
  function validated(value) {
    if (!value || !configValid(value.config) || typeof value.enabled !== "boolean" || typeof value.credentialConfigured !== "boolean"
      || typeof value.persistentCredentialsSupported !== "boolean" || typeof value.credentialCleanupPending !== "boolean"
      || !value.status || ["disabled", "enabled", "error", "unavailable"].indexOf(value.status.state) < 0
      || !["accepted", "dropped", "failed"].every(function (key) { return Number.isFinite(value.status[key]) && value.status[key] >= 0; })) throw new Error("invalid_snapshot");
    var result = {};
    ["enabled", "credentialConfigured", "persistentCredentialsSupported", "credentialCleanupPending", "contentExpiresOnRestart", "testAccepted"].forEach(function (key) { if (value[key] !== undefined) result[key] = value[key]; });
    result.status = { state: value.status.state, accepted: value.status.accepted, dropped: value.status.dropped, failed: value.status.failed, lastError: String(value.status.lastError || "") }; result.config = {};
    fields.forEach(function (key) { result.config[key] = copy(value.config[key]); });
    return result;
  }
  model.metadataDirty = function () { return !!model.snapshot && JSON.stringify(model.draft) !== JSON.stringify(model.snapshot.config); };
  model.dirty = function () { return model.metadataDirty() || !!model.secret || model.unknown; };
  model.locked = function () { return !!model.busy || model.loading || model.unknown || !!model.snapshot && model.snapshot.status.state === "unavailable"; };
  model.load = function () {
    if (model.busy) return Promise.resolve();
    invalidate(); model.secret = ""; model.loading = true; model.error = ""; var current = generation;
    controller = new AbortController(); emit();
    return request("", "GET", undefined, controller.signal).then(function (value) {
      if (!alive || current !== generation) return;
      var result = validated(value); model.snapshot = result; model.draft = copy(result.config); model.unknown = false; model.notice = "";
      if (sourceRequest) return sourceRequest("", "GET").then(function (sources) {
        if (!alive || current !== generation) return;
        if (!sources || !sources.config || !Array.isArray(sources.config.mcpServers) || !Array.isArray(sources.config.mailAccounts)) throw new Error("invalid_sources");
        model.sources = { mcpServers: sources.config.mcpServers.map(function (item) { return { id: item.id, label: item.name || item.id }; }),
          mailAccounts: sources.config.mailAccounts.map(function (item) { return { id: item.id, label: item.displayName || item.email || item.id }; }) }; model.sourcesError = false;
      }).catch(function () { if (alive && current === generation) { model.sources = null; model.sourcesError = true; } });
    }).catch(function (error) { if (alive && current === generation) model.error = observabilityErrorText(error); })
      .finally(function () { if (alive && current === generation) { model.loading = false; emit(); } });
  };
  model.change = function (field, value) {
    if (!model.draft || model.locked() || fields.indexOf(field) < 0 || field === "schemaVersion" || field === "revision") return;
    model.draft[field] = copy(value); model.error = ""; model.notice = ""; emit();
  };
  model.setSecret = function (value) { if (!model.locked()) { model.secret = String(value); model.error = ""; emit(); } };
  function mutate(action, path, body, revisionChange, keepDraft) {
    invalidate(); var current = generation, previous = copy(model.snapshot.config), draft = copy(model.draft);
    model.busy = action; model.error = ""; model.notice = ""; model.secret = ""; emit();
    return request(path, action === "save" ? "PUT" : "POST", body).then(function (value) {
      if (!alive || current !== generation) return;
      var result = validated(value);
      if (result.config.revision !== previous.revision + revisionChange) throw new Error("unverified_revision");
      if (action !== "save" && JSON.stringify(Object.assign({}, result.config, { revision: previous.revision })) !== JSON.stringify(previous)) throw new Error("unverified_target");
      if ((action === "save" || action === "credential" || action === "disable" || action === "disconnect") && result.enabled) throw new Error("unverified_disabled");
      if (action === "enable" && !result.enabled) throw new Error("unverified_enabled");
      if (action === "test" && result.testAccepted !== true) throw new Error("unverified_test");
      model.snapshot = result;
      if (keepDraft) { draft.revision = result.config.revision; model.draft = draft; } else model.draft = copy(result.config);
      model.notice = result.credentialCleanupPending ? "cleanupPending" : action;
    }).catch(function (error) {
      if (!alive || current !== generation) return;
      if (error && (error.status === 401 || error.status === 403 || error.status === 409 || error.code === "revision_conflict")) model.error = observabilityErrorText(error);
      else if (action === "test") model.error = observabilityErrorText(error);
      else { model.unknown = true; model.error = t("observability.unknown"); }
    }).finally(function () { if (body && Object.prototype.hasOwnProperty.call(body, "secret")) body.secret = undefined;
      if (alive && current === generation) { model.busy = ""; emit(); } });
  }
  model.save = function () {
    if (!model.snapshot || model.locked() || !model.metadataDirty()) return Promise.resolve();
    if (!configValid(model.draft) || /PRIVATE KEY/.test(model.draft.caCertificatePem)) { model.error = t("observability.error.invalid_config"); emit(); return Promise.resolve(); }
    return mutate("save", "", { expectedRevision: model.snapshot.config.revision, config: copy(model.draft) }, 1, false);
  };
  model.credential = function () {
    if (!model.snapshot || model.locked() || model.metadataDirty() || !model.secret || !model.snapshot.persistentCredentialsSupported) return Promise.resolve();
    return mutate("credential", "/credential", { expectedRevision: model.snapshot.config.revision, secret: model.secret }, 1, false);
  };
  model.action = function (action, confirm) {
    if (!model.snapshot || model.busy || model.loading || ["enable", "disable", "disconnect", "test"].indexOf(action) < 0) return Promise.resolve();
    if ((model.unknown || model.snapshot.status.state === "unavailable") && action !== "disable") return Promise.resolve();
    if (action !== "disable" && model.metadataDirty()) { model.error = t("observability.saveFirst"); emit(); return Promise.resolve(); }
    if ((action === "enable" || action === "test") && !model.snapshot.credentialConfigured) { model.error = t("observability.error.credential_required"); emit(); return Promise.resolve(); }
    if (typeof confirm !== "function") return Promise.resolve();
    if (action === "enable" || action === "test") {
      invalidate(); var current = generation, saved = copy(model.snapshot.config);
      model.busy = "preview"; model.error = ""; model.notice = ""; model.secret = ""; emit();
      return request("/preview", "POST", { expectedRevision: saved.revision }).then(function (preview) {
        if (!alive || current !== generation) return;
        var destination = preview && preview.destination;
        if (!destination || destination.endpoint !== saved.endpoint || destination.publicKey !== saved.publicKey
          || typeof destination.category !== "string" || !destination.category || typeof destination.caFingerprint !== "string" || !destination.caFingerprint
          || !Array.isArray(destination.addresses) || !destination.addresses.length || !destination.addresses.every(function (address) { return typeof address === "string" && address.length > 0; })
          || typeof preview.confirmationToken !== "string" || !preview.confirmationToken) throw new Error("invalid_preview");
        if (!confirm({ action: action, config: copy(saved), destination: copy(destination) })) return;
        var body = { expectedRevision: saved.revision, confirmationToken: preview.confirmationToken };
        if (action === "enable") body.disclosureVersion = 1;
        return mutate(action, "/" + action, body, 0, false);
      }).catch(function (error) { if (alive && current === generation) model.error = observabilityErrorText(error); })
        .finally(function () { if (alive && current === generation) { model.busy = ""; emit(); } });
    }
    if (!confirm({ action: action, config: copy(model.snapshot.config) })) return Promise.resolve();
    return mutate(action, "/" + action, { expectedRevision: model.snapshot.config.revision }, action === "disconnect" ? 1 : 0, action === "disable");
  };
  model.destroy = function () { alive = false; invalidate(); model.secret = ""; model.snapshot = null; model.draft = null; model.sources = null; };
  return model;
}

function mountObservability(root) {
  var model = createObservabilityModel(api.observability, render, api.integrations), controls = {}, sourceBoxes = [];
  function el(tag, text, className) { var node = document.createElement(tag); if (text != null) node.textContent = String(text); if (className) node.className = className; return node; }
  function button(label, fn) { var node = el("button", t(label), "btn btn-sm btn-outline"); node.type = "button"; node.addEventListener("click", fn); return node; }
  root.replaceChildren();
  var section = el("section", null, "observability-settings"), scroller = el("div", null, "config-scroll-area"); section.appendChild(scroller);
  scroller.appendChild(el("h3", t("observability.title"))); scroller.appendChild(el("p", t("observability.intro"), "observability-disclosure"));
  var status = el("p"); status.setAttribute("role", "status"); scroller.appendChild(status);
  var counts = el("p", null, "observability-hint"); scroller.appendChild(counts);
  var form = el("div", null, "observability-grid"); scroller.appendChild(form);
  function field(name, label, type, choices) {
    var wrap = el("label", t(label), "observability-field"), input = el(choices ? "select" : type === "textarea" ? "textarea" : "input");
    input.id = "observability-" + name; wrap.htmlFor = input.id;
    if (choices) choices.forEach(function (choice) { var option = el("option", t(choice[1])); option.value = choice[0]; input.appendChild(option); });
    else if (input.tagName === "INPUT") input.type = type || "text";
    if (name === "secret") { input.autocomplete = "new-password"; input.maxLength = 2048; input.spellcheck = false; }
    else if (name === "sampleRate") { input.min = "0"; input.max = "1"; input.step = "0.05"; }
    else input.maxLength = name === "caCertificatePem" ? 16384 : 2048;
    input.addEventListener(choices ? "change" : "input", function () { if (name === "secret") model.setSecret(input.value);
      else model.change(name, name === "sampleRate" ? Number(input.value) : input.value); });
    wrap.appendChild(input); form.appendChild(wrap); controls[name] = input; return input;
  }
  field("endpoint", "observability.endpoint", "url"); field("publicKey", "observability.publicKey");
  field("sampleRate", "observability.sampleRate", "number");
  field("captureMode", "observability.mode", "text", [["metadata", "observability.metadata"], ["content", "observability.content"]]);
  field("scope", "observability.scope", "text", [["session", "observability.scope.session"], ["all-runtime", "observability.scope.all-runtime"]]);
  field("sessionId", "observability.sessionId");
  var useSession = button("observability.useCurrentSession", function () { if (state.activeChatSessionId) model.change("sessionId", state.activeChatSessionId); }); form.appendChild(useSession);
  field("caCertificatePem", "observability.ca", "textarea");
  scroller.appendChild(el("p", t("observability.network"), "observability-hint"));
  var categories = el("fieldset"); categories.appendChild(el("legend", t("observability.categories"))); scroller.appendChild(categories);
  var categoryBoxes = {};
  ["modelText", "toolText", "toolCatalog", "mailHeaders", "mailBody"].forEach(function (name) {
    var label = el("label", null, "observability-choice"), input = el("input"); input.type = "checkbox";
    input.addEventListener("change", function () { var selected = model.draft.categories.filter(function (item) { return item !== name; }); if (input.checked) selected.push(name); model.change("categories", selected); });
    label.appendChild(input); label.appendChild(el("span", t("observability.category." + name))); categories.appendChild(label); categoryBoxes[name] = input;
  });
  var sources = el("div", null, "observability-sources"); scroller.appendChild(sources);
  scroller.appendChild(el("p", t("observability.contentRisk"), "observability-disclosure"));
  scroller.appendChild(el("p", t("observability.images"), "observability-disclosure"));
  scroller.appendChild(el("p", t("observability.filtering"), "observability-hint"));
  var credentials = el("div", null, "observability-grid"); scroller.appendChild(credentials);
  field("secret", "observability.secret", "password"); credentials.appendChild(controls.secret.parentNode);
  var credentialStatus = el("p", null, "observability-hint"); scroller.appendChild(credentialStatus);
  var error = el("p", null, "observability-error"); error.setAttribute("role", "alert"); error.tabIndex = -1;
  var notice = el("p"); notice.setAttribute("role", "status"); notice.tabIndex = -1; scroller.appendChild(error); scroller.appendChild(notice);
  var row = el("div", null, "observability-actions"); scroller.appendChild(row);
  function confirmAction(value) {
    var config = value.config;
    var details = t("observability.confirmDetails", { endpoint: config.endpoint, project: config.publicKey,
      mode: t("observability." + config.captureMode), categories: config.categories.map(function (name) { return t("observability.category." + name); }).join(", ") || t("observability.none"),
      tools: config.toolSources.join(", ") || t("observability.none"), mail: config.mailAccounts.join(", ") || t("observability.none"),
      scope: config.scope === "session" ? t("observability.scope.session") + ": " + config.sessionId : t("observability.scope.all-runtime") });
    if (value.destination) details += "\n" + t("observability.destinationDetails", { category: value.destination.category, addresses: value.destination.addresses.join(", "), fingerprint: value.destination.caFingerprint });
    return window.confirm(t("observability.confirm." + value.action) + "\n\n" + details + "\n\n" + t("observability.contentRisk") + "\n" + t("observability.network"));
  }
  ["credential", "test", "enable", "disable", "disconnect"].forEach(function (action) {
    var node = button("observability.action." + action, function () {
      var result = action === "credential" ? model.credential() : model.action(action, confirmAction);
      result.then(function () { (model.error ? error : notice).focus(); });
    }); row.appendChild(node); controls[action] = node;
  });
  var footer = el("div", null, "config-action-bar observability-actions"); section.appendChild(footer);
  var discard = button("config.discard", function () { if (!model.dirty() || window.confirm(t("config.confirmDiscardClose"))) model.load(); });
  var save = button("config.saveChanges", function () { model.save().then(function () { (model.error ? error : notice).focus(); }); });
  footer.appendChild(discard); footer.appendChild(save); root.appendChild(section);
  var sourceKey = "";
  function renderSources(data) {
    var key = JSON.stringify([data.sources, data.draft && data.draft.toolSources, data.draft && data.draft.mailAccounts]);
    if (key === sourceKey) return;
    var focusedSource = document.activeElement && document.activeElement.id;
    sourceKey = key; sources.replaceChildren(); sourceBoxes = [];
    if (!data.draft) return;
    if (data.sourcesError) sources.appendChild(el("p", t("observability.sourcesError"), "observability-error"));
    [["toolSources", "mcpServers", "observability.toolSources"], ["mailAccounts", "mailAccounts", "observability.mailAccounts"]].forEach(function (group) {
      var fieldset = el("fieldset"); fieldset.appendChild(el("legend", t(group[2]))); sources.appendChild(fieldset);
      var entries = data.sources ? data.sources[group[1]].slice() : [];
      data.draft[group[0]].forEach(function (id) { if (!entries.some(function (item) { return item.id === id; })) entries.push({ id: id, label: t("observability.unavailableSource") }); });
      if (!entries.length) fieldset.appendChild(el("p", t("observability.noSources"), "observability-hint"));
      entries.forEach(function (entry, index) {
        var label = el("label", null, "observability-choice"), input = el("input"); input.type = "checkbox"; input.id = "observability-source-" + group[0] + "-" + index; input.checked = data.draft[group[0]].indexOf(entry.id) >= 0;
        input.addEventListener("change", function () { var items = model.draft[group[0]].filter(function (id) { return id !== entry.id; }); if (input.checked) items.push(entry.id); model.change(group[0], items); });
        label.appendChild(input); label.appendChild(el("span", entry.label + " (" + entry.id + ")")); fieldset.appendChild(label); sourceBoxes.push(input); if (input.id === focusedSource) input.focus();
      });
    });
  }
  function render(data) {
    status.textContent = data.loading ? t("common.loading") : data.snapshot ? t("observability.status", {
      revision: data.snapshot.config.revision, state: t(data.snapshot.enabled ? "observability.enabled" : "observability.disabled") }) : "";
    error.textContent = data.unknown ? t("observability.unknown") : data.error;
    notice.textContent = data.snapshot && data.snapshot.credentialCleanupPending ? t("observability.notice.cleanupPending") : data.notice ? t("observability.notice." + data.notice) : "";
    if (data.snapshot) {
      counts.textContent = t("observability.counts", data.snapshot.status);
      if (data.snapshot.status.lastError) counts.textContent += " " + observabilityErrorText({ code: data.snapshot.status.lastError });
      credentialStatus.textContent = t(!data.snapshot.persistentCredentialsSupported ? "observability.vaultUnavailable" : data.snapshot.credentialConfigured ? "observability.keySaved" : "observability.keyMissing");
    }
    discard.disabled = !!data.busy || data.loading; save.disabled = !data.draft || data.locked() || !data.metadataDirty();
    if (!data.draft) { Object.keys(controls).forEach(function (key) { controls[key].disabled = true; }); Object.keys(categoryBoxes).forEach(function (key) { categoryBoxes[key].disabled = true; }); useSession.disabled = true; return; }
    Object.keys(controls).forEach(function (name) {
      var input = controls[name];
      if (input.tagName === "BUTTON") {
        input.disabled = !!data.busy || data.loading || (data.unknown || data.snapshot.status.state === "unavailable") && name !== "disable" || name !== "disable" && data.metadataDirty();
        if (name === "credential") input.disabled = input.disabled || !data.secret || !data.snapshot.persistentCredentialsSupported;
        if (name === "enable" || name === "test") input.disabled = input.disabled || !data.snapshot.credentialConfigured;
      } else {
        input.disabled = data.locked() || name === "secret" && !data.snapshot.persistentCredentialsSupported;
        var value = name === "secret" ? data.secret : data.draft[name]; if (input.value !== String(value)) input.value = String(value);
      }
    });
    useSession.disabled = data.locked() || !state.activeChatSessionId || data.draft.scope !== "session";
    controls.sessionId.disabled = data.locked() || data.draft.scope !== "session";
    Object.keys(categoryBoxes).forEach(function (name) { categoryBoxes[name].checked = data.draft.categories.indexOf(name) >= 0; categoryBoxes[name].disabled = data.locked() || data.draft.captureMode !== "content"; });
    renderSources(data); sourceBoxes.forEach(function (input) { input.disabled = data.locked() || data.draft.captureMode !== "content"; });
  }
  model.load();
  return { dirty: model.dirty, saving: function () { return !!model.busy; }, destroy: function () { model.destroy(); controls.secret.value = ""; root.replaceChildren(); } };
}
