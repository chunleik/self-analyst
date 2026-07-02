/* ============================================================
   SelfAnalyst Desktop - Event Bindings
   ============================================================ */
"use strict";

function setupEvents() {
  // Tab switching
  state.dom.tabs.forEach(function (tab) {
    tab.addEventListener("click", function () {
      switchTab(tab.dataset.tab);
    });
  });

  // Task list click delegation (from tasksBody)
  state.dom.tasksBody.addEventListener("click", function (e) {
    var target = e.target;

    // Click on task item header to expand
    var taskItem = target.closest(".task-item");
    if (taskItem && !target.closest("button") && !target.closest("input") && !target.closest("select")) {
      var taskId = taskItem.dataset.taskId;
      if (state.editingTaskId === taskId) {
        state.editingTaskId = null;
      } else {
        state.editingTaskId = taskId;
      }
      renderTasks();
      return;
    }

    // Complete
    if (target.classList.contains("complete-task-btn")) {
      var tid = target.dataset.taskId;
      api
        .completeTask(tid)
        .then(function () {
          state.editingTaskId = null;
          loadTasks();
        })
        .catch(function (err) {
          alert("完成任务失败: " + err.message);
        });
      return;
    }

    // Discuss
    if (target.classList.contains("discuss-task-btn")) {
      var dId = target.dataset.taskId;
      var dTitle = target.dataset.taskTitle;
      // Find the full task object to pass context
      var fullTask = null;
      for (var ti = 0; ti < state.tasks.length; ti++) {
        if (String(state.tasks[ti].id) === String(dId)) { fullTask = state.tasks[ti]; break; }
      }
      var taskCtx = { type: "task", id: fullTask ? fullTask.id : dId, title: "任务讨论：" + dTitle, label: dTitle };
      if (fullTask) {
        taskCtx.priority = fullTask.priority;
        taskCtx.status = fullTask.status;
        taskCtx.dueAt = fullTask.dueAt;
        taskCtx.notes = fullTask.notes;
        taskCtx.source = fullTask.source;
      }
      openChatTabWithContext(taskCtx);
      return;
    }

    // Archive
    if (target.classList.contains("archive-task-btn")) {
      var aId = target.dataset.taskId;
      api
        .archiveTask(aId)
        .then(function () {
          state.editingTaskId = null;
          loadTasks();
        })
        .catch(function (err) {
          alert("归档任务失败: " + err.message);
        });
      return;
    }

    // Delete
    if (target.classList.contains("delete-task-btn")) {
      var delId = target.dataset.taskId;
      if (!confirm("确认删除此任务？")) return;
      api
        .deleteTask(delId)
        .then(function () {
          state.editingTaskId = null;
          loadTasks();
        })
        .catch(function (err) {
          alert("删除任务失败: " + err.message);
        });
      return;
    }

    // Save task edit
    if (target.classList.contains("save-task-btn")) {
      var sId = target.dataset.taskId;
      var taskItemEl = target.closest(".task-item");
      var titleInput = taskItemEl
        ? taskItemEl.querySelector(".edit-task-title")
        : null;
      var prioritySel = taskItemEl
        ? taskItemEl.querySelector(".edit-task-priority")
        : null;
      var dueInput = taskItemEl
        ? taskItemEl.querySelector(".edit-task-due")
        : null;

      var updates = {};
      if (titleInput) updates.title = titleInput.value.trim();
      if (prioritySel) updates.priority = prioritySel.value;
      if (dueInput) updates.dueAt = dueInput.value || null;

      api
        .updateTask(sId, updates)
        .then(function () {
          state.editingTaskId = null;
          loadTasks();
        })
        .catch(function (err) {
          alert("保存任务失败: " + err.message);
        });
      return;
    }
  });

  // Timeline click delegation
  state.dom.timelineBody.addEventListener("click", function (e) {
    var target = e.target;

    // Expand/collapse entry
    var entry = target.closest(".timeline-entry");
    if (entry && !target.closest("button")) {
      entry.classList.toggle("expanded");
      return;
    }

    // Discuss button
    if (target.classList.contains("timeline-entry-discuss-btn")) {
      var idx = parseInt(target.dataset.entryIdx, 10);
      var sm = state.summary;
      var entries = sm ? sm.entries || sm.timeline || [] : [];
      var entryData = entries[idx];
      if (entryData) {
        var entryCtx = { type: "timeline_entry", id: entryData.key || idx, title: (entryData.label || entryData.period) + "追问", label: entryData.label || entryData.period };
        if (entryData.headline) entryCtx.headline = entryData.headline;
        if (entryData.summary) entryCtx.summary = entryData.summary;
        if (entryData.period) entryCtx.period = entryData.period;
        if (entryData.label) entryCtx.entryLabel = entryData.label;
        openChatTabWithContext(entryCtx);
      }
      return;
    }
  });

  // Config modal open/close
  state.dom.configOpenBtn.addEventListener("click", openConfigModal);
  state.dom.configCloseBtn.addEventListener("click", closeConfigModal);
  state.dom.configModal
    .querySelector(".config-modal-overlay")
    .addEventListener("click", closeConfigModal);

  // Config delegation
  state.dom.configGrid.addEventListener("click", function (e) {
    var target = e.target;
    if (target && target.nodeType !== 1) target = target.parentElement;
    target = target && target.closest ? target.closest("button") : null;
    if (!target || !state.dom.configGrid.contains(target)) return;

    if (target.id === "save-all-config-btn") {
      saveAllConfig();
      return;
    }

    if (target.id === "discard-config-btn") {
      discardConfigChanges();
      return;
    }

    // All configurable keys reference panel toggle. SPEC-TOML-UI-004a.
    if (target.id === "config-allkeys-btn") {
      toggleSupportedKeys();
      return;
    }

    // Insert a supported key into the editor. SPEC-TOML-UI-004c.
    if (target.classList.contains("config-allkeys-insert")) {
      insertSupportedKey(target.dataset.assignment);
      return;
    }

    // Version history toggle
    if (target.id === "config-history-btn") {
      toggleConfigHistory();
      return;
    }

    // View a historical version (toggle inline preview)
    if (target.classList.contains("config-history-view")) {
      toggleVersionPreview(target.dataset.versionId);
      return;
    }

    // Switch: load a historical version into the editor as unsaved changes
    if (target.classList.contains("config-history-switch")) {
      switchToVersion(target.dataset.versionId);
      return;
    }

    // Test LLM — values come from the editor text. SPEC-CFGUI-UI-005a/b.
    if (target.id === "test-llm-btn") {
      var llmConfig = readLlmConfigFromEditor();
      target.disabled = true;
      target.textContent = "测试中...";
      api
        .testLlm(llmConfig)
        .then(function (resp) {
          state.configSaveResult = {
            type: "success",
            msg: resp.ok || resp.success ? "连接成功" : "连接失败: " + (resp.error || "未知错误"),
          };
          target.disabled = false;
          target.textContent = "测试 LLM 连接";
          updateConfigActionBar();
        })
        .catch(function (err) {
          state.configSaveResult = {
            type: "error",
            msg: "测试失败: " + err.message,
          };
          target.disabled = false;
          target.textContent = "测试 LLM 连接";
          updateConfigActionBar();
        });
      return;
    }

    // Test Embedding — values come from the editor text. SPEC-CFGUI-UI-005a/b.
    if (target.id === "test-embedding-btn") {
      var embeddingConfig = readEmbeddingConfigFromEditor();
      target.disabled = true;
      target.textContent = "测试中...";
      api
        .testEmbedding(embeddingConfig)
        .then(function (resp) {
          state.configSaveResult = {
            type: "success",
            msg: resp.ok || resp.success ? "连接成功" : "连接失败: " + (resp.error || "未知错误"),
          };
          target.disabled = false;
          target.textContent = "测试 Embedding 连接";
          updateConfigActionBar();
        })
        .catch(function (err) {
          state.configSaveResult = {
            type: "error",
            msg: "测试失败: " + err.message,
          };
          target.disabled = false;
          target.textContent = "测试 Embedding 连接";
          updateConfigActionBar();
        });
      return;
    }

  });

  state.dom.configGrid.addEventListener("input", handleConfigFieldChange);
  state.dom.configGrid.addEventListener("change", handleConfigFieldChange);

  // Chat drawer
  state.dom.chatCloseBtn.addEventListener("click", closeChat);
  state.dom.chatDrawer
    .querySelector(".chat-overlay")
    .addEventListener("click", closeChat);

  state.dom.chatSendBtn.addEventListener("click", sendChatMessage);

  state.dom.chatInput.addEventListener("keydown", function (e) {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      sendChatMessage();
    }
  });

  // Chat suggestions delegation
  state.dom.chatSuggestions.addEventListener("click", function (e) {
    var card = e.target.closest(".chat-suggested-task-card");
    if (!card) return;
    var taskTitle = card.dataset.suggestedTask;
    api
      .createTask({ title: taskTitle, priority: "medium", source: "chat" })
      .then(function () {
        card.textContent = "已添加";
        card.disabled = true;
        card.style.opacity = "0.6";
        loadTasks();
      })
      .catch(function (err) {
        alert("添加任务失败: " + err.message);
      });
  });

  // Chat tab - new session button
  state.dom.newChatSessionBtn.addEventListener("click", function () {
    createChatSession({ title: "新会话" });
    switchTab("chat");
    renderChatTab();
    if (state.dom.chatTabInput) state.dom.chatTabInput.focus();
  });

  // Chat tab - session search
  state.dom.chatSessionSearchInput.addEventListener("input", function () {
    state.chatSessionSearch = this.value;
    renderChatSessionList();
  });

  // Chat tab - send button
  state.dom.chatTabSendBtn.addEventListener("click", sendChatTabMessage);

  // Chat tab - enter key to send
  state.dom.chatTabInput.addEventListener("keydown", function (e) {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      sendChatTabMessage();
    }
  });

  // Context toggles
  var toggles = state.dom.chatContextToggles.querySelectorAll("input[type=checkbox]");
  for (var ti = 0; ti < toggles.length; ti++) {
    toggles[ti].addEventListener("change", function () {
      state.chatContextToggles[this.dataset.toggle] = this.checked;
    });
  }

  // Error retry
  state.dom.errorRetryBtn.addEventListener("click", function () {
    loadAll();
  });

  // Keyboard shortcut: Escape to close chat / config modal
  document.addEventListener("keydown", function (e) {
    if (e.key !== "Escape") return;
    if (state.chatOpen) {
      closeChat();
    } else if (state.configOpen) {
      closeConfigModal();
    }
  });
}
