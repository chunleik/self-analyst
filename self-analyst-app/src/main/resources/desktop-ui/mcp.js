"use strict";

function mcpLoopbackUrl(value) {
  try {
    var url = new URL(value);
    if (url.protocol !== "http:" || url.hostname !== "127.0.0.1" || url.username || url.password
        || url.search || url.hash || !["/", "/mcp", "/mcp/"].includes(url.pathname)) return "";
    return url.origin + "/mcp/all";
  } catch (ignored) { return ""; }
}

function mcpStatusValid(value) {
  function flags(item, keys) { return keys.every(function (key) { return typeof item[key] === "boolean"; }); }
  return !!value && Number.isSafeInteger(value.revision) && value.revision >= 0
    && flags(value, ["tokenConfigured", "persistentSupported"]) && !!mcpLoopbackUrl(value.baseUrl)
    && Array.isArray(value.accounts) && value.accounts.every(function (item) {
      return item && typeof item.id === "string" && item.id && typeof item.label === "string"
        && flags(item, ["expose", "allowDraft", "allowSend", "smtpConfigured"])
        && (item.enabled === undefined || typeof item.enabled === "boolean");
    }) && new Set(value.accounts.map(function (item) { return item.id; })).size === value.accounts.length
    && Array.isArray(value.pending);
}

function mountMcpManagement(root) {
  var el = integrationElement, button = integrationButton, request = api.mcpManagement;
  var active = true, busy = false, controller = null, clipboardToken = "", clipboardRevision = -1;
  var snapshot = null, overrides = new Map(), accountControls = new Map(), accountSignature = "", pendingSignature = "";
  var stale = false, loading = false, unavailable = false;
  var page = el("div", null, "mcp-management");
  var connectionsRoot = el("section"), externalRoot = el("section"), pendingRoot = el("section");
  page.appendChild(externalRoot); page.appendChild(pendingRoot); page.appendChild(connectionsRoot);
  root.replaceChildren(page);
  var connections = mountIntegrations(connectionsRoot);

  externalRoot.appendChild(el("h2", t("mcp.externalTitle")));
  externalRoot.appendChild(el("p", t("mcp.externalDisclosure")));
  externalRoot.appendChild(el("p", t("mcp.portHint"), "integrations-hint"));
  var address = el("code"), endpoint = el("p");
  endpoint.appendChild(el("span", t("mcp.endpoint") + " ")); endpoint.appendChild(address); externalRoot.appendChild(endpoint);
  var stateText = el("p"); stateText.setAttribute("role", "status"); externalRoot.appendChild(stateText);
  var storage = el("p", t("mcp.noEncryptedStore"), "integrations-hint"); storage.hidden = true; externalRoot.appendChild(storage);
  var messages = el("p"); messages.setAttribute("role", "status"); externalRoot.appendChild(messages);
  var error = el("p", null, "integrations-error"); error.setAttribute("role", "alert"); externalRoot.appendChild(error);
  var actions = el("div", null, "integrations-actions");
  var refresh = button(t("mcp.refresh"), function () {
    if (busy || loading || (policyDirty() || passwordDirty()) && !window.confirm(t("config.confirmDiscardClose"))) return;
    if (controller) { controller.abort(); controller = null; }
    overrides.clear(); clearPasswords(); stale = false; load(true);
  });
  var rotate = button(t("mcp.createToken"), function () {
    if (!canChange() || !snapshot.persistentSupported || policyDirty() || !window.confirm(t("mcp.confirmRotate"))) return;
    clipboardToken = ""; clipboardRevision = -1;
    mutate("/token", "POST", { revision: snapshot.revision }, function (result) {
      if (!result || typeof result.token !== "string" || !/^[A-Za-z0-9_-]+$/.test(result.token)
          || !mcpStatusValid(result.status)) throw {};
      applyStatus(result.status, true);
      clipboardToken = result.token; clipboardRevision = result.status.revision;
      return "mcp.tokenReady";
    });
  });
  var copy = button(t("mcp.copyConfig"), function () {
    if (!canChange() || !clipboardToken || clipboardRevision !== snapshot.revision) return;
    var url = mcpLoopbackUrl(snapshot.baseUrl);
    if (!url) return;
    var config = JSON.stringify({ mcpServers: { "self-analyst": { url: url, headers: {
      Authorization: "Bearer " + clipboardToken
    } } } }, null, 2);
    Promise.resolve().then(function () { return navigator.clipboard.writeText(config); }).then(function () {
      if (active) messages.textContent = t("mcp.copied");
    }).catch(function () { if (active) error.textContent = t("mcp.clipboardError"); });
  });
  var save = button(t("config.saveChanges"), function () {
    if (!canChange() || !policyDirty()) return;
    var accounts = snapshot.accounts.map(function (account) {
      var draft = overrides.get(account.id) || account;
      return { id: account.id, expose: account.enabled !== false && draft.expose,
        allowDraft: account.enabled !== false && draft.allowDraft,
        allowSend: account.enabled !== false && account.smtpConfigured && draft.allowSend };
    });
    if (accounts.some(function (account, i) {
      var saved = snapshot.accounts[i];
      return account.expose && !saved.expose || account.allowDraft && !saved.allowDraft || account.allowSend && !saved.allowSend;
    }) && !window.confirm(t("mcp.confirmPermissions"))) return;
    mutate("", "PUT", { revision: snapshot.revision, accounts: accounts }, function (result) {
      applyStatus(result, true); return "mcp.saved";
    });
  });
  [refresh, rotate, copy, save].forEach(function (control) { actions.appendChild(control); }); externalRoot.appendChild(actions);
  var accountRoot = el("div"); externalRoot.appendChild(accountRoot);
  pendingRoot.appendChild(el("h3", t("mcp.pendingTitle")));
  pendingRoot.appendChild(el("p", t("mcp.pendingHint"), "integrations-hint"));
  var pendingList = el("div"); pendingRoot.appendChild(pendingList);
  pendingList.appendChild(el("p", t("common.loading")));

  function policyDirty() { return overrides.size > 0; }
  function passwordDirty() {
    return Array.from(accountControls.values()).some(function (row) { return !!row.password.value; });
  }
  function clearPasswords() { accountControls.forEach(function (row) { row.password.value = ""; }); }
  function canChange() { return active && !!snapshot && !stale && !unavailable && !busy && !loading; }
  function acceptAccount(account, field, value) {
    if (!canChange() || account.enabled === false || field === "allowSend" && !account.smtpConfigured) return;
    var next = Object.assign({}, overrides.get(account.id) || account); next[field] = value;
    if (["expose", "allowDraft", "allowSend"].every(function (key) { return account[key] === next[key]; })) overrides.delete(account.id);
    else overrides.set(account.id, next);
    render();
  }
  function checkbox(parent, key, onChange) {
    var label = el("label", t("mcp." + key), "integrations-field"), input = el("input");
    input.type = "checkbox"; input.addEventListener("change", function () { onChange(input.checked); });
    label.appendChild(input); parent.appendChild(label); return input;
  }
  function buildAccounts(accounts) {
    clearPasswords(); accountControls.clear(); accountRoot.replaceChildren();
    if (!accounts.length) accountRoot.appendChild(el("p", t("mcp.emptyAccounts")));
    accounts.forEach(function (account) {
      var row = {}, card = el("fieldset", null, "mcp-account");
      card.appendChild(el("legend", account.label || account.id));
      row.mailStatus = el("p"); card.appendChild(row.mailStatus);
      row.endpoint = el("code"); card.appendChild(row.endpoint);
      ["expose", "allowDraft", "allowSend"].forEach(function (key) {
        row[key] = checkbox(card, key, function (value) {
          var current = snapshot.accounts.find(function (item) { return item.id === account.id; });
          if (current) acceptAccount(current, key, value);
        });
      });
      var label = el("label", t("mcp.smtpSecret"), "integrations-field"), password = el("input");
      password.type = "password"; password.autocomplete = "new-password"; password.spellcheck = false; password.maxLength = 2048;
      row.password = password; label.appendChild(password); card.appendChild(label);
      row.store = button(t("mcp.storeSmtp"), function () {
        var current = snapshot && snapshot.accounts.find(function (item) { return item.id === account.id; });
        if (!canChange() || !current || current.enabled === false || !snapshot.persistentSupported || policyDirty() || !password.value) return;
        var body = { revision: snapshot.revision, secret: password.value };
        password.value = "";
        mutate("/smtp/" + encodeURIComponent(account.id), "PUT", body, function (result) {
          applyStatus(result, true); return "mcp.stored";
        });
        body.secret = undefined;
      });
      password.addEventListener("input", render);
      card.appendChild(el("p", t("mcp.smtpCredentialHint"), "integrations-hint"));
      card.appendChild(row.store); accountRoot.appendChild(card); accountControls.set(account.id, row);
    });
  }
  function validPending(item) {
    return item && typeof item.id === "string" && item.id
      && ["create_draft", "send_email"].includes(item.operation)
      && typeof item.account === "string"
      && (typeof item.to === "string" || Array.isArray(item.to) && item.to.every(function (to) { return typeof to === "string"; }))
      && typeof item.subject === "string" && typeof item.body === "string";
  }
  function renderPending() {
    var items = !stale && !unavailable && snapshot ? snapshot.pending.filter(validPending) : [];
    var signature = JSON.stringify([!!snapshot, stale, unavailable, busy, items]);
    if (signature === pendingSignature) return; pendingSignature = signature;
    pendingList.replaceChildren();
    if (!snapshot || stale || unavailable) {
      pendingList.appendChild(el("p", t(stale || unavailable ? "mcp.unavailable" : "common.loading"))); return;
    }
    if (!items.length) pendingList.appendChild(el("p", t("mcp.pendingEmpty")));
    items.forEach(function (item) {
      var card = el("article", null, "mcp-review"), operation = t(item.operation === "send_email" ? "mcp.send" : "mcp.draft");
      card.appendChild(el("h4", operation));
      var to = Array.isArray(item.to) ? item.to.join(", ") : item.to;
      card.appendChild(el("p", t("mcp.account") + " " + item.account));
      card.appendChild(el("p", t("mcp.recipient") + " " + to));
      card.appendChild(el("p", t("mcp.subject") + " " + item.subject));
      var body = el("pre", item.body); body.tabIndex = 0; body.setAttribute("aria-label", t("mcp.body"));
      card.appendChild(body);
      [true, false].forEach(function (allowed) {
        var action = button(t(allowed ? "mcp.approve" : "mcp.deny"), function () {
          if (!canChange() || !snapshot.pending.some(function (live) { return JSON.stringify(live) === JSON.stringify(item); })) return;
          if (allowed && !window.confirm(t("mcp.confirmWrite", { operation: operation, recipient: to }))) return;
          mutate("/approval/" + encodeURIComponent(item.id), "POST", { allowed: allowed }, function () {
            snapshot.pending = snapshot.pending.filter(function (live) { return live.id !== item.id; });
            return allowed ? "mcp.approved" : "mcp.denied";
          });
        });
        action.disabled = busy;
        card.appendChild(action);
      });
      pendingList.appendChild(card);
    });
  }
  function render() {
    var endpointUrl = snapshot && mcpLoopbackUrl(snapshot.baseUrl) || "";
    address.textContent = endpointUrl;
    stateText.textContent = !snapshot ? t(loading ? "common.loading" : "mcp.unavailable") : t(snapshot.tokenConfigured ? "mcp.tokenConfigured" : "mcp.tokenAbsent");
    storage.hidden = !snapshot || snapshot.persistentSupported;
    refresh.disabled = busy || loading; rotate.disabled = !canChange() || !snapshot.persistentSupported || policyDirty();
    rotate.textContent = t(snapshot && snapshot.tokenConfigured ? "mcp.rotateToken" : "mcp.createToken");
    copy.disabled = !canChange() || !clipboardToken || clipboardRevision !== snapshot.revision;
    save.disabled = !canChange() || !policyDirty();
    if (snapshot) {
      var signature = JSON.stringify(snapshot.accounts.map(function (account) { return [account.id, account.label]; }));
      if (signature !== accountSignature) { accountSignature = signature; buildAccounts(snapshot.accounts); }
      snapshot.accounts.forEach(function (account) {
        var row = accountControls.get(account.id), draft = overrides.get(account.id) || account;
        row.endpoint.textContent = account.enabled !== false && account.expose && endpointUrl
          ? endpointUrl.slice(0, -3) + encodeURIComponent(account.id) : "";
        row.mailStatus.textContent = t(account.enabled === false ? "mcp.accountInactive" : !snapshot.persistentSupported ? "mcp.noEncryptedStore"
          : account.smtpConfigured ? "mcp.smtpConfigured" : "mcp.smtpAbsent");
        ["expose", "allowDraft", "allowSend"].forEach(function (key) {
          var available = account.enabled !== false && (key !== "allowSend" || account.smtpConfigured);
          row[key].checked = available && draft[key]; row[key].disabled = !available || !canChange();
        });
        row.password.disabled = account.enabled === false || !canChange() || !snapshot.persistentSupported || policyDirty();
        row.store.disabled = row.password.disabled || !row.password.value;
      });
    }
    renderPending();
  }
  function applyStatus(value, committed) {
    if (!mcpStatusValid(value)) throw {};
    unavailable = false;
    if (snapshot && value.revision !== snapshot.revision) { clipboardToken = ""; clipboardRevision = -1; }
    if (!committed && snapshot && value.revision !== snapshot.revision && (policyDirty() || passwordDirty())) {
      stale = true; snapshot.pending = value.pending; error.textContent = t("mcp.conflict"); return;
    }
    stale = false;
    snapshot = value;
    if (committed) overrides.clear();
  }
  function failure(reason) {
    if (reason && reason.status === 409) stale = true;
    error.textContent = t(reason && reason.status === 403 ? "mcp.requiresDesktop" :
      stale ? "mcp.conflict" : "mcp.unavailable");
  }
  function load(manual) {
    if (!active || busy || controller || document.hidden && !manual) return;
    controller = new AbortController();
    var current = controller;
    if (manual) { loading = true; error.textContent = ""; messages.textContent = ""; render(); }
    Promise.resolve().then(function () { return request("", "GET", undefined, current.signal); }).then(function (result) {
      if (active && current === controller) applyStatus(result, false);
    }).catch(function (reason) {
      if (active && current === controller) {
        if (snapshot) snapshot.pending = [];
        unavailable = true; failure(reason);
      }
    }).finally(function () {
      if (active && current === controller) { controller = null; loading = false; render(); }
    });
  }
  function mutate(path, method, body, accept) {
    if (controller) { controller.abort(); controller = null; }
    busy = true; error.textContent = ""; messages.textContent = ""; render();
    var work;
    try { work = request(path, method, body); }
    catch (reason) { work = Promise.reject(reason); }
    Promise.resolve(work).then(function (result) {
      if (active) messages.textContent = t(accept(result));
    }).catch(function (reason) { if (active) failure(reason); }).finally(function () {
      if (active) { busy = false; render(); load(false); }
    });
  }
  load(true);
  var timer = setInterval(function () { load(false); }, 5000);
  return {
    dirty: function () { return connections.dirty() || policyDirty() || passwordDirty(); },
    saving: function () { return connections.saving() || busy; },
    destroy: function () {
      active = false; clearInterval(timer); if (controller) controller.abort(); controller = null;
      clipboardToken = ""; clearPasswords(); overrides.clear(); accountControls.clear();
      connections.destroy(); root.replaceChildren();
    }
  };
}
