import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

const source = fs.readFileSync(
  new URL("../../main/resources/desktop-ui/ui.js", import.meta.url),
  "utf8",
);
const initSource = fs.readFileSync(
  new URL("../../main/resources/desktop-ui/init.js", import.meta.url),
  "utf8",
);

assert.doesNotMatch(source, /audioRefreshTimer|loadAudioEvents|updateAudio/);
assert.doesNotMatch(initSource, /updateAudio|loadAudioEvents/);

function element() {
  return { className: "", title: "", textContent: "", classList: { toggle() {} } };
}

const context = {
  state: {
    status: {
      collectors: { window: "running", contextTitle: "degraded", file: "running" },
      contentPersistence: {
        ready: false,
        status: "migration_failed",
        error: "simulated safe migration error",
      },
      llm: { configured: false },
      raw: {
        status: "blocked",
        diskWarning: true,
        projectionLagSeconds: 42,
        data: "SELF_ANALYST_DOM_SECRET",
      },
      aw: {},
    },
    dom: {
      backendDot: element(),
      backendText: element(),
      collectorsDot: element(),
      collectorsText: element(),
      rawDot: element(),
      rawText: element(),
      fileStatusBtn: element(),
      fileDot: element(),
      fileText: element(),
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
context.updateStatusBar();

assert.match(context.state.dom.collectorsDot.className, /orange/);
assert.match(context.state.dom.collectorsDot.title, /status\.contextTitleMigrationFailed/);
assert.match(context.state.dom.collectorsDot.title, /simulated safe migration error/);
assert.equal(
  context.state.dom.collectorsText.title,
  context.state.dom.collectorsDot.title,
);
assert.match(context.state.dom.fileDot.className, /green/);
assert.match(context.state.dom.fileStatusBtn.title, /status\.running/);
assert.match(context.state.dom.rawDot.className, /red/);
assert.match(context.state.dom.rawDot.title, /status\.blocked/);
assert.match(context.state.dom.rawDot.title, /status\.diskWarning/);
assert.match(context.state.dom.rawDot.title, /42s/);
assert.doesNotMatch(context.state.dom.rawDot.title, /SELF_ANALYST_DOM_SECRET/);
assert.doesNotMatch(context.state.dom.rawText.title, /SELF_ANALYST_DOM_SECRET/);
