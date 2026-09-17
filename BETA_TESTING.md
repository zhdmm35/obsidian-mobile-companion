# Beta testing / 试用说明

The v0.1.1 release is an installable prerelease for real-world feedback. Test it with a disposable or otherwise controlled GitHub Markdown repository before using it with important data.

## Suggested test pass

1. Connect a repository with a fine-grained token and browse a folder of Markdown notes.
2. Open one note, disconnect the network, and confirm that the cached note remains readable.
3. Edit and save a note while online, then confirm the change appears in the repository.
4. Change the same note remotely while a local edit is open; verify that the app shows a conflict for review instead of silently overwriting either version.
5. Check WikiLinks, callouts, tables, task lists, frontmatter, and embedded images with synthetic fixtures.
6. Run `tools/vaultsync` on a PC clone and verify that a phone edit reaches the PC and a PC edit reaches the phone.

## What to report

Use the [beta feedback issue form](.github/ISSUE_TEMPLATE/beta_feedback.yml). Include device model, Android version, app version, test scenario, expected result, actual result, and sanitized logs. Do not include tokens, private repository URLs, or private note content.

The maintainer records download counts from GitHub Releases and counts only real testers, real issues, and verified fixes. No synthetic adoption numbers or fabricated feedback are used.
