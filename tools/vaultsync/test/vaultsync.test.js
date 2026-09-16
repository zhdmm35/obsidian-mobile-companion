import test from 'node:test';
import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdtemp, mkdir, rm, writeFile, readFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';

import { createSync, makeRunner, SyncError, gitErrorMessage } from '../src/git.js';
import { startWatcher } from '../src/watcher.js';

const pExecFile = promisify(execFile);
const sh = (cwd, args) => pExecFile('git', args, { cwd });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// 每个用例一套干净沙箱：bare remote + work 仓库（已 init commit、已 push -u origin main）
async function makeSandbox(t) {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'vaultsync-test-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const rem = path.join(dir, 'remote.git');
  const work = path.join(dir, 'work');
  const phone = path.join(dir, 'phone'); // 模拟手机：直接向 remote 推 commit

  await sh(dir, ['init', '--bare', '-b', 'main', 'remote.git']);
  await mkdir(work);
  await sh(work, ['init', '-b', 'main']);
  for (const [k, v] of [['user.email', 't@t'], ['user.name', 't'], ['commit.gpgsign', 'false']]) {
    await sh(work, ['config', k, v]);
  }
  await writeFile(path.join(work, 'a.md'), 'v1\n');
  await sh(work, ['add', '-A']);
  await sh(work, ['commit', '-m', 'init']);
  await sh(work, ['remote', 'add', 'origin', rem]);
  await sh(work, ['push', '-u', 'origin', 'main']);

  await mkdir(phone);
  await sh(phone, ['clone', rem, '.']);
  for (const [k, v] of [['user.email', 't@t'], ['user.name', 't'], ['commit.gpgsign', 'false']]) {
    await sh(phone, ['config', k, v]);
  }
  return { dir, rem, work, phone };
}

const rev = async (repo) => (await sh(repo, ['rev-parse', 'HEAD'])).stdout.trim();
const commitSubject = async (repo) => (await sh(repo, ['log', '-1', '--pretty=%s'])).stdout.trim();

const quietLog = () => {
  const lines = [];
  return Object.assign((...a) => lines.push(a.join(' ')), { lines });
};

test('no changes: no empty commit, up to date', async (t) => {
  const { work, rem } = await makeSandbox(t);
  const before = await rev(work);
  const log = quietLog();
  const { syncOnce } = createSync({ vaultPath: work, runGit: makeRunner(work), log });

  const res = await syncOnce('manual');

  assert.equal(res.committed, 0);
  assert.equal(res.pulled, false);
  assert.equal(await rev(work), before);
  assert.equal(await rev(rem), before);
  assert.ok(log.lines.some((l) => l.includes('Up to date')));
  assert.ok(!log.lines.some((l) => l.includes('Commit created')));
});

test('local change: single commit created and pushed', async (t) => {
  const { work, rem } = await makeSandbox(t);
  const log = quietLog();
  const { syncOnce } = createSync({ vaultPath: work, runGit: makeRunner(work), log });

  await writeFile(path.join(work, 'a.md'), 'v2\n');
  await writeFile(path.join(work, 'b.md'), 'new\n');
  const res = await syncOnce('manual');

  assert.equal(res.committed, 2);
  assert.equal(await rev(work), await rev(rem));
  const subject = await commitSubject(rem);
  assert.match(subject, /^vaultsync: update \d{4}-\d{2}-\d{2} \d{2}:\d{2}$/);
  assert.ok(log.lines.some((l) => l.includes('2 file(s) changed')));
  assert.ok(log.lines.some((l) => l.includes('Commit created')));
  assert.ok(log.lines.some((l) => l.includes('Push complete')));
});

test('remote ahead (phone commit): periodic pull via rebase fast-forward', async (t) => {
  const { work, phone } = await makeSandbox(t);
  await writeFile(path.join(phone, 'from-phone.md'), 'phone\n');
  await sh(phone, ['add', '-A']);
  await sh(phone, ['commit', '-m', 'mobile: update from-phone.md']);
  await sh(phone, ['push']);
  const remoteHead = await rev(phone);

  const { syncOnce } = createSync({ vaultPath: work, runGit: makeRunner(work) });
  const res = await syncOnce('periodic');

  assert.equal(res.pulled, true);
  assert.equal(await rev(work), remoteHead);
  const pulled = await readFile(path.join(work, 'from-phone.md'), 'utf8');
  assert.equal(pulled.trim(), 'phone'); // autocrlf 会把 LF 转成 CRLF，只比内容
});

test('rebase conflict: abort, keep local commit, never overwrite remote', async (t) => {
  const { work, phone, rem } = await makeSandbox(t);
  // 手机改同一行并先推
  await writeFile(path.join(phone, 'a.md'), 'remote-line\n');
  await sh(phone, ['commit', '-am', 'mobile: edit a.md']);
  await sh(phone, ['push']);
  // 本地在落后状态下也改同一行并 commit
  await writeFile(path.join(work, 'a.md'), 'local-line\n');
  await sh(work, ['commit', '-am', 'local: edit a.md']);
  const localHead = await rev(work);
  const remoteHead = await rev(phone);

  const { syncOnce } = createSync({ vaultPath: work, runGit: makeRunner(work) });
  await assert.rejects(syncOnce('manual'), (e) => {
    assert.ok(e instanceof SyncError);
    assert.equal(e.kind, 'conflict');
    assert.match(e.message, /同步遇到冲突/);
    return true;
  });

  // 本地 commit 保留、rebase 已 abort 干净、remote 未被覆盖
  assert.equal(await rev(work), localHead);
  assert.equal(await rev(rem), remoteHead);
  const st = (await sh(work, ['status'])).stdout;
  assert.match(st, /nothing to commit|no changes added to the index/);
  assert.ok(!st.includes('rebase in progress'));
  // 用户可手动解决：merge 取远端版本（merge 语义下 theirs=远端），双方历史都保留
  await sh(work, ['merge', '--no-edit', '-X', 'theirs', 'origin/main']);
  const resolved = await readFile(path.join(work, 'a.md'), 'utf8');
  assert.equal(resolved.trim(), 'remote-line');
  const subjects = (await sh(work, ['log', '--format=%s', 'HEAD'])).stdout;
  assert.match(subjects, /local: edit a\.md/); // 本地 commit 仍在历史中
});

test('git failure: sync rejects but can recover on next attempt', async (t) => {
  const { work } = await makeSandbox(t);
  const bad = path.join(path.dirname(work), 'no-such-remote.git');
  await sh(work, ['remote', 'set-url', 'origin', bad]);
  const { syncOnce } = createSync({ vaultPath: work, runGit: makeRunner(work) });

  await assert.rejects(syncOnce('periodic')); // 失败只 reject，由 safe() 兜住，进程不退出

  await sh(work, ['remote', 'set-url', 'origin', path.join(path.dirname(work), 'remote.git')]);
  const res = await syncOnce('periodic'); // 下一次触发恢复正常
  assert.equal(res.committed, 0);
});

test('mutex: concurrent syncs run strictly one at a time', async (t) => {
  const { work } = await makeSandbox(t);
  let running = 0;
  let maxRunning = 0;
  const slowRunner = async (args) => {
    running++;
    maxRunning = Math.max(maxRunning, running);
    await sleep(50);
    try {
      return await makeRunner(work)(args);
    } finally {
      running--;
    }
  };
  const { syncOnce } = createSync({ vaultPath: work, runGit: slowRunner });

  await writeFile(path.join(work, 'm1.md'), '1\n');
  const p1 = syncOnce('watcher');
  await writeFile(path.join(work, 'm2.md'), '2\n');
  const p2 = syncOnce('periodic');
  const [r1, r2] = await Promise.all([p1, p2]);

  assert.equal(maxRunning, 1, `git flows must never overlap (max=${maxRunning})`); // 绝不并行
  // m2 可能在第一个 sync 的 status 前或后写入；无论哪种交错，最终必须全部 commit 并推上去
  assert.equal(r1.committed + r2.committed, 2, `r1=${r1.committed} r2=${r2.committed}`);
  const st = (await sh(work, ['status', '--porcelain'])).stdout.trim();
  assert.equal(st, '', 'worktree clean after both syncs');
  const names = (await sh(work, ['ls-tree', '--name-only', 'origin/main'])).stdout;
  assert.match(names, /m1\.md/);
  assert.match(names, /m2\.md/);
});

test('pre-existing rebase: refuse to touch it, recover after manual abort', async (t) => {
  const { work, phone } = await makeSandbox(t);
  // 双方改同一行；用户手动 rebase，卡在冲突中
  await writeFile(path.join(phone, 'a.md'), 'remote-line\n');
  await sh(phone, ['commit', '-am', 'mobile: edit a.md']);
  await sh(phone, ['push']);
  await writeFile(path.join(work, 'a.md'), 'local-line\n');
  await sh(work, ['commit', '-am', 'local: edit a.md']);
  await sh(work, ['fetch', 'origin']);
  await assert.rejects(sh(work, ['rebase', 'origin/main'])); // 冲突，rebase 中断
  assert.match((await sh(work, ['status'])).stdout, /rebase in progress/);

  const { syncOnce } = createSync({ vaultPath: work, runGit: makeRunner(work) });
  // vaultsync 拒绝插手，且不动用户的 rebase 状态
  await assert.rejects(syncOnce('manual'), (e) => {
    assert.ok(e instanceof SyncError);
    assert.equal(e.kind, 'rebase-in-progress');
    return true;
  });
  assert.match((await sh(work, ['status'])).stdout, /rebase in progress/);

  // 用户 abort 后，vaultsync 恢复走正常冲突路径（本地 commit 保留、remote 不被覆盖）
  await sh(work, ['rebase', '--abort']);
  await assert.rejects(syncOnce('manual'), (e) => e.kind === 'conflict');
  assert.match((await sh(work, ['status'])).stdout, /nothing to commit/);
});

test('gitErrorMessage: masks credentials in remote URL, reports timeout', () => {
  const err = new Error('Command failed: git fetch origin');
  err.stderr = "fatal: unable to access 'https://ghp_secretTOKEN123@github.com/u/r.git/': Could not resolve host: github.com";
  const msg = gitErrorMessage(err);
  assert.ok(!msg.includes('ghp_secretTOKEN123'), `credential leaked into log: ${msg}`);
  assert.ok(msg.includes('https://***@github.com'));

  assert.match(gitErrorMessage({ killed: true, signal: 'SIGTERM' }), /超时/);
});

test('debounce: burst of file events fires exactly once', async (t) => {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'vaultsync-debounce-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  let fires = 0;
  const watcher = startWatcher({
    vaultPath: dir,
    debounceMs: 300,
    onChange: () => fires++,
  });
  await sleep(600); // 等 chokidar ready

  await writeFile(path.join(dir, 'x1.md'), '1\n');
  await sleep(120);
  await writeFile(path.join(dir, 'x2.md'), '2\n');
  await sleep(120);
  await writeFile(path.join(dir, 'x3.md'), '3\n'); // 持续重置计时

  // awaitWriteFinish 会额外延迟 ~600ms 才产生事件，给出宽松余量
  await sleep(2000);
  assert.equal(fires, 1, `expected 1 fire, got ${fires}`);
  await sleep(1000);
  assert.equal(fires, 1, 'must not fire again');

  await writeFile(path.join(dir, 'x4.md'), '4\n');
  await sleep(2000);
  assert.equal(fires, 2, `next burst fires again, got ${fires}`);

  await watcher.close();
});
