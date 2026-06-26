/* ============================================================
   SelfAnalyst Desktop - Chat Drawer (Legacy)
   ============================================================ */
"use strict";

function openChat(context) {
  state.chatOpen = true;
  state.chatContext = context;
  state.chatMessages = [];
  state.dom.chatDrawer.classList.remove("hidden");
  state.dom.chatMessages.innerHTML = "";
  state.dom.chatSuggestions.innerHTML = "";
  state.dom.chatInput.value = "";

  if (context) {
    state.dom.chatTitle.textContent = "讨论: " + (context.title || context.label || "条目");
    state.dom.chatContext.innerHTML =
      '<div style="font-size:13px;color:var(--text-secondary);">' +
      "<strong>上下文:</strong> " +
      escHtml(context.description || context.headline || context.summary || context.title || "当前选中项") +
      "</div>";
  } else {
    state.dom.chatTitle.textContent = "讨论";
    state.dom.chatContext.innerHTML = "";
  }

  requestAnimationFrame(function () {
    state.dom.chatInput.focus();
  });
}

function closeChat() {
  state.chatOpen = false;
  state.chatContext = null;
  state.dom.chatDrawer.classList.add("hidden");
}

function sendChatMessage() {
  var msg = state.dom.chatInput.value.trim();
  if (!msg) return;

  var context = state.chatContext
    ? {
        type: state.chatContext.type || "entry",
        id: state.chatContext.id,
        title: state.chatContext.title || state.chatContext.label,
        headline: state.chatContext.headline,
        summary: state.chatContext.summary,
      }
    : null;

  // Add user message
  addChatMessage("user", msg);
  state.dom.chatInput.value = "";

  // Add loading message
  var loadingEl = addChatMessage("agent", '<span class="spinner"></span> 思考中...');

  api
    .postChat(msg, context)
    .then(function (resp) {
      // Remove loading
      if (loadingEl && loadingEl.parentNode) {
        loadingEl.remove();
      }

      var reply = resp.reply || resp.message || resp.text || "无回复";
      addChatMessage("agent", reply);

      // Show suggested tasks if any
      var suggested = resp.suggestedTasks || resp.suggested_tasks || resp.tasks || [];
      if (suggested.length) {
        renderChatSuggestions(suggested);
      }
    })
    .catch(function (err) {
      if (loadingEl && loadingEl.parentNode) {
        loadingEl.remove();
      }
      addChatMessage("agent", "发送失败: " + err.message);
    });
}

function addChatMessage(role, text) {
  var el = document.createElement("div");
  el.className = "chat-msg " + role;
  el.innerHTML = text;
  state.dom.chatMessages.appendChild(el);
  state.dom.chatMessages.scrollTop = state.dom.chatMessages.scrollHeight;
  return el;
}

function renderChatSuggestions(suggested) {
  var html = "";
  suggested.forEach(function (s) {
    var title = s.title || s.task || s;
    html +=
      '<button class="chat-suggested-task-card" data-suggested-task="' +
      escHtml(typeof title === "string" ? title : JSON.stringify(title)) +
      '">+ 添加为任务: ' +
      escHtml(typeof title === "string" ? title : JSON.stringify(title)) +
      "</button>";
  });
  state.dom.chatSuggestions.innerHTML = html;
}
