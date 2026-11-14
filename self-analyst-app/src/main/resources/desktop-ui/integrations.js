"use strict";

// Public metadata and write-only secrets deliberately have separate request paths.
function integrationErrorText(error) {
  var code = String(error && (error.code || error.errorCode) || "unavailable").split(/[.]/).pop();
  var aliases = { revision_conflict: "conflict", auth_required: "authentication", authentication_required: "authentication", authentication_failed: "authentication",
    storage_unavailable: "storage", unsafe_login: "unsafeLogin", permission_changed: "permission", integration_authorization_required: "permission", managed_desktop_required: "permission", stale_reference: "stale",
    invalid_config: "validation", unsupported_transport: "validation", tls_error: "tls", tls_failure: "tls", provider_restricted: "unsafeLogin" };
  code = aliases[code] || (/^(invalid_|unsupported_|disclosure_required)/.test(code) ? "validation" : code);
  var allowed = ["conflict", "authentication", "storage", "unsafeLogin", "permission", "stale", "validation", "tls", "timeout", "unavailable", "cancelled"];
  return t("integrations.error." + (allowed.indexOf(code) >= 0 ? code : "unavailable"));
}

function createIntegrationsModel(request, changed) {
  var model = { snapshot: null, draft: null, selected: null, loading: false, busy: "", error: "", notice: "",
    secret: "", persist: false, diagnostic: null, tools: [], outcomeUnknown: false };
  var alive = true, generation = 0, controller = null, readGeneration = 0;
  function copy(value) { return JSON.parse(JSON.stringify(value)); }
  function emit() { if (alive) changed(model); }
  function invalidate() { generation++; if (controller) controller.abort(); controller = null; model.diagnostic = null; model.tools = []; }
  function clearSecret() { model.secret = ""; model.persist = false; }
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
    return request("", "GET", undefined, controller.signal).then(function (snapshot) {
      if (!alive || current !== generation) return;
      model.snapshot = snapshot; model.draft = copy(snapshot.config); model.selected = null; model.notice = ""; model.outcomeUnknown = false;
    }).catch(function (error) { if (alive && current === generation) model.error = integrationErrorText(error); })
      .finally(function () { if (alive && current === generation) { model.loading = false; emit(); } });
  };
  model.select = function (kind, id) {
    if (model.busy || model.loading) return;
    invalidate(); clearSecret(); model.selected = { kind: kind, id: id }; model.error = ""; model.notice = ""; emit();
  };
  model.add = function (kind) {
    if (!model.draft || model.busy || model.loading || model.outcomeUnknown) return;
    var id = (kind === "mail" ? "mail-" : "mcp-") + crypto.randomUUID();
    var entry = kind === "mail" ? { id: id, provider: "qq", email: "", displayName: "", enabled: false,
      allowedFolders: ["INBOX"], bodyAccess: false, credentialRef: null, storageMode: "session", disclosureVersion: 0 }
      : { id: id, name: "", enabled: false, transport: "streamable-http", endpoint: "", authType: "anonymous", credentialRef: null, storageMode: "session",
        budget: { connectTimeoutSeconds: 10, requestTimeoutSeconds: 30, maxTools: 64, maxSchemaBytes: 32768, maxResultBytes: 262144 } };
    model.draft[kind === "mail" ? "mailAccounts" : "mcpServers"].push(entry); model.select(kind, id);
  };
  model.change = function (field, value) {
    var entry = model.entry(); if (!entry || model.busy || model.outcomeUnknown || model.selected.kind === "legacy") return;
    var allowed = model.selected.kind === "mail"
      ? ["provider", "email", "displayName", "enabled", "allowedFolders", "bodyAccess", "disclosureVersion"]
      : ["name", "endpoint", "authType", "enabled"];
    if (allowed.indexOf(field) < 0) return;
    invalidate();
    if (["provider", "email", "endpoint", "authType"].indexOf(field) >= 0) { clearSecret(); entry.enabled = false; entry.credentialRef = null; entry.storageMode = "session"; }
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
  model.setSecret = function (value) { if (!model.busy && !model.outcomeUnknown && model.entry()) { invalidate(); model.secret = value; model.error = ""; emit(); } };
  model.setPersist = function (value) { if (!model.busy && !model.outcomeUnknown) { model.persist = !!value && !!model.snapshot.persistentCredentialsSupported; emit(); } };
  model.save = function () {
    if (!model.snapshot || model.busy || model.outcomeUnknown || !metadataDirty()) return Promise.resolve();
    var invalid = model.draft.mailAccounts.some(function (item) { return item.enabled && item.disclosureVersion !== 1; });
    if (invalid) { model.error = t("integrations.consentRequired"); emit(); return Promise.resolve(); }
    invalidate(); var current = generation; model.busy = "save"; model.error = ""; emit();
    var configuration = copy(model.draft);
    configuration.mailAccounts.forEach(function (account) { if (!account.displayName.trim()) account.displayName = account.email; });
    return request("", "PUT", { expectedRevision: model.snapshot.config.revision, config: configuration })
      .then(function (snapshot) { if (alive && current === generation) { model.snapshot = snapshot; model.draft = copy(snapshot.config); model.notice = snapshot.credentialCleanupPending ? "cleanupPending" : "saved"; } })
      .catch(function (error) { if (alive && current === generation) model.error = integrationErrorText(error); })
      .finally(function () { if (alive && current === generation) { model.busy = ""; emit(); } });
  };
  model.action = function (action, tool) {
    if (model.busy || model.outcomeUnknown || metadataDirty() || !model.entry()) return Promise.resolve();
    if (model.selected.kind === "legacy" && ["discover", "tools/approve"].indexOf(action) < 0) return Promise.resolve();
    if (action === "credential" && !model.secret) return Promise.resolve();
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
  model.savedEntry = function () {
    if (!model.selected || !model.snapshot || model.selected.kind === "legacy") return null;
    return model.snapshot.config[model.selected.kind === "mail" ? "mailAccounts" : "mcpServers"]
      .find(function (item) { return item.id === model.selected.id; }) || null;
  };
  model.remove = function (confirmDelete) {
    if (model.busy || model.loading || model.outcomeUnknown || !model.entry() || model.selected.kind === "legacy") return Promise.resolve();
    var selected = copy(model.selected), saved = model.savedEntry(), identity = copy(saved || model.entry());
    if (typeof confirmDelete !== "function" || !confirmDelete({ saved: !!saved, kind: selected.kind, entry: identity })) return Promise.resolve();
    invalidate(); clearSecret(); var current = generation;
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
    if (model.busy || model.outcomeUnknown) return Promise.resolve();
    var version = ++readGeneration, current = generation;
    return request("", "GET").then(function (snapshot) {
      if (!alive || version !== readGeneration || current !== generation) return;
      if (!model.dirty()) { model.snapshot = snapshot; model.draft = copy(snapshot.config); }
      else { model.snapshot.statuses = snapshot.statuses; model.snapshot.persistentCredentialsSupported = snapshot.persistentCredentialsSupported; }
      emit();
    }).catch(function (error) { if (alive && version === readGeneration && current === generation) { model.error = integrationErrorText(error); emit(); } });
  };
  model.cancel = function () { if (!model.busy || model.busy === "test" || model.busy === "discover") { invalidate(); clearSecret(); model.busy = ""; emit(); } };
  model.destroy = function () { alive = false; invalidate(); clearSecret(); model.draft = null; model.snapshot = null; };
  return model;
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
  var model = createIntegrationsModel(api.integrations, render), builtFor = null, controls = {}, status, error, notice, list, editor, tools, footer, connectionState;
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
  ["mail", "mcp"].forEach(function (kind) { actions.appendChild(integrationButton(t("integrations.add." + kind), function () {
    if (model.secret && !window.confirm(t("config.confirmDiscardClose"))) return; model.add(kind);
  })); }); scroller.appendChild(actions);
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
  var save = integrationButton(t("config.saveChanges"), function () { model.save().then(function () { (model.error ? error : notice).focus(); }); });
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
      else model.change(name, name === "allowedFolders" ? value.split(/\r?\n/).map(function (v) { return v.trim(); }).filter(Boolean)
        : name === "disclosureVersion" ? value ? 1 : 0 : value);
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
      field("allowedFolders", "integrations.folders"); field("bodyAccess", "integrations.bodyAccess", "checkbox");
      editor.appendChild(integrationElement("p", t("integrations.mailDisclosure"), "integrations-disclosure"));
      var recipient = integrationElement("p", t("integrations.modelTarget") + ": " + (model.snapshot.modelTarget || t("integrations.unknownTarget")), "integrations-disclosure"); editor.appendChild(recipient);
      field("disclosureVersion", "integrations.retentionConsent", "checkbox");
    } else {
      field("name", "integrations.name"); field("endpoint", "integrations.endpoint", "url");
      field("authType", "integrations.authType", "text", [["anonymous", t("integrations.anonymous")], ["bearer", "Bearer"]]);
      editor.appendChild(integrationElement("p", t("integrations.remoteDisclosure"), "integrations-disclosure"));
    }
    field("enabled", "integrations.enabled", "checkbox");
    field("secret", kind === "mail" ? "integrations.authorizationCode" : "integrations.bearer", "password");
    field("persist", "integrations.persist", "checkbox");
    editor.appendChild(integrationElement("p", t("integrations.sessionOnly"), "integrations-hint"));
    var row = integrationElement("div", null, "integrations-actions");
    ["credential", "test", "disconnect"].concat(kind === "mcp" ? ["discover"] : []).forEach(function (action) {
      var button = integrationButton(t("integrations.action." + action), function () {
        if (action === "disconnect" && !window.confirm(t("integrations.confirmDisconnect"))) return;
        model.action(action);
      }); controls[action] = button; row.appendChild(button);
    });
    var remove = integrationButton(t("integrations.action.delete"), function () {
      model.remove(confirmRemoval).then(function () { (model.error ? error : notice).focus(); });
    }); controls.delete = remove; remove.classList.add("integration-delete"); row.appendChild(remove);
    if (kind === "mcp") {
      var viewTools = integrationButton(t("integrations.catalog.open"), function () { openCatalog(viewTools); });
      controls.catalog = viewTools; row.appendChild(viewTools);
    }
    var cancel = integrationButton(t("action.cancel"), function () { model.cancel(); }); controls.cancel = cancel; row.appendChild(cancel); editor.appendChild(row);
    editor.appendChild(integrationElement("p", t("integrations.saveFirst"), "integrations-hint"));
  }
  function render(data) {
    status.textContent = data.loading ? t("common.loading") : data.snapshot ? t("integrations.revision", { revision: data.snapshot.config.revision }) : "";
    error.textContent = data.outcomeUnknown ? t("integrations.deleteUnknown") : data.error; notice.textContent = data.snapshot && data.snapshot.credentialCleanupPending ? t("integrations.notice.cleanupPending") : data.notice ? t("integrations.notice." + data.notice) : "";
    discard.disabled = !!data.busy || data.loading; save.disabled = !!data.busy || data.outcomeUnknown || !data.metadataDirty();
    actions.querySelectorAll("button").forEach(function (el) { el.disabled = !data.snapshot || data.loading || data.outcomeUnknown || !!data.busy; });
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
      connectionState.textContent = t("integrations.state." + liveState) + " · "
        + t(live.credentialConfigured ? "integrations.credentialConfigured" : "integrations.credentialMissing");
    }
    if (entry && data.selected.kind === "mail") {
      var providerHelp = editor.querySelector("#integration-provider-help");
      providerHelp.href = entry.provider === "qq" ? "https://help.mail.qq.com/detail/106/985"
        : "https://help.mail.126.com/faqDetail.do?code=d7a5dc8471cd0c0e8b4b8f4f8e49998b374173cfe9171305fa1ce630d7f67ac2a5feb28b66796d3b";
    }
    if (entry) Object.keys(controls).forEach(function (name) {
      var input = controls[name];
      if (input.tagName === "BUTTON") {
        if (name === "catalog") { input.disabled = !!data.busy; return; }
        if (name === "delete") { input.disabled = !!data.busy || data.loading || data.outcomeUnknown; return; }
        input.disabled = name === "cancel" ? data.busy !== "test" && data.busy !== "discover"
        : !!data.busy || data.outcomeUnknown || data.metadataDirty() || name === "credential" && !data.secret; return; }
      input.disabled = !!data.busy || data.outcomeUnknown || name === "persist" && !data.snapshot.persistentCredentialsSupported
        || (name === "secret" || name === "persist") && data.selected.kind === "mcp" && entry.authType === "anonymous";
      var value = name === "secret" ? data.secret : name === "persist" ? data.persist : entry[name];
      if (input.type === "checkbox") input.checked = name === "disclosureVersion" ? value === 1 : !!value;
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
    catalog.destroy(); model.destroy(); if (approvals) approvals.destroy(true); Object.keys(controls).forEach(function (key) { if (controls[key].type === "password") controls[key].value = ""; }); root.replaceChildren();
  } };
}
