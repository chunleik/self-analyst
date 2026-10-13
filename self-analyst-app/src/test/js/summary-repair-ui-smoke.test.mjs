import test from 'node:test';
import assert from 'node:assert/strict';
import { checkSummaryRepairUi } from '../../../../scripts/check-summary-repair-ui.mjs';

// The existing Windows Maven/Node CI step runs this without workflow changes.
// Local and non-Windows runs never launch a browser or download dependencies.
function windowsGitHubCi(platform, environment) {
  return platform === 'win32' && environment.GITHUB_ACTIONS === 'true';
}

test('synthetic UI smoke platform gate only enables Windows GitHub Actions', () => {
  assert.equal(windowsGitHubCi('win32', { GITHUB_ACTIONS: 'true' }), true);
  assert.equal(windowsGitHubCi('win32', {}), false);
  assert.equal(windowsGitHubCi('win32', { CI: 'true' }), false);
  assert.equal(windowsGitHubCi('win32', { GITHUB_ACTIONS: 'false' }), false);
  assert.equal(windowsGitHubCi('linux', { GITHUB_ACTIONS: 'true' }), false);
  assert.equal(windowsGitHubCi('darwin', { GITHUB_ACTIONS: 'true' }), false);
});

test('synthetic repair UI in existing Windows CI Edge', {
  skip: windowsGitHubCi(process.platform, process.env) ? false : 'Requires Windows GitHub Actions and its installed Edge',
  timeout: 240000,
}, async () => {
  // Missing Edge is a failure on CI, not a silently skipped visual check.
  await checkSummaryRepairUi();
});
