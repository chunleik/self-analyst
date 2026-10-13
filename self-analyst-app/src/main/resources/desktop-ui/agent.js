/* ============================================================
   SelfAnalyst Desktop - Dashboard Timeline Rendering
   ============================================================ */
"use strict";

// ---- Timeline ----

// Presentation-only compatibility projection. Preserve source statistics and
// never filter topics merely because they mention AFK or an unknown app.
function hiddenSummaryDiagnostic(value) {
  if (value && typeof value === "object") {
    var kind = value.diagnosticType || value.type || value.code;
    if (typeof kind === "string") kind = kind.replace(/([a-z])([A-Z])/g, "$1_$2").replace(/-/g, "_").toLowerCase();
    return ["unknown_activity", "unknown_application_activity", "unidentified_application_activity",
      "afk_coverage_incomplete", "incomplete_afk_coverage", "afk_coverage_warning"].indexOf(kind) >= 0;
  }
  if (typeof value !== "string") return false;
  var text = value.trim();
  return /^(?:未识别应用活动|Unidentified application activity)[:： ]*\d[\d.\s]*(?:(?:小时|分钟|分|秒|h|m|s|min|sec|minutes?|seconds?|hours?)[\d.\s]*)+[。.]?$/i.test(text)
    || /^AFK 覆盖不足[：:,，]\s*部分时长未扣除非活跃时间[，,]仅供估计[。.]?$/.test(text)
    || /^Incomplete AFK coverage: some durations include unverified idle time and are estimates[.]?$/.test(text);
}

function summaryEvidenceProjection(value) {
  if (hiddenSummaryDiagnostic(value)) return null;
  if (typeof value === "string") {
    // Legacy evidence is sometimes serialized as JSON rather than an array.
    var trimmed = value.trim();
    if (/^[\[{]/.test(trimmed)) {
      try { return summaryEvidenceProjection(JSON.parse(trimmed)); } catch (ignored) {}
    }
    if (!trimmed) return null;
    var segments = value.split(/\n|[;；]/);
    var lines = segments.filter(function (line) { return !hiddenSummaryDiagnostic(line); });
    return lines.length === segments.length ? value : lines.filter(function (line) { return line.trim(); }).join("; ").trim() || null;
  }
  if (Array.isArray(value)) {
    var items = value.map(summaryEvidenceProjection).filter(function (item) { return item != null; });
    return items.length ? items : null;
  }
  if (value && typeof value === "object") {
    var result = {};
    Object.keys(value).forEach(function (key) {
      if (["unknownActivitySeconds", "unknown_activity_seconds", "afkCoverageWarning"].indexOf(key) >= 0) return;
      var item = summaryEvidenceProjection(value[key]);
      if (item != null) result[key] = item;
    });
    return Object.keys(result).length ? result : null;
  }
  return value == null ? null : value;
}

function validSummaryRepairTarget(target) {
  return !!(target && typeof target.entryId === "string" && target.entryId
    && typeof target.level === "string" && target.level
    && typeof target.timezone === "string" && target.timezone
    && Number.isInteger(target.revision) && target.revision >= 0
    && Number.isFinite(Date.parse(target.periodStart)) && Number.isFinite(Date.parse(target.periodEnd))
    && Date.parse(target.periodEnd) > Date.parse(target.periodStart));
}

function summaryRepairButton(target, entryIndex, dependencyIndex, label) {
  if (!validSummaryRepairTarget(target)) return "";
  var range = label || target.periodStart + " – " + target.periodEnd;
  return '<button type="button" class="btn btn-sm btn-outline timeline-summary-repair-btn" data-entry-idx="'
    + entryIndex + '"' + (dependencyIndex == null ? '' : ' data-dependency-idx="' + dependencyIndex + '"')
    + ' aria-label="' + escHtml(t("repair.openRange", { range: range })) + '">'
    + escHtml(t("repair.open")) + '</button>';
}

function historyStateText(status) {
  var keys = { waiting_dependencies: "timeline.historyWaitingDependencies", pending: "timeline.historyPending",
    failed: "timeline.historyFailed", missing: "timeline.historyMissing", skipped: "timeline.historySkipped",
    unavailable: "timeline.historyUnavailable" };
  var progress = status.generationProgress;
  var reasons = { quality: "timeline.historyQuality", period_budget: "timeline.periodBudgetPaused",
    global_budget: "timeline.dailyBudgetPaused", configuration: "timeline.summaryNeedsConfiguration",
    input: "timeline.summaryInputPaused" };
  return t(progress && reasons[progress.state] || keys[status.state] || "timeline.historyPending");
}

function historyProgressDetail(progress) {
  if (!progress || typeof progress !== "object") return "";
  var html = "";
  if (Number.isFinite(progress.calls) && Number.isFinite(progress.maxCalls)) {
    html += '<div class="timeline-detail-text">' + escHtml(t("timeline.summaryBudgetUse", {
      calls: progress.calls, maxCalls: progress.maxCalls,
      tokens: Number.isFinite(progress.tokens) ? progress.tokens : 0,
      maxTokens: Number.isFinite(progress.maxTokens) ? progress.maxTokens : 0
    })) + '</div>';
  }
  if (progress.reservedTokens > 0 || progress.estimatedCalls > 0) {
    html += '<div class="timeline-detail-text">' + escHtml(t("timeline.summaryBudgetEstimate")) + '</div>';
  }
  if (typeof progress.nextRetryAt === "string" && !progress.nextRetryAt.startsWith("9999-")
      && Number.isFinite(Date.parse(progress.nextRetryAt))) {
    var retry = new Date(progress.nextRetryAt);
    var zone = state.summary && state.summary.timezone;
    var formatted;
    try { formatted = retry.toLocaleString(undefined, zone ? { timeZone: zone } : {}); }
    catch (ignored) { formatted = progress.nextRetryAt; }
    html += '<div class="timeline-detail-text">' + escHtml(t("timeline.historyRetryAt", { time: formatted })) + '</div>';
  }
  return html;
}

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
    var headline = summaryEvidenceProjection(entry.headline) || t("timeline.noSummary");
    var summary = summaryEvidenceProjection(entry.summary) || "";
    var tags = entry.tags || [];
    var evidence = summaryEvidenceProjection(entry.evidence);
    var insight = summaryEvidenceProjection(entry.insight);
    var overview = typeof insight === "string" && insight.trim() ? summaryEvidenceProjection(insight) || "" : "";
    var suggestion = entry.suggestion;
    var localFacts = entry.local_facts;
    var progress = entry.generationProgress;
    var history = entry.summaryStatus;
    var partial = Array.isArray(entry.partialSummaries) ? entry.partialSummaries.map(function (part) {
      if (!part || typeof part.summary !== "string") return null;
      var projected = summaryEvidenceProjection(part.summary);
      return projected ? Object.assign({}, part, { summary: projected }) : null;
    }).filter(function (part) { return part && typeof part.summary === "string" && part.summary.trim(); }) : [];

    var isLlmAvailable = !!(headline && headline !== t("timeline.noSummary"));

    html +=
      '<div class="timeline-entry' + (state.timelineExpanded.has(id) ? ' expanded' : '') +
      '" data-entry-id="' + escHtml(id) + '" data-entry-idx="' +
      idx +
      '">' +
      '<div class="timeline-entry-label">' +
      escHtml(label) +
      "</div>";

    if (history) {
      html += '<div class="timeline-entry-summary">' + escHtml(t(partial.length
        ? "timeline.historyPartial" : "timeline.historyStatisticsOnly")) + '</div>';
      html += '<div class="timeline-entry-summary">' + escHtml(historyStateText(history)) + '</div>';
    }
    html += summaryRepairButton(entry.repairTarget || history && history.repairTarget, idx, null, label);
    if (entry.source === "user_revision" || entry.source === "user-revision" || entry.userRevised || entry.repairRevision) {
      html += '<div class="timeline-repair-source">' + escHtml(t("repair.userRevision")) + '</div>';
    }
    partial.forEach(function (part) {
      html += '<div class="timeline-partial-period">';
      if (typeof part.periodLabel === "string") html += '<div class="timeline-entry-summary">' + escHtml(part.periodLabel) + '</div>';
      html += '<div class="timeline-entry-overview">' + escHtml(part.summary) + '</div>';
      if (part.userRevised) html += '<div class="timeline-repair-source">' + escHtml(t("repair.userRevision")) + '</div>';
      html += '</div>';
    });
    if (overview) {
      html += '<div class="timeline-entry-overview">' + escHtml(overview) + "</div>";
    }
    if (!partial.length && (!overview || (entry.headline && String(entry.headline).trim() !== overview.trim()))) {
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

    if (!history && progress && progress.state && progress.state !== "queued") {
      var noticeKey = progress.state === "period_budget" ? "timeline.periodBudgetPaused"
        : progress.state === "global_budget" ? "timeline.dailyBudgetPaused"
        : progress.state === "configuration" ? "timeline.summaryNeedsConfiguration"
        : progress.state === "input" ? "timeline.summaryInputPaused" : "timeline.summaryRetrying";
      html += '<div class="timeline-entry-summary">' + escHtml(t(noticeKey)) + '</div>';
    }

    html += '<div class="timeline-entry-detail">';
    if (partial.length) html += '<div class="timeline-detail-section">' + escHtml(headline) + '</div>';

    partial.forEach(function (part) {
      if (!Array.isArray(part.taskSegments) || !part.taskSegments.length) return;
      html += '<div class="timeline-detail-section">';
      if (typeof part.periodLabel === "string") html += '<div class="timeline-detail-label">' + escHtml(part.periodLabel) + '</div>';
      (Array.isArray(part.taskSegments) ? part.taskSegments : []).forEach(function (topic) {
        if (!topic || typeof topic !== "object") return;
        if (typeof topic.title === "string") html += '<div class="timeline-detail-label">' + escHtml(topic.title) + '</div>';
        if (typeof topic.summary === "string") html += '<div class="timeline-detail-text">' + escHtml(topic.summary) + '</div>';
      });
      html += '</div>';
    });
    if (history) {
      html += historyProgressDetail(history.generationProgress);
      (Array.isArray(history.dependencies) ? history.dependencies : []).forEach(function (dependency, dependencyIndex) {
        if (!dependency || typeof dependency !== "object") return;
        html += '<div class="timeline-detail-section">';
        if (typeof dependency.periodLabel === "string") html += '<div class="timeline-detail-label">' + escHtml(dependency.periodLabel) + '</div>';
        html += '<div class="timeline-detail-text">' + escHtml(historyStateText(dependency)) + '</div>';
        html += historyProgressDetail(dependency.generationProgress);
        html += summaryRepairButton(dependency.repairTarget, idx, dependencyIndex, dependency.periodLabel) + '</div>';
      });
    }

    if (!history && progress && Number.isFinite(progress.calls) && Number.isFinite(progress.maxCalls)) {
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

    var topics = Array.isArray(entry.taskSegments) ? entry.taskSegments : [];
    topics.forEach(function (topic) {
      if (!topic || typeof topic !== "object" || Array.isArray(topic)) return;
      var title = typeof topic.title === "string" && topic.title.trim() ? topic.title : "";
      var narrative = typeof topic.summary === "string" && topic.summary.trim() ? topic.summary : "";
      if (!title && !narrative) return;
      html += '<div class="timeline-detail-section timeline-topic-card">';
      if (title) html += '<div class="timeline-detail-label">' + escHtml(title) + '</div>';
      if (narrative) html += '<div class="timeline-detail-text">' + escHtml(narrative) + '</div>';
      if (topic.claimType === "user_confirmed") html += '<div class="timeline-repair-source">' + escHtml(t("repair.userConfirmed")) + '</div>';
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
