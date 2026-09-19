"use strict";

var runtimeStorageGeneration = 0;

function stopRuntimeStorage() {
  runtimeStorageGeneration++;
}

function storageElement(tag, className, text) {
  var element = document.createElement(tag);
  if (className) element.className = className;
  if (text != null) element.textContent = text;
  return element;
}

function storageButton(label, action) {
  var button = storageElement("button", "btn btn-sm btn-outline", t(label));
  button.type = "button";
  button.addEventListener("click", action);
  return button;
}

function storageJson(url, options) {
  return fetch(API_BASE + url, options).then(function (response) {
    if (!response.ok) throw new Error("storage request failed");
    return response.json();
  });
}

function loadRuntimeStorage() {
  var container = document.getElementById("runtime-storage");
  if (!container) return Promise.resolve();
  var generation = ++runtimeStorageGeneration;
  var current = function () { return generation === runtimeStorageGeneration; };
  container.textContent = "";
  var header = storageElement("header", "storage-heading");
  var introduction = storageElement("div");
  var title = storageElement("h3", "", t("storage.title"));
  title.id = "runtime-storage-title";
  introduction.appendChild(title);
  introduction.appendChild(storageElement("p", "", t("storage.intro")));
  header.appendChild(introduction);
  var refresh = storageButton("storage.refresh", function () { if (current()) loadRuntimeStorage(); });
  header.appendChild(refresh);
  container.appendChild(header);
  var status = storageElement("p", "storage-feedback", t("storage.loading"));
  status.setAttribute("role", "status");
  container.appendChild(status);
  var body = storageElement("div", "storage-body");
  container.appendChild(body);
  refresh.disabled = true;

  return storageJson("/desktop/config/runtime-storage").then(function (storage) {
    if (!current()) return;
    status.textContent = "";
    var location = storageElement("div", "storage-location");
    [["storage.mode", t("storage.mode." + storage.mode)],
      ["storage.runtimeRoot", storage.runtimeRoot], ["storage.dataRoot", storage.dataRoot]].forEach(function (entry) {
      var row = storageElement("div", "storage-location-row");
      row.appendChild(storageElement("span", "", t(entry[0])));
      row.appendChild(storageElement(entry[0] === "storage.mode" ? "strong" : "code", "", entry[1]));
      location.appendChild(row);
    });
    var actions = storageElement("div", "storage-location-actions");
    var openStatus = storageElement("span");
    openStatus.setAttribute("role", "status");
    var invoke = window.__TAURI__ && window.__TAURI__.core && window.__TAURI__.core.invoke;
    if (typeof invoke === "function") {
      var open = storageButton("storage.open", function () {
        if (open.disabled || !current()) return;
        open.disabled = true;
        openStatus.textContent = "";
        Promise.resolve().then(function () { return invoke("open_data_directory"); })
          .catch(function () { if (current()) openStatus.textContent = t("storage.openFailed"); })
          .finally(function () { if (current()) open.disabled = false; });
      });
      actions.appendChild(open);
    } else openStatus.textContent = t("storage.nativeUnavailable");
    actions.appendChild(openStatus);
    location.appendChild(actions);
    body.appendChild(location);
    return loadMergedStorage(body, current, refresh);
  }).catch(function () {
    if (current()) { status.textContent = t("storage.loadFailed"); status.classList.add("error"); }
  }).finally(function () { if (current()) refresh.disabled = false; });
}

function loadMergedStorage(container, current, refresh) {
  current = current || function () { return true; };
  var panel = storageElement("section", "storage-usage");
  var status = storageElement("p", "storage-feedback", t("storage.capacityLoading"));
  status.setAttribute("role", "status");
  panel.appendChild(status);
  container.appendChild(panel);
  function retry() { panel.remove(); return loadMergedStorage(container, current, refresh); }
  return storageJson("/desktop/storage/status").then(function (storage) {
    if (!current()) return;
    if (storage.mode !== "merged") { panel.remove(); return; }
    var entries = [["storage.active", storage.activeBytes], ["storage.auxiliary", storage.auxiliaryBytes],
      ["storage.backups", storage.backupBytes], ["storage.staging", storage.stagingBytes]];
    // Missing statistics are unknown, never a successful zero-byte result.
    if (entries.some(function (entry) { return typeof entry[1] !== "number" || !Number.isFinite(entry[1]) || entry[1] < 0; })) {
      throw new Error("missing storage capacity");
    }
    status.textContent = "";
    panel.appendChild(storageElement("h4", "", t("storage.usage")));
    var total = entries.reduce(function (sum, entry) { return sum + entry[1]; }, 0);
    var amount = storageElement("div", "storage-total", (total / 1048576).toFixed(1));
    amount.appendChild(storageElement("small", "", " MiB · " + t("storage.total")));
    panel.appendChild(amount);
    var track = storageElement("div", "storage-track");
    track.setAttribute("aria-hidden", "true");
    var cards = storageElement("div", "storage-stats");
    entries.forEach(function (entry, index) {
      var segment = storageElement("span", "storage-part storage-part-" + index);
      segment.style.flexGrow = String(entry[1]);
      track.appendChild(segment);
      var card = storageElement("div", "storage-stat storage-part-" + index);
      card.appendChild(storageElement("span", "", t(entry[0])));
      card.appendChild(storageElement("strong", "", (entry[1] / 1048576).toFixed(1)));
      card.appendChild(storageElement("small", "", "MiB"));
      cards.appendChild(card);
    });
    panel.appendChild(track);
    panel.appendChild(cards);
    panel.appendChild(storageElement("p", "storage-note", t("storage.mergedNote") + " " + t("storage.capacityNote")));
    if (storage.backupBytes === 0) return;
    var backup = storageElement("div", "storage-backup");
    var description = storageElement("div");
    var canClean = storage.migration === "complete";
    description.appendChild(storageElement("h4", "", t(canClean ? "storage.backupReady" : "storage.migrationPending")));
    description.appendChild(storageElement("p", "", t(canClean ? "storage.backupWarning" : "storage.activeRetained")));
    backup.appendChild(description);
    if (canClean) {
      var cleanup = storageButton("storage.cleanBackups", function () {
        if (cleanup.disabled || !current() || !window.confirm(t("storage.backupWarning"))) return;
        cleanup.disabled = true;
        if (refresh) refresh.disabled = true;
        status.classList.remove("error");
        status.textContent = t("storage.cleaning");
        fetch(API_BASE + "/desktop/storage/backups/cleanup", {
          method: "POST", headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ migrationId: storage.migrationId })
        }).then(function (response) {
          if (!response.ok) throw new Error("cleanup failed");
          if (current()) return retry();
        }).catch(function () {
          if (!current()) return;
          status.classList.add("error"); status.textContent = t("storage.cleanFailed");
          cleanup.disabled = false;
        }).finally(function () { if (current() && refresh) refresh.disabled = false; });
      });
      backup.appendChild(cleanup);
    }
    panel.appendChild(backup);
  }).catch(function () {
    if (!current()) return;
    status.classList.add("error"); status.textContent = t("storage.capacityFailed");
    panel.appendChild(storageButton("storage.refresh", function () { if (current()) retry(); }));
  });
}
