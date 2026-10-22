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

## 当前开发版：后台暂存与 Inbox 收集

1. 编辑后不等 1.5 秒就切后台，重新打开并恢复草稿，核对最后输入；再验证「丢弃修改并退出」后不会重新出现草稿。正常切后台会立即安排写入，强制结束进程或断电仍不能保证尚未完成的落盘。
2. 分享内容并修改目录，切后台或返回后再次分享，确认新分享沿用该仓库的目录；打开旧草稿时原位置不变。切换仓库，确认目录记忆互不影响。
3. 在收集页选择「追加到 Inbox.md」，确认弹窗显示完整目标路径；取消不上传。确认后已有正文保留，缺失的文件自动创建，收集草稿仅在成功后删除。
4. 在电脑与手机同时修改 Inbox，确认版本冲突时提示重试且保留收集草稿；手机已有同路径的编辑草稿时，追加不应覆盖或删除它。
5. 模拟上传后响应中断，再次追加同一条草稿，确认只写入一次；两次独立分享相同正文应保留两份。

Inbox 源文件会保留形如 `<!-- inkstone-capture:… -->` 的隐藏注释，阅读时不显示，用于识别已成功追加的分享。手工删除该标记后，旧草稿重试将无法识别之前的上传。

## 当前开发版：今日笔记与内置模板

1. 首页点击「今日笔记」，核对手机本地日期、仓库和完整目标路径。日记目录留空时使用根目录，文件名固定为 `YYYY-MM-DD.md`；确认前不会创建文件。
2. 选择日记或会议记录模板，确认初始正文；目标已存在时应直接打开原笔记，不替换正文。已有同路径编辑草稿也应保留。新建需要联网，已缓存的日记可离线打开。
3. 在电脑先创建同名日记、手机尚未刷新索引时点击「打开或创建」，应打开电脑的内容；在创建过程中重复点击不能产生多个请求。
4. 更改日记目录和模板并确认，再次打开应沿用选择；切换另一仓库后应使用该仓库自己的设置。取消对话框不改变偏好。
5. 全部笔记 → 新建，输入笔记名并选择模板，预览标题与日期；创建后编辑器正文应与预览一致。空白模板继续创建空文件，同名笔记仍提示换名。
6. 小屏、横屏和字体放大时，确认表单可以滚动，模板选项与确认/取消按钮可操作；跨午夜停留在今日笔记对话框时，确认仍使用展示的日期，重新打开后更新到新日期。

## 当前开发版：阅读设置与任务勾选

1. 阅读页 → 更多 → 阅读设置，连续调整字号并切换紧凑/标准/宽松行距；正文和预览应立即更新。关闭再打开应用、换另一篇笔记后应记住本机选择，「恢复默认」还原 17 号与标准行距。
2. 小屏、横屏及系统字体放大时，确认设置内容可滚动，底部「完成」「恢复默认」可操作；调整样式不能改变笔记原文。
3. 含任务的笔记 → 更多 → 勾选任务，连续勾选未完成和已完成项目；普通列表项不应变成复选框。嵌套列表、引用、Callout 内任务也应能操作，代码块和转义标记不能误修改。
4. 返回编辑，撤销/重做勾选，核对 Frontmatter、CRLF、缩进及其余正文未改变。取消 → 保留草稿并退出，再进入并恢复，应保留任务状态；确认保存后才上传到 GitHub。
5. 断网勾选并尝试保存，应保留本机草稿；电脑同时修改该笔记时，保存应进入现有冲突流程。只读仓库的「勾选任务」入口应显示权限说明。

## What to report

Use the [beta feedback issue form](.github/ISSUE_TEMPLATE/beta_feedback.yml). Include device model, Android version, app version, test scenario, expected result, actual result, and sanitized logs. Do not include tokens, private repository URLs, or private note content.

The maintainer records download counts from GitHub Releases and counts only real testers, real issues, and verified fixes. No synthetic adoption numbers or fabricated feedback are used.
