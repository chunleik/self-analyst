import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
const source = fs.readFileSync(new URL('../../main/resources/desktop-ui/ontology.js', import.meta.url), 'utf8');
class Element {
  constructor(tag = 'div') {
    this.tag = tag; this.children = []; this.value = ''; this.checked = false; this.events = {};
    this.classList = { toggle() {} };
  }
  set textContent(value) { this.text = String(value); this.children = []; }
  get textContent() { return (this.text || '') + this.children.map(c => c.textContent).join(' '); }
  append(...items) { for (const item of items) { this.children.push(item); item.parent = this; if (this.tag === 'select' && !this.value) this.value = item.value; } }
  replaceChildren(...items) { this.scrollTop = 0; this.children = []; if (this.tag === 'select') this.value = ''; this.append(...items); }
  setAttribute(key, value) { this[key] = String(value); }
  getAttribute(key) { return this[key]; }
  removeAttribute(key) { delete this[key]; }
  contains(node) { return all(this).includes(node); }
  focus() { this.owner.activeElement = this; }
  scrollIntoView() {}
  setCustomValidity(message) { this.validationMessage = message; }
  get isConnected() { return !!this.parent; }
  addEventListener(name, fn) { this.events[name] = fn; }
  showModal() { this.open = true; }
  close() { this.open = false; this.events.close?.(); }
  remove() { this.parent.children = this.parent.children.filter(c => c !== this); }
}
const all = node => [node, ...node.children.flatMap(all)];
const button = (node, label) => all(node).find(e => e.tag === 'button' && e.textContent === label);
const flush = () => new Promise(resolve => setImmediate(resolve));
const entity = (name = 'Project') => ({ id: 'p', type: 'project', name, source: 'manual', description: '', aliases: [], evidence: [], attributes: {} });
const page = items => ({ items, offset: 0, limit: 30, total: items.length, hasMore: false, coverage: { wiki: 'current', memory: 'current' } });
function setup(handler = async () => page([])) {
  const root = new Element(), body = new Element('body'); body.append(root); root.id = 'knowledge-root';
  const calls = [];
  const document = { body, activeElement: null, events: {},
    createElement: tag => { const element = new Element(tag); element.owner = document; return element; },
    getElementById: id => all(body).find(e => e.id === id),
    addEventListener(name, fn) { this.events[name] = fn; } };
  const ctx = vm.createContext({ URLSearchParams, Date, state: { dateLocale: 'en-US' }, API_BASE: '',
    window: { confirm: () => true }, t: key => key,
    document, setTimeout, clearTimeout,
    chatJsonResponse: async r => { if (!r.ok) throw new Error('failed'); return r.json(); },
    fetch: async (url, options) => { calls.push({ url, options }); const data = await handler(url, options); return { ok: true, json: async () => data }; }
  }); vm.runInContext(source, ctx); return { ctx, root, body, calls };
}

test('knowledge renders hostile names as text and shows evidence gaps and ambiguity', async () => {
  const h = setup(async () => page([entity('<img onerror=bad()>')])); await h.ctx.openKnowledge();
  assert.match(h.root.textContent, /<img onerror=bad\(\)>/); assert.equal(all(h.root).some(n => n.tag === 'img'), false);
  h.ctx.renderKnowledgeDetail({ entity: { ...entity('Activity'), type: 'activity', source: 'wiki', evidence: [{ ref: 'wiki:a/f1', available: false }] },
    classification: 'ambiguous', relations: page([]) });
  assert.match(h.root.textContent, /ontology.classification.ambiguous/); assert.match(h.root.textContent, /ontology.evidenceMissing/);
  assert.match(h.root.textContent, /ontology.readOnly/);
});

test('selecting an entity or following a relation moves the list highlight', async () => {
  const other = { ...entity('Other'), id: 'o' };
  const h = setup(async url => url.includes('/entities/')
    ? { entity: url.includes('/entities/o') ? other : entity(), classification: 'not-applicable', relations: page([]) }
    : page([entity(), other]));
  await h.ctx.openKnowledge();
  const rows = all(h.root).filter(e => e.className === 'knowledge-entity');
  assert.deepEqual(rows.map(r => r['aria-current']), ['false', 'false']);
  rows[0].events.click(); await flush();
  assert.deepEqual(rows.map(r => r['aria-current']), ['true', 'false']);
  h.ctx.knowledge.selected = 'o'; await h.ctx.loadKnowledgeDetail();
  assert.deepEqual(rows.map(r => r['aria-current']), ['false', 'true']);
});

test('empty and failed source requests clear stale content', async () => {
  let fail = false;
  const h = setup(async () => { if (fail) throw new Error('source failed'); return page([]); });
  await h.ctx.openKnowledge(); assert.match(h.root.textContent, /ontology.empty/);
  h.ctx.knowledge.detail.append(new Element('secret')); fail = true; await h.ctx.loadKnowledge();
  assert.match(h.root.textContent, /source failed/); assert.equal(h.ctx.knowledge.detail.children.length, 0);
});

test('late search result cannot overwrite a newer query', async () => {
  let resolveOld; let calls = 0;
  const h = setup(() => ++calls === 1 ? new Promise(r => { resolveOld = r; }) : Promise.resolve(page([entity('New')])));
  const pending = h.ctx.openKnowledge(); await flush(); await h.ctx.loadKnowledge(); resolveOld(page([entity('Old')])); await pending;
  assert.match(h.root.textContent, /New/); assert.doesNotMatch(h.root.textContent, /Old/);
});

test('failed entity save keeps typed fields and allows retry without duplicate submissions', async () => {
  let fail = true;
  const h = setup(async (url, options) => { if (options.method === 'POST') { if (fail) throw new Error('save failed'); return entity('Saved'); }
    if (url.includes('/entities/p')) return { entity: entity('Saved'), classification: 'not-applicable', relations: page([]) }; return page([]); });
  await h.ctx.openKnowledge(); h.ctx.knowledgeEntityForm(null, 'project');
  const dialog = all(h.body).find(e => e.tag === 'dialog'); const form = all(dialog).find(e => e.tag === 'form');
  all(form).find(e => e.tag === 'input').value = 'My project';
  await form.events.submit({ preventDefault() {} });
  assert.equal(dialog.open, true); assert.equal(all(form).find(e => e.tag === 'input').value, 'My project');
  assert.match(dialog.textContent, /save failed/); assert.equal(button(dialog, 'ontology.save').disabled, false);
  fail = false; await form.events.submit({ preventDefault() {} }); assert.equal(dialog.open, false);
});

test('relation confirmation sends explicit endpoints and refreshes the knowledge view', async () => {
  const h = setup(async url => url.includes('/entities/p') ? { entity: entity(), relations: page([]) } : page([entity()]));
  await h.ctx.openKnowledge(); h.ctx.knowledge.selected = 'p'; await h.ctx.loadKnowledgeDetail();
  h.ctx.knowledge.list.scrollTop = 137; h.ctx.knowledge.detail.scrollTop = 289;
  await h.ctx.knowledgeDecision({ subject: 'a', predicate: 'relatedTo', object: 'p' }, 'confirm');
  const call = h.calls.find(c => c.options.method === 'POST');
  assert.deepEqual(JSON.parse(call.options.body), { subject: 'a', predicate: 'relatedTo', object: 'p', action: 'confirm' });
  assert.equal(call.options.credentials, 'same-origin'); assert.match(h.root.textContent, /ontology.saved/);
  assert.equal(h.ctx.knowledge.list.scrollTop, 137); assert.equal(h.ctx.knowledge.detail.scrollTop, 289);
  await h.ctx.knowledgeDecision({ subject: 'a', predicate: 'relatedTo', object: 'p' }, 'reject');
  assert.equal(h.ctx.knowledge.list.scrollTop, 137); assert.equal(h.ctx.knowledge.detail.scrollTop, 289);
});

test('knowledge labels and dynamic vocabulary are present in both languages', () => {
  const dynamic = ['type.project','type.activity','type.topic','type.application','type.goal','type.pattern','type.improvement',
    'predicate.relatedTo','predicate.uses','predicate.about','predicate.supports','predicate.tracks','claim.observed','claim.inferred','claim.confirmed','claim.legacy','claim.candidate',
    'time.all','time.week','time.month','time.custom',
    'relationLabel.relatedTo.project','relationLabel.relatedTo.activity','relationLabel.relatedTo.topic','relationLabel.relatedTo.pattern',
    'relationLabel.uses.application','relationLabel.uses.activity','relationLabel.about.topic','relationLabel.about.activity',
    'relationLabel.supports.goal','relationLabel.supports.project','relationLabel.tracks.goal','relationLabel.tracks.improvement',
    'classification.assigned','classification.unclassified','classification.ambiguous','status.current','status.unavailable','status.truncated'];
  const keys = [...source.matchAll(/"(ontology\.[\w.]+)"/g)].map(m => m[1]).filter(k => !k.endsWith('.')).concat(dynamic.map(k => 'ontology.' + k));
  for (const lang of ['en','zh']) {
    const catalog = JSON.parse(fs.readFileSync(new URL(`../../main/resources/desktop-ui/locales/${lang}.json`, import.meta.url), 'utf8'));
    for (const key of keys) assert.equal(typeof catalog[key], 'string', key);
  }
});

test('knowledge no longer mounts or reads the optional Neo4j integration', async () => {
  const h = setup(); let mounts = 0, reads = 0;
  h.ctx.mountNeo4jSync = () => { mounts++; };
  h.ctx.refreshNeo4jStatus = () => { reads++; };
  await h.ctx.openKnowledge(); await h.ctx.openKnowledge();
  assert.equal(mounts, 0); assert.equal(reads, 0);
  assert.doesNotMatch(h.root.textContent, /neo4j/);
  assert.match(h.root.textContent, /ontology.newProject/);
});


test('header menus expose ARIA and support arrows, Escape, outside dismissal and cancelled rematch', async () => {
  const h = setup(); await h.ctx.openKnowledge();
  const trigger = button(h.root, 'ontology.new');
  assert.equal(trigger['aria-haspopup'], 'menu'); assert.equal(trigger['aria-expanded'], 'false');
  trigger.events.click();
  const menu = all(h.root).find(e => e.id === trigger['aria-controls']);
  assert.equal(menu.role, 'menu'); assert.equal(menu.hidden, false);
  assert.equal(h.ctx.document.activeElement, button(menu, 'ontology.newProject'));
  menu.parent.events.keydown({ key: 'ArrowDown', preventDefault() {} });
  assert.equal(h.ctx.document.activeElement, button(menu, 'ontology.newTopic'));
  menu.parent.events.keydown({ key: 'Escape', preventDefault() {} });
  assert.equal(menu.hidden, true); assert.equal(trigger['aria-expanded'], 'false');
  assert.equal(h.ctx.document.activeElement, trigger);
  trigger.events.keydown({ key: 'ArrowUp', target: trigger, preventDefault() {} });
  menu.parent.events.keydown({ key: 'ArrowUp', target: trigger, preventDefault() {} });
  assert.equal(h.ctx.document.activeElement, button(menu, 'ontology.newTopic'));
  h.ctx.document.events.click({ target: h.body }); assert.equal(menu.hidden, true);
  button(h.root, 'ontology.more').events.click(); button(h.root, 'ontology.rebuild').events.click();
  const dialog = all(h.body).find(e => e.tag === 'dialog');
  assert.match(dialog.textContent, /ontology.rebuildHelp/);
  button(dialog, 'ontology.cancel').events.click();
  assert.equal(h.calls.some(c => c.options.method === 'POST'), false);
});


test('instant search debounces final input, invalidates older requests and Enter runs immediately', async () => {
  let resolveOld;
  const h = setup(url => new URL(url, 'http://local').searchParams.get('q') === 'old'
    ? new Promise(resolve => { resolveOld = resolve; }) : Promise.resolve(page([entity('Latest')])));
  await h.ctx.openKnowledge();
  const query = h.ctx.knowledge.query;
  query.value = 'old'; query.events.keydown({ key: 'Enter', preventDefault() {} }); await flush();
  query.value = 'f'; query.events.input(); query.value = 'final'; query.events.input();
  resolveOld(page([entity('Stale')])); await flush();
  assert.doesNotMatch(h.root.textContent, /Stale/);
  await new Promise(resolve => setTimeout(resolve, 340));
  assert.equal(h.calls.filter(c => c.url.includes('q=final')).length, 1);
  assert.equal(h.calls.some(c => /q=f&/.test(c.url)), false);
  assert.equal(button(h.root, 'ontology.search'), undefined);
  query.value = 'enter'; query.events.input(); query.events.keydown({ key: 'Enter', preventDefault() {} });
  await new Promise(resolve => setTimeout(resolve, 340));
  assert.equal(h.calls.filter(c => c.url.includes('q=enter')).length, 1);
});

test('time presets produce ISO bounds and to-organize is a direct activity filter', async () => {
  const h = setup(); await h.ctx.openKnowledge();
  for (const [preset, days] of [['week', 7], ['month', 30]]) {
    h.ctx.knowledgeSetTime(preset);
    const params = h.ctx.knowledgeQuery(0);
    assert.equal(Date.parse(params.get('end')) - Date.parse(params.get('start')), days * 86400000);
    assert.ok(Math.abs(Date.now() - Date.parse(params.get('end'))) < 1000);
    assert.equal(h.ctx.knowledge.customTime.hidden, true);
  }
  h.ctx.knowledgeSetTime('all'); assert.equal(h.ctx.knowledgeQuery(0).has('start'), false);
  h.ctx.knowledgeSetTime('custom'); assert.equal(h.ctx.knowledge.customTime.hidden, false);
  h.ctx.knowledge.start.value = '2026-11-01T12:00';
  assert.equal(h.ctx.knowledgeQuery(0).get('start'), new Date('2026-11-01T12:00').toISOString());
  button(h.root, 'ontology.unclassified').events.click(); await flush();
  const params = h.ctx.knowledgeQuery(0);
  assert.equal(params.get('type'), 'activity'); assert.equal(params.get('unclassified'), 'true');
});


test('healthy coverage stays quiet and period explanation appears only once on demand', async () => {
  let coverage = { wiki: 'current', memory: 'current' };
  const h = setup(async () => ({ ...page([]), coverage })); await h.ctx.openKnowledge();
  assert.equal(h.ctx.knowledge.coverage.hidden, true); assert.equal(h.ctx.knowledge.coverage.textContent, '');
  assert.doesNotMatch(h.root.textContent, /ontology.coverage/);
  h.ctx.renderKnowledgeDetail({ entity: { ...entity(), start: '2026-11-01T00:00:00Z', end: '2026-11-02T00:00:00Z' }, relations: page([]) });
  const explanations = all(h.root).filter(e => e.tag === 'p' && e.textContent === 'ontology.periodNote');
  assert.equal(explanations.length, 1); assert.equal(explanations[0].parent.tag, 'details');
  for (const warning of ['unavailable', 'truncated']) {
    coverage = { wiki: warning, memory: 'current' }; await h.ctx.loadKnowledge();
    assert.equal(h.ctx.knowledge.coverage.hidden, false); assert.match(h.ctx.knowledge.coverage.textContent, /⚠/);
  }
});


test('detail header presents text-safe aliases and keeps merge/delete in a confirmed secondary menu', async () => {
  const h = setup(); await h.ctx.openKnowledge(); h.ctx.knowledge.selected = 'p';
  h.ctx.renderKnowledgeDetail({ entity: { ...entity('<script>bad()</script>'), aliases: ['<svg onload=bad()>'] }, relations: page([]) });
  const detail = h.ctx.knowledge.detail;
  assert.match(detail.textContent, /<script>bad\(\)<\/script>/); assert.equal(all(detail).some(e => e.tag === 'script' || e.tag === 'svg'), false);
  const header = all(detail).find(e => e.tag === 'header'); assert.match(header.textContent, /<svg onload=bad\(\)>/);
  assert.equal(button(detail, 'ontology.edit').parent.className, 'knowledge-actions');
  const remove = button(detail, 'ontology.delete'); assert.equal(remove.parent.role, 'menu'); assert.equal(remove.parent.hidden, true);
  button(detail, 'ontology.more').events.click(); remove.events.click();
  let dialog = all(h.body).find(e => e.tag === 'dialog');
  button(dialog, 'ontology.cancel').events.click(); assert.equal(h.calls.some(c => c.options.method === 'DELETE'), false);
  button(detail, 'ontology.more').events.click(); remove.events.click();
  dialog = all(h.body).find(e => e.tag === 'dialog');
  await all(dialog).find(e => e.tag === 'form').events.submit({ preventDefault() {} });
  assert.equal(h.calls.filter(c => c.options.method === 'DELETE').length, 1); assert.equal(h.ctx.knowledge.selected, null);
});


test('relations group current-page counts and distinguish inferred, confirmed and candidate states', async () => {
  const h = setup(); await h.ctx.openKnowledge();
  h.ctx.t = (key, values) => key === 'ontology.groupCount' ? `${values.type}: ${values.count} on this page`
    : key === 'ontology.relationLabel.relatedTo.activity' ? 'Includes activity' : key;
  const relation = (id, claimType, status = 'accepted', type = 'activity') => ({
    other: { ...entity(id), id, type },
    assertion: { subject: id, object: 'p', predicate: 'relatedTo', claimType, status, source: 'alias-rule', evidence: [] }
  });
  h.ctx.renderKnowledgeDetail({ entity: entity(), relations: { ...page([
    relation('inferred', 'inferred'), relation('confirmed', 'confirmed'), relation('candidate', 'inferred', 'candidate'),
    relation('observed', 'observed', 'accepted', 'topic')]), total: 45, hasMore: true } });
  const groups = all(h.ctx.knowledge.detail).filter(e => e.className === 'knowledge-relation-group');
  assert.equal(groups.length, 2); assert.match(groups[0].textContent, /ontology.type.activity: 3 on this page/);
  const rows = all(h.ctx.knowledge.detail).filter(e => e.tag === 'article');
  assert.match(rows[0].textContent, /Includes activity/); assert.match(rows[0].textContent, /~ ontology.claim.inferred/);
  assert.match(rows[0].textContent, /ontology.aliasReason/); assert.ok(button(rows[0], 'ontology.confirm'));
  assert.match(rows[1].textContent, /✓ ontology.claim.confirmed/); assert.equal(button(rows[1], 'ontology.confirm'), undefined);
  assert.ok(button(rows[1], 'ontology.reject')); assert.match(rows[2].textContent, /\? ontology.claim.candidate/);
  assert.ok(button(rows[2], 'ontology.confirm')); assert.match(rows[3].textContent, /• ontology.claim.observed/);
  assert.match(rows[3].textContent, /ontology.predicate.relatedTo/); // Missing sentence label falls back safely.
});


test('detail explains active time filters and clears both list and relation bounds', async () => {
  const h = setup(async url => url.includes('/entities/p') ? { entity: entity(), relations: page([]) } : page([entity()]));
  await h.ctx.openKnowledge(); h.ctx.knowledge.selected = 'p'; h.ctx.knowledgeSetTime('week'); await h.ctx.loadKnowledge();
  assert.match(h.ctx.knowledge.detail.textContent, /ontology.timeFiltered/);
  assert.ok(h.calls.at(-1).url.includes('start='));
  const before = h.calls.length; button(h.ctx.knowledge.detail, 'ontology.clearTime').events.click(); await flush();
  assert.equal(h.ctx.knowledge.time.value, 'all');
  assert.equal(h.calls.slice(before).length, 2);
  for (const call of h.calls.slice(before)) assert.equal(new URL(call.url, 'http://local').searchParams.has('start'), false);
  assert.doesNotMatch(h.ctx.knowledge.detail.textContent, /ontology.timeFiltered/);
});


for (const mode of ['merge', 'relation']) {
  test(`${mode} target combobox supports keyboard selection and submission`, async () => {
    const goal = { ...entity('Chosen target'), id: 'target', type: mode === 'merge' ? 'project' : 'goal' };
    const h = setup(async (url, options) => options.method === 'POST' ? goal
      : url.includes('/entities/') ? { entity: goal, relations: page([]) } : page([entity(), goal]));
    await h.ctx.openKnowledge();
    if (mode === 'merge') h.ctx.knowledgeMergeForm(entity()); else h.ctx.knowledgeRelationForm(entity());
    await flush();
    const dialog = all(h.body).find(e => e.tag === 'dialog'), form = all(dialog).find(e => e.tag === 'form');
    const input = all(form).find(e => e.role === 'combobox'), list = all(form).find(e => e.role === 'listbox');
    assert.equal(input['aria-controls'], list.id); assert.equal(input['aria-expanded'], 'true');
    input.events.keydown({ key: 'ArrowDown', preventDefault() {} });
    assert.equal(input['aria-activedescendant'], list.children[0].id); assert.equal(list.children[0]['aria-selected'], 'true');
    input.events.keydown({ key: 'Enter', preventDefault() {} });
    assert.equal(input.value, 'Chosen target'); assert.equal(input['aria-expanded'], 'false');
    assert.equal(input.validationMessage, '');
    await form.events.submit({ preventDefault() {} });
    const call = h.calls.find(c => c.options.method === 'POST');
    assert.deepEqual(JSON.parse(call.options.body), mode === 'merge' ? { from: 'p', to: 'target' }
      : { subject: 'p', predicate: 'supports', object: 'target', action: 'confirm' });
  });
}

test('combobox debounces, discards stale targets, clears selection and scrolls additional pages', async () => {
  let resolveOld;
  const h = setup(async url => {
    const params = new URL(url, 'http://local').searchParams;
    if (params.get('q') === 'old') return new Promise(resolve => { resolveOld = resolve; });
    return { ...page([{ ...entity(params.get('q') || 'First'), id: params.get('offset') === '30' ? 'second' : 'first' }]),
      offset: Number(params.get('offset')), hasMore: params.get('offset') !== '30' };
  });
  await h.ctx.openKnowledge(); h.ctx.knowledgeMergeForm(entity()); await flush();
  const dialog = all(h.body).find(e => e.tag === 'dialog');
  const input = all(dialog).find(e => e.role === 'combobox'), list = all(dialog).find(e => e.role === 'listbox');
  list.scrollTop = 100; list.clientHeight = 100; list.scrollHeight = 200;
  list.events.scroll(); list.events.scroll(); await flush();
  assert.equal(h.calls.filter(c => c.url.includes('offset=30')).length, 1); assert.equal(list.children.length, 2);
  input.value = 'old'; input.events.input(); input.events.keydown({ key: 'Enter', preventDefault() {} }); await flush();
  input.value = 'new'; input.events.input(); resolveOld(page([{ ...entity('Stale target'), id: 'stale' }])); await flush();
  assert.doesNotMatch(list.textContent, /Stale/); await new Promise(resolve => setTimeout(resolve, 340));
  assert.match(list.textContent, /new/); assert.equal(h.calls.filter(c => c.url.includes('q=new')).length, 1);
  input.events.keydown({ key: 'ArrowDown', preventDefault() {} }); input.events.keydown({ key: 'Enter', preventDefault() {} });
  input.value = 'unselected'; input.events.input();
  await all(dialog).find(e => e.tag === 'form').events.submit({ preventDefault() {} });
  assert.equal(h.calls.some(c => c.options.method === 'POST'), false); assert.match(dialog.textContent, /ontology.targetRequired/);
  input.events.keydown({ key: 'Escape', preventDefault() {}, stopPropagation() {} });
  assert.equal(input['aria-expanded'], 'false'); assert.equal(input['aria-activedescendant'], undefined);
});


test('selected search target keeps its result query when loading more after reopening', async () => {
  const h = setup(async url => {
    const params = new URL(url, 'http://local').searchParams;
    return { ...page([{ ...entity('Matching project'), id: params.get('offset') === '30' ? 'second' : 'first' }]),
      offset: Number(params.get('offset')), hasMore: params.get('offset') !== '30' };
  });
  await h.ctx.openKnowledge(); h.ctx.knowledgeMergeForm(entity()); await flush();
  const dialog = all(h.body).find(e => e.tag === 'dialog');
  const input = all(dialog).find(e => e.role === 'combobox');
  input.value = 'match'; input.events.input(); input.events.keydown({ key: 'Enter', preventDefault() {} }); await flush();
  input.events.keydown({ key: 'ArrowDown', preventDefault() {} }); input.events.keydown({ key: 'Enter', preventDefault() {} });
  input.events.keydown({ key: 'ArrowDown', preventDefault() {} }); button(dialog, 'ontology.next').events.click(); await flush();
  const params = new URL(h.calls.at(-1).url, 'http://local').searchParams;
  assert.equal(params.get('q'), 'match'); assert.equal(params.get('offset'), '30');
});

test('pending form submission cannot be repeated and rejected saves retain every field', async () => {
  let rejectSave;
  const h = setup(async (url, options) => options.method === 'POST'
    ? new Promise((resolve, reject) => { rejectSave = reject; }) : page([]));
  await h.ctx.openKnowledge(); h.ctx.knowledgeEntityForm(null, 'topic');
  const dialog = all(h.body).find(e => e.tag === 'dialog'), form = all(dialog).find(e => e.tag === 'form');
  const fields = all(form).filter(e => e.tag === 'input' || e.tag === 'textarea');
  fields.forEach((field, index) => { field.value = ['Topic', 'Description', 'One\nTwo'][index]; });
  const pending = form.events.submit({ preventDefault() {} }); await flush();
  await form.events.submit({ preventDefault() {} });
  assert.equal(h.calls.filter(c => c.options.method === 'POST').length, 1);
  rejectSave(new Error('Try again')); await pending;
  assert.deepEqual(fields.map(field => field.value), ['Topic', 'Description', 'One\nTwo']);
  assert.equal(dialog.open, true); assert.equal(button(form, 'ontology.save').disabled, false);
});

test('ontology locale keys stay paired across English and Chinese', () => {
  const keys = lang => Object.keys(JSON.parse(fs.readFileSync(new URL(`../../main/resources/desktop-ui/locales/${lang}.json`, import.meta.url))))
    .filter(key => key.startsWith('ontology.')).sort();
  assert.deepEqual(keys('en'), keys('zh'));
});
