import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { readdir, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { renderConfig, validateVault } from './setup-lib.js';

const exec = promisify(execFile);
export async function git(cwd, args) {
  return (await exec('git', args, { cwd, timeout: 60000, windowsHide: true,
    // 配置阶段允许 Credential Manager 的图形登录，禁止隐藏终端输入。
    env: { ...process.env, GIT_TERMINAL_PROMPT: '0', GCM_INTERACTIVE: 'true', GCM_GUI_PROMPT: 'true' } })).stdout.trim();
}

export function repositoryUrl(value) {
  const url = new URL(value);
  if (url.protocol !== 'https:' || url.hostname !== 'github.com' || url.username || url.password ||
      url.search || url.hash || !/^\/[\w.-]+\/[\w.-]+\/?$/.test(url.pathname)) {
    throw new Error('请填写 GitHub 仓库地址，例如 https://github.com/用户名/仓库名；不要在地址中填写令牌。');
  }
  return url.href.replace(/\/$/, '');
}
export function comparableRepository(value) {
  return value.replace(/^git@github\.com:/, 'https://github.com/').replace(/\.git$/, '').replace(/\/$/, '');
}

// 新目录与已有目录分别处理；远端已有内容时绝不把本地文件直接覆盖进去。
export async function prepareVault({ vaultPath, repository, mode = 'existing', name, email }, deps = {}) {
  const parseUrl = deps.parseUrl ?? repositoryUrl;
  if (!path.isAbsolute(vaultPath || '')) throw new Error('请选择笔记文件夹的完整路径。');
  if (!existsSync(vaultPath)) throw new Error('笔记目录不存在，请先选择或创建文件夹。');
  const isRepo = existsSync(path.join(vaultPath, '.git'));
  if (!isRepo) {
    const url = parseUrl(repository);
    const refs = await git(vaultPath, ['ls-remote', url]);
    if (mode === 'download') {
      if ((await readdir(vaultPath)).length) throw new Error('下载笔记需要空文件夹，请选择一个新的空文件夹。');
      await git(vaultPath, ['clone', '--', url, '.']);
    } else {
      if (refs) throw new Error('GitHub 仓库已有内容。请选“下载已有笔记”到空目录，再手动复制本地笔记，避免覆盖。');
      await git(vaultPath, ['init', '-b', 'main']);
      await git(vaultPath, ['remote', 'add', 'origin', url]);
    }
  }
  if (name?.trim()) await git(vaultPath, ['config', 'user.name', name.trim()]);
  if (email?.trim()) await git(vaultPath, ['config', 'user.email', email.trim()]);
  await validateVault(vaultPath);
  for (const key of ['user.name', 'user.email']) {
    try { await git(vaultPath, ['config', '--get', key]); }
    catch { throw new Error('请填写提交者姓名和邮箱（仅用于这个笔记仓库的版本记录）。'); }
  }
  let branch;
  try { branch = await git(vaultPath, ['symbolic-ref', '--short', 'HEAD']); }
  catch { throw new Error('当前仓库未处于普通分支，请先完成正在进行的 Git 操作。'); }
  let remote = 'origin';
  let upstream;
  try { upstream = await git(vaultPath, ['rev-parse', '--abbrev-ref', '@{u}']); remote = upstream.split('/')[0]; } catch { /* 新仓库 */ }
  const url = await git(vaultPath, ['remote', 'get-url', remote]);
  if (repository?.trim() && comparableRepository(parseUrl(comparableRepository(repository))) !== comparableRepository(url)) {
    throw new Error('该笔记目录已关联另一个仓库。请核对地址，工具不会自动更换已有远端。');
  }
  const refs = await git(vaultPath, ['ls-remote', remote]);
  if (!upstream && refs) throw new Error('已有远端内容，但本地分支尚未关联远端分支。请先处理分支关联，避免推送到错误位置。');
  return { vaultPath, repository: url.replace(/(\w+:\/\/)[^/@\s]+@/g, '$1***@'), branch };
}

export async function saveVault(configFile, input) {
  const summary = await prepareVault(input);
  await writeFile(configFile, renderConfig(input.vaultPath));
  return summary;
}
