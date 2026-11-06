import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { clickSummaryRepairButton } from '../../../../scripts/check-summary-repair-ui.mjs';

test('repair smoke waits for an absent and disabled button, then clicks exactly once', async () => {
  let attempts = 0, clicks = 0;
  const button = { textContent: 'Validate locally', disabled: true, click: () => clicks++ };
  const evaluate = async expression => {
    attempts++;
    button.disabled = attempts < 3;
    const fixtureRoot = attempts === 1 ? null : { querySelectorAll: () => [button] };
    return vm.runInNewContext(expression, { fixtureRoot });
  };
  assert.equal(await clickSummaryRepairButton(evaluate, 'fixtureRoot', 'Validate locally', 2000), true);
  assert.equal(attempts, 3);
  assert.equal(clicks, 1);
});

test('repair smoke still fails when the requested button never becomes actionable', async () => {
  let clicks = 0;
  const evaluate = async expression => vm.runInNewContext(expression, {
    fixtureRoot: { querySelectorAll: () => [
      { textContent: 'Validate locally', disabled: true, click: () => clicks++ },
      { textContent: 'Another action', disabled: false, click: () => clicks++ },
    ] },
  });
  await assert.rejects(clickSummaryRepairButton(evaluate, 'fixtureRoot', 'Validate locally', 1),
    /Timed out: enabled button: Validate locally/);
  assert.equal(clicks, 0);
});
