"use strict";

var chatImageDrafts = Object.create(null);
var chatImagePasteBound = false;
var chatImageLoading = 0;
function chatImageKey() { return state.activeChatSessionId || "new"; }
function chatImageDraft(key) { return chatImageDrafts[key || chatImageKey()] || []; }
function chatHasImages() { return chatImageDraft().length > 0; }

async function addChatImages(files) {
  if (state.chatSending) return;
  var key = chatImageKey();
  var draft = chatImageDrafts[key] || (chatImageDrafts[key] = []);
  chatImageLoading++;
  try {
    for (var file of Array.from(files)) {
      if (draft.length >= 4 || file.size === 0 || file.size > 5 * 1024 * 1024 ||
          !["image/png", "image/jpeg"].includes(file.type)) throw new Error(t("chat.imageLimits"));
      var url = URL.createObjectURL(file);
      try {
        var size = await new Promise(function (resolve, reject) {
          var image = new Image();
          image.onload = function () { resolve(image.naturalWidth * image.naturalHeight); };
          image.onerror = function () { reject(new Error(t("chat.imageInvalid"))); };
          image.src = url;
        });
        if (size > 20000000 || draft.length >= 4) throw new Error(t("chat.imageLimits"));
        draft.push({ file: file, url: url });
      } catch (error) { URL.revokeObjectURL(url); throw error; }
    }
  } catch (error) { alert(error.message); }
  chatImageLoading--;
  renderChatImages();
}

function clearChatImages(key, sent) {
  var draft = chatImageDraft(key);
  draft.forEach(function (item) {
    URL.revokeObjectURL(item.url);
    if (!sent && item.uploaded) api.deleteChatImage(item.session, item.uploaded.id).catch(function () {});
  });
  delete chatImageDrafts[key];
}

async function uploadChatImages(session, draft) {
  var ids = [];
  for (var item of draft) {
    if (!item.uploaded || item.session !== session || Date.now() - item.uploadedAt > 23 * 3600000) {
      item.uploaded = await api.uploadChatImage(session, item.file);
      item.session = session;
      item.uploadedAt = Date.now();
    }
    ids.push(item.uploaded.id);
  }
  return ids;
}

function chatImageHtml(message) {
  return (message.images || []).filter(function (image) {
    return /^\/desktop\/chat\/sessions\/[a-f0-9]{32}\/images\/[a-f0-9]{32}$/.test(image.url);
  }).map(function (image) {
    return '<img class="chat-message-image" style="max-width:240px;max-height:180px;object-fit:contain" src="' +
      escHtml(image.url) + '" alt="' + escHtml(t("chat.imagePreview")) + '" loading="lazy">';
  }).join("");
}

function renderChatImages() {
  var bar = document.getElementById("chat-image-composer");
  if (!bar) return;
  var deepSurface = typeof deepChatElement === "function" && deepChatElement();
  [document, deepSurface && deepSurface.shadowRoot].filter(Boolean).forEach(function (root) {
    if (root.chatImageErrorsBound) return;
    root.chatImageErrorsBound = true;
    root.addEventListener("error", function (event) {
      var image = event.target;
      if (image && image.classList && image.classList.contains("chat-message-image")) image.alt = t("chat.imageUnavailable");
    }, true);
  });
  var key = chatImageKey();
  var draft = chatImageDraft(key);
  var disabled = state.chatSending || chatImageLoading ? " disabled" : "";
  bar.innerHTML = '<label class="btn btn-sm">' + escHtml(t("chat.addImage")) +
    '<input type="file" accept="image/png,image/jpeg" multiple' + disabled + '></label>' +
    '<span class="chat-image-hint">' + escHtml(t("chat.imageLimits")) + '</span>' +
    '<div class="chat-image-previews">' + draft.map(function (item, index) {
      return '<div><img src="' + escHtml(item.url) + '" alt="' + escHtml(t("chat.imagePreview")) +
        '"><button type="button" data-remove-image="' + index + '"' + disabled + '>' +
        escHtml(t("chat.removeImage")) + '</button></div>';
    }).join("") + '</div>' + (draft.length ? '<button type="button" class="btn btn-primary" data-send-images' +
      (state.chatSending || chatImageLoading || !state.status || !state.status.llm || !state.status.llm.configured ? " disabled" : "") +
      '>' + escHtml(t("chat.sendImages")) + '</button>' : "");
  bar.querySelector("input").onchange = function (event) { addChatImages(event.target.files); };
  bar.querySelectorAll("[data-remove-image]").forEach(function (button) {
    button.onclick = function () {
      var removed = draft.splice(Number(button.dataset.removeImage), 1)[0];
      URL.revokeObjectURL(removed.url);
      if (removed.uploaded) api.deleteChatImage(removed.session, removed.uploaded.id).catch(function () {});
      renderChatImages();
    };
  });
  var send = bar.querySelector("[data-send-images]");
  if (send) send.onclick = function () {
    var deep = typeof deepChatElement === "function" && deepChatElement();
    var deepInput = deep && deep.shadowRoot && deep.shadowRoot.querySelector("#text-input");
    var enhanced = deepInput && !deepChatAdapterState.upgradeFailed;
    if (enhanced) {
      deepChatAdapterState.requestSessionId = key;
      sendChatTabMessage({ source: "deep-chat", text: deepInput.textContent || "" }).then(function (outcome) {
        if (outcome.error && !outcome.inputPersisted) alert(formatChatErrorMessage(outcome.error));
        if (outcome.inputPersisted && chatImageKey() === key) {
          var current = deep.shadowRoot && deep.shadowRoot.querySelector("#text-input");
          if (current) { current.textContent = ""; current.dispatchEvent(new Event("input", { bubbles: true })); }
        }
      }).finally(function () {
        deepChatAdapterState.requestSessionId = null;
        deepChatAdapterState.signature = null;
        renderChatTab();
      });
    } else sendChatTabMessage();
  };
  if (!chatImagePasteBound) {
    chatImagePasteBound = true;
    document.addEventListener("paste", function (event) {
      if (state.tab !== "chat") return;
      var path = typeof event.composedPath === "function" ? event.composedPath() : [event.target];
      if (!path.some(function (node) { return node && (node.id === "text-input" || node.id === "chat-tab-input"); })) return;
      var files = Array.from(event.clipboardData && event.clipboardData.files || []);
      if (!files.length) return;
      event.preventDefault();
      event.stopImmediatePropagation();
      addChatImages(files);
    }, true);
    document.addEventListener("keydown", function (event) {
      if (state.tab !== "chat" || event.key !== "Enter" || event.shiftKey || event.isComposing ||
          !chatHasImages() || state.chatSending || chatImageLoading) return;
      var path = typeof event.composedPath === "function" ? event.composedPath() : [event.target];
      if (!path.some(function (node) { return node && (node.id === "text-input" || node.id === "chat-tab-input"); })) return;
      event.preventDefault(); event.stopImmediatePropagation();
      var button = bar.querySelector("[data-send-images]");
      if (button && !button.disabled) button.click();
    }, true);
  }
}

api.uploadChatImage = function (session, file) {
  return fetch(API_BASE + "/desktop/chat/sessions/" + encodeURIComponent(session) + "/images", {
    method: "POST", headers: { "Content-Type": file.type }, body: file,
  }).then(function (response) { return chatJsonResponse(response, t("chat.imageInvalid")); });
};
api.deleteChatImage = function (session, id) {
  return fetch(API_BASE + "/desktop/chat/sessions/" + encodeURIComponent(session) + "/images/" + encodeURIComponent(id),
    { method: "DELETE" });
};
