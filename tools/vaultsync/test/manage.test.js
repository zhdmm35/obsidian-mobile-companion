import test from 'node:test';
import assert from 'node:assert/strict';
import { cp, mkdtemp, mkdir, readFile, writeFile, rm, symlink } from 'node:fs/promises';
import { execFile, spawn } from 'node:child_process';
import { promisify } from 'node:util';
import os from 'node:os';
import path from 'node:path';
import { TOOL_DIR } from '../src/config.js';
import { git } from '../src/onboarding.js';
const exec = promisify(execFile);
const sleep = ms => new Promise(r => setTimeout(r, ms));

async function sandbox(t) {
  const root = await mkdtemp(path.join(os.tmpdir(), 'vaultsync-management-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const tool = path.join(root, 'tool');
  await mkdir(tool);
  await cp(path.join(TOOL_DIR, 'src'), path.join(tool, 'src'), { recursive: true });
  await cp(path.join(TOOL_DIR, 'package.json'), path.join(tool, 'package.json'));
  await symlink(path.join(TOOL_DIR, 'node_modules'), path.join(tool, 'node_modules'), 'junction');
  await git(root, ['init', '--bare', '-b', 'main', 'remote.git']);
  const vault = path.join(root, 'notes');
  await mkdir(vault);
  await git(vault, ['init', '-b', 'main']);
  await git(vault, ['config', 'user.name', 'Test']);
  await git(vault, ['config', 'user.email', 'test@example.com']);
  await git(vault, ['remote', 'add', 'origin', path.join(root, 'remote.git')]);
  await writeFile(path.join(vault, 'note.md'), 'initial');
  await writeFile(path.join(tool, 'vaultsync.config.json'), JSON.stringify({ vaultPath: vault, debounceSeconds: 30, pollMinutes: 5 }));
  const run = async action => JSON.parse((await exec(process.execPath, [path.join(tool, 'src/manage.js'), action], { timeout: 45000 })).stdout).data;
  return { root, tool, vault, run };
}

test('single sync reports actual failure through exit code and persisted result', async t => {
  const { tool, vault } = await sandbox(t);
  await git(vault, ['remote', 'set-url', 'origin', path.join(tool, 'missing.git')]);
  await assert.rejects(exec(process.execPath, [path.join(tool, 'src/index.js'), '--once']), e => e.code === 1);
  const result = JSON.parse(await readFile(path.join(tool, 'vaultsync.result.json'), 'utf8'));
  assert.equal(result.ok, false);
  assert.equal(result.vaultPath, vault);
  assert.match(result.error, /repository|read from remote/);
});

test('daemon management: manual sync uses existing daemon, pause/resume, graceful stop', async t => {
  const { tool, vault, run } = await sandbox(t);
  const child = spawn(process.execPath, [path.join(tool, 'src/index.js')], { stdio: 'ignore', windowsHide: true });
  const exited = new Promise(resolve => child.on('exit', resolve));
  t.after(async () => { if (child.exitCode === null) { child.kill(); await exited; } });
  for (let i = 0; i < 100; i++) {
    try { if (JSON.parse(await readFile(path.join(tool, 'vaultsync.result.json'), 'utf8')).ok) break; } catch { /* 等启动 */ }
    await sleep(100);
  }
  assert.equal((await run('status')).result.ok, true);
  await run('pause');
  await writeFile(path.join(vault, 'note.md'), 'manual change');
  await assert.rejects(run('sync'), e => /恢复自动同步/.test(e.stdout));
  await run('resume');
  const synced = await run('sync');
  assert.equal(synced.result.ok, true);
  assert.equal(synced.result.changed, 1);
  assert.equal(await git(vault, ['status', '--porcelain']), '');
  const stopped = await run('stop');
  assert.equal(stopped.running, false);
  assert.equal(stopped.paused, true);
  await exited;
});
