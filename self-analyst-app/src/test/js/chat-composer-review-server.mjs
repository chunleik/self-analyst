// Synthetic loopback UI fixture: real assets, no credentials, collectors or model calls.
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
const root = path.resolve(fileURLToPath(new URL("../../main/resources/desktop-ui/", import.meta.url)));
const cacheDom = fs.readFileSync(path.join(root, "init.js"), "utf8").split("var initializationPending")[0];
const bootstrap = cacheDom + `
(async function () {
  const params = new URLSearchParams(location.search);
  state.lang = params.get('lang') === 'en' ? 'en' : 'zh';
  state.dateLocale = state.lang === 'zh' ? 'zh-CN' : 'en-US';
  await loadI18n(state.lang); applyI18n(document); cacheDom();
  document.documentElement.lang = state.lang;
  state.tab = 'chat'; state.status = {llm:{configured:true}};
  document.getElementById('error-overlay').classList.add('hidden');
  document.querySelectorAll('.tab-content, .tab').forEach(el => el.classList.remove('active'));
  document.getElementById('tab-chat').classList.add('active');
  document.querySelector('[data-tab="chat"]').classList.add('active');
  state.chatSessions = [{id:'a',title:state.lang === 'zh' ? '输入区体验 · 示例会话' : 'Composer review · sample chat',
    updatedAt:Date.now(), messagesLoaded:true, messages:[
      {id:'u1',role:'user',content:'请总结今天的工作。',status:'sent'},
      {id:'a1',role:'assistant',content:'这是用于界面验证的示例数据，不会调用模型。\\n\\n- 集中整理项目资料\\n- 完成页面交互优化\\n- 保留后续测试清单',status:'sent'}]}];
  state.activeChatSessionId = 'a'; state.chatSessionsLoaded = true;
  refreshChatDocuments = function () {}; refreshSummaryRepair = function () {};
  syncChatIntegrationApprovals = function () {};
  api.setActiveSession = async function () {};
  if (params.has('fallback')) activateDeepChatFallback(state.dom.deepChat);
  bindChatComposer(); renderChatTab();
  window.fixtureReady = true;
})();`;
export function startComposerReviewServer() {
  return http.createServer((req,res) => {
    const name = decodeURIComponent(new URL(req.url,'http://localhost').pathname).replace(/^\//,'') || 'index.html';
    if (name === 'init.js') { res.setHeader('Content-Type','text/javascript; charset=utf-8'); return res.end(bootstrap); }
    const target = path.resolve(root,name);
    if (!target.startsWith(root + path.sep) || !fs.existsSync(target) || !fs.statSync(target).isFile()) { res.writeHead(404); return res.end(); }
    res.setHeader('Content-Type',({'.js':'text/javascript','.css':'text/css','.html':'text/html','.json':'application/json','.svg':'image/svg+xml'})[path.extname(target)] || 'application/octet-stream');
    res.end(fs.readFileSync(target));
  }).listen(0,'127.0.0.1');
}
if (!process.env.NODE_TEST_CONTEXT && process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const server = startComposerReviewServer(); server.on('listening',()=>console.log('COMPOSER_REVIEW_PORT='+server.address().port));
}
