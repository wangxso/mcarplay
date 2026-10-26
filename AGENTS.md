# Repository Guidelines

mcarplay is an experimental Android APK that bridges Xiaomi CarWith to a wireless CarPlay receiver: it consumes CarWith's native
video, audio and touch over local CarLife channels and acts as the CarPlay phone side (Bluetooth iAP2 + Wi-Fi/AirPlay). It is
derived from CarDeck (MIT); keep upstream notices in `LICENSE`, `NOTICE` and `THIRD_PARTY_NOTICES.md`.

## Project Structure & Module Organization

`app/` is the only Gradle module. Main Kotlin code lives in `app/src/main/java/com/mcarplay/app/`:

- `carlife/`: CarWith/CarLife bridge — seven-channel connector, authentication and seed exchange, protocol, state machine,
  video/audio parsers, touch, session registry, plus EasyConn/External-HU discovery, mDNS and Wi-Fi Direct (P2P) helpers.
- `airplay/`, `iap2/`, `bt/`, `net/`, `transport/`: CarPlay transport — AirPlay pairing and crypto, binary plist, iAP2 link and
  sessions, Bluetooth RFCOMM, receiver discovery and Wi-Fi join.
- `video/`, `audio/`, `input/`, `capture/`, `render/`: media senders (H.264, AAC-LC with PCM resampling), HID touch/keys and display.
- `diagnostics/`: connection timeline, error codes, redaction and the support report; `util/` holds `DebugFileLog` and `LogExporter`.
- `launcher/`, `ui/`, `media/`, `legal/`, `shizuku/`, `service/`: CarDeck-derived launcher/settings UI, disclaimer, notification
  media listener, optional Shizuku utilities and foreground services.
- `SessionController.kt` orchestrates a connection; `MainActivity.kt` is the main connection screen.

Resources and assets are in `app/src/main/res/` and `app/src/main/assets/`; the Shizuku AIDL interface is
`app/src/main/aidl/com/mcarplay/app/shizuku/ICarInput.aidl`. Other source sets:

- `app/src/debug/`: debug-only `BtAnchorService`, used only for bench emulation; it is not evidence for no-ADB or vehicle support.
- `app/src/remote/`: the `remote` flavor's remote-debug bridge (`bridge/`); it must never leak into `standard`.
- `app/src/test/java/`: JVM unit tests, including protocol replay under `replay/`; fixtures notes in `app/src/test/resources/carplay/fixtures/`.

Python tools in `tools/`: `carlife_probe.py` (CarLife loopback probe, writes raw H.264), `analyze_diagnostics.py` (offline summary
of a redacted support report), `release_notes.py` (extracts a changelog entry for a tag) and `prepare_upload.py` (source-only
upload bundle under `dist/`), each with `test_*.py`. Documentation:

- `IMPLEMENTATION_PLAN.md` (Chinese): milestones, current baseline and acceptance criteria; update it when status changes.
- `docs/CARWITH-ENTRY-FINDINGS.md`: dated, redacted device evidence and corrections of earlier conclusions.
- `docs/DIAGNOSTICS.md`: report contents, retention and error codes; keep it in sync with `diagnostics/`.
- `docs/GITHUB_SETUP.md`: keystore and tag publishing. `README.md` and `README.zh-CN.md` must stay equivalent.

## Build, Test, and Development Commands

Use JDK 21 (bytecode targets Java 17), Android SDK Platform 35 and Build Tools 35.0.0; set `ANDROID_HOME` or `sdk.dir` in the
ignored `local.properties`. `minSdk` is 31, `targetSdk` 34. Run from the repository root:

- Full gate, as in CI: `./gradlew spotlessCheck :app:testStandardDebugUnitTest :app:lintStandardDebug :app:assembleStandardDebug`.
- `./gradlew spotlessApply` formats Kotlin and `*.gradle.kts`.
- `./gradlew :app:updateLintBaseline` rebuilds `app/lint-baseline.xml`; do it only for intentional exemptions. Lint aborts on errors.
- Python tests: `python3 -m venv .venv && .venv/bin/python -m pip install -r tools/requirements.txt`, then
  `.venv/bin/python -m unittest discover -s tools -p 'test_*.py'`.
- `adb install -r app/build/outputs/apk/standard/debug/app-standard-debug.apk` installs a debug build.
- `./gradlew :app:assembleRemoteDebug` builds the `remote` flavor (package `com.mcarplay.app.rd`, installable alongside).
- Local signed build: copy `keystore.properties.example` to `keystore.properties`, then
  `./gradlew -Pmcarplay.requireReleaseSigning=true :app:assembleStandardRelease`.
- On-device debug log: `adb shell run-as com.mcarplay.app cat files/main_debug.log`.

Enable the repository hook once per clone with `git config core.hooksPath .githooks`. `.githooks/pre-commit` blocks
signing files, local configuration, captures and replay fixtures, then runs Spotless, lint and unit tests for app/Gradle
changes and the Python tests for `tools/` changes; documentation-only commits skip the checks. `MCARPLAY_HOOK_SKIP_TESTS=1`
skips only unit tests; use `git commit --no-verify` sparingly, since CI still runs the full gate. Do not add an `applicationIdSuffix` to `standard` or
`debug`: Shizuku grants and the `run-as` path are bound to `com.mcarplay.app`. R8/minification is intentionally off.

## Coding Style & Naming Conventions

Follow `.editorconfig`: UTF-8, LF endings, a final newline and `ktlint_official` style (ktlint 1.3.1 via Spotless) with four-space
indentation and a 140-character line limit. Explicit imports only; consecutive comments are allowed. Use `UpperCamelCase` for
types, `lowerCamelCase` for functions and properties, and packages under `com.mcarplay.app`. Keep protocol constants, byte
ordering and port numbers explicit. Existing KDoc and comments are often Chinese; either language is fine, but keep the
surrounding file consistent. Do not block video, audio or UI threads on disk I/O; diagnostics write on a background thread.

## Testing Guidelines

Name JUnit 4 files `*Test.kt` and mirror the production package under `app/src/test/java/`. Add focused tests for protocol
parsing, framing, crypto, state transitions, lifecycle/shutdown races and regressions. Android calls return defaults in unit
tests (`isReturnDefaultValues`), and `org.json` is replaced by the reference implementation. Recorded vehicle replay fixtures
(`*.jsonl`) are git-ignored because they hold device and hotspot identifiers; tests must not depend on them being present.
There is no coverage threshold.

Builds and bench results do not establish vehicle compatibility. For device changes, record the phone model, HyperOS, CarWith
and Xiaomi CarLife versions, the receiver and firmware, connection steps, and observed video, audio and touch, then add dated
evidence to `docs/CARWITH-ENTRY-FINDINGS.md`. Never claim a vehicle or phone is supported without that evidence.

## Versioning, Changelog & Releases

`appVersionName` in `app/build.gradle.kts` is the single version source; `versionCode` is derived from it. `-Pmcarplay.buildNumber`
(1..9999) appends a build number and requires release signing. Tags are `vX.Y.Z` or `vX.Y.Z-test.N` and must match
`appVersionName`; test tags publish as GitHub prereleases. Before tagging, add an exact `## [vX.Y.Z-test.N] - YYYY-MM-DD` entry at
the top of `CHANGELOG.md`, with English then Simplified Chinese, a validation line (test counts and checks run) and a compare
link; the signed workflow publishes that entry and stops if it is missing, empty or duplicated. CI skips documentation-only
changes. Release signing never falls back to a debug certificate; keep the same key for upgrades.

## Commit & Pull Request Guidelines

Use short, imperative subjects, e.g. `Fix CarLife shutdown and document device findings` or
`Prepare v0.1.0-test.4 prerelease`. Pull requests should describe the change, link a relevant issue, list build and test
results, and include redacted logs, a support-report excerpt or screenshots for connection or UI changes.

## Security & Configuration

Keep `local.properties`, `keystore.properties`, keystores, `.env`, pairing material, captures (`*.h264`, `*.pcap`), logs,
replay fixtures, `dist/`, `reference/` and third-party APKs out of commits. Do not bundle CarWith, CarLife, OcarPlay or receiver
APKs. Support reports must stay redacted and are never uploaded automatically; extend `DiagnosticRedactor` and its tests when
adding new report fields.
