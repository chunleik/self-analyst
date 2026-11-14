import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const base = new URL('../../main/resources/desktop-ui/', import.meta.url);
const source = fs.readFileSync(new URL('integration-tools-dialog.js', base), 'utf8');
function setup() {
  let document;
  class Element {
    constructor(tag) { this.tagName = tag.toUpperCase(); this.children = []; this.events = {}; this.attributes = {}; this.textContent = ''; this.open = false; this.disabled = false; this.isConnected = true; this.tabIndex = ['BUTTON', 'SUMMARY', 'INPUT', 'SELECT', 'TEXTAREA'].includes(this.tagName) ? 0 : -1; }
    appendChild(child) { this.children.push(child); child.parent = this; return child; }
    replaceChildren(...children) { this.children = children; }
    contains(node) { return this === node || this.children.some(child => child.contains(node)); }
    querySelectorAll() { return this.children.flatMap(child => [child, ...child.querySelectorAll()]).filter(node => node.tabIndex >= 0); }
    getClientRects() {
      for (let node = this; node; node = node.parent) {
        if (node.hidden || node.parent?.tagName === 'DETAILS' && !node.parent.open && node.tagName !== 'SUMMARY') return [];
      }
      return [{}];
    }
    setAttribute(key, value) { this.attributes[key] = value; }
    addEventListener(type, listener) { (this.events[type] ||= []).push(listener); }
    dispatch(type, extras = {}) { const event = { prevented: false, stopped: false, preventDefault() { this.prevented = true; }, stopPropagation() { this.stopped = true; }, ...extras }; for (const cb of this.events[type] || []) cb(event); return event; }
    focus() { document.activeElement = this; }
    showModal() { this.open = true; }
    close() { this.open = false; this.dispatch('close'); }
    remove() { this.isConnected = false; if (this.parent) this.parent.children = this.parent.children.filter(v => v !== this); }
  }
  document = { createElement: tag => new Element(tag), body: new Element('body'), activeElement: null };
  let discovers = 0, closes = 0, discoverHandler = () => {};
  const context = vm.createContext({ document, t: key => key }); vm.runInContext(source, context);
  const dialog = context.createIntegrationToolsDialog({ onDiscover: () => { discovers++; return discoverHandler(); }, onClose: () => closes++ });
  const root = document.body.children[0], trigger = new Element('button');
  const nodes = (el = root) => [el, ...el.children.flatMap(c => nodes(c))];
  const button = key => nodes().find(el => el.tagName === 'BUTTON' && el.textContent === 'integrations.catalog.' + key);
  const text = () => nodes().map(el => el.textContent).join('\n');
  return { dialog, root, trigger, document, nodes, button, text, counts: () => ({ discovers, closes }), setDiscover: handler => { discoverHandler = handler; } };
}
const view = (changes = {}) => ({ id: 'one', kind: 'mcp', name: 'Synthetic MCP', endpoint: 'https://example.com/mcp', state: 'not_connected', known: false, tools: [], busy: false, canDiscover: true, hint: '', error: '', ...changes });
const flush = () => new Promise(resolve => setImmediate(resolve));

test('open is a read-only native modal with accessible name and focus; empty differs from undiscovered', () => {
  const h = setup(); h.dialog.open(view(), h.trigger);
  assert.equal(h.root.open, true); assert.equal(h.root.attributes['aria-label'], 'integrations.catalog.title');
  assert.equal(h.document.activeElement, h.button('close')); assert.match(h.text(), /Synthetic MCP/);
  assert.match(h.text(), /undiscovered/); assert.doesNotMatch(h.text(), /catalog.empty/); assert.equal(h.counts().discovers, 0);
  h.dialog.update(view({ known: true, state: 'ready' }));
  assert.match(h.text(), /catalog.empty/); assert.match(h.text(), /catalog.cached/); assert.doesNotMatch(h.text(), /undiscovered/);
  assert.equal(h.counts().discovers, 0);
});

test('tool content and schemas remain plain text and approval never executes anything', () => {
  const h = setup(); const hostile = '<img src=x onerror=alert(1)>';
  h.dialog.open(view({ known: true, tools: [{ name: hostile, description: '<script>steal()</script>', inputSchema: { type: 'object', properties: { arg: { description: hostile } } }, approved: true }, { name: 'second', approved: false }] }), h.trigger);
  assert.match(h.text(), /<img src=x/); assert.match(h.text(), /<script>/);
  assert.equal(h.nodes().some(n => n.tagName === 'IMG' || n.tagName === 'SCRIPT'), false);
  assert.match(h.text(), /catalog.approved/); assert.match(h.text(), /catalog.unapproved/);
  assert.equal(h.nodes().filter(n => n.tagName === 'DETAILS').length, 2);
  assert.equal(h.nodes().filter(n => n.tagName === 'BUTTON').length, 2);
  assert.deepEqual(h.counts(), { discovers: 0, closes: 0 });
  assert.doesNotMatch(source, /innerHTML|outerHTML|insertAdjacentHTML/);
});

test('only explicit discovery calls owner, duplicates lock, errors stay safe and retry unlocks', async () => {
  const h = setup(); let reject; h.setDiscover(() => new Promise((_, fail) => { reject = fail; }));
  h.dialog.open(view(), h.trigger); h.button('discover').dispatch('click'); h.button('discover').dispatch('click');
  await flush(); assert.equal(h.counts().discovers, 1); assert.equal(h.button('discover').disabled, true);
  reject(new Error('secret remote detail')); await flush();
  assert.match(h.text(), /catalog.failed/); assert.doesNotMatch(h.text(), /secret remote/); assert.equal(h.button('discover').disabled, false);
  h.dialog.update(view({ canDiscover: false, hint: 'Save first' })); h.button('discover').dispatch('click'); await flush();
  assert.equal(h.counts().discovers, 1); assert.match(h.text(), /Save first/);
});

test('Escape and close notify once, return focus, and closed updates cannot reopen modal', () => {
  const h = setup(); h.dialog.open(view(), h.trigger);
  assert.equal(h.root.dispatch('cancel').prevented, true); assert.equal(h.root.open, false);
  assert.equal(h.document.activeElement, h.trigger); assert.equal(h.counts().closes, 1);
  h.dialog.close(); h.dialog.update(view({ known: true })); assert.equal(h.root.open, false); assert.equal(h.counts().closes, 1);
  h.dialog.open(view(), h.trigger); h.button('close').dispatch('click'); assert.equal(h.counts().closes, 2);
});

test('identity change cancels old view and late discovery cannot corrupt a reopened connection', async () => {
  const h = setup(); let reject; h.setDiscover(() => new Promise((_, fail) => { reject = fail; }));
  h.dialog.open(view(), h.trigger); h.button('discover').dispatch('click'); await flush();
  h.dialog.update(view({ id: 'other' })); assert.equal(h.root.open, false); assert.equal(h.counts().closes, 1);
  h.dialog.open(view({ id: 'other', name: 'Other connection' }), h.trigger);
  reject(new Error('old failure')); await flush();
  assert.equal(h.root.open, true); assert.match(h.text(), /Other connection/); assert.doesNotMatch(h.text(), /catalog.failed/);
  assert.equal(h.button('discover').disabled, false);
});

test('destroy cancels once and releases DOM; close before discovery dispatch prevents request', async () => {
  const h = setup(); h.dialog.open(view(), h.trigger); h.button('discover').dispatch('click'); h.dialog.destroy();
  await flush(); assert.deepEqual(h.counts(), { discovers: 0, closes: 1 }); assert.equal(h.document.body.children.length, 0);
  h.dialog.open(view(), h.trigger); h.dialog.destroy(); assert.equal(h.counts().closes, 1);
});

test('mail is not misrepresented as an external MCP tools connection and unknown states are safe', () => {
  const h = setup(); h.dialog.open(view({ kind: 'mail' }), h.trigger); assert.equal(h.root.open, false);
  h.dialog.open(view({ kind: 'legacy', state: '<remote invalid status>' }), h.trigger);
  assert.match(h.text(), /integrations.state.unavailable/); assert.doesNotMatch(h.text(), /remote invalid status/);
});

test('catalog locale keys exist in both languages and layout supports narrow screens', () => {
  const en = JSON.parse(fs.readFileSync(new URL('locales/en.json', base))), zh = JSON.parse(fs.readFileSync(new URL('locales/zh.json', base)));
  for (const match of source.matchAll(/"(integrations\.catalog\.[^"]+)"/g)) { assert.ok(en[match[1]], match[1]); assert.ok(zh[match[1]], match[1]); }
  const css = fs.readFileSync(new URL('integrations.css', base), 'utf8');
  assert.match(css, /integration-tools-dialog::backdrop/); assert.match(css, /100dvh/); assert.match(css, /focus-visible/);
});


test('Escape key does not bubble to the underlying settings shortcut', () => {
  const h = setup(); h.dialog.open(view(), h.trigger);
  const event = h.root.dispatch('keydown', { key: 'Escape' });
  assert.equal(event.stopped, true); assert.equal(event.prevented, true);
  assert.equal(h.root.open, false); assert.equal(h.counts().closes, 1);
});

test('failed discovery can display previous tools explicitly marked stale', () => {
  const h = setup(); h.dialog.open(view({ known: false, state: 'degraded', error: 'Safe failure', tools: [{ name: 'previousTool' }] }), h.trigger);
  assert.match(h.text(), /catalog.stale/); assert.match(h.text(), /previousTool/); assert.match(h.text(), /Safe failure/);
  assert.doesNotMatch(h.text(), /catalog.empty/);
});


test('Tab and Shift+Tab explicitly cycle within modal and skip collapsed schemas and disabled actions', () => {
  const h = setup(); h.dialog.open(view({known: true, tools: [{name: 'one', inputSchema: {type: 'object'}}]}), h.trigger);
  const summary = h.nodes().find(node => node.tagName === 'SUMMARY');
  const details = h.nodes().find(node => node.tagName === 'DETAILS');
  const schema = h.nodes().find(node => node.tagName === 'PRE');
  const next = (shiftKey = false) => {
    const event = h.root.dispatch('keydown', {key: 'Tab', shiftKey});
    assert.equal(event.prevented, true); assert.equal(event.stopped, true);
    assert.equal(h.root.contains(h.document.activeElement), true);
    return h.document.activeElement;
  };
  assert.equal(next(), summary); // Wrap from Close instead of leaving for browser chrome.
  assert.equal(next(true), h.button('close'));
  summary.focus(); assert.equal(next(), h.button('refresh')); // Closed schema is not tabbable.
  details.open = true; summary.focus(); assert.equal(next(), schema);
  assert.equal(next(), h.button('refresh')); assert.equal(next(), h.button('close'));
  h.button('refresh').disabled = true; schema.focus(); assert.equal(next(), h.button('close'));
  assert.equal(next(true), schema);
  h.document.activeElement = h.root; assert.equal(next(), summary);
  for (let i = 0; i < 24; i++) next(i % 2 === 0);
});

test('directory refresh recovers removed tool focus before subsequent Tab', () => {
  const h = setup(); h.dialog.open(view({known: true, tools: [{name: 'one'}]}), h.trigger);
  h.nodes().find(node => node.tagName === 'SUMMARY').focus();
  h.dialog.update(view({known: true, tools: []}));
  assert.equal(h.document.activeElement, h.button('close'));
  h.root.dispatch('keydown', {key: 'Tab'});
  assert.equal(h.document.activeElement, h.button('refresh'));
});
