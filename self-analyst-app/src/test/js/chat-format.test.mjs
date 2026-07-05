import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

const chatJs = fs.readFileSync(
  new URL("../../main/resources/desktop-ui/chat.js", import.meta.url),
  "utf8",
);

const sandbox = {
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
    };
    return messages[key] || key;
  },
};
vm.createContext(sandbox);
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
