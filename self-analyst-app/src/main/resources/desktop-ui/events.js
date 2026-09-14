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
  state.dom.configOpenBtn.addEventListener("click", function () { openConfigModal(); });
  state.dom.configCloseBtn.addEventListener("click", closeConfigModal);
  var configTabs = document.getElementById("config-view-tabs");
  if (configTabs) configTabs.addEventListener("click", function (e) {
    var button = e.target.closest("[data-config-view]");
    if (button) switchConfigView(button.dataset.configView);
  });
  state.dom.configModal.addEventListener("keydown", function (e) {
    if (e.key === "Escape") { e.preventDefault(); closeConfigModal(); }
    if (e.key !== "Tab") return;
    var focusable = Array.from(state.dom.configModal.querySelectorAll("button:not(:disabled),input:not(:disabled),select:not(:disabled),textarea:not(:disabled)"));
    var first = focusable[0], last = focusable[focusable.length - 1];
    if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
    else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
  });
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
        .testLlm({ text: currentEditorText() })
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
        .testEmbedding({ text: currentEditorText() })
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

  // File collector entry and page actions.
  state.dom.fileStatusBtn.addEventListener("click", openFileStatusEntry);
  state.dom.fileSettingsBtn.addEventListener("click", openFileSettingsModal);
  state.dom.fileContent.addEventListener("click", function (e) {
    var action = e.target && e.target.closest ? e.target.closest("[data-file-action]") : null;
    var remove = e.target && e.target.closest
      ? e.target.closest("[data-file-settings-remove]") : null;
    if (remove) {
      removeFileSettingsPath(parseInt(remove.dataset.fileSettingsRemove, 10));
      return;
    }
    if (!action) return;
    if (action.dataset.fileAction === "settings") {
      openFileSettingsModal();
    } else if (action.dataset.fileAction === "retry") {
      loadFiles();
    } else if (action.dataset.fileAction === "add-folder") {
      addFileSettingsPath();
    } else if (action.dataset.fileAction === "save-folders") {
      saveFileFolders();
    }
  });
  state.dom.fileContent.addEventListener("input", function (e) {
    if (e.target && e.target.matches && e.target.matches("input[data-file-path]")) {
      syncFileFolderDraft();
    }
  });
  state.dom.fileSettingsCloseBtn.addEventListener("click", closeFileSettingsModal);
  state.dom.fileSettingsCancelBtn.addEventListener("click", closeFileSettingsModal);
  state.dom.fileSettingsSaveBtn.addEventListener("click", saveFileSettings);
  state.dom.fileSettingsModal.querySelector(".config-modal-overlay")
    .addEventListener("click", closeFileSettingsModal);

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
    } else if (state.fileSettingsOpen) {
      closeFileSettingsModal();
    } else if (state.configOpen) {
      closeConfigModal();
    }
  });
}
