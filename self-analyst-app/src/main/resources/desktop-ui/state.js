/* ============================================================
   SelfAnalyst Desktop - Application State
   ============================================================ */
"use strict";

var state = {
  tab: "agent",
  status: null,
  summary: null,
  tasks: [],
  config: null,
  chatOpen: false,
  chatContext: null,
  chatMessages: [],
  loading: true,
  error: null,
  editingTaskId: null,
  configDirty: false,
  configChangedSections: 0,
  configRawText: "",
  configRawBaseline: "",
  configLoadError: false,
  configHistory: [],
  configHistoryOpen: false,
  configHistoryExpandedId: null,
  configSaveResult: null,
  configSaving: false,
  configOpen: false,
  chatSessions: [],
  activeChatSessionId: null,
  chatSessionSearch: "",
  chatSending: false,
  chatContextToggles: { currentStatus: true, futureTasks: true, history: false },
};

var CHAT_STORAGE_KEY = "selfAnalyst.chatSessions.v1";
