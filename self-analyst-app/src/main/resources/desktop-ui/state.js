/* ============================================================
   SelfAnalyst Desktop - Application State
   ============================================================ */
"use strict";

var state = {
  tab: "agent",
  lang: "zh",  // effective language; overwritten from /desktop/status in init() (SPEC-I18N-UI-004)
  status: null,
  summary: null,
  usage: null,
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
  configSupportedKeys: [],
  configAllKeysOpen: false,
  configSaveResult: null,
  configSaving: false,
  configOpen: false,
  chatSessions: [],
  activeChatSessionId: null,
  chatSessionSearch: "",
  chatSending: false,
  chatContextToggles: { currentStatus: true, futureTasks: true, history: false },
  memoryItems: [],
  memoryFilter: "",
  memoryLoading: false,
  memoryLoadError: null,
  pendingMemoryCount: 0,
  memoryDraft: "",
  audioEvents: [],
  audioEventsLoading: false,
  audioEventsError: null,
  audioEventsStatus: "disabled",
  audioEventsUpdatedAt: null,
  audioEventsLatestAt: null,
  audioEventsCount: 0,
  audioDiagnostics: null,
};

// Legacy localStorage key. No longer a data source (sessions live in the
// backend, SPEC-CSP-DEC-002/-004); retained only for the one-time deletion in
// init() so upgraded clients drop stale local data (SPEC-CSP-FE-006).
var CHAT_STORAGE_KEY = "selfAnalyst.chatSessions.v1";
