"use strict";

var chatImageDrafts = Object.create(null);
var chatImageGenerations = Object.create(null);
var chatImageQueues = Object.create(null);
var chatImageErrors = Object.create(null);
var chatImagePasteBound = false;
var chatImageLoading = 0;
function chatImageKey() { return state.activeChatSessionId || "new"; }
function chatImageDraft(key) { return chatImageDrafts[key || chatImageKey()] || []; }
function chatHasImages() { return chatImageDraft().length > 0; }

function addChatImages(files) {
  if (state.chatSending) return Promise.resolve();
  var key = chatImageKey();
  var generation = chatImageGenerations[key] || 0;
  var selected = Array.from(files || []);
  if (!selected.length) return Promise.resolve();
  chatImageLoading++;
  renderChatImages();
  var queued = (chatImageQueues[key] || Promise.resolve()).then(async function () {
    if ((chatImageGenerations[key] || 0) !== generation) return;
    var draft = chatImageDrafts[key] || (chatImageDrafts[key] = []);
    var rejected = 0;
    var reason = "";
    for (var file of selected) {
      if ((chatImageGenerations[key] || 0) !== generation || chatImageDrafts[key] !== draft) break;
      var url = null;
      try {
        var bytes = draft.reduce(function (sum, item) { return sum + item.file.size; }, 0);
        if (!file || !["image/png", "image/jpeg"].includes(file.type)) throw new Error(t("chat.imageDropInvalid"));
        if (draft.length >= 4 || !Number.isFinite(file.size) || file.size <= 0 ||
            file.size > 5 * 1024 * 1024 || bytes + file.size > 20 * 1024 * 1024) throw new Error(t("chat.imageLimits"));
        url = URL.createObjectURL(file);
        var size = await new Promise(function (resolve, reject) {
          var image = new Image();
          image.onload = function () { resolve(image.naturalWidth * image.naturalHeight); };
          image.onerror = function () { reject(new Error(t("chat.imageInvalid"))); };
          image.src = url;
        });
        if ((chatImageGenerations[key] || 0) !== generation || chatImageDrafts[key] !== draft) {
          URL.revokeObjectURL(url);
          break;
        }
        if (!Number.isFinite(size) || size <= 0 || size > 20000000) throw new Error(t("chat.imageLimits"));
        draft.push({ file: file, url: url });
      } catch (error) {
        if (url) URL.revokeObjectURL(url);
        rejected++;
        reason = error.message || t("chat.imageInvalid");
      }
    }
    if ((chatImageGenerations[key] || 0) === generation && chatImageDrafts[key] === draft) {
      chatImageErrors[key] = rejected ? t("chat.imageRejected").replace("{count}", rejected) + " · " + reason : "";
    }
  }).finally(function () {
    chatImageLoading--;
    if (chatImageQueues[key] === queued) delete chatImageQueues[key];
    renderChatImages();
  });
  chatImageQueues[key] = queued;
  return queued;
}

function releaseChatImage(item, sent) {
  URL.revokeObjectURL(item.url);
  if (!sent && item.uploaded) api.deleteChatImage(item.session, item.uploaded.id).catch(function () {});
}

function clearChatImages(key, sent) {
  key = key || chatImageKey();
  chatImageGenerations[key] = (chatImageGenerations[key] || 0) + 1;
  chatImageDraft(key).forEach(function (item) { releaseChatImage(item, sent); });
  delete chatImageDrafts[key];
  delete chatImageErrors[key];
}

function removeChatImage(key, index) {
  if (state.chatSending) return;
  var draft = chatImageDraft(key);
  if (!draft[index]) return;
  chatImageGenerations[key] = (chatImageGenerations[key] || 0) + 1;
  releaseChatImage(draft.splice(index, 1)[0], false);
  delete chatImageErrors[key];
  renderChatImages();
  var bar = document.getElementById("chat-image-composer");
  if (key === chatImageKey() && bar) {
    var next = bar.querySelector('[data-remove-image="' + Math.min(index, draft.length - 1) + '"]') ||
      bar.querySelector("[data-add-image]");
    if (next) next.focus();
  }
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

function chatImageEventPath(event) {
  return typeof event.composedPath === "function" ? event.composedPath() : [event.target];
}

function bindChatImageInputs() {
  if (chatImagePasteBound || !document.addEventListener) return;
  chatImagePasteBound = true;
  document.addEventListener("paste", function (event) {
    if (state.tab !== "chat" || !chatImageEventPath(event).some(function (node) {
      return node && node.id === "chat-tab-input";
    })) return;
    var files = Array.from(event.clipboardData && event.clipboardData.files || []);
    if (!files.length) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    addChatImages(files);
  }, true);
  document.addEventListener("dragover", function (event) {
    if (state.tab !== "chat" || !chatImageEventPath(event).some(function (node) {
      return node && node.id === "chat-composer";
    })) return;
    var types = Array.from(event.dataTransfer && event.dataTransfer.types || []);
    if (types.includes("Files") || types.includes("text/uri-list")) {
      event.preventDefault();
      if (event.dataTransfer) event.dataTransfer.dropEffect = state.chatSending ? "none" : "copy";
    }
  });
  document.addEventListener("drop", function (event) {
    if (state.tab !== "chat" || !chatImageEventPath(event).some(function (node) {
      return node && node.id === "chat-composer";
    })) return;
    var transfer = event.dataTransfer;
    var files = Array.from(transfer && transfer.files || []);
    var types = Array.from(transfer && transfer.types || []);
    var items = Array.from(transfer && transfer.items || []);
    if (!files.length && !types.includes("Files") && !types.includes("text/uri-list")) return;
    event.preventDefault();
    event.stopPropagation();
    if (state.chatSending) return;
    var directory = items.some(function (item) {
      var entry = typeof item.webkitGetAsEntry === "function" && item.webkitGetAsEntry();
      return entry && entry.isDirectory;
    });
    if (directory || !files.length) {
      chatImageErrors[chatImageKey()] = t("chat.imageDropInvalid");
      renderChatImages();
      return;
    }
    addChatImages(files);
  });
}

function renderChatImages() {
  if (typeof updateChatComposerState === "function") updateChatComposerState();
  bindChatImageInputs();
  var bar = document.getElementById("chat-image-composer");
  if (!bar) return;
  var deepSurface = typeof deepChatElement === "function" && deepChatElement();
  [document, deepSurface && deepSurface.shadowRoot].filter(Boolean).forEach(function (root) {
    if (root.chatImageErrorsBound || !root.addEventListener) return;
    root.chatImageErrorsBound = true;
    root.addEventListener("error", function (event) {
      var image = event.target;
      if (image && image.classList && image.classList.contains("chat-message-image")) image.alt = t("chat.imageUnavailable");
    }, true);
  });
  var key = chatImageKey();
  var draft = chatImageDraft(key);
  var disabled = state.chatSending || chatImageLoading ? " disabled" : "";
  var html = '<div class="chat-image-tools"><button type="button" class="btn btn-sm" data-add-image' + disabled +
    ' aria-label="' + escHtml(t("chat.addImage")) + '" title="' + escHtml(t("chat.addImage")) + '">+</button>' +
    '<input type="file" accept="image/png,image/jpeg" multiple hidden tabindex="-1"' + disabled + '>' +
    '<details class="chat-image-help"><summary>' + escHtml(t("chat.imageHelp")) + '</summary><span>' +
    escHtml(t("chat.imageLimits")) + '</span></details>' +
    (draft.length ? '<span class="chat-image-count">' + escHtml(t("chat.imageCount").replace("{count}", draft.length)) + '</span>' : '') + '</div>' +
    '<div class="chat-image-previews">' + draft.map(function (item, index) {
      return '<div><img src="' + escHtml(item.url) + '" alt="' + escHtml(t("chat.imagePreview")) + ' ' + (index + 1) +
        '"><button type="button" data-remove-image="' + index + '"' + disabled + ' aria-label="' +
        escHtml(t("chat.removeImage")) + ' ' + (index + 1) + '">' + escHtml(t("chat.removeImage")) + ' ' + (index + 1) + '</button></div>';
    }).join("") + '</div><div class="chat-image-error" role="status" aria-live="polite">' +
    escHtml(chatImageErrors[key] || "") + '</div>';
  // Streaming renders must not replace focused controls or collapse the help.
  if (bar.chatImageSignature === key + html) return;
  bar.chatImageSignature = key + html;
  var oldHelp = bar.querySelector("details");
  var helpOpen = oldHelp && oldHelp.open;
  var active = document.activeElement;
  var focusedAdd = active && active === bar.querySelector("[data-add-image]");
  var focusedHelp = active && active === bar.querySelector("summary");
  bar.innerHTML = html;
  var help = bar.querySelector("details");
  if (help) help.open = !!helpOpen;
  var input = bar.querySelector("input");
  var add = bar.querySelector("[data-add-image]");
  add.onclick = function () { input.click(); };
  function focusComposer() {
    if (key !== chatImageKey()) return;
    var composer = document.getElementById("chat-tab-input");
    if (composer && !composer.disabled) composer.focus();
  }
  input.onchange = function (event) {
    if (key !== chatImageKey()) return;
    var files = Array.from(event.target.files || []);
    event.target.value = "";
    addChatImages(files);
    focusComposer();
  };
  input.oncancel = focusComposer;
  bar.querySelectorAll("[data-remove-image]").forEach(function (button) {
    button.onclick = function () { removeChatImage(key, Number(button.dataset.removeImage)); };
  });
  if (focusedAdd && !add.disabled) add.focus();
  else if (focusedHelp) bar.querySelector("summary").focus();
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
