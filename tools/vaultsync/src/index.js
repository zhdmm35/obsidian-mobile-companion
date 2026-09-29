import { existsSync, readFileSync, rmSync, writeFileSync } from 'node:fs';

import { loadConfig, PID_FILE, PAUSE_FILE, RESULT_FILE } from './config.js';
import { createSync, gitErrorMessage } from './git.js';
import { pidAlive } from './status-lib.js';
import { startWatcher } from './watcher.js';

function ts() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, '0');
  return `[${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}]`;
}
const log = (...a) => console.log(ts(), ...a);

async function main() {
  const once = process.argv.includes('--once');
  const cfgIdx = process.argv.indexOf('--config');
  const configPath = cfgIdx >= 0 ? process.argv[cfgIdx + 1] : undefined;
  const cfg = loadConfig(configPath);

  // 单实例：pid 文件里的进程还活着就直接退出，避免双击 vbs / 重复自启产生第二个 daemon
  // 互相抢 git 锁；单次同步也遵守单实例限制。
  {
    let oldPid = NaN;
    let hadPidFile = false;
    try {
      oldPid = Number(readFileSync(PID_FILE, 'utf8').trim());
      hadPidFile = true;
    } catch {
      // 没有 pid 文件：首次运行，或上次被强杀
    }
    if (oldPid !== process.pid && pidAlive(oldPid)) {
      if (once) throw new Error('自动同步正在运行，请通过配置窗口的“立即同步”操作，避免同时操作仓库。');
      log(`VaultSync already running (pid ${oldPid}), exit`);
      return;
    }
    if (hadPidFile) rmSync(PID_FILE, { force: true });
    try { writeFileSync(PID_FILE, String(process.pid), { flag: 'wx' }); }
    catch (e) {
      if (e.code === 'EEXIST') throw new Error('另一个同步进程正在启动，请稍后重试。');
      throw e;
    }
    process.on('exit', () => {
      try {
        if (Number(readFileSync(PID_FILE, 'utf8')) === process.pid) rmSync(PID_FILE, { force: true });
      } catch {
        // 清理失败无妨，下次启动按存活检查覆盖
      }
    });
  }

  log('VaultSync started');
  log(`Vault: ${cfg.vaultPath}`);
  log(`Debounce: ${cfg.debounceSeconds}s, Poll: ${cfg.pollMinutes}min`);

  const { syncOnce, idle } = createSync({ vaultPath: cfg.vaultPath, log });

  // 任何 git 失败（网络等）只记录，不让进程退出，等下次触发再重试
  const safe = async (reason) => {
    if (!once && existsSync(PAUSE_FILE)) return;
    try {
      const result = await syncOnce(reason);
      writeFileSync(RESULT_FILE, JSON.stringify({ ok: true, vaultPath: cfg.vaultPath, time: new Date().toISOString(), ...result }));
    } catch (e) {
      const error = gitErrorMessage(e);
      writeFileSync(RESULT_FILE, JSON.stringify({ ok: false, vaultPath: cfg.vaultPath, time: new Date().toISOString(), error }));
      log(`Sync failed: ${error}`);
      if (once) process.exitCode = 1;
    }
  };

  await safe(once ? 'manual' : 'startup');
  if (once) return;

  const watcher = startWatcher({
    vaultPath: cfg.vaultPath,
    debounceMs: cfg.debounceMs,
    log,
    onChange: () => safe('watcher'),
  });
  log('Watcher active');

  const timer = setInterval(() => safe('periodic'), cfg.pollMs);
  const manualTimer = setInterval(() => {
    const stop = `${PID_FILE}.stop`;
    if (existsSync(stop)) {
      rmSync(stop, { force: true });
      shutdown('configuration window');
      return;
    }
    const request = `${PID_FILE}.sync`;
    if (existsSync(request)) {
      rmSync(request, { force: true });
      safe('manual');
    }
  }, 1000);
  manualTimer.unref();
  timer.unref(); // watcher 关闭后不被空 timer 拖住

  let closing = false;
  const shutdown = async (name) => {
    if (closing) return;
    closing = true;
    log(`Received ${name}, shutting down`);
    clearInterval(timer);
    clearInterval(manualTimer);
    await watcher.close();
    // 等在途的 git 流程跑完再退出，避免打断 rebase/commit 留下脏状态
    await idle();
    process.exit(0);
  };
  process.on('SIGINT', () => shutdown('SIGINT'));
  process.on('SIGTERM', () => shutdown('SIGTERM'));
}

main().catch((e) => {
  console.error(`VaultSync fatal: ${e.message}`);
  process.exit(1);
});
