import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

const i18nJs = fs.readFileSync(
  new URL("../../main/resources/desktop-ui/i18n.js", import.meta.url),
  "utf8",
);
const chatJs = fs.readFileSync(
  new URL("../../main/resources/desktop-ui/chat.js", import.meta.url),
  "utf8",
);

function textSlot() {
  return { innerHTML: "", textContent: "" };
}

const currentStatus = textSlot();
const recentActivity = textSlot();
const memoryContent = textSlot();

const sandbox = {
  state: {
    lang: "zh",
    chatSessionSearch: "",
    activeChatSessionId: "s1",
    chatSending: false,
    memoryItems: [],
    pendingMemoryCount: 0,
    memoryLoading: false,
    memoryDraft: "",
    status: { llm: { configured: true } },
    summary: null,
    chatSessions: [{
      id: "s1",
      title: "新会话",
      updatedAt: "2026-07-04T12:00:00Z",
      messages: [],
      messagesLoaded: true,
    }],
    dom: {
      chatSessionList: { innerHTML: "", onclick: null },
      chatThread: { innerHTML: "", scrollHeight: 0, scrollTop: 0 },
      chatSessionTitle: { textContent: "" },
      chatContextSummary: { querySelector: () => currentStatus },
      chatRecentActivity: { querySelector: () => recentActivity },
      chatTaskSuggestions: { querySelector: () => textSlot() },
      chatTabInput: { disabled: false, placeholder: "" },
      chatTabSendBtn: { disabled: false },
    },
  },
  document: {
    getElementById(id) {
      return id === "chat-memory-content" ? memoryContent : null;
    },
    querySelectorAll() {
      return [];
    },
  },
  escHtml(value) {
    return String(value || "")
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  },
  formatRelativeTime() {
    return "刚刚";
  },
};

vm.createContext(sandbox);
vm.runInContext(i18nJs, sandbox);
vm.runInContext(chatJs, sandbox);

assert.doesNotThrow(() => sandbox.renderChatTab());
assert.match(sandbox.state.dom.chatThread.innerHTML, /欢迎使用会话模式/);
assert.equal(currentStatus.textContent, "暂无数据");
assert.equal(recentActivity.textContent, "暂无活动摘要");
