"use strict";

var documentView = { sessionId: null, generation: 0, documents: [], timer: null, sending: false, loadedAt: 0, offset: 0, hasMore: false };
var documentSaves = new Map();
var documentCardStyles = ".document-card{display:flex;flex-direction:column;gap:7px;padding:12px;border:1px solid var(--border-color,#ddd);border-radius:8px;overflow-wrap:anywhere;min-width:0;background:var(--bg-card,#fff);color:var(--text-primary,#222)}.document-card small{opacity:.75}.document-card button{align-self:flex-start;font:inherit;padding:5px 10px;cursor:pointer}.chat-document-slot{display:flex;flex-direction:column;gap:8px;min-width:0;max-width:100%;margin-top:8px}.chat-document-slot:empty{display:none}";

function documentTurnId(session, index) {
  if (!session || session.messages[index].role !== "assistant") return null;
  for (var i = index - 1; i >= 0; i--) {
    if (session.messages[i].role === "assistant") return null;
    if (session.messages[i].role === "user") return session.messages[i].id;
  }
  return null;
}

function documentSlotHtml(session, index) {
  var turn = documentTurnId(session, index);
  return turn ? '<div class="chat-document-slot" data-document-turn="' + escHtml(turn) + '"></div>' : "";
}

function mountChatDocuments() {
  if (typeof getActiveChatSession !== "function") return;
  var session = getActiveChatSession();
  if (!session || session.id !== documentView.sessionId) return;
  var element = typeof deepChatElement === "function" && deepChatElement();
  var root = element && element.shadowRoot;
  if (!root) root = state.dom && state.dom.chatThread;
  if (!root || !root.querySelectorAll) return;
  // The streaming component owns the current text bubble. Attach beside it,
  // without replacing its text or restarting the component's stream.
  if (element && typeof deepChatAdapterState !== "undefined" && deepChatAdapterState.requestSessionId === session.id) {
    var index = session.messages.length - 1;
    var turn = index >= 0 && documentTurnId(session, index);
    if (turn && documentView.documents.some(function (item) { return item.userMessageId === turn; })) {
      var existing = Array.from(root.querySelectorAll("[data-document-turn]")).find(function (slot) { return slot.getAttribute("data-document-turn") === turn; });
      var bubbles = root.querySelectorAll(".deep-chat-outer-container-role-ai, .deep-chat-outer-container-role-user");
      var last = bubbles[bubbles.length - 1];
      if (existing && existing.getAttribute("data-document-live") && last && last.nextElementSibling !== existing) last.after(existing);
      if (!existing) {
        if (last) {
          var slot = document.createElement("div"); slot.className = "chat-document-slot";
          slot.setAttribute("data-document-live", "true");
          slot.setAttribute("data-document-turn", turn); last.after(slot);
        }
      }
    }
  }
  root.querySelectorAll("[data-document-turn]").forEach(function (slot) {
    var turn = slot.getAttribute("data-document-turn");
    renderDocumentCards(slot, documentView.documents.filter(function (item) {
      return item.sessionId === session.id && item.userMessageId === turn;
    }));
  });
}

function documentContentUrl(sessionId, artifactId) {
  if (!/^[a-f0-9]{32}$/.test(sessionId) || !/^[a-f0-9]{32}$/.test(artifactId)) throw new Error("Invalid document identifier");
  return "/desktop/chat/sessions/" + sessionId + "/documents/" + artifactId + "/content";
}

function documentMerge(existing, incoming) {
  var byId = new Map();
  existing.concat(incoming).forEach(function (item) {
    if (item && /^[a-f0-9]{32}$/.test(item.id)) byId.set(item.id, item);
  });
  return Array.from(byId.values());
}

function refreshChatDocuments(force, more) {
  var panel = document.getElementById("chat-document-list");
  if (!panel) return;
  var id = state.activeChatSessionId;
  if (id !== documentView.sessionId) {
    documentView.sessionId = id;
    documentView.generation++;
    documentView.documents = [];
    documentView.offset = 0;
    documentView.hasMore = false;
    documentView.loadedAt = 0;
    var history = document.getElementById("chat-document-history");
    if (history) history.open = false;
    panel.replaceChildren();
    force = true;
  }
  clearTimeout(documentView.timer);
  mountChatDocuments();
  if (!id) return;
  if (documentView.sending !== !!state.chatSending) force = true;
  documentView.sending = !!state.chatSending;
  if (!force && Date.now() - documentView.loadedAt < 1500) {
    if (state.chatSending) documentView.timer = setTimeout(function () { refreshChatDocuments(true); }, 1600);
    return;
  }
  documentView.loadedAt = Date.now();
  var generation = ++documentView.generation;
  var offset = more ? documentView.offset : 0;
  return fetch(API_BASE + "/desktop/chat/sessions/" + id + "/documents?offset=" + offset)
    .then(function (response) { return chatJsonResponse(response, t("document.loadFailed")); })
    .then(function (result) {
      if (generation !== documentView.generation || state.activeChatSessionId !== id) return;
      documentView.documents = documentMerge(documentView.documents, (result.documents || []).filter(function (item) { return item.sessionId === id; }));
      if (more || offset === documentView.offset || !documentView.offset) {
        documentView.offset = result.nextOffset;
        documentView.hasMore = !!result.hasMore;
      }
      documentView.offset = Math.max(documentView.offset || 0, documentView.documents.length);
      renderDocumentCards(panel);
      mountChatDocuments();
    }).catch(function (error) {
      if (generation !== documentView.generation || state.activeChatSessionId !== id) return;
      panel.replaceChildren();
      panel.documentSignature = null;
      var notice = document.createElement("p"); notice.textContent = t("document.loadFailed"); panel.appendChild(notice);
      var retry = document.createElement("button"); retry.className = "btn btn-sm";
      retry.textContent = t("chat.retry"); retry.onclick = function () { refreshChatDocuments(true); }; panel.appendChild(retry);
    }).finally(function () {
      if (generation === documentView.generation && state.activeChatSessionId === id && state.chatSending) {
        documentView.timer = setTimeout(function () { refreshChatDocuments(true); }, 1600);
      }
    });
}

function renderDocumentCards(panel, items) {
  if (!panel) return;
  var inline = !!items;
  items = items || documentView.documents;
  var signature = JSON.stringify([state.lang, items, inline ? false : documentView.hasMore, items.map(function (item) { return documentSaves.get(item.sessionId + ":" + item.id) || { busy: false, result: "" }; })]);
  if (panel.documentSignature === signature) return;
  panel.documentSignature = signature;
  panel.replaceChildren();
  if (!inline && !items.length) {
    var empty = document.createElement("p"); empty.textContent = t("document.empty"); panel.appendChild(empty);
  }
  items.forEach(function (item) {
    var card = document.createElement("article"); card.className = "document-card";
    var title = document.createElement("strong"); title.textContent = item.name;
    var detail = document.createElement("small");
    detail.textContent = item.status === "READY"
      ? item.format + " · " + Math.ceil(item.size / 1024) + " KB · v" + item.version
      : t("document.status." + item.status);
    card.appendChild(title); card.appendChild(detail);
    if (item.status === "READY") {
      var button = document.createElement("button"); button.className = "btn btn-sm";
      var nativeSave = window.__TAURI__ && window.__TAURI__.core;
      button.textContent = t(nativeSave ? "document.saveAs" : "document.download");
      var status = document.createElement("span"); status.setAttribute("role", "status");
      var saveKey = item.sessionId + ":" + item.id;
      var save = documentSaves.get(saveKey) || { busy: false, result: "" };
      documentSaves.set(saveKey, save);
      function updateSave() { button.disabled = save.busy; status.textContent = save.result ? t("document." + save.result) : ""; }
      updateSave();
      button.onclick = function () {
        if (save.busy) return;
        save.busy = true; updateSave();
        var action = Promise.resolve().then(function () {
          if (nativeSave) return window.__TAURI__.core.invoke("save_document", { sessionId: item.sessionId, artifactId: item.id });
          var anchor = document.createElement("a"); anchor.href = documentContentUrl(item.sessionId, item.id);
          anchor.download = item.name; document.body.appendChild(anchor); anchor.click(); anchor.remove();
          return "downloadStarted";
        });
        action.then(function (result) { save.result = result; })
          .catch(function () { save.result = "saveFailed"; })
          .finally(function () { save.busy = false; updateSave(); renderDocumentCards(document.getElementById("chat-document-list")); mountChatDocuments(); });
      };
      card.appendChild(button); card.appendChild(status);
    }
    panel.appendChild(card);
  });
  if (!inline && documentView.hasMore) {
    var more = document.createElement("button"); more.className = "btn btn-sm"; more.textContent = t("document.more");
    more.onclick = function () { refreshChatDocuments(true, true); }; panel.appendChild(more);
  }
}

if (typeof module !== "undefined") module.exports = { documentContentUrl: documentContentUrl, documentMerge: documentMerge };
