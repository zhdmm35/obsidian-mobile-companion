import test from 'node:test';
import assert from 'node:assert/strict';
import { createSync } from '../src/git.js';

test('automatic triggers merge behind an active sync while manual requests keep their results', async () => {
  let flows = 0;
  let release;
  let started;
  const gate = new Promise((resolve) => { release = resolve; });
  const active = new Promise((resolve) => { started = resolve; });
  const runGit = async (args) => {
    if (args[0] === 'symbolic-ref') {
      flows++;
      if (flows === 1) { started(); await gate; }
      return { stdout: 'main' };
    }
    return { stdout: args[0] === 'rev-parse' ? (args.includes('@{u}') ? 'origin/main' : 'head') : '' };
  };
  const { syncOnce, idle } = createSync({ vaultPath: '.', runGit });
  const first = syncOnce('startup');
  await active;
  const automatic = Array.from({ length: 20 }, (_, i) => syncOnce(i % 2 ? 'periodic' : 'watcher'));
  const manual1 = syncOnce('manual');
  const manual2 = syncOnce('manual');
  release();
  await Promise.all([first, ...automatic, manual1, manual2]);
  await idle();
  assert.equal(flows, 4, 'startup + one automatic follow-up + two manual requests');
  assert.ok(automatic.every((promise) => promise === automatic[0]));
  assert.notEqual(manual1, manual2);
});

test('a failed automatic sync releases scheduling state for a later retry', async () => {
  let failed = true;
  const runGit = async (args) => {
    if (args[0] === 'symbolic-ref') return { stdout: 'main' };
    if (args[0] === 'fetch' && failed) throw new Error('network');
    return { stdout: args[0] === 'rev-parse' ? (args.includes('@{u}') ? 'origin/main' : 'head') : '' };
  };
  const { syncOnce } = createSync({ vaultPath: '.', runGit });
  await assert.rejects(syncOnce('watcher'), /network/);
  failed = false;
  assert.equal((await syncOnce('periodic')).committed, 0);
});
