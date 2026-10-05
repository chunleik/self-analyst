"use strict";

var knowledge = { mounted: false, offset: 0, detailOffset: 0, selected: null, items: [], generation: 0, detailGeneration: 0 };

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
function knowledgePeriod(entity) {
  if (!entity.start || !entity.end) return "";
  function stamp(value) {
    var date = new Date(typeof value === "number" ? value * 1000 : value);
    return date.toLocaleString(state.dateLocale || undefined);
  }
  return stamp(entity.start) + " – " + stamp(entity.end);
}
function knowledgeQuery(offset) {
  var params = new URLSearchParams({ q: knowledge.query.value, type: knowledge.type.value,
    unclassified: String(knowledge.unclassified.checked), offset: String(offset), limit: "30" });
  ["start", "end"].forEach(function (name) {
    if (knowledge[name].value) params.set(name, new Date(knowledge[name].value).toISOString());
  });
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
  actions.append(knowledgeButton("ontology.newProject", function () { knowledgeEntityForm(null, "project"); }),
    knowledgeButton("ontology.newTopic", function () { knowledgeEntityForm(null, "topic"); }),
    knowledgeButton("ontology.rebuild", async function () {
      knowledgeMessage(t("common.loading"));
      try { await ontologyRequest("/rebuild", "POST", {}); await loadKnowledge(); knowledgeMessage(t("ontology.rebuilt")); }
      catch (error) { knowledgeMessage(error.message, true); }
    }));
  heading.append(intro, actions); root.append(heading);
  var filters = knowledgeNode("form", null, "knowledge-filters");
  knowledge.query = knowledgeField(filters, "ontology.search", knowledgeNode("input")); knowledge.query.type = "search";
  knowledge.query.maxLength = 300;
  knowledge.type = knowledgeField(filters, "ontology.type", knowledgeSelect([["", "ontology.all"]].concat(
    ["project", "activity", "topic", "application", "goal", "pattern", "improvement"].map(function (type) { return [type, "ontology.type." + type]; }))));
  knowledge.start = knowledgeField(filters, "ontology.start", knowledgeNode("input")); knowledge.start.type = "datetime-local";
  knowledge.end = knowledgeField(filters, "ontology.end", knowledgeNode("input")); knowledge.end.type = "datetime-local";
  knowledge.unclassified = knowledgeField(filters, "ontology.unclassified", knowledgeNode("input"), "knowledge-check"); knowledge.unclassified.type = "checkbox";
  var submit = knowledgeNode("button", t("ontology.search"), "btn btn-primary"); submit.type = "submit"; filters.append(submit);
  filters.addEventListener("submit", function (event) { event.preventDefault(); knowledge.offset = 0; loadKnowledge(); });
  root.append(filters);
  var message = knowledgeNode("p", null, "knowledge-status"); message.id = "knowledge-message"; message.setAttribute("role", "status"); message.setAttribute("aria-live", "polite"); root.append(message);
  knowledge.coverage = knowledgeNode("p", null, "knowledge-muted"); root.append(knowledge.coverage);
  var layout = knowledgeNode("div", null, "knowledge-layout");
  var side = knowledgeNode("section", null, "knowledge-list-panel"); side.setAttribute("aria-label", t("ontology.entities"));
  knowledge.list = knowledgeNode("div", null, "knowledge-list"); knowledge.pages = knowledgeNode("div", null, "knowledge-actions knowledge-pager");
  side.append(knowledge.list, knowledge.pages);
  knowledge.detail = knowledgeNode("section", null, "knowledge-detail"); knowledge.detail.setAttribute("aria-label", t("ontology.details"));
  knowledge.detail.append(knowledgeNode("p", t("ontology.select"), "knowledge-empty")); layout.append(side, knowledge.detail); root.append(layout);
}
async function loadKnowledge() {
  var generation = ++knowledge.generation;
  knowledgeMessage(t("common.loading"));
  try {
    var page = await ontologyRequest("/entities?" + knowledgeQuery(knowledge.offset));
    if (generation !== knowledge.generation) return;
    knowledge.list.replaceChildren(); knowledge.pages.replaceChildren(); knowledge.items = [];
    if (!page.items.length) knowledge.list.append(knowledgeNode("p", t("ontology.empty"), "knowledge-empty"));
    page.items.forEach(function (entity) {
      var button = knowledgeNode("button", null, "knowledge-entity"); button.type = "button";
      button.append(knowledgeNode("span", t("ontology.type." + entity.type), "knowledge-badge"), knowledgeNode("strong", entity.name));
      if (entity.start) button.append(knowledgeNode("small", knowledgePeriod(entity)));
      button.addEventListener("click", function () { knowledge.selected = entity.id; knowledge.detailOffset = 0; loadKnowledgeDetail(); });
      knowledge.list.append(button); knowledge.items.push({ id: entity.id, button: button });
    });
    knowledgeMarkSelected();
    knowledgePager(knowledge.pages, page, function (offset) { knowledge.offset = offset; loadKnowledge(); });
    var coverage = page.coverage || {};
    knowledge.coverage.textContent = t("ontology.coverage", { wiki: t("ontology.status." + (coverage.wiki || "unavailable")),
      memory: t("ontology.status." + (coverage.memory || "unavailable")) }) + " " + t("ontology.periodNote")
      + (Number(coverage.coarseEntriesOmitted) ? " " + t("ontology.coarseOmitted", { count: coverage.coarseEntriesOmitted }) : "");
    knowledgeMessage(t("ontology.count", { count: page.total }));
    if (knowledge.selected) await loadKnowledgeDetail();
  } catch (error) {
    if (generation !== knowledge.generation) return;
    knowledge.list.replaceChildren(); knowledge.items = []; knowledge.detail.replaceChildren(); knowledge.coverage.textContent = "";
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
  root.replaceChildren();
  root.append(knowledgeNode("span", t("ontology.type." + entity.type), "knowledge-badge"), knowledgeNode("h2", entity.name));
  if (entity.description && entity.description !== entity.name) root.append(knowledgeNode("p", entity.description));
  if (entity.aliases.length) root.append(knowledgeNode("p", t("ontology.aliases") + ": " + entity.aliases.join(" · ")));
  root.append(knowledgeNode("p", entity.source === "manual" ? t("ontology.manual") : t("ontology.readOnly"), "knowledge-muted"));
  if (entity.start) root.append(knowledgeNode("p", knowledgePeriod(entity)), knowledgeNode("p", t("ontology.periodNote"), "knowledge-muted"));
  if (entity.type === "activity") root.append(knowledgeNode("p", t("ontology.classification." + detail.classification), "knowledge-badge"));
  if (entity.attributes && entity.attributes.goalSource === "missing") root.append(knowledgeNode("p", t("ontology.missingGoal"), "knowledge-muted"));
  var actions = knowledgeNode("div", null, "knowledge-actions");
  if (entity.source === "manual") {
    actions.append(knowledgeButton("ontology.edit", function () { knowledgeEntityForm(entity); }),
      knowledgeButton("ontology.merge", function () { knowledgeMergeForm(entity); }),
      knowledgeButton("ontology.delete", async function () {
        if (!window.confirm(t("ontology.confirmDelete", { name: entity.name }))) return;
        try { await ontologyRequest("/entities/" + encodeURIComponent(entity.id), "DELETE"); knowledge.selected = null;
          root.replaceChildren(); await loadKnowledge(); } catch (error) { knowledgeMessage(error.message, true); }
      }, "btn btn-outline knowledge-danger"));
  }
  if (["activity", "project", "pattern", "goal", "topic"].includes(entity.type)) {
    actions.append(knowledgeButton(entity.type === "activity" ? "ontology.assign" : "ontology.addRelation", function () { knowledgeRelationForm(entity); }));
  }
  root.append(actions);
  knowledgeEvidence(root, entity.evidence);
  root.append(knowledgeNode("h3", t("ontology.relations")));
  if (!detail.relations.items.length) root.append(knowledgeNode("p", t("ontology.noRelations"), "knowledge-muted"));
  detail.relations.items.forEach(function (item) {
    var a = item.assertion, row = knowledgeNode("article", null, "knowledge-relation");
    var link = knowledgeNode("button", item.other.name, "knowledge-link"); link.type = "button";
    link.addEventListener("click", function () { knowledge.selected = item.other.id; knowledge.detailOffset = 0; loadKnowledgeDetail(); });
    row.append(knowledgeNode("span", t("ontology.predicate." + a.predicate) + " · " + t("ontology.claim." + a.claimType), "knowledge-badge"), link);
    if (a.status === "candidate") row.append(knowledgeNode("p", t("ontology.candidate"), "knowledge-muted"));
    if (a.source === "alias-rule") row.append(knowledgeNode("p", t("ontology.aliasReason"), "knowledge-muted"));
    if (item.other.start) row.append(knowledgeNode("small", knowledgePeriod(item.other)));
    var buttons = knowledgeNode("div", null, "knowledge-actions");
    if (a.claimType !== "confirmed") buttons.append(knowledgeButton("ontology.confirm", function () { knowledgeDecision(a, "confirm"); }));
    buttons.append(knowledgeButton("ontology.reject", function () { knowledgeDecision(a, "reject"); })); row.append(buttons);
    knowledgeEvidence(row, a.evidence); root.append(row);
  });
  var pages = knowledgeNode("div", null, "knowledge-actions knowledge-pager"); knowledgePager(pages, detail.relations, function (offset) { knowledge.detailOffset = offset; loadKnowledgeDetail(); }); root.append(pages);
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
    await loadKnowledge(); knowledgeMessage(t("ontology.saved")); } catch (error) { knowledgeMessage(error.message, true); }
}

function knowledgeDialog(title, build) {
  var dialog = knowledgeNode("dialog", null, "knowledge-dialog");
  var titleNode = knowledgeNode("h2", t(title)); titleNode.id = "knowledge-dialog-title"; dialog.setAttribute("aria-labelledby", titleNode.id);
  var form = knowledgeNode("form"); var error = knowledgeNode("p", null, "knowledge-error"); error.setAttribute("role", "alert");
  dialog.append(titleNode, form, error); document.body.append(dialog);
  dialog.addEventListener("close", function () { dialog.remove(); });
  build(form, dialog, error); dialog.showModal();
}
function knowledgeSubmit(form, dialog, error, action) {
  var buttons = knowledgeNode("div", null, "knowledge-actions");
  var submit = knowledgeNode("button", t("ontology.save"), "btn btn-primary"); submit.type = "submit";
  buttons.append(submit, knowledgeButton("ontology.cancel", function () { dialog.close(); })); form.append(buttons);
  form.addEventListener("submit", async function (event) {
    event.preventDefault(); if (submit.disabled) return; submit.disabled = true; error.textContent = "";
    try { await action(); dialog.close(); await loadKnowledge(); knowledgeMessage(t("ontology.saved")); }
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
function knowledgePicker(form, error, type, excluded) {
  var query = knowledgeField(form, "ontology.targetSearch", knowledgeNode("input")); query.type = "search"; query.maxLength = 300;
  var select = knowledgeField(form, "ontology.target", knowledgeNode("select")); select.required = true; select.size = 5;
  var offset = 0, generation = 0, currentType = type;
  var buttons = knowledgeNode("div", null, "knowledge-actions"); form.append(buttons);
  async function load(append) {
    var current = ++generation;
    try {
      var page = await ontologyRequest("/entities?" + new URLSearchParams({ type: currentType, q: query.value, limit: "30", offset: String(offset) }));
      if (current !== generation) return;
      if (!append) select.replaceChildren();
      page.items.filter(function (e) { return e.id !== excluded; }).forEach(function (e) {
        var option = knowledgeNode("option", e.name + (e.source === "manual" ? "" : " · " + t("ontology.source"))); option.value = e.id; select.append(option);
      });
      buttons.replaceChildren(knowledgeButton("ontology.search", function () { offset = 0; load(false); }));
      if (page.hasMore) buttons.append(knowledgeButton("ontology.next", function () { offset += page.limit; load(true); }));
    } catch (failure) { if (current === generation) error.textContent = failure.message; }
  }
  query.addEventListener("keydown", function (event) { if (event.key === "Enter") { event.preventDefault(); offset = 0; load(false); } });
  load(false);
  return { value: function () { return select.value; }, type: function (value) { currentType = value; offset = 0; load(false); } };
}
function knowledgeMergeForm(entity) {
  knowledgeDialog("ontology.merge", function (form, dialog, error) {
    form.append(knowledgeNode("p", t("ontology.mergeHelp")));
    var picker = knowledgePicker(form, error, entity.type, entity.id);
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
