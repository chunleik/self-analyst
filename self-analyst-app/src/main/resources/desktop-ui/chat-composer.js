/* Shared composer for enhanced and fallback message surfaces. */
"use strict";
var chatComposer = {
  key: null, composing: false, compositionEnded: 0, request: null,
  errors: Object.create(null), bound: false,
};

function resizeChatComposer() {
  var input = state.dom.chatTabInput;
  if (!input || !input.style) return;
  var limit = Math.max(72, Math.min(200, Math.floor((window.innerHeight || 800) * 0.25)));
  input.style.height = "auto";
  input.style.height = Math.min(limit, Math.max(72, input.scrollHeight || 72)) + "px";
  input.style.overflowY = (input.scrollHeight || 72) > limit ? "auto" : "hidden";
}

function setChatComposerDraft(text) {
  var input = state.dom.chatTabInput;
  var session = getActiveChatSession();
  var value = String(text || "");
  chatComposer.key = state.activeChatSessionId || "new";
  if (input) input.value = value;
  if (session) session.composerDraft = value;
  resizeChatComposer();
  updateChatComposerState();
}

function chatComposerAvailability() {
  var session = getActiveChatSession();
  if (!state.status || !state.status.llm || !state.status.llm.configured) return "chat.composerUnavailable";
  if (session && session.reconciliationRequired) return "chat.composerReconciliation";
  if (!session || !session.messagesLoaded || session.messagesLoading) return "chat.composerLoading";
  return null;
}

function updateChatComposerState() {
  var input = state.dom.chatTabInput;
  var button = state.dom.chatTabSendBtn;
  if (!input || !button) return;
  var key = state.activeChatSessionId || "new";
  if (chatComposer.key !== key) {
    chatComposer.key = key;
    var session = getActiveChatSession();
    input.value = session && session.composerDraft || "";
  }
  var reason = chatComposerAvailability();
  var request = chatComposer.request;
  var loading = typeof chatImageLoading !== "undefined" && chatImageLoading > 0;
  var canStop = request && request.phase === "generating";
  input.disabled = !!reason || !!state.chatSending;
  input.setAttribute("aria-label", t("chat.composerLabel"));
  var label = canStop ? "chat.composerStop" : request && request.phase === "stopping"
    ? "chat.composerStopping" : state.chatSending || loading ? "chat.composerPreparing" : "chat.composerSend";
  button.textContent = t(label);
  button.setAttribute("aria-label", t(label));
  button.disabled = !canStop && (!!reason || !!state.chatSending || loading ||
    !(String(input.value || "").trim() || (typeof chatHasImages === "function" && chatHasImages())));
  var status = document.getElementById("chat-composer-status");
  if (status) status.textContent = chatComposer.errors[key] ||
    (request || loading ? (label === "chat.composerSend" ? "" : t(label)) : reason ? t(reason) : "");
  resizeChatComposer();
}

function stopChatComposerRequest() {
  var request = chatComposer.request;
  if (!request || request.phase !== "generating") return Promise.resolve();
  request.phase = "stopping";
  updateChatComposerState();
  var timeout;
  var cancellation;
  try { cancellation = api.cancelChat(request.sessionId, request.userId); }
  catch (error) { cancellation = Promise.reject(error); }
  return Promise.race([Promise.resolve(cancellation).catch(function () {}), new Promise(function (resolve) {
    timeout = setTimeout(resolve, 750);
  })]).finally(function () {
    clearTimeout(timeout);
    request.controller.abort();
  });
}

function submitChatComposer() {
  if (chatComposer.request && chatComposer.request.phase === "generating") return stopChatComposerRequest();
  if (state.chatSending || chatComposer.request || chatComposer.composing || chatComposerAvailability() ||
      (typeof chatImageLoading !== "undefined" && chatImageLoading)) return Promise.resolve({ skipped: true });
  var input = state.dom.chatTabInput;
  var text = String(input && input.value || "");
  if (!text.trim() && !(typeof chatHasImages === "function" && chatHasImages())) return Promise.resolve({ skipped: true });
  var key = state.activeChatSessionId || "new";
  var request = { phase: "preparing", controller: new AbortController(), sessionId: key, userId: null, pending: null, text: "" };
  chatComposer.request = request;
  delete chatComposer.errors[key];
  return sendChatTabMessage({
    source: "deep-chat", text: text, streaming: true, signal: request.controller.signal,
    onExecutionStart: function (sessionId, userId, pending) {
      request.phase = "generating";
      request.sessionId = sessionId;
      request.userId = userId;
      request.pending = pending;
      updateChatComposerState();
    },
    onDelta: function (delta) {
      if (request.phase !== "generating" || !request.pending) return;
      request.text += delta;
      request.pending.content = request.text;
      if (state.activeChatSessionId === request.sessionId) renderChatTab();
    },
  }).then(function (outcome) {
    if (outcome && outcome.error && !outcome.inputPersisted) {
      chatComposer.errors[key] = t("chat.composerError", { msg: formatChatErrorMessage(outcome.error) });
    }
    return outcome;
  }).catch(function (error) {
    chatComposer.errors[key] = t("chat.composerError", { msg: formatChatErrorMessage(error) });
    return { error: error };
  }).finally(function () {
    if (chatComposer.request === request) chatComposer.request = null;
    updateChatComposerState();
  });
}

function bindChatComposer() {
  if (chatComposer.bound || !state.dom.chatTabInput || !state.dom.chatTabSendBtn) return;
  chatComposer.bound = true;
  var input = state.dom.chatTabInput;
  input.addEventListener("input", function () {
    var session = getActiveChatSession();
    if (session) session.composerDraft = input.value;
    delete chatComposer.errors[state.activeChatSessionId || "new"];
    updateChatComposerState();
  });
  input.addEventListener("compositionstart", function () { chatComposer.composing = true; });
  input.addEventListener("compositionend", function () {
    chatComposer.composing = false;
    chatComposer.compositionEnded = Date.now();
  });
  input.addEventListener("keydown", function (event) {
    if (event.key !== "Enter" || event.shiftKey || event.isComposing || event.keyCode === 229 ||
        chatComposer.composing || Date.now() - chatComposer.compositionEnded < 50) return;
    event.preventDefault();
    // Enter never turns into a stop action while a response is running.
    if (!state.chatSending) submitChatComposer();
  });
  state.dom.chatTabSendBtn.addEventListener("click", submitChatComposer);
  window.addEventListener("resize", resizeChatComposer);
  updateChatComposerState();
}

function retryChatComposer(messageId) {
  if (state.chatSending || chatComposer.request || chatComposerAvailability()) return;
  var request = { phase: "preparing", controller: new AbortController() };
  chatComposer.request = request;
  return Promise.resolve(retryChatMessage(messageId, {
    composerOwned: true, streaming: true, signal: request.controller.signal,
    onExecutionStart: function (sessionId, userId) {
      request.sessionId = sessionId;
      request.userId = userId;
      request.phase = "generating";
      updateChatComposerState();
    },
  })).finally(function () {
    if (chatComposer.request === request) chatComposer.request = null;
    updateChatComposerState();
  });
}
