// Explicit synthetic UI fixture. No real OAuth, credentials, model calls, or collectors.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const root = path.resolve(fileURLToPath(new URL('../../main/resources/desktop-ui/', import.meta.url)));
const bootstrap = `
(async function () {
  state.lang = new URLSearchParams(location.search).get('lang') === 'zh' ? 'zh' : 'en';
  await loadI18n(state.lang); document.documentElement.lang = state.lang; applyI18n(document);
  const fixture = window.fixture = { calls: [], browserCalls: [], serial: 0 };
  const fields = { provider: 'chatgpt', baseUrl: 'https://example.invalid/v1', model: 'api-model', temperature: .7,
    chatgptAccount: 'account-a', chatgptModel: 'chatgpt-model' };
  const settings = () => ({ fields: Object.fromEntries(Object.entries(fields).map(([key,value]) => [key,{effectiveValue:value,source:'toml'}])),
    credential:{configured:true,source:'environment',hasUserOverride:false},runtime:{application:{llm:{status:'applied'}}} });
  fixture.status = { status:'connected', accounts:[{id:'account-a', label:'Example <img src=x onerror=alert(1)>', connected:true}], activeAccountId:'account-a' };
  fixture.complete = () => { fixture.status.status='connected'; delete fixture.status.loginAttemptId;
    fixture.status.activeAccountId='account-b'; fixture.status.accounts.push({id:'account-b',label:'Second example account',connected:true}); };
  window.__SELF_ANALYST_DESKTOP__ = true;
  window.__TAURI__ = { core:{ invoke:async (command,args) => { fixture.browserCalls.push({command,args});
    if (new URLSearchParams(location.search).has('browserFail')) throw Error('synthetic browser unavailable'); } } };
  api.llmSettings = async (route,method,body,signal) => {
    fixture.calls.push({route,method:method || 'GET',body});
    if (route === '/chatgpt/status') return structuredClone(fixture.status);
    if (route === '/presets') return {presets:[{id:'openai',name:'OpenAI',baseUrl:'https://api.openai.com/v1'}]};
    if (route === '/chatgpt/login') {
      const attemptId='synthetic-'+(++fixture.serial), url=new URL('https://auth.openai.com/api/accounts/authorize');
      Object.entries({client_id:'dynamic_agent_client',response_type:'code',redirect_uri:'http://127.0.0.1:1455/auth/callback',
        state:'s'.repeat(32),code_challenge:'c'.repeat(43),code_challenge_method:'S256',resource:'https://api.openai.com/v1',
        ext_agent_host_id:'urn:uuid:12345678-1234-1234-1234-123456789abc',agent_name_hint:'SelfAnalyst'}).forEach(([k,v])=>url.searchParams.set(k,v));
      fixture.status.status='pending'; fixture.status.loginAttemptId=attemptId;
      return {attemptId,authorizationUrl:url.href,expiresAt:'2099-01-01T00:00:00Z'};
    }
    if (route === '/chatgpt/cancel') { fixture.status.status='connected'; delete fixture.status.loginAttemptId; return {}; }
    if (route === '/chatgpt/logout') { fixture.status.accounts=fixture.status.accounts.filter(a=>a.id!==body.accountId); return {remoteRevocationConfirmed:false}; }
    if (route === '/chatgpt/models') return {ok:true,code:'success',models:[{slug:'synthetic-model',displayName:'Synthetic model'}]};
    if (route === '/chatgpt/test') return {ok:true,code:'success',latencyMs:12};
    if (method === 'PUT') { Object.assign(fields,body.updates); return {settings:settings()}; }
    return settings();
  };
  document.getElementById('error-overlay').classList.add('hidden');
  await openConfigModal(); document.body.dataset.fixtureReady='true';
})();`;
export function startChatgptSettingsReviewServer() {
  return http.createServer((req, res) => {
    const url = new URL(req.url, 'http://localhost');
    const name = decodeURIComponent(url.pathname).replace(/^\/(?:desktop-ui\/)?/, '') || 'index.html';
    if (name === 'init.js') { res.setHeader('Content-Type', 'text/javascript; charset=utf-8'); return res.end(bootstrap); }
    const target = path.resolve(root, name);
    if (!target.startsWith(root + path.sep) || !fs.existsSync(target) || !fs.statSync(target).isFile()) { res.writeHead(404); return res.end(); }
    res.setHeader('Content-Type', ({'.js':'text/javascript','.css':'text/css','.html':'text/html','.json':'application/json','.svg':'image/svg+xml'})[path.extname(target)] || 'application/octet-stream');
    res.end(fs.readFileSync(target));
  }).listen(0, '127.0.0.1');
}
if (!process.env.NODE_TEST_CONTEXT && process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const server = startChatgptSettingsReviewServer();
  server.on('listening', () => console.log('CHATGPT_SETTINGS_REVIEW_PORT=' + server.address().port));
}
