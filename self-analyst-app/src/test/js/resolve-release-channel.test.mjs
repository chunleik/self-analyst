import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve, dirname, basename } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const script = fileURLToPath(new URL('../../../../scripts/resolve-release-channel.ps1', import.meta.url));
const windowsOnly = { skip: process.platform !== 'win32' };
const modules = [
  'self-analyst-app',
  'self-analyst-content',
  'self-analyst-events',
  'self-analyst-file',
  'self-analyst-wiki',
];

function writeRepo(t, version, overrides = {}) {
  const root = mkdtempSync(join(tmpdir(), 'self-analyst-release-'));
  t.after(() => {
    assert.equal(dirname(resolve(root)), resolve(tmpdir()));
    assert.ok(basename(root).startsWith('self-analyst-release-'));
    rmSync(root, { recursive: true, force: true });
  });
  const value = name => (Object.prototype.hasOwnProperty.call(overrides, name) ? overrides[name] : version);
  writeFileSync(join(root, 'pom.xml'), `<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <version>${value('rootPom')}</version>
  <dependencies><dependency><version>9.9.9</version></dependency></dependencies>
</project>
`);
  for (const module of modules) {
    mkdirSync(join(root, module));
    writeFileSync(join(root, module, 'pom.xml'), `<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <parent><version>${value(module)}</version></parent>
  <dependencies><dependency><version>9.9.9</version></dependency></dependencies>
</project>
`);
  }
  mkdirSync(join(root, 'self-analyst-desktop', 'src-tauri'), { recursive: true });
  mkdirSync(join(root, 'self-analyst-axsidecar'));
  writeFileSync(join(root, 'self-analyst-desktop', 'package.json'), JSON.stringify({
    name: 'self-analyst-desktop',
    version: value('packageJson'),
    devDependencies: { '@tauri-apps/cli': '9.9.9' },
  }));
  writeFileSync(join(root, 'self-analyst-desktop', 'src-tauri', 'tauri.conf.json'), JSON.stringify({
    productName: 'SelfAnalyst',
    version: value('tauri'),
  }));
  writeCargo(join(root, 'self-analyst-desktop', 'src-tauri'), 'self-analyst-desktop', value('desktopCargo'), value('desktopLock'));
  writeCargo(join(root, 'self-analyst-axsidecar'), 'self-analyst-axsidecar', value('sidecarCargo'), value('sidecarLock'));
  return root;
}

function writeCargo(dir, name, cargoVersion, lockVersion) {
  writeFileSync(join(dir, 'Cargo.toml'), `[dependencies]
serde = "9.9.9"

[package]
name = "${name}"
version = "${cargoVersion}"

[[bin]]
name = "not-the-package"
`);
  writeFileSync(join(dir, 'Cargo.lock'), `[[package]]
name = "serde"
version = "9.9.9"

[[package]]
name = "${name}"
version = "${lockVersion}"
dependencies = [
 "serde",
]
`);
}

function run(tag, root) {
  const command = '[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false); '
    + '& $env:SELF_ANALYST_TEST_RELEASE_SCRIPT -Tag $env:SELF_ANALYST_TEST_RELEASE_TAG -RepoRoot $env:SELF_ANALYST_TEST_RELEASE_ROOT';
  const result = spawnSync('pwsh', [
    '-NoLogo', '-NoProfile', '-NonInteractive', '-EncodedCommand', Buffer.from(command, 'utf16le').toString('base64'),
  ], {
    encoding: 'utf8', windowsHide: true, timeout: 15000,
    env: {
      ...process.env,
      SELF_ANALYST_TEST_RELEASE_SCRIPT: script,
      SELF_ANALYST_TEST_RELEASE_TAG: tag,
      SELF_ANALYST_TEST_RELEASE_ROOT: root,
    },
  });
  assert.ifError(result.error);
  return result;
}

test('accepts a stable tag when every project version matches', windowsOnly, t => {
  const result = run('v0.5.0', writeRepo(t, '0.5.0'));
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.stdout.trim(), 'stable');
});

test('accepts beta and rc tags when project versions use the same prerelease', windowsOnly, t => {
  for (const [tag, version] of [['v0.5.0-beta.1', '0.5.0-beta.1'], ['v0.5.0-rc.2', '0.5.0-rc.2'], ['v0.5.0-beta.10', '0.5.0-beta.10']]) {
    const result = run(tag, writeRepo(t, version));
    assert.equal(result.status, 0, result.stderr);
    assert.equal(result.stdout.trim(), 'prerelease');
  }
});

for (const tag of ['v01.2.3', 'v0.05.0', 'v0.5.0-beta.0', 'v0.5.0-beta.01', 'v0.5.0-alpha.1', 'v0.5', 'v0.5.0-beta', 'v1']) {
  test(`rejects unsupported tag ${tag}`, windowsOnly, t => {
    const result = run(tag, writeRepo(t, '0.5.0'));
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /无法识别的发布标签/);
  });
}

for (const field of ['rootPom', ...modules, 'packageJson', 'tauri', 'desktopCargo', 'desktopLock', 'sidecarCargo', 'sidecarLock']) {
  test(`rejects a mismatched ${field} version`, windowsOnly, t => {
    const result = run('v0.5.0', writeRepo(t, '0.5.0', { [field]: '9.9.9' }));
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /项目版本与标签不一致/);
  });
}
