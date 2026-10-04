import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const config = fs.readFileSync(new URL('../../../../.github/dependabot.yml', import.meta.url), 'utf8');
const entries = config.split(/\n  - package-ecosystem:/).slice(1);

test('every Dependabot update entry groups patch updates only', () => {
  assert.equal(entries.length, 5);
  const names = entries.map(entry => {
    const group = entry.match(/\n    groups:\n      ([\w-]+):\n((?:        .*\n?)+)/);
    assert.ok(group, 'update entry has no groups block:' + entry.split('\n')[0]);
    assert.match(group[2], /applies-to: version-updates/);
    assert.match(group[2], /update-types: \["patch"\]/, 'minor and major updates must stay ungrouped');
    assert.equal(entry.match(/\n      [\w-]+:\n/g).length, 1, 'only the patch group is expected');
    return group[1];
  });
  assert.equal(new Set(names).size, names.length, 'group names must be unique');
});
