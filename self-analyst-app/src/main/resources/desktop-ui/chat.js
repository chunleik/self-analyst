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
  });
}

// Lazy-load a session's message bodies + summary (SPEC-CSP-FE-003).
function ensureSessionMessagesLoaded(session) {
  if (!session) return Promise.resolve(null);
  if (session.messagesLoaded) return Promise.resolve(session);
  return api.getSession(session.id).then(function (full) {
    session.messages = (full && full.messages) || [];
    session.summary = full ? full.summary : session.summary;
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
  return createChatSession({ title: "当前会话" });
}

// Create a session via the backend; returns a Promise resolving to it.
function createChatSession(opts) {
  return api.createSession({
    title: (opts && opts.title) || "新会话",
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
      html += '<div class="structured-response-row"><span>洞察</span><p>' + escHtml(item.insight) + '</p></div>';
    }
    if (item.suggestion) {
      html += '<div class="structured-response-row"><span>建议</span><p>' + escHtml(item.suggestion) + '</p></div>';
    }
    if (item.confidence) {
      html += '<div class="structured-response-meta">置信度: ' + escHtml(item.confidence) + '</div>';
    }
    html += '</div>';
  }
  if (items.length > limit) {
    html += '<div class="structured-response-more">还有 ' + (items.length - limit) + ' 条摘要未展开</div>';
  }
  html += '</div>';
  return html;
}

function formatChatErrorMessage(err) {
  var reason = err && err.message ? err.message : String(err || "未知错误");
  return "发送失败: " + reason;
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
    list.innerHTML = '<div class="chat-session-empty">' + (search ? "没有匹配的会话" : '暂无会话，点击"+ 新建"开始') + '</div>';
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
      '<button class="session-delete-btn" data-action="delete-session" title="删除会话">' +
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
      if (sid && confirm("确定删除该会话及其所有消息？")) {
        deleteChatSession(sid).then(renderChatTab).catch(function (err) {
          alert("删除会话失败: " + err.message);
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
    thread.innerHTML = '<div class="chat-welcome"><p>选择或创建一个会话</p><p class="chat-welcome-hint">从左侧列表选择会话，或点击"+ 新建"创建新会话</p></div>';
    state.dom.chatSessionTitle.textContent = "选择或创建会话";
    return;
  }

  state.dom.chatSessionTitle.textContent = session.title;

  // Bodies are lazy-loaded; show a transient state and fetch on demand.
  if (!session.messagesLoaded) {
    thread.innerHTML = '<div class="chat-welcome"><p>加载中…</p></div>';
    ensureSessionMessagesLoaded(session).then(function () {
      if (getActiveChatSession() === session) renderChatThread();
    });
    return;
  }

  if (session.messages.length === 0) {
    thread.innerHTML = '<div class="chat-welcome"><p>欢迎使用会话模式</p><p class="chat-welcome-hint">可以追问当前状态、记录想法、生成待办</p></div>';
    return;
  }

  var html = "";
  for (var i = 0; i < session.messages.length; i++) {
    var m = session.messages[i];
    var cls = "chat-tab-message " + m.role + (m.status === "pending" ? " pending" : "") + (m.status === "error" ? " error" : "");
    html += '<div class="' + cls + '">';
    html += '<div class="msg-content">' + formatChatMessageContent(m.content) + '</div>';
    if (m.status === "error") {
      html += '<div class="msg-retry" data-mid="' + m.id + '">重试</div>';
    }
    // Suggested tasks
    if (m.suggestedTasks && m.suggestedTasks.length > 0) {
      html += '<div class="chat-suggested-tasks">';
      for (var t = 0; t < m.suggestedTasks.length; t++) {
        var st = m.suggestedTasks[t];
        html += '<div class="chat-suggested-task-row">' +
          '<span class="task-title">' + escHtml(st.title) + '</span>' +
          '<button class="btn btn-sm btn-primary create-suggested-task" data-title="' + escHtml(st.title) + '" data-notes="' + escHtml(st.notes || "") + '" data-priority="' + escHtml(st.priority || "medium") + '">创建任务</button>' +
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
    statusDiv.textContent = "暂无数据";
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
    actDiv.textContent = "暂无活动摘要";
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
      taskDiv.textContent = "暂无建议待办";
    }
  }
}

function updateChatInputState() {
  var llmOk = state.status && state.status.llm && state.status.llm.configured;
  var session = getActiveChatSession();
  if (state.dom.chatTabInput) {
    state.dom.chatTabInput.disabled = !llmOk || !session;
    if (!llmOk) state.dom.chatTabInput.placeholder = "LLM 未配置，请先在配置页设置 API Key";
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
    var pendingMsg = { role: "assistant", content: "思考中...", status: "pending" };

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
          var content = resp.message || resp.reply || resp.content || "Agent 未返回可显示内容";
          var tasks = resp.suggestedTasks || resp.suggested_tasks || resp.tasks || [];
          savedPending.status = "sent";
          savedPending.content = content;
          savedPending.suggestedTasks = tasks;
          renderChatTab();
          return api.updateMessage(session.id, savedPending.id,
            { status: "sent", content: content, suggestedTasks: tasks });
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
    alert("发送失败: " + (err && err.message ? err.message : err));
  }).then(function () {
    state.chatSending = false;
    renderChatTab();
  });
}

// Backfill a default-titled session from its first user message (SPEC-CSP-FE-004).
function backfillSessionTitle(session, text) {
  if (!session || session.title !== "新会话") return;
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
  pendingMsg.content = "思考中...";
  renderChatTab();

  api.updateMessage(session.id, pendingMsg.id, { status: "pending" }).catch(function () {});

  api.postChat(userMsg.content, userMsg.contextSnapshot || buildChatContext(session)).then(function (resp) {
    var content = resp.message || resp.reply || resp.content || "Agent 未返回可显示内容";
    var tasks = resp.suggestedTasks || resp.suggested_tasks || resp.tasks || [];
    pendingMsg.status = "sent";
    pendingMsg.content = content;
    pendingMsg.suggestedTasks = tasks;
    return api.updateMessage(session.id, pendingMsg.id,
      { status: "sent", content: content, suggestedTasks: tasks });
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

function createSuggestedTask(title, notes, priority, btn) {
  api.createTask({ title: title, notes: notes, priority: priority, source: "chat" }).then(function () {
    if (btn) { btn.textContent = "已创建"; btn.disabled = true; }
    loadTasks();
  }).catch(function (err) {
    alert("创建任务失败: " + err.message);
  });
}

// ---- Open Chat Tab with Context ----

function openChatTabWithContext(context) {
  createChatSession({
    title: context.title || "上下文追问",
    source: context.type === "task" ? "task_context" : "agent_context",
    contextLabel: context.label || context.title,
    contextSnapshot: context,
    initialMessages: [{
      role: "system",
      content: "已带入上下文：" + (context.title || context.label || "当前条目"),
      contextSnapshot: context,
    }],
  }).then(function () {
    switchTab("chat");
    renderChatTab();
  }).catch(function (err) {
    alert("创建会话失败: " + (err && err.message ? err.message : err));
  });
}
