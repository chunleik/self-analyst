/* ============================================================
   SelfAnalyst Desktop - Initialization
   ============================================================ */
"use strict";

function cacheDom() {
  state.dom = {
    tabs: $$(".tab"),
    tabAgent: $("#tab-agent"),
    // Status bar
    backendDot: $("#backend-dot"),
    backendText: $("#backend-text"),
    collectorsDot: $("#collectors-dot"),
    collectorsText: $("#collectors-text"),
    rawDot: $("#raw-dot"),
    rawText: $("#raw-text"),
    fileStatusBtn: $("#file-status-btn"),
    fileDot: $("#file-dot"),
    fileText: $("#file-text"),
    llmDot: $("#llm-dot"),
    llmText: $("#llm-text"),
    // Agent tab
    timelineBody: $("#timeline-body"),
    tasksBody: $("#tasks-body"),
    // Config modal
    configGrid: $("#config-grid"),
    configModal: $("#config-modal"),
    configOpenBtn: $("#config-open-btn"),
    configCloseBtn: $("#config-close-btn"),
    // Chat
    chatDrawer: $("#chat-drawer"),
    chatTitle: $("#chat-title"),
    chatCloseBtn: $("#chat-close-btn"),
    chatContext: $("#chat-context"),
    chatMessages: $("#chat-messages"),
    chatSuggestions: $("#chat-suggestions"),
    chatInput: $("#chat-input"),
    chatSendBtn: $("#chat-send-btn"),
    // Chat Tab
    tabChat: $("#tab-chat"),
    // Files tab
    fileNavTab: $('[data-tab="files"]'),
    tabFiles: $("#tab-files"),
    fileTabStatus: $("#file-tab-status"),
    fileTabSubtitle: $("#file-tab-subtitle"),
    fileContent: $("#file-content"),
    fileSettingsBtn: $("#file-settings-btn"),
    fileSettingsModal: $("#file-settings-modal"),
    fileSettingsCloseBtn: $("#file-settings-close-btn"),
    fileSettingsCancelBtn: $("#file-settings-cancel-btn"),
    fileSettingsSaveBtn: $("#file-settings-save-btn"),
    fileSettingsEnabled: $("#file-settings-enabled"),
    fileSettingsError: $("#file-settings-error"),
    chatSessionList: $("#chat-session-list"),
    chatSessionSearchInput: $("#chat-session-search-input"),
    newChatSessionBtn: $("#new-chat-session-btn"),
    chatThread: $("#chat-thread"),
    deepChat: $("#deep-chat"),
    chatFallback: $("#chat-fallback"),
    chatFallbackThread: $("#chat-fallback-thread"),
    chatSessionTitle: $("#chat-session-title"),
    chatTabInput: $("#chat-tab-input"),
    chatTabSendBtn: $("#chat-tab-send-btn"),
    chatContextSummary: $("#chat-context-summary"),
    chatRecentActivity: $("#chat-recent-activity"),
    chatTaskSuggestions: $("#chat-task-suggestions"),
    chatContextToggles: $("#chat-context-toggles"),
    // Overlay
    errorOverlay: $("#error-overlay"),
    errorMessage: $("#error-message"),
    errorRetryBtn: $("#error-retry-btn"),
  };
}

var initializationPending = null;
var initialized = false;
var bootstrapBound = false;

function initializeLanguage() {
  if (initialized) return Promise.resolve();
  if (initializationPending) return initializationPending;
  var timeout;
  var statusRequest = Promise.race([
    api.getStatus(),
    new Promise(function (_, reject) { timeout = setTimeout(function () { reject(new Error("Status timeout")); }, 5000); })
  ]);
  initializationPending = statusRequest.then(function (st) {
    if (!st || !st.language || !st.dateLocale) throw new Error("Missing language metadata");
    var selected = (st.languages || []).find(function (language) { return language.code === st.language; });
    return loadI18n(st.language, selected && selected.resource).then(function () {
      state.lang = st.language;
      state.dateLocale = st.dateLocale;
      state.status = st;
      document.documentElement.lang = st.language;
      applyI18n(document);
      hideError();
      initialized = true;
      state.dom.errorRetryBtn.removeEventListener("click", initializeLanguage);
      setupEvents();
      switchTab("agent");
      loadChatSessions().then(function () { renderChatTab(); });
      loadAll();
      startAutoRefresh();
    });
  }).catch(function () {
    showError(t("error.notReady"));
  }).finally(function () {
    clearTimeout(timeout);
    initializationPending = null;
  });
  return initializationPending;
}

function init() {
  if (!bootstrapBound) {
    cacheDom();
    try { localStorage.removeItem(CHAT_STORAGE_KEY); } catch (ignored) {}
    state.dom.errorRetryBtn.addEventListener("click", initializeLanguage);
    bootstrapBound = true;
    state.dom.errorRetryBtn.textContent = t("error.retry");
    showError(t("common.loading"));
  }
  return initializeLanguage();
}

// Start when DOM is ready
if (document.readyState === "loading") {
  document.addEventListener("DOMContentLoaded", init);
} else {
  init();
}
