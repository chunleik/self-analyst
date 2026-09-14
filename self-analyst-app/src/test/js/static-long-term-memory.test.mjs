import fs from "node:fs";
import assert from "node:assert/strict";

const read = (path) => fs.readFileSync(new URL(path, import.meta.url), "utf8");

const api = read("../../main/resources/desktop-ui/api.js");
[
  "listMemory",
  "createMemory",
  "updateMemory",
  "deleteMemory",
  "setSessionMemoryPolicy",
  "createSessionMemory",
].forEach((name) => assert.match(api, new RegExp(name + "\\s*:"), `api.js missing ${name}`));

const chat = read("../../main/resources/desktop-ui/chat.js");
assert.doesNotMatch(chat, /renderChatMemoryPanel|loadMemoryForChat|refreshMemoryPanelSoon|api\.listMemory/, "chat must not load or render the removed panel");
const html = read("../../main/resources/desktop-ui/index.html");
assert.doesNotMatch(html, /chat-memory-section|chat-memory-content/, "removed panel must not leave a layout placeholder");

const config = read("../../main/resources/desktop-ui/config.js");
assert.doesNotMatch(config, /renderMemoryManager|memory-manager-/, "config modal must not contain memory management");

const i18n = read("../../main/resources/desktop-ui/locales/zh.json") + read("../../main/resources/desktop-ui/locales/en.json");
[
  "memory.chatTitle",
  "memory.loadFailed",
  "memory.policy.smart",
  "memory.policy.confirmAll",
  "memory.policy.off",
  "memory.edit",
  "memory.editApprove",
  "memory.editContent",
  "memory.editEvidence",
  "memory.pendingCount",
].forEach((key) => assert.match(i18n, new RegExp(`"${key}"`), `i18n missing ${key}`));

const state = read("../../main/resources/desktop-ui/state.js");
assert.match(state, /memoryLoadError/, "state.js should persist memory load errors");

console.log("static-long-term-memory checks passed");
