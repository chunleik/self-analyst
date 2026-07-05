/* ============================================================
   SelfAnalyst Desktop - Chat Tab
   ============================================================ */
"use strict";

// ---- Backend Persistence (SPEC-CSP-FE-002..004) ----
// The backend (`{memoryDir}/chat-sessions/`) is the source of truth; the
// session list below is an in-memory cache that drives rendering. Index meta
// rows carry no `messages` — bodies are lazy-loaded on activation.

function loadChatSessions() {
  return api.listSessions().then(function (index) {
    var rows = (index && index.sessions) || [];
    state.chatSessions = rows.map(function (meta) {
      meta.messages = [];
      meta.messagesLoaded = false;
      return meta;
    });
    state.activeChatSessionId = (index && index.activeSessionId) || null;
  }).catch(function () {
    // Keep an empty list rather than breaking init (SPEC-CSP-FE-007).
    state.chatSessions = [];
    state.activeChatSessionId = null;
  }).then(function () {
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

// Lazy-load a session's message bodies + summary (SPEC-CSP-FE-003).
function ensureSessionMessagesLoaded(session) {
  if (!session) return Promise.resolve(null);
  if (session.messagesLoaded) return Promise.resolve(session);
  return api.getSession(session.id).then(function (full) {
    session.messages = (full && full.messages) || [];
    session.summary = full ? full.summary : session.summary;
    session.memoryPolicy = full ? (full.memoryPolicy || "smart") : session.memoryPolicy;
    session.messagesLoaded = true;
    return session;
  }).catch(function () {
    // Treat as loaded-but-empty so the thread renders instead of spinning.
    session.messages = session.messages || [];
    session.messagesLoaded = true;
    return session;
  });
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
  if (fresh.title != null) session.title = fresh.title;
  if (fresh.updatedAt != null) session.updatedAt = fresh.updatedAt;
  if (fresh.summary != null) session.summary = fresh.summary;
  if (fresh.contextLabel != null) session.contextLabel = fresh.contextLabel;
  if (fresh.contextSnapshot != null) session.contextSnapshot = fresh.contextSnapshot;
  if (fresh.memoryPolicy != null) session.memoryPolicy = fresh.memoryPolicy;
}

function getActiveChatSession() {
  if (!state.activeChatSessionId) return null;
  for (var i = 0; i < state.chatSessions.length; i++) {
    if (state.chatSessions[i].id === state.activeChatSessionId) return state.chatSessions[i];
  }
  return null;
}

// Returns a Promise resolving to the active session, creating one if none.
function ensureActiveChatSession() {
  var s = getActiveChatSession();
  if (s) return Promise.resolve(s);
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
    session.messages = session.messages || [];
    session.messagesLoaded = true;
    state.chatSessions.unshift(session);
    state.activeChatSessionId = session.id;
    return session;
  });
}

function deleteChatSession(id) {
  return api.deleteSession(id).then(function (resp) {
    state.chatSessions = state.chatSessions.filter(function (s) { return s.id !== id; });
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
  html += '<textarea id="memory-draft-input" class="memory-draft-input" rows="2" placeholder="' + escHtml(t("memory.addPlaceholder")) + '"></textarea>';
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
    addBtn.onclick = function () {
      var text = draft.value.trim();
      if (!text) return;
      api.createSessionMemory(session.id, {
        type: "note",
        content: text,
        evidence: t("memory.manualEvidence"),
        status: "active",
      }).then(loadMemoryForChat).then(renderChatTab).catch(function (err) {
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
  // Search matches index fields only (title + preview + summary), not message
  // bodies (SPEC-CSP-FE-005 / DEC-009).
  var filtered = state.chatSessions.filter(function (s) {
    if (!search) return true;
    var q = search.toLowerCase();
    return (s.title && s.title.toLowerCase().indexOf(q) >= 0) ||
           (s.lastMessagePreview && s.lastMessagePreview.toLowerCase().indexOf(q) >= 0) ||
           (s.summary && s.summary.toLowerCase().indexOf(q) >= 0);
  });

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
  list.innerHTML = html;

  // Click delegation: select session or delete
  list.onclick = function (e) {
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
      ensureSessionMessagesLoaded(selected).then(renderChatTab);
    }
  };
}

function renderChatThread() {
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
    thread.innerHTML = '<div class="chat-welcome"><p>' + escHtml(t("common.loadingEllipsis")) + '</p></div>';
    ensureSessionMessagesLoaded(session).then(function () {
      if (getActiveChatSession() === session) renderChatThread();
    });
    return;
  }

  if (session.messages.length === 0) {
    thread.innerHTML = '<div class="chat-welcome"><p>' + escHtml(t("chat.welcomeTitle")) + '</p><p class="chat-welcome-hint">' + escHtml(t("chat.welcomeHint")) + '</p></div>';
    return;
  }

  var html = "";
  for (var i = 0; i < session.messages.length; i++) {
    var m = session.messages[i];
    var cls = "chat-tab-message " + m.role + (m.status === "pending" ? " pending" : "") + (m.status === "error" ? " error" : "");
    html += '<div class="' + cls + '">';
    html += '<div class="msg-content">' + formatChatMessageContent(m.content) + '</div>';
    if (m.status === "error") {
      html += '<div class="msg-retry" data-mid="' + m.id + '">' + escHtml(t("chat.retry")) + '</div>';
    }
    // Suggested tasks
    if (m.suggestedTasks && m.suggestedTasks.length > 0) {
      html += '<div class="chat-suggested-tasks">';
      for (var t = 0; t < m.suggestedTasks.length; t++) {
        var st = m.suggestedTasks[t];
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
      var t = timeline[i];
      actHtml += '<div class="activity-item"><strong>' + escHtml(t.label) + '</strong> ' + escHtml((t.headline || "").substring(0, 30)) + '</div>';
    }
    actDiv.innerHTML = actHtml;
  } else {
    actDiv.textContent = t("chat.noActivitySummary");
  }

  // Task suggestions -- from last assistant message with suggestedTasks
  var taskDiv = state.dom.chatTaskSuggestions.querySelector(".chat-context-content");
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
      for (var t = 0; t < lastAssistantTasks.length; t++) {
        tHtml += '<div class="chat-suggested-task-row"><span class="task-title">' + escHtml(lastAssistantTasks[t].title) + '</span></div>';
      }
      taskDiv.innerHTML = tHtml;
    } else {
      taskDiv.textContent = t("chat.noSuggestedTasks");
    }
  }
  renderChatMemoryPanel();
}

function updateChatInputState() {
  var llmOk = state.status && state.status.llm && state.status.llm.configured;
  var session = getActiveChatSession();
  if (state.dom.chatTabInput) {
    state.dom.chatTabInput.disabled = !llmOk || !session;
    if (!llmOk) state.dom.chatTabInput.placeholder = t("chat.llmNotConfiguredPlaceholder");
  }
  if (state.dom.chatTabSendBtn) {
    state.dom.chatTabSendBtn.disabled = !llmOk || !session || state.chatSending;
  }
}

// ---- Send Message ----

function sendChatTabMessage() {
  if (state.chatSending) return;
  var input = state.dom.chatTabInput;
  var text = input.value.trim();
  if (!text) return;

  state.chatSending = true;
  input.value = "";

  ensureActiveChatSession().then(function (session) {
    var context = buildChatContext(session);
    var userMsg = { role: "user", content: text, status: "sent", contextSnapshot: context };
    var pendingMsg = { role: "assistant", content: t("chat.thinking"), status: "pending" };

    // Persist user + pending assistant; adopt server-assigned ids into cache.
    return api.appendMessages(session.id, { messages: [userMsg, pendingMsg] })
      .then(function (appended) {
        var savedUser = appended[0];
        var savedPending = appended[1];
        session.messages.push(savedUser, savedPending);
        session.updatedAt = savedPending.createdAt || new Date().toISOString();
        resortChatSessions();
        renderChatTab();
        backfillSessionTitle(session, text);

        return api.postChat(text, context).then(function (resp) {
          var content = resp.message || resp.reply || resp.content || t("chat.agentNoContent");
          var tasks = resp.suggestedTasks || resp.suggested_tasks || resp.tasks || [];
          savedPending.status = "sent";
          savedPending.content = content;
          savedPending.suggestedTasks = tasks;
          renderChatTab();
          return api.updateMessage(session.id, savedPending.id,
            { status: "sent", content: content, suggestedTasks: tasks }).then(function (updated) {
              refreshMemoryPanelSoon();
              return updated;
            });
        }).catch(function (err) {
          // Never lose the user's input: reflect error in-memory and persist best-effort.
          savedPending.status = "error";
          savedPending.content = formatChatErrorMessage(err);
          savedPending.error = err.message || String(err);
          renderChatTab();
          return api.updateMessage(session.id, savedPending.id,
            { status: "error", error: savedPending.error }).catch(function () {});
        });
      });
  }).catch(function (err) {
    alert(t("chat.sendFailed", { msg: (err && err.message ? err.message : err) }));
  }).then(function () {
    state.chatSending = false;
    renderChatTab();
  });
}

// Backfill a default-titled session from its first user message (SPEC-CSP-FE-004).
function backfillSessionTitle(session, text) {
  if (!session || session.title !== t("chat.newSessionDefault")) return;
  var txt = (text || "").replace(/\n/g, " ").trim().substring(0, 18);
  if (!txt) return;
  api.updateSession(session.id, { title: txt }).then(function (fresh) {
    mergeSessionFields(session, fresh);
    renderChatSessionList();
  }).catch(function () { /* non-fatal */ });
}

function retryChatMessage(msgId) {
  if (state.chatSending) return;
  var session = getActiveChatSession();
  if (!session) return;
  // Find the error message and the preceding user message
  var errIdx = -1;
  for (var i = 0; i < session.messages.length; i++) {
    if (session.messages[i].id === msgId) { errIdx = i; break; }
  }
  if (errIdx < 0) return;
  var pendingMsg = session.messages[errIdx];
  // Find preceding user message
  var userMsg = null;
  for (var j = errIdx - 1; j >= 0; j--) {
    if (session.messages[j].role === "user") { userMsg = session.messages[j]; break; }
  }
  if (!userMsg) return;

  state.chatSending = true;
  pendingMsg.status = "pending";
  pendingMsg.content = t("chat.thinking");
  renderChatTab();

  api.updateMessage(session.id, pendingMsg.id, { status: "pending" }).catch(function () {});

  api.postChat(userMsg.content, userMsg.contextSnapshot || buildChatContext(session)).then(function (resp) {
    var content = resp.message || resp.reply || resp.content || t("chat.agentNoContent");
    var tasks = resp.suggestedTasks || resp.suggested_tasks || resp.tasks || [];
    pendingMsg.status = "sent";
    pendingMsg.content = content;
    pendingMsg.suggestedTasks = tasks;
    return api.updateMessage(session.id, pendingMsg.id,
      { status: "sent", content: content, suggestedTasks: tasks }).then(function (updated) {
        refreshMemoryPanelSoon();
        return updated;
      });
  }).catch(function (err) {
    pendingMsg.status = "error";
    pendingMsg.content = formatChatErrorMessage(err);
    pendingMsg.error = err.message || String(err);
    return api.updateMessage(session.id, pendingMsg.id,
      { status: "error", error: pendingMsg.error }).catch(function () {});
  }).then(function () {
    state.chatSending = false;
    renderChatTab();
  });
}

function buildChatContext(session) {
  var ctx = { type: "global", title: session.title };
  if (state.chatContextToggles.currentStatus && state.summary && state.summary.current) {
    ctx.currentStatus = {
      headline: state.summary.current.headline,
      evidence: state.summary.current.evidence,
    };
  }
  if (state.chatContextToggles.futureTasks && state.tasks) {
    var open = state.tasks.filter(function (t) { return t.status === "open"; }).slice(0, 10);
    ctx.futureTasks = open;
  }
  if (state.summary && state.summary.timeline) {
    ctx.recentActivity = state.summary.timeline.slice(0, 4);
  }
  if (state.chatContextToggles.history && session) {
    ctx.history = session.messages.filter(function (m) { return m.status !== "pending" && m.status !== "error"; }).slice(-10).map(function (m) { return { role: m.role, content: m.content }; });
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
  api.createTask({ title: title, notes: notes, priority: priority, source: "chat" }).then(function () {
    if (btn) { btn.textContent = t("task.created"); btn.disabled = true; }
    loadTasks();
  }).catch(function (err) {
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
      contextSnapshot: context,
    }],
  }).then(function () {
    switchTab("chat");
    renderChatTab();
  }).catch(function (err) {
    alert(t("chat.createSessionFailed", { msg: (err && err.message ? err.message : err) }));
  });
}
