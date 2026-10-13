/* Local, server-authoritative summary repair. No model text creates actions. */
"use strict";

var summaryRepairs = Object.create(null);
var summaryRepairOpening = false;
var summaryRepairStyles = ".summary-repair-card{display:flex;flex-direction:column;gap:12px;padding:16px;border:1px solid var(--border-color,#d9dfe7);border-radius:10px;background:var(--bg-card,#fff);color:var(--text-primary,#222);min-width:0;max-width:100%;overflow-wrap:anywhere;font:13px/1.5 system-ui,sans-serif}.summary-repair-card label{display:flex;flex-direction:column;gap:4px;font-weight:500}.summary-repair-card textarea,.summary-repair-card input[type=text],.summary-repair-card select{box-sizing:border-box;max-width:100%;width:100%;padding:8px;border:1px solid var(--border-color,#ccd2dc);border-radius:5px;background:var(--bg-primary,#fff);color:inherit;font:inherit}.summary-repair-card textarea{resize:vertical;min-height:66px}.summary-repair-task{display:flex;flex-direction:column;gap:9px;margin:0;padding:12px;border:1px solid var(--border-color,#ddd);min-width:0;border-radius:7px}.summary-repair-actions{display:flex;flex-wrap:wrap;gap:8px}.summary-repair-card button{max-width:100%;white-space:normal;padding:7px 10px;border:1px solid var(--border-color,#ccd2dc);border-radius:5px;background:var(--bg-primary,#f5f6f8);color:inherit;cursor:pointer;font:inherit}.summary-repair-card button:disabled{opacity:.55;cursor:default}.summary-repair-card button:focus-visible,.summary-repair-card input:focus-visible,.summary-repair-card textarea:focus-visible,.summary-repair-card select:focus-visible{outline:2px solid #4e7bd8;outline-offset:2px}.summary-repair-card .summary-repair-save{background:#2563eb;color:#fff}.summary-repair-ref{flex-direction:row!important;align-items:flex-start;font-weight:400!important;gap:6px!important}.summary-repair-notice{padding:8px;background:var(--bg-secondary,#f2f5f9);border-radius:5px;white-space:pre-wrap}.summary-repair-issues{color:var(--danger,#a42828);margin:0;padding-left:20px}.summary-repair-slot{max-width:100%;min-width:0;margin-top:12px}.summary-repair-slot:empty{display:none}.summary-repair-muted{color:var(--text-secondary,#616c7b);font-size:12px}.summary-repair-evidence{display:flex;flex-direction:column;gap:6px;max-height:190px;overflow:auto}.summary-repair-card details{min-width:0}.summary-repair-card summary{cursor:pointer;font-weight:500}";

function repairState(sessionId) {
  if (!summaryRepairs[sessionId]) summaryRepairs[sessionId] = {
    view: null, candidate: null, dirty: false, edit: 0, generation: 0,
    busy: false, loading: false, loaded: false, error: "", conflict: false, timer: null,
  };
  return summaryRepairs[sessionId];
}

function isSummaryRepairSession(session) {
  return !!(session && (session.source === "summary_repair"
    || session.contextSnapshot && session.contextSnapshot.type === "summary_repair"));
}

function repairClone(value) { return JSON.parse(JSON.stringify(value)); }
function repairEmptyCandidate() { return { summary: "", primaryTask: "", taskSegments: [] }; }

function repairRange(target) {
  if (!target) return "";
  var start = target.periodStart, end = target.periodEnd;
  try {
    var options = { timeZone: target.timezone, month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hourCycle: "h23" };
    start = new Date(start).toLocaleString(state.dateLocale || "en-US", options);
    end = new Date(end).toLocaleString(state.dateLocale || "en-US", options);
  } catch (ignored) {}
  return start + " – " + end + " (" + target.timezone + ")";
}

function preserveRepairComposer() {
  var session = getActiveChatSession();
  if (!session) return;
  var element = typeof deepChatElement === "function" && deepChatElement();
  var input = element && element.shadowRoot && element.shadowRoot.querySelector
    && element.shadowRoot.querySelector("#text-input");
  session.composerDraft = input ? input.innerText || input.textContent : state.dom.chatTabInput && state.dom.chatTabInput.value || "";
}

function restoreRepairComposer(session) {
  var text = session && session.composerDraft || "";
  if (state.dom.chatTabInput) state.dom.chatTabInput.value = text;
  if (typeof deepChatSetDraft === "function") deepChatSetDraft(text);
}

function openSummaryRepair(target) {
  if (!validSummaryRepairTarget(target) || summaryRepairOpening) return Promise.resolve(null);
  if (state.chatSending) {
    alert(t("repair.sendBusy"));
    return Promise.resolve(null);
  }
  preserveRepairComposer();
  summaryRepairOpening = true;
  var existing = (state.chatSessions || []).find(function (session) {
    var ctx = session.contextSnapshot;
    return isSummaryRepairSession(session) && ctx && (ctx.targetId || ctx.target && ctx.target.entryId) === target.entryId;
  });
  var sessionPromise = existing ? Promise.resolve(existing) : createChatSession({
    title: t("repair.title", { range: repairRange(target) }), source: "summary_repair",
    contextLabel: repairRange(target),
  });
  return sessionPromise.then(function (session) {
    return api.summaryRepair("", "POST", {
      sessionId: session.id, targetId: target.entryId, expectedTargetRevision: target.revision,
    }).then(function (view) {
      acceptSummaryRepairResponse(session.id, view);
      // Read the authoritative binding; a client context is never permission.
      return api.getSession(session.id).then(function (full) {
        applyFullSession(session, full);
        state.activeChatSessionId = session.id;
        if (api.setActiveSession) api.setActiveSession(session.id).catch(function () {});
        if (!session.composerDraft && !(session.messages || []).some(function (message) { return message.role === "user"; })) {
          session.composerDraft = t("repair.prefill");
        }
        switchTab("chat");
        renderChatTab();
        restoreRepairComposer(session);
        if (typeof focusChatComposer === "function") focusChatComposer();
        return view;
      });
    });
  }).catch(function (error) {
    alert(t("repair.openFailed", { message: error && error.message || t("common.unknownError") }));
    return null;
  }).finally(function () { summaryRepairOpening = false; });
}

function acceptSummaryRepairResponse(sessionId, view, expectedEdit) {
  if (!view || !view.draft || view.draft.sessionId !== sessionId) return false;
  var local = repairState(sessionId);
  if (local.view && local.view.draft.id !== view.draft.id) return false;
  if (local.view && view.draft.draftRevision < local.view.draft.draftRevision) return false;
  local.view = view;
  local.loaded = true;
  if (expectedEdit == null ? !local.dirty : expectedEdit === local.edit) {
    local.candidate = repairClone(view.draft.candidate || repairEmptyCandidate());
    local.dirty = false;
  }
  local.error = "";
  local.conflict = view.draft.state !== "committed" && view.target.revision !== view.draft.targetRevision;
  if (state.activeChatSessionId === sessionId && !local.busy) mountSummaryRepair();
  return true;
}

function refreshSummaryRepair(force) {
  var session = getActiveChatSession();
  var contextPanel = typeof document !== "undefined" && document.getElementById("summary-repair-context");
  var layout = contextPanel && contextPanel.closest && contextPanel.closest(".chat-tab-layout");
  if (layout) layout.classList.toggle("has-summary-repair", isSummaryRepairSession(session));
  if (!isSummaryRepairSession(session)) {
    if (contextPanel) { contextPanel.hidden = true; contextPanel.replaceChildren(); }
    return Promise.resolve(null);
  }
  var local = repairState(session.id);
  renderSummaryRepairContext(contextPanel, local);
  mountSummaryRepair();
  if (local.loading || local.busy || local.loaded && !force) return Promise.resolve(local.view);
  var generation = ++local.generation;
  local.loading = true;
  var edit = local.edit;
  return api.summaryRepair("/session/" + encodeURIComponent(session.id))
    .then(function (view) {
      if (generation !== local.generation) return null;
      local.loaded = true;
      if (view) acceptSummaryRepairResponse(session.id, view, local.dirty ? -1 : edit);
      return view;
    }).catch(function (error) {
      if (generation !== local.generation) return;
      local.error = repairErrorMessage(error);
    }).finally(function () {
      if (generation !== local.generation) return;
      local.loading = false;
      if (state.activeChatSessionId === session.id) {
        renderSummaryRepairContext(contextPanel, local);
        mountSummaryRepair();
      }
    });
}

function summaryRepairSlotHtml(session, index) {
  if (!isSummaryRepairSession(session)) return "";
  var messages = session.messages || [];
  var last = -1;
  for (var i = messages.length - 1; i >= 0; i--) {
    if (messages[i].role === "assistant") { last = i; break; }
  }
  // Before the first reply, a system-only session has its local editor below
  // the last message. Later, the editor follows the latest assistant reply.
  if (last < 0 && messages.length) last = messages.length - 1;
  if (index !== last) return "";
  return '<div class="summary-repair-slot" data-summary-repair-session="' + escHtml(session.id) + '"></div>';
}

function mountSummaryRepair() {
  if (typeof document === "undefined") return;
  var session = getActiveChatSession();
  if (!isSummaryRepairSession(session)) return;
  var local = repairState(session.id);
  renderSummaryRepairContext(document.getElementById("summary-repair-context"), local);
  var element = typeof deepChatElement === "function" && deepChatElement();
  var root = element && element.shadowRoot || state.dom && state.dom.chatThread;
  if (!root || !root.querySelectorAll) return;
  root.querySelectorAll("[data-summary-repair-session]").forEach(function (slot) {
    if (slot.getAttribute("data-summary-repair-session") === session.id) renderSummaryRepairCard(slot, session.id);
  });
}

function refreshRepairDashboard() {
  if (!api.getSummary) return Promise.resolve();
  return api.getSummary().then(function (summary) {
    if (typeof compatibleSummary === "function" && !compatibleSummary(summary)) return;
    state.summary = summary;
    if (state.tab === "agent" && typeof renderTimeline === "function") renderTimeline();
  }).catch(function () {});
}

function repairErrorMessage(error) {
  var code = error && error.body && (error.body.code || error.body.errorCode);
  var key = "repair.issue." + code;
  var message = t(key);
  return message !== key ? message : error && error.message || t("repair.actionFailed");
}

function repairFailureReason(view) {
  var code = view && typeof view.message === "string" && view.message.match(/WIKI_[A-Z_]+(?::[A-Za-z.]+)?/);
  return code ? t("repair.failureReason", { code: code[0] }) : "";
}

function repairNode(tag, text, className) {
  var node = document.createElement(tag);
  if (text != null) node.textContent = String(text);
  if (className) node.className = className;
  return node;
}

function renderSummaryRepairContext(panel, local) {
  if (!panel) return;
  panel.hidden = false;
  var view = local.view;
  var signature = JSON.stringify([state.lang, view && view.target, view && view.message, local.error]);
  if (panel.repairSignature === signature) return;
  panel.repairSignature = signature;
  panel.replaceChildren();
  panel.appendChild(repairNode("strong", view ? t("repair.title", { range: repairRange(view.target) }) : t("repair.loading")));
  if (repairFailureReason(view)) panel.appendChild(repairNode("div", repairFailureReason(view), "summary-repair-muted"));
}

function repairChanged(sessionId) {
  var local = repairState(sessionId);
  local.edit++;
  local.dirty = true;
  local.error = "";
  clearTimeout(local.timer);
  local.timer = setTimeout(function () { repairAction(sessionId, "draft"); }, 700);
  // Do not replace the focused editor while typing. Mark actions unavailable
  // until its current contents have been persisted and validated.
  var element = typeof deepChatElement === "function" && deepChatElement();
  var root = element && element.shadowRoot || state.dom && state.dom.chatThread;
  if (state.activeChatSessionId === sessionId && root && root.querySelectorAll) {
    root.querySelectorAll("[data-repair-save]").forEach(function (button) { button.disabled = true; });
    root.querySelectorAll("[data-repair-state]").forEach(function (notice) { notice.textContent = t("repair.unsaved"); });
  }
}

function repairPersist(sessionId) {
  var local = repairState(sessionId);
  if (!local.dirty) return Promise.resolve(local.view);
  var edit = local.edit;
  var view = local.view;
  return api.summaryRepair("/" + encodeURIComponent(view.draft.id) + "/draft", "PUT", {
    sessionId: sessionId, draftRevision: view.draft.draftRevision,
    candidate: repairClone(local.candidate),
  }).then(function (result) {
    acceptSummaryRepairResponse(sessionId, result, edit);
    return result;
  });
}

function repairAction(sessionId, action, indexes) {
  var local = repairState(sessionId);
  if (local.busy || !local.view || local.view.draft.state === "committed") return Promise.resolve(null);
  if (action === "commit" && (local.dirty || local.conflict || local.view.draft.state !== "valid")) return Promise.resolve(null);
  clearTimeout(local.timer);
  local.busy = true;
  local.error = "";
  ++local.generation; // An earlier GET cannot undo a mutation response.
  local.loading = false;
  if (action !== "draft") mountSummaryRepair();
  return (action === "refresh" ? Promise.resolve(local.view) : repairPersist(sessionId)).then(function (view) {
    if (action === "draft" || local.dirty && action !== "refresh") return view;
    var revision = local.view.draft.draftRevision;
    var edit = local.edit;
    var body = { sessionId: sessionId, draftRevision: revision };
    if (action === "confirm") body.taskIndexes = indexes;
    if (action === "refresh") body.candidate = repairClone(local.candidate);
    if (action === "commit") body.idempotencyKey = local.view.draft.id + ":" + revision;
    return api.summaryRepair("/" + encodeURIComponent(local.view.draft.id) + "/" + action, "POST", body)
      .then(function (result) {
        acceptSummaryRepairResponse(sessionId, result, edit);
        if (action === "commit" && result && result.draft && result.draft.state === "committed") {
          // The dashboard refreshes from canonical data, never from chat text.
          refreshRepairDashboard();
        }
        return result;
      });
  }).catch(function (error) {
    local.error = repairErrorMessage(error);
    local.conflict = !!(error && error.status === 409);
    return null;
  }).finally(function () {
    local.busy = false;
    if (state.activeChatSessionId === sessionId) mountSummaryRepair();
    if (local.dirty && !local.error) {
      local.timer = setTimeout(function () { repairAction(sessionId, "draft"); }, 700);
    }
  });
}

function prepareSummaryRepairForChat(session) {
  if (!isSummaryRepairSession(session)) return Promise.resolve(session);
  var local = repairState(session.id);
  if (local.busy) return Promise.reject(new Error(t("repair.waitForDraft")));
  if (!local.dirty) return Promise.resolve(session);
  return repairAction(session.id, "draft").then(function () {
    if (local.dirty || local.error) throw new Error(local.error || t("repair.waitForDraft"));
    return session;
  });
}

function repairField(parent, labelText, value, changed, options) {
  options = options || {};
  var label = repairNode("label", labelText);
  var input = repairNode(options.multiline ? "textarea" : "input");
  if (!options.multiline) input.type = "text";
  input.value = value || "";
  input.maxLength = options.maxLength || 4000;
  input.disabled = !!options.disabled;
  if (options.fieldId) input.setAttribute("data-repair-field", options.fieldId);
  input.oninput = function () { changed(input.value); };
  label.appendChild(input);
  parent.appendChild(label);
  return input;
}

function repairButton(parent, text, action, disabled, className) {
  var button = repairNode("button", text, className);
  button.type = "button";
  button.disabled = !!disabled;
  button.onclick = action;
  parent.appendChild(button);
  return button;
}

function renderSummaryRepairCard(slot, sessionId) {
  var local = repairState(sessionId);
  var view = local.view;
  var signature = JSON.stringify([state.lang, view, local.edit, local.busy, local.error, local.conflict, local.loading, !!state.chatSending]);
  if (slot.repairSignature === signature) return;
  slot.repairSignature = signature;
  var tree = slot.getRootNode && slot.getRootNode();
  var active = tree && tree.activeElement || document.activeElement;
  var focus = active && slot.contains && slot.contains(active) && active.getAttribute && active.getAttribute("data-repair-field");
  var caret = focus ? [active.selectionStart, active.selectionEnd] : null;
  slot.replaceChildren();
  var style = repairNode("style", summaryRepairStyles);
  slot.appendChild(style);
  var card = repairNode("article", null, "summary-repair-card");
  card.setAttribute("aria-label", t("repair.preview"));
  slot.appendChild(card);
  card.appendChild(repairNode("strong", t("repair.preview")));
  if (!view) {
    card.appendChild(repairNode("p", local.error || t("repair.loading")));
    repairButton(card, t("repair.reload"), function () { refreshSummaryRepair(true); }, local.loading);
    return;
  }
  var draft = view.draft;
  var committed = draft.state === "committed";
  var candidate = local.candidate || (local.candidate = repairClone(draft.candidate || repairEmptyCandidate()));
  card.appendChild(repairNode("div", repairRange(view.target), "summary-repair-muted"));
  if (view.failureTextAvailable === false) card.appendChild(repairNode("p", t("repair.originalMissing"), "summary-repair-notice"));
  card.appendChild(repairNode("p", t("repair.localAvailable"), "summary-repair-muted"));
  var status = repairNode("div", committed ? t("repair.saved", { revision: draft.committedRevision })
    : local.dirty ? t("repair.unsaved") : t("repair.state." + draft.state), "summary-repair-notice");
  status.setAttribute("role", "status");
  status.setAttribute("data-repair-state", "true");
  card.appendChild(status);
  if (local.error || local.conflict) {
    var error = repairNode("p", local.conflict ? t("repair.conflict") : local.error, "summary-repair-notice");
    error.setAttribute("role", "alert");
    card.appendChild(error);
    repairButton(card, t("repair.reload"), function () { refreshSummaryRepair(true); }, local.busy);
    if (local.conflict && !view.currentEntry) {
      card.appendChild(repairNode("p", t("repair.rebaseExplanation"), "summary-repair-muted"));
      repairButton(card, t("repair.rebase"), function () { repairAction(sessionId, "refresh"); }, local.busy);
    }
  }
  if (Array.isArray(draft.issues) && draft.issues.length && !local.dirty) {
    var issues = repairNode("ul", null, "summary-repair-issues");
    draft.issues.forEach(function (issue) {
      var key = "repair.issue." + issue.code;
      var message = t(key);
      issues.appendChild(repairNode("li", issue.field + ": " + (message === key ? t("repair.issue.generic", { code: issue.code }) : message)));
    });
    card.appendChild(issues);
  }
  var disabled = committed || local.busy || !!state.chatSending;
  repairField(card, t("repair.summary"), candidate.summary, function (value) { candidate.summary = value; repairChanged(sessionId); }, { multiline: true, maxLength: 1200, disabled: disabled, fieldId: "summary" });
  repairField(card, t("repair.primaryTask"), candidate.primaryTask, function (value) { candidate.primaryTask = value; repairChanged(sessionId); }, { maxLength: 160, disabled: disabled, fieldId: "primaryTask" });
  (candidate.taskSegments || []).forEach(function (task, index) {
    var fieldset = repairNode("fieldset", null, "summary-repair-task");
    fieldset.appendChild(repairNode("legend", t("repair.task", { number: index + 1 })));
    repairField(fieldset, t("repair.taskTitle"), task.title, function (value) { task.title = value; repairChanged(sessionId); }, { maxLength: 80, disabled: disabled, fieldId: "task." + index + ".title" });
    repairField(fieldset, t("repair.taskSummary"), task.summary, function (value) { task.summary = value; repairChanged(sessionId); }, { multiline: true, maxLength: 500, disabled: disabled, fieldId: "task." + index + ".summary" });
    var sourceLabel = repairNode("label", t("repair.source"));
    var source = repairNode("select");
    ["observed", "inferred", "user_confirmed"].forEach(function (type) {
      var option = repairNode("option", t("repair.source." + type)); option.value = type; source.appendChild(option);
    });
    source.setAttribute("data-repair-field", "task." + index + ".source");
    source.value = task.claimType === "user_confirmed" ? "user_confirmed" : task.claimType === "inferred" ? "inferred" : "observed";
    source.disabled = disabled;
    source.onchange = function () {
      task.claimType = source.value;
      task.confidence = "medium";
      repairChanged(sessionId);
      mountSummaryRepair();
    };
    sourceLabel.appendChild(source); fieldset.appendChild(sourceLabel);
    var evidenceDetails = repairNode("details");
    evidenceDetails.appendChild(repairNode("summary", t("repair.references")));
    var refs = repairNode("div", null, "summary-repair-evidence");
    (view.evidence || []).forEach(function (fact) {
      var label = repairNode("label", null, "summary-repair-ref");
      var checkbox = repairNode("input"); checkbox.type = "checkbox";
      checkbox.setAttribute("data-repair-field", "task." + index + ".ref." + fact.id);
      checkbox.checked = (task.evidenceFactIds || []).indexOf(fact.id) >= 0;
      checkbox.disabled = disabled;
      checkbox.onchange = function () {
        var ids = (task.evidenceFactIds || []).filter(function (id) { return id !== fact.id; });
        if (checkbox.checked && ids.length >= 16) {
          checkbox.checked = false;
          local.error = t("repair.referenceLimit");
          mountSummaryRepair();
          return;
        }
        if (checkbox.checked) ids.push(fact.id);
        task.evidenceFactIds = ids;
        var selected = (view.evidence || []).filter(function (item) { return ids.indexOf(item.id) >= 0; });
        task.apps = Array.from(new Set(selected.map(function (item) { return item.app; }).filter(Boolean)));
        task.evidence = selected.map(function (item) { return item.title || item.app || item.id; });
        repairChanged(sessionId);
      };
      label.appendChild(checkbox);
      label.appendChild(repairNode("span", fact.id + " · " + (fact.app || "") + " · " + (fact.title || "")));
      refs.appendChild(label);
    });
    if (!(view.evidence || []).length) refs.appendChild(repairNode("p", t("repair.noEvidence")));
    evidenceDetails.appendChild(refs); fieldset.appendChild(evidenceDetails);
    if (task.claimType === "user_confirmed") {
      var statement = !local.dirty && (draft.statements || []).find(function (item) {
        return item.taskIndex === index && item.confirmedAt && item.draftRevision === draft.draftRevision;
      });
      fieldset.appendChild(repairNode("p", t("repair.confirmExplanation"), "summary-repair-muted"));
      if (statement) {
        fieldset.appendChild(repairNode("p", t("repair.confirmed") + " · " + statement.id + " · " + statement.text, "summary-repair-notice"));
      } else repairButton(fieldset, t("repair.confirmTask"), function () { repairAction(sessionId, "confirm", [index]); }, disabled);
    }
    if (!committed) repairButton(fieldset, t("repair.removeTask"), function () {
      candidate.taskSegments.splice(index, 1); repairChanged(sessionId); mountSummaryRepair();
    }, disabled);
    card.appendChild(fieldset);
  });
  var actions = repairNode("div", null, "summary-repair-actions");
  if (!committed) {
    repairButton(actions, t("repair.addTask"), function () {
      if (!candidate.taskSegments) candidate.taskSegments = [];
      candidate.taskSegments.push({ title: "", summary: "", evidence: [], apps: [], confidence: "medium", evidenceFactIds: [], claimType: "observed" });
      repairChanged(sessionId); mountSummaryRepair();
    }, disabled || (candidate.taskSegments || []).length >= 24);
    repairButton(actions, t("repair.persist"), function () { repairAction(sessionId, "draft"); }, disabled || !local.dirty);
    repairButton(actions, t("repair.validate"), function () { repairAction(sessionId, "validate"); }, disabled);
    var save = repairButton(actions, local.busy ? t("repair.working") : t("repair.save"), function () { repairAction(sessionId, "commit"); }, disabled || local.dirty || local.conflict || draft.state !== "valid", "summary-repair-save");
    save.setAttribute("data-repair-save", "true");
  } else {
    repairButton(actions, t("repair.backDashboard"), function () { switchTab("agent"); refreshRepairDashboard(); }, false);
  }
  card.appendChild(actions);
  card.appendChild(repairNode("p", t("repair.sourceRetention"), "summary-repair-muted"));
  if (view.currentEntry && local.conflict) {
    var latest = repairNode("details");
    latest.appendChild(repairNode("summary", t("repair.latest")));
    latest.appendChild(repairNode("p", view.currentEntry.summary || ""));
    card.appendChild(latest);
  }
  if (focus && slot.querySelectorAll) {
    var replacement = Array.from(slot.querySelectorAll("[data-repair-field]")).find(function (item) {
      return item.getAttribute("data-repair-field") === focus;
    });
    if (replacement && !replacement.disabled) {
      replacement.focus({ preventScroll: true });
      if (caret && typeof replacement.setSelectionRange === "function" && caret[0] != null) {
        replacement.setSelectionRange(caret[0], caret[1]);
      }
    }
  }
}
