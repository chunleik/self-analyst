import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, readdirSync, rmSync, realpathSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve, dirname, basename } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const script = fileURLToPath(new URL('../../../../scripts/resolve-app-jar.ps1', import.meta.url));
const windowsOnly = { skip: process.platform !== 'win32' };

// CI 的 TEMP 可能是 8.3 短名（RUNNER~1），而 PowerShell 输出解析后的长名；
// 两端都取最终长路径后再比较。
const finalPath = p => realpathSync.native(p);

function fixture(t, files) {
  const root = mkdtempSync(join(tmpdir(), 'self-analyst-jar-'));
  t.after(() => {
    assert.equal(dirname(resolve(root)), resolve(tmpdir()));
    assert.ok(basename(root).startsWith('self-analyst-jar-'));
    rmSync(root, { recursive: true, force: true });
  });
  const target = join(root, '构建 产物');
  mkdirSync(target);
  for (const [name, content] of Object.entries(files)) writeFileSync(join(target, name), content);
  return target;
}

function run(target) {
  // Force a predictable pipe encoding without interpolating filesystem paths
  // into PowerShell code (the paths may contain spaces or non-ASCII text).
  const command = "[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false); "
    + "& $env:SELF_ANALYST_TEST_RESOLVER_SCRIPT -TargetDir $env:SELF_ANALYST_TEST_RESOLVER_TARGET";
  const result = spawnSync('pwsh', [
    '-NoLogo', '-NoProfile', '-NonInteractive', '-EncodedCommand', Buffer.from(command, 'utf16le').toString('base64'),
  ], {
    encoding: 'utf8', windowsHide: true, timeout: 15000,
    env: { ...process.env, SELF_ANALYST_TEST_RESOLVER_SCRIPT: script, SELF_ANALYST_TEST_RESOLVER_TARGET: target },
  });
  assert.ifError(result.error);
  return result;
}

test('resolves the formal JAR and excludes original artifacts', windowsOnly, t => {
  const target = fixture(t, {
    'self-analyst-app-0.2.8.jar': 'formal',
    'original-self-analyst-app-0.2.8.jar': 'unshaded',
  });
  const result = run(target);
  assert.equal(result.status, 0, result.stderr);
  assert.equal(finalPath(result.stdout.trim()), finalPath(join(target, 'self-analyst-app-0.2.8.jar')));
});

for (const shaded of ['formal', 'different intermediate bytes']) {
  test(`ignores shaded artifacts without modifying files (${shaded})`, windowsOnly, t => {
    const files = { 'self-analyst-app-0.2.8.jar': 'formal', 'self-analyst-app-0.2.8-shaded.jar': shaded };
    const target = fixture(t, files);
    const result = run(target);
    assert.equal(result.status, 0, result.stderr);
    assert.equal(finalPath(result.stdout.trim()), finalPath(join(target, 'self-analyst-app-0.2.8.jar')));
    assert.deepEqual(readdirSync(target).sort(), Object.keys(files).sort());
    for (const [name, content] of Object.entries(files)) assert.equal(readFileSync(join(target, name), 'utf8'), content);
  });
}

test('handles snapshot versions without hardcoding the release version', windowsOnly, t => {
  const target = fixture(t, {
    'self-analyst-app-1.0.0-SNAPSHOT.jar': 'formal',
    'self-analyst-app-1.0.0-SNAPSHOT-shaded.jar': 'intermediate',
  });
  const result = run(target);
  assert.equal(result.status, 0, result.stderr);
  assert.equal(finalPath(result.stdout.trim()), finalPath(join(target, 'self-analyst-app-1.0.0-SNAPSHOT.jar')));
});

test('rejects multiple formal versions instead of choosing one', windowsOnly, t => {
  const target = fixture(t, {
    'self-analyst-app-0.2.8.jar': 'current',
    'self-analyst-app-0.2.7.jar': 'old',
    'self-analyst-app-0.2.8-shaded.jar': 'intermediate',
  });
  const result = run(target);
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /self-analyst-app-0\.2\.7\.jar/);
  assert.match(result.stderr, /self-analyst-app-0\.2\.8\.jar/);
  assert.equal(result.stdout.trim(), '');
});

test('rejects a directory containing only an intermediate JAR', windowsOnly, t => {
  const result = run(fixture(t, { 'self-analyst-app-0.2.8-shaded.jar': 'intermediate' }));
  assert.notEqual(result.status, 0);
  assert.equal(result.stdout.trim(), '');
});

test('rejects an empty output directory', windowsOnly, t => {
  const result = run(fixture(t, {}));
  assert.notEqual(result.status, 0);
  assert.equal(result.stdout.trim(), '');
});

test('rejects a missing output directory', windowsOnly, t => {
  const result = run(join(fixture(t, {}), 'missing'));
  assert.notEqual(result.status, 0);
  assert.equal(result.stdout.trim(), '');
});
