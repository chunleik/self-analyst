/* ============================================================
   SelfAnalyst Desktop - Dashboard Timeline Rendering
   ============================================================ */
"use strict";

// ---- Timeline ----

// Reading state belongs to the view, not the refreshable summary or DOM.
function timelineReadingScope(summary) {
  var zone = summary.timezone || "UTC";
  var date = new Date(summary.assembledAt || Date.now());
  var day = "legacy";
  if (Number.isFinite(date.getTime())) {
    try {
      // Java ZoneId also emits fixed offsets (e.g. GMT+08:00), which some
      // WebViews do not accept as Intl timeZone identifiers.
      var offset = /^(?:(?:UTC|GMT|UT))?([+-])(\d{1,2})(?::?(\d{2}))?(?::?(\d{2}))?$/.exec(zone);
      var displayDate = date;
      var displayZone = zone;
      if (offset) {
        var seconds = (+offset[2] * 3600 + +(offset[3] || 0) * 60 + +(offset[4] || 0))
          * (offset[1] === "-" ? -1 : 1);
        displayDate = new Date(date.getTime() + seconds * 1000);
        displayZone = "UTC";
      }
      var parts = new Intl.DateTimeFormat("en-CA", {
        timeZone: displayZone, year: "numeric", month: "2-digit", day: "2-digit",
        hour: "2-digit", hourCycle: "h23"
      }).formatToParts(displayDate);
      var fields = {};
      parts.forEach(function (part) { fields[part.type] = part.value; });
      // Activity days start at 04:00 in the summary's timezone, including DST.
      var calendarDay = new Date(Date.UTC(+fields.year, +fields.month - 1, +fields.day));
      if (+fields.hour < 4) calendarDay.setUTCDate(calendarDay.getUTCDate() - 1);
      day = calendarDay.toISOString().slice(0, 10);
    } catch (ignored) {
      // Malformed legacy zones must not stop rendering.
      day = new Date(date.getTime() - 4 * 3600000).toISOString().slice(0, 10);
    }
  }
  return JSON.stringify([zone, summary.calendarVersion || "", day]);
}

function toggleTimelineEntry(entry) {
  var expanded = entry.classList.toggle("expanded");
  var id = entry.dataset && entry.dataset.entryId;
  if (!id) return;
  if (!state.timelineExpanded) state.timelineExpanded = new Set();
  if (expanded) state.timelineExpanded.add(id);
  else state.timelineExpanded.delete(id);
}

function renderTimeline() {
  var sm = state.summary;
  var body = state.dom.timelineBody;

  if (!state.timelineExpanded) state.timelineExpanded = new Set();
  if (!sm) {
    state.timelineExpanded.clear();
    body.innerHTML = '<div class="timeline-empty">' + escHtml(t("timeline.insufficient")) + '</div>';
    return;
  }

  var scope = timelineReadingScope(sm);
  if (state.timelineReadingScope !== scope) {
    state.timelineExpanded.clear();
    state.timelineReadingScope = scope;
  }
  var entries = sm.entries || sm.timeline || [];
  if (!entries.length) {
    state.timelineExpanded.clear();
    body.innerHTML = '<div class="timeline-empty">' + escHtml(t("timeline.insufficient")) + '</div>';
    return;
  }

  var visibleIds = new Set();
  var occurrences = new Map();
  var html = '<div class="timeline-list">';
  entries.forEach(function (entry, idx) {
    // Never use headline, insight, source or array index for keyed entries.
    var identity = JSON.stringify([entry.key || entry.period || entry.label || "legacy",
      entry.periodStart || entry.start || ""]);
    var occurrence = occurrences.get(identity) || 0;
    occurrences.set(identity, occurrence + 1);
    var id = encodeURIComponent(JSON.stringify([identity, occurrence]));
    visibleIds.add(id);
    var label = entry.label || entry.period || t("timeline.period");
    var headline = entry.headline || t("timeline.noSummary");
    var summary = entry.summary || "";
    var tags = entry.tags || [];
    var evidence = entry.evidence;
    var insight = entry.insight;
    var overview = typeof insight === "string" && insight.trim() ? insight : "";
    var suggestion = entry.suggestion;
    var localFacts = entry.local_facts;
    var progress = entry.generationProgress;

    var isLlmAvailable = !!(headline && headline !== t("timeline.noSummary"));

    html +=
      '<div class="timeline-entry' + (state.timelineExpanded.has(id) ? ' expanded' : '') +
      '" data-entry-id="' + escHtml(id) + '" data-entry-idx="' +
      idx +
      '">' +
      '<div class="timeline-entry-label">' +
      escHtml(label) +
      "</div>";

    if (overview) {
      html += '<div class="timeline-entry-overview">' + escHtml(overview) + "</div>";
    }
    if (!overview || (entry.headline && String(entry.headline).trim() !== overview.trim())) {
      html += '<div class="timeline-entry-headline' + (overview ? ' timeline-entry-topic' : '') + '">' +
        escHtml(headline) + "</div>";
    }

    if (summary && (!overview || String(summary).trim() !== overview.trim())) {
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
    var topics = Array.isArray(entry.taskSegments) ? entry.taskSegments : [];
    topics.forEach(function (topic) {
      if (!topic || typeof topic !== "object" || Array.isArray(topic)) return;
      var title = typeof topic.title === "string" && topic.title.trim() ? topic.title : "";
      var narrative = typeof topic.summary === "string" && topic.summary.trim() ? topic.summary : "";
      if (!title && !narrative) return;
      html += '<div class="timeline-detail-section timeline-topic-card">';
      if (title) html += '<div class="timeline-detail-label">' + escHtml(title) + '</div>';
      if (narrative) html += '<div class="timeline-detail-text">' + escHtml(narrative) + '</div>';
      html += '</div>';
    });

    var hasEvidence = typeof evidence === "string" ? !!evidence.trim()
      : Array.isArray(evidence) ? evidence.length > 0
      : evidence && typeof evidence === "object" && Object.keys(evidence).length > 0;
    if (hasEvidence) {
      html +=
        '<div class="timeline-detail-section">' +
        '<div class="timeline-detail-label">' + escHtml(t("timeline.evidence")) + '</div>' +
        '<div class="timeline-detail-text">' +
        escHtml(typeof evidence === "string" ? evidence : JSON.stringify(evidence)) +
        "</div></div>";
    }

    if (insight && !overview) {
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

  state.timelineExpanded.forEach(function (id) {
    if (!visibleIds.has(id)) state.timelineExpanded.delete(id);
  });
  body.innerHTML = html;
}
