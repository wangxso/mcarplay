# Repository Guidelines

## Project Structure & Module Organization

`app/` is the Android application module. Main Kotlin code lives in `app/src/main/java/com/mcarplay/app/`; packages such as `airplay/`, `iap2/`, `bt/`, `video/`, and `input/` separate the CarPlay transport from media and controls. Android resources and assets are in `app/src/main/res/` and `app/src/main/assets/`; the Shizuku AIDL interface is in `app/src/main/aidl/`. The `remote` flavor adds debugging code from `app/src/remote/`. Local JUnit tests are in `app/src/test/java/`. `tools/carlife_probe.py` probes Xiaomi CarLife sockets, and `OcarPlay-analysis.md` records protocol findings.

## Build, Test, and Development Commands

Use JDK 21 and an Android SDK with API 35; set `sdk.dir` in the ignored `local.properties` file. Run commands from the repository root:

- `./gradlew :app:assembleStandardDebug` builds the standard APK under `app/build/outputs/apk/standard/debug/`.
- `./gradlew :app:testStandardDebugUnitTest` runs local JUnit tests.
- `./gradlew :app:lintStandardDebug` checks Android lint against `app/lint-baseline.xml`.
- `./gradlew spotlessCheck` checks formatting; `./gradlew spotlessApply` fixes it.
- `adb install -r app/build/outputs/apk/standard/debug/app-standard-debug.apk` installs a build on a connected device.

## Coding Style & Naming Conventions

Follow `.editorconfig`: UTF-8, LF endings, a final newline, and the official Kotlin style with four-space indentation and a 140-character line limit. Use `UpperCamelCase` for types, `lowerCamelCase` for functions and properties, and package names under `com.mcarplay.app`. Keep protocol constants and byte ordering explicit. Spotless uses ktlint 1.3.1; run it before submitting changes.

## Testing Guidelines

Name JUnit 4 test files `*Test.kt` and place them beside the corresponding package under `app/src/test/java/`. Add focused tests for protocol parsing, framing, state transitions, and regressions. There is no stated coverage threshold. For device changes, record the Xiaomi model, Android/CarWith versions, connection steps, and observed video, audio, and touch behavior; a successful build alone does not establish vehicle compatibility.

## Commit & Pull Request Guidelines

This checkout has no Git history, so no existing commit convention can be verified. Use short, imperative subjects such as `Add CarLife video frame parser`. Pull requests should describe the change, link a relevant issue, list build and test results, and include redacted logs or screenshots for connection or UI changes.

## Security & Configuration

Keep `local.properties`, `keystore.properties`, signing keys, pairing material, and identifiable device logs out of commits. Use `keystore.properties.example` as the signing configuration template.
