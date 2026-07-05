import fs from "node:fs";
import assert from "node:assert/strict";

const read = (path) => fs.readFileSync(path, "utf8");

const api = read("self-analyst-app/src/main/resources/desktop-ui/api.js");
[
  "listMemory",
  "createMemory",
  "updateMemory",
  "deleteMemory",
  "setSessionMemoryPolicy",
  "createSessionMemory",
].forEach((name) => assert.match(api, new RegExp(name + "\\s*:"), `api.js missing ${name}`));

const chat = read("self-analyst-app/src/main/resources/desktop-ui/chat.js");
assert.match(chat, /renderChatMemoryPanel/, "chat.js should render memory panel");
assert.match(chat, /session-memory-policy/, "chat.js should bind session memory policy");
assert.match(chat, /memory-pending-count/, "chat.js should show pending memory count");
assert.match(chat, /memory-approve/, "chat.js should expose pending approval");
assert.match(chat, /memory-edit-approve/, "chat.js should expose edit-before-approval");
assert.match(chat, /memory-reject/, "chat.js should expose pending rejection");
assert.match(chat, /memory-panel-disable/, "chat.js should let users disable active memories");

const config = read("self-analyst-app/src/main/resources/desktop-ui/config.js");
assert.match(config, /renderMemoryManager/, "config.js should render memory manager");
assert.match(config, /memory-manager-add/, "config.js should expose global manual memory add");
assert.match(config, /memory-manager-edit/, "config.js should expose global memory edit");

const i18n = read("self-analyst-app/src/main/resources/desktop-ui/i18n.js");
[
  "memory.chatTitle",
  "memory.policy.smart",
  "memory.policy.confirmAll",
  "memory.policy.off",
  "memory.managerTitle",
  "memory.edit",
  "memory.editApprove",
  "memory.editContent",
  "memory.editEvidence",
  "memory.pendingCount",
].forEach((key) => assert.match(i18n, new RegExp(`"${key}"`), `i18n missing ${key}`));

console.log("static-long-term-memory checks passed");
