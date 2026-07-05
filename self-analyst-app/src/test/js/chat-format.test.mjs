import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

const chatJs = fs.readFileSync(
  new URL("../../main/resources/desktop-ui/chat.js", import.meta.url),
  "utf8",
);
const i18nJs = fs.readFileSync(
  new URL("../../main/resources/desktop-ui/i18n.js", import.meta.url),
  "utf8",
);

const sandbox = {
  state: { lang: "zh" },
  escHtml(value) {
    return String(value || "")
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  },
  t(key, params) {
    const messages = {
      "timeline.insight": "洞察",
      "timeline.suggestion": "建议",
      "chat.confidence": `置信度: ${params?.v ?? ""}`,
      "chat.moreSummaries": `还有 ${params?.n ?? 0} 条摘要未展开`,
      "chat.sendFailed": `发送失败: ${params?.msg ?? ""}`,
      "common.unknownError": "未知错误",
      "memory.loading": "加载中",
      "memory.loadFailed": `加载失败: ${params?.msg ?? ""}`,
      "memory.noSession": "无会话",
      "memory.policy.smart": "智能",
      "memory.policy.confirmAll": "全部确认",
      "memory.policy.off": "关闭",
      "memory.pendingCount": `待确认 ${params?.n ?? 0}`,
      "memory.addPlaceholder": "添加记忆",
      "memory.add": "添加",
      "memory.empty": "暂无记忆",
    };
    return messages[key] || key;
  },
};
vm.createContext(sandbox);
vm.runInContext(i18nJs, sandbox);
vm.runInContext(chatJs, sandbox);

const jsonReply = "```json\n" + JSON.stringify([
  {
    headline: "Chrome与终端之间的短时开发会话",
    insight: "窗口切换55次，注意力成本偏高",
    suggestion: "给终端和浏览器分配固定时间段",
    confidence: "medium",
  },
  {
    headline: "Chrome全天运行超13小时",
    insight: "大量时间未实际操作",
    suggestion: null,
    confidence: "high",
  },
]) + "\n```";

const formatted = sandbox.formatChatMessageContent(jsonReply);
assert.match(formatted, /chat-structured-response/);
assert.match(formatted, /Chrome与终端之间的短时开发会话/);
assert.match(formatted, /洞察/);
assert.match(formatted, /建议/);
assert.doesNotMatch(formatted, /```json/);
assert.doesNotMatch(formatted, /"headline"/);

const escaped = sandbox.formatChatMessageContent("hello <script>alert(1)</script>");
assert.equal(escaped, "hello &lt;script&gt;alert(1)&lt;/script&gt;");

assert.equal(
  sandbox.formatChatErrorMessage(new Error("timeout")),
  "发送失败: timeout",
);

const root = { innerHTML: "", textContent: "" };
const draft = { value: "", oninput: null };
const elements = {
  "chat-memory-content": root,
  "session-memory-policy": {},
  "memory-draft-input": draft,
  "memory-add-btn": {},
};
sandbox.document = {
  getElementById(id) {
    return elements[id] || null;
  },
  querySelectorAll() {
    return [];
  },
};
sandbox.state = {
  activeChatSessionId: "session-1",
  chatSessions: [{ id: "session-1", memoryPolicy: "smart" }],
  memoryLoading: false,
  memoryLoadError: null,
  memoryItems: [],
  pendingMemoryCount: 0,
  memoryDraft: "未提交草稿",
};

sandbox.renderChatMemoryPanel();
assert.equal(draft.value, "未提交草稿");
assert.equal(typeof draft.oninput, "function");
draft.value = "新的草稿";
draft.oninput();
assert.equal(sandbox.state.memoryDraft, "新的草稿");
