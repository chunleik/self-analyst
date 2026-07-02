/* ============================================================
   SelfAnalyst Desktop - Agent Tab Rendering
   ============================================================ */
"use strict";

function renderAgentTab() {
  renderBehaviorAdvice();
  renderTimeline();
  renderTasks();
}

// ---- Behavior Advice Card (SPEC-ADV-UI-*) ----

function renderBehaviorAdvice() {
  var card = document.getElementById("behavior-advice-card");
  var body = document.getElementById("behavior-advice-body");
  if (!card || !body) return;

  var sm = state.summary;
  var advice = sm && sm.behaviorAdvice ? sm.behaviorAdvice : null;

  // SPEC-ADV-API-004: handle null behaviorAdvice from old responses
  card.classList.remove("hidden");
  // Remove old type classes
  card.classList.remove("type-encouragement", "type-suggestion", "type-reminder", "type-empty");

  if (!advice || advice.type === "empty") {
    // Show empty state — card is always visible
    card.classList.add("type-empty");
    body.innerHTML =
      '<div class="behavior-advice-empty">' +
      escHtml((advice && advice.title) || t("advice.empty")) +
      "</div>";
    return;
  }

  // SPEC-ADV-UI-003: Full card content
  card.classList.add("type-" + (advice.type || "suggestion"));

  var typeLabels = {
    encouragement: t("advice.type.encouragement"),
    suggestion: t("advice.type.suggestion"),
    reminder: t("advice.type.reminder"),
  };
  var typeLabel = typeLabels[advice.type] || t("advice.type.suggestion");

  var updatedText = advice.generatedAt
    ? formatRelativeTime(advice.generatedAt)
    : "";

  var html = "";

  // Header: type tag + scope + updated time
  html += '<div class="behavior-advice-header">';
  html +=
    '<span class="behavior-advice-type-tag tag-' +
    escHtml(advice.type || "suggestion") +
    '">' +
    escHtml(typeLabel) +
    "</span>";
  if (advice.scopeLabel) {
    html +=
      '<span class="behavior-advice-scope">' +
      escHtml(t("advice.basedOn", { scope: advice.scopeLabel })) +
      "</span>";
  }
  if (updatedText) {
    html +=
      '<span class="behavior-advice-updated">' +
      escHtml(t("advice.updated", { time: updatedText })) +
      "</span>";
  }
  html += "</div>";

  // Title
  if (advice.title) {
    html +=
      '<div class="behavior-advice-title">' + escHtml(advice.title) + "</div>";
  }

  // Body
  if (advice.body) {
    html +=
      '<div class="behavior-advice-body">' + escHtml(advice.body) + "</div>";
  }

  // Evidence tags (SPEC-ADV-UI-003)
  if (advice.evidenceTags && advice.evidenceTags.length) {
    html += '<div class="behavior-advice-tags">';
    advice.evidenceTags.forEach(function (tag) {
      html +=
        '<span class="behavior-advice-tag">' + escHtml(tag) + "</span>";
    });
    html += "</div>";
  }

  // Basis summary (SPEC-ADV-UI-003)
  if (advice.basis) {
    html += '<div class="behavior-advice-basis">';
    if (advice.basis.observationRange) {
      html +=
        '<span class="behavior-advice-basis-item"><strong>' + escHtml(t("advice.basis.observationRange")) + '</strong> ' +
        escHtml(advice.basis.observationRange) +
        "</span>";
    }
    if (advice.basis.trend) {
      html +=
        '<span class="behavior-advice-basis-item"><strong>' + escHtml(t("advice.basis.trend")) + '</strong> ' +
        escHtml(advice.basis.trend) +
        "</span>";
    }
    if (advice.basis.adviceKind) {
      html +=
        '<span class="behavior-advice-basis-item"><strong>' + escHtml(t("advice.basis.adviceKind")) + '</strong> ' +
        escHtml(advice.basis.adviceKind) +
        "</span>";
    }
    if (advice.basis.dataCompleteness) {
      html +=
        '<span class="behavior-advice-basis-item"><strong>' + escHtml(t("advice.basis.dataCompleteness")) + '</strong> ' +
        escHtml(advice.basis.dataCompleteness) +
        "</span>";
    }
    html += "</div>";
  }

  body.innerHTML = html;
}

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

    html += '<div class="timeline-entry-detail">';

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

// ---- Future Tasks ----

function renderTasks() {
  var body = state.dom.tasksBody;
  var tasks = state.tasks || [];

  var html = "";

  // Task list
  if (!tasks.length) {
    html += '<div class="tasks-empty">' + escHtml(t("tasks.empty")) + '</div>';
    body.innerHTML = html;
    return;
  }

  // Sort: open first, then by dueAt, then by priority
  var sorted = tasks.slice().sort(function (a, b) {
    if (a.status === "completed" && b.status !== "completed") return 1;
    if (b.status === "completed" && a.status !== "completed") return -1;
    if (a.dueAt && b.dueAt) {
      return new Date(a.dueAt) - new Date(b.dueAt);
    }
    if (a.dueAt) return -1;
    if (b.dueAt) return 1;
    var prio = { high: 0, medium: 1, low: 2 };
    return (prio[a.priority] || 1) - (prio[b.priority] || 1);
  });

  html += '<div class="tasks-list">';
  sorted.forEach(function (task) {
    html += renderTaskItem(task);
  });
  html += "</div>";

  body.innerHTML = html;
}

function renderTaskItem(task) {
  var isExpanded = state.editingTaskId === task.id;
  var isCompleted = task.status === "completed";

  var html =
    '<div class="task-item' +
    (isCompleted ? " completed" : "") +
    (isExpanded ? " expanded" : "") +
    '" data-task-id="' +
    escHtml(String(task.id)) +
    '">' +
    '<div class="task-item-header">' +
    '<div class="task-item-title">' +
    escHtml(task.title || t("task.untitled")) +
    "</div>" +
    '<div class="task-item-meta">' +
    (task.dueAt
      ? '<span class="task-item-due">' +
        formatDate(task.dueAt) +
        "</span>"
      : "") +
    priorityBadge(task.priority || "medium") +
    "</div>" +
    "</div>";

  if (isExpanded) {
    html += renderTaskEditForm(task);
  }

  html += "</div>";
  return html;
}

function renderTaskEditForm(task) {
  var html = '<div class="task-item-detail">';
  html += '<div class="task-item-edit-form">';
  html +=
    "<label>" + escHtml(t("task.label.title")) + "</label>" +
    '<input type="text" class="edit-task-title" value="' +
    escHtml(task.title || "") +
    '">';
  html +=
    "<label>" + escHtml(t("task.label.priority")) + "</label>" +
    '<select class="edit-task-priority">' +
    '<option value="high"' +
    (task.priority === "high" ? " selected" : "") +
    ">" + escHtml(t("priority.high")) + "</option>" +
    '<option value="medium"' +
    (task.priority === "medium" || !task.priority ? " selected" : "") +
    ">" + escHtml(t("priority.medium")) + "</option>" +
    '<option value="low"' +
    (task.priority === "low" ? " selected" : "") +
    ">" + escHtml(t("priority.low")) + "</option>" +
    "</select>";
  html +=
    "<label>" + escHtml(t("task.label.due")) + "</label>" +
    '<input type="text" class="edit-task-due" placeholder="YYYY-MM-DD" value="' +
    escHtml(task.dueAt || "") +
    '">';

  html += '<div class="task-item-edit-actions">';
  if (!task || task.status !== "completed") {
    html +=
      '<button class="btn btn-sm btn-success complete-task-btn" data-task-id="' +
      escHtml(String(task.id)) +
      '">' + escHtml(t("task.complete")) + '</button>';
  }
  html +=
    '<button class="btn btn-sm btn-outline discuss-task-btn" data-task-id="' +
    escHtml(String(task.id)) +
    '" data-task-title="' +
    escHtml(task.title || "") +
    '">' + escHtml(t("task.discuss")) + '</button>';
  html +=
    '<button class="btn btn-sm btn-outline archive-task-btn" data-task-id="' +
    escHtml(String(task.id)) +
    '">' + escHtml(t("task.archive")) + '</button>';
  html +=
    '<button class="btn btn-sm btn-danger delete-task-btn" data-task-id="' +
    escHtml(String(task.id)) +
    '">' + escHtml(t("task.delete")) + '</button>';
  html +=
    '<button class="btn btn-sm btn-primary save-task-btn" data-task-id="' +
    escHtml(String(task.id)) +
    '">' + escHtml(t("task.save")) + '</button>';
  html += "</div>";
  html += "</div></div>";
  return html;
}
