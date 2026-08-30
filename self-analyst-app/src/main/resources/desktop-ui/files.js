/* ============================================================
   SelfAnalyst Desktop - File Collector Tab
   ============================================================ */
"use strict";

function loadFiles() {
  state.filesLoading = true;
  state.filesError = null;
  renderFilesTab();
  return api.getFiles(20)
    .then(function (overview) {
      state.filesOverview = overview || null;
    })
    .catch(function (error) {
      state.filesError = error && error.message ? error.message : t("common.unknownError");
    })
    .then(function () {
      state.filesLoading = false;
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
  if (overview.latestIndexedAt) {
    return t("file.latest", { time: formatRelativeTime(overview.latestIndexedAt) });
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
    '<div class="file-restart-note">' + escHtml(t("file.restartNote")) + "</div>" +
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

  if (overview.status === "degraded") {
    html += renderFileMessage(
      "warning", fileReasonMessage(overview),
      '<button class="btn btn-sm btn-outline" type="button" data-file-action="settings">' +
        escHtml(t("file.checkSettings")) + "</button>");
  }

  html += '<section class="file-status-strip ' +
    (overview.status === "degraded" ? "is-degraded" : "") + '">' +
    '<div><div class="file-status-title">' +
      escHtml(t("file.monitoringRoots", { n: roots.length })) + "</div>" +
    '<div class="file-status-detail">' + escHtml(fileLatestDetail(overview)) + "</div></div>" +
    '<div class="file-counts">' +
      fileCount(totals.indexed, t("file.indexed")) +
      fileCount(totals.pending, t("file.pending")) +
      fileCount(totals.failed, t("file.failed")) +
    "</div></section>";

  if (overview.semantic && overview.semantic.configured && !overview.semantic.available) {
    html += '<div class="file-semantic-note">' + escHtml(t("file.semanticUnavailable")) + "</div>";
  }

  html += '<div class="file-main-grid">' +
    '<section class="card file-recent-panel">' +
      '<div class="card-header"><h3 class="card-title">' + escHtml(t("file.recent")) + "</h3></div>" +
      '<div class="file-list">' + renderRecentFiles(files) + "</div>" +
    "</section>" +
    '<aside class="card file-roots-panel">' +
      '<div class="card-header"><h3 class="card-title">' + escHtml(t("file.roots")) + "</h3></div>" +
      '<div class="file-roots-body">' + renderWatchRoots(roots) +
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
    var topics = (file.mainTopics || []).map(function (topic) {
      return '<span class="file-topic">' + escHtml(topic) + "</span>";
    }).join("");
    return '<article class="file-item">' +
      '<div class="file-item-header"><span class="file-item-name" title="' + escHtml(file.path) + '">' +
        escHtml(fileDisplayName(file)) + '</span><span class="file-item-time">' +
        escHtml(formatRelativeTime(file.lastIndexedAt)) + "</span></div>" +
      '<p class="file-item-summary">' + escHtml(file.summary || t("file.noSummary")) + "</p>" +
      '<div class="file-item-meta">' + topics +
        '<span class="file-item-path" title="' + escHtml(file.path) + '">' +
          escHtml(file.relativePath || file.path) + "</span></div>" +
    "</article>";
  }).join("");
}

function renderWatchRoots(roots) {
  if (!roots.length) return '<div class="file-list-empty">' + escHtml(t("file.noRoots")) + "</div>";
  return roots.map(function (root) {
    var counts = root.counts || {};
    return '<div class="file-root-item"><div class="file-root-path" title="' + escHtml(root.path) + '">' +
      escHtml(root.path) + '</div><div class="file-root-counts">' +
      escHtml(t("file.rootCounts", {
        indexed: counts.indexed || 0,
        pending: counts.pending || 0,
        failed: counts.failed || 0,
      })) + "</div></div>";
  }).join("");
}

function fileLatestDetail(overview) {
  if (overview.latestIndexedAt && overview.latestPath) {
    return t("file.latestPath", {
      time: formatRelativeTime(overview.latestIndexedAt),
      path: overview.latestPath,
    });
  }
  return overview.status === "running" ? t("file.awaitingFirstIndex") : fileReasonMessage(overview);
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

function fileCount(value, label) {
  return '<div class="file-count"><strong>' + escHtml(String(value || 0)) +
    '</strong><span>' + escHtml(label) + "</span></div>";
}

function renderFileMessage(type, text, action) {
  return '<section class="file-message file-message-' + escHtml(type) + '"><span>' +
    escHtml(text) + '</span><div>' + (action || "") + "</div></section>";
}
