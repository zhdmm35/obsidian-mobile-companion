# vaultsync

PC 端 Obsidian Vault ↔ GitHub 轻量自动同步（Phase 6A）。

手机 App（Phase 3-5）直接读写 GitHub 私有仓库；vaultsync 负责 PC 这一侧：
Vault 文件变化后自动 commit + push，同时定时拉取手机推上来的新 commit。

## 原理

- Node.js + chokidar 监听 Vault 文件变化，30s debounce（持续编辑时计时不断重置）。
- 每 5 分钟 periodic 检查一次远端（即使本地无变化，也可能有手机推的新 commit）。
- 所有触发方式（startup / watcher / periodic / manual）走同一个 `syncOnce()`，
  进程内 Promise 锁保证同一时间只有一个 git 流程。
- Git 操作全部通过系统 `git` CLI（`child_process.execFile`），当前 branch 跟踪的
  upstream 从 `@{u}` 解析，不假设 `main`。
- 流程：`status → 有修改则 add -A + commit → fetch → rebase @{u} → push`。
  - 无修改不产生空 commit；commit 不签名、不跑钩子（`-c commit.gpgsign=false --no-verify`）。
  - rebase 冲突：`--abort` 保留本地 commit，明确报错退出本次同步，绝不 force。
  - 检测到遗留 rebase 状态（手动 rebase 或上次异常中断）：跳过并提示，绝不代为 abort。
  - fetch/push 网络失败：记录日志，进程不退出，等下次触发重试。
  - 单次 git 调用 5 分钟超时 + `GIT_TERMINAL_PROMPT=0`：网络挂死/凭据失效只会报错，
    不会把 daemon 拖成"活着但永远不再同步"的僵尸。

## 使用

前置要求：安装 [Node.js](https://nodejs.org/) 与 [Git](https://git-scm.com/)，Vault 目录已是 git 仓库且关联了 GitHub remote。

```bash
cd tools/vaultsync
npm run setup      # 一键部署：环境检查 → 装依赖 → 配置向导 → 写入开机自启 → 启动并验证
```

setup 可重复执行（幂等）：已有配置跳过向导并显示摘要，自启文件重新生成覆盖；
daemon 已在运行时跳过启动，重复部署不会产生第二个实例（pid 文件单实例守卫）。

日常命令：

```bash
npm run status   # 查看 daemon 是否在跑、配置摘要、上次同步结果、日志尾部（不在跑时退出码非 0）
npm run sync     # 只同步一次然后退出（调试用，报错直接可见）
npm start        # 前台常驻（调试用；平时不需要，setup 已配置无窗口自启）
npm test         # 单元测试（离线，临时 bare remote，不碰真实 Vault）
```

Ctrl+C 正常退出：关闭 watcher、停掉 timer，等在途 git 流程结束后退出。

## 开机自启（Windows）

`npm run setup` 已自动完成：把写入了本机 `start-vaultsync.ps1` 绝对路径的
`start-vaultsync.vbs` 放入「启动」文件夹（`shell:startup`），登录后无窗口启动 daemon
（vbs 调用 ps1，`cmd /c` 追加写日志保持纯文本编码）。无需再手动编辑任何路径。

- 排查 daemon 是否在跑：`npm run status`。daemon 启动时写 `vaultsync.pid`，退出时删除；
  检测以 pid 文件为准，旧版（无 pid 文件）启动的 daemon 按命令行匹配兜底。
- 手动重启一次：`wscript start-vaultsync.vbs`（startup sync 会自动补推停机期间的改动）。
- 停用自启：删除「启动」文件夹里的 `start-vaultsync.vbs`（Win+R 输入 `shell:startup` 打开）。
- 本目录下的 `start-vaultsync.vbs` 是**模板**，仅供手动安装参考；实际自启文件由 setup 生成。
  vaultsync 所在路径含非 ASCII 字符（如中文用户名目录）时 wscript 可能读错 vbs，
  若自启失效请把 vaultsync 移到纯 ASCII 路径后重新运行 setup。

非 Windows 系统：setup 只完成依赖与配置，自启请自行配置（systemd --user / launchd），
或手动 `npm start` 常驻。

## 配置

`npm run setup` 的配置向导会生成 `vaultsync.config.json` 并逐项校验（目录存在、是 git 仓库、有 remote）。手动管理也可以（也可用 `--config <path>` 或环境变量 `VAULTSYNC_CONFIG` 指定）：

```json
{
  "vaultPath": "C:/Users/you/Documents/MyVault",
  "debounceSeconds": 30,
  "pollMinutes": 5
}
```

从 `vaultsync.config.example.json` 复制一份为 `vaultsync.config.json`，再填写自己的 Vault 路径。实际配置文件已加入 `.gitignore`，不要提交包含个人路径或知识库信息的本地配置。

- `vaultPath`：Vault 所在目录，必须已经是 git 仓库且有 remote。
- `debounceSeconds` / `pollMinutes`：支持小数（测试时可用 0.25 = 15s）。

## 日志样例

```
[21:51:29] VaultSync started
[21:51:29] Sync: startup
[21:51:44] Up to date
[21:52:30] Sync: watcher
[21:52:30] 1 file(s) changed
[21:52:30] Commit created
[21:52:46] Push complete
[22:06:30] Sync: periodic
[22:06:32] Pulled from remote
[22:06:33] Push complete
```

失败时只输出 git 的 fatal/error 首行，不含完整 trace；
remote URL 中内嵌的凭据（`https://token@...`）会脱敏为 `***`。

## 已知限制

- vault 内新增嵌套 git 仓库（如 clone 来的项目）必须先加进 vault 的 `.gitignore`，
  否则 `add -A` 会把它提交成 gitlink（无 .gitmodules 的坏引用）并推上 GitHub。
- 笔记目录若恰好名为 `node_modules`，该目录不会被 watcher 监听（与依赖目录共用忽略规则；
  periodic 拉取不受影响）。

## 边界（本阶段明确不做）

多 Vault、冲突自动合并、GUI / 托盘、Windows Service、
新建远程仓库、Android 端改动 —— 见 `docs/phase6/PHASE6A_VAULTSYNC.md`。
