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
  var collectors = st.collectors || {};
  var contentPersistence = st.contentPersistence || {};
  var contentPersistenceFailed = contentPersistence.ready === false;

  setStatusDot(state.dom.backendDot, true, t("status.service"));
  state.dom.backendText.textContent = t("status.service");

  // Collectors
  var collectorsOk = !contentPersistenceFailed && (
    st.collectors === "running" || st.collectors_status === "running" ||
    collectors.window === "running" || collectors.afk === "running" ||
    collectors.contextTitle === "running" || collectors.content === "running");
  setStatusDot(state.dom.collectorsDot, collectorsOk, t("status.capture"));
  state.dom.collectorsText.textContent = t("status.capture");
  if (contentPersistenceFailed) {
    var persistenceTitle = t("status.contextTitleMigrationFailed");
    if (contentPersistence.error) persistenceTitle += ": " + contentPersistence.error;
    state.dom.collectorsDot.title = persistenceTitle;
    state.dom.collectorsText.title = persistenceTitle;
  } else {
    state.dom.collectorsText.title = state.dom.collectorsDot.title;
  }

  // File collector has its own persistent entry so it remains discoverable.
  var fileStatus = collectors.file || "disabled";
  setStatusDotByState(state.dom.fileDot, fileStatus, t("status.file"));
  state.dom.fileText.textContent = t("status.file");
  state.dom.fileStatusBtn.title = state.dom.fileDot.title;

  // Permanent raw-event storage: only diagnostic metadata is rendered.
  var raw = st.raw || { status: "unavailable" };
  setStatusDotByState(state.dom.rawDot, raw.status, t("status.raw"));
  state.dom.rawText.textContent = t("status.raw");
  var rawTitle = state.dom.rawDot.title;
  if (raw.diskWarning) rawTitle += "; " + t("status.diskWarning");
  if (typeof raw.projectionLagSeconds === "number") {
    rawTitle += "; " + t("status.projectionLag") + ": " + raw.projectionLagSeconds + "s";
  }
  state.dom.rawDot.title = rawTitle;
  state.dom.rawText.title = rawTitle;

  // LLM
  var llmOk = st.llm && st.llm.configured;
  var llmTitle = t("status.llm") + " " + (llmOk ? t("status.ok") : t("status.notReady"));
  setStatusDot(state.dom.llmDot, llmOk, t("status.llm"));
  state.dom.llmDot.title = llmTitle;
  state.dom.llmText.textContent = t("status.llm");
  state.dom.llmText.title = llmTitle;

}

function setStatusDot(el, ok, label) {
  el.className = "status-dot " + (ok ? "green" : "orange");
  el.title = label + " " + (ok ? t("status.ok") : t("status.notReady"));
}

function setStatusDotByState(el, status, label) {
  var css = status === "running" ? "green" :
    (status === "degraded" || status === "blocked") ? "red" : "gray";
  var key = status === "running" ? "status.running" :
    status === "degraded" ? "status.degraded" :
    status === "blocked" ? "status.blocked" :
    status === "unavailable" ? "status.unavailable" : "status.disabled";
  el.className = "status-dot " + css;
  el.title = label + " " + t(key);
}

// ---- Tab Switching ----

function switchTab(tab) {
  state.tab = tab;
  state.dom.tabs.forEach(function (t) {
    t.classList.toggle("active", t.dataset.tab === tab);
  });
  state.dom.tabAgent.classList.toggle("active", tab === "agent");
  state.dom.tabChat.classList.toggle("active", tab === "chat");
  state.dom.tabFiles.classList.toggle("active", tab === "files");

  if (tab === "chat") {
    // ensureActiveChatSession may create a session asynchronously; re-render
    // once it resolves so a freshly created session shows up (SPEC-CSP-FE-002).
    ensureActiveChatSession().then(renderChatTab).catch(function () {});
    renderChatTab();
    setTimeout(function () {
      focusChatComposer();
    }, 100);
  } else if (tab === "files") {
    renderFilesTab();
    loadFiles();
  }
}

// ---- Config Modal ----

function openConfigModal(focusKey) {
  if (typeof focusKey !== "string") focusKey = null;
  state.configOpen = true;
  state.dom.configModal.classList.remove("hidden");
  // Always reload so the editor reflects the on-disk file, which may have been
  // changed by the Agent or externally. SPEC-CFGUI-UI-002a.
  return loadConfig().then(function () {
    if (focusKey) focusConfigEditorKey(focusKey);
  });
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
  hideError();
  if (state.summary && state.tab === "agent") {
    renderBehaviorAdvice();
    renderTimeline();
  }
  state.loading = !state.summary;

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
    withTimeout(api.getTasks(), 5000).catch(function () { return []; }),
    withTimeout(api.getUsage(), 5000).catch(function () { return null; })
  ]).then(function (results) {
    state.status = results[0];
    state.tasks = results[1] || [];
    state.usage = results[2];

    if (!state.status) {
      showError(t("error.notReady"));
    } else {
      hideError();
    }
    updateStatusBar();
    renderTasks();
  });

  // Phase 2: summary may refresh the open window; keep any existing snapshot on screen.
  withTimeout(api.getSummary(), 30000)
    .then(function (summary) {
      if (summary) state.summary = summary;
      state.loading = false;
      if (state.tab === "agent" && state.summary) {
        renderBehaviorAdvice();
        renderTimeline();
      }
    })
    .catch(function () {
      state.loading = false;
    });
}

function loadConfig() {
  return api.getRawConfig()
    .then(function (resp) {
      state.configRawText = resp.text || "";
      state.configRawBaseline = resp.text || "";
      state.configPath = (resp && resp.path) || "config.toml";
      state.configLoadError = false;
      state.configDirty = false;
      state.configSaveResult = null;
      state.configSaving = false;
      renderConfigTab();
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
    if (state.tab === "files") {
      api.getStatus().catch(function () { return state.status; }).then(function (status) {
        state.status = status || state.status;
        updateStatusBar();
      });
      loadFiles();
      return;
    }
    if (state.tab !== "agent") return;
    // Status + tasks refresh fast
    Promise.all([
      api.getStatus().catch(function () { return state.status; }),
      api.getTasks().catch(function () { return state.tasks; }),
      api.getUsage().catch(function () { return state.usage; }),
    ]).then(function (results) {
      state.status = results[0];
      state.tasks = results[1] || state.tasks;
      state.usage = results[2] || state.usage;
      updateStatusBar();
      renderTasks();
    });
    // Summary refresh separately; keep the last rendered snapshot if the request fails.
    api.getSummary().then(function (summary) {
      if (!summary) return;
      state.summary = summary;
      renderBehaviorAdvice();
      renderTimeline();
    }).catch(function () {});
  }, 30000);
}

function stopAutoRefresh() {
  if (refreshTimer) {
    clearInterval(refreshTimer);
    refreshTimer = null;
  }
}
