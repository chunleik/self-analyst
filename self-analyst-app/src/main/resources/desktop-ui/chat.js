/* ============================================================
   SelfAnalyst Desktop - Chat Tab
   ============================================================ */
"use strict";

// ---- LocalStorage Persistence ----

function loadChatSessions() {
  try {
    var raw = localStorage.getItem(CHAT_STORAGE_KEY);
    if (!raw) { state.chatSessions = []; return; }
    var data = JSON.parse(raw);
    if (data && data.version === 1 && Array.isArray(data.sessions)) {
      state.chatSessions = data.sessions;
      state.activeChatSessionId = data.activeChatSessionId || null;
    }
  } catch (e) { state.chatSessions = []; }
}

function saveChatSessions() {
  try {
    // Prune: keep top 50 sessions by updatedAt
    var sessions = state.chatSessions.slice(0, 50);
    sessions.forEach(function (s) {
      s.messages = s.messages.slice(0, 200);
    });
    localStorage.setItem(CHAT_STORAGE_KEY, JSON.stringify({
      version: 1,
      activeChatSessionId: state.activeChatSessionId,
      sessions: sessions,
    }));
  } catch (e) { /* localStorage unavailable */ }
}

function touchSession(session) {
  session.updatedAt = new Date().toISOString();
  // Move to top
  var idx = state.chatSessions.indexOf(session);
  if (idx > 0) {
    state.chatSessions.splice(idx, 1);
    state.chatSessions.unshift(session);
  }
}

function getActiveChatSession() {
  if (!state.activeChatSessionId) return null;
  for (var i = 0; i < state.chatSessions.length; i++) {
    if (state.chatSessions[i].id === state.activeChatSessionId) return state.chatSessions[i];
  }
  return null;
}

function ensureActiveChatSession() {
  var s = getActiveChatSession();
  if (s) return s;
  return createChatSession({ title: "当前会话" });
}

function createChatSession(opts) {
  var now = new Date().toISOString();
  var session = {
    id: createId("chat"),
    title: (opts && opts.title) || "新会话",
    createdAt: now,
    updatedAt: now,
    source: (opts && opts.source) || "manual",
    contextLabel: opts && opts.contextLabel,
    contextSnapshot: opts && opts.contextSnapshot || null,
    messages: [],
  };
  state.chatSessions.unshift(session);
  state.activeChatSessionId = session.id;
  saveChatSessions();
  return session;
}

function deleteChatSession(id) {
  state.chatSessions = state.chatSessions.filter(function (s) { return s.id !== id; });
  if (state.activeChatSessionId === id) {
    state.activeChatSessionId = state.chatSessions.length > 0 ? state.chatSessions[0].id : null;
  }
  saveChatSessions();
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
  var filtered = state.chatSessions.filter(function (s) {
    if (!search) return true;
    var q = search.toLowerCase();
    return (s.title && s.title.toLowerCase().indexOf(q) >= 0) ||
           (s.messages.some(function (m) { return m.content && m.content.toLowerCase().indexOf(q) >= 0; }));
  });

  if (filtered.length === 0) {
    list.innerHTML = '<div class="chat-session-empty">' + (search ? "没有匹配的会话" : '暂无会话，点击"+ 新建"开始') + '</div>';
    return;
  }

  var html = "";
  for (var i = 0; i < filtered.length; i++) {
    var s = filtered[i];
    var active = s.id === state.activeChatSessionId;
    var lastMsg = s.messages.length > 0 ? s.messages[s.messages.length - 1] : null;
    var preview = lastMsg ? (lastMsg.content || "").substring(0, 40) : "";
    if (lastMsg && lastMsg.content && lastMsg.content.length > 40) preview += "...";
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
        deleteChatSession(sid);
        renderChatTab();
      }
      return;
    }
    var item = e.target.closest(".chat-session-item");
    if (item) {
      state.activeChatSessionId = item.dataset.sid;
      saveChatSessions();
      renderChatTab();
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
  var session = getActiveChatSession();
  if (!session) { session = ensureActiveChatSession(); }
  var input = state.dom.chatTabInput;
  var text = input.value.trim();
  if (!text) return;

  var context = buildChatContext(session);
  var now = new Date().toISOString();
  var userMsg = { id: createId("msg"), role: "user", content: text, createdAt: now, status: "sent", contextSnapshot: context };
  var pendingMsg = { id: createId("msg"), role: "assistant", content: "思考中...", createdAt: now, status: "pending" };

  session.messages.push(userMsg, pendingMsg);
  updateSessionTitleFromFirstMessage(session);
  touchSession(session);
  input.value = "";
  state.chatSending = true;
  saveChatSessions();
  renderChatTab();

  api.postChat(text, context).then(function (resp) {
    pendingMsg.status = "sent";
    pendingMsg.content = resp.message || resp.reply || resp.content || "Agent 未返回可显示内容";
    pendingMsg.suggestedTasks = resp.suggestedTasks || resp.suggested_tasks || resp.tasks || [];
    touchSession(session);
    state.chatSending = false;
    saveChatSessions();
    renderChatTab();
  }).catch(function (err) {
    pendingMsg.status = "error";
    pendingMsg.content = formatChatErrorMessage(err);
    pendingMsg.error = err.message || String(err);
    state.chatSending = false;
    saveChatSessions();
    renderChatTab();
  });
}

function updateSessionTitleFromFirstMessage(session) {
  if (session.title !== "新会话") return;
  var userMsg = null;
  for (var i = 0; i < session.messages.length; i++) {
    if (session.messages[i].role === "user") { userMsg = session.messages[i]; break; }
  }
  if (!userMsg) return;
  var txt = (userMsg.content || "").replace(/\n/g, " ").trim();
  session.title = txt.substring(0, 18) || "新会话";
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
  // Find preceding user message
  var userMsg = null;
  for (var j = errIdx - 1; j >= 0; j--) {
    if (session.messages[j].role === "user") { userMsg = session.messages[j]; break; }
  }
  if (!userMsg) return;

  session.messages[errIdx].status = "pending";
  session.messages[errIdx].content = "思考中...";
  state.chatSending = true;
  saveChatSessions();
  renderChatTab();

  api.postChat(userMsg.content, userMsg.contextSnapshot || buildChatContext(session)).then(function (resp) {
    session.messages[errIdx].status = "sent";
    session.messages[errIdx].content = resp.message || resp.reply || resp.content || "Agent 未返回可显示内容";
    session.messages[errIdx].suggestedTasks = resp.suggestedTasks || resp.suggested_tasks || resp.tasks || [];
    touchSession(session);
    state.chatSending = false;
    saveChatSessions();
    renderChatTab();
  }).catch(function (err) {
    session.messages[errIdx].status = "error";
    session.messages[errIdx].content = formatChatErrorMessage(err);
    session.messages[errIdx].error = err.message || String(err);
    state.chatSending = false;
    saveChatSessions();
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
  var session = createChatSession({
    title: context.title || "上下文追问",
    source: context.type === "task" ? "task_context" : "agent_context",
    contextLabel: context.label || context.title,
    contextSnapshot: context,
  });
  session.messages.push({
    id: createId("msg"),
    role: "system",
    content: "已带入上下文：" + (context.title || context.label || "当前条目"),
    createdAt: new Date().toISOString(),
    contextSnapshot: context,
  });
  saveChatSessions();
  switchTab("chat");
}
