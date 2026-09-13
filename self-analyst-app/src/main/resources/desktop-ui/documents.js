"use strict";

var documentView = { sessionId: null, generation: 0, documents: [], timer: null, sending: false, loadedAt: 0, offset: 0, hasMore: false };

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
    documentView.loadedAt = 0;
    panel.replaceChildren();
    force = true;
  }
  clearTimeout(documentView.timer);
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
      documentView.documents = documentMerge(more ? documentView.documents : [], result.documents || []);
      documentView.offset = result.nextOffset;
      documentView.hasMore = !!result.hasMore;
      renderDocumentCards(panel);
    }).catch(function (error) {
      if (generation !== documentView.generation || state.activeChatSessionId !== id) return;
      panel.replaceChildren();
      var notice = document.createElement("p"); notice.textContent = t("document.loadFailed"); panel.appendChild(notice);
      var retry = document.createElement("button"); retry.className = "btn btn-sm";
      retry.textContent = t("chat.retry"); retry.onclick = function () { refreshChatDocuments(true); }; panel.appendChild(retry);
    }).finally(function () {
      if (generation === documentView.generation && state.activeChatSessionId === id && state.chatSending) {
        documentView.timer = setTimeout(function () { refreshChatDocuments(true); }, 1600);
      }
    });
}

function renderDocumentCards(panel) {
  panel.replaceChildren();
  if (!documentView.documents.length) {
    var empty = document.createElement("p"); empty.textContent = t("document.empty"); panel.appendChild(empty);
  }
  documentView.documents.forEach(function (item) {
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
      button.onclick = function () {
        button.disabled = true;
        var action;
        if (nativeSave) action = window.__TAURI__.core.invoke("save_document", { sessionId: item.sessionId, artifactId: item.id });
        else {
          var anchor = document.createElement("a"); anchor.href = documentContentUrl(item.sessionId, item.id);
          anchor.download = item.name; document.body.appendChild(anchor); anchor.click(); anchor.remove();
          action = Promise.resolve("downloadStarted");
        }
        Promise.resolve(action).then(function (result) { status.textContent = t("document." + result); })
          .catch(function () { status.textContent = t("document.saveFailed"); })
          .finally(function () { button.disabled = false; });
      };
      card.appendChild(button); card.appendChild(status);
    }
    panel.appendChild(card);
  });
  if (documentView.hasMore) {
    var more = document.createElement("button"); more.className = "btn btn-sm"; more.textContent = t("document.more");
    more.onclick = function () { refreshChatDocuments(true, true); }; panel.appendChild(more);
  }
}

if (typeof module !== "undefined") module.exports = { documentContentUrl: documentContentUrl, documentMerge: documentMerge };
