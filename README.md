# Obsidian Mobile Companion

An independent Android companion for Markdown vaults stored in GitHub repositories. Browse and search notes, render common Obsidian syntax, read cached notes offline, and edit existing notes with explicit conflict handling.

> **Status:** pre-release. There is no published APK yet. This project is not affiliated with or endorsed by Obsidian.md.

## Features

- Connect to a GitHub repository with a fine-grained personal access token.
- Browse files and folders, search note names, and keep favorites and recent notes.
- Render Markdown tables, task lists, Obsidian WikiLinks, callouts, and embedded images/notes.
- Read previously cached notes offline; uncached content requires a network connection.
- Edit existing Markdown notes and save them to GitHub. If the remote note changed while editing, review both versions before resolving the conflict.
- Optional `tools/vaultsync` desktop helper watches one local vault, commits and pushes local changes, and periodically fetches and rebases remote changes.

## Build and test

Requirements: Android SDK 35, JDK 17, and the Android SDK components required by Gradle. The app supports Android 8.0 (API 26) and newer.

```powershell
./gradlew.bat assembleDebug
./gradlew.bat testDebugUnitTest
./gradlew.bat connectedDebugAndroidTest
```

`connectedDebugAndroidTest` requires a running Android emulator or connected device.

The desktop helper requires Node.js and Git:

```powershell
cd tools/vaultsync
npm ci
Copy-Item vaultsync.config.example.json vaultsync.config.json
# Edit vaultsync.config.json and set vaultPath to your own Git-backed vault.
npm test
npm start
```

`vaultsync.config.json` is machine-specific and is ignored by Git. Review the configured vault and remote before starting the helper: it automatically commits and pushes local changes.

## Data and credentials

The Android app calls the GitHub REST API directly; it does not require an app-owned account service. Note content is read from and written to the repository you select. Cached note content and app metadata are stored locally. The GitHub token is encrypted with AES-GCM using a key held by Android Keystore; the app does not put the token in its Room database, DataStore, or logs.

Use a fine-grained token restricted to the repository you want to access and grant only the permissions needed for your workflow. Revoke the token in GitHub if you no longer use the app.

`vaultsync` runs local Git commands against the configured vault. It can commit and push changes automatically, so use it only with a repository whose contents and remote you control.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for local checks and pull request guidance. Please use synthetic notes in tests and never include personal vault content, access tokens, or private screenshots in issues or pull requests.

## 简体中文

这是一个独立的 Android 应用，用于浏览和编辑托管在 GitHub 仓库中的 Markdown 知识库。支持 Obsidian 常用语法、已缓存笔记离线阅读、编辑保存及冲突处理；另附 PC 端 `vaultsync`，可自动提交、推送和拉取指定知识库。

项目仍处于预发布阶段，尚未发布 APK。构建需要 JDK 17 和 Android SDK 35；应用最低支持 Android 8.0（API 26）。测试命令和数据处理说明见上文。请勿将个人知识库、访问令牌或真实业务数据放入公开仓库。

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE).
