/* ============================================================
   SelfAnalyst Desktop - Dashboard Timeline Rendering
   ============================================================ */
"use strict";

// ---- Timeline ----

function renderTimeline() {
  var sm = state.summary;
  var body = state.dom.timelineBody;

  if (!sm) {
    body.innerHTML = '<div class="timeline-empty">' + escHtml(t("timeline.insufficient")) + '</div>';
    return;
  }

  var entries = sm.entries || sm.timeline || [];
  if (!entries.length) {
    body.innerHTML = '<div class="timeline-empty">' + escHtml(t("timeline.insufficient")) + '</div>';
    return;
  }

  var html = '<div class="timeline-list">';
  entries.forEach(function (entry, idx) {
    var label = entry.label || entry.period || t("timeline.period");
    var headline = entry.headline || t("timeline.noSummary");
    var summary = entry.summary || "";
    var tags = entry.tags || [];
    var evidence = entry.evidence;
    var insight = entry.insight;
    var suggestion = entry.suggestion;
    var localFacts = entry.local_facts;
    var progress = entry.generationProgress;

    var isLlmAvailable = !!(headline && headline !== t("timeline.noSummary"));

    html +=
      '<div class="timeline-entry" data-entry-idx="' +
      idx +
      '">' +
      '<div class="timeline-entry-label">' +
      escHtml(label) +
      "</div>" +
      '<div class="timeline-entry-headline">' +
      escHtml(headline) +
      "</div>";

    if (summary) {
      html +=
        '<div class="timeline-entry-summary">' + escHtml(summary) + "</div>";
    }

    if (tags.length) {
      html += '<div class="timeline-entry-tags">';
      tags.forEach(function (t) {
        html += '<span class="tag tag-info">' + escHtml(t) + "</span>";
      });
      html += "</div>";
    }

    if (progress && progress.state && progress.state !== "queued") {
      var noticeKey = progress.state === "period_budget" ? "timeline.periodBudgetPaused"
        : progress.state === "global_budget" ? "timeline.dailyBudgetPaused"
        : progress.state === "configuration" ? "timeline.summaryNeedsConfiguration"
        : progress.state === "input" ? "timeline.summaryInputPaused" : "timeline.summaryRetrying";
      html += '<div class="timeline-entry-summary">' + escHtml(t(noticeKey)) + '</div>';
    }

    html += '<div class="timeline-entry-detail">';

    if (progress && Number.isFinite(progress.calls) && Number.isFinite(progress.maxCalls)) {
      html += '<div class="timeline-detail-section">' + escHtml(t("timeline.summaryBudgetUse", {
        calls: progress.calls, maxCalls: progress.maxCalls,
        tokens: Number.isFinite(progress.tokens) ? progress.tokens : 0,
        maxTokens: Number.isFinite(progress.maxTokens) ? progress.maxTokens : 0
      })) + '</div>';
      if (progress.reservedTokens > 0 || progress.estimatedCalls > 0) {
        html += '<div class="timeline-detail-section">' + escHtml(t("timeline.summaryBudgetEstimate")) + '</div>';
      }
    }
    var generationCoverage = entry.generationCoverage;
    if (generationCoverage && (generationCoverage.omittedFacts > 0 || generationCoverage.mergeOmittedFacts > 0
        || generationCoverage.omittedIntermediateSummaries > 0 || generationCoverage.omittedTopicCards > 0
        || generationCoverage.unresolvedFacts > 0)) {
      html += '<div class="timeline-detail-section">' + escHtml(t("timeline.summaryPartialInput")) + '</div>';
    }

    if (!isLlmAvailable && localFacts) {
      html += '<div class="timeline-local-facts">';
      if (localFacts.top_apps && localFacts.top_apps.length) {
        html += "<strong>" + escHtml(t("timeline.topApps")) + "</strong> ";
        html += localFacts.top_apps
          .map(function (a) { return "<span>" + escHtml(a) + "</span>"; })
          .join(" ");
        html += "<br>";
      }
      if (localFacts.active_time) {
        html +=
          "<strong>" + escHtml(t("timeline.activeTime")) + "</strong> " +
          escHtml(localFacts.active_time) +
          "<br>";
      }
      if (localFacts.afk_time) {
        html +=
          "<strong>" + escHtml(t("timeline.afkTime")) + "</strong> " +
          escHtml(localFacts.afk_time) +
          "<br>";
      }
      html += "</div>";
      html +=
        '<div class="llm-not-configured">' + escHtml(t("timeline.llmNotConfigured")) + '</div>';
    }

    if (entry.unknownActivitySeconds > 0) {
      html += '<div class="timeline-detail-section">' + escHtml(t("timeline.unknownActivity"))
        + ' ' + escHtml(t("timeline.activityDuration", { minutes: Math.floor(entry.unknownActivitySeconds / 60),
          seconds: Math.floor(entry.unknownActivitySeconds % 60) })) + '</div>';
    }
    if (entry.coverage === "estimated") {
      html += '<div class="timeline-detail-section">' + escHtml(t("timeline.estimated")) + '</div>';
    }
    if (evidence) {
      html +=
        '<div class="timeline-detail-section">' +
        '<div class="timeline-detail-label">' + escHtml(t("timeline.evidence")) + '</div>' +
        '<div class="timeline-detail-text">' +
        escHtml(typeof evidence === "string" ? evidence : JSON.stringify(evidence)) +
        "</div></div>";
    }

    if (insight) {
      html +=
        '<div class="timeline-detail-section">' +
        '<div class="timeline-detail-label">' + escHtml(t("timeline.insight")) + '</div>' +
        '<div class="timeline-detail-text">' +
        escHtml(typeof insight === "string" ? insight : JSON.stringify(insight)) +
        "</div></div>";
    }

    if (suggestion) {
      html +=
        '<div class="timeline-detail-section">' +
        '<div class="timeline-detail-label">' + escHtml(t("timeline.suggestion")) + '</div>' +
        '<div class="timeline-detail-text">' +
        escHtml(typeof suggestion === "string" ? suggestion : JSON.stringify(suggestion)) +
        "</div></div>";
    }

    html +=
      '<button class="btn btn-sm btn-outline timeline-entry-discuss-btn" data-entry-idx="' +
      idx +
      '">' + escHtml(t("timeline.discuss")) + '</button>';

    html += "</div></div>";
  });
  html += "</div>";

  body.innerHTML = html;
}
