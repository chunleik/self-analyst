/* ============================================================
   SelfAnalyst Desktop - Deep Chat presentation adapter
   ============================================================ */
"use strict";

// Deep Chat owns only the message surface and composer. SelfAnalyst remains
// the source of truth for sessions, message ids, persistence and retries.
var deepChatAdapterState = {
  configuredElement: null,
  renderedElement: null,
  deferredSyncElement: null,
  readyPromise: null,
  signature: null,
  inputStateKey: null,
  requestSessionId: null,
  transientErrors: Object.create(null),
  upgradeFailed: false,
  upgradeFallbackTimer: null,
};

function deepChatElement() {
  return state && state.dom ? state.dom.deepChat : null;
}

function deepChatWhenReady() {
  var element = deepChatElement();
  if (!element) return Promise.resolve(null);
  if (typeof element.addMessage === "function") {
    configureDeepChat(element);
    return Promise.resolve(element);
  }
  if (!window.customElements || typeof window.customElements.whenDefined !== "function") {
    activateDeepChatFallback(element);
    return Promise.resolve(null);
  }
  scheduleDeepChatUpgradeFallback(element);
  if (!deepChatAdapterState.readyPromise) {
    deepChatAdapterState.readyPromise = window.customElements.whenDefined("deep-chat")
      .then(function () {
        var upgraded = deepChatElement();
        if (!upgraded || typeof upgraded.addMessage !== "function") return null;
        configureDeepChat(upgraded);
        renderChatTab();
        return upgraded;
      }).catch(function () {
        activateDeepChatFallback(element);
        return null;
      });
  }
  return deepChatAdapterState.readyPromise;
}

function deepChatInputConfig(disabled, placeholder) {
  return {
    disabled: disabled,
    placeholder: {
      text: placeholder,
      style: { color: "var(--text-muted)" },
    },
    styles: {
      container: {
        borderTop: "1px solid var(--border-color)",
        backgroundColor: "var(--bg-primary)",
      },
      text: {
        color: "var(--text-primary)",
        fontFamily: "inherit",
        fontSize: "13px",
      },
    },
  };
}

function configureDeepChat(element) {
  if (!element || deepChatAdapterState.configuredElement === element) return;
  scheduleDeepChatUpgradeFallback(element);
  deepChatAdapterState.configuredElement = element;
  deepChatAdapterState.renderedElement = null;
  deepChatAdapterState.signature = null;
  deepChatAdapterState.inputStateKey = null;

  element.onComponentRender = function () {
    if (deepChatAdapterState.renderedElement === element) return;
    deepChatAdapterState.renderedElement = element;
    markDeepChatUpgradeReady(element);
    deepChatAdapterState.signature = null;
    Promise.resolve().then(function () {
      if (deepChatElement() === element) renderChatTab();
    });
  };
  // Deep Chat 2.5.0 upgrades the custom element before its public message
  // methods are wired to the rendered view. Its own fallback render runs on a
  // short timer, so perform one deferred canonical sync as well. The lifecycle
  // callback above remains the primary path; this closes the upgrade race when
  // that callback was assigned after the initial render started.
  if (window.setTimeout && deepChatAdapterState.deferredSyncElement !== element) {
    deepChatAdapterState.deferredSyncElement = element;
    window.setTimeout(function () {
      if (deepChatElement() !== element) return;
      var rendered = element.shadowRoot && element.shadowRoot.querySelector &&
        element.shadowRoot.querySelector("#chat-view");
      if (rendered && typeof element.addMessage === "function") {
        markDeepChatUpgradeReady(element);
      }
      deepChatAdapterState.signature = null;
      renderChatTab();
    }, 50);
  }

  Object.assign(element.style, {
    display: "block",
    width: "100%",
    height: "100%",
    border: "0",
    borderRadius: "0",
    background: "transparent",
  });
  element.chatStyle = {
    width: "100%",
    height: "100%",
    border: "0",
    borderRadius: "0",
    backgroundColor: "var(--bg-primary)",
    fontFamily: "inherit",
  };
  element.requestBodyLimits = { maxMessages: 1, totalMessagesMaxCharLength: 20000 };
  element.maxVisibleMessages = 200;
  element.scrollButton = true;
  element.hiddenMessages = true;
  // Keep raw HTML disabled for model text. The only HTML passed to Deep Chat
  // is generated below from escaped, server-owned ids and normalized tasks.
  element.remarkable = { html: false, breaks: true, linkTarget: "_blank" };
  element.errorMessages = {
    displayServiceErrorMessages: false,
    overrides: { default: t("common.unknownError"), service: t("common.unknownError") },
  };
  element.messageStyles = {
    default: {
      shared: {
        bubble: {
          maxWidth: "85%",
          boxShadow: "none",
          fontFamily: "inherit",
          fontSize: "13px",
          lineHeight: "1.55",
        },
      },
      user: {
        bubble: { backgroundColor: "var(--accent)", color: "#fff" },
      },
      ai: {
        bubble: {
          backgroundColor: "var(--bg-card)",
          color: "var(--text-primary)",
          border: "1px solid var(--border-color)",
        },
      },
    },
    html: {
      shared: {
        bubble: {
          maxWidth: "85%",
          width: "auto",
          padding: "0",
          backgroundColor: "transparent",
          boxShadow: "none",
        },
      },
    },
  };
  element.auxiliaryStyle = [
    ".sa-chat-actions{display:flex;flex-wrap:wrap;gap:6px;margin:4px 0 2px}",
    ".sa-chat-action{border:1px solid var(--border-color);border-radius:6px;padding:5px 9px;background:var(--bg-card);color:var(--accent);font:inherit;font-size:11px;cursor:pointer}",
    ".sa-chat-action:hover{border-color:var(--accent);background:var(--accent-dim)}",
    ".sa-chat-action:disabled{opacity:.55;cursor:default}",
    ".chat-structured-response{display:flex;flex-direction:column;gap:8px;max-width:100%}",
    ".structured-response-item{padding:10px 12px;border:1px solid var(--border-color);border-radius:8px;background:var(--bg-card);color:var(--text-primary)}",
    ".structured-response-headline{font-weight:600;margin-bottom:6px}",
    ".structured-response-row{display:grid;grid-template-columns:auto 1fr;gap:8px;margin-top:4px}",
    ".structured-response-row span,.structured-response-meta,.structured-response-more{color:var(--text-muted);font-size:11px}",
    ".structured-response-row p{margin:0}",
    "pre{overflow:auto;border-radius:7px;padding:10px;background:#111827;color:#f9fafb}",
    "code{font-family:Consolas,'Courier New',monospace}",
    "a{color:var(--accent)}",
  ].join("");
  element.htmlClassUtilities = {
    "sa-chat-retry": {
      events: {
        click: function (event) {
          var target = event.currentTarget || event.target;
          retryChatMessage(target && target.getAttribute("data-message-id"));
        },
      },
    },
    "sa-chat-create-task": {
      events: {
        click: function (event) {
          var target = event.currentTarget || event.target;
          createDeepChatSuggestedTask(target);
        },
      },
    },
  };
  element.connect = { handler: handleDeepChatRequest };
}

function scheduleDeepChatUpgradeFallback(element) {
  if (!window.setTimeout || deepChatAdapterState.upgradeFallbackTimer ||
      deepChatAdapterState.upgradeFailed) return;
  deepChatAdapterState.upgradeFallbackTimer = window.setTimeout(function () {
    deepChatAdapterState.upgradeFallbackTimer = null;
    if (deepChatElement() !== element) return;
    var rendered = element.shadowRoot && element.shadowRoot.querySelector &&
      element.shadowRoot.querySelector("#chat-view");
    if (typeof element.addMessage !== "function" || !rendered) {
      activateDeepChatFallback(element);
    }
  }, 2000);
}

function markDeepChatUpgradeReady(element) {
  if (deepChatElement() !== element) return;
  if (deepChatAdapterState.upgradeFallbackTimer && window.clearTimeout) {
    window.clearTimeout(deepChatAdapterState.upgradeFallbackTimer);
  }
  deepChatAdapterState.upgradeFallbackTimer = null;
}

function activateDeepChatFallback(element) {
  if (deepChatAdapterState.upgradeFailed) return;
  deepChatAdapterState.upgradeFailed = true;
  if (element && element.parentElement) element.parentElement.classList.add("hidden");
  if (state.dom.chatFallback) state.dom.chatFallback.classList.remove("hidden");
  if (state.dom.chatFallbackThread) state.dom.chatThread = state.dom.chatFallbackThread;
  state.dom.deepChat = null;
  deepChatAdapterState.signature = null;
  renderChatTab();
}

function deepChatTransientError(sessionId) {
  return sessionId ? (deepChatAdapterState.transientErrors[sessionId] || null) : null;
}

function setDeepChatTransientError(sessionId, value) {
  if (!sessionId) return;
  if (value) deepChatAdapterState.transientErrors[sessionId] = value;
  else delete deepChatAdapterState.transientErrors[sessionId];
}

function deepChatRequestText(body) {
  var messages = body && Array.isArray(body.messages) ? body.messages : [];
  for (var i = messages.length - 1; i >= 0; i--) {
    if (messages[i] && messages[i].role === "user" && messages[i].text != null) {
      return String(messages[i].text).trim();
    }
  }
  return "";
}

function handleDeepChatRequest(body, signals) {
  var text = deepChatRequestText(body);
  if (!text || state.chatSending) {
    signals.onResponse({ error: t("common.unknownError") });
    return;
  }

  deepChatAdapterState.requestSessionId = state.activeChatSessionId;
  setDeepChatTransientError(deepChatAdapterState.requestSessionId, null);
  return sendChatTabMessage({ source: "deep-chat", text: text }).then(function (outcome) {
    var message = outcome && outcome.message;
    if (message && message.status === "sent") {
      return signals.onResponse({
        text: message.content || t("chat.agentNoContent"),
        role: "ai",
        custom: { id: message.id, status: message.status },
      });
    }
    var reason = outcome && outcome.error;
    var errorText = formatChatErrorMessage(reason || (message && (message.error || message.content)));
    if (outcome && !outcome.inputPersisted) {
      setDeepChatTransientError(
        outcome.sessionId || deepChatAdapterState.requestSessionId, {
        text: errorText,
        draft: text,
      });
    }
    return signals.onResponse({ error: errorText });
  }).catch(function (error) {
    setDeepChatTransientError(deepChatAdapterState.requestSessionId, {
      text: formatChatErrorMessage(error),
      draft: text,
    });
    return signals.onResponse({ error: formatChatErrorMessage(error) });
  }).then(function () {
    var activeRequestSession = deepChatAdapterState.requestSessionId;
    deepChatAdapterState.requestSessionId = null;
    deepChatAdapterState.signature = null;
    renderChatTab();
    if (deepChatTransientError(activeRequestSession) &&
        state.activeChatSessionId === activeRequestSession) {
      deepChatSetDraft(text);
    }
  });
}

function deepChatSetDraft(text) {
  var element = deepChatElement();
  if (!element) return;
  element.defaultInput = { text: text || "" };
  if (typeof element.focusInput === "function") element.focusInput();
}

function deepChatActionHtml(message, retryable) {
  var html = "";
  var messageId = escHtml(String((message && message.id) || ""));
  if (retryable && messageId) {
    html += '<button type="button" class="sa-chat-action sa-chat-retry" data-message-id="' +
      messageId + '">' + escHtml(t("chat.retry")) + '</button>';
  }
  var tasks = normalizeSuggestedTasks(message && message.suggestedTasks);
  for (var i = 0; i < tasks.length; i++) {
    html += '<button type="button" class="sa-chat-action sa-chat-create-task" data-message-id="' +
      messageId + '" data-task-index="' + i + '">' +
      escHtml(t("chat.createTask") + ": " + tasks[i].title) + '</button>';
  }
  return html ? '<div class="sa-chat-actions">' + html + '</div>' : "";
}

function deepChatMessage(message, retryable) {
  var visibleContent = message.content ||
    (message.status === "error" ? message.error : "") || "";
  var result = {
    role: message.role === "assistant" ? "ai" : message.role,
    custom: { id: message.id, status: message.status },
  };
  var structured = parseStructuredChatContent(visibleContent);
  var actions = deepChatActionHtml(message, retryable);
  if (structured) {
    result.html = renderStructuredChatContent(structured) + actions;
  } else {
    result.text = visibleContent;
    if (actions) result.html = actions;
  }
  return result;
}

function deepChatDisplayMessages(session) {
  if (!session) {
    return [{ role: "ai", text: t("chat.selectOrCreateThread"), custom: { transient: true } }];
  }
  if (!session.messagesLoaded) {
    var loadingText = session.messagesLoadError
      ? t("chat.loadSessionFailed", { msg: session.messagesLoadError })
      : t("common.loadingEllipsis");
    return [{ role: "ai", text: loadingText, custom: { transient: true } }];
  }
  if (!session.messages || session.messages.length === 0) {
    return [{
      role: "ai",
      text: t("chat.welcomeTitle") + "\n\n" + t("chat.welcomeHint"),
      custom: { transient: true },
    }];
  }
  var retryableAssistantIdx = state.chatSending ? -1 : getLatestAssistantTurnIndex(session.messages);
  var result = [];
  for (var i = 0; i < session.messages.length; i++) {
    result.push(deepChatMessage(
      session.messages[i],
      i === retryableAssistantIdx &&
        (session.messages[i].status === "error" || session.messages[i].status === "pending")
    ));
  }
  var transientError = deepChatTransientError(session.id);
  if (transientError) {
    result.push({
      role: "ai",
      text: transientError.text,
      custom: { transient: true, status: "error" },
    });
  }
  return result;
}

function deepChatMessagesSignature(session, messages) {
  return JSON.stringify({
    sessionId: session ? session.id : null,
    loaded: !!(session && session.messagesLoaded),
    loadError: session && session.messagesLoadError,
    messages: messages,
  });
}

function renderDeepChatThread() {
  if (deepChatAdapterState.upgradeFailed) return false;
  var element = deepChatElement();
  if (!element) return false;
  if (typeof element.addMessage !== "function") {
    deepChatWhenReady();
    return true;
  }
  configureDeepChat(element);
  var session = getActiveChatSession();
  if (deepChatAdapterState.requestSessionId && session &&
      session.id === deepChatAdapterState.requestSessionId) {
    return true;
  }
  var messages = deepChatDisplayMessages(session);
  var signature = deepChatMessagesSignature(session, messages);
  if (signature === deepChatAdapterState.signature) return true;
  deepChatAdapterState.signature = signature;
  element.clearMessages(false);
  for (var i = 0; i < messages.length; i++) {
    element.addMessage(messages[i], true);
  }
  var transientError = session ? deepChatTransientError(session.id) : null;
  if (transientError && transientError.draft) {
    element.defaultInput = { text: transientError.draft };
  }
  if (typeof element.scrollToBottom === "function") element.scrollToBottom();
  return true;
}

function updateDeepChatInputState(enabled, placeholder) {
  var element = deepChatElement();
  if (!element) return false;
  if (deepChatAdapterState.requestSessionId) return true;
  if (typeof element.addMessage !== "function") {
    deepChatWhenReady();
    return true;
  }
  configureDeepChat(element);
  var key = String(!!enabled) + "|" + String(placeholder || "");
  if (deepChatAdapterState.inputStateKey !== key) {
    deepChatAdapterState.inputStateKey = key;
    element.textInput = deepChatInputConfig(!enabled, placeholder);
    if (typeof element.disableSubmitButton === "function") {
      element.disableSubmitButton(!enabled);
    }
  }
  return true;
}

function focusChatComposer() {
  var element = deepChatElement();
  if (element && typeof element.focusInput === "function") {
    element.focusInput();
    return;
  }
  if (state.dom.chatTabInput && typeof state.dom.chatTabInput.focus === "function") {
    state.dom.chatTabInput.focus();
  }
}

function createDeepChatSuggestedTask(button) {
  if (!button) return;
  var session = getActiveChatSession();
  if (!session) return;
  var messageId = button.getAttribute("data-message-id");
  var index = parseInt(button.getAttribute("data-task-index"), 10);
  var message = findSessionMessage(session, messageId);
  var tasks = normalizeSuggestedTasks(message && message.suggestedTasks);
  if (!Number.isInteger(index) || index < 0 || index >= tasks.length) return;
  createSuggestedTask(tasks[index].title, tasks[index].notes, tasks[index].priority, button);
}
