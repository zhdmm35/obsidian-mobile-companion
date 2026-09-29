import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, rm, writeFile, readFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { repositoryUrl, prepareVault, saveVault, git } from '../src/onboarding.js';

test('repository address rejects credentials, non-GitHub and extra URL components', () => {
  assert.equal(repositoryUrl('https://github.com/user/notes/'), 'https://github.com/user/notes');
  for (const url of ['https://secret@github.com/user/notes', 'https://example.com/u/r', 'http://github.com/u/r', 'https://github.com/u/r?token=x', 'https://github.com/u/r/tree/main']) {
    assert.throws(() => repositoryUrl(url));
  }
});

async function sandbox(t) {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'vaultsync-onboarding-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  await git(dir, ['init', '--bare', '-b', 'main', 'remote.git']);
  const work = path.join(dir, 'notes');
  await mkdir(work);
  await git(work, ['init', '-b', 'main']);
  await git(work, ['remote', 'add', 'origin', path.join(dir, 'remote.git')]);
  return { dir, work };
}

test('existing repository: local identity and config saved without committing notes', async t => {
  const { dir, work } = await sandbox(t);
  await writeFile(path.join(work, 'note.md'), 'private test note');
  const file = path.join(dir, 'config.json');
  const summary = await saveVault(file, { vaultPath: work, name: 'Test User', email: 'test@example.com' });
  assert.equal(summary.branch, 'main');
  assert.equal(JSON.parse(await readFile(file, 'utf8')).vaultPath, work.replace(/\\/g, '/'));
  assert.equal(await git(work, ['config', '--local', 'user.email']), 'test@example.com');
  assert.match(await git(work, ['status', '--porcelain']), /\?\? note.md/);
  await assert.rejects(git(work, ['rev-parse', 'HEAD']));
});

test('remote content without upstream is rejected before configuration is written', async t => {
  const { dir, work } = await sandbox(t);
  await git(work, ['config', 'user.name', 'Test']);
  await git(work, ['config', 'user.email', 'test@example.com']);
  await writeFile(path.join(work, 'note.md'), 'test');
  await git(work, ['add', '.']);
  await git(work, ['-c', 'commit.gpgsign=false', 'commit', '--no-verify', '-m', 'test']);
  await git(work, ['push', 'origin', 'main']);
  const file = path.join(dir, 'config.json');
  await assert.rejects(saveVault(file, { vaultPath: work }), /尚未关联/);
  await assert.rejects(readFile(file), /ENOENT/);
  await git(work, ['branch', '--set-upstream-to=origin/main']);
  assert.equal((await prepareVault({ vaultPath: work })).branch, 'main');
});

test('invalid and mismatched destinations do not change existing remote', async t => {
  const { work } = await sandbox(t);
  await assert.rejects(prepareVault({ vaultPath: 'relative' }), /完整路径/);
  await assert.rejects(prepareVault({ vaultPath: work, repository: 'https://github.com/other/notes', name: 'Test', email: 't@t' }), /另一个仓库/);
  assert.match(await git(work, ['remote', 'get-url', 'origin']), /remote\.git$/);
});

test('new local notes initialize against empty remote; remote notes clone into empty folder', async t => {
  const { dir, work } = await sandbox(t);
  const remote = path.join(dir, 'remote.git');
  const local = path.join(dir, 'local');
  await mkdir(local);
  await writeFile(path.join(local, 'local.md'), 'local');
  const deps = { parseUrl: value => value };
  await prepareVault({ vaultPath: local, repository: remote, name: 'Test', email: 'test@example.com' }, deps);
  assert.equal(await git(local, ['remote', 'get-url', 'origin']), remote);
  assert.match(await git(local, ['status', '--porcelain']), /local.md/);
  await git(work, ['config', 'user.name', 'Test']);
  await git(work, ['config', 'user.email', 'test@example.com']);
  await writeFile(path.join(work, 'remote.md'), 'remote');
  await git(work, ['add', '.']);
  await git(work, ['-c', 'commit.gpgsign=false', 'commit', '--no-verify', '-m', 'test']);
  await git(work, ['push', '-u', 'origin', 'main']);
  const download = path.join(dir, 'download');
  await mkdir(download);
  await prepareVault({ vaultPath: download, repository: remote, mode: 'download', name: 'Test', email: 'test@example.com' }, deps);
  assert.equal(await readFile(path.join(download, 'remote.md'), 'utf8'), 'remote');
  const occupied = path.join(dir, 'occupied');
  await mkdir(occupied);
  await writeFile(path.join(occupied, 'keep.md'), 'keep');
  await assert.rejects(prepareVault({ vaultPath: occupied, repository: remote }, deps), /已有内容/);
  await assert.rejects(prepareVault({ vaultPath: occupied, repository: remote, mode: 'download' }, deps), /空文件夹/);
  assert.equal(await readFile(path.join(occupied, 'keep.md'), 'utf8'), 'keep');
});
