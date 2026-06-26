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

function formatDate(d) {
  if (!d) return "";
  var date = new Date(d);
  if (isNaN(date.getTime())) return String(d);
  return date.toLocaleDateString("zh-CN", {
    month: "short",
    day: "numeric",
    weekday: "short",
  });
}

function formatDateTime(d) {
  if (!d) return "";
  var date = new Date(d);
  if (isNaN(date.getTime())) return String(d);
  return date.toLocaleString("zh-CN");
}

function priorityLabel(p) {
  var map = { high: "高", medium: "中", low: "低" };
  return map[p] || p || "中";
}

function priorityBadge(p) {
  var cls = "badge badge-" + (p === "high" ? "high" : p === "low" ? "low" : "medium");
  return '<span class="' + cls + '">' + escHtml(priorityLabel(p)) + "</span>";
}

function statusBadge(s) {
  var cls = "status-" + (s === "running" || s === "online" ? "running" : s === "degraded" ? "degraded" : "error");
  var label =
    s === "running" || s === "online" ? "运行中" : s === "degraded" ? "降级" : "已禁用";
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
    if (min < 1) return "刚刚";
    if (min < 60) return min + "分钟前";
    var hrs = Math.floor(min / 60);
    if (hrs < 24) return hrs + "小时前";
    return Math.floor(hrs / 24) + "天前";
  } catch (e) { return ""; }
}
