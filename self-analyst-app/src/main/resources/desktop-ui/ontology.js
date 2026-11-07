"use strict";

var knowledge = { mounted: false, offset: 0, detailOffset: 0, selected: null, items: [], generation: 0, detailGeneration: 0, menuId: 0 };

function ontologyRequest(path, method, body) {
  return fetch(API_BASE + "/desktop/ontology" + path, {
    method: method || "GET", credentials: "same-origin",
    headers: { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body)
  }).then(function (response) { return chatJsonResponse(response, t("ontology.error.unavailable")); });
}

function knowledgeNode(tag, text, className) {
  var node = document.createElement(tag);
  if (text != null) node.textContent = text;
  if (className) node.className = className;
  return node;
}
function knowledgeButton(key, action, className) {
  var button = knowledgeNode("button", t(key), className || "btn btn-outline");
  button.type = "button"; button.addEventListener("click", action); return button;
}
function knowledgeField(parent, key, input, className) {
  var label = knowledgeNode("label", null, "knowledge-field" + (className ? " " + className : ""));
  label.append(knowledgeNode("span", t(key)), input); parent.append(label); return input;
}
function knowledgeSelect(options) {
  var select = knowledgeNode("select");
  options.forEach(function (item) { var option = knowledgeNode("option", t(item[1])); option.value = item[0]; select.append(option); });
  return select;
}
function knowledgeCloseMenu(restoreFocus) {
  var menu = knowledge.openMenu;
  if (!menu) return;
  menu.panel.hidden = true; menu.trigger.setAttribute("aria-expanded", "false");
  knowledge.openMenu = null;
  if (restoreFocus) menu.trigger.focus();
}
function knowledgeMenu(key, entries) {
  var root = knowledgeNode("div", null, "knowledge-menu");
  var trigger = knowledgeButton(key, function () { toggle(!panel.hidden); });
  var panel = knowledgeNode("div", null, "knowledge-menu-panel"); panel.hidden = true;
  panel.id = "ontology-menu-" + (++knowledge.menuId);
  panel.setAttribute("role", "menu"); panel.setAttribute("aria-label", t(key));
  trigger.setAttribute("aria-haspopup", "menu"); trigger.setAttribute("aria-expanded", "false");
  trigger.setAttribute("aria-controls", panel.id);
  var items = entries.map(function (entry) {
    var item = knowledgeButton(entry[0], function () { knowledgeCloseMenu(true); entry[1](); }, entry[2]);
    item.setAttribute("role", "menuitem"); item.tabIndex = -1; panel.append(item); return item;
  });
  function toggle(close, last) {
    knowledgeCloseMenu(false);
    if (close) return;
    panel.hidden = false; trigger.setAttribute("aria-expanded", "true");
    knowledge.openMenu = { root: root, trigger: trigger, panel: panel };
    items[last ? items.length - 1 : 0].focus();
  }
  trigger.addEventListener("keydown", function (event) {
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault(); toggle(false, event.key === "ArrowUp");
    }
  });
  root.addEventListener("keydown", function (event) {
    if (panel.hidden || event.target === trigger) return;
    var index = items.indexOf(document.activeElement);
    if (event.key === "Escape") { event.preventDefault(); knowledgeCloseMenu(true); }
    else if (event.key === "Tab") knowledgeCloseMenu(false);
    else if (["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key) && index >= 0) {
      event.preventDefault();
      var next = event.key === "Home" ? 0 : event.key === "End" ? items.length - 1
        : (index + (event.key === "ArrowDown" ? 1 : -1) + items.length) % items.length;
      items[next].focus();
    }
  });
  root.append(trigger, panel); return root;
}
function knowledgeConfirm(key, message, action, successKey) {
  knowledgeDialog(key, function (form, dialog, error) {
    form.append(knowledgeNode("p", message));
    knowledgeSubmit(form, dialog, error, action, key, successKey);
  });
}
function knowledgeMessage(text, error) {
  var node = document.getElementById("knowledge-message");
  if (!node) return;
  node.textContent = text; node.classList.toggle("knowledge-error", !!error);
}
function knowledgeMarkSelected() {
  knowledge.items.forEach(function (item) {
    var selected = item.id === knowledge.selected;
    item.button.classList.toggle("selected", selected);
    item.button.setAttribute("aria-current", String(selected));
  });
}
function knowledgeTypeBadge(type) {
  var badge = knowledgeNode("span", t("ontology.type." + type), "knowledge-badge");
  badge.setAttribute("data-type", type); return badge;
}
function knowledgePeriod(entity) {
  if (!entity.start || !entity.end) return "";
  function stamp(value) {
    var date = new Date(typeof value === "number" ? value * 1000 : value);
    return date.toLocaleString(state.dateLocale || undefined);
  }
  return stamp(entity.start) + " – " + stamp(entity.end);
}
function knowledgeDebounce(action) {
  var timer;
  function cancel() { clearTimeout(timer); }
  return { cancel: cancel, run: function () { cancel(); return action(); },
    schedule: function () { cancel(); timer = setTimeout(action, 300); } };
}
function knowledgeTimeParams() {
  if (knowledge.time.value !== "custom") return knowledge.period || {};
  var result = {};
  ["start", "end"].forEach(function (name) {
    if (knowledge[name].value) result[name] = new Date(knowledge[name].value).toISOString();
  });
  return result;
}
function knowledgeSetTime(value) {
  knowledge.time.value = value; knowledge.period = {};
  knowledge.customTime.hidden = value !== "custom";
  if (value === "week" || value === "month") {
    var end = new Date(), start = new Date(end.getTime() - (value === "week" ? 7 : 30) * 24 * 60 * 60 * 1000);
    knowledge.period = { start: start.toISOString(), end: end.toISOString() };
  }
}
function knowledgeQuery(offset) {
  var params = new URLSearchParams({ q: knowledge.query.value, type: knowledge.type.value,
    unclassified: String(knowledge.unclassified.getAttribute("aria-pressed") === "true"), offset: String(offset), limit: "30" });
  var period = knowledgeTimeParams();
  Object.keys(period).forEach(function (name) { params.set(name, period[name]); });
  return params;
}

function openKnowledge() {
  if (!knowledge.mounted) mountKnowledge();
  return loadKnowledge();
}
function mountKnowledge() {
  var root = document.getElementById("knowledge-root");
  if (!root) return;
  knowledge.mounted = true;
  var heading = knowledgeNode("div", null, "knowledge-heading");
  var intro = knowledgeNode("div");
  intro.append(knowledgeNode("h2", t("ontology.title")), knowledgeNode("p", t("ontology.subtitle")));
  var actions = knowledgeNode("div", null, "knowledge-actions");
  actions.append(knowledgeMenu("ontology.new", [
    ["ontology.newProject", function () { knowledgeEntityForm(null, "project"); }],
    ["ontology.newTopic", function () { knowledgeEntityForm(null, "topic"); }]
  ]), knowledgeMenu("ontology.more", [["ontology.rebuild", function () {
    knowledgeConfirm("ontology.rebuild", t("ontology.rebuildHelp"), function () {
      return ontologyRequest("/rebuild", "POST", {});
    }, "ontology.rebuilt");
  }]]));
  document.addEventListener("click", function (event) {
    if (knowledge.openMenu && !knowledge.openMenu.root.contains(event.target)) knowledgeCloseMenu(false);
  });
  heading.append(intro, actions); root.append(heading);
  var filters = knowledgeNode("form", null, "knowledge-filters");
  knowledge.query = knowledgeField(filters, "ontology.search", knowledgeNode("input")); knowledge.query.type = "search";
  knowledge.query.maxLength = 300;
  knowledge.type = knowledgeField(filters, "ontology.type", knowledgeSelect([["", "ontology.all"]].concat(
    ["project", "activity", "topic", "application", "goal", "pattern", "improvement"].map(function (type) { return [type, "ontology.type." + type]; }))));
  knowledge.time = knowledgeField(filters, "ontology.timeRange", knowledgeSelect([
    ["all", "ontology.time.all"], ["week", "ontology.time.week"], ["month", "ontology.time.month"], ["custom", "ontology.time.custom"]
  ]));
  knowledge.customTime = knowledgeNode("div", null, "knowledge-custom-time"); knowledge.customTime.hidden = true;
  knowledge.start = knowledgeField(knowledge.customTime, "ontology.start", knowledgeNode("input")); knowledge.start.type = "datetime-local";
  knowledge.end = knowledgeField(knowledge.customTime, "ontology.end", knowledgeNode("input")); knowledge.end.type = "datetime-local";
  knowledge.unclassified = knowledgeButton("ontology.unclassified", function () {
    var active = knowledge.unclassified.getAttribute("aria-pressed") !== "true";
    knowledge.unclassified.setAttribute("aria-pressed", String(active));
    if (active) knowledge.type.value = "activity";
    refresh.run();
  }, "btn btn-outline knowledge-organize"); knowledge.unclassified.setAttribute("aria-pressed", "false");
  filters.append(knowledge.unclassified, knowledge.customTime);
  var refresh = knowledgeDebounce(function () { knowledge.offset = 0; knowledge.detailOffset = 0; return loadKnowledge(); });
  knowledge.query.addEventListener("input", function () {
    ++knowledge.generation; ++knowledge.detailGeneration; refresh.schedule();
  });
  filters.addEventListener("submit", function (event) { event.preventDefault(); refresh.run(); });
  knowledge.query.addEventListener("keydown", function (event) {
    if (event.key === "Enter") { event.preventDefault(); refresh.run(); }
  });
  knowledge.type.addEventListener("change", function () {
    if (knowledge.type.value !== "activity") knowledge.unclassified.setAttribute("aria-pressed", "false");
    refresh.run();
  });
  knowledge.time.addEventListener("change", function () { knowledgeSetTime(knowledge.time.value); refresh.run(); });
  [knowledge.start, knowledge.end].forEach(function (input) { input.addEventListener("change", function () { refresh.run(); }); });
  root.append(filters);
  var message = knowledgeNode("p", null, "knowledge-status"); message.id = "knowledge-message"; message.setAttribute("role", "status"); message.setAttribute("aria-live", "polite"); root.append(message);
  knowledge.coverage = knowledgeNode("p", null, "knowledge-warning"); knowledge.coverage.hidden = true;
  knowledge.coverage.setAttribute("role", "status"); root.append(knowledge.coverage);
  var periodHelp = knowledgeNode("details", null, "knowledge-period-help");
  periodHelp.append(knowledgeNode("summary", t("ontology.periodHelp")), knowledgeNode("p", t("ontology.periodNote")));
  knowledge.omitted = knowledgeNode("p"); periodHelp.append(knowledge.omitted); root.append(periodHelp);
  var layout = knowledgeNode("div", null, "knowledge-layout");
  var side = knowledgeNode("section", null, "knowledge-list-panel"); side.setAttribute("aria-label", t("ontology.entities"));
  knowledge.list = knowledgeNode("div", null, "knowledge-list"); knowledge.pages = knowledgeNode("div", null, "knowledge-actions knowledge-pager");
  side.append(knowledge.list, knowledge.pages);
  knowledge.detail = knowledgeNode("section", null, "knowledge-detail"); knowledge.detail.setAttribute("aria-label", t("ontology.details"));
  knowledge.detail.append(knowledgeNode("p", t("ontology.select"), "knowledge-empty")); layout.append(side, knowledge.detail); root.append(layout);
}
async function loadKnowledge(scroll) {
  var generation = ++knowledge.generation, selected = knowledge.selected;
  ++knowledge.detailGeneration;
  knowledgeMessage(t("common.loading"));
  try {
    var page = await ontologyRequest("/entities?" + knowledgeQuery(knowledge.offset));
    if (generation !== knowledge.generation) return;
    knowledge.list.replaceChildren(); knowledge.pages.replaceChildren(); knowledge.items = [];
    if (!page.items.length) knowledge.list.append(knowledgeNode("p", t("ontology.empty"), "knowledge-empty"));
    page.items.forEach(function (entity) {
      var button = knowledgeNode("button", null, "knowledge-entity"); button.type = "button";
      button.append(knowledgeTypeBadge(entity.type), knowledgeNode("strong", entity.name));
      if (entity.start) button.append(knowledgeNode("small", knowledgePeriod(entity)));
      button.addEventListener("click", function () { knowledge.selected = entity.id; knowledge.detailOffset = 0; loadKnowledgeDetail(); });
      knowledge.list.append(button); knowledge.items.push({ id: entity.id, button: button });
    });
    knowledgeMarkSelected();
    knowledgePager(knowledge.pages, page, function (offset) { knowledge.offset = offset; loadKnowledge(); });
    var coverage = page.coverage || {};
    var warnings = ["wiki", "memory"].filter(function (source) { return ["unavailable", "truncated"].includes(coverage[source] || "unavailable"); });
    knowledge.coverage.hidden = !warnings.length;
    knowledge.coverage.textContent = warnings.length ? "⚠ " + t("ontology.coverage", {
      wiki: t("ontology.status." + (coverage.wiki || "unavailable")), memory: t("ontology.status." + (coverage.memory || "unavailable"))
    }) : "";
    knowledge.omitted.textContent = Number(coverage.coarseEntriesOmitted) ? t("ontology.coarseOmitted", { count: coverage.coarseEntriesOmitted }) : "";
    knowledge.omitted.hidden = !Number(coverage.coarseEntriesOmitted);
    knowledgeMessage(t("ontology.count", { count: page.total }));
    if (knowledge.selected) await loadKnowledgeDetail();
    if (scroll && generation === knowledge.generation && selected === knowledge.selected) {
      knowledge.list.scrollTop = scroll.list; knowledge.detail.scrollTop = scroll.detail;
    }
  } catch (error) {
    if (generation !== knowledge.generation) return;
    knowledge.list.replaceChildren(); knowledge.pages.replaceChildren(); knowledge.items = []; knowledge.detail.replaceChildren(); knowledge.coverage.textContent = ""; knowledge.coverage.hidden = true; knowledge.omitted.textContent = "";
    knowledgeMessage(error.message, true);
  }
}
function knowledgePager(parent, page, move) {
  if (page.offset > 0) parent.append(knowledgeButton("ontology.previous", function () { move(Math.max(0, page.offset - page.limit)); }));
  parent.append(knowledgeNode("span", t("ontology.page", { from: page.total ? Math.min(page.offset + 1, page.total) : 0,
    to: Math.min(page.offset + page.items.length, page.total), total: page.total })));
  if (page.hasMore) parent.append(knowledgeButton("ontology.next", function () { move(page.offset + page.limit); }));
}
async function loadKnowledgeDetail() {
  var id = knowledge.selected, generation = ++knowledge.detailGeneration;
  knowledgeMarkSelected();
  var params = knowledgeQuery(knowledge.detailOffset); params.set("limit", "20");
  try {
    var detail = await ontologyRequest("/entities/" + encodeURIComponent(id) + "?" + params);
    if (generation !== knowledge.detailGeneration || id !== knowledge.selected) return;
    renderKnowledgeDetail(detail);
  } catch (error) {
    if (generation !== knowledge.detailGeneration) return;
    knowledge.detail.replaceChildren(knowledgeNode("p", error.message, "knowledge-error"));
  }
}
function renderKnowledgeDetail(detail) {
  var entity = detail.entity, root = knowledge.detail;
  if (knowledge.openMenu && root.contains(knowledge.openMenu.root)) knowledgeCloseMenu(false);
  root.replaceChildren();
  var header = knowledgeNode("header", null, "knowledge-detail-header");
  var badges = knowledgeNode("div", null, "knowledge-badges"); badges.append(knowledgeTypeBadge(entity.type));
  if (entity.source !== "manual") {
    var readOnly = knowledgeNode("span", t("ontology.readOnlyBadge"), "knowledge-readonly");
    readOnly.tabIndex = 0; readOnly.title = t("ontology.readOnly"); readOnly.setAttribute("aria-describedby", "ontology-readonly-help");
    var help = knowledgeNode("span", t("ontology.readOnly"), "knowledge-sr-only"); help.id = "ontology-readonly-help";
    badges.append(readOnly, help);
  }
  header.append(badges, knowledgeNode("h2", entity.name));
  if (entity.description && entity.description !== entity.name) header.append(knowledgeNode("p", entity.description));
  if (entity.aliases.length) {
    var aliases = knowledgeNode("div", null, "knowledge-aliases"); aliases.setAttribute("aria-label", t("ontology.aliases"));
    entity.aliases.forEach(function (alias) { aliases.append(knowledgeNode("span", alias, "knowledge-alias")); }); header.append(aliases);
  }
  if (entity.start) header.append(knowledgeNode("p", knowledgePeriod(entity), "knowledge-muted"));
  if (entity.type === "activity") header.append(knowledgeNode("p", t("ontology.classification." + detail.classification), "knowledge-badge"));
  if (entity.attributes && entity.attributes.goalSource === "missing") header.append(knowledgeNode("p", t("ontology.missingGoal"), "knowledge-muted"));
  root.append(header);
  var actions = knowledgeNode("div", null, "knowledge-actions");
  if (entity.source === "manual") {
    actions.append(knowledgeButton("ontology.edit", function () { knowledgeEntityForm(entity); }, "btn btn-primary"));
  }
  if (["activity", "project", "pattern", "goal", "topic"].includes(entity.type)) {
    actions.append(knowledgeButton(entity.type === "activity" ? "ontology.assign" : "ontology.addRelation", function () { knowledgeRelationForm(entity); }));
  }
  if (entity.source === "manual") {
    actions.append(knowledgeMenu("ontology.more", [
      ["ontology.merge", function () { knowledgeMergeForm(entity); }],
      ["ontology.delete", function () {
        knowledgeConfirm("ontology.delete", t("ontology.confirmDelete", { name: entity.name }), async function () {
          await ontologyRequest("/entities/" + encodeURIComponent(entity.id), "DELETE");
          if (knowledge.selected === entity.id) { knowledge.selected = null; ++knowledge.detailGeneration; root.replaceChildren(); }
        });
      }, "btn btn-outline knowledge-danger"]
    ]));
  }
  root.append(actions);
  knowledgeEvidence(root, entity.evidence);
  root.append(knowledgeNode("h3", t("ontology.relations")));
  var period = knowledgeTimeParams();
  if (period.start || period.end) {
    var range = knowledge.time.value === "custom" ? [knowledge.start.value, knowledge.end.value].join(" – ") : t("ontology.time." + knowledge.time.value);
    var notice = knowledgeNode("div", null, "knowledge-time-notice");
    notice.append(knowledgeNode("span", t("ontology.timeFiltered", { range: range })), knowledgeButton("ontology.clearTime", function () {
      knowledgeSetTime("all"); knowledge.start.value = ""; knowledge.end.value = "";
      knowledge.offset = 0; knowledge.detailOffset = 0; loadKnowledge();
    })); root.append(notice);
  }
  if (!detail.relations.items.length) root.append(knowledgeNode("p", t("ontology.noRelations"), "knowledge-muted"));
  var groups = {};
  detail.relations.items.forEach(function (item) {
    var type = item.other.type;
    if (!groups[type]) groups[type] = [];
    groups[type].push(item);
  });
  Object.keys(groups).forEach(function (type) {
    var group = knowledgeNode("section", null, "knowledge-relation-group");
    group.setAttribute("data-type", type);
    group.append(knowledgeNode("h4", t("ontology.groupCount", { type: t("ontology.type." + type), count: groups[type].length })));
    groups[type].forEach(function (item) { group.append(knowledgeRelation(item)); }); root.append(group);
  });
  var pages = knowledgeNode("div", null, "knowledge-actions knowledge-pager"); knowledgePager(pages, detail.relations, function (offset) { knowledge.detailOffset = offset; loadKnowledgeDetail(); }); root.append(pages);
}
function knowledgeRelation(item) {
  var a = item.assertion, row = knowledgeNode("article", null, "knowledge-relation");
  var link = knowledgeNode("button", item.other.name, "knowledge-link"); link.type = "button";
  link.addEventListener("click", function () { knowledge.selected = item.other.id; knowledge.detailOffset = 0; loadKnowledgeDetail(); });
  var labelKey = "ontology.relationLabel." + a.predicate + "." + item.other.type;
  var label = t(labelKey); if (label === labelKey) label = t("ontology.predicate." + a.predicate);
  var status = a.status === "candidate" ? "candidate" : a.claimType;
  var icon = { confirmed: "✓", inferred: "~", observed: "•", legacy: "•", candidate: "?" }[status];
  var badge = knowledgeNode("span", icon + " " + t("ontology.claim." + status), "knowledge-claim");
  badge.setAttribute("data-status", status);
  row.append(knowledgeNode("span", label, "knowledge-relation-label"), link, badge);
  if (a.status === "candidate") row.append(knowledgeNode("p", t("ontology.candidate"), "knowledge-muted"));
  if (a.source === "alias-rule") row.append(knowledgeNode("p", t("ontology.aliasReason"), "knowledge-muted"));
  if (item.other.start) row.append(knowledgeNode("small", knowledgePeriod(item.other)));
  var buttons = knowledgeNode("div", null, "knowledge-actions");
  if (status !== "confirmed") buttons.append(knowledgeButton("ontology.confirm", function () { knowledgeDecision(a, "confirm"); }));
  buttons.append(knowledgeButton("ontology.reject", function () { knowledgeDecision(a, "reject"); })); row.append(buttons);
  knowledgeEvidence(row, a.evidence); return row;
}
function knowledgeEvidence(parent, evidence) {
  if (!evidence || !evidence.length) { parent.append(knowledgeNode("p", t("ontology.noEvidence"), "knowledge-muted")); return; }
  var details = knowledgeNode("details", null, "knowledge-evidence"); details.append(knowledgeNode("summary", t("ontology.evidence")));
  evidence.forEach(function (fact) {
    var item = knowledgeNode("div", null, "knowledge-evidence-item"); item.append(knowledgeNode("code", fact.ref));
    item.append(knowledgeNode("p", fact.available ? (fact.text || t("ontology.userEvidence")) : t("ontology.evidenceMissing")));
    details.append(item);
  }); parent.append(details);
}
async function knowledgeDecision(assertion, action) {
  try { await ontologyRequest("/relations", "POST", { subject: assertion.subject, predicate: assertion.predicate, object: assertion.object, action: action });
    await loadKnowledge({ list: knowledge.list.scrollTop, detail: knowledge.detail.scrollTop }); knowledgeMessage(t("ontology.saved")); } catch (error) { knowledgeMessage(error.message, true); }
}

function knowledgeDialog(title, build) {
  var opener = document.activeElement;
  var dialog = knowledgeNode("dialog", null, "knowledge-dialog");
  var titleNode = knowledgeNode("h2", t(title)); titleNode.id = "knowledge-dialog-title"; dialog.setAttribute("aria-labelledby", titleNode.id);
  var form = knowledgeNode("form"); var error = knowledgeNode("p", null, "knowledge-error"); error.setAttribute("role", "alert");
  dialog.append(titleNode, form, error); document.body.append(dialog);
  dialog.addEventListener("close", function () { dialog.remove(); if (opener && opener.isConnected) opener.focus(); });
  build(form, dialog, error); dialog.showModal();
}
function knowledgeSubmit(form, dialog, error, action, submitKey, successKey) {
  var buttons = knowledgeNode("div", null, "knowledge-actions");
  var submit = knowledgeNode("button", t(submitKey || "ontology.save"), "btn btn-primary"); submit.type = "submit";
  buttons.append(submit, knowledgeButton("ontology.cancel", function () { dialog.close(); })); form.append(buttons);
  form.addEventListener("submit", async function (event) {
    event.preventDefault(); if (submit.disabled) return; submit.disabled = true; error.textContent = "";
    try { await action(); dialog.close(); await loadKnowledge(); knowledgeMessage(t(successKey || "ontology.saved")); }
    catch (failure) { error.textContent = failure.message; }
    finally { submit.disabled = false; }
  });
}
function knowledgeEntityForm(entity, type) {
  knowledgeDialog(entity ? "ontology.edit" : "ontology.create", function (form, dialog, error) {
    var name = knowledgeField(form, "ontology.name", knowledgeNode("input")); name.required = true; name.maxLength = 300; name.value = entity ? entity.name : "";
    var description = knowledgeField(form, "ontology.description", knowledgeNode("textarea")); description.maxLength = 2000; description.value = entity ? entity.description : "";
    var aliases = knowledgeField(form, "ontology.aliasesHelp", knowledgeNode("textarea")); aliases.value = entity ? entity.aliases.join("\n") : "";
    knowledgeSubmit(form, dialog, error, async function () {
      var result = await ontologyRequest(entity ? "/entities/" + encodeURIComponent(entity.id) : "/entities", entity ? "PUT" : "POST", {
        type: entity ? entity.type : type, name: name.value, description: description.value,
        aliases: aliases.value.split("\n").map(function (s) { return s.trim(); }).filter(Boolean)
      }); knowledge.selected = result.id; knowledge.detailOffset = 0;
    });
  });
}
function knowledgePicker(form, error, type, excluded, manualOnly) {
  var root = knowledgeNode("div", null, "knowledge-picker"); form.append(root);
  var query = knowledgeField(root, "ontology.targetSearch", knowledgeNode("input")); query.type = "search"; query.maxLength = 300;
  query.required = true; query.autocomplete = "off"; query.setAttribute("role", "combobox");
  query.setAttribute("aria-autocomplete", "list"); query.setAttribute("aria-haspopup", "listbox");
  var list = knowledgeNode("div", null, "knowledge-picker-list");
  list.id = "ontology-picker-" + (++knowledge.menuId); list.setAttribute("role", "listbox"); list.setAttribute("aria-label", t("ontology.target"));
  query.setAttribute("aria-controls", list.id);
  var status = knowledgeNode("p", null, "knowledge-muted"); status.setAttribute("role", "status"); status.setAttribute("aria-live", "polite");
  var more = knowledgeButton("ontology.next", function () { load(true); }); more.hidden = true;
  root.append(list, status, more);
  var generation = 0, currentType = type, options = [], active = -1, selected = "", loading = false, hasMore = false, nextOffset = 0, resultsQuery = "";
  function expand(open) {
    list.hidden = !open; query.setAttribute("aria-expanded", String(open)); more.hidden = !open || !hasMore;
    if (!open) query.removeAttribute("aria-activedescendant");
    else if (active >= 0) query.setAttribute("aria-activedescendant", options[active].node.id);
  }
  function highlight(index) {
    active = index;
    options.forEach(function (option, i) { option.node.setAttribute("aria-selected", String(i === active)); });
    if (active >= 0) {
      query.setAttribute("aria-activedescendant", options[active].node.id);
      options[active].node.scrollIntoView({ block: "nearest" });
    } else query.removeAttribute("aria-activedescendant");
  }
  function choose(index) {
    if (!options[index]) return;
    selected = options[index].entity.id; query.value = options[index].entity.name;
    query.setCustomValidity(""); highlight(index); expand(false); query.focus();
  }
  function reset() {
    ++generation; resultsQuery = query.value; selected = ""; options = []; active = -1; hasMore = false; nextOffset = 0; loading = false;
    list.replaceChildren(); query.removeAttribute("aria-activedescendant"); query.setCustomValidity(t("ontology.targetRequired"));
    more.hidden = true; status.textContent = t("ontology.targetLoading");
  }
  async function load(append) {
    if (!root.isConnected || (append && (loading || !hasMore))) return;
    var current = ++generation; loading = true; list.setAttribute("aria-busy", "true"); more.disabled = true;
    status.textContent = t("ontology.targetLoading");
    try {
      var page = await ontologyRequest("/entities?" + new URLSearchParams({ type: currentType, q: resultsQuery,
        limit: "30", offset: String(append ? nextOffset : 0) }));
      if (current !== generation || !root.isConnected) return;
      if (!append) { list.replaceChildren(); options = []; active = -1; query.removeAttribute("aria-activedescendant"); }
      page.items.filter(function (e) { return e.id !== excluded && (!manualOnly || e.source === "manual"); }).forEach(function (entity) {
        var option = knowledgeNode("div", entity.name + (entity.source === "manual" ? "" : " · " + t("ontology.source")), "knowledge-picker-option");
        var index = options.length; option.id = list.id + "-" + index; option.setAttribute("role", "option"); option.setAttribute("aria-selected", "false");
        option.addEventListener("mousedown", function (event) { event.preventDefault(); });
        option.addEventListener("click", function () { choose(index); });
        list.append(option); options.push({ node: option, entity: entity });
      });
      hasMore = page.hasMore; nextOffset = page.offset + page.limit;
      more.hidden = list.hidden || !hasMore;
      status.textContent = options.length ? t("ontology.targetCount", { count: options.length }) : t("ontology.targetEmpty");
      error.textContent = "";
    } catch (failure) {
      if (current === generation && root.isConnected) { error.textContent = failure.message; status.textContent = ""; }
    } finally {
      if (current === generation) { loading = false; list.setAttribute("aria-busy", "false"); more.disabled = false; }
    }
  }
  var search = knowledgeDebounce(function () { load(false); });
  query.addEventListener("input", function () { reset(); expand(true); search.schedule(); });
  query.addEventListener("focus", function () { if (!selected) expand(true); });
  root.addEventListener("focusout", function (event) { if (!root.contains(event.relatedTarget)) expand(false); });
  query.addEventListener("keydown", function (event) {
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault(); expand(true);
      if (options.length) highlight(event.key === "ArrowDown" ? (active + 1) % options.length : (active < 0 ? options.length - 1 : (active - 1 + options.length) % options.length));
    } else if (event.key === "Escape" && !list.hidden) { event.preventDefault(); event.stopPropagation(); expand(false); }
    else if (event.key === "Enter" && (!list.hidden || !selected)) {
      event.preventDefault();
      if (active >= 0 && !list.hidden) choose(active); else { expand(true); search.run(); }
    }
  });
  list.addEventListener("scroll", function () {
    if (list.scrollTop + list.clientHeight >= list.scrollHeight - 24) load(true);
  });
  expand(true); query.setCustomValidity(t("ontology.targetRequired")); load(false);
  return { value: function () { if (!selected) throw new Error(t("ontology.targetRequired")); return selected; },
    type: function (value) { currentType = value; query.value = ""; search.cancel(); reset(); expand(true); load(false); } };
}
function knowledgeMergeForm(entity) {
  knowledgeDialog("ontology.merge", function (form, dialog, error) {
    form.append(knowledgeNode("p", t("ontology.mergeHelp")));
    var picker = knowledgePicker(form, error, entity.type, entity.id, true);
    knowledgeSubmit(form, dialog, error, async function () {
      var result = await ontologyRequest("/merge", "POST", { from: entity.id, to: picker.value() });
      knowledge.selected = result.id; knowledge.detailOffset = 0;
    });
  });
}
function knowledgeRelationForm(entity) {
  var options = {
    activity: [["relatedTo:project:out", "ontology.assignProject"], ["about:topic:out", "ontology.assignTopic"]],
    project: [["supports:goal:out", "ontology.supportsGoal"], ["relatedTo:activity:in", "ontology.linkActivity"], ["relatedTo:pattern:in", "ontology.linkPattern"]],
    pattern: [["relatedTo:project:out", "ontology.assignProject"], ["relatedTo:topic:out", "ontology.assignTopic"]],
    goal: [["supports:project:in", "ontology.linkProject"]],
    topic: [["about:activity:in", "ontology.linkActivity"], ["relatedTo:pattern:in", "ontology.linkPattern"]]
  }[entity.type];
  knowledgeDialog("ontology.addRelation", function (form, dialog, error) {
    var relation = knowledgeField(form, "ontology.relation", knowledgeSelect(options));
    var picker = knowledgePicker(form, error, relation.value.split(":")[1], entity.id);
    relation.addEventListener("change", function () { picker.type(relation.value.split(":")[1]); });
    knowledgeSubmit(form, dialog, error, async function () {
      var parts = relation.value.split(":");
      await ontologyRequest("/relations", "POST", { subject: parts[2] === "in" ? picker.value() : entity.id,
        predicate: parts[0], object: parts[2] === "in" ? entity.id : picker.value(), action: "confirm" });
    });
  });
}
