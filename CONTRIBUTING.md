# Contributing

Thanks for your interest in Obsidian Mobile Companion. Contributions are welcome.

## Before opening an issue or pull request

- Describe the expected behavior and the steps to reproduce a problem.
- Include the Android version and device or emulator details when relevant.
- Remove access tokens, private repository names, note contents, local paths, and personal data from logs and screenshots.
- Keep changes focused. Add or update tests for behavior changes.

## Local checks

Run the Android unit tests, lint and debug/unsigned release builds:

```powershell
./gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleRelease
```

For instrumented tests, start an emulator or connect an Android device and run:

```powershell
./gradlew.bat connectedDebugAndroidTest
```

Room schemas in `app/schemas/` are versioned history. Keep old versions unchanged;
export the new schema when changing database structure and add a migration test.

CI excludes the synthetic search benchmark from its regression run. Run it alone
to compare search timing (500 cached notes, about 31 MiB):

```powershell
./gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.obsidiancompanion.feature.search.ContentSearchBenchmarkTest
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
