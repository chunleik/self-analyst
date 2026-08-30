import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

const source = fs.readFileSync(
  new URL("../../main/resources/desktop-ui/ui.js", import.meta.url),
  "utf8",
);

function element() {
  return { className: "", title: "", textContent: "", classList: { toggle() {} } };
}

const context = {
  state: {
    status: {
      collectors: { window: "running", contextTitle: "degraded" },
      contentPersistence: {
        ready: false,
        status: "migration_failed",
        error: "simulated safe migration error",
      },
      llm: { configured: false },
      aw: {},
    },
    dom: {
      backendDot: element(),
      backendText: element(),
      collectorsDot: element(),
      collectorsText: element(),
      llmDot: element(),
      llmText: element(),
      tabs: [],
    },
  },
  t(key) { return key; },
  console,
};
vm.createContext(context);
vm.runInContext(source, context);
context.updateAudioToggle = () => {};
context.updateAudioAvailability = () => {};

context.updateStatusBar();

assert.match(context.state.dom.collectorsDot.className, /orange/);
assert.match(context.state.dom.collectorsDot.title, /status\.contextTitleMigrationFailed/);
assert.match(context.state.dom.collectorsDot.title, /simulated safe migration error/);
assert.equal(
  context.state.dom.collectorsText.title,
  context.state.dom.collectorsDot.title,
);
