/* ============================================================
   SelfAnalyst Desktop - File Collector Tab
   ============================================================ */
"use strict";

function isFileCollectionEnabled(overview, status) {
  if (overview && typeof overview.enabled === "boolean") {
    return overview.enabled;
  }
  var collectors = status && status.collectors;
  var fileStatus = collectors && collectors.file;
  return Boolean(fileStatus) && fileStatus !== "disabled";
}

function syncFileUiVisibility() {
  var visible = isFileCollectionEnabled(state.filesOverview, state.status);
  var nav = state.dom && state.dom.fileNavTab;
  if (nav && nav.classList) {
    nav.classList.toggle("hidden", !visible);
  }
  var statusBtn = state.dom && state.dom.fileStatusBtn;
  if (statusBtn && statusBtn.classList) {
    statusBtn.classList.toggle("hidden", !visible);
  }
  if (!visible && state.tab === "files") {
    switchTab("agent");
  }
}

function openFileStatusEntry() {
  if (isFileCollectionEnabled(state.filesOverview, state.status)) {
    switchTab("files");
    return;
  }
  openFileSettingsModal();
}

function loadFiles() {
  state.filesLoadRequestId = (state.filesLoadRequestId || 0) + 1;
  var requestId = state.filesLoadRequestId;
  function requestIsCurrent() {
    return requestId === state.filesLoadRequestId;
  }
  state.filesLoading = true;
  state.filesError = null;
  renderFilesTab();
  return api.getFiles(20)
    .then(function (overview) {
      if (!requestIsCurrent()) return;
      state.filesOverview = overview || null;
    })
    .catch(function (error) {
      if (!requestIsCurrent()) return;
      state.filesError = error && error.message ? error.message : t("common.unknownError");
    })
    .then(function () {
      if (!requestIsCurrent()) return;
      state.filesLoading = false;
      if (!state.fileFoldersDirty) {
        state.fileSettingsPaths = configuredWatchPaths(state.filesOverview);
      }
      syncFileUiVisibility();
      renderFilesTab();
    });
}

function renderFilesTab() {
  var content = state.dom.fileContent;
  if (!content) return;
  var overview = state.filesOverview;
  var status = overview && overview.status ? overview.status : "disabled";

  state.dom.fileTabStatus.innerHTML = statusBadge(status);
  state.dom.fileTabSubtitle.textContent = fileSubtitle(overview);

  if (state.filesLoading && !overview) {
    content.innerHTML = '<div class="loading-placeholder">' + escHtml(t("common.loading")) + "</div>";
    return;
  }
  if (state.filesError && !overview) {
    content.innerHTML = renderFileMessage(
      "error", t("file.loadFailed", { msg: state.filesError }),
      '<button class="btn btn-primary" type="button" data-file-action="retry">' +
        escHtml(t("file.retry")) + "</button>");
    return;
  }
  if (!overview || status === "disabled") {
    content.innerHTML = renderFileDisabled();
    return;
  }

  content.innerHTML = renderFileOverview(overview);
}

function fileSubtitle(overview) {
  if (state.filesLoading && !overview) return t("common.loading");
  if (state.filesError && !overview) return t("file.subtitle");
  if (!overview || overview.status === "disabled") return t("file.subtitle");
  if (overview.status === "degraded") return fileReasonMessage(overview);
  if (overview.latestCollectedAt) {
    return t("file.latest", { time: formatRelativeTime(overview.latestCollectedAt) });
  }
  return t("file.watching");
}

function renderFileDisabled() {
  return '<section class="file-empty-state">' +
    '<div class="file-empty-icon" aria-hidden="true">📁</div>' +
    '<h3>' + escHtml(t("file.enableTitle")) + "</h3>" +
    '<p>' + escHtml(t("file.enableBody")) + "</p>" +
    '<button class="btn btn-primary" type="button" data-file-action="settings">' +
      escHtml(t("file.configure")) + "</button>" +
    '<div class="file-privacy-note"><strong>' + escHtml(t("file.privacyTitle")) + "</strong> " +
      escHtml(t("file.privacyBody")) + "</div>" +
    '<div class="file-restart-note">' + escHtml(t("file.liveApplyNote")) + "</div>" +
  "</section>";
}

function renderFileOverview(overview) {
  var roots = overview.roots || [];
  var totals = overview.totals || {};
  var files = overview.files || [];
  var html = "";

  if (state.filesError) {
    html += renderFileMessage(
      "error", t("file.loadFailed", { msg: state.filesError }),
      '<button class="btn btn-sm btn-outline" type="button" data-file-action="retry">' +
        escHtml(t("file.retry")) + "</button>");
  }

  if (overview.status === "degraded" && overview.reason !== "paths_unavailable") {
    html += renderFileMessage(
      "warning", fileReasonMessage(overview),
      '<button class="btn btn-sm btn-outline" type="button" data-file-action="settings">' +
        escHtml(t("file.checkSettings")) + "</button>" +
      '<button class="btn btn-sm btn-outline" type="button" data-file-action="retry">' +
        escHtml(t("file.retry")) + "</button>");
  } else if (overview.status === "degraded") {
    html += renderFileMessage(
      "warning", fileReasonMessage(overview),
      '<button class="btn btn-sm btn-outline" type="button" data-file-action="retry">' +
        escHtml(t("file.retry")) + "</button>");
  }

  html += '<section class="file-status-strip ' +
    (overview.status === "degraded" ? "is-degraded" : "") + '">' +
    '<div><div class="file-status-title">' +
      escHtml(t(overview.status === "degraded" ? "file.configuredRoots" : "file.monitoringRoots",
        { n: roots.length })) + "</div>" +
    '<div class="file-status-detail">' + escHtml(fileLatestDetail(overview)) + "</div></div>" +
    '<div class="file-counts">' +
      fileCount(totals.collected, t("file.collected")) +
      fileCount(totals.pending, t("file.pending")) +
      fileCount(totals.failed, t("file.failed")) +
    "</div></section>";

  html += '<div class="file-main-grid">' +
    '<section class="card file-recent-panel">' +
      '<div class="card-header"><h3 class="card-title">' + escHtml(t("file.recent")) + "</h3></div>" +
      '<div class="file-list">' + renderRecentFiles(files) + "</div>" +
    "</section>" +
    '<aside class="card file-roots-panel">' +
      '<div class="card-header"><h3 class="card-title">' + escHtml(t("file.roots")) + "</h3>" +
        '<button class="btn btn-sm btn-outline" type="button" data-file-action="add-folder"' +
          (state.fileFoldersSaving ? " disabled" : "") + '>' +
          escHtml(t("file.addFolder")) + "</button></div>" +
      '<div class="file-roots-body">' +
        '<p class="file-folders-hint">' + escHtml(t("file.foldersHint")) + "</p>" +
        renderFileFolderEditor(overview) +
        (state.fileFoldersError
          ? '<div class="file-settings-error" role="alert">' + escHtml(state.fileFoldersError) + "</div>"
          : "") +
        '<button class="btn btn-primary" type="button" data-file-action="save-folders"' +
          (state.fileFoldersSaving ? " disabled" : "") + '>' +
          escHtml(state.fileFoldersSaving ? t("file.savingFolders") : t("file.saveFolders")) +
          "</button>" +
        '<div class="file-privacy-note"><strong>' + escHtml(t("file.privacyTitle")) + "</strong> " +
          escHtml(t("file.privacyBody")) + "</div>" +
        '<div class="file-agent-hint"><strong>' + escHtml(t("file.agentHintTitle")) + "</strong>" +
          '<p>' + escHtml(t("file.agentHintBody")) + "</p></div>" +
      "</div>" +
    "</aside>" +
  "</div>";
  return html;
}

function renderRecentFiles(files) {
  if (!files.length) {
    return '<div class="file-list-empty"><strong>' + escHtml(t("file.empty")) + "</strong>" +
      '<p>' + escHtml(t("file.emptyBody")) + "</p></div>";
  }
  return files.map(function (file) {
    var created = file.fileCreatedAt ? formatRelativeTime(file.fileCreatedAt) : t("file.unknownTime");
    var modified = file.lastModified ? formatRelativeTime(file.lastModified) : t("file.unknownTime");
    return '<article class="file-item">' +
      '<div class="file-item-header"><span class="file-item-name" title="' + escHtml(file.path) + '">' +
        escHtml(fileDisplayName(file)) + '</span><span class="file-item-time">' +
        escHtml(formatRelativeTime(file.lastCollectedAt)) + "</span></div>" +
      '<p class="file-item-metadata">' + escHtml(t("file.metadataLine", {
        created: created, modified: modified, size: formatFileSize(file.sizeBytes),
      })) + "</p>" +
      '<div class="file-item-meta">' +
        '<span class="file-item-path" title="' + escHtml(file.path) + '">' +
          escHtml(file.relativePath || file.path) + "</span></div>" +
    "</article>";
  }).join("");
}

function configuredWatchPaths(overview) {
  return ((overview && overview.roots) || []).map(function (root) {
    return root && root.path ? root.path : "";
  }).filter(Boolean);
}

function fileFolderDraftPaths() {
  var paths = state.fileFoldersDirty
    ? (state.fileSettingsPaths || [])
    : configuredWatchPaths(state.filesOverview);
  return paths.length ? paths : [""];
}

function renderFileFolderEditor() {
  return '<div class="file-settings-paths">' +
    renderFileSettingsPaths(fileFolderDraftPaths(), Boolean(state.fileFoldersSaving)) +
    "</div>";
}

function fileLatestDetail(overview) {
  if (overview.latestCollectedAt && overview.latestPath) {
    return t("file.latestPath", {
      time: formatRelativeTime(overview.latestCollectedAt),
      path: overview.latestPath,
    });
  }
  return overview.status === "running" ? t("file.awaitingFirstCollection") : fileReasonMessage(overview);
}

function fileReasonMessage(overview) {
  var reason = overview && overview.reason ? overview.reason : "runtime_unavailable";
  var key = "file.reason." + reason;
  var message = t(key);
  if (message === key) message = t("file.reason.runtime_unavailable");
  if (overview && overview.error) message += ": " + overview.error;
  return message;
}

function fileDisplayName(file) {
  var value = file.relativePath || file.path || "";
  var parts = value.split(/[\\/]/);
  return parts[parts.length - 1] || value;
}

function formatFileSize(value) {
  var bytes = Number(value || 0);
  if (bytes < 1024) return bytes + " B";
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + " KB";
  return (bytes / (1024 * 1024)).toFixed(1) + " MB";
}

function fileCount(value, label) {
  return '<div class="file-count"><strong>' + escHtml(String(value || 0)) +
    '</strong><span>' + escHtml(label) + "</span></div>";
}

function renderFileMessage(type, text, action) {
  return '<section class="file-message file-message-' + escHtml(type) + '"><span>' +
    escHtml(text) + '</span><div>' + (action || "") + "</div></section>";
}

function openFileSettingsModal() {
  if (!state.filesOverview) {
    return loadFiles().then(function () {
      if (state.filesOverview) openFileSettingsModal();
    });
  }
  state.fileSettingsEnabled = Boolean(state.filesOverview.enabled);
  state.fileSettingsError = null;
  state.fileSettingsSaving = false;
  state.fileSettingsOpen = true;
  state.dom.fileSettingsModal.classList.remove("hidden");
  renderFileSettingsModal();
}

function closeFileSettingsModal() {
  if (state.fileSettingsSaving) return;
  state.fileSettingsOpen = false;
  state.fileSettingsError = null;
  state.dom.fileSettingsModal.classList.add("hidden");
}

function syncFileSettingsDraft() {
  if (!state.dom.fileSettingsEnabled) return;
  state.fileSettingsEnabled = Boolean(state.dom.fileSettingsEnabled.checked);
}

function renderFileSettingsModal() {
  if (!state.dom.fileSettingsModal) return;
  state.dom.fileSettingsEnabled.checked = Boolean(state.fileSettingsEnabled);
  state.dom.fileSettingsEnabled.disabled = Boolean(state.fileSettingsSaving);
  state.dom.fileSettingsCancelBtn.disabled = Boolean(state.fileSettingsSaving);
  state.dom.fileSettingsSaveBtn.disabled = Boolean(state.fileSettingsSaving);
  state.dom.fileSettingsSaveBtn.textContent = state.fileSettingsSaving
    ? t("file.savingSettings") : t("action.save");
  state.dom.fileSettingsError.textContent = state.fileSettingsError || "";
  state.dom.fileSettingsError.classList.toggle("hidden", !state.fileSettingsError);
}

function renderFileSettingsPaths(paths, disabled) {
  if (!paths.length) {
    return '<div class="file-settings-empty">' + escHtml(t("file.noFolderRows")) + "</div>";
  }
  return paths.map(function (path, index) {
    return '<div class="file-settings-path-row">' +
      '<input class="config-input" type="text" data-file-path data-file-path-index="' + index +
        '"' + (disabled ? " disabled" : "") + ' value="' + escHtml(path) + '" placeholder="' +
        escHtml(t("file.folderPlaceholder")) + '">' +
      '<button class="btn btn-icon file-settings-remove-btn" type="button" data-file-settings-remove="' +
        index + '" title="' + escHtml(t("file.removeFolder")) + '" aria-label="' +
        escHtml(t("file.removeFolder")) + '"' + (disabled ? " disabled" : "") + '>×</button>' +
    "</div>";
  }).join("");
}

function syncFileFolderDraft() {
  var content = state.dom.fileContent;
  if (!content) return;
  var inputs = content.querySelectorAll("input[data-file-path]");
  if (!inputs.length) return;
  state.fileSettingsPaths = Array.from(inputs).map(function (input) { return input.value; });
  state.fileFoldersDirty = true;
}

function addFileSettingsPath() {
  syncFileFolderDraft();
  if (!state.fileSettingsPaths.length) state.fileSettingsPaths = fileFolderDraftPaths();
  state.fileSettingsPaths.push("");
  state.fileFoldersDirty = true;
  renderFilesTab();
}

function removeFileSettingsPath(index) {
  syncFileFolderDraft();
  if (!state.fileSettingsPaths.length) state.fileSettingsPaths = fileFolderDraftPaths();
  state.fileSettingsPaths.splice(index, 1);
  state.fileFoldersDirty = true;
  renderFilesTab();
}

function saveFileFolders() {
  if (state.fileFoldersSaving) return Promise.resolve();
  syncFileFolderDraft();
  state.fileFoldersSaving = true;
  state.fileFoldersError = null;
  renderFilesTab();
  var paths = (state.fileSettingsPaths || []).map(function (path) { return path.trim(); })
    .filter(Boolean);
  return api.saveFileSettings({
    enabled: isFileCollectionEnabled(state.filesOverview, state.status),
    paths: paths,
  }).then(function (overview) {
    state.filesOverview = overview || null;
    state.fileFoldersSaving = false;
    state.fileFoldersDirty = false;
    state.fileSettingsPaths = configuredWatchPaths(overview);
    syncFileUiVisibility();
    renderFilesTab();
    return api.getStatus().then(function (status) {
      state.status = status || state.status;
      if (typeof updateStatusBar === "function") updateStatusBar();
    }).catch(function () {});
  }).catch(function (error) {
    state.fileFoldersSaving = false;
    state.fileFoldersError = t("file.saveSettingsFailed", {
      msg: error && error.message ? error.message : t("common.unknownError"),
    });
    renderFilesTab();
  });
}

function saveFileSettings() {
  if (state.fileSettingsSaving) return Promise.resolve();
  syncFileSettingsDraft();
  state.fileSettingsSaving = true;
  state.fileSettingsError = null;
  renderFileSettingsModal();
  return api.saveFileSettings({
    enabled: state.fileSettingsEnabled,
    paths: configuredWatchPaths(state.filesOverview),
  }).then(function (overview) {
    state.filesOverview = overview || null;
    state.fileSettingsSaving = false;
    closeFileSettingsModal();
    syncFileUiVisibility();
    if (state.fileSettingsEnabled && typeof switchTab === "function") {
      switchTab("files");
    }
    renderFilesTab();
    return api.getStatus().then(function (status) {
      state.status = status || state.status;
      if (typeof updateStatusBar === "function") updateStatusBar();
    }).catch(function () {});
  }).catch(function (error) {
    state.fileSettingsSaving = false;
    state.fileSettingsError = t("file.saveSettingsFailed", {
      msg: error && error.message ? error.message : t("common.unknownError"),
    });
    renderFileSettingsModal();
  });
}
