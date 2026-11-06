"use strict";

// The frontend and native opener both enforce the same narrow authorization target.
function validChatgptAuthorizationUrl(value) {
  if (typeof value !== "string" || value.length > 8192 || /[\x00-\x20\x7f\\]/.test(value)) return false;
  try {
    var url = new URL(value), params = url.searchParams;
    if (url.origin !== "https://auth.openai.com" || url.pathname !== "/api/accounts/authorize"
        || url.username || url.password || url.hash) return false;
    var allowed = ["client_id", "response_type", "redirect_uri", "scope", "state", "nonce", "code_challenge",
      "code_challenge_method", "login_hint", "prompt", "screen_hint", "resource", "ext_agent_host_id", "agent_name_hint"];
    if (Array.from(params.keys()).some(function (key) { return allowed.indexOf(key) < 0 || params.getAll(key).length !== 1; })) return false;
    var redirect = params.get("redirect_uri") || "", port = /^http:\/\/127\.0\.0\.1:(\d+)\/auth\/callback$/.exec(redirect);
    return params.get("resource") === "https://api.openai.com/v1"
      && (params.get("client_id") === "dynamic_agent_client" ? params.get("agent_name_hint") === "SelfAnalyst" : !params.has("agent_name_hint"))
      && /^urn:uuid:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(params.get("ext_agent_host_id") || "")
      && !!port && Number(port[1]) > 0 && Number(port[1]) <= 65535
      && params.get("response_type") === "code" && params.get("code_challenge_method") === "S256"
      && /^[A-Za-z0-9_-]{43}$/.test(params.get("code_challenge") || "")
      && /^[A-Za-z0-9_-]{16,256}$/.test(params.get("state") || "")
      && /^[A-Za-z0-9_-]{1,160}$/.test(params.get("client_id") || "");
  } catch (ignored) { return false; }
}

function chatgptErrorText(code) {
  var known = ["unsupported", "network", "timeout", "cancelled", "expired", "authentication", "permission",
    "storage", "identity", "protocol", "registration", "callback", "scope", "unavailable"];
  var suffix = String(code || "").replace(/^.*chatgpt[_.]/, "");
  var aliases = { plan_permission_required: "permission", sign_in_required: "authentication", storage_unavailable: "storage",
    invalid_identity: "identity", identity_unavailable: "network", identity_mismatch: "identity", client_mismatch: "identity", authorization_declined: "cancelled",
    login_expired: "expired", refresh_failed: "network", invalid_callback: "callback", login_start_failed: "unavailable",
    unknown_account: "authentication", network_error: "network", request_interrupted: "network", code_exchange_failed: "authentication",
    invalid_response: "protocol", login_cancelled: "cancelled", discovery_failed: "unsupported", service_closed: "unavailable",
    refresh_not_yet_allowed: "network", request_timeout: "timeout", response_too_large: "protocol" };
  suffix = aliases[suffix] || suffix;
  return t("llm.chatgpt.error." + (known.indexOf(suffix) >= 0 ? suffix : "unavailable"));
}


function chatgptProbeText(result) {
  if (result.message) return result.message; // Only locally sanitized request errors set this field.
  var standard = ["success", "rate_limit", "model_not_found", "unsupported_discovery", "parameters", "protocol", "response_limit", "timeout", "network", "http"];
  return standard.indexOf(result.code) >= 0 ? t("llm.probe." + result.code) : chatgptErrorText(result.code);
}

// 草稿状态独立于配置窗口、TOML 和任何浏览器持久存储。
function createLlmSettingsModel(request, changed) {
  var model = { snapshot: null, presets: [], updates: {}, reset: [], credential: { action: "keep" },
    loading: false, saving: false, error: "", notice: "", candidates: [], probe: {}, busy: {},
    chatgpt: { status: "signed_out", accounts: [] }, authBusy: false, authError: "", authNotice: "", login: null };
  var alive = true, generation = 0, controllers = {}, timer = null, authGeneration = 0, authTimer = null, authRead = 0;
  var defaults = { provider: "api-key", chatgptAccount: "", chatgptModel: "" };
  model.field = function (field) { return model.snapshot && model.snapshot.fields[field]
    || { effectiveValue: defaults[field] == null ? "" : defaults[field], source: "default" }; };
  function emit() { if (alive) changed(model); }
  function invalidate() {
    generation++;
    Object.keys(controllers).forEach(function (key) { controllers[key].abort(); });
    controllers = {}; model.probe = {}; model.busy = {}; model.candidates = [];
  }
  model.dirty = function () { return Object.keys(model.updates).length > 0 || model.reset.length > 0 || model.credential.action !== "keep"; };
  model.value = function (field) {
    return Object.prototype.hasOwnProperty.call(model.updates, field) ? model.updates[field]
      : model.snapshot ? model.field(field).effectiveValue : "";
  };
  model.change = function (field, value) {
    if (model.saving || model.authBusy === "logout" || !model.snapshot) return;
    model.reset = model.reset.filter(function (key) { return key !== field; });
    if (field === "provider" || field === "chatgptAccount") model.cancelLogin();
    if (String(value) === String(model.field(field).effectiveValue)) delete model.updates[field];
    else model.updates[field] = value;
    model.error = ""; model.notice = ""; invalidate(); emit();
  };
  model.inherit = function (field) {
    if (model.saving || model.authBusy === "logout" || !model.snapshot) return;
    if (field === "provider" || field === "chatgptAccount") model.cancelLogin();
    delete model.updates[field];
    if (model.reset.indexOf(field) < 0) model.reset.push(field);
    model.notice = ""; invalidate(); emit();
  };
  model.setCredential = function (action, value) {
    if (model.saving || model.authBusy === "logout" || !model.snapshot) return;
    model.credential = action === "replace" && value.trim() ? { action: action, value: value.trim() }
      : { action: action === "replace" ? "keep" : action };
    model.notice = ""; invalidate(); emit();
  };
  model.load = function () {
    if (model.authBusy === "logout") return Promise.resolve();
    model.cancelLogin(); authGeneration++; invalidate(); clearTimeout(timer); var version = generation;
    model.loading = true; model.error = ""; model.snapshot = null; emit();
    return Promise.all([request(""), request("/presets").catch(function () { return { presets: [] }; })])
      .then(function (values) {
        if (!alive || version !== generation) return;
        model.snapshot = values[0]; model.presets = values[1].presets;
        model.updates = {}; model.reset = []; model.credential = { action: "keep" }; model.notice = "";
        scheduleRuntime(); model.refreshAuth();
      }).catch(function (error) { if (alive && version === generation) model.error = error.message; })
      .finally(function () { if (alive && version === generation) { model.loading = false; emit(); } });
  };
  model.save = function () {
    if (model.saving || model.authBusy || model.login || !model.snapshot || !model.dirty()) return Promise.resolve();
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
    if (!model.snapshot || model.saving || model.busy[kind] || model.reset.indexOf("provider") >= 0) return Promise.resolve();
    var chatgpt = model.value("provider") === "chatgpt";
    if (chatgpt && (model.authBusy || model.login || !model.selectedAccount() || model.reset.indexOf("chatgptAccount") >= 0
        || model.reset.indexOf("chatgptModel") >= 0)) return Promise.resolve();
    var controller = new AbortController(), version = generation;
    controllers[kind] = controller; model.busy[kind] = true; delete model.probe[kind]; emit();
    var draft = { baseUrl: model.value("baseUrl"), credential: model.credential };
    if (kind === "test") draft.model = model.value("model");
    draft.reset = model.reset.slice();
    model.reset.forEach(function (key) { delete draft[key]; });
    var path = kind === "test" ? "/test" : "/discover-models";
    if (chatgpt) {
      draft = { accountId: model.value("chatgptAccount") };
      if (kind === "test") draft.model = model.value("chatgptModel");
      path = kind === "test" ? "/chatgpt/test" : "/chatgpt/models";
    }
    return request(path, "POST", draft, controller.signal)
      .then(function (result) {
        if (!alive || version !== generation || controller.signal.aborted) return;
        model.probe[kind] = result;
        if (kind === "discover") model.candidates = result.ok ? (result.models || []).map(function (item) {
          return typeof item === "string" ? item : item && item.slug;
        }).filter(function (id) { return typeof id === "string" && id.length > 0; }) : [];
      }).catch(function (error) {
        if (alive && version === generation && !controller.signal.aborted) model.probe[kind] = {
          ok: false, message: chatgpt ? chatgptErrorText(error.code) : error.message };
      }).finally(function () {
        if (alive && version === generation && !controller.signal.aborted) {
          model.busy[kind] = false; emit();
          if (chatgpt && kind === "test") return model.refreshAuth();
        }
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
  model.selectedAccount = function () {
    return (model.chatgpt.accounts || []).find(function (account) {
      return account.id === model.value("chatgptAccount") && account.connected;
    });
  };
  function localAuthState() {
    return (model.chatgpt.accounts || []).some(function (account) { return account.connected; }) ? "connected" : "signed_out";
  }
  function cancelAttempt(attemptId) {
    if (!attemptId) return Promise.resolve();
    return request("/chatgpt/cancel", "POST", { attemptId: attemptId });
  }
  function scheduleAuth(version) {
    clearTimeout(authTimer);
    if (alive && version === authGeneration && model.login) authTimer = setTimeout(function () {
      model.refreshAuth(version);
    }, 1500);
  }
  model.refreshAuth = function (version) {
    version = version == null ? authGeneration : version;
    if (!alive || model.authBusy) return Promise.resolve();
    var read = ++authRead;
    return request("/chatgpt/status").then(function (status) {
      if (!alive || version !== authGeneration || read !== authRead) return;
      var pending = model.login;
      model.chatgpt = status;
      model.authError = status.errorCode ? chatgptErrorText(status.errorCode)
        : pending && status.status === "pending" && model.authError === t("llm.chatgpt.browserFailed") ? model.authError : "";
      if (status.status === "pending" && status.loginAttemptId) {
        if (!pending || pending.attemptId !== status.loginAttemptId) model.login = { attemptId: status.loginAttemptId };
        scheduleAuth(version);
      } else {
        model.login = null; clearTimeout(authTimer);
        if (pending && status.activeAccountId && (status.status === "connected" || status.errorCode === "chatgpt_plan_permission_required")) {
          // Authentication selects a draft account only; model and API drafts are preserved.
          if (String(model.field("chatgptAccount").effectiveValue) === status.activeAccountId) delete model.updates.chatgptAccount;
          else model.updates.chatgptAccount = status.activeAccountId;
          model.reset = model.reset.filter(function (key) { return key !== "chatgptAccount"; });
          model.authNotice = status.status === "connected" ? "connected" : "authorizedLimited"; invalidate();
        }
      }
      emit();
    }).catch(function (error) {
      if (alive && version === authGeneration && read === authRead) {
        clearTimeout(authTimer); model.authError = chatgptErrorText(error.code); emit();
      }
    });
  };
  model.startLogin = function (accountId) {
    if (!alive || !model.snapshot || model.saving || model.authBusy || model.login) return Promise.resolve(null);
    var version = ++authGeneration;
    model.authBusy = "login"; model.authError = ""; model.authNotice = ""; invalidate(); emit();
    return request("/chatgpt/login", "POST", accountId ? { accountId: accountId } : {}).then(function (result) {
      if (!alive || version !== authGeneration) {
        return cancelAttempt(result.attemptId).catch(function () {}).then(function () { return null; });
      }
      if (!result.attemptId || !validChatgptAuthorizationUrl(result.authorizationUrl)) {
        cancelAttempt(result.attemptId).catch(function () {});
        throw new Error("Invalid authorization response");
      }
      model.login = result; model.chatgpt.status = "pending"; scheduleAuth(version);
      return result.authorizationUrl;
    }).catch(function (error) {
      if (alive && version === authGeneration) model.authError = chatgptErrorText(error.code);
      return null;
    }).finally(function () {
      if (alive && version === authGeneration) { model.authBusy = false; emit(); }
    });
  };
  model.cancelLogin = function () {
    if (alive && model.authBusy === "logout") return Promise.resolve();
    if (!model.login && model.authBusy !== "login") return Promise.resolve();
    var attemptId = model.login && model.login.attemptId, wasPending = !!model.login || model.authBusy;
    var version = ++authGeneration;
    clearTimeout(authTimer); model.login = null; model.authBusy = false; model.authError = "";
    model.chatgpt.status = localAuthState();
    if (wasPending) model.authNotice = "cancelled";
    emit();
    return cancelAttempt(attemptId).catch(function (error) {
      if (alive && version === authGeneration) { model.authError = chatgptErrorText(error.code); emit(); }
    });
  };
  model.logout = function () {
    var accountId = model.value("chatgptAccount");
    if (!alive || !accountId || model.saving || model.authBusy || model.login) return Promise.resolve();
    var version = ++authGeneration, logoutError = "";
    model.authBusy = "logout"; model.authError = ""; model.authNotice = ""; invalidate(); emit();
    return request("/chatgpt/logout", "POST", { accountId: accountId }).then(function (result) {
      if (!alive || version !== authGeneration) return;
      model.authNotice = result.remoteRevocationConfirmed ? "disconnected" : "disconnectedLocal";
    }).catch(function (error) {
      if (alive && version === authGeneration) model.authError = logoutError = chatgptErrorText(error.code);
    }).finally(function () {
      if (alive && version === authGeneration) {
        model.authBusy = false; emit();
        return model.refreshAuth(version).then(function () {
          if (alive && version === authGeneration && logoutError) { model.authError = logoutError; emit(); }
        });
      }
    });
  };
  model.retryBrowser = function () {
    if (alive && model.authError === t("llm.chatgpt.browserFailed")) { model.authError = ""; emit(); }
  };
  model.browserFailed = function () { if (alive && model.login) { model.authError = t("llm.chatgpt.browserFailed"); emit(); } };
  model.destroy = function () {
    alive = false; model.cancelLogin(); invalidate(); clearTimeout(timer); model.credential = { action: "keep" };
  };
  return model;
}

function mountLlmSettings(root) {
  var built = false, lastSnapshot = null, lastLoading = null, openingAttempt = null;
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
    root.querySelectorAll("input,select,button").forEach(function (el) { el.disabled = data.saving || data.authBusy === "logout"; });
    var chatgpt = data.value("provider") === "chatgpt";
    root.querySelectorAll("[data-llm-provider-section]").forEach(function (el) {
      el.hidden = el.dataset.llmProviderSection !== data.value("provider");
    });
    ["provider", "baseUrl", "model", "temperature", "chatgptModel"].forEach(function (key) {
      var input = root.querySelector('[data-llm-field="' + key + '"]');
      if (document.activeElement !== input) input.value = data.value(key);
      input.disabled = data.saving || data.authBusy === "logout" || data.reset.indexOf(key) >= 0;
      var source = root.querySelector('[data-llm-source="' + key + '"]');
      source.textContent = data.reset.indexOf(key) >= 0 ? t("llm.inheritPending") : t("config.source." + data.field(key).source);
    });
    if (data.credential.action !== "replace") {
      root.querySelector('[data-llm-key]').value = "";
      root.querySelector('[data-llm-key]').type = "password";
      root.querySelector('[data-llm-action="reveal-key"]').textContent = t("llm.showKey");
      root.querySelector('[data-llm-action="reveal-key"]').setAttribute("aria-pressed", "false");
    }
    root.querySelector("#llm-key-state").textContent = t("llm.credential." + data.credential.action) + " · "
      + t(data.snapshot.credential.configured ? "llm.configured" : "llm.notConfigured") + " · " + t("config.source." + data.snapshot.credential.source);
    root.querySelector('[data-llm-action="save"]').disabled = data.saving || data.authBusy || !!data.login || !data.dirty();
    root.querySelector('[data-llm-action="discard"]').disabled = data.saving || data.authBusy === "logout" || !data.dirty();
    ["test", "discover"].forEach(function (kind) {
      root.querySelectorAll('[data-llm-action="' + kind + '"]').forEach(function (el) {
        el.disabled = data.saving || !!data.busy[kind] || data.reset.indexOf("provider") >= 0 || (chatgpt && (!data.selectedAccount()
          || data.authBusy || !!data.login || data.reset.indexOf("chatgptAccount") >= 0 || data.reset.indexOf("chatgptModel") >= 0));
      });
      var result = data.probe[kind];
      root.querySelector('#llm-' + kind + '-result').textContent = data.busy[kind] ? t("config.testing") : result
        ? (chatgpt ? chatgptProbeText(result) : result.message || t("llm.probe." + result.code)) + (result.latencyMs != null ? ' (' + result.latencyMs + ' ms)' : '')
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
    renderAuth(data);
    state.configSaving = data.saving;
  }
  function renderAuth(data) {
    var account = root.querySelector('[data-llm-field="chatgptAccount"]');
    var current = data.value("chatgptAccount"), accounts = data.chatgpt.accounts || [];
    var options = '<option value="">' + escHtml(t("llm.chatgpt.chooseAccount")) + '</option>';
    if (current && !accounts.some(function (item) { return item.id === current; })) {
      options += '<option value="' + escHtml(current) + '">' + escHtml(t("llm.chatgpt.missingAccount")) + '</option>';
    }
    options += accounts.map(function (item) {
      var label = item.label || item.email || t("llm.chatgpt.account");
      return '<option value="' + escHtml(item.id) + '">' + escHtml(label)
        + (item.connected ? '' : ' · ' + escHtml(t("llm.chatgpt.state.signed_out"))) + '</option>';
    }).join("");
    if (account.innerHTML !== options) account.innerHTML = options;
    account.value = current; account.disabled = data.saving || data.authBusy || data.reset.indexOf("chatgptAccount") >= 0;
    root.querySelector('[data-llm-source="chatgptAccount"]').textContent = data.reset.indexOf("chatgptAccount") >= 0
      ? t("llm.inheritPending") : t("config.source." + data.field("chatgptAccount").source);
    root.querySelector('#llm-auth-state').textContent = t("llm.chatgpt.state." + (data.authBusy ? "working"
      : data.login ? "pending" : data.selectedAccount() ? "connected" : "signed_out"));
    var selected = data.selectedAccount();
    var paused = root.querySelector('#llm-auth-paused');
    paused.hidden = !selected || !selected.usagePaused;
    root.querySelector('#llm-auth-error').textContent = data.authError;
    root.querySelector('#llm-auth-notice').textContent = data.authNotice ? t("llm.chatgpt.notice." + data.authNotice) : "";
    ["login", "reconnect", "logout"].forEach(function (action) {
      root.querySelector('[data-llm-action="' + action + '"]').disabled = data.saving || data.authBusy || !!data.login
        || (action !== "login" && !current);
    });
    var cancel = root.querySelector('[data-llm-action="cancel-login"]');
    cancel.hidden = !data.login && data.authBusy !== "login"; cancel.disabled = data.saving;
    root.querySelector('[data-llm-action="refresh-auth"]').disabled = data.saving || data.authBusy;
    var link = root.querySelector('#llm-auth-open');
    var url = data.login && data.login.authorizationUrl;
    link.hidden = !url; link.removeAttribute("href");
    if (url && validChatgptAuthorizationUrl(url)) link.href = url;
    root.querySelector('#llm-auth-pending').hidden = !data.login;
  }
  function form(data) {
    function field(key) {
      return '<div class="llm-field' + (key === "baseUrl" || key === "model" || key === "chatgptModel" ? ' llm-wide' : '') + '"><label for="llm-' + key + '">' + escHtml(t("llm." + key))
        + '</label><div class="llm-input-row"><input id="llm-' + key + '" data-llm-field="' + key + '" value="' + escHtml(data.value(key)) + '"'
        + (key === "temperature" ? ' type="number" min="0" max="2" step="0.1"' : ' type="text"')
        + '>' + (key === "model" || key === "chatgptModel" ? button("discover", "llm.discover") : '') + '</div><div class="llm-field-meta"><span data-llm-source="' + key + '"></span>'
        + button('reset-' + key, "llm.inherit", "llm-link-button") + '</div></div>';
    }
    var html = '<div class="llm-field llm-provider"><label for="llm-provider">' + escHtml(t("llm.provider"))
      + '</label><select id="llm-provider" data-llm-field="provider"><option value="api-key">' + escHtml(t("llm.provider.api-key"))
      + '</option><option value="chatgpt">' + escHtml(t("llm.provider.chatgpt")) + '</option></select>'
      + '<div class="llm-field-meta"><span data-llm-source="provider"></span>' + button("reset-provider", "llm.inherit", "llm-link-button") + '</div></div>'
      + '<section class="llm-form-section" data-llm-provider-section="api-key"><h4><span class="config-step">01</span>' + escHtml(t("llm.connectionSection")) + '</h4>'
      + '<div class="llm-field-grid"><label class="llm-wide">' + escHtml(t("llm.preset"))
      + '<select data-llm-preset><option value="">' + escHtml(t("llm.custom")) + '</option>'
      + data.presets.filter(function (p) { return p.id !== "custom"; }).map(function (p) {
        return '<option value="' + escHtml(p.id) + '"' + (p.baseUrl === data.value("baseUrl") ? ' selected' : '') + '>' + escHtml(p.name) + '</option>';
      }).join("") + '</select></label>';
    html += field("baseUrl") + '<div class="llm-wide"><label for="llm-key">API Key</label>'
      + '<div class="llm-input-row"><input id="llm-key" data-llm-key type="password" autocomplete="new-password" placeholder="' + escHtml(t("llm.keyHint")) + '">'
      + button("reveal-key", "llm.showKey") + '</div><p id="llm-key-state"></p><div class="llm-credential-actions">'
      + button("clear-key", "llm.clearKey", "llm-link-button") + button("reset-key", "llm.resetKey", "llm-link-button") + '</div></div></div></section>'
      + '<section class="llm-form-section llm-chatgpt" data-llm-provider-section="chatgpt"><h4>' + escHtml(t("llm.chatgpt.title")) + '</h4>'
      + '<p class="llm-hint">' + escHtml(t("llm.chatgpt.disclosure")) + '</p>'
      + '<label for="llm-chatgpt-account">' + escHtml(t("llm.chatgptAccount")) + '</label>'
      + '<select id="llm-chatgpt-account" data-llm-field="chatgptAccount"></select>'
      + '<div class="llm-field-meta"><span data-llm-source="chatgptAccount"></span>' + button("reset-chatgptAccount", "llm.inherit", "llm-link-button") + '</div>'
      + '<p id="llm-auth-state" role="status"></p><p id="llm-auth-paused" role="status" class="llm-hint">'
      + escHtml(t("llm.chatgpt.usagePaused")) + '</p><div class="llm-auth-actions">'
      + button("login", "llm.chatgpt.login") + button("reconnect", "llm.chatgpt.reconnect") + button("logout", "llm.chatgpt.logout")
      + button("cancel-login", "llm.chatgpt.cancel") + button("refresh-auth", "llm.chatgpt.refresh", "llm-link-button") + '</div>'
      + '<p id="llm-auth-pending" class="llm-hint">' + escHtml(t("llm.chatgpt.pendingHelp")) + '</p>'
      + '<a id="llm-auth-open" target="_blank" rel="noopener noreferrer" data-llm-action="open-login">' + escHtml(t("llm.chatgpt.open")) + '</a>'
      + '<p id="llm-auth-error" role="alert"></p><p id="llm-auth-notice" role="status"></p></section>'
      + '<section class="llm-form-section"><h4><span class="config-step">02</span>' + escHtml(t("llm.generationSection")) + '</h4><div class="llm-field-grid">'
      + '<div class="llm-wide" data-llm-provider-section="api-key">' + field("model") + '</div>'
      + '<div class="llm-wide" data-llm-provider-section="chatgpt">' + field("chatgptModel") + '</div>'
      + '<div class="llm-wide"><p id="llm-discover-result" role="status"></p><div id="llm-models" class="llm-model-candidates"></div></div>'
      + '<div data-llm-provider-section="api-key">' + field("temperature") + '</div>'
      + '<p class="llm-hint llm-wide" data-llm-provider-section="chatgpt">' + escHtml(t("llm.chatgpt.parameters")) + '</p>'
      + '</div><p class="llm-hint">' + escHtml(t("llm.outputPolicy")) + '</p></section>'
      + '<section class="llm-probes"><p>' + escHtml(t("llm.billing")) + '</p>' + button("test", "llm.test")
      + '<p id="llm-test-result" role="status"></p></section>'
      + '<section><h4>' + escHtml(t("config.runtimeTitle")) + '</h4><ul id="llm-runtime" aria-live="polite"></ul></section>';
    return html;
  }
  function input(event) {
    var el = event.target;
    if (el.dataset.llmField && el.tagName !== "SELECT") model.change(el.dataset.llmField, el.value);
    if (el.hasAttribute("data-llm-key")) model.setCredential("replace", el.value);
  }
  function change(event) {
    if (event.target.dataset.llmField) model.change(event.target.dataset.llmField, event.target.value);
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
      var modelField = model.value("provider") === "chatgpt" ? "chatgptModel" : "model";
      model.change(modelField, el.dataset.modelId);
      root.querySelector('[data-llm-field="' + modelField + '"]').focus();
    }
    else if (action === "login" || action === "reconnect") {
      model.startLogin(action === "reconnect" ? model.value("chatgptAccount") : null).then(function (url) {
        if (url && model.login && nativeBrowser()) openLogin(url);
      });
    }
    else if (action === "open-login") {
      if (nativeBrowser()) { event.preventDefault(); openLogin(model.login && model.login.authorizationUrl); }
      else if (!model.login || !validChatgptAuthorizationUrl(model.login.authorizationUrl)) event.preventDefault();
    }
    else if (action === "cancel-login") model.cancelLogin();
    else if (action === "refresh-auth") model.refreshAuth();
    else if (action === "logout") {
      if (window.confirm(t("llm.chatgpt.confirmLogout"))) model.logout();
    }
    else if (action === "discard" || action === "retry") model.load();
    else if (action === "test" || action === "discover") model.run(action);
    else if (action === "clear-key" || action === "reset-key") {
      if (window.confirm(t(action === "clear-key" ? "llm.confirmClear" : "llm.confirmReset"))) model.setCredential(action === "clear-key" ? "clear" : "reset");
    } else if (action.indexOf("reset-") === 0) model.inherit(action.slice(6));
  }
  function nativeBrowser() {
    return !!(window.__SELF_ANALYST_DESKTOP__ && window.__TAURI__ && window.__TAURI__.core);
  }
  function openLogin(url) {
    if (!validChatgptAuthorizationUrl(url)) { model.browserFailed(); return; }
    var attempt = model.login && model.login.attemptId;
    if (!attempt || openingAttempt === attempt) return;
    openingAttempt = attempt; model.retryBrowser();
    Promise.resolve().then(function () {
      if (model.login && model.login.attemptId === attempt) {
        return window.__TAURI__.core.invoke("help_action", { action: "chatgpt_login", authorizationUrl: url });
      }
    }).catch(function () {
      if (model.login && model.login.attemptId === attempt) model.browserFailed();
    }).finally(function () { if (openingAttempt === attempt) openingAttempt = null; });
  }
  root.addEventListener("input", input); root.addEventListener("change", change); root.addEventListener("click", click);
  model.load();
  return { dirty: model.dirty, saving: function () { return model.saving; }, destroy: function () {
    model.destroy(); root.removeEventListener("input", input); root.removeEventListener("change", change); root.removeEventListener("click", click); root.innerHTML = "";
  } };
}
