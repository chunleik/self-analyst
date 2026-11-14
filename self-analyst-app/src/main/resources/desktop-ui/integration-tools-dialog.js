"use strict";

// A read-only view of one connection's discovered tools. Network discovery is explicit.
function createIntegrationToolsDialog(options) {
  options = options || {};
  var dialog = document.createElement("dialog"), active = false, destroyed = false, trigger = null;
  var current = null, opening = 0, requesting = false;
  dialog.className = "integration-tools-dialog";
  dialog.setAttribute("aria-label", t("integrations.catalog.title"));
  function element(tag, text, className) {
    var node = document.createElement(tag);
    if (text != null) node.textContent = String(text);
    if (className) node.className = className;
    return node;
  }
  var heading = element("h3", t("integrations.catalog.title"));
  var identity = element("p", "", "integration-catalog-identity");
  var endpoint = element("p", "", "integration-catalog-endpoint");
  var disclosure = element("p", t("integrations.catalog.disclosure"), "integrations-disclosure");
  var status = element("p", "", "integrations-hint"); status.setAttribute("role", "status");
  var hint = element("p", "", "integrations-hint");
  var error = element("p", "", "integrations-error"); error.setAttribute("role", "alert");
  var list = element("div", null, "integration-catalog-list");
  var actions = element("div", null, "integrations-actions");
  var discover = element("button", t("integrations.catalog.discover"), "btn btn-sm btn-outline"); discover.type = "button";
  var closeButton = element("button", t("integrations.catalog.close"), "btn btn-sm btn-outline"); closeButton.type = "button";
  closeButton.autofocus = true;
  actions.appendChild(discover); actions.appendChild(closeButton);
  [heading, identity, endpoint, disclosure, status, hint, error, list, actions].forEach(function (node) { dialog.appendChild(node); });
  document.body.appendChild(dialog);

  function render() {
    if (!active || !current) return;
    identity.textContent = String(current.name || current.id || "");
    endpoint.textContent = String(current.endpoint || "");
    var states = ["disabled", "tested", "testing", "ready", "degraded", "auth_required", "unavailable", "not_connected"];
    var state = current.state === "needs_auth" ? "auth_required" : current.state;
    state = states.indexOf(state) >= 0 ? state : "unavailable";
    var busy = !!current.busy || requesting;
    dialog.setAttribute("aria-busy", String(busy));
    status.textContent = busy ? t("integrations.catalog.loading") : t("integrations.catalog.connectionState") + " " + t("integrations.state." + state);
    hint.textContent = String(current.hint || ""); hint.hidden = !hint.textContent;
    error.textContent = String(current.error || "");
    discover.textContent = t(current.known ? "integrations.catalog.refresh" : "integrations.catalog.discover");
    discover.disabled = busy || !current.canDiscover;
    var focusedTool = list.contains(document.activeElement);
    list.replaceChildren();
    // Refreshing the directory removes summary/schema nodes. Keep focus inside the modal.
    if (focusedTool) closeButton.focus();
    var tools = Array.isArray(current.tools) ? current.tools : [];
    if (!current.known) {
      list.appendChild(element("p", t(tools.length ? "integrations.catalog.stale" : "integrations.catalog.undiscovered"), "integrations-hint"));
      if (!tools.length) return;
    }
    list.appendChild(element("p", t("integrations.catalog.cached"), "integrations-hint"));
    if (!tools.length) list.appendChild(element("p", t("integrations.catalog.empty")));
    tools.forEach(function (tool) {
      var card = element("article", null, "integration-tool");
      card.appendChild(element("h4", tool.name || t("integrations.catalog.unnamed")));
      card.appendChild(element("p", t(tool.approved === true ? "integrations.catalog.approved" : "integrations.catalog.unapproved"), "integration-catalog-approval"));
      card.appendChild(element("p", tool.description || t("integrations.catalog.noDescription")));
      var details = element("details"), summary = element("summary", t("integrations.catalog.parameters"));
      details.appendChild(summary);
      var schema = tool.inputSchema == null ? t("integrations.catalog.noSchema")
        : typeof tool.inputSchema === "string" ? tool.inputSchema : JSON.stringify(tool.inputSchema, null, 2);
      var parameters = element("pre", schema); parameters.tabIndex = 0;
      details.appendChild(parameters); card.appendChild(details); list.appendChild(card);
    });
  }
  function finish() {
    if (!active) return;
    active = false; opening++; requesting = false; current = null;
    var restore = trigger; trigger = null;
    // Notify once, including native Escape/close, so the owner can cancel discovery.
    if (typeof options.onClose === "function") options.onClose();
    if (restore && restore.isConnected && typeof restore.focus === "function") restore.focus();
  }
  function close() {
    if (!active) return;
    if (dialog.open) dialog.close();
    finish();
  }
  closeButton.addEventListener("click", close);
  dialog.addEventListener("cancel", function (event) { event.preventDefault(); close(); });
  dialog.addEventListener("keydown", function (event) {
    if (event.key === "Escape") { event.preventDefault(); event.stopPropagation(); close(); return; }
    if (event.key !== "Tab" || !active) return;
    // Native dialog inertness does not stop Chromium from moving Tab focus to browser chrome.
    // Recompute after every render so disabled discovery and collapsed schemas are skipped.
    var focusable = Array.from(dialog.querySelectorAll("button, [href], input, select, textarea, summary, [tabindex]"))
      .filter(function (node) { return !node.disabled && !node.hidden && node.tabIndex >= 0 && node.getClientRects().length > 0; });
    event.preventDefault(); event.stopPropagation();
    var index = focusable.indexOf(document.activeElement), step = event.shiftKey ? -1 : 1;
    var next = index < 0 ? event.shiftKey ? focusable.length - 1 : 0 : (index + step + focusable.length) % focusable.length;
    (focusable[next] || closeButton).focus();
  });
  dialog.addEventListener("close", function () { if (!dialog.open) finish(); });
  discover.addEventListener("click", function () {
    if (!active || requesting || !current || current.busy || !current.canDiscover || typeof options.onDiscover !== "function") return;
    requesting = true; var generation = opening; render();
    Promise.resolve().then(function () {
      if (active && generation === opening) return options.onDiscover();
    }).catch(function () {
      if (active && generation === opening && current) current = Object.assign({}, current, { error: t("integrations.catalog.failed") });
    }).finally(function () { if (active && generation === opening) { requesting = false; render(); } });
  });
  return {
    open: function (view, returnFocus) {
      if (destroyed || !view || view.kind !== "mcp" && view.kind !== "legacy") return;
      if (active) close();
      current = view; trigger = returnFocus || document.activeElement; active = true; opening++; render();
      dialog.showModal(); closeButton.focus();
    },
    update: function (view) {
      if (!active || destroyed) return;
      if (!view || !current || view.id !== current.id || view.kind !== current.kind) { close(); return; }
      current = view; render();
    },
    close: close,
    destroy: function () { if (destroyed) return; close(); destroyed = true; dialog.remove(); }
  };
}
