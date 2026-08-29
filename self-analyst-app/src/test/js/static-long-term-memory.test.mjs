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
assert.match(chat, /renderChatMemoryPanel/, "chat.js should render memory panel");
assert.match(chat, /memoryLoadError/, "chat.js should track memory load failure");
assert.match(chat, /memory\.loadFailed/, "chat.js should render memory load failure copy");
assert.match(chat, /session-memory-policy/, "chat.js should bind session memory policy");
assert.match(chat, /memory-pending-count/, "chat.js should show pending memory count");
assert.match(chat, /memory-approve/, "chat.js should expose pending approval");
assert.match(chat, /memory-edit-approve/, "chat.js should expose edit-before-approval");
assert.match(chat, /memory-reject/, "chat.js should expose pending rejection");
assert.match(chat, /memory-panel-disable/, "chat.js should let users disable active memories");

const config = read("../../main/resources/desktop-ui/config.js");
assert.doesNotMatch(config, /renderMemoryManager|memory-manager-/, "config modal must not contain memory management");

const i18n = read("../../main/resources/desktop-ui/i18n.js");
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
