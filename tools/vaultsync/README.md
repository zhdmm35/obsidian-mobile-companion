# vaultsync

PC 端 Obsidian Vault ↔ GitHub 轻量自动同步（Phase 6A）。

手机 App（Phase 3-5）直接读写 GitHub 私有仓库；vaultsync 负责 PC 这一侧：
Vault 文件变化后自动 commit + push，同时定时拉取手机推上来的新 commit。

## 原理

- Node.js + chokidar 监听 Vault 文件变化，30s debounce（持续编辑时计时不断重置）。
- 每 5 分钟 periodic 检查一次远端（即使本地无变化，也可能有手机推的新 commit）。
- 所有触发方式（startup / watcher / periodic / manual）走同一个 `syncOnce()`，
  进程内 Promise 锁保证同一时间只有一个 git 流程。自动触发合并为最多一轮待执行同步；
  运行期间的新变更仍会安排下一轮，手动同步各自保留独立结果。
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

### Windows 图形配置（推荐）

已有 Node.js 20+ 和 Git（在 PATH 中可用）时，请下载 Release 的 `VaultSync-v0.2.0-windows-light.zip`。轻量包包含脚本和依赖，使用本机运行环境，无需 npm install。启动窗口检查 Node.js、Git 命令是否存在，缺少时提示使用完整包。完整包自带运行环境，优先使用包内版本，适合未安装或希望固定运行版本的用户。

使用便携包：解压到一个固定目录，双击 **VaultSync.vbs**（也可使用 VaultSync.cmd），无需安装 Node.js、Git 或编辑 JSON。请先解压，不能直接在压缩包内启动；启用自启后不要移动这个目录，移动后需重新启用。

1. 选择 Obsidian 笔记目录。
2. 选择“电脑已有笔记”或“从 GitHub 下载已有笔记”，粘贴 GitHub 仓库地址。已有 Git 仓库会沿用原有远端，不自动更换。
3. 填写用于版本记录的姓名和邮箱（已有 Git 配置可留空），点击“检查并保存配置”。首次访问私有仓库可能打开 Git Credential Manager 登录窗口；认证方式由 Git 的凭据配置决定。
4. 核对显示的目录、仓库和分支后，点击“启用并验证同步”。工具设置 Windows 登录自启，执行真实同步，显示成功时间或失败原因。后台运行中不等于同步成功。

GitHub 仓库需事先创建：上传已有本地笔记时请使用**空仓库**，不要预先添加 README；下载已有笔记时请选择**空文件夹**。两边都有内容时工具停止并提示，避免覆盖。首次上传会提交并推送未被 Git 忽略的文件，请先核对需要排除的文件。

窗口提供刷新、立即同步、暂停、恢复、退出后台、关闭开机自启和打开日志。关闭窗口后后台继续同步。“暂停”保留进程，等待本次同步完成后不再开始新同步；“退出后台”等待在途同步结束后退出，保留暂停状态。修改目录前先退出后台。关闭自启只影响下次登录，不会结束当前同步。

当前版本通过仓库地址连接 GitHub，不提供仓库列表、自动创建仓库或自动解决冲突。默认静默 30 秒后上传、每 5 分钟获取远端；高级间隔仍可通过配置文件调整。

配置阶段允许图形登录；后台同步禁止弹出登录窗口，凭据失效时显示失败，重新检查配置即可进入登录流程。相关选项参见 [Git Credential Manager 环境变量说明](https://github.com/git-ecosystem/git-credential-manager/blob/main/docs/environment.md)。

### CLI / 源码运行

便携包的命令行入口是 `VaultSync-cli.cmd`：`VaultSync-cli.cmd setup` 打开交互向导，`configure` 重新配置，`doctor` 检查，`status` 查看状态，`sync` 立即同步，`pause` / `resume` / `stop` 控制后台。无需 npm。CLI 控制接口返回 JSON，便于脚本使用。

源码版前置要求：安装 [Node.js](https://nodejs.org/) 与 [Git](https://git-scm.com/)。首次图形启动前执行 `npm ci`，然后双击 VaultSync.vbs 或运行 `npm run gui`。

```bash
cd tools/vaultsync
npm run setup      # 一键部署：环境检查 → 装依赖 → 配置向导 → 写入开机自启 → 启动并验证
```

setup 可重复执行（幂等）：已有配置跳过向导并显示摘要，自启文件重新生成覆盖；
daemon 已在运行时跳过启动，重复部署不会产生第二个实例（pid 文件单实例守卫）。

日常命令：

```bash
npm run status   # 查看 daemon 是否在跑、配置摘要、上次同步结果、日志尾部（不在跑时退出码非 0）
npm run configure # 重新运行配置向导（先 npm run stop）
npm run doctor   # 检查运行环境、提交身份和远端读取权限
npm run pause    # 暂停自动同步
npm run resume   # 恢复自动同步
npm run stop     # 等待在途同步结束后退出后台
npm run sync     # 后台未运行时同步一次，失败退出码非 0；后台运行时请使用窗口的立即同步
npm start        # 前台常驻（调试用；平时不需要，setup 已配置无窗口自启）
npm test         # 单元测试（离线，临时 bare remote，不碰真实 Vault）
```

Ctrl+C 正常退出：关闭 watcher、停掉 timer，等在途 git 流程结束后退出。

### 制作 Windows 便携包

制作不含运行环境的轻量包：执行 `npm run build:light`，产物为 `dist/VaultSync-light-时间.zip`。此方式不要求打包机安装完整 Git for Windows，但需要先准备 npm 依赖。

在安装了 Node.js、完整 Git for Windows 的开发机执行 `npm ci`、`npm test`，再执行 `npm run build:portable`。产物在 `dist/VaultSync-时间.zip`，包含 Node.js、Git（含 Credential Manager）、依赖和分发许可；不包含本机配置、笔记、日志或登录凭据。依赖仍在版本锁文件中管理。便携包以开发机 Windows 架构为准；源码方式的 CLI 可继续在其他系统使用。

## 开机自启（Windows）

`npm run setup` 已自动完成：把写入了本机 `start-vaultsync.ps1` 绝对路径的
`start-vaultsync.vbs` 放入「启动」文件夹（`shell:startup`），登录后无窗口启动 daemon
（vbs 调用 ps1，`cmd /c` 追加写日志保持纯文本编码）。无需再手动编辑任何路径。

- 排查 daemon 是否在跑：`npm run status`。daemon 启动时写 `vaultsync.pid`，退出时删除；
  检测以 pid 文件为准，旧版（无 pid 文件）启动的 daemon 按命令行匹配兜底。
- 手动重启一次：`wscript start-vaultsync.vbs`（startup sync 会自动补推停机期间的改动）。
- 停用自启：删除「启动」文件夹里的 `start-vaultsync.vbs`（Win+R 输入 `shell:startup` 打开）。
- 本目录下的 `start-vaultsync.vbs` 是**模板**，仅供手动安装参考；实际自启文件由 setup 生成。
  自动生成的自启文件使用带 BOM 的 UTF-16，支持中文路径。

非 Windows 系统：setup 只完成依赖与配置，自启请自行配置（systemd --user / launchd），
或手动 `npm start` 常驻。

## 配置

CLI 和图形窗口共用配置检查：目录、远端访问、提交身份及分支关联。配置向导可初始化连接空 GitHub 仓库，或克隆已有仓库到空目录。手动管理也可以（也可用 `--config <path>` 或环境变量 `VAULTSYNC_CONFIG` 指定）：

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

多 Vault、冲突自动合并、托盘、Windows Service、
新建远程仓库、Android 端改动 —— 见 `docs/phase6/PHASE6A_VAULTSYNC.md`。
