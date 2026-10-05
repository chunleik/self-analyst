"use strict";

// Status and config actions are local-only. Only the confirmed POST may connect to Neo4j.
var neo4jSync = { mounted: false, busy: false, generation: 0, operation: 0, status: null, phase: null };
var neo4jStates = ["disabled", "unconfigured", "invalid", "ready", "running", "success", "failed", "unavailable"];
var neo4jCoreErrors = { "neo4j.invalidConfig": "invalid", "neo4j.disabled": "disabled",
  "neo4j.confirmationRequired": "confirmation", "neo4j.ontologyUnavailable": "unavailable", "neo4j.busy": "running",
  "neo4j.missingPassword": "credentials", "neo4j.snapshotTooLarge": "capacity", "neo4j.invalidSnapshot": "invalid",
  "neo4j.authenticationFailed": "authentication", "neo4j.connectionFailed": "connection", "neo4j.timeout": "timeout", "neo4j.syncFailed": "failed" };
var neo4jErrors = ["disabled", "invalid", "credentials", "confirmation", "targetChanged", "running", "capacity", "authentication", "connection", "timeout", "unavailable", "failed"];

function neo4jNode(tag, text, className) {
  var node = document.createElement(tag);
  if (text != null) node.textContent = text;
  if (className) node.className = className;
  return node;
}
function neo4jButton(key, action) {
  var button = neo4jNode("button", t(key), "btn btn-outline");
  button.type = "button"; button.addEventListener("click", action); return button;
}
function neo4jMessage(key, error) {
  if (!neo4jSync.message) return;
  neo4jSync.message.textContent = t(key);
  neo4jSync.message.classList.toggle("knowledge-error", !!error);
}
async function neo4jRequest(path, body) {
  try {
    var response = await fetch(API_BASE + "/desktop/ontology/neo4j/" + path, {
      method: body === undefined ? "GET" : "POST", credentials: "same-origin",
      headers: { "Content-Type": "application/json" }, body: body === undefined ? undefined : JSON.stringify(body)
    });
    var data = await response.json();
    if (!response.ok) {
      var suffix = String(data.errorCode || "").replace(/^neo4j\.error\./, "");
      throw new Error(neo4jErrors.includes(suffix) ? "neo4j.error." + suffix : "neo4j.error.unavailable");
    }
    return data;
  } catch (error) {
    if (neo4jErrors.some(function (suffix) { return error.message === "neo4j.error." + suffix; })) throw error;
    throw new Error("neo4j.error.unavailable");
  }
}
function stopNeo4jSync() {
  neo4jSync.mounted = false;
  ++neo4jSync.generation;
  neo4jSync.status = null;
  // An abandoned read must not keep a reopened panel locked; only a sent POST owns a persistent lock.
  if (neo4jSync.phase === "neo4j.checking") {
    ++neo4jSync.operation;
    neo4jSync.busy = false; neo4jSync.phase = null;
  }
}
function mountNeo4jSync(root) {
  stopNeo4jSync();
  neo4jSync.mounted = true;
  var panel = neo4jNode("section", null, "config-neo4j");
  var heading = neo4jNode("div", null, "config-neo4j-heading");
  neo4jSync.badge = neo4jNode("span", t("common.loading"), "knowledge-badge");
  heading.append(neo4jNode("h3", t("neo4j.title")), neo4jSync.badge); panel.append(heading);
  panel.append(neo4jNode("p", t("neo4j.intro"), "knowledge-muted"));
  var disclosure = neo4jNode("details"); disclosure.append(neo4jNode("summary", t("neo4j.scopeTitle")));
  disclosure.append(neo4jNode("p", t("neo4j.disclosure")), neo4jNode("p", t("neo4j.boundary")),
    neo4jNode("p", t("neo4j.namespaceHelp")), neo4jNode("p", t("neo4j.configHelp")),
    neo4jNode("pre", t("neo4j.configTemplate"), "config-neo4j-config"));
  panel.append(disclosure);
  neo4jSync.target = neo4jNode("dl", null, "config-neo4j-target"); panel.append(neo4jSync.target);
  neo4jSync.result = neo4jNode("p", null, "knowledge-muted"); panel.append(neo4jSync.result);
  neo4jSync.message = neo4jNode("p", null, "knowledge-status");
  neo4jSync.message.setAttribute("role", "status"); neo4jSync.message.setAttribute("aria-live", "polite");
  panel.append(neo4jSync.message);
  var actions = neo4jNode("div", null, "knowledge-actions");
  neo4jSync.settingsButton = neo4jButton("neo4j.settings", function () { openConfigModal("neo4j.enabled"); });
  neo4jSync.refreshButton = neo4jButton("neo4j.refresh", refreshNeo4jStatus);
  neo4jSync.syncButton = neo4jButton("neo4j.sync", confirmNeo4jSync); neo4jSync.syncButton.disabled = true;
  actions.append(neo4jSync.settingsButton, neo4jSync.refreshButton, neo4jSync.syncButton);
  panel.append(actions); root.append(panel);
  neo4jSync.refreshButton.disabled = neo4jSync.busy;
  if (neo4jSync.busy) {
    neo4jSync.badge.textContent = t("neo4j.state.running");
    neo4jMessage(neo4jSync.phase || "neo4j.checking");
  }
}
function neo4jCanSync(status) {
  return !!(status && status.target && status.target.enabled && status.configured
    && typeof status.targetFingerprint === "string" && status.targetFingerprint && ["ready", "success", "error"].includes(status.state));
}
function renderNeo4jStatus(status) {
  neo4jSync.status = status;
  var stateName = status && ({ invalidConfig: "invalid", missingPassword: "unconfigured", error: "failed" }[status.state] || status.state);
  if (!neo4jStates.includes(stateName)) stateName = "unavailable";
  neo4jSync.badge.textContent = t("neo4j.state." + stateName);
  neo4jSync.target.replaceChildren();
  var target = status && status.target || {};
  ["uri", "database", "namespace", "username", "passwordEnv", "timeoutSeconds"].forEach(function (key) {
    neo4jSync.target.append(neo4jNode("dt", t("neo4j.target." + key)),
      neo4jNode("dd", target[key] == null || target[key] === "" ? t("neo4j.notSet") : String(target[key])));
  });
  var result = status && status.lastResult || {};
  neo4jSync.result.textContent = "";
  if (result.success === true) {
    var coverage = result.coverage || {};
    function coverageStatus(value) {
      return ["current", "truncated", "unavailable"].includes(value) ? value : "unavailable";
    }
    neo4jSync.result.textContent = t("neo4j.lastSuccess", { at: String(result.at || ""),
      entities: Number(result.entities) || 0, assertions: Number(result.assertions) || 0, bytes: Number(result.bytes) || 0 })
      + " " + t("ontology.coverage", { wiki: t("ontology.status." + coverageStatus(coverage.wiki)),
        memory: t("ontology.status." + coverageStatus(coverage.memory)) }) + " " + t("ontology.periodNote");
    if (coverage.entryLimit != null) neo4jSync.result.textContent += " " + t("neo4j.coverageBounds", {
      read: Number(coverage.entriesRead) || 0, limit: Number(coverage.entryLimit) || 0,
      omitted: Number(coverage.coarseEntriesOmitted) || 0, filtered: Number(coverage.privacyFilteredSegments) || 0
    });
  }
  neo4jSync.syncButton.disabled = neo4jSync.busy || !neo4jCanSync(status);
  neo4jSync.refreshButton.disabled = neo4jSync.busy;
}
async function refreshNeo4jStatus() {
  if (!neo4jSync.mounted || neo4jSync.busy) return;
  var generation = ++neo4jSync.generation;
  try {
    var status = await neo4jRequest("status");
    if (!neo4jSync.mounted || generation !== neo4jSync.generation) return;
    renderNeo4jStatus(status);
    if (status.state === "missingPassword") neo4jMessage("neo4j.error.credentials", true);
    else if (status.state === "invalidConfig") neo4jMessage("neo4j.error.invalid", true);
    else if (status.state === "error") neo4jMessage("neo4j.error." + (neo4jCoreErrors[status.lastResult && status.lastResult.code] || "failed"), true);
    else if (status.state === "success") neo4jMessage("neo4j.completed");
    else neo4jSync.message.textContent = "";
  } catch (error) {
    if (!neo4jSync.mounted || generation !== neo4jSync.generation) return;
    renderNeo4jStatus(null); neo4jMessage(error.message, true);
  }
}
function neo4jConfirmation(status) {
  var target = status.target;
  return t("neo4j.confirmTitle") + "\n\n"
    + ["uri", "database", "namespace", "username", "passwordEnv", "timeoutSeconds"].map(function (key) {
      return t("neo4j.target." + key) + ": " + String(target[key]);
    }).join("\n") + "\n\n" + t("neo4j.disclosure") + "\n\n" + t("neo4j.namespaceHelp") + "\n\n" + t("neo4j.confirmSend");
}
async function confirmNeo4jSync() {
  if (!neo4jSync.mounted || neo4jSync.busy || !neo4jCanSync(neo4jSync.status)) return;
  neo4jSync.busy = true;
  var operation = ++neo4jSync.operation;
  var generation = ++neo4jSync.generation;
  var current = function () { return neo4jSync.mounted && generation === neo4jSync.generation; };
  neo4jSync.phase = "neo4j.checking";
  renderNeo4jStatus(neo4jSync.status); neo4jMessage(neo4jSync.phase);
  try {
    // Re-read before showing the target. The server rejects changes after this fingerprint was confirmed.
    var status = await neo4jRequest("status");
    if (!current()) return;
    renderNeo4jStatus(status);
    if (!neo4jCanSync(status)) { neo4jMessage("neo4j.notReady", true); return; }
    if (!window.confirm(neo4jConfirmation(status))) { neo4jMessage("neo4j.cancelled"); return; }
    neo4jSync.phase = "neo4j.syncing";
    neo4jMessage(neo4jSync.phase);
    var result = await neo4jRequest("sync", { confirmedTarget: status.targetFingerprint });
    if (current()) { renderNeo4jStatus(result); neo4jMessage("neo4j.completed"); }
  } catch (error) {
    if (current()) {
      neo4jMessage(error.message, true);
      // Discard the prior target on error; refresh is needed before another confirmation.
      renderNeo4jStatus(null);
    }
  } finally {
    if (operation === neo4jSync.operation) {
      neo4jSync.busy = false; neo4jSync.phase = null;
      if (current()) renderNeo4jStatus(neo4jSync.status);
      // A reopened panel reads current config, never an old request's target.
      else if (neo4jSync.mounted) await refreshNeo4jStatus();
    }
  }
}
