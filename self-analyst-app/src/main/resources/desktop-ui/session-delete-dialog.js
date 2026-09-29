/* Application-owned confirmation; the browser supplies modal background isolation. */
"use strict";

var confirmSessionDeletion = (function () {
  var pending = false;
  return function (restoreFocus) {
    if (pending) return Promise.resolve(false);
    var dialog = document.getElementById("session-delete-dialog");
    var cancel = document.getElementById("session-delete-cancel");
    var confirm = document.getElementById("session-delete-confirm");
    document.getElementById("session-delete-title").textContent = t("chat.deleteDialogTitle");
    document.getElementById("session-delete-description").textContent = t("chat.confirmDeleteSession");
    cancel.textContent = t("action.cancel");
    confirm.textContent = t("chat.deleteConfirm");
    confirm.disabled = false;
    pending = true;

    return new Promise(function (resolve, reject) {
      var accepted = false;
      var closing = false;
      function close(value) {
        if (closing) return;
        closing = true;
        accepted = value;
        confirm.disabled = true;
        dialog.close();
      }
      function onCancel(event) { event.preventDefault(); close(false); }
      function onConfirm() { close(true); }
      function onKeydown(event) {
        if (event.key !== "Tab") return;
        if (event.shiftKey && document.activeElement === cancel) {
          event.preventDefault(); confirm.focus();
        } else if (!event.shiftKey && document.activeElement === confirm) {
          event.preventDefault(); cancel.focus();
        }
      }
      function cleanup() {
        dialog.removeEventListener("cancel", onCancel);
        dialog.removeEventListener("close", onClose);
        dialog.removeEventListener("keydown", onKeydown);
        cancel.removeEventListener("click", onCancel);
        confirm.removeEventListener("click", onConfirm);
        pending = false;
      }
      function onClose() {
        cleanup();
        restoreFocus();
        resolve(accepted);
      }
      dialog.addEventListener("cancel", onCancel);
      dialog.addEventListener("close", onClose);
      dialog.addEventListener("keydown", onKeydown);
      cancel.addEventListener("click", onCancel);
      confirm.addEventListener("click", onConfirm);
      try {
        dialog.showModal();
        cancel.focus();
      } catch (error) {
        cleanup();
        reject(error);
      }
    });
  };
})();
