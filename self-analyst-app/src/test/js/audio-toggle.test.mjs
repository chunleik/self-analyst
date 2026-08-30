import assert from "node:assert/strict";
import fs from "node:fs";

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
const eventsJs = fs.readFileSync(
  new URL("../../main/resources/desktop-ui/events.js", import.meta.url),
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

assert.match(
  indexHtml,
  /<button id="audio-toggle-btn"[^>]*class="[^"]*\bbtn-icon\b/,
  "top status bar should expose an icon button for recording capture",
);
assert.match(indexHtml, /data-i18n-title="audio\.(start|stop)"/);

assert.match(
  apiJs,
  /setAudioCapture:\s*function\s*\(\s*enabled\s*\)/,
  "API client should expose a runtime audio capture toggle",
);
assert.match(apiJs, /\/desktop\/audio/);
assert.match(apiJs, /JSON\.stringify\(\{\s*enabled:\s*enabled\s*\}\)/);
assert.match(desktopServer, /app\.post\("\/desktop\/audio",\s*audioCtrl::setAudioCapture\)/);

assert.match(
  initJs,
  /audioToggleBtn:\s*(?:document\.getElementById\("audio-toggle-btn"\)|\$\("#audio-toggle-btn"\))/,
);
assert.match(eventsJs, /audioToggleBtn\.addEventListener\("click"/);
assert.match(eventsJs, /api\.setAudioCapture\(!audioCaptureRunning\(\)\)/);

assert.match(uiJs, /function audioCaptureRunning\(\)/);
assert.match(uiJs, /collectors\.audio === "running"/);
assert.match(uiJs, /collectors\.contextTitle === "running"/);
assert.match(uiJs, /audio-toggle-btn/);
assert.match(uiJs, /audio\.stop/);
assert.match(uiJs, /audio\.start/);

assert.match(i18nJs, /"audio\.start":\s*\{\s*zh:\s*"开始录音"/);
assert.match(i18nJs, /"audio\.stop":\s*\{\s*zh:\s*"暂停录音"/);
