import { existsSync, readFileSync, statSync } from 'node:fs';
import path from 'node:path';

import { TOOL_DIR, PID_FILE, loadConfig } from './config.js';
import { findDaemon, formatAge, summarizeLog } from './status-lib.js';

const LOG_FILE = path.join(TOOL_DIR, 'vaultsync.log');
const ERR_FILE = path.join(TOOL_DIR, 'vaultsync.err.log');

async function main() {
  console.log('VaultSync 状态\n');

  const daemon = await findDaemon(PID_FILE);
  const running = daemon.pids.length > 0;
  if (running) {
    const via = daemon.via === 'wmi' ? '，按命令行匹配（旧版 daemon，下次重启电脑后启用 pid 检测）' : '';
    console.log(`  daemon:    运行中 (pid ${daemon.pids.join(', ')}${via})`);
  } else {
    console.log('  daemon:    未运行');
    if (daemon.stalePid) {
      console.log(`             pid 文件残留 (pid ${daemon.stalePid})，daemon 可能异常退出过`);
    }
    console.log('             启动: wscript start-vaultsync.vbs（或 npm start 前台运行）');
  }

  try {
    const cfg = loadConfig();
    console.log(`  Vault:     ${cfg.vaultPath}`);
    console.log(`  参数:      debounce ${cfg.debounceSeconds}s, poll ${cfg.pollMinutes}min`);
  } catch (e) {
    console.log(`  配置:      ${e.message}`);
    console.log('             请先运行: npm run setup');
  }

  if (existsSync(LOG_FILE)) {
    const st = statSync(LOG_FILE);
    console.log(`  日志:      ${LOG_FILE}`);
    console.log(`  最后写入:  ${st.mtime.toLocaleString()}（${formatAge(Date.now() - st.mtimeMs)}）`);
    const { tail, lastResult } = summarizeLog(readFileSync(LOG_FILE, 'utf8'));
    if (lastResult) console.log(`  最近结果:  ${lastResult}`);
    if (tail.length > 0) {
      console.log('  最近日志:');
      for (const l of tail) console.log(`    ${l}`);
    }
  } else {
    console.log('  日志:      尚无日志（daemon 可能从未运行过）');
  }

  if (existsSync(ERR_FILE)) {
    const lines = readFileSync(ERR_FILE, 'utf8').split(/\r?\n/).filter(Boolean);
    if (lines.length > 0) {
      console.log('  err.log 末尾:');
      for (const l of lines.slice(-3)) console.log(`    ${l}`);
    }
  }

  process.exitCode = running ? 0 : 1;
}

main().catch((e) => {
  console.error(`status 失败: ${e.message}`);
  process.exitCode = 2;
});
