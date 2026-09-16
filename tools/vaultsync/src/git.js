import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { existsSync } from 'node:fs';
import path from 'node:path';

const pExecFile = promisify(execFile);

// 单次 git 调用超时：网络半开/挂起时让失败有界，否则互斥锁被占死，daemon 不再同步也无日志
const GIT_TIMEOUT_MS = 300_000;

export class SyncError extends Error {
  constructor(kind, message) {
    super(message);
    this.kind = kind;
  }
}

export function makeRunner(cwd) {
  return (args) =>
    pExecFile('git', args, {
      cwd,
      windowsHide: true,
      maxBuffer: 16 * 1024 * 1024,
      timeout: GIT_TIMEOUT_MS,
      // 凭据失效时报错而非交互式询问——daemon 无人应答，询问等于挂死
      env: { ...process.env, GIT_TERMINAL_PROMPT: '0' },
    });
}

// git 的 fatal 行会原样引用 remote URL；URL 内嵌凭据（https://token@host/...）时先脱敏再进日志
const maskCredentials = (s) => s.replace(/(\w+:\/\/)[^/@\s]+@/g, '$1***@');

// 只取 git 的 fatal/error 行，避免把完整 stderr（可能很长）打进日志
export function gitErrorMessage(err) {
  if (err?.killed && err?.signal) {
    return `git 操作超时（${GIT_TIMEOUT_MS / 1000}s）已终止，可能是网络挂起`;
  }
  const lines = String(err?.stderr || err?.message || 'git command failed')
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter(Boolean);
  const meaningful = lines.filter((l) => /^(fatal|error|conflict|warning)\b/i.test(l));
  return maskCredentials(meaningful[0] || lines[0] || 'git command failed').slice(0, 300);
}

function nowStamp() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

/**
 * 创建 syncOnce。所有触发方式（startup/watcher/periodic/manual）共用同一个函数，
 * 内部用 Promise 链做进程内互斥锁：同一时间只跑一个 git 流程。
 */
export function createSync({ vaultPath, log = () => {}, runGit = makeRunner(vaultPath) }) {
  let tail = Promise.resolve(); // 锁：新的 sync 排在上一个结束之后

  async function doSync() {
    // 遗留 rebase（用户手动操作或上次进程被强杀中断）绝不插手：
    // 否则下方 catch 的 --abort 会废弃别人的 rebase，且此后每轮都误报冲突、永不自愈。
    // 必须先于 detached 检查：rebase 进行中 HEAD 本来就是 detached，要报更具体的原因
    const gitDir = path.join(vaultPath, '.git');
    if (existsSync(path.join(gitDir, 'rebase-merge')) || existsSync(path.join(gitDir, 'rebase-apply'))) {
      throw new SyncError(
        'rebase-in-progress',
        '仓库正处于 rebase 中（手动操作或上次异常中断），请先手动 git rebase --abort 或 --continue'
      );
    }

    const branch = (await runGit(['rev-parse', '--abbrev-ref', 'HEAD'])).stdout.trim();
    if (branch === 'HEAD') {
      throw new SyncError('detached', 'detached HEAD，跳过同步');
    }

    // 1. 本地有修改 → add + commit（无修改不产生空 commit）
    const status = (await runGit(['status', '--porcelain'])).stdout;
    const changed = status.split('\n').filter(Boolean);
    let committed = 0;
    if (changed.length > 0) {
      log(`${changed.length} file(s) changed`);
      await runGit(['add', '-A']);
      // 自动化提交不签名、不跑钩子：用户全局 gpgsign / pre-commit hook 会让每轮同步永久失败
      await runGit(['-c', 'commit.gpgsign=false', 'commit', '--no-verify', '-m', `vaultsync: update ${nowStamp()}`]);
      committed = changed.length;
      log('Commit created');
    }

    // 2. 确定当前 branch 的 upstream（不假设 main）
    let upstream = null;
    try {
      upstream = (await runGit(['rev-parse', '--abbrev-ref', '--symbolic-full-name', '@{u}'])).stdout.trim() || null;
    } catch {
      upstream = null;
    }
    const remote = upstream ? upstream.split('/')[0] : 'origin';
    const headBefore = (await runGit(['rev-parse', 'HEAD'])).stdout.trim();

    // 3. fetch + rebase；冲突时 abort，保留本地 commit，明确报错
    await runGit(['fetch', remote]);
    if (upstream) {
      try {
        await runGit(['rebase', upstream]);
      } catch {
        // 入口已排除遗留 rebase，这里 abort 的一定是本次调用自己启动的；
        // 少数非冲突失败（如 commit 后 worktree 又被改脏）同样按冲突上报，信息不精确但下轮自愈
        try {
          await runGit(['rebase', '--abort']);
        } catch {
          // rebase 已不在进行中，忽略
        }
        throw new SyncError('conflict', '同步遇到冲突，需要手动处理。本地内容和 GitHub 内容都已保留。');
      }
    }

    // 4. push（无 upstream 的分支用 -u 建立跟踪）
    if (upstream) {
      await runGit(['push', remote, branch]);
    } else {
      await runGit(['push', '-u', remote, branch]);
    }

    const headAfter = (await runGit(['rev-parse', 'HEAD'])).stdout.trim();
    const pulled = Boolean(upstream) && headBefore !== headAfter;

    if (!committed && !pulled) {
      log('Up to date');
    } else {
      if (pulled) log('Pulled from remote');
      log('Push complete');
    }
    return { committed, pulled, changed: changed.length };
  }

  function syncOnce(reason) {
    const run = tail.then(() => {
      log(`Sync: ${reason}`);
      return doSync();
    });
    tail = run.then(
      () => {},
      () => {}
    );
    return run;
  }

  // 等待正在执行/排队的 sync 全部结束（优雅退出用）
  function idle() {
    return tail;
  }

  return { syncOnce, idle };
}
