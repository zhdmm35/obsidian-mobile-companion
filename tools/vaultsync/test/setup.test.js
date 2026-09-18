import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';

import { renderConfig, renderVbs, startupVbsPath, validateVault } from '../src/setup-lib.js';
import { findDaemon, formatAge, parsePids, pidAlive, summarizeLog } from '../src/status-lib.js';

test('renderVbs: embeds absolute ps1 path with doubled quotes, ASCII only', () => {
  const ps1 = 'C:\\Users\\you dir\\tools\\vaultsync\\start-vaultsync.ps1';
  const vbs = renderVbs(ps1);
  assert.ok(vbs.includes(`-File ""${ps1}""`), vbs);
  assert.match(vbs, /Wscript\.Shell/);
  // wscript 按 ANSI 读 .vbs：任何非 ASCII 字符（中文注释/路径分隔符以外的字节）都会乱码
  assert.ok(/^[\x20-\x7E\r\n]*$/.test(vbs), 'vbs 必须纯 ASCII');
});

test('renderConfig: forward slashes, defaults, valid JSON', () => {
  const json = JSON.parse(renderConfig('E:\\obs\\my vault\\'));
  assert.equal(json.vaultPath, 'E:/obs/my vault');
  assert.equal(json.debounceSeconds, 30);
  assert.equal(json.pollMinutes, 5);
});

test('validateVault: missing dir / not git repo / no remote / ok', async () => {
  const existing = new Set();
  const exists = (p) => existing.has(p);
  const hasRemote = async () => ['origin'];
  const noRemote = async () => [];

  await assert.rejects(validateVault('E:/v', { exists, listRemotes: hasRemote }), /目录不存在/);

  existing.add('E:/v');
  await assert.rejects(validateVault('E:/v', { exists, listRemotes: hasRemote }), /不是 git 仓库/);

  existing.add(path.join('E:/v', '.git'));
  await assert.rejects(validateVault('E:/v', { exists, listRemotes: noRemote }), /remote add origin/);

  await validateVault('E:/v', { exists, listRemotes: hasRemote }); // 通过则不抛
});

test('startupVbsPath: derived from APPDATA, throws without it', () => {
  const p = startupVbsPath({ APPDATA: 'C:\\Users\\u\\AppData\\Roaming' });
  assert.match(p, /Startup[\\/]start-vaultsync\.vbs$/);
  assert.throws(() => startupVbsPath({}), /APPDATA/);
});

test('summarizeLog: last result line + 3-line tail; failure wins when latest', () => {
  const log = [
    '[21:51:29] VaultSync started',
    '[21:51:29] Sync: startup',
    '[21:51:44] Up to date',
    '[21:52:30] Sync: watcher',
    '[21:52:30] 1 file(s) changed',
    '[21:52:46] Push complete',
  ].join('\n');
  const { tail, lastResult } = summarizeLog(log);
  assert.equal(lastResult, '[21:52:46] Push complete');
  assert.deepEqual(tail, log.split('\n').slice(-3));

  const failed = `${log}\n[22:06:30] Sync failed: fatal: unable to access`;
  assert.match(summarizeLog(failed).lastResult, /Sync failed/);

  assert.equal(summarizeLog('').lastResult, null);
  assert.deepEqual(summarizeLog('').tail, []);
});

test('formatAge: seconds / minutes / hours / days / invalid', () => {
  assert.equal(formatAge(5_000), '5 秒前');
  assert.equal(formatAge(3 * 60_000), '3 分钟前');
  assert.equal(formatAge(2 * 3_600_000), '2 小时前');
  assert.equal(formatAge(10 * 86_400_000), '10 天前');
  assert.equal(formatAge(-1), '未知');
});

test('pidAlive: current process alive, impossible/invalid pids dead', () => {
  assert.equal(pidAlive(process.pid), true);
  // Windows pid 是 4 的倍数、Linux 默认上限 2^22：2^30+3 在两者上都不可能存在
  assert.equal(pidAlive(2 ** 30 + 3), false);
  assert.equal(pidAlive(NaN), false);
  assert.equal(pidAlive(-1), false);
});

test('parsePids: powershell output to pid list', () => {
  assert.deepEqual(parsePids('1234\r\n5678\r\n'), [1234, 5678]);
  assert.deepEqual(parsePids('  \r\nabc\r\n42\n'), [42]);
  assert.deepEqual(parsePids(''), []);
});

test('findDaemon: live pid file wins; dead pid reported stale; none when nothing found', async (t) => {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'vaultsync-pid-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const pidFile = path.join(dir, 'vaultsync.pid');
  const noWmi = { wmiFind: async () => [] };

  await writeFile(pidFile, String(process.pid));
  const live = await findDaemon(pidFile, noWmi);
  assert.deepEqual(live, { pids: [process.pid], via: 'pidfile', stalePid: null });

  await writeFile(pidFile, String(2 ** 30 + 3));
  const dead = await findDaemon(pidFile, noWmi);
  assert.deepEqual(dead.pids, []);
  assert.equal(dead.stalePid, 2 ** 30 + 3);

  await writeFile(pidFile, 'garbage');
  const none = await findDaemon(pidFile, noWmi);
  assert.deepEqual(none.pids, []);
  assert.equal(none.stalePid, null);
});
