// Explicit, loopback-only visual fixture. No production backend, user data, or collectors.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('../../main/resources/desktop-ui/', import.meta.url));
const language = process.env.SELF_ANALYST_TITLEBAR_REVIEW_LANGUAGE === 'en' ? 'en' : 'zh';
const bootstrap = `state.lang = ${JSON.stringify(language)}; loadI18n(state.lang).then(function () {
document.documentElement.lang = state.lang; applyI18n(document); window.SelfAnalystTitlebar.localize();
document.getElementById('error-overlay').classList.add('hidden');
document.querySelector('[data-tab=files]').classList.remove('hidden');
document.getElementById('file-status-btn').classList.remove('hidden');
document.querySelectorAll('.status-dot').forEach(function(el){el.style.background='#10b981';});
document.getElementById('timeline-body').innerHTML = '<div class="timeline-item"><h3>SelfAnalyst</h3><p>${language === 'zh' ? '标题栏与帮助菜单验收 · 示例数据' : 'Titlebar and help menu review · Sample data'}</p></div>';
});`;
if (!process.env.NODE_TEST_CONTEXT && process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  http.createServer((req, res) => {
    let url = new URL(req.url, 'http://localhost');
    let name = decodeURIComponent(url.pathname).replace(/^\/desktop-ui\//, '') || 'index.html';
    if (name === 'init.js') { res.setHeader('Content-Type', 'text/javascript; charset=utf-8'); return res.end(bootstrap); }
    let target = path.resolve(root, name);
    if (!target.startsWith(root) || !fs.existsSync(target) || !fs.statSync(target).isFile()) { res.writeHead(404); return res.end(); }
    res.setHeader('Content-Type', ({'.js':'text/javascript','.css':'text/css','.html':'text/html','.json':'application/json','.svg':'image/svg+xml'})[path.extname(target)] || 'application/octet-stream');
    res.end(fs.readFileSync(target));
  }).listen(0, '127.0.0.1', function () { console.log('TITLEBAR_REVIEW_PORT=' + this.address().port); });
}
