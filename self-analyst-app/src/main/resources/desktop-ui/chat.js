/* ============================================================
   SelfAnalyst Desktop - Chat Tab
   ============================================================ */
"use strict";

// ---- Backend Persistence (SPEC-CSP-FE-002..004) ----
// The backend (`{memoryDir}/chat-sessions/`) is the source of truth; the
// session list below is an in-memory cache that drives rendering. Index meta
// rows carry no `messages` — bodies are lazy-loaded on activation.

function loadChatSessions(acceptResult, preserveCachedSessions) {
  state.chatSessionLoadRequestId = (state.chatSessionLoadRequestId || 0) + 1;
  var loadRequestId = state.chatSessionLoadRequestId;
  state.chatSessionLatestLoadRequestId = loadRequestId;
  var mutationGeneration = state.chatSessionMutationGeneration;
  var activeSessionIdAtStart = state.activeChatSessionId;
  var preservedLoadFailed = false;
  function resultIsCurrent() {
    return loadRequestId === state.chatSessionLoadRequestId
      && mutationGeneration === state.chatSessionMutationGeneration
      && !state.chatSending
      && (!acceptResult || acceptResult());
  }
  function recoverStaleBaseLoad() {
    if (acceptResult || state.chatSessionLatestLoadRequestId !== loadRequestId) return;
    state.chatSessionListReloadNeeded = true;
    if (!state.chatSending) return refreshChatSessionListIfNeeded();
  }
  return api.listSessions({ limit: 50 }).then(function (index) {
    if (!resultIsCurrent()) return recoverStaleBaseLoad();
    var activeChangedLocally = state.activeChatSessionId !== activeSessionIdAtStart;
    var rows = (index && index.sessions) || [];
    var cached = preserveCachedSessions ? (state.chatSessions || []).slice() : [];
    var nextSessions = [];
    for (var rowIndex = 0; rowIndex < rows.length; rowIndex++) {
      var meta = rows[rowIndex];
      var existing = null;
      for (var cachedIndex = 0; cachedIndex < cached.length; cachedIndex++) {
        if (cached[cachedIndex].id === meta.id) {
          existing = cached[cachedIndex];
          cached.splice(cachedIndex, 1);
          break;
        }
      }
      if (existing) {
        mergeSessionFields(existing, meta);
        nextSessions.push(existing);
      } else {
        meta.messages = [];
        meta.messagesLoaded = false;
        nextSessions.push(meta);
      }
    }
    if (preserveCachedSessions) nextSessions = nextSessions.concat(cached);
    state.chatSessions = nextSessions;
    state.activeChatSessionId = activeChangedLocally
      ? state.activeChatSessionId : ((index && index.activeSessionId) || null);
    state.chatSessionsNextCursor = (index && index.nextCursor) || null;
    state.chatSessionsHasMore = !!(index && index.hasMore);
    if (!(state.chatSessionSearch || "").trim()) {
      state.chatSessionSearchResults = null;
      state.chatSessionSearchCursor = null;
      state.chatSessionSearchHasMore = false;
    }
    state.chatSessionsLoaded = true;
    state.chatSessionsLoadError = null;
    var activePresent = state.chatSessions.some(function (session) {
      return session.id === state.activeChatSessionId;
    });
    if (state.activeChatSessionId && !activePresent) {
      var requestedActiveId = state.activeChatSessionId;
      return api.getSession(requestedActiveId).then(function (full) {
        if (!resultIsCurrent() || state.activeChatSessionId !== requestedActiveId) return;
        full.messages = full.messages || [];
        full.messagesLoaded = true;
        state.chatSessions.unshift(full);
      }).catch(function (error) {
        if (!resultIsCurrent() || state.activeChatSessionId !== requestedActiveId) return;
        var alreadyCached = state.chatSessions.some(function (session) {
          return session.id === requestedActiveId;
        });
        if (alreadyCached) return;
        state.chatSessions.unshift({
          id: requestedActiveId,
          title: t("chat.loadSessionFailed", {
            msg: error && error.message ? error.message : t("common.unknownError"),
          }),
          messages: [],
          messagesLoaded: false,
          messagesLoadError: error && error.message ? error.message : t("common.unknownError"),
        });
      });
    }
    if (!state.activeChatSessionId && state.chatSessions.length > 0) {
      state.activeChatSessionId = state.chatSessions[0].id;
      if (api.setActiveSession) api.setActiveSession(state.activeChatSessionId).catch(function () {});
    }
  }).catch(function (err) {
    if (!resultIsCurrent()) return recoverStaleBaseLoad();
    if (preserveCachedSessions) {
      preservedLoadFailed = true;
      state.chatSessionListReloadNeeded = true;
      state.chatSessionsLoadError = err && err.message
        ? err.message : t("common.unknownError");
      return;
    }
    // Keep an empty list rather than breaking init (SPEC-CSP-FE-007).
    state.chatSessions = [];
    state.activeChatSessionId = null;
    state.chatSessionsLoaded = false;
    state.chatSessionsLoadError = err && err.message ? err.message : t("common.unknownError");
  }).then(function () {
    if (!resultIsCurrent()) return recoverStaleBaseLoad();
    if (preservedLoadFailed) return false;
    state.chatSessionListReloadNeeded = false;
    return loadMemoryForChat();
  });
}

function loadMemoryForChat() {
  state.memoryLoading = true;
  return api.listMemory({}).then(function (resp) {
    state.memoryItems = (resp && resp.memories) || [];
    state.pendingMemoryCount = state.memoryItems.filter(function (m) { return m.status === "pending"; }).length;
    state.memoryLoadError = null;
  }).catch(function (err) {
    state.memoryItems = [];
    state.pendingMemoryCount = 0;
    state.memoryLoadError = err && err.message ? err.message : t("common.unknownError");
  }).then(function () {
    state.memoryLoading = false;
  });
}

function appendSessionMetaRows(target, rows) {
  for (var i = 0; i < rows.length; i++) {
    var meta = rows[i];
    var existing = null;
    for (var j = 0; j < target.length; j++) {
      if (target[j].id === meta.id) { existing = target[j]; break; }
    }
    if (existing) {
      mergeSessionFields(existing, meta);
    } else {
      meta.messages = [];
      meta.messagesLoaded = false;
      target.push(meta);
    }
  }
}

function invalidateChatSessionLoads() {
  state.chatSessionLoadRequestId = (state.chatSessionLoadRequestId || 0) + 1;
}

function refreshChatSessionListIfNeeded() {
  if (!state.chatSessionListReloadNeeded || state.chatSending) return Promise.resolve();
  state.chatSessionListReloadNeeded = false;
  return loadChatSessions(null, true).then(function (reloaded) {
    if (reloaded === false) return false;
    if (state.chatSessionListReloadNeeded && !state.chatSending) {
      return refreshChatSessionListIfNeeded();
    }
    return true;
  });
}

function noteChatSessionMutation() {
  invalidateChatSessionLoads();
  state.chatSessionMutationGeneration += 1;
  var query = (state.chatSessionSearch || "").trim();
  if (!query) return;
  if (state.chatSessionSearchTimer) {
    clearTimeout(state.chatSessionSearchTimer);
    state.chatSessionSearchTimer = null;
  }
  state.chatSessionSearchRequestId += 1;
  var requestId = state.chatSessionSearchRequestId;
  state.chatSessionSearchLoading = true;
  runChatSessionSearch(query, requestId, state.chatSessionMutationGeneration);
}

function chatSessionPageRequestIsCurrent(
    searching, requestedQuery, searchRequestId, mutationGeneration) {
  if (mutationGeneration !== state.chatSessionMutationGeneration
      || searchRequestId !== state.chatSessionSearchRequestId) return false;
  var currentQuery = (state.chatSessionSearch || "").trim();
  return currentQuery === requestedQuery && (!!currentQuery) === searching;
}

function loadMoreChatSessions() {
  var query = (state.chatSessionSearch || "").trim();
  var requestedQuery = query;
  var searching = !!query;
  var searchRequestId = state.chatSessionSearchRequestId;
  var mutationGeneration = state.chatSessionMutationGeneration;
  var hasMore = searching ? state.chatSessionSearchHasMore : state.chatSessionsHasMore;
  var cursor = searching ? state.chatSessionSearchCursor : state.chatSessionsNextCursor;
  if (!hasMore || !cursor || state.chatSessionsLoadingMore) return Promise.resolve();
  state.chatSessionsLoadingMore = true;
  var request = api.listSessions({ limit: 50, cursor: cursor, q: query || null })
    .then(function (page) {
      if (!chatSessionPageRequestIsCurrent(
          searching, requestedQuery, searchRequestId, mutationGeneration)) return;
      var rows = (page && page.sessions) || [];
      if (searching) {
        if (!state.chatSessionSearchResults) state.chatSessionSearchResults = [];
        appendSessionMetaRows(state.chatSessionSearchResults, rows);
        state.chatSessionSearchCursor = (page && page.nextCursor) || null;
        state.chatSessionSearchHasMore = !!(page && page.hasMore);
      } else {
        appendSessionMetaRows(state.chatSessions, rows);
        state.chatSessionsNextCursor = (page && page.nextCursor) || null;
        state.chatSessionsHasMore = !!(page && page.hasMore);
      }
    }, function (error) {
      if (!chatSessionPageRequestIsCurrent(
          searching, requestedQuery, searchRequestId, mutationGeneration)) return;
      if (error && error.status === 400) {
        if (searching) {
          state.chatSessionSearchRequestId += 1;
          var retryId = state.chatSessionSearchRequestId;
          state.chatSessionSearchLoading = true;
          return runChatSessionSearch(requestedQuery, retryId,
            state.chatSessionMutationGeneration);
        }
        if (state.chatSending) return;
        return loadChatSessions(function () {
          return !state.chatSending && chatSessionPageRequestIsCurrent(
            false, requestedQuery, searchRequestId, mutationGeneration);
        }, true);
      }
      throw error;
    });
  return request.then(function (value) {
    state.chatSessionsLoadingMore = false;
    renderChatSessionList();
    return value;
  }, function (error) {
    state.chatSessionsLoadingMore = false;
    renderChatSessionList();
    throw error;
  });
}

function runChatSessionSearch(query, requestId, mutationGeneration) {
  return api.listSessions({ limit: 50, q: query })
    .then(function (page) {
      if (requestId !== state.chatSessionSearchRequestId
          || mutationGeneration !== state.chatSessionMutationGeneration
          || (state.chatSessionSearch || "").trim() !== query) return;
      state.chatSessionSearchResults = [];
      appendSessionMetaRows(state.chatSessionSearchResults, (page && page.sessions) || []);
      state.chatSessionSearchCursor = (page && page.nextCursor) || null;
      state.chatSessionSearchHasMore = !!(page && page.hasMore);
      state.chatSessionSearchLoading = false;
      renderChatSessionList();
    }).catch(function () {
      if (requestId !== state.chatSessionSearchRequestId
          || mutationGeneration !== state.chatSessionMutationGeneration
          || (state.chatSessionSearch || "").trim() !== query) return;
      state.chatSessionSearchResults = [];
      state.chatSessionSearchCursor = null;
      state.chatSessionSearchHasMore = false;
      state.chatSessionSearchLoading = false;
      renderChatSessionList();
    });
}

function scheduleChatSessionSearch(query) {
  state.chatSessionSearch = String(query || "").substring(0, 200).trim();
  state.chatSessionSearchRequestId += 1;
  var requestId = state.chatSessionSearchRequestId;
  if (state.chatSessionSearchTimer) clearTimeout(state.chatSessionSearchTimer);
  if (!state.chatSessionSearch.trim()) {
    state.chatSessionSearchResults = null;
    state.chatSessionSearchCursor = null;
    state.chatSessionSearchHasMore = false;
    state.chatSessionSearchLoading = false;
    renderChatSessionList();
    return;
  }
  state.chatSessionSearchLoading = true;
  renderChatSessionList();
  state.chatSessionSearchTimer = setTimeout(function () {
    runChatSessionSearch(
      state.chatSessionSearch, requestId, state.chatSessionMutationGeneration);
  }, 200);
}

// Lazy-load a session's message bodies + summary (SPEC-CSP-FE-003).
function ensureSessionMessagesLoaded(session) {
  if (!session) return Promise.resolve(null);
  if (session.messagesLoaded && !session.reconciliationRequired) return Promise.resolve(session);
  if (session.messagesLoadPromise) return session.messagesLoadPromise;
  session.messagesLoading = true;
  session.messagesLoadError = null;
  session.messagesLoadPromise = api.getSession(session.id).then(function (full) {
    applyFullSession(session, full);
    return session;
  }).catch(function (err) {
    session.messages = session.messages || [];
    session.messagesLoaded = false;
    session.messagesLoadError = err && err.message ? err.message : t("common.unknownError");
    throw err;
  }).then(function (loaded) {
    session.messagesLoading = false;
    session.messagesLoadPromise = null;
    return loaded;
  }, function (err) {
    session.messagesLoading = false;
    session.messagesLoadPromise = null;
    throw err;
  });
  return session.messagesLoadPromise;
}

// Re-sort the cached list by updatedAt desc (server owns ordering; this keeps
// the local view consistent until the next listSessions()).
function resortChatSessions() {
  state.chatSessions.sort(function (a, b) {
    return String(b.updatedAt || "").localeCompare(String(a.updatedAt || ""));
  });
}

// Replace the cached row's meta/fields from a server Session/meta response.
function mergeSessionFields(session, fresh) {
  if (!session || !fresh) return;
  if (fresh.createdAt != null) session.createdAt = fresh.createdAt;
  if (fresh.title != null) session.title = fresh.title;
  if (fresh.updatedAt != null) session.updatedAt = fresh.updatedAt;
  if (fresh.source != null) session.source = fresh.source;
  if (fresh.summary != null) session.summary = fresh.summary;
  if (fresh.contextLabel != null) session.contextLabel = fresh.contextLabel;
  if (Object.prototype.hasOwnProperty.call(fresh, "contextSnapshot")) {
    session.contextSnapshot = fresh.contextSnapshot;
  }
  if (fresh.memoryPolicy != null) session.memoryPolicy = fresh.memoryPolicy;
  if (fresh.lastMessagePreview != null) session.lastMessagePreview = fresh.lastMessagePreview;
  if (fresh.messageCount != null) session.messageCount = fresh.messageCount;
}

function normalizeSuggestedTasks(tasks) {
  if (!Array.isArray(tasks)) return [];
  var normalized = [];
  for (var i = 0; i < tasks.length && normalized.length < 20; i++) {
    var item = tasks[i];
    if (typeof item === "string") item = { title: item };
    if (!item || typeof item !== "object") continue;
    var title = item.title || item.task;
    if (title == null || !String(title).trim()) continue;
    var copy = {};
    for (var key in item) {
      if (Object.prototype.hasOwnProperty.call(item, key)) copy[key] = item[key];
    }
    copy.title = String(title).substring(0, 256);
    if (copy.notes != null) copy.notes = String(copy.notes).substring(0, 2048);
    normalized.push(copy);
  }
  return normalized;
}

function mergeMessageFields(message, fresh) {
  if (!message || !fresh) return;
  var fields = ["id", "role", "content", "status", "error", "createdAt", "contextSnapshot"];
  for (var i = 0; i < fields.length; i++) {
    var field = fields[i];
    if (Object.prototype.hasOwnProperty.call(fresh, field)) message[field] = fresh[field];
  }
  if (Object.prototype.hasOwnProperty.call(fresh, "suggestedTasks")) {
    message.suggestedTasks = normalizeSuggestedTasks(fresh.suggestedTasks);
  }
}

function applyFullSession(session, full) {
  if (!session || !full) return session;
  mergeSessionFields(session, full);
  session.messages = (full.messages || []).map(function (message) {
    message.suggestedTasks = normalizeSuggestedTasks(message.suggestedTasks);
    return message;
  });
  session.messagesLoaded = true;
  session.messagesLoadError = null;
  session.reconciliationRequired = false;
  session.messageCount = session.messages.length;
  var last = session.messages.length ? session.messages[session.messages.length - 1] : null;
  session.lastMessagePreview = last && last.content
    ? String(last.content).trim().substring(0, 80) : "";
  return session;
}

function refreshCanonicalSession(session) {
  if (!session || !api.getSession) return Promise.resolve(session);
  return api.getSession(session.id).then(function (full) {
    applyFullSession(session, full);
    resortChatSessions();
    return session;
  });
}

function findSessionMessage(session, messageId) {
  if (!session || !session.messages) return null;
  for (var i = 0; i < session.messages.length; i++) {
    if (session.messages[i].id === messageId) return session.messages[i];
  }
  return null;
}

function freezeSessionForReconciliation(session, message, error) {
  message.persistenceError = error && error.message ? error.message : String(error);
  session.reconciliationRequired = true;
  session.messagesLoaded = false;
  session.messagesLoadError = t("chat.reconciliationPending", {
    msg: message.persistenceError,
  });
  renderChatTab();
  return message;
}

function markAssistantPersistenceError(session, message, error) {
  message.status = "error";
  message.content = formatChatErrorMessage(error);
  message.error = error && error.message ? error.message : String(error);
  message.suggestedTasks = [];
  renderChatTab();
  return api.updateMessage(session.id, message.id, {
    status: "error",
    content: message.content,
    error: message.error,
  }).then(function (updated) {
    noteChatSessionMutation();
    mergeMessageFields(message, updated);
    return refreshCanonicalSession(session).catch(function (refreshError) {
      return freezeSessionForReconciliation(session, message, refreshError);
    })
      .then(function () {
        resortChatSessions();
        renderChatTab();
        return message;
      });
  }).catch(function (updateError) {
    return api.getSession
      ? freezeSessionForReconciliation(session, message, updateError)
      : message;
  });
}

function reconcileAmbiguousAssistantUpdate(session, message, persistenceError) {
  if (!api.getSession) {
    return markAssistantPersistenceError(session, message, persistenceError);
  }
  return refreshCanonicalSession(session).then(function () {
    var canonical = findSessionMessage(session, message.id);
    if (canonical && canonical.status === "sent") {
      noteChatSessionMutation();
      renderChatTab();
      return canonical;
    }
    return markAssistantPersistenceError(
      session, canonical || message, persistenceError);
  }).catch(function () {
    // The model reply is known, but neither persistence nor a canonical reload can
    // be confirmed. Freeze this session until a later GET reconciles the transcript.
    return freezeSessionForReconciliation(session, message, persistenceError);
  });
}

function syncSessionMessageCache(session, appended) {
  var combined = (session.messages || []).concat(appended || []);
  if (combined.length > 200) {
    var from = combined.length - 200;
    while (from < combined.length && combined[from].role !== "user") from++;
    if (from >= combined.length) from = combined.length - 200;
    combined = combined.slice(from);
  }
  session.messages = combined;
  session.messageCount = session.messages.length;
  var last = session.messages.length ? session.messages[session.messages.length - 1] : null;
  var content = last && last.content ? String(last.content).trim() : "";
  session.lastMessagePreview = content.substring(0, 80);
  if (last) session.updatedAt = last.createdAt || new Date().toISOString();
}

function getActiveChatSession() {
  if (!state.activeChatSessionId) return null;
  for (var i = 0; i < state.chatSessions.length; i++) {
    if (state.chatSessions[i].id === state.activeChatSessionId) return state.chatSessions[i];
  }
  var searchRows = state.chatSessionSearchResults || [];
  for (var j = 0; j < searchRows.length; j++) {
    if (searchRows[j].id === state.activeChatSessionId) {
      state.chatSessions.push(searchRows[j]);
      return searchRows[j];
    }
  }
  return null;
}

// Returns a Promise resolving to the active session, creating one if none.
function ensureActiveChatSession() {
  var s = getActiveChatSession();
  if (s) return Promise.resolve(s);
  if (state.chatSessions && state.chatSessions.length > 0) {
    state.activeChatSessionId = state.chatSessions[0].id;
    if (api.setActiveSession) api.setActiveSession(state.activeChatSessionId).catch(function () {});
    return Promise.resolve(state.chatSessions[0]);
  }
  if (state.chatSessionsLoaded === false) {
    return Promise.reject(new Error(state.chatSessionsLoadError || t("chat.loadSessionFailed")));
  }
  return createChatSession({ title: t("chat.currentSession") });
}

// Create a session via the backend; returns a Promise resolving to it.
function createChatSession(opts) {
  return api.createSession({
    title: (opts && opts.title) || t("chat.newSessionDefault"),
    source: (opts && opts.source) || "manual",
    contextLabel: opts && opts.contextLabel,
    contextSnapshot: (opts && opts.contextSnapshot) || null,
    initialMessages: (opts && opts.initialMessages) || null,
  }).then(function (session) {
    noteChatSessionMutation();
    session.messages = session.messages || [];
    session.messagesLoaded = true;
    state.chatSessions.unshift(session);
    state.activeChatSessionId = session.id;
    return session;
  });
}

function deleteChatSession(id) {
  return api.deleteSession(id).then(function (resp) {
    noteChatSessionMutation();
    state.chatSessions = state.chatSessions.filter(function (s) { return s.id !== id; });
    if (state.chatSessionSearchResults) {
      state.chatSessionSearchResults = state.chatSessionSearchResults.filter(function (s) {
        return s.id !== id;
      });
    }
    state.activeChatSessionId = (resp && resp.activeSessionId) || null;
    return resp;
  });
}

// ---- Message Formatting ----

function formatChatMessageContent(content) {
  var structured = parseStructuredChatContent(content);
  if (structured) return renderStructuredChatContent(structured);
  return escHtml(content).replace(/\n/g, "<br>");
}

function parseStructuredChatContent(content) {
  if (typeof content !== "string") return null;
  var text = content.trim();
  if (!text) return null;

  var fence = text.match(/^```(?:json)?\s*([\s\S]*?)\s*```$/i);
  if (fence) text = fence[1].trim();

  var parsed = tryParseJson(text);
  if (!parsed) {
    var arrayStart = text.indexOf("[");
    var arrayEnd = text.lastIndexOf("]");
    var objectStart = text.indexOf("{");
    var objectEnd = text.lastIndexOf("}");
    if (arrayStart >= 0 && arrayEnd > arrayStart) {
      parsed = tryParseJson(text.substring(arrayStart, arrayEnd + 1));
    } else if (objectStart >= 0 && objectEnd > objectStart) {
      parsed = tryParseJson(text.substring(objectStart, objectEnd + 1));
    }
  }

  if (!parsed) return null;
  if (Array.isArray(parsed)) {
    var items = parsed.filter(isStructuredSummaryItem);
    return items.length > 0 ? items : null;
  }
  return isStructuredSummaryItem(parsed) ? [parsed] : null;
}

function tryParseJson(text) {
  try {
    return JSON.parse(text);
  } catch (e) {
    return null;
  }
}

function renderChatMemoryPanel() {
  var root = document.getElementById("chat-memory-content");
  var session = getActiveChatSession();
  if (!root) return;
  if (state.memoryLoading) {
    root.textContent = t("memory.loading");
    return;
  }
  if (state.memoryLoadError) {
    root.textContent = t("memory.loadFailed", { msg: state.memoryLoadError });
    return;
  }
  if (!session) {
    root.textContent = t("memory.noSession");
    return;
  }
  var policy = session.memoryPolicy || "smart";
  var related = state.memoryItems.filter(function (m) {
    return m.sourceSessionId === session.id || m.status === "pending";
  }).slice(0, 6);
  var pendingCount = state.pendingMemoryCount || 0;
  var html = '<div class="memory-policy-row">' +
    '<select id="session-memory-policy">' +
    '<option value="smart"' + (policy === "smart" ? " selected" : "") + '>' + escHtml(t("memory.policy.smart")) + '</option>' +
    '<option value="confirm_all"' + (policy === "confirm_all" ? " selected" : "") + '>' + escHtml(t("memory.policy.confirmAll")) + '</option>' +
    '<option value="off"' + (policy === "off" ? " selected" : "") + '>' + escHtml(t("memory.policy.off")) + '</option>' +
    '</select><span class="memory-pending-count">' + escHtml(t("memory.pendingCount", { n: pendingCount })) + '</span></div>';
  html += '<textarea id="memory-draft-input" class="memory-draft-input" rows="2" placeholder="' + escHtml(t("memory.addPlaceholder")) + '">' + escHtml(state.memoryDraft || "") + '</textarea>';
  html += '<button class="btn btn-sm btn-outline" id="memory-add-btn">' + escHtml(t("memory.add")) + '</button>';
  if (related.length === 0) {
    html += '<div class="memory-empty">' + escHtml(t("memory.empty")) + '</div>';
  } else {
    html += '<div class="memory-list">';
    related.forEach(function (m) {
      var actions = "";
      if (m.status === "pending") {
        actions = '<button class="btn btn-sm btn-primary memory-edit-approve">' + escHtml(t("memory.editApprove")) + '</button>' +
          '<button class="btn btn-sm btn-outline memory-approve">' + escHtml(t("memory.approve")) + '</button>' +
          '<button class="btn btn-sm btn-outline memory-reject">' + escHtml(t("memory.reject")) + '</button>';
      } else if (m.status === "active") {
        actions = '<button class="btn btn-sm btn-outline memory-panel-edit">' + escHtml(t("memory.edit")) + '</button>' +
          '<button class="btn btn-sm btn-outline memory-panel-disable">' + escHtml(t("memory.disable")) + '</button>';
      }
      html += '<div class="memory-item status-' + escHtml(m.status || "") + '" data-mid="' + escHtml(m.id || "") + '">' +
        '<div class="memory-content">' + escHtml(m.content || "") + '</div>' +
        '<div class="memory-evidence">' + escHtml(m.evidence || "") + '</div>' +
        actions +
        '</div>';
    });
    html += '</div>';
  }
  root.innerHTML = html;
  bindChatMemoryPanel(session);
}

function memoryById(id) {
  return state.memoryItems.find(function (m) { return m.id === id; });
}

function promptMemoryPatch(item) {
  if (!item) return null;
  var content = prompt(t("memory.editContent"), item.content || "");
  if (content == null) return null;
  content = content.trim();
  if (!content) return null;
  var evidence = prompt(t("memory.editEvidence"), item.evidence || "");
  if (evidence == null) return null;
  return { content: content, evidence: evidence.trim() };
}

function refreshChatMemoryAfterUpdate() {
  return loadMemoryForChat().then(renderChatTab);
}

function bindChatMemoryPanel(session) {
  var policy = document.getElementById("session-memory-policy");
  if (policy) {
    policy.onchange = function () {
      api.setSessionMemoryPolicy(session.id, policy.value).then(function (fresh) {
        mergeSessionFields(session, fresh);
        session.memoryPolicy = fresh.memoryPolicy;
        renderChatTab();
      }).catch(function (err) {
        alert(t("memory.saveFailed", { msg: err.message }));
      });
    };
  }
  var addBtn = document.getElementById("memory-add-btn");
  var draft = document.getElementById("memory-draft-input");
  if (addBtn && draft) {
    draft.value = state.memoryDraft || "";
    draft.oninput = function () {
      state.memoryDraft = draft.value;
    };
    addBtn.onclick = function () {
      var text = draft.value.trim();
      if (!text) return;
      api.createSessionMemory(session.id, {
        type: "note",
        content: text,
        evidence: t("memory.manualEvidence"),
        status: "active",
      }).then(function () {
        state.memoryDraft = "";
        return loadMemoryForChat();
      }).then(renderChatTab).catch(function (err) {
        alert(t("memory.saveFailed", { msg: err.message }));
      });
    };
  }
  var approve = document.querySelectorAll(".memory-approve");
  for (var i = 0; i < approve.length; i++) {
    approve[i].onclick = function () {
      var id = this.closest(".memory-item").dataset.mid;
      api.updateMemory(id, { status: "active" })
        .then(refreshChatMemoryAfterUpdate)
        .catch(function (err) { alert(t("memory.saveFailed", { msg: err.message })); });
    };
  }
  var editApprove = document.querySelectorAll(".memory-edit-approve");
  for (var k = 0; k < editApprove.length; k++) {
    editApprove[k].onclick = function () {
      var id = this.closest(".memory-item").dataset.mid;
      var patch = promptMemoryPatch(memoryById(id));
      if (!patch) return;
      patch.status = "active";
      api.updateMemory(id, patch)
        .then(refreshChatMemoryAfterUpdate)
        .catch(function (err) { alert(t("memory.saveFailed", { msg: err.message })); });
    };
  }
  var edit = document.querySelectorAll(".memory-panel-edit");
  for (var e = 0; e < edit.length; e++) {
    edit[e].onclick = function () {
      var id = this.closest(".memory-item").dataset.mid;
      var patch = promptMemoryPatch(memoryById(id));
      if (!patch) return;
      api.updateMemory(id, patch)
        .then(refreshChatMemoryAfterUpdate)
        .catch(function (err) { alert(t("memory.saveFailed", { msg: err.message })); });
    };
  }
  var disable = document.querySelectorAll(".memory-panel-disable");
  for (var d = 0; d < disable.length; d++) {
    disable[d].onclick = function () {
      var id = this.closest(".memory-item").dataset.mid;
      api.updateMemory(id, { status: "disabled" })
        .then(refreshChatMemoryAfterUpdate)
        .catch(function (err) { alert(t("memory.saveFailed", { msg: err.message })); });
    };
  }
  var reject = document.querySelectorAll(".memory-reject");
  for (var j = 0; j < reject.length; j++) {
    reject[j].onclick = function () {
      var id = this.closest(".memory-item").dataset.mid;
      api.updateMemory(id, { status: "rejected" })
        .then(refreshChatMemoryAfterUpdate)
        .catch(function (err) { alert(t("memory.saveFailed", { msg: err.message })); });
    };
  }
}

function isStructuredSummaryItem(item) {
  if (!item || typeof item !== "object" || Array.isArray(item)) return false;
  return typeof item.headline === "string" ||
    typeof item.insight === "string" ||
    typeof item.suggestion === "string";
}

function renderStructuredChatContent(items) {
  var limit = 6;
  var html = '<div class="chat-structured-response">';
  for (var i = 0; i < Math.min(items.length, limit); i++) {
    var item = items[i];
    html += '<div class="structured-response-item">';
    if (item.headline) {
      html += '<div class="structured-response-headline">' + escHtml(item.headline) + '</div>';
    }
    if (item.insight) {
      html += '<div class="structured-response-row"><span>' + escHtml(t("timeline.insight")) + '</span><p>' + escHtml(item.insight) + '</p></div>';
    }
    if (item.suggestion) {
      html += '<div class="structured-response-row"><span>' + escHtml(t("timeline.suggestion")) + '</span><p>' + escHtml(item.suggestion) + '</p></div>';
    }
    if (item.confidence) {
      html += '<div class="structured-response-meta">' + escHtml(t("chat.confidence", { v: item.confidence })) + '</div>';
    }
    html += '</div>';
  }
  if (items.length > limit) {
    html += '<div class="structured-response-more">' + escHtml(t("chat.moreSummaries", { n: items.length - limit })) + '</div>';
  }
  html += '</div>';
  return html;
}

function formatChatErrorMessage(err) {
  var reason = err && err.message ? err.message : String(err || t("common.unknownError"));
  return t("chat.sendFailed", { msg: reason });
}

function getLatestAssistantTurnIndex(messages) {
  var latestUserIdx = -1;
  for (var i = messages.length - 1; i >= 0; i--) {
    if (messages[i].role === "user") {
      latestUserIdx = i;
      break;
    }
  }
  if (latestUserIdx < 0) return -1;
  for (var j = messages.length - 1; j > latestUserIdx; j--) {
    if (messages[j].role === "assistant") return j;
  }
  return -1;
}

// ---- Render ----

function renderChatTab() {
  renderChatSessionList();
  renderChatThread();
  renderChatContextPanel();
  updateChatInputState();
}

function renderChatSessionList() {
  var list = state.dom.chatSessionList;
  var search = state.chatSessionSearch || "";
  if (search && state.chatSessionSearchLoading) {
    list.innerHTML = '<div class="chat-session-empty">' + escHtml(t("chat.searching")) + '</div>';
    return;
  }
  // Paged search is performed against the full server index, not just loaded rows.
  var filtered = search ? (state.chatSessionSearchResults || []) : state.chatSessions;

  if (filtered.length === 0) {
    list.innerHTML = '<div class="chat-session-empty">' + escHtml(search ? t("chat.noMatch") : t("chat.emptyHint")) + '</div>';
    return;
  }

  var html = "";
  for (var i = 0; i < filtered.length; i++) {
    var s = filtered[i];
    var active = s.id === state.activeChatSessionId;
    // Prefer the index preview; fall back to last loaded message if present.
    var preview = s.lastMessagePreview || "";
    if (!preview && s.messages && s.messages.length > 0) {
      var lastMsg = s.messages[s.messages.length - 1];
      preview = (lastMsg.content || "").substring(0, 40);
      if (lastMsg.content && lastMsg.content.length > 40) preview += "...";
    }
    html += '<div class="chat-session-item' + (active ? " active" : "") + '" data-sid="' + s.id + '">' +
      '<div class="session-title">' + escHtml(s.title) + '</div>' +
      (preview ? '<div class="session-preview">' + escHtml(preview) + '</div>' : "") +
      '<div class="session-time">' + formatRelativeTime(s.updatedAt) + '</div>' +
      '<button class="session-delete-btn" data-action="delete-session" title="' + escHtml(t("chat.deleteSessionTitle")) + '">' +
      '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>' +
      '</button>' +
      '</div>';
  }
  var hasMore = search ? state.chatSessionSearchHasMore : state.chatSessionsHasMore;
  if (hasMore) {
    html += '<button class="btn btn-secondary chat-load-more" data-action="load-more-sessions">' +
      escHtml(state.chatSessionsLoadingMore ? t("common.loadingEllipsis") : t("chat.loadMore")) +
      '</button>';
  }
  list.innerHTML = html;

  // Click delegation: select session or delete
  list.onclick = function (e) {
    var loadMore = e.target.closest("[data-action='load-more-sessions']");
    if (loadMore) {
      loadMoreChatSessions().catch(function (err) {
        alert(t("chat.loadMoreFailed", { msg: err && err.message ? err.message : err }));
      });
      return;
    }
    var deleteBtn = e.target.closest("[data-action='delete-session']");
    if (deleteBtn) {
      e.stopPropagation();
      var item = deleteBtn.closest(".chat-session-item");
      var sid = item ? item.dataset.sid : null;
      if (sid && confirm(t("chat.confirmDeleteSession"))) {
        deleteChatSession(sid).then(renderChatTab).catch(function (err) {
          alert(t("chat.deleteSessionFailed", { msg: err.message }));
        });
      }
      return;
    }
    var item = e.target.closest(".chat-session-item");
    if (item) {
      var sid = item.dataset.sid;
      state.activeChatSessionId = sid;
      api.setActiveSession(sid).catch(function () { /* best-effort pointer */ });
      var selected = getActiveChatSession();
      renderChatTab();
      ensureSessionMessagesLoaded(selected).then(renderChatTab).catch(renderChatTab);
    }
  };
}

function renderChatThread() {
  var session = getActiveChatSession();
  state.dom.chatSessionTitle.textContent = session
    ? session.title : t("chat.selectOrCreate");
  if (typeof renderDeepChatThread === "function" && renderDeepChatThread()) {
    if (session && !session.messagesLoaded && !session.messagesLoadError &&
        !session.messagesLoadPromise) {
      ensureSessionMessagesLoaded(session).then(function () {
        if (getActiveChatSession() === session) renderChatTab();
      }).catch(function () {
        if (getActiveChatSession() === session) renderChatTab();
      });
    }
    return;
  }
  renderLegacyChatThread();
}

function renderLegacyChatThread() {
  var thread = state.dom.chatThread;
  var session = getActiveChatSession();

  if (!session) {
    thread.innerHTML = '<div class="chat-welcome"><p>' + escHtml(t("chat.selectOrCreateThread")) + '</p><p class="chat-welcome-hint">' + escHtml(t("chat.selectFromListHint")) + '</p></div>';
    state.dom.chatSessionTitle.textContent = t("chat.selectOrCreate");
    return;
  }

  state.dom.chatSessionTitle.textContent = session.title;

  // Bodies are lazy-loaded; show a transient state and fetch on demand.
  if (!session.messagesLoaded) {
    if (session.messagesLoadError) {
      thread.innerHTML = '<div class="chat-welcome"><p>' +
        escHtml(t("chat.loadSessionFailed", { msg: session.messagesLoadError })) + '</p></div>';
    } else {
      thread.innerHTML = '<div class="chat-welcome"><p>' + escHtml(t("common.loadingEllipsis")) + '</p></div>';
      ensureSessionMessagesLoaded(session).then(function () {
        if (getActiveChatSession() === session) renderChatTab();
      }).catch(function () {
        if (getActiveChatSession() === session) renderChatTab();
      });
    }
    return;
  }

  if (session.messages.length === 0) {
    thread.innerHTML = '<div class="chat-welcome"><p>' + escHtml(t("chat.welcomeTitle")) + '</p><p class="chat-welcome-hint">' + escHtml(t("chat.welcomeHint")) + '</p></div>';
    return;
  }

  var html = "";
  var retryableAssistantIdx = state.chatSending
    ? -1
    : getLatestAssistantTurnIndex(session.messages);
  for (var i = 0; i < session.messages.length; i++) {
    var m = session.messages[i];
    var cls = "chat-tab-message " + m.role + (m.status === "pending" ? " pending" : "") + (m.status === "error" ? " error" : "");
    html += '<div class="' + cls + '">';
    var visibleContent = m.content || (m.status === "error" ? m.error : "") || "";
    html += '<div class="msg-content">' + formatChatMessageContent(visibleContent) + '</div>';
    if (i === retryableAssistantIdx &&
        (m.status === "error" || m.status === "pending")) {
      html += '<div class="msg-retry" data-mid="' + m.id + '">' + escHtml(t("chat.retry")) + '</div>';
    }
    // Suggested tasks
    m.suggestedTasks = normalizeSuggestedTasks(m.suggestedTasks);
    if (m.suggestedTasks.length > 0) {
      html += '<div class="chat-suggested-tasks">';
      for (var taskIndex = 0; taskIndex < m.suggestedTasks.length; taskIndex++) {
        var st = m.suggestedTasks[taskIndex];
        html += '<div class="chat-suggested-task-row">' +
          '<span class="task-title">' + escHtml(st.title) + '</span>' +
          '<button class="btn btn-sm btn-primary create-suggested-task" data-title="' + escHtml(st.title) + '" data-notes="' + escHtml(st.notes || "") + '" data-priority="' + escHtml(st.priority || "medium") + '">' + escHtml(t("chat.createTask")) + '</button>' +
          '</div>';
      }
      html += '</div>';
    }
    html += '</div>';
  }
  thread.innerHTML = html;
  thread.scrollTop = thread.scrollHeight;

  // Bind retry buttons
  var retries = thread.querySelectorAll(".msg-retry");
  for (var r = 0; r < retries.length; r++) {
    retries[r].addEventListener("click", function () {
      retryChatMessage(this.dataset.mid);
    });
  }

  // Bind suggested task buttons
  var taskBtns = thread.querySelectorAll(".create-suggested-task");
  for (var b = 0; b < taskBtns.length; b++) {
    taskBtns[b].addEventListener("click", function () {
      createSuggestedTask(this.dataset.title, this.dataset.notes, this.dataset.priority, this);
    });
  }
}

function renderChatContextPanel() {
  var session = getActiveChatSession();

  // Current status
  var statusDiv = state.dom.chatContextSummary.querySelector(".chat-context-content");
  var summary = state.summary;
  if (summary && summary.current && summary.current.headline) {
    statusDiv.innerHTML = '<div>' + escHtml(summary.current.headline) + '</div>' +
      (summary.current.evidence ? '<div style="font-size:11px;color:var(--text-muted);margin-top:4px">' + escHtml(summary.current.evidence.slice(0, 2).join("; ")) + '</div>' : "");
  } else {
    statusDiv.textContent = t("common.noData");
  }

  // Recent activity
  var actDiv = state.dom.chatRecentActivity.querySelector(".chat-context-content");
  var timeline = summary && summary.timeline;
  if (timeline && timeline.length > 0) {
    var actHtml = "";
    for (var i = 0; i < Math.min(4, timeline.length); i++) {
      var timelineEntry = timeline[i];
      actHtml += '<div class="activity-item"><strong>' + escHtml(timelineEntry.label) + '</strong> ' + escHtml((timelineEntry.headline || "").substring(0, 30)) + '</div>';
    }
    actDiv.innerHTML = actHtml;
  } else {
    actDiv.textContent = t("chat.noActivitySummary");
  }

  // Task suggestions -- from last assistant message with suggestedTasks
  var taskDiv = state.dom.chatTaskSuggestions.querySelector(".chat-context-content");
  taskDiv.textContent = t("chat.noSuggestedTasks");
  if (session && session.messages.length > 0) {
    var lastAssistantTasks = null;
    for (var m = session.messages.length - 1; m >= 0; m--) {
      if (session.messages[m].role === "assistant" && session.messages[m].suggestedTasks) {
        lastAssistantTasks = session.messages[m].suggestedTasks;
        break;
      }
    }
    if (lastAssistantTasks && lastAssistantTasks.length > 0) {
      var tHtml = "";
      for (var taskIdx = 0; taskIdx < lastAssistantTasks.length; taskIdx++) {
        tHtml += '<div class="chat-suggested-task-row"><span class="task-title">' + escHtml(lastAssistantTasks[taskIdx].title) + '</span></div>';
      }
      taskDiv.innerHTML = tHtml;
    }
  }
  renderChatMemoryPanel();
}

function updateChatInputState() {
  var llmOk = state.status && state.status.llm && state.status.llm.configured;
  var session = getActiveChatSession();
  var sessionReady = session && session.messagesLoaded && !session.messagesLoading;
  var enabled = !!llmOk && !!sessionReady && !state.chatSending;
  var placeholder = llmOk
    ? t("chat.composerPlaceholder") : t("chat.llmNotConfiguredPlaceholder");
  if (typeof updateDeepChatInputState === "function") {
    updateDeepChatInputState(enabled, placeholder);
  }
  if (state.dom.chatTabInput) {
    state.dom.chatTabInput.disabled = !enabled;
    state.dom.chatTabInput.placeholder = placeholder;
  }
  if (state.dom.chatTabSendBtn) {
    state.dom.chatTabSendBtn.disabled = !enabled;
  }
}

// ---- Send Message ----

function sendChatTabMessage(request) {
  if (state.chatSending) return Promise.resolve({ skipped: true });
  var deepChatRequest = request && request.source === "deep-chat";
  var input = state.dom.chatTabInput;
  var text = deepChatRequest
    ? String(request.text || "").trim()
    : String(input && input.value || "").trim();
  if (!text) return Promise.resolve({ skipped: true });

  var outcome = {
    message: null,
    error: null,
    inputPersisted: false,
    sessionId: null,
  };

  invalidateChatSessionLoads();
  state.chatSending = true;
  renderChatTab();

  var inputPersisted = false;
  return ensureActiveChatSession().then(function (session) {
    outcome.sessionId = session.id;
    return ensureSessionMessagesLoaded(session);
  }).then(function (session) {
    var context = buildChatContext(session);
    var userMsg = { role: "user", content: text, status: "sent", contextSnapshot: context };
    var pendingMsg = { role: "assistant", content: t("chat.thinking"), status: "pending" };

    // Persist user + pending assistant; adopt server-assigned ids into cache.
    return api.appendMessages(session.id, { messages: [userMsg, pendingMsg] })
      .then(function (appended) {
        var savedUser = appended[0];
        var savedPending = appended[1];
        if (!savedUser || !savedPending) throw new Error("Invalid append response");
        noteChatSessionMutation();
        if (input) input.value = "";
        inputPersisted = true;
        outcome.inputPersisted = true;
        syncSessionMessageCache(session, [savedUser, savedPending]);
        resortChatSessions();
        renderChatTab();
        backfillSessionTitle(session, text);

        if (deepChatRequest && request.onExecutionStart) {
          request.onExecutionStart(session.id, savedUser.id);
        }

        var executionRequest = deepChatRequest && request.streaming && api.postChatStream
          ? api.postChatStream(text, context, session.id, savedUser.id, {
              signal: request.signal,
              onEvent: function (eventName, payload) {
                if (eventName === "delta" && request.onDelta && payload && payload.text) {
                  request.onDelta(String(payload.text));
                }
              },
            })
          : api.postChat(text, context, session.id, savedUser.id);
        return executionRequest.then(function (resp) {
          var content = resp.message || resp.reply || resp.content || t("chat.agentNoContent");
          var tasks = normalizeSuggestedTasks(
            resp.suggestedTasks || resp.suggested_tasks || resp.tasks || []);
          savedPending.status = "sent";
          savedPending.content = content;
          savedPending.error = null;
          savedPending.suggestedTasks = tasks;
          renderChatTab();
          return api.updateMessage(session.id, savedPending.id,
            { status: "sent", content: content, suggestedTasks: tasks }).then(function (updated) {
              noteChatSessionMutation();
              mergeMessageFields(savedPending, updated);
              syncSessionMessageCache(session, []);
              renderChatTab();
              refreshMemoryPanelSoon();
              return refreshCanonicalSession(session).catch(function () { return session; });
            }, function (persistenceError) {
              outcome.error = persistenceError;
              return reconcileAmbiguousAssistantUpdate(
                session, savedPending, persistenceError);
            }).then(function () {
              outcome.message = findSessionMessage(session, savedPending.id) || savedPending;
              return outcome.message;
            });
        }, function (executionError) {
          // The model call failed, so pending -> error is unambiguous and retryable.
          outcome.error = executionError;
          return markAssistantPersistenceError(session, savedPending, executionError)
            .then(function (message) {
              outcome.message = message || findSessionMessage(session, savedPending.id) || savedPending;
              return outcome.message;
            });
        });
      });
  }).catch(function (err) {
    if (!outcome.error) outcome.error = err;
    if (!inputPersisted && input && !input.value) input.value = text;
    if (!deepChatRequest) {
      alert(t("chat.sendFailed", { msg: (err && err.message ? err.message : err) }));
    }
  }).then(function () {
    state.chatSending = false;
    renderChatTab();
    return refreshChatSessionListIfNeeded().then(function () {
      renderChatTab();
      return outcome;
    });
  });
}

// Backfill a default-titled session from its first user message (SPEC-CSP-FE-004).
function backfillSessionTitle(session, text) {
  if (!session || session.title !== t("chat.newSessionDefault")) return;
  var txt = (text || "").replace(/\n/g, " ").trim().substring(0, 18);
  if (!txt) return;
  api.updateSession(session.id, { title: txt }).then(function (fresh) {
    noteChatSessionMutation();
    mergeSessionFields(session, fresh);
    renderChatSessionList();
  }).catch(function () { /* non-fatal */ });
}

function retryChatMessage(msgId, options) {
  if (state.chatSending) return;
  var session = getActiveChatSession();
  if (!session) return;
  // Only the assistant for the latest user turn can be replayed safely.
  var retryIdx = -1;
  for (var i = 0; i < session.messages.length; i++) {
    if (session.messages[i].id === msgId) { retryIdx = i; break; }
  }
  if (retryIdx < 0 || retryIdx !== getLatestAssistantTurnIndex(session.messages)) return;
  var pendingMsg = session.messages[retryIdx];
  if (pendingMsg.role !== "assistant" ||
      (pendingMsg.status !== "pending" && pendingMsg.status !== "error")) return;
  // Find preceding user message
  var userMsg = null;
  for (var j = retryIdx - 1; j >= 0; j--) {
    if (session.messages[j].role === "user") { userMsg = session.messages[j]; break; }
  }
  if (!userMsg) return;

  invalidateChatSessionLoads();
  state.chatSending = true;
  pendingMsg.status = "pending";
  pendingMsg.content = t("chat.thinking");
  pendingMsg.error = null;
  renderChatTab();

  return api.updateMessage(session.id, pendingMsg.id,
    { status: "pending", content: pendingMsg.content }).then(function (updated) {
    noteChatSessionMutation();
    mergeMessageFields(pendingMsg, updated);
    var streamedContent = "";
    var retryRequest = options && options.streaming && api.postChatStream
      ? api.postChatStream(
          userMsg.content,
          userMsg.contextSnapshot || buildChatContext(session),
          session.id,
          userMsg.id,
          {
            signal: options.signal,
            onEvent: function (eventName, payload) {
              if (eventName !== "delta" || !payload || !payload.text) return;
              streamedContent += String(payload.text);
              pendingMsg.content = streamedContent;
              renderChatTab();
            },
          })
      : api.postChat(
          userMsg.content,
          userMsg.contextSnapshot || buildChatContext(session),
          session.id,
          userMsg.id);
    return retryRequest.then(function (resp) {
      var content = resp.message || resp.reply || resp.content || t("chat.agentNoContent");
      var tasks = normalizeSuggestedTasks(
        resp.suggestedTasks || resp.suggested_tasks || resp.tasks || []);
      pendingMsg.status = "sent";
      pendingMsg.content = content;
      pendingMsg.error = null;
      pendingMsg.suggestedTasks = tasks;
      return api.updateMessage(session.id, pendingMsg.id,
        { status: "sent", content: content, suggestedTasks: tasks }).then(function (sent) {
          noteChatSessionMutation();
          mergeMessageFields(pendingMsg, sent);
          syncSessionMessageCache(session, []);
          refreshMemoryPanelSoon();
          return refreshCanonicalSession(session).catch(function () { return session; });
        }, function (persistenceError) {
          return reconcileAmbiguousAssistantUpdate(
            session, pendingMsg, persistenceError);
        });
    }, function (executionError) {
      return markAssistantPersistenceError(session, pendingMsg, executionError);
    });
  }).catch(function (err) {
    // This catch is for the initial pending PUT (or an unexpected local error).
    return markAssistantPersistenceError(session, pendingMsg, err);
  }).then(function () {
    state.chatSending = false;
    renderChatTab();
    return refreshChatSessionListIfNeeded().then(function () {
      renderChatTab();
    });
  });
}

function buildChatContext(session) {
  var ctx = { type: "global", title: session.title };
  if (session && session.contextSnapshot != null) {
    ctx.boundContext = session.contextSnapshot;
  }
  if (state.chatContextToggles.currentStatus && state.summary && state.summary.current) {
    var currentEvidence = Array.isArray(state.summary.current.evidence)
      ? state.summary.current.evidence : [];
    ctx.currentStatus = {
      headline: state.summary.current.headline,
      evidence: currentEvidence.slice(0, 3).map(function (item) {
        return String(item || "").substring(0, 300);
      }),
    };
  }
  if (state.chatContextToggles.futureTasks && state.tasks) {
    var open = state.tasks.filter(function (t) { return t.status === "open"; }).slice(0, 10);
    ctx.futureTasks = open.map(function (task) {
      return {
        id: task.id,
        title: String(task.title || "").substring(0, 200),
        notes: String(task.notes || "").substring(0, 300),
        priority: task.priority,
        dueAt: task.dueAt || null,
      };
    });
  }
  if (state.summary && Array.isArray(state.summary.timeline)) {
    ctx.recentActivity = state.summary.timeline.slice(0, 4).map(function (entry) {
      return {
        key: entry.key,
        label: entry.label,
        headline: String(entry.headline || "").substring(0, 300),
        evidence: (Array.isArray(entry.evidence) ? entry.evidence : []).slice(0, 3).map(function (item) {
          return String(item || "").substring(0, 300);
        }),
      };
    });
  }
  return ctx;
}

function refreshMemoryPanelSoon() {
  setTimeout(function () {
    loadMemoryForChat().then(function () {
      renderChatMemoryPanel();
    });
  }, 1500);
}

function createSuggestedTask(title, notes, priority, btn) {
  if (btn) btn.disabled = true;
  return api.createTask({ title: title, notes: notes, priority: priority, source: "chat" }).then(function () {
    if (btn) { btn.textContent = t("task.created"); btn.disabled = true; }
    loadTasks();
  }).catch(function (err) {
    if (btn) btn.disabled = false;
    alert(t("task.createFailed", { msg: err.message }));
  });
}

// ---- Open Chat Tab with Context ----

function openChatTabWithContext(context) {
  createChatSession({
    title: context.title || t("chat.contextFollowup"),
    source: context.type === "task" ? "task_context" : "agent_context",
    contextLabel: context.label || context.title,
    contextSnapshot: context,
    initialMessages: [{
      role: "system",
      content: t("chat.contextBrought", { title: context.title || context.label || t("chat.currentItem") }),
    }],
  }).then(function () {
    switchTab("chat");
    renderChatTab();
  }).catch(function (err) {
    alert(t("chat.createSessionFailed", { msg: (err && err.message ? err.message : err) }));
  });
}
