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

  state.dom.audioToggleBtn.addEventListener("click", function () {
    var btn = state.dom.audioToggleBtn;
    btn.disabled = true;
    api.setAudioCapture(!audioCaptureRunning())
      .then(function (resp) {
        if (!state.status) state.status = {};
        if (!state.status.collectors || typeof state.status.collectors !== "object") {
          state.status.collectors = {};
        }
        state.status.collectors.audio = resp.status || (resp.enabled ? "running" : "disabled");
        updateStatusBar();
      })
      .catch(function (err) {
        alert(t("audio.toggleFailed", { msg: err.message || t("common.unknownError") }));
      })
      .then(function () {
        btn.disabled = false;
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
          alert(t("task.completeFailed", { msg: err.message }));
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
      var taskCtx = { type: "task", id: fullTask ? fullTask.id : dId, title: t("task.discussPrefix", { title: dTitle }), label: dTitle };
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
          alert(t("task.archiveFailed", { msg: err.message }));
        });
      return;
    }

    // Delete
    if (target.classList.contains("delete-task-btn")) {
      var delId = target.dataset.taskId;
      if (!confirm(t("task.confirmDelete"))) return;
      api
        .deleteTask(delId)
        .then(function () {
          state.editingTaskId = null;
          loadTasks();
        })
        .catch(function (err) {
          alert(t("task.deleteFailed", { msg: err.message }));
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
          alert(t("task.saveFailed", { msg: err.message }));
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
        var entryCtx = { type: "timeline_entry", id: entryData.key || idx, title: t("timeline.discussSuffix", { label: entryData.label || entryData.period }), label: entryData.label || entryData.period };
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

    // Test LLM — values come from the editor text. SPEC-CFGUI-UI-005a/b.
    if (target.id === "test-llm-btn") {
      var llmConfig = readLlmConfigFromEditor();
      target.disabled = true;
      target.textContent = t("config.testing");
      api
        .testLlm(llmConfig)
        .then(function (resp) {
          state.configSaveResult = {
            type: "success",
            msg: resp.ok || resp.success
              ? t("config.connectOk", { service: "LLM" })
              : t("config.connectFailed", { service: "LLM", msg: resp.error || t("common.unknownError") }),
          };
          target.disabled = false;
          target.textContent = t("config.testLlm");
          updateConfigActionBar();
        })
        .catch(function (err) {
          state.configSaveResult = {
            type: "error",
            msg: t("config.testFailed", { service: "LLM", msg: err.message }),
          };
          target.disabled = false;
          target.textContent = t("config.testLlm");
          updateConfigActionBar();
        });
      return;
    }

    // Test Embedding — values come from the editor text. SPEC-CFGUI-UI-005a/b.
    if (target.id === "test-embedding-btn") {
      var embeddingConfig = readEmbeddingConfigFromEditor();
      target.disabled = true;
      target.textContent = t("config.testing");
      api
        .testEmbedding(embeddingConfig)
        .then(function (resp) {
          state.configSaveResult = {
            type: "success",
            msg: resp.ok || resp.success
              ? t("config.connectOk", { service: "Embedding" })
              : t("config.connectFailed", { service: "Embedding", msg: resp.error || t("common.unknownError") }),
          };
          target.disabled = false;
          target.textContent = t("config.testEmbedding");
          updateConfigActionBar();
        })
        .catch(function (err) {
          state.configSaveResult = {
            type: "error",
            msg: t("config.testFailed", { service: "Embedding", msg: err.message }),
          };
          target.disabled = false;
          target.textContent = t("config.testEmbedding");
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
        card.textContent = t("task.added");
        card.disabled = true;
        card.style.opacity = "0.6";
        loadTasks();
      })
      .catch(function (err) {
        alert(t("task.addFailed", { msg: err.message }));
      });
  });

  // Chat tab - new session button
  state.dom.newChatSessionBtn.addEventListener("click", function () {
    createChatSession({ title: t("chat.newSessionDefault") }).then(function () {
      switchTab("chat");
      renderChatTab();
      focusChatComposer();
    }).catch(function (err) {
      alert(t("chat.newSessionFailed", { msg: (err && err.message ? err.message : err) }));
    });
  });

  // Chat tab - session search
  state.dom.chatSessionSearchInput.addEventListener("input", function () {
    scheduleChatSessionSearch(this.value);
  });

  // Legacy composer fallback used by unit tests and deployments where the
  // vendored web component cannot be upgraded.
  if (state.dom.chatTabSendBtn) {
    state.dom.chatTabSendBtn.addEventListener("click", sendChatTabMessage);
  }
  if (state.dom.chatTabInput) {
    state.dom.chatTabInput.addEventListener("keydown", function (e) {
      if (e.key === "Enter" && !e.shiftKey && !e.isComposing) {
        e.preventDefault();
        sendChatTabMessage();
      }
    });
  }

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
