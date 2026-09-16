# Contributing

Thanks for your interest in Obsidian Mobile Companion. Contributions are welcome.

## Before opening an issue or pull request

- Describe the expected behavior and the steps to reproduce a problem.
- Include the Android version and device or emulator details when relevant.
- Remove access tokens, private repository names, note contents, local paths, and personal data from logs and screenshots.
- Keep changes focused. Add or update tests for behavior changes.

## Local checks

Run the Android unit tests and build:

```powershell
./gradlew.bat testDebugUnitTest assembleDebug
```

For instrumented tests, start an emulator or connect an Android device and run:

```powershell
./gradlew.bat connectedDebugAndroidTest
```

For the desktop helper:

```powershell
cd tools/vaultsync
npm ci
npm test
```

Do not run integration checks against a personal vault. Use synthetic test repositories and notes.

## Pull requests

Explain the user-visible change, the tests you ran, and any known limitation. Avoid bundling unrelated formatting or generated files.
