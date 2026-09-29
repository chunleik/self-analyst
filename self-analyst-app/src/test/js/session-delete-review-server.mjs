// Loopback-only visual fixture: production UI assets, in-memory sample chats, no backend.
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../../main/resources/desktop-ui/", import.meta.url));
const bootstrap = `
state.lang = new URLSearchParams(location.search).get('lang') === 'en' ? 'en' : 'zh';
state.dateLocale = state.lang === 'zh' ? 'zh-CN' : 'en-US';
loadI18n(state.lang).then(function () {
  applyI18n(document);
  document.documentElement.lang = state.lang;
  document.getElementById('error-overlay').classList.add('hidden');
  document.querySelectorAll('.tab-content, .tab').forEach(function (el) { el.classList.remove('active'); });
  document.getElementById('tab-chat').classList.add('active');
  document.querySelector('[data-tab="chat"]').classList.add('active');
  state.dom = { chatSessionList: document.getElementById('chat-session-list') };
  state.chatSessions = ['a', 'b'].map(function (id, index) {
    return { id: id, title: state.lang === 'zh' ? ['今天我都做了什么', '新会话'][index] : ['What did I do today?', 'New chat'][index],
      updatedAt: Date.now(), messages: [], messagesLoaded: true, lastMessagePreview: state.lang === 'zh' ? '示例会话' : 'Sample chat' };
  });
  state.activeChatSessionId = 'a';
  state.chatSessionsLoaded = true;
  var count = 0;
  var result = document.createElement('p'); result.id = 'review-result'; result.style.padding = '24px';
  document.getElementById('chat-thread').replaceChildren(result);
  function status() { result.textContent = (state.lang === 'zh' ? '示例数据 · 删除请求次数：' : 'Sample data · Delete requests: ') + count; }
  api.deleteSession = function () { count++; status(); return Promise.resolve({ activeSessionId: 'b' }); };
  api.setActiveSession = function () { return Promise.resolve(); };
  renderChatTab = renderChatSessionList;
  status(); renderChatTab();
  document.getElementById('chat-session-title').textContent = state.chatSessions[0].title;
});`;

if (!process.env.NODE_TEST_CONTEXT && process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  http.createServer((req, res) => {
    const url = new URL(req.url, "http://localhost");
    const name = decodeURIComponent(url.pathname).replace(/^\//, "") || "index.html";
    if (name === "init.js") { res.setHeader("Content-Type", "text/javascript; charset=utf-8"); return res.end(bootstrap); }
    const target = path.resolve(root, name);
    if (!target.startsWith(root) || !fs.existsSync(target) || !fs.statSync(target).isFile()) { res.writeHead(404); return res.end(); }
    res.setHeader("Content-Type", ({ ".js": "text/javascript", ".css": "text/css", ".html": "text/html", ".json": "application/json", ".svg": "image/svg+xml" })[path.extname(target)] || "application/octet-stream");
    res.end(fs.readFileSync(target));
  }).listen(0, "127.0.0.1", function () { console.log("SESSION_DELETE_REVIEW_PORT=" + this.address().port); });
}
