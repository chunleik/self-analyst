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
    invalid_config: "validation", unsupported_transport: "validation", tls_error: "tls", tls_failure: "tls", provider_restricted: "unsafeLogin", limit_exceeded: "folderLimit", mail_discovery_busy: "folderBusy" };
  code = aliases[code] || (/^(invalid_|unsupported_|disclosure_required)/.test(code) ? "validation" : code);
  var allowed = ["conflict", "authentication", "storage", "unsafeLogin", "permission", "stale", "validation", "tls", "timeout", "unavailable", "cancelled", "mailStorage", "mailCredential", "reviewRequired", "modelTarget", "mailOutcomeUncertain", "folderLimit", "folderBusy", "folderSelection"];
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
      && ["all", "selected"].indexOf(entry.folderScope) >= 0
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
    && saved.folderScope === entry.folderScope
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
    secret: "", persist: false, diagnostic: null, tools: [], outcomeUnknown: false, activationNeedsRefresh: !!integrationMailActivationRecovery };
  if (integrationMailActivationRecovery) {
    model.snapshot = copy(integrationMailActivationRecovery.snapshot); model.draft = copy(model.snapshot.config);
  }
  var alive = true, generation = 0, controller = null, readGeneration = 0, folderGeneration = 0, folderRequest = null;
  model.folders = { items: null, loading: false, error: "", stale: false, query: "" };
  function copy(value) { return JSON.parse(JSON.stringify(value)); }
  function emit() { if (alive) changed(model); }
  function invalidate() { cancelFolders(false); generation++; if (controller) controller.abort(); controller = null; model.diagnostic = null; model.tools = []; }
  function cancelFolders(clear) {
    folderGeneration++;
    if (folderRequest) {
      var pending = folderRequest; folderRequest = null;
      // Cancellation is independent of the aborted discovery response and never changes configuration.
      try { Promise.resolve(request("/mail/folders/cancel", "POST", { id: pending.id, requestId: pending.requestId })).catch(function () {}); }
      catch (ignored) { /* Backend also expires every operation. */ }
      pending.controller.abort();
    }
    model.folders.loading = false;
    if (clear) model.folders = { items: null, loading: false, error: "", stale: false, query: "" };
  }
  function maybeLoadFolders() {
    if (alive && model.selected && model.selected.kind === "mail" && !model.activationNeedsRefresh
        && !model.outcomeUnknown && !metadataDirty() && (model.secret || model.hasStoredMailCredential())) return model.loadFolders();
    return Promise.resolve();
  }
  function clearSecret() { model.secret = ""; model.persist = false; }
  function invalidRead() { return model.activationNeedsRefresh || integrationMailActivationRecovery ? integrationUncertainMailOutcome() : { code: "unavailable" }; }
  function acceptRead(snapshot, recovery, wasPending) {
    if (!integrationValidSnapshot(snapshot)) throw invalidRead();
    var draft = copy(snapshot.config);
    if (recovery === integrationMailActivationRecovery && !wasPending) integrationMailActivationRecovery = null;
    if (model.snapshot && model.snapshot.config.revision !== snapshot.config.revision) cancelFolders(true);
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
    invalidate(); cancelFolders(true); clearSecret(); var current = generation; model.loading = true; model.error = ""; emit();
    controller = new AbortController();
    var recovery = integrationMailActivationRecovery, wasPending = !!(recovery && recovery.pending);
    return request("", "GET", undefined, controller.signal).then(function (snapshot) {
      if (!alive || current !== generation) return;
      acceptRead(snapshot, recovery, wasPending); model.selected = null; model.notice = ""; model.outcomeUnknown = false;
      if (model.activationNeedsRefresh) model.error = t("integrations.error.mailOutcomeUncertain");
    }).catch(function (error) { if (alive && current === generation) model.error = integrationErrorText(model.activationNeedsRefresh ? integrationUncertainMailOutcome() : error); })
      .finally(function () { if (alive && current === generation) { model.loading = false; emit(); } });
  };
  model.select = function (kind, id) {
    if (model.busy || model.loading) return;
    invalidate(); cancelFolders(true); clearSecret(); model.selected = { kind: kind, id: id }; model.error = ""; model.notice = ""; emit();
    return maybeLoadFolders();
  };
  model.add = function (kind) {
    if (!model.draft || model.loading || model.outcomeUnknown || model.busy || kind === "mail" && model.activationNeedsRefresh) return;
    var id = (kind === "mail" ? "mail-" : "mcp-") + crypto.randomUUID();
    var entry = kind === "mail" ? { id: id, provider: "qq", email: "", displayName: "", enabled: false,
      folderScope: "all", allowedFolders: ["INBOX"], bodyAccess: false, credentialRef: null, storageMode: "session", disclosureVersion: 0 }
      : { id: id, name: "", enabled: false, transport: "streamable-http", endpoint: "", authType: "anonymous", credentialRef: null, storageMode: "session",
        budget: { connectTimeoutSeconds: 10, requestTimeoutSeconds: 30, maxTools: 64, maxSchemaBytes: 32768, maxResultBytes: 262144 } };
    model.draft[kind === "mail" ? "mailAccounts" : "mcpServers"].push(entry); model.select(kind, id);
  };
  model.change = function (field, value) {
    var entry = model.entry(); if (!entry || model.busy || model.outcomeUnknown || model.selected.kind === "legacy"
      || model.selected.kind === "mail" && model.activationNeedsRefresh) return;
    var allowed = model.selected.kind === "mail"
      ? ["provider", "email", "displayName", "folderScope", "allowedFolders", "bodyAccess"]
      : ["name", "endpoint", "authType", "enabled"];
    if (allowed.indexOf(field) < 0) return;
    invalidate();
    if (["provider", "email", "endpoint", "authType"].indexOf(field) >= 0) {
      cancelFolders(true); clearSecret();
      // Mail permission and credential resets belong to the metadata transaction on the server.
      if (model.selected.kind === "mcp") { entry.enabled = false; entry.credentialRef = null; entry.storageMode = "session"; }
    }
    if (field === "folderScope" && value === "selected" && !entry.allowedFolders.length && model.folders.items && !model.folders.stale)
      entry.allowedFolders = model.folders.items.filter(function (item) { return item.selectable; }).map(function (item) { return item.name; });
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
    if (!model.busy && !model.outcomeUnknown && model.entry() && !(model.selected.kind === "mail" && model.activationNeedsRefresh)) {
      invalidate(); cancelFolders(true); model.secret = value; model.error = ""; emit();
    }
  };
  model.setPersist = function (value) {
    if (!model.busy && !model.outcomeUnknown && model.snapshot && model.selected && model.selected.kind === "mcp") {
      model.persist = !!value && !!model.snapshot.persistentCredentialsSupported; emit();
    }
  };
  model.save = function () {
    if (!model.snapshot || model.busy || model.outcomeUnknown || !metadataDirty()) return Promise.resolve();
    if (model.draft.mailAccounts.some(function (entry) {
      return ["all", "selected"].indexOf(entry.folderScope) < 0 || entry.allowedFolders.length > 256
        || entry.folderScope === "selected" && !entry.allowedFolders.length;
    })) { model.error = t("integrations.error.folderSelection"); emit(); return Promise.resolve(); }
    invalidate(); var saved = false, current = generation; model.busy = "save"; model.error = ""; emit();
    var configuration = copy(model.draft);
    configuration.mailAccounts.forEach(function (account) {
      if (!account.displayName.trim()) account.displayName = account.email;
    });
    return request("", "PUT", { expectedRevision: model.snapshot.config.revision, config: configuration })
      .then(function (snapshot) { if (alive && current === generation) { if (!integrationValidSnapshot(snapshot)) throw { code: "invalid_snapshot" }; model.snapshot = snapshot; model.draft = copy(snapshot.config); saved = true; cancelFolders(true); model.notice = snapshot.credentialCleanupPending ? "cleanupPending" : "saved"; } })
      .catch(function (error) { if (alive && current === generation) model.error = integrationErrorText(error); })
      .finally(function () { if (alive && current === generation) { model.busy = ""; emit(); if (saved) return maybeLoadFolders(); } });
  };
  model.hasStoredMailCredential = function () {
    var entry = model.entry(), live = entry && model.snapshot && model.snapshot.statuses && model.snapshot.statuses[entry.id];
    return !!(model.selected && model.selected.kind === "mail" && entry && entry.storageMode === "windows" && live && live.credentialConfigured);
  };
  model.activateMail = function (confirm) {
    var entry = model.entry();
    if (!alive || model.busy || model.outcomeUnknown || metadataDirty() || !entry || model.selected.kind !== "mail") return Promise.resolve();
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
    }).finally(function () { recovery.pending = false; body.secret = undefined; if (alive && current === generation) { model.busy = ""; emit(); if (model.notice === "mailActivated") { cancelFolders(true); return maybeLoadFolders(); } } });
  };
  model.action = function (action, tool) {
    if (model.busy || model.outcomeUnknown || metadataDirty() || !model.entry()) return Promise.resolve();
    if (model.selected.kind === "mail" && model.activationNeedsRefresh) return Promise.resolve();
    if (model.selected.kind === "legacy" && ["discover", "tools/approve"].indexOf(action) < 0) return Promise.resolve();
    if (action === "credential" && (model.selected.kind === "mail" || !model.secret)) return Promise.resolve();
    invalidate(); if (action === "disconnect") cancelFolders(true); var current = generation, selected = copy(model.selected);
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
  model.savedEntry = function () {
    if (!model.selected || !model.snapshot || model.selected.kind === "legacy") return null;
    return model.snapshot.config[model.selected.kind === "mail" ? "mailAccounts" : "mcpServers"]
      .find(function (item) { return item.id === model.selected.id; }) || null;
  };
  model.remove = function (confirmDelete) {
    if (model.busy || model.loading || model.outcomeUnknown || model.selected && model.selected.kind === "mail" && model.activationNeedsRefresh || !model.entry() || model.selected.kind === "legacy") return Promise.resolve();
    var selected = copy(model.selected), saved = model.savedEntry(), identity = copy(saved || model.entry());
    if (typeof confirmDelete !== "function" || !confirmDelete({ saved: !!saved, kind: selected.kind, entry: identity })) return Promise.resolve();
    invalidate(); cancelFolders(true); clearSecret(); var current = generation;
    var collection = selected.kind === "mail" ? "mailAccounts" : "mcpServers";
    function removeFrom(config) {
      config[collection] = config[collection].filter(function (item) { return item.id !== selected.id; });
      config.toolApprovals = config.toolApprovals.filter(function (item) { return item.sourceId !== selected.id; });
    }
    if (!saved) {
      removeFrom(model.draft); model.selected = null; model.error = ""; model.notice = "draftDeleted"; emit(); return Promise.resolve();
    }
    var previous = copy(model.snapshot.config), keptDraft = copy(model.draft), expected = copy(previous);
    removeFrom(expected); expected.revision++;
    // Canonical comparison ensures an unrelated or malformed success cannot silently discard drafts.
    function canonical(value) {
      if (Array.isArray(value)) return "[" + value.map(canonical).join(",") + "]";
      if (value && typeof value === "object") return "{" + Object.keys(value).sort().map(function (key) {
        return JSON.stringify(key) + ":" + canonical(value[key]);
      }).join(",") + "}";
      return JSON.stringify(value);
    }
    model.busy = "delete"; model.error = ""; model.notice = ""; emit();
    return request("/delete", "POST", { kind: selected.kind, id: selected.id, expectedRevision: previous.revision })
      .then(function (snapshot) {
        if (!alive || current !== generation) return;
        if (!snapshot || !snapshot.config || canonical(snapshot.config) !== canonical(expected)) throw new Error("unverified_delete");
        removeFrom(keptDraft); keptDraft.revision = snapshot.config.revision;
        model.snapshot = snapshot; model.draft = keptDraft; model.selected = null;
        model.notice = snapshot.credentialCleanupPending ? "cleanupPending" : "deleted";
      }).catch(function (error) {
        if (!alive || current !== generation) return;
        var code = error && error.code;
        if (code === "conflict" || code === "revision_conflict" || error && (error.status === 401 || error.status === 403)) {
          model.error = integrationErrorText(error);
        } else {
          model.outcomeUnknown = true; model.error = t("integrations.deleteUnknown");
        }
      }).finally(function () { if (alive && current === generation) { model.busy = ""; emit(); } });
  };
  model.revoke = function (tool) {
    if (model.busy || model.outcomeUnknown || !model.entry()) return;
    var id = model.selected.id;
    model.draft.toolApprovals = model.draft.toolApprovals.filter(function (item) { return item.sourceId !== id || item.toolName !== tool.name; });
    invalidate(); model.notice = "revocationPending"; emit();
  };
  model.refresh = function () {
    if (!alive || model.outcomeUnknown || model.busy === "delete" || model.busy === "save" || model.busy === "mail/review" || model.busy === "mail/activate") return Promise.resolve();
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
  model.loadFolders = function () {
    var entry = model.entry();
    if (!alive || model.busy || model.outcomeUnknown || model.activationNeedsRefresh || metadataDirty() || !entry
        || model.selected.kind !== "mail" || model.folders.loading) return Promise.resolve();
    if (!model.secret && !model.hasStoredMailCredential()) {
      model.folders.error = t("integrations.folders.waitingCredential"); emit(); return Promise.resolve();
    }
    cancelFolders(false);
    var current = folderGeneration, id = entry.id, revision = model.snapshot.config.revision;
    var body = { id: id, expectedRevision: revision, requestId: crypto.randomUUID() };
    if (model.secret) body.secret = model.secret;
    var operation = { id: id, requestId: body.requestId, controller: new AbortController() }; folderRequest = operation;
    model.folders.loading = true; model.folders.error = ""; model.folders.stale = !!model.folders.items; emit();
    var pending;
    try { pending = request("/mail/folders", "POST", body, operation.controller.signal); }
    catch (error) { pending = Promise.reject(error); }
    return Promise.resolve(pending).then(function (result) {
      if (!alive || current !== folderGeneration || !model.selected || model.selected.id !== id
          || model.snapshot.config.revision !== revision) return;
      var names = [];
      if (!result || result.accountId !== id || result.revision !== revision || result.complete !== true
          || !Array.isArray(result.folders) || result.folders.length > 256 || !result.folders.every(function (item) {
            if (!item || typeof item.name !== "string" || !item.name || item.name.length > 256 || /[\u0000-\u001f\u007f]/.test(item.name)
                || typeof item.separator !== "string" || item.separator.length > 1 || typeof item.selectable !== "boolean"
                || names.indexOf(item.name) >= 0) return false;
            names.push(item.name); return true;
          })) throw { code: "invalid_folder_response" };
      model.folders.items = copy(result.folders); model.folders.stale = false;
    }).catch(function (error) {
      if (alive && current === folderGeneration) model.folders.error = integrationErrorText(error);
    }).finally(function () {
      delete body.secret;
      if (folderRequest === operation) folderRequest = null;
      if (alive && current === folderGeneration) { model.folders.loading = false; emit(); }
    });
  };
  model.filterFolders = function (query) { model.folders.query = String(query); emit(); };
  model.toggleFolder = function (name, checked) {
    var entry = model.entry();
    if (!entry || model.selected.kind !== "mail" || model.busy || model.outcomeUnknown || model.activationNeedsRefresh) return;
    var available = model.folders.items && !model.folders.stale && model.folders.items.filter(function (item) { return item.selectable; });
    if (entry.folderScope === "all") {
      if (!available || checked || !available.some(function (item) { return item.name === name; })) return;
      entry.allowedFolders = available.map(function (item) { return item.name; }); entry.folderScope = "selected";
    }
    if (checked && (!available || !available.some(function (item) { return item.name === name; }))) return;
    var selected = entry.allowedFolders.filter(function (item) { return item !== name; });
    if (checked) selected.push(name);
    model.change("allowedFolders", selected);
  };
  model.selectCurrentFolders = function (clear) {
    var entry = model.entry();
    if (!entry || model.selected.kind !== "mail" || model.busy || model.outcomeUnknown || model.activationNeedsRefresh
        || !clear && (!model.folders.items || model.folders.stale)) return;
    entry.folderScope = "selected";
    model.change("allowedFolders", clear ? [] : model.folders.items.filter(function (item) { return item.selectable; }).map(function (item) { return item.name; }));
  };
  model.cancel = function () { if (!model.busy || model.busy === "test" || model.busy === "discover") { invalidate(); clearSecret(); model.busy = ""; emit(); } };
  model.destroy = function () { alive = false; invalidate(); cancelFolders(true); clearSecret(); model.draft = null; model.snapshot = null; };
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
  var model = createIntegrationsModel(api.integrations, render), mounted = true, builtFor = null, controls = {}, status, error, notice, list, editor, tools, footer, connectionState, folderList, folderStatus, folderCount, folderSearch, folderLoad, folderAll, folderClear;
  var catalogOpen = false, catalog = createIntegrationToolsDialog({
    onDiscover: function () { return model.action("discover"); },
    onClose: function () { catalogOpen = false; if (model.busy === "discover") model.cancel(); }
  });
  function catalogView() {
    var entry = model.entry(), kind = model.selected && model.selected.kind;
    if (!entry) return null;
    var live = model.snapshot.statuses && model.snapshot.statuses[entry.id] || {};
    var legacy = model.snapshot.legacyWebsearch || {};
    return { id: entry.id, kind: kind, name: kind === "legacy" ? t("integrations.kind.legacy") : entry.name || t("integrations.untitled"),
      endpoint: kind === "legacy" ? legacy.endpoint || "" : entry.endpoint || "", state: live.state || "not_connected",
      tools: model.metadataDirty() ? [] : model.tools.length ? model.tools : live.tools || [],
      known: !model.metadataDirty() && (live.catalogAvailable === true || live.state === "ready" || model.notice === "discovered"),
      busy: model.busy === "discover", error: model.error, canDiscover: !model.busy && !model.loading && !model.outcomeUnknown && !model.metadataDirty(),
      hint: model.metadataDirty() ? t("integrations.catalog.saveFirst") : "" };
  }
  function openCatalog(trigger) { catalogOpen = true; catalog.open(catalogView(), trigger); }
  function confirmRemoval(value) {
    var item = value.entry, name = value.kind === "mail" ? item.displayName || item.email : item.name || item.endpoint;
    var target = value.kind === "mail" ? item.email : item.endpoint;
    return window.confirm(t(value.saved ? "integrations.confirmDeleteSaved" : "integrations.confirmDeleteDraft", {
      name: name || t("integrations.untitled"), target: target || t("integrations.untitled"), id: item.id
    }));
  }
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
  var discard = integrationButton(t("config.discard"), function () { if (!(model.dirty() || model.outcomeUnknown) || window.confirm(t("config.confirmDiscardClose"))) model.load(); });
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
      controls.discover = discover; editor.appendChild(discover);
      var legacyCatalog = integrationButton(t("integrations.catalog.open"), function () { openCatalog(legacyCatalog); });
      controls.catalog = legacyCatalog; editor.appendChild(legacyCatalog); return;
    }
    if (kind === "mail") {
      field("provider", "integrations.provider", "text", [["qq", "QQ"], ["163", "163"]]);
      var providerHelp = integrationElement("a", t("integrations.providerHelp"));
      providerHelp.id = "integration-provider-help"; providerHelp.target = "_blank"; providerHelp.rel = "noopener noreferrer";
      providerHelp.referrerPolicy = "no-referrer"; editor.appendChild(providerHelp);
      field("email", "integrations.email", "email"); field("displayName", "integrations.displayName");
      field("folderScope", "integrations.folderScope", "text", [["all", t("integrations.folderScope.all")], ["selected", t("integrations.folderScope.selected")]])
        .setAttribute("aria-describedby", "integration-folder-scope-hint");
      var folderHint = integrationElement("p", null, "integrations-hint"); folderHint.id = "integration-folder-scope-hint"; editor.appendChild(folderHint);
      var picker = integrationElement("section", null, "integration-folder-picker"); picker.setAttribute("aria-label", t("integrations.folders.title"));
      var searchLabel = integrationElement("label", t("integrations.folders.search"), "integrations-field");
      folderSearch = integrationElement("input"); folderSearch.type = "search"; folderSearch.id = "integration-folder-search";
      searchLabel.htmlFor = folderSearch.id; folderSearch.maxLength = 256;
      folderSearch.addEventListener("input", function () { model.filterFolders(folderSearch.value); }); searchLabel.appendChild(folderSearch); picker.appendChild(searchLabel);
      folderStatus = integrationElement("p", null, "integrations-hint"); folderStatus.setAttribute("role", "status"); picker.appendChild(folderStatus);
      folderCount = integrationElement("p", null, "integrations-hint"); folderCount.setAttribute("aria-live", "polite"); picker.appendChild(folderCount);
      var folderActions = integrationElement("div", null, "integrations-actions");
      folderLoad = integrationButton(t("integrations.folders.load"), function () { model.loadFolders(); }); folderLoad.id = "integration-folder-load";
      folderAll = integrationButton(t("integrations.folders.selectCurrent"), function () { model.selectCurrentFolders(false); });
      folderClear = integrationButton(t("integrations.folders.clear"), function () { model.selectCurrentFolders(true); });
      folderActions.appendChild(folderLoad); folderActions.appendChild(folderAll); folderActions.appendChild(folderClear); picker.appendChild(folderActions);
      folderList = integrationElement("div", null, "integration-folder-list"); folderList.setAttribute("role", "group");
      folderList.setAttribute("aria-label", t("integrations.folders.title")); picker.appendChild(folderList); editor.appendChild(picker);
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
    var remove = integrationButton(t("integrations.action.delete"), function () {
      model.remove(confirmRemoval).then(function () { if (mounted) (model.error ? error : notice).focus(); });
    }); controls.delete = remove; remove.classList.add("integration-delete"); row.appendChild(remove);
    if (kind === "mcp") {
      var viewTools = integrationButton(t("integrations.catalog.open"), function () { openCatalog(viewTools); });
      controls.catalog = viewTools; row.appendChild(viewTools);
    }
    var cancel = integrationButton(t("action.cancel"), function () { model.cancel(); }); controls.cancel = cancel; row.appendChild(cancel); editor.appendChild(row);
    editor.appendChild(integrationElement("p", t(kind === "mail" ? "integrations.mailSaveFirst" : "integrations.saveFirst"), "integrations-hint"));
  }
  function renderFolders(data, entry) {
    var state = data.folders, locked = !!data.busy || data.outcomeUnknown || data.activationNeedsRefresh;
    var items = state.items || [], usable = state.items && !state.stale;
    var waiting = !data.secret && !data.hasStoredMailCredential();
    folderLoad.textContent = t(state.items ? "integrations.folders.refresh" : "integrations.folders.load");
    folderLoad.disabled = locked || data.metadataDirty() || state.loading || waiting;
    folderAll.disabled = locked || !usable; folderClear.disabled = locked;
    folderSearch.disabled = locked; if (folderSearch.value !== state.query) folderSearch.value = state.query;
    folderStatus.textContent = state.loading ? t("integrations.folders.loading") : state.error
      || (data.metadataDirty() ? t("integrations.folders.saveFirst") : waiting ? t("integrations.folders.waitingCredential")
      : state.items ? t(items.length ? "integrations.folders.loaded" : "integrations.folders.empty") : t("integrations.folders.ready"));
    if (state.stale) folderStatus.textContent += " " + t("integrations.folders.stale");
    folderCount.textContent = entry.folderScope === "all" ? t("integrations.folders.dynamicAll")
      : t("integrations.folders.selectedCount", { count: entry.allowedFolders.length });
    if (entry.folderScope === "selected" && !entry.allowedFolders.length) folderCount.textContent += " " + t("integrations.error.folderSelection");
    var rows = items.slice(), present = rows.map(function (item) { return item.name; });
    if (entry.folderScope === "selected") entry.allowedFolders.forEach(function (name) {
      if (present.indexOf(name) < 0) rows.push({ name: name, separator: "", selectable: true, unavailable: true });
    });
    rows.sort(function (a, b) { return a.name.localeCompare(b.name); });
    var focusName = document.activeElement && document.activeElement.getAttribute("data-folder-name");
    folderList.replaceChildren(); folderList.setAttribute("aria-busy", String(state.loading));
    rows.filter(function (item) { return item.name.toLocaleLowerCase().indexOf(state.query.toLocaleLowerCase()) >= 0; }).forEach(function (item, index) {
      var row = integrationElement("label", null, "integration-folder-row");
      if (item.separator && item.name.indexOf(item.separator) >= 0) row.classList.add("integration-folder-nested");
      var checkbox = integrationElement("input"); checkbox.type = "checkbox"; checkbox.id = "integration-folder-choice-" + index;
      row.htmlFor = checkbox.id; checkbox.setAttribute("data-folder-name", item.name);
      checkbox.checked = item.selectable && (entry.folderScope === "all" || entry.allowedFolders.indexOf(item.name) >= 0);
      checkbox.disabled = locked || !item.selectable || entry.folderScope === "all" && !usable
        || !checkbox.checked && (!usable || item.unavailable);
      checkbox.addEventListener("change", function () { model.toggleFolder(item.name, checkbox.checked); }); row.appendChild(checkbox);
      var text = integrationElement("span", item.name, "integration-folder-path"); row.appendChild(text);
      if (item.unavailable) row.appendChild(integrationElement("span", t("integrations.folders.unavailable"), "integrations-hint"));
      if (!item.selectable) row.appendChild(integrationElement("span", t("integrations.folders.container"), "integrations-hint"));
      folderList.appendChild(row); if (focusName === item.name) checkbox.focus();
    });
  }
  function render(data) {
    status.textContent = data.loading ? t("common.loading") : data.snapshot ? t("integrations.revision", { revision: data.snapshot.config.revision }) : "";
    error.textContent = data.outcomeUnknown ? t("integrations.deleteUnknown") : data.error; notice.textContent = data.notice ? t("integrations.notice." + data.notice) : "";
    if (data.snapshot && data.snapshot.credentialCleanupPending && data.notice !== "cleanupPending") {
      notice.textContent += (notice.textContent ? " " : "") + t("integrations.notice.cleanupPending");
    }
    discard.disabled = !!data.busy || data.loading; save.disabled = !!data.busy || data.loading || data.outcomeUnknown || !data.metadataDirty();
    actions.querySelectorAll("button").forEach(function (el) {
      el.disabled = !data.snapshot || data.loading || data.outcomeUnknown || !!data.busy || el.id === "integration-add-mail" && data.activationNeedsRefresh;
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
      if (data.hasStoredMailCredential()) storageText += (storageText ? " " : "") + t("integrations.mailStoredCredential");
      editor.querySelector("#integration-mail-storage").textContent = storageText;
      controls.activateMail.textContent = t(!data.secret && data.hasStoredMailCredential() ? "integrations.action.activateStoredMail" : "integrations.action.activateMail");
      editor.querySelector("#integration-folder-scope-hint").textContent = t(entry.folderScope === "all"
        ? "integrations.folderScope.allHint" : "integrations.folderScope.selectedHint");
      renderFolders(data, entry);
    }
    if (entry) Object.keys(controls).forEach(function (name) {
      var input = controls[name];
      if (input.tagName === "BUTTON") {
        if (name === "catalog") { input.disabled = !!data.busy; return; }
        if (name === "delete") { input.disabled = !!data.busy || data.loading || data.outcomeUnknown || data.selected.kind === "mail" && data.activationNeedsRefresh; return; }
        input.disabled = name === "cancel" ? (!!data.busy && data.busy !== "test" && data.busy !== "discover") || (!data.busy && !data.secret && !data.folders.loading)
          : !!data.busy || data.outcomeUnknown || data.metadataDirty() || name === "credential" && !data.secret
          || data.selected.kind === "mail" && data.activationNeedsRefresh && name !== "refreshMail"
          || name === "activateMail" && (data.activationNeedsRefresh || !data.snapshot.persistentCredentialsSupported || !data.snapshot.modelTarget || !data.secret && !data.hasStoredMailCredential());
        return;
      }
      input.disabled = !!data.busy || data.outcomeUnknown || data.selected.kind === "mail" && data.activationNeedsRefresh || name === "persist" && !data.snapshot.persistentCredentialsSupported
                || (name === "secret" || name === "persist") && data.selected.kind === "mcp" && entry.authType === "anonymous";
      var value = name === "secret" ? data.secret : name === "persist" ? data.persist : entry[name];

      if (input.type === "checkbox") input.checked = !!value;
      else { value = Array.isArray(value) ? value.join("\n") : value == null ? "" : String(value); if (input.value !== value) input.value = value; }
    });
    if (catalogOpen) {
      var view = catalogView();
      if (!view || view.kind === "mail") catalog.close(); else catalog.update(view);
    }
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
      button.disabled = !!data.busy || data.outcomeUnknown || data.metadataDirty() || !tool.digest; card.appendChild(button); tools.appendChild(card);
    });
  }
  var approvalsRoot = integrationElement("section", null, "integration-approvals");
  scroller.appendChild(approvalsRoot);
  var approvals = typeof mountIntegrationApprovals === "function" ? mountIntegrationApprovals(approvalsRoot) : null;
  model.load();
  return { dirty: model.dirty, saving: function () { return !!model.busy && model.busy !== "test" && model.busy !== "discover"; }, destroy: function () {
    mounted = false; catalog.destroy(); model.destroy(); if (approvals) approvals.destroy(true); Object.keys(controls).forEach(function (key) { if (controls[key].type === "password") controls[key].value = ""; }); root.replaceChildren();
  } };
}
