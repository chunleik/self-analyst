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
    chatSessionList: $("#chat-session-list"),
    chatSessionSearchInput: $("#chat-session-search-input"),
    newChatSessionBtn: $("#new-chat-session-btn"),
    chatThread: $("#chat-thread"),
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

function init() {
  cacheDom();
  loadChatSessions();
  setupEvents();
  switchTab("agent");
  loadAll();
  startAutoRefresh();
}

// Start when DOM is ready
if (document.readyState === "loading") {
  document.addEventListener("DOMContentLoaded", init);
} else {
  init();
}
