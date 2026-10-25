# Third-Party Notices

This inventory reflects the versions declared by this repository. The project
MIT license does not replace dependency or reference-project licenses. Retain
this file, [NOTICE](NOTICE), [LICENSE](LICENSE), and [`docs/licenses/`](docs/licenses/)
when redistributing the source.

## Upstream source

mcarplay derives from [CarDeck](https://github.com/shaonialife/cardeck), under MIT.
The original `Copyright (c) 2026 lizhihua` notice is retained unchanged.
See [CarDeck-MIT.txt](docs/licenses/CarDeck-MIT.txt).

## Application dependencies

| Dependency | Version | License |
| --- | --- | --- |
| AndroidX Core KTX | 1.13.1 | Apache-2.0 |
| AndroidX AppCompat | 1.7.0 | Apache-2.0 |
| AndroidX ViewPager2 | 1.1.0 | Apache-2.0 |
| AndroidX RecyclerView | 1.3.2 | Apache-2.0 |
| Kotlin standard library | 2.0.21 | Apache-2.0 |
| Bouncy Castle `bcprov-jdk18on` | 1.78.1 | [Bouncy Castle license](docs/licenses/Bouncy-Castle.txt), MIT-style |
| JmDNS | 3.5.12 | Apache-2.0, as declared in its published Maven POM |
| SLF4J API and NOP | 2.0.7 | [MIT](docs/licenses/SLF4J-MIT.txt) |
| Shizuku API and provider | 13.1.5 | [MIT](docs/licenses/Shizuku-API-MIT.txt) |

Gradle resolves additional transitive dependencies. Run
`./gradlew :app:dependencies --configuration standardReleaseRuntimeClasspath`
for the complete resolved graph of a particular build. The table names direct
dependencies and does not claim to be a full transitive-license audit.

## Development and diagnostic dependencies

| Dependency | Version | License |
| --- | --- | --- |
| JUnit 4 | 4.13.2 | [EPL-1.0](docs/licenses/JUnit-EPL-1.0.txt) |
| JSON-java | 20240303 | [Public domain](docs/licenses/JSON-java-Public-Domain.txt) |
| Python cryptography | 50.0.2 | [Apache-2.0 OR BSD-3-Clause](https://github.com/pyca/cryptography/blob/main/LICENSE) |
| Gradle Wrapper / Gradle | 8.10.2 | Apache-2.0 |
| Android Gradle Plugin | 8.7.3 | Apache-2.0 |
| Kotlin Gradle Plugin | 2.0.21 | Apache-2.0 |
| Spotless | 7.2.1 | Apache-2.0 |
| ktlint | 1.3.1 | MIT |

See [Apache-2.0.txt](docs/licenses/Apache-2.0.txt) for the Apache license text.
JUnit and JSON-java are test dependencies. Python cryptography is used by the
diagnostic probe and is not an Android runtime library.

## Receiver and protocol research

- [DiPlay](https://github.com/shihabal3amri/DiPlay): bench-test receiver; see its
  [own notices](https://github.com/shihabal3amri/DiPlay/blob/main/docs/THIRD_PARTY_NOTICES.md).
- [xcertplay](https://github.com/shilapi/xcertplay): receiver protocol research,
  acknowledged by upstream and DiPlay.
- catplay: protocol reference cited in inherited source comments; it is not a
  Gradle dependency. This acknowledgement grants no license to its code.
- OcarPlay: third-party APK examined during feasibility research; no APK is
  included in the source upload package.

These acknowledgements do not claim ownership of third-party work or relicense
it. No CarWith, CarLife, OcarPlay, DiPlay or xcertplay APK, extracted private key,
or external research-source tree belongs in the source distribution.
