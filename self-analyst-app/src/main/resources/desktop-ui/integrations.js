"use strict";

// Public metadata and write-only secrets deliberately have separate request paths.
function integrationErrorText(error, mailActivation) {
  var storageCategory = mailActivation ? "mailStorage" : "storage";
  var code = String(error && (error.code || error.errorCode) || "unavailable").split(/[.]/).pop();
  var aliases = { revision_conflict: "conflict", auth_required: "authentication", authentication_required: "authentication", authentication_failed: "authentication",
    storage_unavailable: storageCategory, persistent_credentials_unavailable: storageCategory, credential_write_failed: storageCategory,
    credential_verification_failed: storageCategory, credential_read_failed: storageCategory, mail_credential_required: "mailCredential",
    mail_activation_confirmation_required: "reviewRequired", mail_activation_required: "reviewRequired", model_target_changed: "reviewRequired",
    model_target_unavailable: "modelTarget", mail_activation_outcome_uncertain: "mailOutcomeUncertain",
    unsafe_login: "unsafeLogin", permission_changed: "permission", integration_authorization_required: "permission", managed_desktop_required: "permission", stale_reference: "stale",
    invalid_config: "validation", unsupported_transport: "validation", tls_error: "tls", tls_failure: "tls", provider_restricted: "unsafeLogin" };
  code = aliases[code] || (/^(invalid_|unsupported_|disclosure_required)/.test(code) ? "validation" : code);
  var allowed = ["conflict", "authentication", "storage", "unsafeLogin", "permission", "stale", "validation", "tls", "timeout", "unavailable", "cancelled", "mailStorage", "mailCredential", "reviewRequired", "modelTarget", "mailOutcomeUncertain"];
  return t("integrations.error." + (allowed.indexOf(code) >= 0 ? code : "unavailable"));
}

// Keep only public recovery metadata across settings close/reopen, never credentials.
var integrationMailActivationRecovery = null;
function integrationValidSnapshot(snapshot) {
  function object(value) { return !!value && typeof value === "object" && !Array.isArray(value); }
  function text(value) { return typeof value === "string"; }
  function strings(value) { return Array.isArray(value) && value.every(text); }
  function credential(entry) {
    return ["session", "windows"].indexOf(entry.storageMode) >= 0 && (entry.credentialRef === null
      || text(entry.credentialRef) && new RegExp("^" + entry.storageMode + ":[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", "i").test(entry.credentialRef));
  }
  if (!object(snapshot) || !object(snapshot.config) || !object(snapshot.statuses)
      || typeof snapshot.persistentCredentialsSupported !== "boolean" || !text(snapshot.modelTarget)
      || snapshot.credentialCleanupPending !== undefined && typeof snapshot.credentialCleanupPending !== "boolean") return false;
  var config = snapshot.config, ids = [];
  if (config.schemaVersion !== 1 || !Number.isSafeInteger(config.revision) || config.revision < 0
      || !Array.isArray(config.mailAccounts) || !Array.isArray(config.mcpServers) || !Array.isArray(config.toolApprovals)) return false;
  function identity(entry) {
    if (!object(entry) || !text(entry.id) || !entry.id || ids.indexOf(entry.id) >= 0 || typeof entry.enabled !== "boolean") return false;
    ids.push(entry.id); return true;
  }
  return config.mailAccounts.every(function (entry) {
    return identity(entry) && ["qq", "163"].indexOf(entry.provider) >= 0 && text(entry.email) && text(entry.displayName)
      && (entry.folderScope === undefined || ["all", "selected"].indexOf(entry.folderScope) >= 0)
      && strings(entry.allowedFolders) && typeof entry.bodyAccess === "boolean" && credential(entry)
      && [0, 1].indexOf(entry.disclosureVersion) >= 0 && (!entry.enabled || entry.disclosureVersion === 1);
  }) && config.mcpServers.every(function (entry) {
    return identity(entry) && text(entry.name) && text(entry.endpoint) && entry.transport === "streamable-http"
      && ["anonymous", "bearer"].indexOf(entry.authType) >= 0 && credential(entry) && object(entry.budget);
  }) && config.toolApprovals.every(function (entry) {
    return object(entry) && text(entry.sourceId) && text(entry.toolName) && text(entry.definitionDigest)
      && Number.isSafeInteger(entry.approvedRevision) && entry.approvedRevision >= 0;
  }) && ids.every(function (id) {
    var status = snapshot.statuses[id];
    return object(status) && text(status.state) && !!status.state && typeof status.credentialConfigured === "boolean";
  }) && Object.keys(snapshot.statuses).every(function (id) {
    var status = snapshot.statuses[id];
    return object(status) && text(status.state) && (status.credentialConfigured === undefined || typeof status.credentialConfigured === "boolean")
      && (status.tools === undefined || Array.isArray(status.tools) && status.tools.every(function (tool) { return object(tool) && text(tool.name); }));
  });
}
function integrationValidMailActivation(snapshot, expected, entry) {
  if (!integrationValidSnapshot(snapshot) || snapshot.config.revision <= expected.expectedRevision
      || snapshot.modelTarget !== expected.modelTarget || snapshot.persistentCredentialsSupported !== true) return false;
  var saved = snapshot.config.mailAccounts.find(function (account) { return account.id === expected.id; });
  var status = snapshot.statuses[expected.id];
  return !!(saved && saved.enabled && saved.disclosureVersion === 1 && saved.storageMode === "windows" && saved.credentialRef
    && status && status.credentialConfigured === true
    && ["provider", "email", "displayName", "bodyAccess"].every(function (key) { return saved[key] === entry[key]; })
    && (saved.folderScope || "selected") === (entry.folderScope || "selected")
    && JSON.stringify(saved.allowedFolders) === JSON.stringify(entry.allowedFolders));
}
function integrationKnownMailRejection(error) {
  var code = String(error && (error.code || error.errorCode) || "").split(/[.]/).pop();
  // Generic server errors can arise while building the response after a committed activation.
  return !!error && (!error.status || error.status < 500 && error.status !== 408) && [
    "revision_conflict", "mail_activation_confirmation_required", "mail_activation_required", "mail_metadata_authorization_immutable",
    "model_target_unavailable", "model_target_changed", "mail_credential_required", "persistent_credentials_unavailable",
    "credential_write_failed", "credential_read_failed", "credential_verification_failed", "invalid_credential", "credential_limit",
    "managed_desktop_required", "integration_authorization_required"
  ].indexOf(code) >= 0;
}
function integrationUncertainMailOutcome() { return { code: "mail_activation_outcome_uncertain" }; }

function createIntegrationsModel(request, changed) {
  var model = { snapshot: null, draft: null, selected: null, loading: false, busy: "", error: "", notice: "",
    secret: "", persist: false, diagnostic: null, tools: [], activationNeedsRefresh: !!integrationMailActivationRecovery };
  if (integrationMailActivationRecovery) {
    model.snapshot = copy(integrationMailActivationRecovery.snapshot); model.draft = copy(model.snapshot.config);
  }
  var alive = true, generation = 0, controller = null, readGeneration = 0;
  function copy(value) { return JSON.parse(JSON.stringify(value)); }
  function emit() { if (alive) changed(model); }
  function invalidate() { generation++; if (controller) controller.abort(); controller = null; model.diagnostic = null; model.tools = []; }
  function clearSecret() { model.secret = ""; model.persist = false; }
  function invalidRead() { return model.activationNeedsRefresh || integrationMailActivationRecovery ? integrationUncertainMailOutcome() : { code: "unavailable" }; }
  function acceptRead(snapshot, recovery, wasPending) {
    if (!integrationValidSnapshot(snapshot)) throw invalidRead();
    var draft = copy(snapshot.config);
    if (recovery === integrationMailActivationRecovery && !wasPending) integrationMailActivationRecovery = null;
    model.snapshot = snapshot; model.draft = draft;
    model.activationNeedsRefresh = !!integrationMailActivationRecovery;
  }
  function metadataDirty() { return !!model.snapshot && JSON.stringify(model.draft) !== JSON.stringify(model.snapshot.config); }
  model.dirty = function () { return metadataDirty() || !!model.secret; };
  model.metadataDirty = metadataDirty;
  model.entry = function () {
    if (!model.selected || !model.draft) return null;
    if (model.selected.kind === "legacy") return { id: "legacy-websearch" };
    return model.draft[model.selected.kind === "mail" ? "mailAccounts" : "mcpServers"].find(function (item) { return item.id === model.selected.id; });
  };
  model.load = function () {
    if (model.busy) return Promise.resolve();
    invalidate(); clearSecret(); var current = generation; model.loading = true; model.error = ""; emit();
    controller = new AbortController();
    var recovery = integrationMailActivationRecovery, wasPending = !!(recovery && recovery.pending);
    return request("", "GET", undefined, controller.signal).then(function (snapshot) {
      if (!alive || current !== generation) return;
      acceptRead(snapshot, recovery, wasPending); model.selected = null; model.notice = "";
      if (model.activationNeedsRefresh) model.error = t("integrations.error.mailOutcomeUncertain");
    }).catch(function (error) { if (alive && current === generation) model.error = integrationErrorText(model.activationNeedsRefresh ? integrationUncertainMailOutcome() : error); })
      .finally(function () { if (alive && current === generation) { model.loading = false; emit(); } });
  };
  model.select = function (kind, id) {
    if (model.busy || model.loading) return;
    invalidate(); clearSecret(); model.selected = { kind: kind, id: id }; model.error = ""; model.notice = ""; emit();
  };
  model.add = function (kind) {
    if (!model.draft || model.busy || kind === "mail" && model.activationNeedsRefresh) return;
    var id = (kind === "mail" ? "mail-" : "mcp-") + crypto.randomUUID();
    var entry = kind === "mail" ? { id: id, provider: "qq", email: "", displayName: "", enabled: false,
      folderScope: "all", allowedFolders: ["INBOX"], bodyAccess: false, credentialRef: null, storageMode: "session", disclosureVersion: 0 }
      : { id: id, name: "", enabled: false, transport: "streamable-http", endpoint: "", authType: "anonymous", credentialRef: null, storageMode: "session",
        budget: { connectTimeoutSeconds: 10, requestTimeoutSeconds: 30, maxTools: 64, maxSchemaBytes: 32768, maxResultBytes: 262144 } };
    model.draft[kind === "mail" ? "mailAccounts" : "mcpServers"].push(entry); model.select(kind, id);
  };
  model.change = function (field, value) {
    var entry = model.entry(); if (!entry || model.busy || model.selected.kind === "legacy"
      || model.selected.kind === "mail" && model.activationNeedsRefresh) return;
    var allowed = model.selected.kind === "mail"
      ? ["provider", "email", "displayName", "folderScope", "allowedFolders", "bodyAccess"]
      : ["name", "endpoint", "authType", "enabled"];
    if (allowed.indexOf(field) < 0) return;
    invalidate();
    if (["provider", "email", "endpoint", "authType"].indexOf(field) >= 0) {
      clearSecret();
      // Mail permission and credential resets belong to the metadata transaction on the server.
      if (model.selected.kind === "mcp") { entry.enabled = false; entry.credentialRef = null; entry.storageMode = "session"; }
    }
    entry[field] = value; model.error = ""; model.notice = "";
    if (field === "endpoint") {
      try {
        var target = new URL(value).href;
        var duplicate = model.draft.mcpServers.some(function (item) { return item.id !== entry.id && item.endpoint && new URL(item.endpoint).href === target; });
        var legacy = model.snapshot.legacyWebsearch;
        if (duplicate || legacy && legacy.endpoint && new URL(legacy.endpoint).href === target) model.notice = "duplicateTarget";
      } catch (ignored) { /* Backend owns strict endpoint validation. */ }
    }
    emit();
  };
  model.setSecret = function (value) {
    if (!model.busy && model.entry() && !(model.selected.kind === "mail" && model.activationNeedsRefresh)) {
      invalidate(); model.secret = value; model.error = ""; emit();
    }
  };
  model.setPersist = function (value) {
    if (!model.busy && model.snapshot && model.selected && model.selected.kind === "mcp") {
      model.persist = !!value && !!model.snapshot.persistentCredentialsSupported; emit();
    }
  };
  model.save = function () {
    if (!model.snapshot || model.busy || !metadataDirty()) return Promise.resolve();
    invalidate(); var current = generation; model.busy = "save"; model.error = ""; emit();
    var configuration = copy(model.draft);
    configuration.mailAccounts.forEach(function (account) {
      // Older accounts remain selected; reading/rendering alone must not dirty their metadata.
      if (account.folderScope === undefined) account.folderScope = "selected";
      if (!account.displayName.trim()) account.displayName = account.email;
    });
    return request("", "PUT", { expectedRevision: model.snapshot.config.revision, config: configuration })
      .then(function (snapshot) { if (alive && current === generation) { model.snapshot = snapshot; model.draft = copy(snapshot.config); model.notice = snapshot.credentialCleanupPending ? "cleanupPending" : "saved"; } })
      .catch(function (error) { if (alive && current === generation) model.error = integrationErrorText(error); })
      .finally(function () { if (alive && current === generation) { model.busy = ""; emit(); } });
  };
  model.hasStoredMailCredential = function () {
    var entry = model.entry(), live = entry && model.snapshot && model.snapshot.statuses && model.snapshot.statuses[entry.id];
    return !!(model.selected && model.selected.kind === "mail" && entry && entry.storageMode === "windows" && live && live.credentialConfigured);
  };
  model.activateMail = function (confirm) {
    var entry = model.entry();
    if (!alive || model.busy || metadataDirty() || !entry || model.selected.kind !== "mail") return Promise.resolve();
    var failure = model.activationNeedsRefresh || integrationMailActivationRecovery ? "mailOutcomeUncertain" : !model.snapshot.persistentCredentialsSupported ? "mailStorage"
      : !model.snapshot.modelTarget ? "modelTarget" : !model.secret && !model.hasStoredMailCredential() ? "mailCredential" : "";
    if (failure) { model.error = t("integrations.error." + failure); emit(); return Promise.resolve(); }
    invalidate(); var current = generation;
    var body = { id: entry.id, expectedRevision: model.snapshot.config.revision, disclosureVersion: 1, modelTarget: model.snapshot.modelTarget };
    var review = integrationMailActivationDisclosure(copy(entry), body.modelTarget);
    model.busy = "mail/review"; model.error = ""; model.notice = ""; emit();
    var accepted = false;
    try { accepted = typeof confirm === "function" && confirm(review) === true; }
    catch (ignored) { model.error = t("integrations.error.reviewRequired"); }
    finally { if (alive && current === generation) model.busy = ""; }
    if (!alive) return Promise.resolve();
    if (!accepted) { clearSecret(); emit(); return Promise.resolve(); }
    if (current !== generation || !model.selected || model.selected.id !== body.id || metadataDirty()
        || model.snapshot.config.revision !== body.expectedRevision || model.snapshot.modelTarget !== body.modelTarget) {
      clearSecret(); model.error = t("integrations.error.reviewRequired"); emit(); return Promise.resolve();
    }
    if (model.secret) body.secret = model.secret;
    clearSecret(); model.busy = "mail/activate"; controller = new AbortController(); emit();
    var recovery = { snapshot: copy(model.snapshot), pending: true };
    integrationMailActivationRecovery = recovery;
    var pending;
    try { pending = request("/mail/activate", "POST", body, controller.signal); }
    catch (error) { pending = Promise.reject(error); }
    return Promise.resolve(pending).then(function (result) {
      if (!alive || current !== generation) return;
      if (!integrationValidMailActivation(result, body, entry)) throw integrationUncertainMailOutcome();
      var draft = copy(result.config);
      model.snapshot = result; model.draft = draft;
      if (integrationMailActivationRecovery === recovery) integrationMailActivationRecovery = null;
      model.notice = "mailActivated";
    }).catch(function (error) {
      if (alive && current === generation) {
        model.activationNeedsRefresh = !integrationKnownMailRejection(error);
        if (!model.activationNeedsRefresh && integrationMailActivationRecovery === recovery) integrationMailActivationRecovery = null;
        model.error = integrationErrorText(model.activationNeedsRefresh ? integrationUncertainMailOutcome() : error, true);
        if (error && error.credentialCleanupPending === true) model.notice = "cleanupPending";
      }
    }).finally(function () { recovery.pending = false; body.secret = undefined; if (alive && current === generation) { model.busy = ""; emit(); } });
  };
  model.action = function (action, tool) {
    if (model.busy || metadataDirty() || !model.entry()) return Promise.resolve();
    if (model.selected.kind === "mail" && model.activationNeedsRefresh) return Promise.resolve();
    if (model.selected.kind === "legacy" && ["discover", "tools/approve"].indexOf(action) < 0) return Promise.resolve();
    if (action === "credential" && (model.selected.kind === "mail" || !model.secret)) return Promise.resolve();
    invalidate(); var current = generation, selected = copy(model.selected);
    var body = { kind: selected.kind, id: selected.id };
    if (action === "credential") Object.assign(body, { secret: model.secret, persist: model.persist, expectedRevision: model.snapshot.config.revision });
    if (action === "disconnect") body.expectedRevision = model.snapshot.config.revision;
    if (action === "discover") body = { serverId: selected.id };
    if (action === "tools/approve") body = { serverId: selected.id, toolName: tool.name, digest: tool.digest, expectedRevision: model.snapshot.config.revision };
    clearSecret(); model.busy = action; model.error = ""; model.notice = ""; controller = new AbortController(); emit();
    return request("/" + action, "POST", body, controller.signal).then(function (result) {
      if (!alive || current !== generation) return;
      if (result.config) { model.snapshot = result; model.draft = copy(result.config); }
      if (action === "test") model.diagnostic = result.config ? { ok: true } : result;
      if (action === "discover") model.tools = Array.isArray(result.tools) ? result.tools : [];
      model.notice = result.credentialCleanupPending ? "cleanupPending" : action === "test" ? "tested" : action === "discover" ? "discovered" : action === "disconnect" ? "disconnected" : "saved";
      // Status-only replies must not overwrite metadata or a newer draft.
      if (!result.config && action !== "test" && action !== "discover") return model.refresh();
    }).catch(function (error) { if (alive && current === generation) model.error = integrationErrorText(error); })
      .finally(function () { body.secret = undefined; if (alive && current === generation) { model.busy = ""; emit(); } });
  };
  model.revoke = function (tool) {
    if (model.busy || !model.entry()) return;
    var id = model.selected.id;
    model.draft.toolApprovals = model.draft.toolApprovals.filter(function (item) { return item.sourceId !== id || item.toolName !== tool.name; });
    invalidate(); model.notice = "revocationPending"; emit();
  };
  model.refresh = function () {
    if (!alive || model.busy === "mail/review" || model.busy === "mail/activate") return Promise.resolve();
    var version = ++readGeneration, current = generation;
    var recovery = integrationMailActivationRecovery, wasPending = !!(recovery && recovery.pending);
    return request("", "GET").then(function (snapshot) {
      if (!alive || version !== readGeneration || current !== generation) return;
      if (!integrationValidSnapshot(snapshot)) throw invalidRead();
      if (!model.dirty()) {
        var recovering = model.activationNeedsRefresh;
        acceptRead(snapshot, recovery, wasPending);
        if (recovering) {
          model.error = model.activationNeedsRefresh ? t("integrations.error.mailOutcomeUncertain") : "";
          model.notice = snapshot.credentialCleanupPending ? "cleanupPending" : "";
        }
      }
      else {
        model.snapshot.statuses = snapshot.statuses; model.snapshot.persistentCredentialsSupported = snapshot.persistentCredentialsSupported;
        model.snapshot.modelTarget = snapshot.modelTarget;
      }
      emit();
    }).catch(function (error) { if (alive && version === readGeneration && current === generation) { model.error = integrationErrorText(model.activationNeedsRefresh ? integrationUncertainMailOutcome() : error); emit(); } });
  };
  model.cancel = function () { if (!model.busy || model.busy === "test" || model.busy === "discover") { invalidate(); clearSecret(); model.busy = ""; emit(); } };
  model.destroy = function () { alive = false; invalidate(); clearSecret(); model.draft = null; model.snapshot = null; };
  return model;
}

function integrationMailActivationDisclosure(entry, modelTarget) {
  var scope = entry.folderScope === "all" ? t("integrations.activation.allFolders")
    : t("integrations.activation.selectedFolders") + "\n" + entry.allowedFolders.map(function (name) { return "• " + name; }).join("\n");
  return [t("integrations.activation.confirm"), t("integrations.email") + ": " + entry.email,
    t("integrations.folderScope") + ": " + scope,
    t("integrations.bodyAccess") + ": " + t(entry.bodyAccess ? "integrations.activation.bodyYes" : "integrations.activation.bodyNo"),
    t("integrations.modelTarget") + ": " + modelTarget, t("integrations.mailDisclosure"),
    t("integrations.mailPersistence"), t("integrations.mailRetention"), t("integrations.activation.effects")].join("\n\n");
}

// All server-originated labels, endpoints and tool arguments go through textContent/value.
function integrationElement(tag, text, className) {
  var el = document.createElement(tag); if (text != null) el.textContent = String(text);
  if (className) el.className = className; return el;
}
function integrationButton(text, action) {
  var button = integrationElement("button", text, "btn btn-sm btn-outline"); button.type = "button";
  button.addEventListener("click", action); return button;
}

function mountIntegrations(root) {
  var model = createIntegrationsModel(api.integrations, render), mounted = true, builtFor = null, controls = {}, status, error, notice, list, editor, tools, footer, connectionState;
  root.replaceChildren();
  var section = integrationElement("section", null, "integrations-settings");
  var scroller = integrationElement("div", null, "config-scroll-area"); section.appendChild(scroller);
  scroller.appendChild(integrationElement("h3", t("integrations.title")));
  scroller.appendChild(integrationElement("p", t("integrations.intro"), "integrations-hint"));
  status = integrationElement("p"); status.setAttribute("role", "status"); scroller.appendChild(status);
  var actions = integrationElement("div", null, "integrations-actions");
  ["mail", "mcp"].forEach(function (kind) {
    var add = integrationButton(t("integrations.add." + kind), function () {
      if (model.secret && !window.confirm(t("config.confirmDiscardClose"))) return; model.add(kind);
    });
    add.id = "integration-add-" + kind; actions.appendChild(add);
  }); scroller.appendChild(actions);
  list = integrationElement("div", null, "integrations-list"); scroller.appendChild(list);
  editor = integrationElement("div", null, "integrations-editor"); scroller.appendChild(editor);
  tools = integrationElement("div", null, "integrations-tools"); scroller.appendChild(tools);
  var legacy = integrationElement("p", t("integrations.legacy"), "integrations-hint"); scroller.appendChild(legacy);
  scroller.appendChild(integrationButton(t("integrations.openLegacy"), function () { switchConfigView("raw", "websearch.enabled"); }));
  error = integrationElement("p", null, "integrations-error"); error.setAttribute("role", "alert"); error.tabIndex = -1;
  notice = integrationElement("p"); notice.setAttribute("role", "status"); notice.tabIndex = -1;
  scroller.appendChild(error); scroller.appendChild(notice);
  footer = integrationElement("div", null, "config-action-bar integrations-actions");
  var discard = integrationButton(t("config.discard"), function () { if (!model.dirty() || window.confirm(t("config.confirmDiscardClose"))) model.load(); });
  var save = integrationButton(t("config.saveChanges"), function () { model.save().then(function () { if (mounted) (model.error ? error : notice).focus(); }); });
  footer.appendChild(discard); footer.appendChild(save); section.appendChild(footer); root.appendChild(section);
  function field(name, label, type, choices) {
    var wrap = integrationElement("label", t(label), "integrations-field"), input = integrationElement(choices ? "select" : name === "allowedFolders" ? "textarea" : "input");
    if (choices) choices.forEach(function (item) { var option = integrationElement("option", item[1]); option.value = item[0]; input.appendChild(option); });
    else if (input.tagName === "INPUT") input.type = type || "text";
    if (name === "secret") { input.autocomplete = "new-password"; input.spellcheck = false; input.maxLength = 2048; }
    if (input.type === "text") input.maxLength = 2048;
    input.id = "integration-" + name; wrap.htmlFor = input.id;
    input.addEventListener(input.type === "checkbox" || choices ? "change" : "input", function () {
      var value = input.type === "checkbox" ? input.checked : input.value;
      if (name === "secret") model.setSecret(value);
      else if (name === "persist") model.setPersist(value);
      else model.change(name, name === "allowedFolders" ? value.split(/\r?\n/).map(function (v) { return v.trim(); }).filter(Boolean) : value);
    });
    wrap.appendChild(input); editor.appendChild(wrap); controls[name] = input; return input;
  }
  function build(entry, kind) {
    editor.replaceChildren(); controls = {};
    editor.appendChild(integrationElement("h4", t("integrations.kind." + kind)));
    connectionState = integrationElement("p"); connectionState.setAttribute("role", "status"); editor.appendChild(connectionState);
    if (kind === "legacy") {
      editor.appendChild(integrationElement("p", t("integrations.legacy"), "integrations-hint"));
      var discover = integrationButton(t("integrations.action.discover"), function () { model.action("discover"); });
      controls.discover = discover; editor.appendChild(discover); return;
    }
    if (kind === "mail") {
      field("provider", "integrations.provider", "text", [["qq", "QQ"], ["163", "163"]]);
      var providerHelp = integrationElement("a", t("integrations.providerHelp"));
      providerHelp.id = "integration-provider-help"; providerHelp.target = "_blank"; providerHelp.rel = "noopener noreferrer";
      providerHelp.referrerPolicy = "no-referrer"; editor.appendChild(providerHelp);
      field("email", "integrations.email", "email"); field("displayName", "integrations.displayName");
      field("folderScope", "integrations.folderScope", "text", [["all", t("integrations.folderScope.all")], ["selected", t("integrations.folderScope.selected")]])
        .setAttribute("aria-describedby", "integration-folder-scope-hint");
      field("allowedFolders", "integrations.folders").setAttribute("aria-describedby", "integration-folder-scope-hint");
      var folderHint = integrationElement("p", null, "integrations-hint"); folderHint.id = "integration-folder-scope-hint"; editor.appendChild(folderHint);
      field("bodyAccess", "integrations.bodyAccess", "checkbox");
      editor.appendChild(integrationElement("p", t("integrations.mailDisclosure"), "integrations-disclosure"));
      var recipient = integrationElement("p", null, "integrations-disclosure"); recipient.id = "integration-model-target"; editor.appendChild(recipient);
      editor.appendChild(integrationElement("p", t("integrations.mailRetention"), "integrations-hint"));
    } else {
      field("name", "integrations.name"); field("endpoint", "integrations.endpoint", "url");
      field("authType", "integrations.authType", "text", [["anonymous", t("integrations.anonymous")], ["bearer", "Bearer"]]);
      editor.appendChild(integrationElement("p", t("integrations.remoteDisclosure"), "integrations-disclosure"));
    }
    if (kind === "mcp") field("enabled", "integrations.enabled", "checkbox");
    field("secret", kind === "mail" ? "integrations.authorizationCode" : "integrations.bearer", "password");
    if (kind === "mcp") {
      field("persist", "integrations.persist", "checkbox");
      editor.appendChild(integrationElement("p", t("integrations.sessionOnly"), "integrations-hint"));
    } else {
      editor.appendChild(integrationElement("p", t("integrations.mailPersistence"), "integrations-hint"));
      var storage = integrationElement("p", null, "integrations-hint"); storage.id = "integration-mail-storage"; editor.appendChild(storage);
    }
    var row = integrationElement("div", null, "integrations-actions");
    [kind === "mail" ? "activateMail" : "credential", "test", "disconnect"].concat(kind === "mcp" ? ["discover"] : ["refreshMail"]).forEach(function (action) {
      var button = integrationButton(t("integrations.action." + action), function () {
        if (action === "disconnect" && !window.confirm(t("integrations.confirmDisconnect"))) return;
        var operation = action === "activateMail" ? model.activateMail(function (review) { return window.confirm(review); })
          : action === "refreshMail" ? model.refresh() : model.action(action);
        operation.then(function () { if (mounted && builtFor) (model.error ? error : notice).focus(); });
      }); controls[action] = button; row.appendChild(button);
    });
    var cancel = integrationButton(t("action.cancel"), function () { model.cancel(); }); controls.cancel = cancel; row.appendChild(cancel); editor.appendChild(row);
    editor.appendChild(integrationElement("p", t(kind === "mail" ? "integrations.mailSaveFirst" : "integrations.saveFirst"), "integrations-hint"));
  }
  function render(data) {
    status.textContent = data.loading ? t("common.loading") : data.snapshot ? t("integrations.revision", { revision: data.snapshot.config.revision }) : "";
    error.textContent = data.error; notice.textContent = data.notice ? t("integrations.notice." + data.notice) : "";
    if (data.snapshot && data.snapshot.credentialCleanupPending && data.notice !== "cleanupPending") {
      notice.textContent += (notice.textContent ? " " : "") + t("integrations.notice.cleanupPending");
    }
    discard.disabled = !!data.busy; save.disabled = !!data.busy || !data.metadataDirty();
    actions.querySelectorAll("button").forEach(function (el) {
      el.disabled = !data.snapshot || !!data.busy || el.id === "integration-add-mail" && data.activationNeedsRefresh;
    });
    if (!data.draft) return;
    list.replaceChildren();
    ["mail", "mcp"].forEach(function (kind) {
      data.draft[kind === "mail" ? "mailAccounts" : "mcpServers"].forEach(function (item) {
        var button = integrationButton((kind === "mail" ? item.displayName || item.email : item.name || item.endpoint) || t("integrations.untitled"), function () {
          if (data.secret && !window.confirm(t("config.confirmDiscardClose"))) return; model.select(kind, item.id);
        });
        button.disabled = !!data.busy;
        button.setAttribute("aria-pressed", String(!!data.selected && item.id === data.selected.id)); list.appendChild(button);
      });
    });
    var legacyButton = integrationButton(t("integrations.kind.legacy"), function () {
      if (data.secret && !window.confirm(t("config.confirmDiscardClose"))) return; model.select("legacy", "legacy-websearch");
    }); legacyButton.disabled = !!data.busy; list.appendChild(legacyButton);
    var entry = data.entry(), key = entry && data.selected.kind + ":" + entry.id;
    if (key !== builtFor) { builtFor = key; if (entry) build(entry, data.selected.kind); else { editor.replaceChildren(); controls = {}; } }
    if (entry) {
      var live = data.snapshot.statuses && data.snapshot.statuses[entry.id] || {};
      var knownStates = ["disabled", "tested", "testing", "ready", "degraded", "auth_required", "unavailable", "not_connected"];
      if (live.state === "needs_auth") live = Object.assign({}, live, { state: "auth_required" });
      var liveState = knownStates.indexOf(live.state) >= 0 ? live.state : "unavailable";
      connectionState.textContent = data.selected.kind === "mail" && data.activationNeedsRefresh ? t("integrations.mailStateUncertain")
        : (data.selected.kind === "mail" ? t(entry.enabled ? "integrations.mailEnabled" : "integrations.mailDisabled") + " · " : "")
        + t(data.selected.kind === "mail" && liveState === "not_connected" ? "integrations.mailNotTested" : "integrations.state." + liveState) + " · "
        + t(live.credentialConfigured ? "integrations.credentialConfigured" : "integrations.credentialMissing");
    }
    if (entry && data.selected.kind === "mail") {
      var providerHelp = editor.querySelector("#integration-provider-help");
      providerHelp.href = entry.provider === "qq" ? "https://help.mail.qq.com/detail/106/985"
        : "https://help.mail.126.com/faqDetail.do?code=d7a5dc8471cd0c0e8b4b8f4f8e49998b374173cfe9171305fa1ce630d7f67ac2a5feb28b66796d3b";
      editor.querySelector("#integration-model-target").textContent = t("integrations.modelTarget") + ": " + (data.snapshot.modelTarget || t("integrations.unknownTarget"));
      var storageText = !data.snapshot.persistentCredentialsSupported ? t("integrations.error.mailStorage") : "";
      if (!data.snapshot.modelTarget) storageText += (storageText ? " " : "") + t("integrations.error.modelTarget");
      if (data.activationNeedsRefresh) storageText += (storageText ? " " : "") + t("integrations.error.mailOutcomeUncertain");
      if (entry.storageMode === "session" && entry.credentialRef) storageText += (storageText ? " " : "") + t("integrations.mailLegacySession");
      else if (data.hasStoredMailCredential()) storageText += (storageText ? " " : "") + t("integrations.mailStoredCredential");
      editor.querySelector("#integration-mail-storage").textContent = storageText;
      controls.activateMail.textContent = t(!data.secret && data.hasStoredMailCredential() ? "integrations.action.activateStoredMail" : "integrations.action.activateMail");
      editor.querySelector("#integration-folder-scope-hint").textContent = t(entry.folderScope === "all"
        ? "integrations.folderScope.allHint" : "integrations.folderScope.selectedHint");
    }
    if (entry) Object.keys(controls).forEach(function (name) {
      var input = controls[name];
      if (input.tagName === "BUTTON") {
        input.disabled = name === "cancel" ? (!!data.busy && data.busy !== "test" && data.busy !== "discover") || (!data.busy && !data.secret)
          : !!data.busy || data.metadataDirty() || name === "credential" && !data.secret
          || data.selected.kind === "mail" && data.activationNeedsRefresh && name !== "refreshMail"
          || name === "activateMail" && (data.activationNeedsRefresh || !data.snapshot.persistentCredentialsSupported || !data.snapshot.modelTarget || !data.secret && !data.hasStoredMailCredential());
        return;
      }
      input.disabled = !!data.busy || data.selected.kind === "mail" && data.activationNeedsRefresh || name === "persist" && !data.snapshot.persistentCredentialsSupported
        || name === "allowedFolders" && entry.folderScope === "all"
        || (name === "secret" || name === "persist") && data.selected.kind === "mcp" && entry.authType === "anonymous";
      var value = name === "secret" ? data.secret : name === "persist" ? data.persist : entry[name];
      if (name === "folderScope" && value === undefined) value = "selected";
      if (input.type === "checkbox") input.checked = !!value;
      else { value = Array.isArray(value) ? value.join("\n") : value == null ? "" : String(value); if (input.value !== value) input.value = value; }
    });
    tools.replaceChildren();
    if (data.diagnostic) tools.appendChild(integrationElement("p", data.diagnostic.ok ? t("integrations.testSuccess") : integrationErrorText(data.diagnostic)));
    var visibleTools = data.tools.length ? data.tools : entry && !data.metadataDirty() && data.snapshot.statuses
      && data.snapshot.statuses[entry.id] && data.snapshot.statuses[entry.id].tools || [];
    visibleTools.forEach(function (tool) {
      var card = integrationElement("article", null, "integration-tool");
      card.appendChild(integrationElement("h4", tool.name)); card.appendChild(integrationElement("p", tool.description));
      card.appendChild(integrationElement("pre", typeof tool.inputSchema === "string" ? tool.inputSchema : JSON.stringify(tool.inputSchema || {}, null, 2)));
      var approved = data.draft.toolApprovals.some(function (item) { return item.sourceId === data.selected.id && item.toolName === tool.name && item.definitionDigest === tool.digest; });
      var button = integrationButton(t(approved ? "integrations.revokeTool" : "integrations.approveTool"), function () {
        if (approved) model.revoke(tool); else model.action("tools/approve", tool);
      });
      button.disabled = !!data.busy || data.metadataDirty() || !tool.digest; card.appendChild(button); tools.appendChild(card);
    });
  }
  var approvalsRoot = integrationElement("section", null, "integration-approvals");
  scroller.appendChild(approvalsRoot);
  var approvals = typeof mountIntegrationApprovals === "function" ? mountIntegrationApprovals(approvalsRoot) : null;
  model.load();
  return { dirty: model.dirty, saving: function () { return !!model.busy && model.busy !== "test" && model.busy !== "discover"; }, destroy: function () {
    mounted = false; model.destroy(); if (approvals) approvals.destroy(true); Object.keys(controls).forEach(function (key) { if (controls[key].type === "password") controls[key].value = ""; }); root.replaceChildren();
  } };
}
