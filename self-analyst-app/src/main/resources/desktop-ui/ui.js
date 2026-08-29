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

  setStatusDot(state.dom.backendDot, true, t("status.service"));
  state.dom.backendText.textContent = t("status.service");

  // Collectors
  var collectorsOk = st.collectors === "running" || st.collectors_status === "running" ||
    collectors.window === "running" || collectors.afk === "running" ||
    collectors.content === "running" || collectors.audio === "running";
  setStatusDot(state.dom.collectorsDot, collectorsOk, t("status.capture"));
  state.dom.collectorsText.textContent = t("status.capture");

  // LLM
  var llmOk = st.llm && st.llm.configured;
  var headroom = (state.usage && state.usage.headroom) || st.headroom || {};
  var headroomStatus = headroom.status || "unknown";
  var headroomLabel = t("headroom.status." + headroomStatus);
  var llmTitle = t("status.llm") + " " + (llmOk ? t("status.ok") : t("status.notReady")) +
    " · Headroom " + headroomLabel;
  setStatusDot(state.dom.llmDot, llmOk, t("status.llm"));
  state.dom.llmDot.title = llmTitle;
  state.dom.llmText.textContent = t("status.llm") + " · Headroom " + headroomLabel;
  state.dom.llmText.title = llmTitle;

  updateAudioToggle();
}

function setStatusDot(el, ok, label) {
  el.className = "status-dot " + (ok ? "green" : "orange");
  el.title = label + " " + (ok ? t("status.ok") : t("status.notReady"));
}

function audioCaptureRunning() {
  var st = state.status || {};
  var collectors = st.collectors || {};
  return collectors.audio === "running";
}

function updateAudioToggle() {
  var btn = state.dom.audioToggleBtn || document.getElementById("audio-toggle-btn");
  if (!btn) return;
  var running = audioCaptureRunning();
  var title = t(running ? "audio.stop" : "audio.start");
  btn.classList.toggle("active", running);
  btn.setAttribute("title", title);
  btn.setAttribute("aria-label", title);
}

// ---- Tab Switching ----

function switchTab(tab) {
  state.tab = tab;
  state.dom.tabs.forEach(function (t) {
    t.classList.toggle("active", t.dataset.tab === tab);
  });
  state.dom.tabAgent.classList.toggle("active", tab === "agent");
  state.dom.tabChat.classList.toggle("active", tab === "chat");
  state.dom.tabAudio.classList.toggle("active", tab === "audio");

  if (tab === "chat") {
    // ensureActiveChatSession may create a session asynchronously; re-render
    // once it resolves so a freshly created session shows up (SPEC-CSP-FE-002).
    ensureActiveChatSession().then(renderChatTab).catch(function () {});
    renderChatTab();
    setTimeout(function () {
      focusChatComposer();
    }, 100);
  } else if (tab === "audio") {
    renderAudioTab();
    loadAudioEvents();
  }
}

function loadAudioEvents() {
  state.audioEventsLoading = true;
  state.audioEventsError = null;
  renderAudioTab();
  return api.getAudioEvents(50)
    .then(function (resp) {
      state.audioEvents = (resp && resp.events) || [];
      state.audioEventsStatus = (resp && resp.status) || "disabled";
      state.audioEventsLatestAt = (resp && resp.latestEventAt) ||
        (state.audioEvents[0] && state.audioEvents[0].timestamp) || null;
      state.audioEventsCount = (resp && typeof resp.eventCount === "number")
        ? resp.eventCount
        : state.audioEvents.length;
      state.audioDiagnostics = (resp && resp.diagnostics) || null;
      state.audioEventsUpdatedAt = new Date().toISOString();
    })
    .catch(function (err) {
      state.audioEventsError = err.message || t("common.unknownError");
    })
    .then(function () {
      state.audioEventsLoading = false;
      renderAudioTab();
    });
}

function renderAudioTab() {
  var list = state.dom.audioTranscriptList;
  if (!list) return;

  if (state.dom.audioTabStatus) {
    state.dom.audioTabStatus.innerHTML = statusBadge(state.audioEventsStatus || "disabled");
  }
  if (state.dom.audioTabSubtitle) {
    var diagnosis = audioDiagnosisMessage();
    if (diagnosis) {
      state.dom.audioTabSubtitle.textContent = diagnosis;
    } else if (state.audioEventsLatestAt) {
      state.dom.audioTabSubtitle.textContent = t("audio.latest", {
        time: formatRelativeTime(state.audioEventsLatestAt)
      });
    } else if (state.audioEventsStatus === "running") {
      state.dom.audioTabSubtitle.textContent = t("audio.listening");
    } else {
      state.dom.audioTabSubtitle.textContent = t("audio.subtitle");
    }
  }

  if (state.audioEventsLoading && (!state.audioEvents || state.audioEvents.length === 0)) {
    list.innerHTML = '<div class="loading-placeholder">' + escHtml(t("common.loading")) + "</div>";
    return;
  }
  if (state.audioEventsError) {
    list.innerHTML = '<div class="audio-empty audio-error">' +
      escHtml(t("audio.loadFailed", { msg: state.audioEventsError })) + "</div>";
    return;
  }

  var events = state.audioEvents || [];
  if (events.length === 0) {
    list.innerHTML = '<div class="audio-empty">' + escHtml(t("audio.empty")) + "</div>";
    return;
  }

  list.innerHTML = events.map(function (ev) {
    var duration = ev.duration ? Math.round(ev.duration) + "s" : "";
    var sourceLabel = audioSourceLabel(ev.source);
    return '<article class="audio-transcript-item">' +
      '<div class="audio-transcript-meta">' +
        '<span>' + escHtml(formatDateTime(ev.timestamp)) + "</span>" +
        (sourceLabel ? '<span>' + escHtml(sourceLabel) + "</span>" : "") +
        (duration ? '<span>' + escHtml(duration) + "</span>" : "") +
        (ev.engine ? '<span>' + escHtml(ev.engine) + "</span>" : "") +
      "</div>" +
      '<div class="audio-transcript-text">' + escHtml(ev.text) + "</div>" +
    "</article>";
  }).join("");
}

function audioSourceLabel(source) {
  if (source === "system") return t("audio.source.system");
  if (source === "both") return t("audio.source.both");
  return t("audio.source.mic");
}

function audioDiagnosisMessage() {
  var d = state.audioDiagnostics;
  if (!d || state.audioEventsStatus !== "running") return "";
  var latestTranscriptMs = audioDiagnosticMs(state.audioEventsLatestAt);
  var diagnostics = [
    { type: "error", at: d.lastErrorAt, msg: d.lastError },
    { type: "emptyTranscript", at: d.lastEmptyTranscriptAt },
    { type: "silent", at: d.lastSilentAt },
  ].map(function (item) {
    item.ms = audioDiagnosticMs(item.at);
    return item;
  }).filter(function (item) {
    return item.ms != null && (latestTranscriptMs == null || item.ms > latestTranscriptMs);
  }).sort(function (a, b) {
    return b.ms - a.ms;
  });

  var latest = diagnostics[0];
  if (!latest) return "";
  if (latest.type === "error") {
    return t("audio.diagnostics.error", { msg: latest.msg || t("common.unknownError") });
  }
  if (latest.type === "emptyTranscript") {
    return t("audio.diagnostics.emptyTranscript", {
      time: formatRelativeTime(latest.at)
    });
  }
  if (latest.type === "silent") {
    return t("audio.diagnostics.silent", {
      time: formatRelativeTime(latest.at)
    });
  }
  return "";
}

function audioDiagnosticMs(value) {
  if (!value) return null;
  var ms = new Date(value).getTime();
  return Number.isFinite(ms) ? ms : null;
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
var audioRefreshTimer = null;

function startAutoRefresh() {
  stopAutoRefresh();
  refreshTimer = setInterval(function () {
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
    // Summary refresh separately (may involve LLM)
    api.getSummary().catch(function () { return state.summary; }).then(function (summary) {
      state.summary = summary;
      renderBehaviorAdvice();
      renderTimeline();
    });
  }, 30000);

  audioRefreshTimer = setInterval(function () {
    if (state.tab === "audio") {
      api.getStatus().catch(function () { return state.status; }).then(function (status) {
        state.status = status || state.status;
        updateStatusBar();
      });
      loadAudioEvents();
    }
  }, 2000);
}

function stopAutoRefresh() {
  if (refreshTimer) {
    clearInterval(refreshTimer);
    refreshTimer = null;
  }
  if (audioRefreshTimer) {
    clearInterval(audioRefreshTimer);
    audioRefreshTimer = null;
  }
}
