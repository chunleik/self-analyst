import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = name => fs.readFileSync(new URL(`../../main/resources/desktop-ui/${name}`, import.meta.url), 'utf8');
const styles = read('ontology.css');
const script = read('ontology.js');

function cssRule(selector) {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const match = styles.match(new RegExp('(?:^|[\\n}])\\s*' + escaped + '\\s*\\{([^}]*)\\}'));
  assert.ok(match, `${selector} CSS rule should exist`);
  return match[1];
}

test('knowledge styles only use design tokens defined by the shared theme', () => {
  const root = read('styles.css').match(/:root\s*\{([\s\S]*?)\}/)[1];
  const defined = new Set([...root.matchAll(/(--[\w-]+)\s*:/g)].map(m => m[1]));
  const used = new Set([...styles.matchAll(/var\((--[\w-]+)/g)].map(m => m[1]));
  for (const name of used) assert.ok(defined.has(name), `${name} is not defined in styles.css`);
  assert.doesNotMatch(styles, /#[0-9a-fA-F]{3,8}\b/, 'colors must come from theme variables');
});

test('every knowledge class set by the script has a style rule', () => {
  const ids = new Set(['knowledge-root', 'knowledge-message', 'knowledge-dialog-title']);
  const classes = new Set([...script.matchAll(/"((?:btn [\w -]*)?knowledge-[\w -]+)"/g)]
    .flatMap(m => m[1].split(' ')).filter(name => name.startsWith('knowledge-') && !ids.has(name)));
  assert.ok(classes.size > 10);
  for (const name of classes) assert.ok(styles.includes('.' + name), `.${name} has no style rule`);
});

test('knowledge dialog is centered despite the global margin reset', () => {
  const dialog = cssRule('.knowledge-dialog');
  assert.match(dialog, /position:\s*fixed/); assert.match(dialog, /inset:\s*0/); assert.match(dialog, /margin:\s*auto/);
});

test('entity list scrolls independently and stacks on narrow windows', () => {
  assert.match(cssRule('.knowledge-list-panel'), /position:\s*sticky/);
  assert.match(cssRule('.knowledge-list'), /overflow-y:\s*auto/);
  const narrow = styles.slice(styles.indexOf('@media (max-width: 650px)'));
  assert.match(narrow, /\.knowledge-layout\s*\{\s*grid-template-columns:\s*1fr/);
  assert.match(narrow, /\.knowledge-list-panel\s*\{[^}]*position:\s*static/);
});

test('selected and keyboard focus states stay visible', () => {
  assert.match(cssRule('.knowledge-entity.selected'), /background:\s*var\(--accent-soft\)/);
  assert.match(styles, /\.knowledge-page button:focus-visible[^{]*\{[^}]*outline:\s*2px solid var\(--accent\)/);
});
