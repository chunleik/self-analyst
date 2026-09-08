import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

const read = name => fs.readFileSync(new URL("../../main/resources/desktop-ui/" + name, import.meta.url), "utf8");
const eventSource = read("events.js");
const start = eventSource.indexOf('  state.dom.configGrid.addEventListener("click"');
const end = eventSource.indexOf('  state.dom.configGrid.addEventListener("input"', start);
assert.ok(start >= 0 && end > start);

for (const service of ["llm", "embedding"]) {
  test(service + " 连接测试显示旧名称错误并保留未保存配置", async () => {
    const editor = { value: "aw.mode='embedded'" };
    const error = "aw.mode 已移除，请手动改为 events.mode";
    let click;
    let sent;
    const state = {
      configDirty: true, configRawBaseline: "old baseline", configRawText: editor.value,
      dom: { configGrid: { addEventListener: (name, handler) => { click = handler; }, contains: () => true } },
    };
    const context = vm.createContext({
      state, window: { location: { origin: "http://localhost:5700" } },
      fetch: async (url, options) => {
        sent = { url, body: JSON.parse(options.body) };
        return { ok: false, status: 400, json: async () => ({ error }) };
      },
      t: (key, values) => values && values.msg ? values.msg : key,
      currentEditorText: () => editor.value,
      readLlmConfigFromEditor: () => ({}), readEmbeddingConfigFromEditor: () => ({}),
      updateConfigActionBar: () => {},
    });
    vm.runInContext(read("api.js"), context);
    vm.runInContext(eventSource.slice(start, end), context);
    const button = { id: "test-" + service + "-btn", nodeType: 1, disabled: false };
    button.closest = () => button;
    click({ target: button });
    await new Promise(setImmediate);
    assert.equal(sent.body.text, editor.value);
    assert.match(sent.url, new RegExp("/test-" + service + "$"));
    assert.equal(state.configSaveResult.type, "error");
    assert.equal(state.configSaveResult.msg, error);
    assert.equal(state.configDirty, true);
    assert.equal(state.configRawBaseline, "old baseline");
    assert.equal(editor.value, "aw.mode='embedded'");
    assert.equal(button.disabled, false);
  });
}
