/* ============================================================
   SelfAnalyst Desktop - UI Helpers (Error, Status Bar, Tabs, Data Loading)
   ============================================================ */
"use strict";

// ---- Error Overlay ----

function showError(msg) {
  state.error = msg;
  state.dom.errorMessage.textContent = msg || t("error.notReady");
  state.dom.errorOverlay.classList.remove("hidden");
}

function hideError() {
  state.error = null;
  state.dom.errorOverlay.classList.add("hidden");
}

// ---- Status Bar ----

function updateStatusBar() {
  var st = state.status || {};
  var cfg = state.config || {};

  setStatusDot(state.dom.backendDot, true, t("status.service"));
  state.dom.backendText.textContent = t("status.service");

  // Collectors
  var collectorsOk = st.collectors === "running" || st.collectors_status === "running";
  setStatusDot(state.dom.collectorsDot, collectorsOk, t("status.capture"));
  state.dom.collectorsText.textContent = t("status.capture");

  // LLM
  var llmOk = st.llm && st.llm.configured;
  setStatusDot(state.dom.llmDot, llmOk, t("status.llm"));
  state.dom.llmText.textContent = t("status.llm");
}

function setStatusDot(el, ok, label) {
  el.className = "status-dot " + (ok ? "green" : "orange");
  el.title = label + " " + (ok ? t("status.ok") : t("status.notReady"));
}

// ---- Tab Switching ----

function switchTab(tab) {
  state.tab = tab;
  state.dom.tabs.forEach(function (t) {
    t.classList.toggle("active", t.dataset.tab === tab);
  });
  state.dom.tabAgent.classList.toggle("active", tab === "agent");
  state.dom.tabChat.classList.toggle("active", tab === "chat");

  if (tab === "chat") {
    // ensureActiveChatSession may create a session asynchronously; re-render
    // once it resolves so a freshly created session shows up (SPEC-CSP-FE-002).
    ensureActiveChatSession().then(renderChatTab).catch(function () {});
    renderChatTab();
    setTimeout(function () {
      if (state.dom.chatTabInput) state.dom.chatTabInput.focus();
    }, 100);
  }
}

// ---- Config Modal ----

function openConfigModal() {
  state.configOpen = true;
  state.dom.configModal.classList.remove("hidden");
  // Always reload so the editor reflects the on-disk file, which may have been
  // changed by the Agent or externally. SPEC-CFGUI-UI-002a.
  loadConfig();
}

function closeConfigModal() {
  // Guard against losing unsaved edits. SPEC-CFGUI-UI-004a.
  if (state.configDirty && !window.confirm(t("config.confirmDiscardClose"))) {
    return;
  }
  state.configOpen = false;
  state.configDirty = false;
  state.dom.configModal.classList.add("hidden");
}

// ---- Data Loading ----

function loadAll() {
  state.loading = true;
  hideError();

  var withTimeout = function (p, ms) {
    return new Promise(function (resolve, reject) {
      var timer = setTimeout(function () { reject(new Error("timeout")); }, ms);
      p.then(function (v) { clearTimeout(timer); resolve(v); })
       .catch(function (e) { clearTimeout(timer); reject(e); });
    });
  };

  // Phase 1: status + tasks are fast — render immediately
  Promise.all([
    withTimeout(api.getStatus(), 5000).catch(function () { return null; }),
    withTimeout(api.getTasks(), 5000).catch(function () { return []; })
  ]).then(function (results) {
    state.status = results[0];
    state.tasks = results[1] || [];

    if (!state.status) {
      showError(t("error.notReady"));
    } else {
      hideError();
    }
    updateStatusBar();
    renderTasks();
  });

  // Phase 2: summary can be slow (LLM calls) — update when ready
  withTimeout(api.getSummary(), 30000)
    .catch(function () { return null; })
    .then(function (summary) {
      state.summary = summary;
      state.loading = false;
      if (state.tab === "agent") {
        renderBehaviorAdvice();
        renderTimeline();
      }
    });
}

function loadConfig() {
  api.getRawConfig()
    .then(function (resp) {
      state.configRawText = resp.text || "";
      state.configRawBaseline = resp.text || "";
      state.configSupportedKeys = (resp && resp.supportedKeys) || [];
      state.configLoadError = false;
      state.configDirty = false;
      state.configSaveResult = null;
      state.configSaving = false;
      state.configHistoryExpandedId = null;
      renderConfigTab();
      loadConfigHistory().then(renderConfigTab);
    })
    .catch(function (err) {
      // Load failed → read-only error state, no saving on unknown content.
      // SPEC-CFGUI-UI-002c.
      state.configRawText = "";
      state.configRawBaseline = "";
      state.configLoadError = true;
      state.configDirty = false;
      state.configSaveResult = { type: "error", msg: t("config.loadConfigFailed", { msg: err.message || t("common.unknownError") }) };
      state.configSaving = false;
      renderConfigTab();
    });
}

function loadTasks() {
  api.getTasks()
    .then(function (tasks) {
      state.tasks = tasks || [];
      renderTasks();
    })
    .catch(function () {
      renderTasks();
    });
}

// ---- Auto-refresh ----

var refreshTimer = null;

function startAutoRefresh() {
  stopAutoRefresh();
  refreshTimer = setInterval(function () {
    if (state.tab !== "agent") return;
    // Status + tasks refresh fast
    Promise.all([
      api.getStatus().catch(function () { return state.status; }),
      api.getTasks().catch(function () { return state.tasks; }),
    ]).then(function (results) {
      state.status = results[0];
      state.tasks = results[1] || state.tasks;
      updateStatusBar();
      renderTasks();
    });
    // Summary refresh separately (may involve LLM)
    api.getSummary().catch(function () { return state.summary; }).then(function (summary) {
      state.summary = summary;
      renderBehaviorAdvice();
      renderTimeline();
    });
  }, 30000);
}

function stopAutoRefresh() {
  if (refreshTimer) {
    clearInterval(refreshTimer);
    refreshTimer = null;
  }
}
