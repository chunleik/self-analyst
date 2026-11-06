import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

test('bundled Java runtime includes the OAuth callback HTTP server module', () => {
  const script = readFileSync(new URL('../../../../scripts/build-portable.ps1', import.meta.url), 'utf8');
  const modules = /\$modules\s*=\s*"([^"]+)"/.exec(script);
  assert.ok(modules, 'portable build must declare its runtime module set');
  assert.ok(modules[1].split(',').includes('jdk.httpserver'));
});
