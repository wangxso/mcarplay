# mcarplay

<img src="docs/branding/mcarplay-icon.png" width="96" height="96" alt="mcarplay app icon">

**[English](README.md) | [简体中文](README.zh-CN.md)**

Bridge Xiaomi CarWith to a wireless CarPlay receiver using an Android APK.
mcarplay receives native CarWith video and audio on the phone, sends them to the
receiver, and routes receiver touch events back to CarWith. The displayed
interface is CarWith, rather than the iOS CarPlay application interface.

## Status

Experimental, with a working Xiaomi-to-DiPlay test setup as of October 26, 2026.

| Capability | Validation |
| --- | --- |
| Native video | 1280 x 720, approximately 20 fps on DiPlay |
| Receiver touch | Page changes verified on the test receiver |
| Native music | 44.1 kHz PCM converted to 48 kHz AAC-LC; audible playback confirmed |
| Combined playback | Approximately 6 minutes 36 seconds without audio drops, underruns or write errors |
| Real vehicle compatibility | Not yet verified for the mcarplay CarWith bridge |
| Navigation announcements, pause/resume, extended stability | Further validation required |

Test phone: Xiaomi 17 Pro Max (`2509FPN0BC`), HyperOS `OS4.0.4.0.XPBCNXM`,
CarWith `4.0.20-20260923`, Xiaomi CarLife `8.8.4`.
Test receiver: PA2353 Android 14 tablet running DiPlay 0.2.21.
The minimum SDK is Android 12 / API 31; this does not establish compatibility
with every Android 12+ device. Upstream CarDeck vehicle results are not mcarplay
vehicle compatibility claims.

## Known limitations and roadmap

| Area | Known status | Next step | Priority |
| --- | --- | --- | --- |
| Real vehicle testing | The current validation uses a DiPlay bench receiver; mcarplay has not been validated with a car's head unit. | Test pairing, touch controls, audio, ignition cycles and reconnect behavior with real vehicles and record the vehicle/receiver combinations. | High |
| Phone brands and system versions | Verified on one Xiaomi 17 Pro Max configuration. The bridge depends on CarWith and Xiaomi CarLife components, so other Android brands are unverified and may not expose compatible services. | Build a phone/OS/CarWith compatibility matrix; explore other brands where a compatible CarWith service is available. | High |
| Session stability | Combined video and music were verified for about 6 minutes 36 seconds; longer sessions and interruptions are not covered. | Test longer runs, incoming navigation prompts, pause/resume, disconnects and recovery after reconnect. | High |
| Receiver compatibility | The documented bench result is for one PA2353 tablet running DiPlay. | Try additional wireless CarPlay receivers and document pairing, video, audio and touch results. | Medium |

These are planned validation steps, not compatibility guarantees. Results may vary
with the phone, CarWith version, receiver and vehicle.

## Install and connect

1. Download an APK from this repository's **Actions** artifacts or **Releases**.
   See [automatic builds](#automatic-apk-builds). No external download mirror is maintained here.
2. Install it on a Xiaomi phone with CarWith and the Xiaomi CarLife component.
3. Pair the phone with the wireless CarPlay receiver and follow its Wi-Fi/hotspot
   flow. Select the intended receiver in mcarplay and start the connection.
4. Choose whether to enable sound. Native CarWith audio requires no separate
   screen-recording grant in mcarplay.
5. For bench testing, use a second Android device running [DiPlay](https://github.com/shihabal3amri/DiPlay).
   Disconnect other CarLife receivers before starting the native session.

The core CarWith path uses ordinary application intents and local sockets without
root or Shizuku. Optional display/launcher utilities inherited from CarDeck may
still use [Shizuku](https://shizuku.rikka.app/).
If pairing stalls after reinstalling, restart the test receiver to clear its previous session.

## Architecture

```text
Xiaomi CarWith / CarLife
    -> authenticated local CarLife channels
    -> H.264 + native PCM + touch translation
    -> Bluetooth iAP2 + Wi-Fi / AirPlay
    -> wireless CarPlay receiver
```

CarWith creates the source display and encoder; mcarplay consumes its media and
handles the receiver transport. No CarWith, CarLife, OcarPlay or receiver APK is
included in the source distribution.

## Build locally

Use JDK 21, Android SDK Platform 35 and Build Tools 35.0.0. Set `ANDROID_HOME`,
or set `sdk.dir=/path/to/Android/sdk` in an untracked `local.properties` file.

```bash
./gradlew spotlessCheck :app:testStandardDebugUnitTest \
  :app:lintStandardDebug :app:assembleStandardDebug
```

Output: `app/build/outputs/apk/standard/debug/app-standard-debug.apk`.
Use `./gradlew spotlessApply` to format Kotlin. `standard` is the normal flavor;
`remote` contains additional development tools.

Optional diagnostic-tool tests:

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -r tools/requirements.txt
.venv/bin/python -m unittest discover -s tools -p 'test_*.py'
```

## Automatic APK builds

Workflows work with any repository owner, without changing repository URLs:

| Workflow | Trigger | Output |
| --- | --- | --- |
| **Android CI** | Branch push, pull request or manual run | Debug APK, SHA-256 checksum and check reports |
| **Signed APK** | `v*` tag push or manual run | Signed release APK, checksum and signing-certificate report |

**Android CI needs no signing secrets.** Open **Actions → Android CI → a successful
run → Artifacts**, download `mcarplay-debug-<run number>` and extract the APK.
Artifacts expire after 14 days. Hosted-runner debug certificates can change
between runs; these test APKs may require uninstalling an older build.

For stable release signing, add repository secrets under
**Settings → Secrets and variables → Actions**:

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | Base64-encoded release keystore file |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Signing-key alias |
| `KEY_PASSWORD` | Signing-key password |

Follow [GitHub setup](docs/GITHUB_SETUP.md) to generate a keystore and publish a tag.
A stable tag such as `v0.1.0`, or a test tag such as `v0.1.0-test.1`, must match
`appVersionName` in `app/build.gradle.kts`; test tags publish as GitHub prereleases.
Manual runs only upload artifacts.
Missing secrets fail the signed workflow; it never substitutes a debug certificate.
Retain the same private signing key for subsequent updates.

For local signing, copy `keystore.properties.example` to `keystore.properties`,
fill in your own values, and run:

```bash
./gradlew -Pmcarplay.requireReleaseSigning=true :app:assembleStandardRelease
```

## Repository layout

- `app/src/main/java/com/mcarplay/app/`: CarLife bridge, CarPlay transport, media and UI.
- `app/src/test/java/`: JUnit protocol, crypto, audio and lifecycle tests.
- `tools/`: diagnostic probe, Python tests and clean upload preparation.
- `docs/`: device findings, GitHub setup and license texts.
- [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md): milestones and acceptance criteria.
- [AGENTS.md](AGENTS.md): contributor commands and conventions.

Run `python3 tools/prepare_upload.py` for a source-only upload folder and ZIP
under `dist/`. SDK settings, signing files, caches, logs, captures and reference
APKs are excluded. When uploading through GitHub's website, include `.github/`
and the root dotfiles.

## Acknowledgements

mcarplay builds upon [CarDeck](https://github.com/shaonialife/cardeck) by shaonialife
and its contributors. Its MIT copyright notice is retained in [LICENSE](LICENSE)
and [NOTICE](NOTICE). Thanks to [DiPlay](https://github.com/shihabal3amri/DiPlay)
and [xcertplay](https://github.com/shilapi/xcertplay) for receiver research and the
test environment; these are separate projects with their own licenses.

Thanks also to the libraries and tools used by this repository:

| Project | Role | License |
| --- | --- | --- |
| [AndroidX](https://developer.android.com/jetpack/androidx) | Core, AppCompat, RecyclerView, ViewPager2 | Apache-2.0 |
| [Kotlin](https://github.com/JetBrains/kotlin) | Language and standard library | Apache-2.0 |
| [Bouncy Castle](https://github.com/bcgit/bc-java) | Pairing and transport cryptography | Bouncy Castle license |
| [JmDNS](https://github.com/jmdns/jmdns) | DNS-SD discovery | Apache-2.0 |
| [SLF4J](https://www.slf4j.org/) | Logging API and no-op backend | MIT |
| [Shizuku API](https://github.com/RikkaApps/Shizuku-API) | Optional shell utilities | MIT |
| [JUnit 4](https://github.com/junit-team/junit4) | JVM tests | EPL-1.0 |
| [JSON-java](https://github.com/stleary/JSON-java) | JSON for local tests | Public domain |
| [cryptography](https://github.com/pyca/cryptography) | Python diagnostics | Apache-2.0 OR BSD-3-Clause |
| [Gradle](https://github.com/gradle/gradle), [Spotless](https://github.com/diffplug/spotless), [ktlint](https://github.com/pinterest/ktlint) | Build and formatting | Apache-2.0 / Apache-2.0 / MIT |

Versions, the distinction between runtime dependencies and research references,
and license texts are documented in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## License and use

Source is distributed under [MIT](LICENSE), subject to preserved upstream and
dependency notices. This unofficial experimental project is unaffiliated with
Apple, Xiaomi or vehicle manufacturers. Configure and test while parked.
See [DISCLAIMER.md](DISCLAIMER.md) for scope, permissions and limitations.
