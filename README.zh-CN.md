# mcarplay

<img src="docs/branding/mcarplay-icon.png" width="96" height="96" alt="mcarplay 应用图标">

[![Android CI](https://github.com/wangxso/mcarplay/actions/workflows/android-ci.yml/badge.svg)](https://github.com/wangxso/mcarplay/actions/workflows/android-ci.yml)
[![最新版本](https://img.shields.io/github/v/release/wangxso/mcarplay?include_prereleases&logo=android)](https://github.com/wangxso/mcarplay/releases)
[![Release 下载量](https://img.shields.io/github/downloads/wangxso/mcarplay/total?logo=github)](https://github.com/wangxso/mcarplay/releases)
[![Android 12+](https://img.shields.io/badge/Android-12%2B-3DDC84?logo=android&logoColor=white)](#安装和连接)
[![MIT 许可证](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![实验阶段](https://img.shields.io/badge/Status-experimental-orange)](#当前进度)
[![GitHub Stars](https://img.shields.io/github/stars/wangxso/mcarplay?style=flat&logo=github)](https://github.com/wangxso/mcarplay/stargazers)

**[English](README.md) | [简体中文](README.zh-CN.md)**

[更新日志](CHANGELOG.md) · [版本下载](https://github.com/wangxso/mcarplay/releases)

用一个 Android APK，把小米 CarWith 桥接到无线 CarPlay 接收端。
mcarplay 在手机本机接收 CarWith 的原生视频和音频，发送到接收端，再把车机触控回传给
CarWith。车机显示的是 CarWith 界面。

## 当前进度

项目处于实验阶段，截至 2026 年 10 月 26 日已跑通小米到 DiPlay 的测试链路。

| 能力 | 验证结果 |
| --- | --- |
| 原生视频 | DiPlay 显示 1280 × 720，约 20fps |
| 接收端触控 | 已验证页面切换 |
| 原生音乐 | 44.1kHz PCM 转 48kHz AAC-LC，用户确认声音、音调和速度正常 |
| 音视频同时运行 | 连续约 6 分 36 秒，无音频丢包、欠载或写入错误 |
| mcarplay CarWith 桥接的实车适配 | 尚待验证 |
| 导航提示、暂停恢复及长时间稳定性 | 仍需专项验收 |

测试手机：小米 17 Pro Max（`2509FPN0BC`），HyperOS `OS4.0.4.0.XPBCNXM`，
CarWith `4.0.20-20260923`，小米 CarLife `8.8.4`。
测试接收端：PA2353 / Android 14 平板，DiPlay 0.2.21。
应用最低 SDK 为 Android 12 / API 31，这不代表所有 Android 12 及以上设备均兼容。
上游 CarDeck 的实车测试不能直接作为 mcarplay CarWith 桥接的车型适配结论。

## 已知限制与后续计划

| 项目 | 已知情况 | 后续计划 | 优先级 |
| --- | --- | --- | --- |
| 实车测试 | 目前使用 DiPlay 桌面接收端验证，尚未在真实汽车车机上验证 mcarplay。 | 在真实车辆上测试配对、触控、音频、点火循环和断线重连，并记录车型与接收端组合。 | 高 |
| 手机品牌与系统版本 | 目前只在一套小米 17 Pro Max 配置上验证。桥接依赖 CarWith 和小米 CarLife 组件，其他 Android 品牌尚未验证，也可能不提供兼容服务。 | 建立手机、系统和 CarWith 版本兼容表；在具备兼容 CarWith 服务的前提下探索其他品牌。 | 高 |
| 会话稳定性 | 音视频同时运行已验证约 6 分 36 秒；更长时间运行和中断恢复尚未覆盖。 | 测试长时间运行、导航提示、暂停恢复、断线及重连恢复。 | 高 |
| 接收端兼容性 | 文档中的桌面验证仅覆盖一台运行 DiPlay 的 PA2353 平板。 | 增加无线 CarPlay 接收端测试，记录配对、视频、音频和触控结果。 | 中 |

以上为计划中的验证工作，不构成兼容性保证；实际结果可能受手机、CarWith 版本、接收端和车型影响。

## 安装和连接

1. 在本仓库的 **Actions** 构建产物或 **Releases** 中获取 APK，具体方式见下节。
   本项目没有另外维护外部网盘下载渠道。
2. 安装到已有 CarWith 和小米 CarLife 组件的小米手机。
3. 与无线 CarPlay 接收端完成蓝牙配对，按接收端流程连接 Wi-Fi / 热点，
   在 mcarplay 中选择目标设备并启动连接。
4. 按需开启声音。mcarplay 的 CarWith 原生音频无需额外录屏授权。
5. 桌面测试可用另一台 Android 设备运行 [DiPlay](https://github.com/shihabal3amri/DiPlay)。
   启动本机 CarWith 会话前，先断开其他 CarLife 接收端。

CarWith 主链路使用普通应用 Intent 和本机套接字，无需 root 或 Shizuku。
上游保留的可选显示、启动器辅助功能可能仍使用 [Shizuku](https://shizuku.rikka.app/)。
覆盖安装后若配对停住，可重启测试接收端清理旧会话，再重新连接。

## 检查更新

停车并断开连接后，打开首页 **检查更新**。应用从
[GitHub Releases](https://github.com/wangxso/mcarplay/releases) 获取版本与更新说明，
可下载 APK 并打开 Android 系统安装器。本项目处于实验阶段，默认开启 **包含预发布版本**；
关闭后只检查正式版。安装始终需要用户确认。

下载后检查 SHA-256、包名/版本、Android 兼容性和当前应用的签名证书。
Debug 包通常无法覆盖更新为正式签名包；未记录发行标签的旧构建无法判断同一基础版本的预发布先后，
请从发行页手动更新。`remote` 调试版不支持标准 APK 更新。
目前已通过离线检查，手机端实际安装仍待验证。详见 [更新机制、限制与验收项](docs/UPDATES.md)。

## 连接问题反馈

点击首页 **导出脱敏诊断报告**，检查后分享文件，同时提供复现步骤、故障时间、接收端型号/固件和屏幕提示。
意外退出后请重新打开并立即导出，不要先重装或清除数据。报告自动包含最近进程关键记录，
无需电脑或 Shizuku，不会自动上传。详见 [诊断内容、错误码与排查方式](docs/DIAGNOSTICS.md)。

## 工作原理

```text
小米 CarWith / CarLife
    → 本机鉴权后的 CarLife 通道
    → H.264、原生 PCM 与触控转换
    → 蓝牙 iAP2 + Wi-Fi / AirPlay
    → 无线 CarPlay 接收端
```

源虚拟显示和编码器由 CarWith 创建，mcarplay 读取其媒体并处理接收端传输。
源码分发包不包含 CarWith、CarLife、OcarPlay 或接收端 APK。

## 本地构建

需要 JDK 21、Android SDK Platform 35 和 Build Tools 35.0.0。
设置 `ANDROID_HOME`，或在不提交的 `local.properties` 中填写
`sdk.dir=/path/to/Android/sdk`。

```bash
./gradlew spotlessCheck :app:testStandardDebugUnitTest \
  :app:lintStandardDebug :app:assembleStandardDebug
```

APK 路径：`app/build/outputs/apk/standard/debug/app-standard-debug.apk`。
使用 `./gradlew spotlessApply` 格式化 Kotlin 代码。执行一次 `git config core.hooksPath .githooks`，
即可在提交前本地检查。
`standard` 为常规版本，`remote` 另带开发调试工具。

可选的诊断工具测试：

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -r tools/requirements.txt
.venv/bin/python -m unittest discover -s tools -p 'test_*.py'
```

## Actions 自动生成 APK

工作流无需填写 GitHub 用户名或仓库地址：

| 工作流 | 触发条件 | 产物 |
| --- | --- | --- |
| **Android CI** | 分支 push、pull request 或手动运行（仅改文档时跳过） | Debug APK、SHA-256 校验文件、检查报告 |
| **Signed APK** | 推送 `v*` 标签或手动运行 | 正式签名 APK、校验文件、签名证书报告 |

**普通自动构建不需要签名 Secrets。** 上传源码后，进入
**Actions → Android CI → 成功的运行记录 → Artifacts**，下载
`mcarplay-debug-<运行编号>` 并解压安装；产物保留 14 天。
托管 runner 的 debug 证书可能随运行变化，测试 APK 更新时可能需要先卸载旧版。

如需固定签名，在仓库 **Settings → Secrets and variables → Actions** 添加：

| Secret 名称 | 内容 |
| --- | --- |
| `KEYSTORE_BASE64` | 正式 keystore 文件的 Base64 |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | 签名别名 |
| `KEY_PASSWORD` | 签名密钥密码 |

生成密钥和发布标签的步骤见 [GitHub 上传与发布指南](docs/GITHUB_SETUP.md)。
正式标签如 `v0.1.0`，或测试标签如 `v0.1.0-test.1`，其基础版本须与
`app/build.gradle.kts` 的 `appVersionName` 一致；测试标签会发布为 GitHub 预发布版本。
手动运行只上传 Actions 产物。
推送标签前，在 `CHANGELOG.md` 添加对应的 `## [vX.Y.Z-test.N]`（或正式标签）版本说明；
签名工作流会提取该节作为 Release 正文。
缺少 Secrets 时正式签名工作流明确失败，不会退回 debug 证书。后续更新应始终使用同一私钥。

本地正式签名：复制 `keystore.properties.example` 为 `keystore.properties`，
填写自己的配置后运行：

```bash
./gradlew -Pmcarplay.requireReleaseSigning=true :app:assembleStandardRelease
```

## 目录说明

- `app/src/main/java/com/mcarplay/app/`：CarLife 桥接、CarPlay 传输、媒体和界面。
- `app/src/test/java/`：协议、密码学、音频和生命周期的 JUnit 测试。
- `tools/`：诊断探针、Python 测试、上传目录整理脚本。
- `docs/`：设备记录、GitHub 配置指南及许可证文本。
- [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md)：实施计划和验收标准。
- [AGENTS.md](AGENTS.md)：贡献规范及常用命令。

运行 `python3 tools/prepare_upload.py` 可在 `dist/` 下生成源码上传目录和 ZIP，
排除本机 SDK 配置、签名文件、缓存、日志、抓包和参考 APK。
使用 GitHub 网页上传时，需一并上传 `.github/` 和根目录的点文件。

## 开源致谢

mcarplay 基于 shaonialife 及贡献者的
[CarDeck](https://github.com/shaonialife/cardeck) 继续开发，原 MIT 版权声明保留在
[LICENSE](LICENSE) 和 [NOTICE](NOTICE) 中。感谢
[DiPlay](https://github.com/shihabal3amri/DiPlay) 与
[xcertplay](https://github.com/shilapi/xcertplay) 提供接收端研究和测试环境；
这些是独立项目，适用各自许可证。

同时感谢本仓库用到的开源库与开发工具：

| 项目 | 用途 | 许可证 |
| --- | --- | --- |
| [AndroidX](https://developer.android.com/jetpack/androidx) | Core、AppCompat、RecyclerView、ViewPager2 | Apache-2.0 |
| [Kotlin](https://github.com/JetBrains/kotlin) | 开发语言及标准库 | Apache-2.0 |
| [Bouncy Castle](https://github.com/bcgit/bc-java) | 配对和传输密码学 | Bouncy Castle license |
| [JmDNS](https://github.com/jmdns/jmdns) | DNS-SD 服务发现 | Apache-2.0 |
| [SLF4J](https://www.slf4j.org/) | 日志 API 和空实现 | MIT |
| [Shizuku API](https://github.com/RikkaApps/Shizuku-API) | 可选 shell 辅助功能 | MIT |
| [JUnit 4](https://github.com/junit-team/junit4) | JVM 测试 | EPL-1.0 |
| [JSON-java](https://github.com/stleary/JSON-java) | 本地测试 JSON 实现 | 公有领域 |
| [cryptography](https://github.com/pyca/cryptography) | Python 诊断工具 | Apache-2.0 OR BSD-3-Clause |
| [Gradle](https://github.com/gradle/gradle)、[Spotless](https://github.com/diffplug/spotless)、[ktlint](https://github.com/pinterest/ktlint) | 构建和格式化 | Apache-2.0 / Apache-2.0 / MIT |

依赖版本、实际打包的库与研究参考的区别及许可证文本，见
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 许可与使用说明

源码按 [MIT](LICENSE) 发布，同时保留上游与依赖的许可声明。
本项目是非官方实验作品，与 Apple、小米或汽车厂商无关联。
请在停车状态下配置和测试，权限、测试范围及限制见 [DISCLAIMER.md](DISCLAIMER.md)。
