"use strict";

var runtimeStorageGeneration = 0;

function loadRuntimeStorage() {
  var container = document.getElementById("runtime-storage");
  if (!container) return Promise.resolve();
  var generation = ++runtimeStorageGeneration;
  container.textContent = t("storage.loading");
  return fetch(API_BASE + "/desktop/config/runtime-storage")
    .then(function (response) {
      if (!response.ok) throw new Error("storage.loadFailed");
      return response.json();
    })
    .then(function (storage) {
      if (generation !== runtimeStorageGeneration) return;
      container.textContent = "";
      var title = document.createElement("strong");
      title.textContent = t("storage.title");
      container.appendChild(title);
      [[t("storage.mode"), t("storage.mode." + storage.mode)],
        [t("storage.runtimeRoot"), storage.runtimeRoot],
        [t("storage.dataRoot"), storage.dataRoot]].forEach(function (entry) {
        var row = document.createElement("div");
        row.textContent = entry[0] + ": " + entry[1];
        row.style.overflowWrap = "anywhere";
        container.appendChild(row);
      });
      var status = document.createElement("div");
      var invoke = window.__TAURI__ && window.__TAURI__.core && window.__TAURI__.core.invoke;
      if (typeof invoke === "function") {
        var button = document.createElement("button");
        button.type = "button";
        button.className = "btn btn-sm";
        button.textContent = t("storage.open");
        button.addEventListener("click", function () {
          button.disabled = true;
          status.textContent = "";
          Promise.resolve().then(function () { return invoke("open_data_directory"); })
            .catch(function () { status.textContent = t("storage.openFailed"); })
            .finally(function () { button.disabled = false; });
        });
        container.appendChild(button);
      } else status.textContent = t("storage.nativeUnavailable");
      container.appendChild(status);
      loadMergedStorage(container);
    }).catch(function () {
      if (generation === runtimeStorageGeneration) container.textContent = t("storage.loadFailed");
    });
}


function loadMergedStorage(container) {
  return fetch(API_BASE + "/desktop/storage/status").then(function (response) {
    if (!response.ok) return null;
    return response.json();
  }).then(function (storage) {
    if (!storage || storage.mode !== "merged") return;
    var panel = document.createElement("div");
    var note = document.createElement("p");
    note.textContent = t("storage.mergedNote");
    panel.appendChild(note);
    [["storage.active", storage.activeBytes], ["storage.auxiliary", storage.auxiliaryBytes],
      ["storage.backups", storage.backupBytes], ["storage.staging", storage.stagingBytes]].forEach(function (entry) {
      var row = document.createElement("div");
      row.textContent = t(entry[0]) + ": " + ((entry[1] || 0) / 1048576).toFixed(1) + " MiB";
      panel.appendChild(row);
    });
    if (storage.migration === "complete" && storage.backupBytes > 0) {
      var button = document.createElement("button");
      button.type = "button"; button.className = "btn btn-sm";
      button.textContent = t("storage.cleanBackups");
      button.addEventListener("click", function () {
        if (!window.confirm(t("storage.backupWarning"))) return;
        button.disabled = true;
        fetch(API_BASE + "/desktop/storage/backups/cleanup", {
          method: "POST", headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ migrationId: storage.migrationId })
        }).then(function (response) {
          if (!response.ok) throw new Error("cleanup");
          panel.remove(); return loadMergedStorage(container);
        }).catch(function () { button.disabled = false; note.textContent = t("storage.cleanFailed"); });
      });
      panel.appendChild(button);
    }
    container.appendChild(panel);
  }).catch(function () {});
}
