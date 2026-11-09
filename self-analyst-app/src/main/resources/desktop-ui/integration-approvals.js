"use strict";

// Only the authenticated approval endpoint can populate this model. Model prose and SSE deltas cannot.
function createIntegrationApprovals(request, changed, options) {
  options = options || {};
  var model = { approvals: [], busy: {}, error: "", sessionId: options.sessionId || null, turnId: options.turnId || null };
  var alive = true, generation = 0, timer = null, controller = null, polling = false, settled = {};
  function emit() { if (alive) changed(model); }
  function expiry(item) { return typeof item.expiresAt === "number" ? item.expiresAt : Date.parse(item.expiresAt); }
  function valid(item) {
    return item && typeof item.id === "string" && item.id.length <= 128
      && typeof item.sessionId === "string" && typeof item.turnId === "string"
      && (!model.sessionId || item.sessionId === model.sessionId)
      && (!model.turnId || item.turnId === model.turnId)
      && Number.isFinite(expiry(item)) && expiry(item) > Date.now();
  }
  function schedule() { clearTimeout(timer); if (alive) timer = setTimeout(model.refresh, 1000); }
  model.refresh = function () {
    if (!alive || polling) return Promise.resolve();
    if (Object.keys(model.busy).length) { schedule(); return Promise.resolve(); }
    polling = true; var current = generation; controller = new AbortController();
    var path = "/approvals" + (model.sessionId ? "?sessionId=" + encodeURIComponent(model.sessionId) : "");
    return request(path, "GET", undefined, controller.signal).then(function (result) {
      if (!alive || current !== generation) return;
      var seen = {};
      model.approvals = (Array.isArray(result.approvals) ? result.approvals : []).filter(function (item) {
        if (!valid(item) || seen[item.id] || settled[item.id]) return false; seen[item.id] = true; return true;
      });
      model.error = ""; emit();
    }).catch(function (error) {
      if (!alive || current !== generation) return;
      model.approvals = model.approvals.filter(valid); model.error = integrationErrorText(error); emit();
    }).finally(function () { if (alive && current === generation) { polling = false; schedule(); } });
  };
  model.decide = function (id, decision) {
    var item = model.approvals.find(function (entry) { return entry.id === id; });
    if (!alive || !item || settled[id] || model.busy[id] || !valid(item) || ["approve", "reject"].indexOf(decision) < 0) return Promise.resolve();
    var current = ++generation; if (controller) controller.abort(); polling = false; clearTimeout(timer);
    model.busy[id] = true; settled[id] = true; model.error = ""; emit();
    return request("/approvals/" + encodeURIComponent(id), "POST", {
      decision: decision, sessionId: item.sessionId, turnId: item.turnId
    }).then(function () {
      if (!alive) return;
      model.approvals = model.approvals.filter(function (entry) { return entry.id !== id; });
    }).catch(function (error) {
      if (alive) {
        // A failed response may follow a consumed one-time approval. Never retry automatically.
        model.error = integrationErrorText(error);
        model.approvals = model.approvals.filter(function (entry) { return entry.id !== id; });
      }
    }).finally(function () { if (alive) { delete model.busy[id]; emit(); schedule(); } });
  };
  model.destroy = function (rejectPending) {
    if (!alive) return;
    alive = false; generation++; clearTimeout(timer); if (controller) controller.abort();
    if (rejectPending) model.approvals.forEach(function (item) {
      if (!model.busy[item.id] && valid(item)) request("/approvals/" + encodeURIComponent(item.id), "POST", {
        decision: "reject", sessionId: item.sessionId, turnId: item.turnId
      }).catch(function () {});
    });
    model.approvals = []; model.busy = {};
  };
  return model;
}

function mountIntegrationApprovals(root, options) {
  var signature = "", model = createIntegrationApprovals(api.integrations, render, options);
  function render(data) {
    var next = JSON.stringify([data.approvals, data.busy, data.error]);
    if (next === signature) return; signature = next;
    var focused = document.activeElement, focusId = focused && focused.dataset.approvalId, focusDecision = focused && focused.dataset.decision;
    root.replaceChildren(); root.hidden = !data.approvals.length && !data.error;
    if (root.hidden) return;
    root.appendChild(integrationElement("h4", t("integrations.approvals.title")));
    root.appendChild(integrationElement("p", t("integrations.approvals.disclosure"), "integrations-hint"));
    if (data.error) { var error = integrationElement("p", data.error, "integrations-error"); error.setAttribute("role", "alert"); root.appendChild(error); }
    data.approvals.forEach(function (item) {
      var card = integrationElement("article", null, "integration-approval"), kind = item.kind === "mail-recipient" ? "recipient" : "tool";
      card.appendChild(integrationElement("h5", t("integrations.approvals." + kind)));
      card.appendChild(integrationElement("p", t("integrations.approvals.target") + ": " + String(item.target || "")));
      if (item.toolName) card.appendChild(integrationElement("p", item.toolName));
      card.appendChild(integrationElement("p", t("integrations.approvals.binding", { session: item.sessionId, turn: item.turnId }), "integrations-hint"));
      var args = typeof item.arguments === "string" ? item.arguments : JSON.stringify(item.arguments || {}, null, 2);
      var bounded = args.length <= 65536;
      card.appendChild(integrationElement("pre", bounded ? args : t("integrations.approvals.tooLarge")));
      card.appendChild(integrationElement("p", t("integrations.approvals.expires", { time: String(item.expiresAt) }), "integrations-hint"));
      var row = integrationElement("div", null, "integrations-actions");
      ["reject", "approve"].forEach(function (decision) {
        var button = integrationButton(t("integrations.approvals." + decision), function () { model.decide(item.id, decision); });
        button.dataset.approvalId = item.id; button.dataset.decision = decision;
        button.disabled = !!data.busy[item.id] || decision === "approve" && !bounded;
        row.appendChild(button);
      }); card.appendChild(row); root.appendChild(card);
    });
    if (focusId) {
      var match = Array.from(root.querySelectorAll("button")).find(function (button) { return button.dataset.approvalId === focusId && button.dataset.decision === focusDecision; });
      if (match && !match.disabled) match.focus();
      else { root.tabIndex = -1; root.focus(); }
    }
  }
  root.addEventListener("keydown", escape);
  function escape(event) {
    if (event.key !== "Escape") return;
    event.preventDefault(); event.stopPropagation();
    model.approvals.slice().forEach(function (item) { model.decide(item.id, "reject"); });
  }
  model.refresh();
  return { model: model, destroy: function (rejectPending) { model.destroy(rejectPending); root.removeEventListener("keydown", escape); root.replaceChildren(); root.hidden = true; } };
}

var chatIntegrationApprovals = null;
function startChatIntegrationApprovals(sessionId, turnId) {
  stopChatIntegrationApprovals();
  var root = document.getElementById("integration-chat-approvals"); if (!root) return;
  chatIntegrationApprovals = { sessionId: sessionId, turnId: turnId, view: mountIntegrationApprovals(root, { sessionId: sessionId, turnId: turnId }) };
}
function stopChatIntegrationApprovals(sessionId, turnId) {
  if (!chatIntegrationApprovals) return;
  if (sessionId && (chatIntegrationApprovals.sessionId !== sessionId || chatIntegrationApprovals.turnId !== turnId)) return;
  chatIntegrationApprovals.view.destroy(true); chatIntegrationApprovals = null;
}
function syncChatIntegrationApprovals() {
  if (chatIntegrationApprovals && (state.activeChatSessionId !== chatIntegrationApprovals.sessionId || state.tab !== "chat")) stopChatIntegrationApprovals();
}
if (typeof window !== "undefined" && window.addEventListener) window.addEventListener("pagehide", function () { stopChatIntegrationApprovals(); });
