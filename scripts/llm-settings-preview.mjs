// 界面人工验收：仅使用内存假数据，不连接供应商或读写用户配置。
import http from 'node:http';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const root = path.resolve(fileURLToPath(new URL('../self-analyst-app/src/main/resources/desktop-ui/', import.meta.url)));
const fields = Object.fromEntries(Object.entries({ baseUrl: 'https://api.openai.com/v1', model: 'demo-model', temperature: 0.7, maxTokens: 2048 })
  .map(([key, value]) => [key, { effectiveValue: value, savedValue: null, source: 'default' }]));
const snapshot = { protocol: 'openai-completions', fields, credential: { configured: false, source: 'default', hasUserOverride: false },
  runtime: { application: { llm: { status: 'unavailable', activeWorkCount: 0 } } } };
const json = (res, data) => { res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify(data)); };
const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://127.0.0.1');
    if (url.pathname === '/desktop/llm-settings/presets') return json(res, { presets: [
      { id: 'openai', name: 'OpenAI', baseUrl: 'https://api.openai.com/v1' },
      { id: 'deepseek', name: 'DeepSeek', baseUrl: 'https://api.deepseek.com/v1' },
      { id: 'openrouter', name: 'OpenRouter', baseUrl: 'https://openrouter.ai/api/v1' }] });
    if (url.pathname === '/desktop/llm-settings') {
      if (req.method === 'PUT') {
        let body = ''; for await (const chunk of req) body += chunk;
        const draft = JSON.parse(body);
        for (const [key, value] of Object.entries(draft.updates)) fields[key] = { effectiveValue: value, savedValue: String(value), source: 'toml' };
        return json(res, { saved: true, settings: snapshot });
      }
      return json(res, snapshot);
    }
    if (url.pathname === '/desktop/llm-settings/discover-models') return json(res, {
      ok: true, code: 'success', models: ['demo-model', 'example-provider/long-model-id-for-layout-verification-' + 'x'.repeat(80)], latencyMs: 12 });
    if (url.pathname === '/desktop/llm-settings/test') return json(res, { ok: false, code: 'not_configured', latencyMs: 0 });
    if (url.pathname === '/desktop/config/raw') return json(res, { text: '# 仅供界面验收\n[llm]\nmodel="demo-model"\n', path: '临时界面假数据/config.toml', exists: true });
    if (url.pathname === '/desktop/config/effective') return json(res, snapshot.runtime);
    if (url.pathname.startsWith('/desktop/')) return json(res, {});
    const relative = url.pathname.replace(/^\/desktop-ui\/?/, '') || 'index.html';
    const target = path.resolve(root, relative);
    if (!target.startsWith(root + path.sep) && target !== path.join(root, 'index.html')) { res.statusCode = 404; return res.end(); }
    let data = await fs.readFile(target);
    if (relative === 'init.js') data = Buffer.from(data.toString().split('// Start when DOM is ready')[0] + `
      cacheDom(); loadI18n('zh').then(function () { state.lang='zh'; applyI18n(document); setupEvents(); openConfigModal(); });
    `);
    res.setHeader('Content-Type', relative.endsWith('.js') ? 'application/javascript' : relative.endsWith('.css') ? 'text/css' : relative.endsWith('.json') ? 'application/json' : 'text/html');
    res.end(data);
  } catch { res.statusCode = 404; res.end(); }
});
server.listen(0, '127.0.0.1', () => console.log('http://127.0.0.1:' + server.address().port + '/desktop-ui/'));
