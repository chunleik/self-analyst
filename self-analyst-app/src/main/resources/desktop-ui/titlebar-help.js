/* Shared desktop/Web help menu. Native actions remain guarded by the desktop host. */
"use strict";
(function (root) {
  function guideUrl(action, language) {
    var base = "https://github.com/chunleik/self-analyst/";
    if (action === "guide") return base + "blob/main/README" + (language === "zh" ? ".zh-CN" : "") + ".md";
    if (action === "feedback") return base + "issues";
    return null;
  }
  function mount(doc, host) {
    var trigger = doc.getElementById("help-trigger");
    if (!trigger) return;
    var native = !!(host.__SELF_ANALYST_DESKTOP__ && host.__TAURI__ && host.__TAURI__.core && typeof host.__TAURI__.core.invoke === "function");
    var menu = doc.getElementById("help-menu");
    var items = Array.from(menu.querySelectorAll("[role=menuitem]"));
    var dialog = doc.getElementById("help-dialog");
    var maximize = doc.getElementById("window-maximize");
    var drag = doc.getElementById("titlebar-drag");
    var maxState = false;
    var pending = false;
    var updatePending = null;
    var dialogGeneration = 0;
    var linkAction = null;
    var retry = doc.getElementById("help-update-retry");
    var releasePage = "https://github.com/chunleik/self-analyst/releases/latest";
    var text = function (key) { return typeof t === "function" ? t(key) : key; };
    function invoke(command, args) { return Promise.resolve().then(function () { return host.__TAURI__.core.invoke(command, args); }); }
    if (native) doc.getElementById("titlebar-help-slot").appendChild(trigger);
    function close(focus) {
      menu.hidden = true;
      trigger.setAttribute("aria-expanded", "false");
      if (focus) trigger.focus();
    }
    function position() {
      var rect = trigger.getBoundingClientRect();
      menu.style.left = Math.max(8, Math.min(rect.left, host.innerWidth - menu.offsetWidth - 8)) + "px";
      menu.style.top = Math.max(0, Math.min(rect.bottom + 4, host.innerHeight - menu.offsetHeight - 8)) + "px";
    }
    function open(index) {
      if (pending) return;
      menu.hidden = false;
      trigger.setAttribute("aria-expanded", "true");
      position();
      items[index || 0].focus();
    }
    function message(title, body, link) {
      dialogGeneration++;
      linkAction = null;
      retry.hidden = true;
      close(false);
      doc.getElementById("help-dialog-title").textContent = title;
      doc.getElementById("help-dialog-body").textContent = body;
      var target = doc.getElementById("help-dialog-link");
      target.hidden = !link;
      target.textContent = link || "";
      if (link) target.href = link;
      else target.removeAttribute("href");
      if (!dialog.open) dialog.showModal();
      return dialogGeneration;
    }
    function failed(link) { message(text("help.label"), text("help.failed"), link); }
    function updateLink(label, action, tag) {
      doc.getElementById("help-dialog-link").textContent = text(label);
      linkAction = { action: action };
      if (tag) linkAction.tag = tag;
    }
    doc.getElementById("help-dialog-link").addEventListener("click", function (event) {
      if (!native || !linkAction) return;
      event.preventDefault();
      var url = doc.getElementById("help-dialog-link").href;
      invoke("help_action", linkAction).catch(function () { failed(url); });
    });
    function checkUpdate() {
      if (updatePending) return updatePending;
      if (!native) {
        message(text("update.check"), text("update.web"), releasePage);
        updateLink("update.releases", "releases");
        return Promise.resolve();
      }
      var generation = message(text("update.check"), text("update.checking"));
      updatePending = invoke("help_action", { action: "check_update" }).then(function (result) {
        if (!dialog.open || generation !== dialogGeneration) return;
        var body = text(result.available ? "update.available" : "update.current") + "\n\n" +
          text("update.installed") + ": " + result.currentVersion + "\n" + text("update.latest") + ": " + result.latestVersion;
        var url = result.available ? "https://github.com/chunleik/self-analyst/releases/tag/" + encodeURIComponent(result.tag) : null;
        message(text("update.check"), body, url);
        if (result.available) updateLink("update.download", "download", result.tag);
      }).catch(function (error) {
        if (!dialog.open || generation !== dialogGeneration) return;
        var codes = ["update.timeout", "update.rateLimited", "update.noRelease", "update.invalid", "update.busy"];
        message(text("update.check"), text(codes.includes(error) ? error : "update.failed"), releasePage);
        updateLink("update.releases", "releases");
        retry.hidden = false;
      }).finally(function () { updatePending = null; });
      return updatePending;
    }
    retry.addEventListener("click", checkUpdate);
    function execute(action) {
      var language = typeof state !== "undefined" ? state.lang : doc.documentElement.lang;
      var url = guideUrl(action, language);
      close(true);
      if (action === "check_update") return checkUpdate();
      if (!url && action !== "about") return Promise.resolve();
      if (native) {
        pending = true;
        return invoke("help_action", { action: action }).catch(function () { failed(url); }).finally(function () {
          pending = false;
          if (!dialog.open) trigger.focus();
        });
      }
      if (action === "about") {
        message(text("help.about"), "SelfAnalyst\n" + text("help.webVersion") + "\n" + host.location.origin);
      } else {
        // A real anchor preserves browser popup policy and suppresses the Referer header.
        var anchor = doc.createElement("a");
        anchor.href = url;
        anchor.target = "_blank";
        anchor.rel = "noopener noreferrer";
        try { anchor.click(); } catch (_) { failed(url); }
      }
      return Promise.resolve();
    }
    trigger.addEventListener("click", function () { if (menu.hidden) open(); else close(true); });
    trigger.addEventListener("keydown", function (event) {
      if (["ArrowDown", "ArrowUp"].includes(event.key)) { event.preventDefault(); open(event.key === "ArrowUp" ? items.length - 1 : 0); }
    });
    items.forEach(function (item) { item.addEventListener("click", function () { execute(item.getAttribute("data-help-action")); }); });
    menu.addEventListener("keydown", function (event) {
      var index = items.indexOf(doc.activeElement);
      if (event.key === "Escape") { event.preventDefault(); event.stopPropagation(); close(true); }
      else if (["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) {
        event.preventDefault();
        var next = event.key === "Home" ? 0 : event.key === "End" ? items.length - 1 : (index + (event.key === "ArrowDown" ? 1 : -1) + items.length) % items.length;
        items[next].focus();
      } else if (event.key === "Tab") {
        // Let the browser advance from the trigger in its normal DOM order.
        close(true);
      }
    });
    doc.addEventListener("pointerdown", function (event) {
      if (!menu.contains(event.target) && !trigger.contains(event.target)) close(false);
    });
    doc.addEventListener("focusin", function (event) {
      if (!menu.hidden && !menu.contains(event.target) && event.target !== trigger) close(false);
    });
    host.addEventListener("blur", function () { close(false); });
    dialog.addEventListener("close", function () { dialogGeneration++; trigger.focus(); });
    dialog.addEventListener("click", function (event) { if (event.target === dialog) {
      var r = dialog.getBoundingClientRect();
      if (event.clientX < r.left || event.clientX > r.right || event.clientY < r.top || event.clientY > r.bottom) dialog.close();
    } });
    function localize() {
      ["minimize", "maximize", "close"].forEach(function (action) {
        var key = action === "maximize" && maxState ? "restore" : action;
        var button = doc.getElementById("window-" + action);
        button.setAttribute("aria-label", text("window." + key));
        button.title = text("window." + key);
      });
      maximize.classList.toggle("is-maximized", maxState);
    }
    function rect(element) {
      var r = element.getBoundingClientRect();
      return { x: r.x, y: r.y, width: r.width, height: r.height };
    }
    function layout() {
      if (!native) return Promise.resolve();
      return invoke("titlebar_layout", { layout: {
        drag: rect(drag), maximize: rect(maximize), minimize: rect(doc.getElementById("window-minimize")),
        close: rect(doc.getElementById("window-close")), scale: host.devicePixelRatio || 1
      } });
    }
    var refreshing = null;
    function refresh() {
      if (!native) return Promise.resolve();
      if (refreshing) return refreshing;
      refreshing = invoke("titlebar_action", { action: "state" }).then(function (value) {
        maxState = !!value;
        localize();
        return layout();
      }).catch(function () { failed(); }).finally(function () { refreshing = null; });
      return refreshing;
    }
    if (native) {
      ["minimize", "maximize", "close"].forEach(function (action) {
        doc.getElementById("window-" + action).addEventListener("click", function () {
          invoke("titlebar_action", { action: action }).then(refresh).catch(function () { failed(); });
        });
      });
      // Also supports keyboard/browser-generated clicks; native hit testing handles mouse dragging.
      drag.addEventListener("mousedown", function (event) {
        if (event.button === 0) invoke("titlebar_action", { action: event.detail === 2 ? "maximize" : "drag" }).then(refresh).catch(function () { failed(); });
      });
      host.addEventListener("focus", refresh);
      if (typeof host.ResizeObserver === "function") {
        var observer = new host.ResizeObserver(function () { refresh(); });
        observer.observe(drag);
      }
      refresh();
    }
    host.addEventListener("resize", function () { if (!menu.hidden) position(); if (native) refresh(); });
    localize();
    return { close: close, open: open, execute: execute, refresh: refresh, localize: localize };
  }
  root.SelfAnalystHelp = { mount: mount, guideUrl: guideUrl };
  if (typeof document !== "undefined") root.SelfAnalystTitlebar = mount(document, root);
})(typeof window !== "undefined" ? window : globalThis);
