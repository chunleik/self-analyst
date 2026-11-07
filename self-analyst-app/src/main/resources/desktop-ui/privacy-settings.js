"use strict";

// Each mount owns its requests. Leaving the panel invalidates asynchronous responses.
var privacySettings = null;
function stopPrivacySettings() {
  if (privacySettings) privacySettings.active = false;
  privacySettings = null;
}
function mountPrivacySettings(root) {
  stopPrivacySettings();
  var view = { active: true, loaded: false, busy: false, apps: [], sites: [], candidates: [], selected: -1 };
  privacySettings = view;
  function node(tag, text, className) {
    var element = document.createElement(tag);
    if (text != null) element.textContent = text;
    if (className) element.className = className;
    return element;
  }
  function button(key, action) {
    var element = node("button", t(key), "btn btn-outline");
    element.type = "button"; element.addEventListener("click", action); return element;
  }
  function message(key, error) {
    notice.textContent = key ? t(key) : "";
    notice.classList.toggle("privacy-error", !!error);
  }
  function key(value) { return value.trim().toLowerCase(); }
  function valid(value) { return !!value.trim() && !/[,;\u0000-\u001f\u007f]/.test(value); }
  function editable() { return view.active && view.loaded && !view.busy; }
  function draft() { return { apps: view.apps, sites: view.sites }; }
  function dirty() {
    state.configDirty = JSON.stringify(draft()) !== view.baseline || !!appInput.value.trim() || !!siteInput.value.trim();
    message(state.configDirty ? "privacy.unsaved" : "", false);
    controls();
  }
  function controls() {
    panel.querySelectorAll("input, select, button").forEach(function (element) { element.disabled = !editable(); });
    retry.disabled = view.busy; retry.hidden = view.loaded;
    save.disabled = !editable() || !state.configDirty;
  }
  var panel = node("section", null, "privacy-settings");
  var scroll = node("div", null, "config-scroll-area");
  scroll.append(node("h3", t("privacy.title")), node("p", t("privacy.intro"), "privacy-muted"));
  var boundary = node("div", null, "privacy-boundary");
  boundary.append(node("p", t("privacy.captureHelp")), node("p", t("privacy.historyHelp")));
  scroll.append(boundary, node("h4", t("privacy.apps")));
  var appLabel = node("label", t("privacy.appLabel")); appLabel.htmlFor = "privacy-app-input";
  var addRow = node("div", null, "privacy-add-row"), combo = node("div", null, "privacy-combo");
  var appInput = node("input"); appInput.id = "privacy-app-input"; appInput.type = "text";
  appInput.placeholder = t("privacy.appPlaceholder"); appInput.autocomplete = "off";
  appInput.setAttribute("role", "combobox"); appInput.setAttribute("aria-autocomplete", "list");
  appInput.setAttribute("aria-controls", "privacy-candidates"); appInput.setAttribute("aria-expanded", "false");
  var list = node("div", null, "privacy-candidates"); list.id = "privacy-candidates"; list.setAttribute("role", "listbox"); list.hidden = true;
  combo.append(appInput, list); addRow.append(combo, button("privacy.addApp", function () { addApp(appInput.value); }));
  var candidateNotice = node("p", null, "privacy-muted"); candidateNotice.id = "privacy-candidate-note";
  appInput.setAttribute("aria-describedby", candidateNotice.id);
  var rows = node("div", null, "privacy-apps");
  scroll.append(appLabel, addRow, candidateNotice, rows, node("h4", t("privacy.sites")), node("p", t("privacy.sitesHelp"), "privacy-muted"));
  var siteLabel = node("label", t("privacy.siteLabel")); siteLabel.htmlFor = "privacy-site-input";
  var siteInput = node("input"); siteInput.id = "privacy-site-input"; siteInput.type = "text"; siteInput.placeholder = "example.com";
  var siteRow = node("div", null, "privacy-add-row"); siteRow.append(siteInput, button("privacy.addSite", addSite));
  var chips = node("div", null, "privacy-chips"); scroll.append(siteLabel, siteRow, chips);
  var footer = node("div", null, "config-action-bar"), notice = node("p", null, "privacy-status");
  notice.setAttribute("role", "status"); notice.setAttribute("aria-live", "polite");
  var actions = node("div", null, "privacy-actions"), retry = button("privacy.retry", load), save = button("privacy.save", saveSettings);
  save.className = "btn btn-primary"; actions.append(retry, save); footer.append(notice, actions);
  panel.append(scroll, footer); root.replaceChildren(panel);
  function hideCandidates() { list.hidden = true; view.selected = -1; appInput.setAttribute("aria-expanded", "false"); appInput.removeAttribute("aria-activedescendant"); }
  function renderCandidates() {
    list.replaceChildren();
    view.filtered = view.candidates.filter(function (item) {
      return !view.apps.some(function (rule) { return key(rule.app) === key(item.app); }) && key(item.app).includes(key(appInput.value));
    });
    if (view.selected >= view.filtered.length) view.selected = -1;
    view.filtered.forEach(function (item, index) {
      var option = node("div", item.app, "privacy-option"); option.id = "privacy-option-" + index;
      option.setAttribute("role", "option"); option.setAttribute("aria-selected", String(index === view.selected));
      option.addEventListener("mousedown", function (event) { event.preventDefault(); });
      option.addEventListener("click", function () { addApp(item.app); }); list.append(option);
    });
    list.hidden = !view.filtered.length;
    appInput.setAttribute("aria-expanded", String(!list.hidden));
    if (view.selected >= 0) {
      appInput.setAttribute("aria-activedescendant", "privacy-option-" + view.selected);
      var active = list.children[view.selected]; if (active.scrollIntoView) active.scrollIntoView({ block: "nearest" });
    } else appInput.removeAttribute("aria-activedescendant");
  }
  function renderRules() {
    rows.replaceChildren(); chips.replaceChildren();
    if (!view.apps.length) rows.append(node("p", t("privacy.noApps"), "privacy-muted"));
    view.apps.forEach(function (rule, index) {
      var row = node("div", null, "privacy-app-row"), name = node("span", rule.app, "privacy-app-name"), mode = node("select");
      mode.setAttribute("aria-label", t("privacy.modeFor", { app: rule.app }));
      ["model", "capture"].forEach(function (value) { var option = node("option", t("privacy.mode." + value)); option.value = value; mode.append(option); });
      mode.value = rule.mode;
      mode.addEventListener("change", function () { if (!editable()) return; rule.mode = mode.value; dirty(); });
      var remove = button("privacy.remove", function () { if (!editable()) return; view.apps.splice(index, 1); renderRules(); dirty(); });
      remove.setAttribute("aria-label", t("privacy.removeItem", { item: rule.app })); row.append(name, mode, remove); rows.append(row);
    });
    view.sites.forEach(function (site, index) {
      var chip = node("span", null, "privacy-chip"), remove = button("privacy.remove", function () { if (!editable()) return; view.sites.splice(index, 1); renderRules(); dirty(); });
      remove.setAttribute("aria-label", t("privacy.removeItem", { item: site })); chip.append(node("span", site), remove); chips.append(chip);
    });
    controls();
  }
  function addApp(value) {
    if (!editable()) return;
    value = value.trim();
    if (!valid(value)) { message("privacy.error.invalidApp", true); return; }
    if (view.apps.some(function (rule) { return key(rule.app) === key(value); })) { message("privacy.duplicate", true); return; }
    view.apps.push({ app: value, mode: "model" }); appInput.value = ""; hideCandidates(); renderRules(); dirty(); appInput.focus();
  }
  function addSite() {
    if (!editable()) return;
    var value = siteInput.value.trim();
    if (!valid(value)) { message("privacy.error.invalidSite", true); return; }
    if (view.sites.some(function (site) { return key(site) === key(value); })) { message("privacy.duplicate", true); return; }
    view.sites.push(value); siteInput.value = ""; renderRules(); dirty(); siteInput.focus();
  }
  appInput.addEventListener("input", function () { view.selected = -1; dirty(); renderCandidates(); });
  appInput.addEventListener("focus", function () { if (editable()) renderCandidates(); });
  appInput.addEventListener("blur", hideCandidates);
  appInput.addEventListener("keydown", function (event) {
    if (!editable() || event.isComposing) return;
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault(); renderCandidates();
      var count = view.filtered.length;
      if (count) view.selected = (view.selected + (event.key === "ArrowDown" ? 1 : view.selected < 0 ? 0 : -1) + count) % count;
      renderCandidates();
    } else if (event.key === "Enter") {
      event.preventDefault(); addApp(!list.hidden && view.selected >= 0 ? view.filtered[view.selected].app : appInput.value);
    } else if (event.key === "Escape" && !list.hidden) { event.preventDefault(); event.stopPropagation(); hideCandidates(); }
  });
  siteInput.addEventListener("input", dirty);
  siteInput.addEventListener("keydown", function (event) { if (event.key === "Enter" && !event.isComposing) { event.preventDefault(); addSite(); } });
  function accept(data) {
    if (!data || !Array.isArray(data.apps) || !Array.isArray(data.sites) || !Array.isArray(data.candidates)
        || data.apps.some(function (rule) { return !rule || typeof rule.app !== "string" || !valid(rule.app) || !["capture", "model"].includes(rule.mode); })
        || data.sites.some(function (site) { return typeof site !== "string" || !valid(site); })) throw new Error("invalidPayload");
    var unique = new Map();
    data.apps.forEach(function (rule) { var previous = unique.get(key(rule.app)); if (!previous || rule.mode === "capture") unique.set(key(rule.app), { app: rule.app.trim(), mode: rule.mode }); });
    view.apps = Array.from(unique.values()); view.sites = Array.from(new Map(data.sites.map(function (site) { return [key(site), site.trim()]; })).values());
    view.candidates = data.candidates.filter(function (item) { return item && typeof item.app === "string" && valid(item.app); });
    candidateNotice.textContent = t(data.candidatesError === "partial" ? "privacy.candidatesPartial" : data.candidatesError ? "privacy.candidatesUnavailable" : view.candidates.length ? "privacy.candidatesHelp" : "privacy.candidatesEmpty");
    view.loaded = true; appInput.value = ""; siteInput.value = ""; view.baseline = JSON.stringify(draft()); state.configDirty = false; renderRules();
  }
  async function request(body) {
    var response = await fetch(API_BASE + "/desktop/privacy", { method: body === undefined ? "GET" : "PUT", credentials: "same-origin",
      headers: { "Content-Type": "application/json" }, body: body === undefined ? undefined : JSON.stringify(body) });
    var data = await response.json(); if (!response.ok) throw new Error(data.errorCode || "unavailable"); return data;
  }
  async function load() {
    if (view.busy || !view.active) return;
    view.busy = true; controls(); message("privacy.loading");
    try { var data = await request(); if (view.active) { accept(data); message(""); } }
    catch (error) { if (view.active) message("privacy.error.unavailable", true); }
    finally { view.busy = false; if (view.active) controls(); }
  }
  async function saveSettings() {
    if (!editable()) return;
    if (appInput.value.trim() || siteInput.value.trim()) { message("privacy.pendingInput", true); return; }
    view.busy = true; state.configSaving = true; hideCandidates(); controls(); message("privacy.saving");
    try {
      var data = await request(draft());
      if (view.active) { if (data.applied !== true) throw new Error("saveFailed"); accept(data); message("privacy.saved"); }
    } catch (error) {
      if (view.active) message("privacy.error." + (["invalidPayload", "invalidApp", "invalidMode", "invalidSite"].includes(error.message) ? error.message : "saveFailed"), true);
    } finally { view.busy = false; state.configSaving = false; if (view.active) controls(); }
  }
  return load();
}
