# Obsidian Mobile Companion

![Cover / 封面](artwork/cover.jpg)

An independent Android companion app for Markdown vaults stored in GitHub repositories. Browse and search your notes, render common Obsidian syntax, read cached notes offline, and edit existing notes with explicit conflict handling. Ships with `tools/vaultsync`, a small desktop daemon that keeps a PC-side vault directory in sync with the same GitHub repository.

一个独立的 Android 应用，用于浏览和编辑托管在 GitHub 仓库中的 Markdown 知识库。支持浏览与搜索笔记、渲染 Obsidian 常用语法、离线阅读已缓存笔记、编辑保存并显式处理冲突。另附 PC 端 `tools/vaultsync` 小工具，可将本地 Vault 目录与同一个 GitHub 仓库保持同步。

> **Status / 状态:** pre-release（预发布）。There is no signed release APK yet; the debug APK on the Releases page is for personal testing only. 还没有正式签名版 APK，Releases 页面上的是 debug 测试包。This project is not affiliated with or endorsed by Obsidian.md. 本项目与 Obsidian.md 无任何关联或背书关系。

---

## Features / 功能

**Android app / 安卓应用**

- Connect to any GitHub repository with a fine-grained personal access token; browse files and folders, search note names, and keep favorites and recent notes.
  使用 fine-grained 个人访问令牌连接任意 GitHub 仓库；浏览文件与文件夹、按笔记名搜索、收藏与最近打开。
- Render Markdown tables, task lists, Obsidian WikiLinks, callouts, frontmatter, and embedded images/notes (unsupported syntax degrades gracefully to plain text).
  渲染 Markdown 表格、任务列表、Obsidian WikiLink、Callout、Frontmatter 以及图片/笔记嵌入（暂不支持的语法会降级为原文显示，不会报错）。
- View vault images full-screen: tap an image in the file browser or inside a note, pinch or double-tap to zoom, and swipe to browse other images in the same folder. Works offline for cached images.
  图片全屏查看：在文件浏览页或笔记中点击图片打开，支持双指/双击缩放与拖动，左右滑动浏览同目录图片；已缓存图片离线可看。
- Read previously cached notes offline; uncached content requires a network connection.
  已缓存的笔记可离线阅读；未缓存内容需要联网。
- Edit existing Markdown notes and save them to GitHub. If the remote note changed while you were editing, the app shows both versions and lets you review before resolving the conflict — no silent overwrite.
  编辑已有 Markdown 笔记并保存回 GitHub。如果编辑期间远端被改动，应用会并列展示两个版本供你确认，绝不静默覆盖。
- Automatic refresh with a freshness window, foreground and network-recovery triggers; background fetches are deduplicated.
  支持自动刷新（新鲜度窗口 + 前台与网络恢复触发），后台请求自动去重。

**Desktop helper (`tools/vaultsync`) / PC 端同步工具**

- Watches one local vault directory and automatically commits and pushes changes (30 s debounce).
  监听本地 Vault 目录，文件变化后自动提交并推送（30 秒防抖）。
- Periodically fetches and rebases remote changes (every 5 min by default), so edits made on the phone arrive on the PC.
  定时拉取远端改动并 rebase（默认每 5 分钟），手机上的编辑会同步回电脑。
- Rebase conflicts abort safely and keep both sides; network failures are logged and retried on the next cycle.
  冲突时安全中止、双方内容都保留；网络失败只记日志，下个周期自动重试。
- Optional Windows autostart via the Startup folder (see `tools/vaultsync/README.md`).
  支持通过「启动」文件夹实现 Windows 开机自启（见 `tools/vaultsync/README.md`）。

## How it works / 工作原理

```
┌────────────┐   GitHub REST API   ┌──────────────────┐   git push/fetch   ┌─────────────┐
│ Android app│ ◄─────────────────► │ GitHub repository│ ◄────────────────► │ PC vaultsync │
└────────────┘                     └──────────────────┘                    └─────────────┘
```

- The app talks to the GitHub REST API directly; there is no app-owned backend service and no account server. Note content is read from and written to the repository you choose.
  应用直连 GitHub REST API，没有自建后端服务，也没有账号服务器。笔记内容只读写你指定的仓库。
- vaultsync runs plain `git` commands (`status → add → commit → fetch → rebase → push`) against the configured vault, which must already be a git clone of the same repository.
  vaultsync 对配置的 Vault 目录执行普通 git 命令（`status → add → commit → fetch → rebase → push`），该目录必须是同一仓库的 git 克隆。

## Build and test / 构建与测试

Requirements / 环境要求：Android SDK 35, JDK 17。The app supports Android 8.0 (API 26) and newer. 应用最低支持 Android 8.0（API 26）。

```powershell
# Debug build / 构建 debug 包
./gradlew.bat assembleDebug

# Unit tests / 单元测试
./gradlew.bat testDebugUnitTest

# Instrumented tests (needs a running emulator or device) / 仪器化测试（需模拟器或真机）
./gradlew.bat connectedDebugAndroidTest
```

The desktop helper requires Node.js and Git / 桌面同步工具需要 Node.js 和 Git：

```powershell
cd tools/vaultsync
npm ci
Copy-Item vaultsync.config.example.json vaultsync.config.json
# Edit vaultsync.config.json and set vaultPath to your own Git-backed vault.
# 编辑 vaultsync.config.json，把 vaultPath 改成你自己的 git 仓库 Vault 路径。
npm test
npm start
```

`vaultsync.config.json` is machine-specific and is ignored by Git. Review the configured vault and remote before starting the helper: it automatically commits and pushes local changes.

`vaultsync.config.json` 是本机配置，已被 Git 忽略。启动前请核对 Vault 路径和远端仓库：它会自动提交并推送本地改动。

## Setup / 使用配置

**Android app / 应用侧**

1. Create a fine-grained personal access token at GitHub → Settings → Developer settings, restricted to the repository you want to access, with the minimum permissions your workflow needs (contents read/write).
   在 GitHub → Settings → Developer settings 创建 fine-grained 令牌，限定到目标仓库，按需授予最小权限（contents 读写）。
2. Install the APK, enter the token and repository, and start browsing.
   安装 APK，填入令牌和仓库地址即可使用。
3. Revoke the token in GitHub if you no longer use the app.
   不再使用时请在 GitHub 吊销令牌。

**PC sync / PC 同步侧**

See `tools/vaultsync/README.md` for configuration, logs, Windows autostart, and troubleshooting.

配置、日志、Windows 开机自启与排查方法见 `tools/vaultsync/README.md`。

## Data and credentials / 数据与凭据

- The GitHub token is encrypted with AES-GCM using a key held by Android Keystore; the app never puts the token in its Room database, DataStore, or logs.
  令牌使用 Android Keystore 持有的密钥以 AES-GCM 加密存储，不会进入 Room 数据库、DataStore 或日志。
- Cached note content and app metadata are stored locally on the device.
  笔记缓存和应用元数据只存在设备本地。
- vaultsync runs local git commands and can commit and push automatically — use it only with a repository whose contents and remote you control.
  vaultsync 执行本地 git 命令、会自动提交推送——只用于你能掌控内容与远端的仓库。

## Contributing / 参与贡献

See [CONTRIBUTING.md](CONTRIBUTING.md) for local checks and pull request guidance. Please use synthetic notes in tests and never include personal vault content, access tokens, or private screenshots in issues or pull requests.

本地检查与 PR 规范见 [CONTRIBUTING.md](CONTRIBUTING.md)。测试请使用虚构笔记；不要在 issue 或 PR 中包含个人知识库内容、访问令牌或隐私截图。

## License / 许可证

This project is licensed under the MIT License. See [LICENSE](LICENSE).

本项目以 MIT 许可证发布，详见 [LICENSE](LICENSE)。
