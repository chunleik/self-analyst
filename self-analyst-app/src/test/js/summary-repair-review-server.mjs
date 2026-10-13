// Loopback-only synthetic UI fixture. No real activity, database, or model access.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const root = path.resolve(fileURLToPath(new URL('../../main/resources/desktop-ui/', import.meta.url)));
const sid = 'a'.repeat(32);
const target = { entryId: 'synthetic-hour', level: 'HOUR', periodStart: '2026-10-12T16:00:00Z', periodEnd: '2026-10-12T17:00:00Z', timezone: 'UTC', revision: 3 };
let scenario, session, repair, calls;
function reset(next = 'quality') {
  scenario = next; calls = [];
  session = { id: sid, title: 'Synthetic repair · 16:00–17:00', source: 'summary_repair', createdAt: '2026-10-13T08:00:00Z', updatedAt: '2026-10-13T08:00:00Z',
    messages: next === 'empty' ? [] : [{ id: 'synthetic-user', role: 'user', content: 'Please revise this synthetic period.', status: 'sent' },
      { id: 'synthetic-assistant', role: 'assistant', content: next === 'budget' ? 'The model budget for this synthetic period is exhausted. Local editing, validation, and saving remain available.' : 'Review the draft below. These are synthetic fixture records. You can edit and validate locally without a model.', status: next === 'budget' ? 'error' : 'sent' }],
    contextSnapshot: { type: 'summary_repair', repairId: 'synthetic-repair', target: { ...target } } };
  const candidate = { summary: next === 'empty' ? '' : 'Reviewed the synthetic test plan in Editor.', primaryTask: next === 'empty' ? '' : 'Synthetic test plan',
    taskSegments: next === 'empty' ? [] : [{ title: 'Synthetic test plan', summary: 'Reviewed the synthetic test plan in Editor.', evidence: [], apps: [], confidence: 'medium', evidenceFactIds: ['title:1'], claimType: 'observed' }] };
  repair = { target: { ...target }, evidence: [{ id: 'title:1', app: 'Editor', title: 'Synthetic test plan', observationOnly: true }, { id: 'title:2', app: 'Browser', title: 'AFK detector documentation', observationOnly: true }], failureTextAvailable: false,
    message: 'WIKI_EVIDENCE_UNSUPPORTED_CLAIM', currentEntry: null,
    draft: { id: 'synthetic-repair', sessionId: sid, targetId: target.entryId, targetRevision: 3, draftRevision: 1,
      state: next === 'quality' ? 'invalid' : next === 'success' ? 'committed' : 'unvalidated', candidate, statements: [],
      issues: next === 'quality' ? [{ field: 'taskSegments[0].summary', code: 'WIKI_EVIDENCE_UNSUPPORTED_CLAIM' }] : [], committedRevision: next === 'success' ? 4 : null } };
  if (next === 'success') repair.target.revision = 4;
}
reset();
function summary() {
  return { statisticsVersion: 'activity-afk-v2', calendarVersion: 'day-0400-v1', timezone: 'UTC', assembledAt: '2026-10-13T08:00:00Z', current: { headline: 'Synthetic Editor activity', evidence: [] }, timeline: [
    { key: 'current', label: 'Current · synthetic', headline: 'Synthetic Editor activity', insight: 'Reviewed a sample test plan and AFK detector documentation.', taskSegments: [{ title: 'AFK detector documentation', summary: 'Read the AFK detector design.' }],
      evidence: ['Unidentified application activity: 4m', 'Incomplete AFK coverage: some durations include unverified idle time and are estimates', 'Editor: Synthetic test plan'], unknownActivitySeconds: 240, coverage: 'estimated' },
    { key: 'yesterday', label: 'Yesterday · synthetic', headline: 'Saved periods and app statistics', partialSummaries: [{ periodLabel: '04:00–12:00', summary: 'Reviewed a sample document.' }],
      summaryStatus: { state: 'waiting_dependencies', dependencies: [{ periodLabel: '10-12 16:00–17:00', state: 'failed', repairTarget: { ...target }, generationProgress: { state: 'quality', calls: 12, maxCalls: 12, tokens: 5000, maxTokens: 5000 } }] } },
  ], behaviorAdvice: { type: 'empty' } };
}
const bootstrap = fs.readFileSync(path.join(root, 'init.js'), 'utf8').split('var initializationPending')[0] + `
(async function () {
  var params = new URLSearchParams(location.search);
  await fetch('/fixture/reset?scenario=' + (params.get('scenario') || 'quality'));
  cacheDom();
  state.lang = params.get('lang') || 'en'; state.dateLocale = state.lang === 'zh' ? 'zh-CN' : 'en-US';
  await loadI18n(state.lang); applyI18n(document); document.documentElement.lang = state.lang;
  state.status = { llm: { configured: params.get('scenario') === 'budget' }, collectors: {} };
  state.usage = params.get('scenario') === 'budget' ? { totalTokens: 5000 } : null;
  state.summary = await api.getSummary(); state.chatSessionsLoaded = true;
  var session = await api.getSession('${sid}'); session.messagesLoaded = true;
  state.chatSessions = [session]; state.activeChatSessionId = session.id;
  setupEvents(); hideError(); updateStatusBar();
  refreshChatDocuments = function () {};
  renderChatImages = function () {};
  if (params.get('surface') === 'fallback') activateDeepChatFallback(state.dom.deepChat);
  switchTab(params.get('page') === 'dashboard' ? 'agent' : 'chat');
  renderTimeline();
  document.querySelectorAll('.timeline-entry').forEach(function (entry) { toggleTimelineEntry(entry); });
  renderChatTab();
  await refreshSummaryRepair(true);
  if (params.get('scenario') === 'conflict') {
    var local = repairState(session.id); local.conflict = true; local.error = 'WIKI_REPAIR_TARGET_CONFLICT'; mountSummaryRepair();
  }
  var marker = document.createElement('div'); marker.id = 'fixture-ready'; marker.hidden = true; document.body.appendChild(marker);
})();`;
const json = (res, value, status = 200) => { res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(value)); };
export function startRepairReviewServer() {
  return http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://localhost');
    if (url.pathname === '/fixture/reset') { reset(url.searchParams.get('scenario')); return json(res, { ok: true }); }
    if (url.pathname === '/fixture/report') return json(res, { calls, repair });
    if (url.pathname.startsWith('/desktop/')) {
      let body = ''; for await (const chunk of req) body += chunk;
      body = body ? JSON.parse(body) : {};
      calls.push({ method: req.method, path: url.pathname, body });
      if (url.pathname === '/desktop/summary') return json(res, summary());
      if (url.pathname === '/desktop/chat/sessions/' + sid) return json(res, session);
      if (url.pathname === '/desktop/chat/active-session') return json(res, { activeSessionId: sid });
      if (url.pathname === '/desktop/summary-repairs' || url.pathname.includes('/summary-repairs/session/')) return json(res, repair);
      if (url.pathname.startsWith('/desktop/summary-repairs/')) {
        if (req.method !== 'GET' && (body.sessionId !== sid || body.draftRevision !== repair.draft.draftRevision)) return json(res, { error: 'WIKI_REPAIR_DRAFT_CONFLICT' }, 409);
        if (url.pathname.endsWith('/draft')) { repair.draft.candidate = body.candidate; repair.draft.draftRevision++; repair.draft.statements = []; repair.draft.issues = []; repair.draft.state = 'unvalidated'; }
        if (url.pathname.endsWith('/confirm')) {
          repair.draft.draftRevision++;
          repair.draft.statements = body.taskIndexes.map(index => ({ id: 'user_statement:synthetic-' + index, targetId: target.entryId, taskIndex: index, sourceId: 'local', text: repair.draft.candidate.taskSegments[index].summary, draftRevision: repair.draft.draftRevision, confirmedAt: '2026-10-13T08:00:00Z' }));
          for (const index of body.taskIndexes) { repair.draft.candidate.taskSegments[index].claimType = 'user_confirmed'; repair.draft.candidate.taskSegments[index].evidenceFactIds.push('user_statement:synthetic-' + index); }
          repair.draft.candidate.summary = repair.draft.candidate.taskSegments.map(task => task.summary).join(' ');
          repair.draft.candidate.primaryTask = repair.draft.candidate.taskSegments[0].title;
        }
        if (url.pathname.endsWith('/refresh')) { repair.draft.candidate = body.candidate || repair.draft.candidate; repair.draft.draftRevision++; repair.draft.targetRevision = repair.target.revision; repair.draft.statements = []; repair.draft.issues = []; repair.draft.state = 'unvalidated'; }
        if (url.pathname.endsWith('/validate')) {
          const valid = repair.draft.candidate.summary.trim() && repair.draft.candidate.taskSegments.length;
          repair.draft.state = valid ? 'valid' : 'invalid'; repair.draft.issues = valid ? [] : [{ field: 'summary', code: 'WIKI_REPAIR_EMPTY' }];
        }
        if (url.pathname.endsWith('/commit')) {
          if (scenario === 'conflict' || scenario === 'commit-conflict') { repair.target.revision = 4; repair.currentEntry = { summary: 'A newer synthetic summary is already published.' }; return json(res, { error: 'WIKI_REPAIR_TARGET_CONFLICT' }, 409); }
          if (repair.draft.state !== 'valid' && repair.draft.state !== 'committed') return json(res, { error: 'WIKI_REPAIR_VALIDATION_REQUIRED' }, 400);
          repair.draft.state = 'committed'; repair.draft.committedRevision = 4; repair.target.revision = 4;
        }
        return json(res, repair);
      }
      return json(res, { error: 'Synthetic fixture has no model or real backend' }, 503);
    }
    const name = decodeURIComponent(url.pathname).replace(/^\//, '') || 'index.html';
    if (name === 'init.js') { res.writeHead(200, { 'Content-Type': 'text/javascript; charset=utf-8' }); return res.end(bootstrap); }
    const file = path.resolve(root, name);
    if (!file.startsWith(root + path.sep) || !fs.existsSync(file) || !fs.statSync(file).isFile()) { res.writeHead(404); return res.end(); }
    res.setHeader('Content-Type', ({ '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.json': 'application/json', '.svg': 'image/svg+xml' })[path.extname(file)] || 'application/octet-stream');
    res.end(fs.readFileSync(file));
  }).listen(0, '127.0.0.1', function () { console.log('SUMMARY_REPAIR_REVIEW_PORT=' + this.address().port); });
}

if (typeof process !== "undefined" && !process.env.NODE_TEST_CONTEXT && process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) startRepairReviewServer();
