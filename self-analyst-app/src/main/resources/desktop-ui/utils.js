/* ============================================================
   SelfAnalyst Desktop - Utility Functions
   ============================================================ */
"use strict";

var $ = function (sel) { return document.querySelector(sel); };
var $$ = function (sel) { return document.querySelectorAll(sel); };

function escHtml(str) {
  if (!str) return "";
  var d = document.createElement("div");
  d.appendChild(document.createTextNode(str));
  return d.innerHTML;
}

function uiLocale() {
  return (typeof state !== "undefined" && state && state.dateLocale) ? state.dateLocale : "en-US";
}

function formatDate(d) {
  if (!d) return "";
  var date = new Date(d);
  if (isNaN(date.getTime())) return String(d);
  return date.toLocaleDateString(uiLocale(), {
    month: "short",
    day: "numeric",
    weekday: "short",
  });
}

function formatDateTime(d) {
  if (!d) return "";
  var date = new Date(d);
  if (isNaN(date.getTime())) return String(d);
  return date.toLocaleString(uiLocale());
}

function priorityLabel(p) {
  var map = { high: t("priority.high"), medium: t("priority.medium"), low: t("priority.low") };
  return map[p] || p || t("priority.medium");
}

function priorityBadge(p) {
  var cls = "badge badge-" + (p === "high" ? "high" : p === "low" ? "low" : "medium");
  return '<span class="' + cls + '">' + escHtml(priorityLabel(p)) + "</span>";
}

function statusBadge(s) {
  var cls = s === "running" || s === "online" ? "running" : s === "degraded" ? "degraded" : "error";
  var label =
    s === "running" || s === "online" ? t("status.running") : s === "degraded" ? t("status.degraded") : t("status.disabled");
  return '<span class="tag tag-' + cls + '">' + escHtml(label) + "</span>";
}

function createId(prefix) {
  return (prefix || "id") + "_" + Date.now().toString(36) + "_" + Math.random().toString(36).slice(2, 8);
}

function formatRelativeTime(iso) {
  if (!iso) return "";
  try {
    var diff = Date.now() - new Date(iso).getTime();
    var min = Math.floor(diff / 60000);
    if (min < 1) return t("time.justNow");
    if (min < 60) return t("time.minutesAgo", { n: min });
    var hrs = Math.floor(min / 60);
    if (hrs < 24) return t("time.hoursAgo", { n: hrs });
    return t("time.daysAgo", { n: Math.floor(hrs / 24) });
  } catch (e) { return ""; }
}
