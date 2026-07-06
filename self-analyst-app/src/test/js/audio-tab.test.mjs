import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

const indexHtml = fs.readFileSync(
  new URL("../../main/resources/desktop-ui/index.html", import.meta.url),
  "utf8",
);
const apiJs = fs.readFileSync(
  new URL("../../main/resources/desktop-ui/api.js", import.meta.url),
  "utf8",
);
const uiJs = fs.readFileSync(
  new URL("../../main/resources/desktop-ui/ui.js", import.meta.url),
  "utf8",
);
const initJs = fs.readFileSync(
  new URL("../../main/resources/desktop-ui/init.js", import.meta.url),
  "utf8",
);
const i18nJs = fs.readFileSync(
  new URL("../../main/resources/desktop-ui/i18n.js", import.meta.url),
  "utf8",
);
const desktopServer = fs.readFileSync(
  new URL("../../main/java/com/selfanalyst/desktop/DesktopServer.java", import.meta.url),
  "utf8",
);

assert.match(indexHtml, /data-tab="audio"[^>]*data-i18n="tab\.audio"/);
assert.match(indexHtml, /id="tab-audio"/);
assert.match(indexHtml, /id="audio-transcript-list"/);

assert.match(apiJs, /getAudioEvents:\s*function\s*\(\s*limit\s*\)/);
assert.match(apiJs, /\/desktop\/audio\/events/);

assert.match(initJs, /tabAudio:\s*(?:document\.getElementById\("tab-audio"\)|\$\("#tab-audio"\))/);
assert.match(initJs, /audioTranscriptList:\s*(?:document\.getElementById\("audio-transcript-list"\)|\$\("#audio-transcript-list"\))/);

assert.match(uiJs, /function loadAudioEvents\(\)/);
assert.match(uiJs, /function renderAudioTab\(\)/);
assert.match(uiJs, /state\.tab === "audio"/);
assert.match(uiJs, /resp\.latestEventAt/);
assert.match(uiJs, /resp\.diagnostics/);
assert.match(uiJs, /state\.audioDiagnostics/);
assert.match(uiJs, /state\.audioEventsLatestAt/);
assert.match(uiJs, /ev\.source/);
assert.match(uiJs, /audio\.latest/);
assert.match(uiJs, /audio\.listening/);
assert.match(uiJs, /audio\.source\.mic/);
assert.match(uiJs, /audio\.source\.system/);
assert.match(uiJs, /audio\.diagnostics\.silent/);
assert.match(uiJs, /audio\.diagnostics\.emptyTranscript/);

assert.match(i18nJs, /"tab\.audio":\s*\{\s*zh:\s*"录音"/);
assert.match(i18nJs, /"audio\.empty":\s*\{\s*zh:\s*"暂无录音文本"/);
assert.match(i18nJs, /"audio\.latest":\s*\{\s*zh:\s*"最后录音/);
assert.match(i18nJs, /"audio\.listening":\s*\{\s*zh:\s*"监听中/);
assert.match(i18nJs, /"audio\.diagnostics\.silent":\s*\{\s*zh:\s*"最近采样正常/);
assert.match(i18nJs, /"audio\.diagnostics\.emptyTranscript":\s*\{\s*zh:\s*"检测到声音/);

assert.match(desktopServer, /app\.get\("\/desktop\/audio\/events",\s*audioEventsCtrl::getEvents\)/);

const diagnosisContext = {
  state: {
    audioEventsStatus: "running",
    audioEventsLatestAt: "2026-07-05T14:32:05.020460300Z",
    audioDiagnostics: {
      lastErrorAt: null,
      lastError: null,
      lastEmptyTranscriptAt: "2026-07-05T14:33:00.392110200Z",
      lastSilentAt: "2026-07-05T14:39:58.089295400Z",
    },
  },
  t: (key, params) => `${key}:${params && (params.time || params.msg || "")}`,
  formatRelativeTime: (iso) => iso,
  document: { getElementById: () => null },
};

vm.runInNewContext(
  `${uiJs}\nthis.__diagnosis = audioDiagnosisMessage();`,
  diagnosisContext,
);
assert.equal(
  diagnosisContext.__diagnosis,
  "audio.diagnostics.silent:2026-07-05T14:39:58.089295400Z",
);
