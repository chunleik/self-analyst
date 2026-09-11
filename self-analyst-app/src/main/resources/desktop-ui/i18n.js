"use strict";

var MESSAGES = { en: {
  "common.loading": "Loading...",
  "error.notReady": "Local service not ready. Retry connection.",
  "error.retry": "Retry connection"
} };

function t(key, params) {
  var lang = typeof state !== "undefined" && state.lang ? state.lang : "en";
  var selected = MESSAGES[lang] || {};
  var english = MESSAGES.en || {};
  var s = Object.prototype.hasOwnProperty.call(selected, key) ? selected[key] : english[key];
  if (typeof s !== "string") s = key;
  return s.replace(/\{(\w+)\}/g, function (match, name) {
    return params && params[name] != null ? String(params[name]) : match;
  });
}

function loadLocale(code, resource) {
  resource = resource || code;
  if (!/^[a-z]{2,8}(?:-[a-z0-9]{2,8})*$/.test(code) ||
      !/^[a-z]{2,8}(?:-[a-z0-9]{2,8})*$/.test(resource)) return Promise.reject(new Error("Invalid language"));
  var controller = new AbortController();
  var timeout = setTimeout(function () { controller.abort(); }, 5000);
  return fetch("locales/" + resource + ".json", { signal: controller.signal }).then(function (response) {
    if (!response.ok) throw new Error("Language resource unavailable");
    return response.json();
  }).then(function (catalog) {
    if (!catalog || Array.isArray(catalog) || typeof catalog !== "object" ||
        Object.keys(catalog).some(function (key) { return typeof catalog[key] !== "string"; })) {
      throw new Error("Invalid language resource");
    }
    MESSAGES[code] = catalog;
  }).finally(function () { clearTimeout(timeout); });
}

function loadI18n(language, resource) {
  return loadLocale("en").then(function () {
    if (language !== "en") return loadLocale(language, resource).catch(function () { /* English fallback */ });
  });
}

/**
 * Apply the catalog to static markup under root: [data-i18n] sets textContent,
 * [data-i18n-placeholder] sets placeholder, [data-i18n-title] sets title
 * (SPEC-I18N-UI-003a).
 */
function applyI18n(root) {
  root = root || document;
  var nodes = root.querySelectorAll("[data-i18n]");
  for (var i = 0; i < nodes.length; i++) {
    nodes[i].textContent = t(nodes[i].getAttribute("data-i18n"));
  }
  var ph = root.querySelectorAll("[data-i18n-placeholder]");
  for (var j = 0; j < ph.length; j++) {
    ph[j].setAttribute("placeholder", t(ph[j].getAttribute("data-i18n-placeholder")));
  }
  var titles = root.querySelectorAll("[data-i18n-title]");
  for (var k = 0; k < titles.length; k++) {
    titles[k].setAttribute("title", t(titles[k].getAttribute("data-i18n-title")));
  }
}
