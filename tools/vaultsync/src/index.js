import { readFileSync, rmSync, writeFileSync } from 'node:fs';

import { loadConfig, PID_FILE } from './config.js';
import { createSync, gitErrorMessage } from './git.js';
import { pidAlive } from './status-lib.js';
import { startWatcher } from './watcher.js';

function ts() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, '0');
  return `[${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}]`;
}
const log = (...a) => console.log(ts(), ...a);

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  const once = process.argv.includes('--once');
  const cfgIdx = process.argv.indexOf('--config');
  const configPath = cfgIdx >= 0 ? process.argv[cfgIdx + 1] : undefined;
  const cfg = loadConfig(configPath);

  // 单实例：pid 文件里的进程还活着就直接退出，避免双击 vbs / 重复自启产生第二个 daemon
  // 互相抢 git 锁。--once 调试模式不受此限。
  if (!once) {
    let oldPid = NaN;
    try {
      oldPid = Number(readFileSync(PID_FILE, 'utf8').trim());
    } catch {
      // 没有 pid 文件：首次运行，或上次被强杀
    }
    if (oldPid !== process.pid && pidAlive(oldPid)) {
      log(`VaultSync already running (pid ${oldPid}), exit`);
      return;
    }
    writeFileSync(PID_FILE, String(process.pid));
    process.on('exit', () => {
      try {
        rmSync(PID_FILE, { force: true });
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
    try {
      await syncOnce(reason);
    } catch (e) {
      log(`Sync failed: ${gitErrorMessage(e)}`);
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
  timer.unref(); // watcher 关闭后不被空 timer 拖住

  let closing = false;
  const shutdown = async (name) => {
    if (closing) return;
    closing = true;
    log(`Received ${name}, shutting down`);
    clearInterval(timer);
    await watcher.close();
    // 等在途的 git 流程跑完再退出，避免打断 rebase/commit 留下脏状态
    await Promise.race([idle(), sleep(10_000)]);
    process.exit(0);
  };
  process.on('SIGINT', () => shutdown('SIGINT'));
  process.on('SIGTERM', () => shutdown('SIGTERM'));
}

main().catch((e) => {
  console.error(`VaultSync fatal: ${e.message}`);
  process.exit(1);
});
