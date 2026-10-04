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
  replaceChildren(...items) { this.children = []; if (this.tag === 'select') this.value = ''; this.append(...items); }
  setAttribute(key, value) { this[key] = value; }
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
  const ctx = vm.createContext({ URLSearchParams, Date, state: { dateLocale: 'en-US' }, API_BASE: '',
    window: { confirm: () => true }, t: key => key,
    document: { body, createElement: tag => new Element(tag), getElementById: id => all(body).find(e => e.id === id) },
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
  const h = setup(); await h.ctx.openKnowledge();
  await h.ctx.knowledgeDecision({ subject: 'a', predicate: 'relatedTo', object: 'p' }, 'confirm');
  const call = h.calls.find(c => c.options.method === 'POST');
  assert.deepEqual(JSON.parse(call.options.body), { subject: 'a', predicate: 'relatedTo', object: 'p', action: 'confirm' });
  assert.equal(call.options.credentials, 'same-origin'); assert.match(h.root.textContent, /ontology.saved/);
});

test('knowledge labels and dynamic vocabulary are present in both languages', () => {
  const dynamic = ['type.project','type.activity','type.topic','type.application','type.goal','type.pattern','type.improvement',
    'predicate.relatedTo','predicate.uses','predicate.about','predicate.supports','predicate.tracks','claim.observed','claim.inferred','claim.confirmed','claim.legacy',
    'classification.assigned','classification.unclassified','classification.ambiguous','status.current','status.unavailable','status.truncated'];
  const keys = [...source.matchAll(/"(ontology\.[\w.]+)"/g)].map(m => m[1]).filter(k => !k.endsWith('.')).concat(dynamic.map(k => 'ontology.' + k));
  for (const lang of ['en','zh']) {
    const catalog = JSON.parse(fs.readFileSync(new URL(`../../main/resources/desktop-ui/locales/${lang}.json`, import.meta.url), 'utf8'));
    for (const key of keys) assert.equal(typeof catalog[key], 'string', key);
  }
});
