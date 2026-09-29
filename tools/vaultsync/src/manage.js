import { existsSync, readFileSync } from 'node:fs';
import { readFile, writeFile, rm } from 'node:fs/promises';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import path from 'node:path';
import { TOOL_DIR, PID_FILE, PAUSE_FILE, RESULT_FILE, loadConfig } from './config.js';
import { saveVault, git, comparableRepository } from './onboarding.js';
import { findDaemon } from './status-lib.js';
import { renderVbs, startupVbsPath } from './setup-lib.js';
import { gitErrorMessage } from './git.js';

const exec = promisify(execFile);
const configFile = path.join(TOOL_DIR, 'vaultsync.config.json');
function ownsStartup() {
  if (process.platform !== 'win32') return false;
  try {
    const bytes = readFileSync(startupVbsPath());
    const text = bytes[0] === 0xff && bytes[1] === 0xfe ? bytes.toString('utf16le') : bytes.toString('utf8');
    return text.includes(path.join(TOOL_DIR, 'start-vaultsync.ps1'));
  } catch { return false; }
}
async function status() {
  let config = null;
  let error = null;
  try { config = loadConfig(); } catch (e) { error = e.message; }
  let result = null;
  try { result = JSON.parse(readFileSync(RESULT_FILE, 'utf8')); } catch { /* 尚未同步 */ }
  if (result?.vaultPath !== config?.vaultPath) result = null;
  if (config) {
    try { config.repository = comparableRepository(await git(config.vaultPath, ['remote', 'get-url', 'origin'])).replace(/(\w+:\/\/)[^/@\s]+@/g, '$1***@'); } catch { /* 自定义远端 */ }
  }
  return { running: (await findDaemon(PID_FILE, { wmiFind: async () => [] })).pids.length > 0,
    paused: existsSync(PAUSE_FILE), config, error, result,
    startup: ownsStartup() };
}
async function main() {
  const action = process.argv[2] || 'status';
  let input = {};
  if (process.argv[3]) input = JSON.parse(await readFile(process.argv[3], 'utf8'));
  if (action === 'status') return status();
  if (action === 'doctor') {
    const state = await status();
    if (!state.config) throw new Error('尚无有效配置，请先运行 npm run setup 或打开 VaultSync.vbs。');
    const node = process.version;
    const { stdout } = await exec('git', ['--version'], { windowsHide: true });
    await git(state.config.vaultPath, ['config', '--get', 'user.name']);
    await git(state.config.vaultPath, ['config', '--get', 'user.email']);
    let remote = 'origin';
    try { remote = (await git(state.config.vaultPath, ['rev-parse', '--abbrev-ref', '@{u}'])).split('/')[0]; } catch { /* 新仓库 */ }
    await git(state.config.vaultPath, ['ls-remote', remote]);
    return { ...state, checks: { node, git: stdout.trim(), identity: '通过', repositoryAccess: '通过（读取权限；写入由首次同步验证）' } };
  }
  if (action === 'stop') {
    await writeFile(PAUSE_FILE, 'paused');
    if ((await status()).running) {
      await writeFile(`${PID_FILE}.stop`, 'stop');
      for (let i = 0; i < 30; i++) {
        await new Promise(r => setTimeout(r, 1000));
        if (!(await status()).running) break;
      }
      if ((await status()).running) throw new Error('后台尚未退出，请等待正在进行的同步完成。');
    }
    return status();
  }
  if (action === 'configure') {
    if ((await status()).running) throw new Error('修改目录前请退出后台同步，或重新启动电脑后先打开配置窗口。当前可调整暂停和自启。');
    return saveVault(configFile, input);
  }
  if (action === 'pause') { await writeFile(PAUSE_FILE, 'paused'); return status(); }
  if (action === 'resume') { await rm(PAUSE_FILE, { force: true }); return status(); }
  if (action === 'startup-off') {
    if (!ownsStartup() && existsSync(startupVbsPath())) throw new Error('开机启动关联另一份 VaultSync，请在原配置窗口中关闭，或先启用当前目录。');
    await rm(startupVbsPath(), { force: true });
    return status();
  }
  if (action === 'enable') {
    loadConfig();
    const target = startupVbsPath();
    // WSH 根据 BOM 识别 Unicode，支持中文安装路径。
    await writeFile(target, '\ufeff' + renderVbs(path.join(TOOL_DIR, 'start-vaultsync.ps1')), 'utf16le');
    await rm(PAUSE_FILE, { force: true });
    await exec('wscript.exe', [target], { windowsHide: true });
    for (let i = 0; i < 15; i++) {
      await new Promise(r => setTimeout(r, 1000));
      if ((await status()).running) return status();
    }
    throw new Error('后台启动失败，请查看 vaultsync.err.log。');
  }
  if (action === 'sync') {
    const before = (await status()).result?.time;
    if ((await status()).running) {
      if (existsSync(PAUSE_FILE)) throw new Error('请先恢复自动同步，再点击立即同步。');
      await writeFile(`${PID_FILE}.sync`, 'sync');
    } else {
      await exec(process.execPath, [path.join(TOOL_DIR, 'src/index.js'), '--once'], { cwd: TOOL_DIR, timeout: 1200000, windowsHide: true });
      return status();
    }
    for (let i = 0; i < 600; i++) {
      await new Promise(r => setTimeout(r, 1000));
      const state = await status();
      if (state.result && state.result.time !== before) return state;
      if (!state.running) throw new Error('后台进程已退出，请查看 vaultsync.err.log。');
    }
    throw new Error('同步仍未完成，请查看状态和日志。');
  }
  throw new Error(`未知操作: ${action}`);
}
main().then(data => console.log(JSON.stringify({ ok: true, data }))).catch(e => {
  console.log(JSON.stringify({ ok: false, error: gitErrorMessage(e) }));
  process.exitCode = 1;
});
