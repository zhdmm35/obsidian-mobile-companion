# Beta testing / 试用说明

The v0.1.2 release is an installable prerelease for real-world feedback. Test it with a disposable or otherwise controlled GitHub Markdown repository before using it with important data.

## Suggested test pass

1. Connect a repository with a fine-grained token and browse a folder of Markdown notes.
2. Open one note, disconnect the network, and confirm that the cached note remains readable.
3. Edit and save a note while online, then confirm the change appears in the repository.
4. Change the same note remotely while a local edit is open; verify that the app shows a conflict for review instead of silently overwriting either version.
5. Check WikiLinks, callouts, tables, task lists, frontmatter, and embedded images with synthetic fixtures.
6. Run `tools/vaultsync` on a PC clone and verify that a phone edit reaches the PC and a PC edit reaches the phone.

## v0.1.2 新增验证步骤

1. 在 v0.1.1 保留一条分享草稿和一篇编辑草稿，直接安装 v0.1.2；确认 Token、仓库、收藏仍在，旧分享草稿迁移到草稿中心。
2. 编辑文字、选区和快捷格式，依次撤销/重做；中文输入法组词确认后应能一次撤销组合输入。撤销后输入新内容，重做应禁用。
3. 修改笔记后立即返回 →「保留草稿并退出」，在草稿中心继续编辑；断网/重启应用后仍能恢复。若电脑修改远端同一笔记，保存旧草稿应触发冲突。
4. 连续分享两条不同内容，分别改名/目录并退出；草稿中心应同时显示两条，上传或丢弃其中一条不会影响另一条。引导未完成时收到的分享也应保留。
5. 复制全文核对内容；点丢弃弹窗外部应保留，明确确认才删除。连接另一仓库后，旧仓库的编辑草稿仍可复制，但不能误写到新仓库。

候选版不包含自动上传队列。撤销历史只存在当前编辑会话；突然结束进程可能丢失尚未经过约 1.5 秒防抖的新输入。

## What to report

Use the [beta feedback issue form](.github/ISSUE_TEMPLATE/beta_feedback.yml). Include device model, Android version, app version, test scenario, expected result, actual result, and sanitized logs. Do not include tokens, private repository URLs, or private note content.

The maintainer records download counts from GitHub Releases and counts only real testers, real issues, and verified fixes. No synthetic adoption numbers or fabricated feedback are used.
