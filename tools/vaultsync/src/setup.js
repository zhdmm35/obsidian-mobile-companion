import { execFile, spawn } from 'node:child_process';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import readline from 'node:readline/promises';
import { promisify } from 'node:util';

import { TOOL_DIR, PID_FILE, loadConfig } from './config.js';
import { renderConfig, renderVbs, startupVbsPath, validateVault } from './setup-lib.js';
import { findDaemon } from './status-lib.js';

const pExecFile = promisify(execFile);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const CONFIG_FILE = path.join(TOOL_DIR, 'vaultsync.config.json');
const PS1_FILE = path.join(TOOL_DIR, 'start-vaultsync.ps1');
const ERR_FILE = path.join(TOOL_DIR, 'vaultsync.err.log');

let step = 0;
const banner = (title) => console.log(`\n[${++step}] ${title}`);

// npm 在 Windows 上是 npm.cmd，必须经 shell 调用；stdio 继承让用户看到安装进度
function run(cmd, args) {
  return new Promise((resolve, reject) => {
    const child = spawn(cmd, args, { cwd: TOOL_DIR, shell: true, stdio: 'inherit', windowsHide: true });
    child.on('error', reject);
    child.on('exit', (code) => (code === 0 ? resolve() : reject(new Error(`${cmd} 退出码 ${code}`))));
  });
}

function printErrTail() {
  try {
    const lines = readFileSync(ERR_FILE, 'utf8').split(/\r?\n/).filter(Boolean);
    if (lines.length === 0) {
      console.log('    （err.log 为空）');
    }
    for (const l of lines.slice(-5)) console.log(`    ${l}`);
  } catch {
    console.log('    （无 err.log）');
  }
}

async function waitDaemonUp() {
  await sleep(1500); // 新进程写 pid 文件需要一点时间
  for (let i = 0; i < 8; i++) {
    const d = await findDaemon(PID_FILE);
    if (d.pids.length > 0) return true;
    await sleep(1000);
  }
  return false;
}

async function main() {
  console.log('=== VaultSync 一键部署 ===');

  banner('环境检查');
  console.log(`  Node ${process.version}`);
  try {
    const { stdout } = await pExecFile('git', ['--version'], { timeout: 10000, windowsHide: true });
    console.log(`  ${stdout.trim()}`);
  } catch {
    throw new Error('找不到 git 命令。请先安装 Git for Windows: https://git-scm.com/download/win');
  }

  banner('依赖');
  if (existsSync(path.join(TOOL_DIR, 'node_modules', 'chokidar'))) {
    console.log('  node_modules 已就绪');
  } else {
    console.log('  正在安装依赖（npm install，首次可能需要几分钟）...');
    await run('npm', ['install']);
    console.log('  依赖安装完成');
  }

  const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
  try {
    banner('配置');
    if (existsSync(CONFIG_FILE)) {
      const cfg = loadConfig(); // 顺带校验；无效则抛错提示手动修复
      console.log(`  已有配置: ${path.basename(CONFIG_FILE)}`);
      console.log(`  Vault: ${cfg.vaultPath}（debounce ${cfg.debounceSeconds}s, poll ${cfg.pollMinutes}min）`);
    } else {
      while (true) {
        const input = (await rl.question('  请输入 Obsidian Vault 的绝对路径（直接回车取消）: ')).trim();
        if (!input) {
          console.log('  已取消。');
          return;
        }
        const vaultPath = path.resolve(input.replace(/^"|"$/g, '')); // 拖入终端的路径常带引号
        try {
          await validateVault(vaultPath);
          writeFileSync(CONFIG_FILE, renderConfig(vaultPath));
          console.log(`  已写入 ${path.basename(CONFIG_FILE)}（debounce 30s, poll 5min，可手改）`);
          break;
        } catch (e) {
          console.log(`  ✗ ${e.message}`);
        }
      }
    }

    if (process.platform === 'win32') {
      banner('开机自启');
      const vbsTarget = startupVbsPath();
      writeFileSync(vbsTarget, renderVbs(PS1_FILE));
      console.log(`  已写入「启动」文件夹: ${vbsTarget}`);
      console.log('  每次登录 Windows 后 daemon 无窗口自动启动。');
      if (/[^\x20-\x7E]/.test(PS1_FILE)) {
        console.log('  ⚠ vaultsync 路径含非 ASCII 字符，若自启失效请移到纯 ASCII 路径后重新运行 setup');
      }

      banner('启动');
      const daemon = await findDaemon(PID_FILE);
      if (daemon.pids.length > 0) {
        const via = daemon.via === 'wmi' ? '（由旧版启动，下次重启电脑后自动使用新版）' : '';
        console.log(`  daemon 已在运行 (pid ${daemon.pids.join(', ')})${via}，跳过启动`);
      } else {
        const answer = (await rl.question('  现在启动 daemon？[Y/n]: ')).trim().toLowerCase();
        if (answer === '' || answer === 'y' || answer === 'yes') {
          await pExecFile('wscript', [vbsTarget], { windowsHide: true });
          process.stdout.write('  等待 daemon 启动');
          const up = await waitDaemonUp();
          console.log(up ? '\n  ✓ daemon 启动成功' : '\n  ✗ 未检测到 daemon 进程，错误信息：');
          if (!up) {
            printErrTail();
            throw new Error('daemon 启动失败，可运行 npm run sync 查看直接报错');
          }
        } else {
          console.log('  已跳过。手动启动: wscript start-vaultsync.vbs');
        }
      }
    } else {
      banner('开机自启');
      console.log('  非 Windows 系统，自启配置已跳过。');
      console.log('  请用 systemd --user / launchd 自行配置，或手动运行: npm start');
    }
  } finally {
    rl.close();
  }

  console.log('\n=== 部署完成 ===');
  console.log('  查看状态:  npm run status');
  console.log('  单次同步:  npm run sync');
  console.log(`  日志:      ${path.join(TOOL_DIR, 'vaultsync.log')}`);
  console.log('  停用自启:  删除「启动」文件夹中的 start-vaultsync.vbs（Win+R 输入 shell:startup）');
}

main().catch((e) => {
  console.error(`\nsetup 未完成: ${e.message}`);
  process.exitCode = 1;
});
