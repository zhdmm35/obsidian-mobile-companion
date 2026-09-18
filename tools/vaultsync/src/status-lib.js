import { execFile } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { promisify } from 'node:util';

const pExecFile = promisify(execFile);

// signal 0 只探测不发送；EPERM 说明进程存在但无权操作，也算存活
export function pidAlive(pid) {
  if (!Number.isInteger(pid) || pid <= 0) return false;
  try {
    process.kill(pid, 0);
    return true;
  } catch (e) {
    return e.code === 'EPERM';
  }
}

export function parsePids(stdout) {
  return String(stdout)
    .split(/\r?\n/)
    .map((s) => Number(s.trim()))
    .filter((n) => Number.isInteger(n) && n > 0);
}

const RESULT_RE = /(Up to date|Pulled from remote|Push complete|Sync failed.*)$/;

export function summarizeLog(text) {
  const lines = String(text ?? '')
    .split(/\r?\n/)
    .filter((l) => l.trim());
  let lastResult = null;
  for (let i = lines.length - 1; i >= 0; i--) {
    if (RESULT_RE.test(lines[i])) {
      lastResult = lines[i].trim();
      break;
    }
  }
  return { tail: lines.slice(-3), lastResult };
}

export function formatAge(ms) {
  if (!(ms >= 0)) return '未知';
  const s = Math.floor(ms / 1000);
  if (s < 60) return `${s} 秒前`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m} 分钟前`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h} 小时前`;
  return `${Math.floor(h / 24)} 天前`;
}

// 旧版 daemon（无 pid 文件）兜底：按命令行匹配 node src\index.js
async function wmiFindNodeIndexJs() {
  try {
    const { stdout } = await pExecFile(
      'powershell.exe',
      [
        '-NoProfile',
        '-Command',
        "Get-CimInstance Win32_Process -Filter \"Name='node.exe' AND CommandLine LIKE '%src%index.js%'\" | Select-Object -ExpandProperty ProcessId",
      ],
      { timeout: 15000, windowsHide: true }
    );
    return parsePids(stdout);
  } catch {
    return [];
  }
}

// 优先读 pid 文件（daemon 启动时写入）；读不到才走 WMI 兜底。
// 返回 { pids, via: 'pidfile'|'wmi'|'none', stalePid }；stalePid 非空表示 pid 文件残留但进程已死。
export async function findDaemon(pidFile, deps = {}) {
  const alive = deps.pidAlive ?? pidAlive;
  const wmi = deps.wmiFind ?? wmiFindNodeIndexJs;
  let pid = NaN;
  try {
    pid = Number(readFileSync(pidFile, 'utf8').trim());
  } catch {
    // 没有 pid 文件
  }
  if (alive(pid)) return { pids: [pid], via: 'pidfile', stalePid: null };
  const stalePid = Number.isInteger(pid) && pid > 0 ? pid : null;
  const pids = process.platform === 'win32' ? await wmi() : [];
  return { pids, via: pids.length > 0 ? 'wmi' : 'none', stalePid };
}
