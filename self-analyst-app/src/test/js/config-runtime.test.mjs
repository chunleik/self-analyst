import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

const source = fs.readFileSync(new URL("../../main/resources/desktop-ui/config.js", import.meta.url), "utf8");
function harness(responses = []) {
  const timers = new Map();
  let id = 0;
  const elements = new Map([
    ["config-runtime-status", { innerHTML: "" }],
    ["config-raw-editor", { value: "[llm]\nmodel='new'" }],
  ]);
  const state = { configOpen: true, configDirty: true, configRawBaseline: "old",
    configRawText: "new", configSaving: false, configLoadError: false,
    dom: { configGrid: { innerHTML: "" } } };
  const context = vm.createContext({
    state,
    api: { getEffectiveConfig: () => Promise.resolve(responses.shift()) },
    document: { getElementById: key => elements.get(key) || null, querySelector: key => elements.get(key.slice(1)) || null },
    t: key => key,
    escHtml: value => String(value ?? "").replaceAll("<", "&lt;").replaceAll(">", "&gt;"),
    setTimeout: fn => { timers.set(++id, fn); return id; },
    clearTimeout: key => timers.delete(key),
  });
  vm.runInContext(source, context);
  return { context, state, timers, elements };
}

test("runtime rendering shows saved and running values without replacing the editor", () => {
  const h = harness();
  h.state.configRuntime = {
    application: { llm: { status: "draining", activeWorkCount: 1 } },
    configured: { "llm.model": { value: "<new>", source: "toml" }, "llm.api-key": { value: "****", source: "environment" } },
    running: { "llm.model": "old", "llm.api-key": "****" },
  };
  const html = h.context.renderConfigRuntime();
  assert.match(html, /config.runtime.draining/);
  assert.match(html, /&lt;new&gt;/);
  assert.match(html, /old/);
  assert.match(html, /config.source.environment/);
  assert.equal(h.elements.get("config-raw-editor").value, "[llm]\nmodel='new'");
});

test("draining refresh stops after the final old task finishes", async () => {
  const h = harness([
    { application: { llm: { status: "draining", activeWorkCount: 1 } } },
    { application: { llm: { status: "applied", activeWorkCount: 0 } } },
  ]);
  await h.context.refreshConfigRuntime();
  assert.equal(h.timers.size, 1);
  [...h.timers.values()][0]();
  await new Promise(setImmediate);
  assert.equal(h.timers.size, 0);
  assert.match(h.elements.get("config-runtime-status").innerHTML, /config.runtime.applied/);
});

test("closing invalidates an outstanding runtime request", async () => {
  const h = harness();
  let finish;
  h.context.api.getEffectiveConfig = () => new Promise(resolve => { finish = resolve; });
  const request = h.context.refreshConfigRuntime();
  h.state.configOpen = false;
  h.context.stopConfigRuntimeRefresh();
  finish({ application: { llm: { status: "draining", activeWorkCount: 1 } } });
  await request;
  assert.equal(h.state.configRuntime, undefined);
  assert.equal(h.timers.size, 0);
});

test("runtime lookup errors preserve edits and explain that status is unavailable", async () => {
  const h = harness();
  h.context.api.getEffectiveConfig = () => Promise.reject(new Error("offline"));
  await h.context.refreshConfigRuntime();
  assert.equal(h.state.configDirty, true);
  assert.equal(h.state.configRawBaseline, "old");
  assert.match(h.elements.get("config-runtime-status").innerHTML, /runtimeLoadFailed/);
});

test("saving failure preserves dirty text and the last successful baseline", async () => {
  const h = harness();
  const languageSelect = { disabled: false };
  h.elements.set("config-language", languageSelect);
  h.context.api.saveRawConfig = () => Promise.reject(new Error("write failed"));
  h.context.saveAllConfig();
  assert.equal(languageSelect.disabled, true);
  await new Promise(setImmediate);
  assert.equal(languageSelect.disabled, false);
  assert.equal(h.state.configDirty, true);
  assert.equal(h.state.configRawBaseline, "old");
  assert.equal(h.state.configSaving, false);
  assert.equal(h.state.configSaveResult.type, "error");
});

test("unavailable and restart states are independent and do not schedule polling", async () => {
  const h = harness([{ application: {
    llm: { status: "unavailable", activeWorkCount: 0 },
    embedding: { status: "restart_required", changedKeys: ["embedding.api-key"] },
  } }]);
  await h.context.refreshConfigRuntime();
  const html = h.elements.get("config-runtime-status").innerHTML;
  assert.match(html, /config.runtime.unavailable/);
  assert.match(html, /config.runtime.restart_required/);
  assert.equal(h.timers.size, 0);
});

test("unchanged polling does not rebuild an expanded source table", async () => {
  const data = { application: { llm: { status: "draining", activeWorkCount: 1 } } };
  const h = harness([data, data]);
  await h.context.refreshConfigRuntime();
  h.elements.get("config-runtime-status").innerHTML = "expanded by user";
  await h.context.refreshConfigRuntime();
  assert.equal(h.elements.get("config-runtime-status").innerHTML, "expanded by user");
});
