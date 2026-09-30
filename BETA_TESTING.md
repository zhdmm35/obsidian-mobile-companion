# Beta testing / 试用说明

The v0.1.3 release is an installable prerelease for real-world feedback. Test it with a disposable or otherwise controlled GitHub Markdown repository before using it with important data.

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

## v0.1.3 性能与升级验证

1. 从 v0.1.1 或 v0.1.2 直接覆盖安装 v0.1.3，无需卸载；核对 Token、仓库、收藏、编辑草稿与多条分享草稿仍在。
2. 分享内容后连续改名/目录，立即返回或切后台，重新打开应保留最后一次修改；复制全文核对长正文没有被列表摘要截断。
3. 在较多缓存笔记中搜索正文，扫描期间应显示提示；快速改换关键词或清空，不应出现旧查询结果。未打开过的笔记仍不参与正文搜索。
4. 打开长笔记预览并滚动；打开大图，双击或双指放大、拖动、还原，再滑动浏览同目录图片。
5. 记录真实设备的启动、输入、滚动与搜索体验。模拟器的内存对比见 [性能记录](PERFORMANCE.md)，不作为真机帧率保证。

分享草稿改名/目录约 250 ms 合并写入；正常返回会等待保存，切后台会安排立即写入。强制结束进程或断电仍可能丢失尚未落盘的输入。

## What to report

Use the [beta feedback issue form](.github/ISSUE_TEMPLATE/beta_feedback.yml). Include device model, Android version, app version, test scenario, expected result, actual result, and sanitized logs. Do not include tokens, private repository URLs, or private note content.

The maintainer records download counts from GitHub Releases and counts only real testers, real issues, and verified fixes. No synthetic adoption numbers or fabricated feedback are used.
