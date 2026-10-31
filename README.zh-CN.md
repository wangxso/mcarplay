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

项目处于实验阶段，截至 2026 年 10 月 31 日已跑通小米到 DiPlay、小米到 WinPlay 1.2.5
的测试链路，并验证一条小米到奇瑞车机的手动无线连接路径。
当前正式版为 `v0.1.3`，发布范围仍是已验收的手动连接路径。

| 能力 | 验证结果 |
| --- | --- |
| 原生视频 | CarWith 按接收端宽高比编码（长边最多 1920），实测 1920 × 1080 与 1280 × 720、30fps；早期 DiPlay 测试实测送达约 20fps |
| WinPlay 1.2.5 | 默认设置下画面、触控和音乐到达接收端；WinPlay 释放蓝牙后会话持续约 25 分钟；手动断开及手动断网后均能重连 |
| WinPlay 1.2.4 | 短时实测出画面和声音 |
| 接收端触控 | 已在 DiPlay 和 WinPlay 1.2.5 上验证点击与页面切换；用户另行确认奇瑞车机手动连接路径触控正常 |
| 接收端媒体键 | 上一曲／下一曲转发给 CarWith；尚未实机验证 |
| 原生音乐 | 44.1kHz PCM 转 48kHz AAC-LC，用户确认声音、音调和速度正常 |
| 音视频同时运行 | WinPlay 1.2.5 上带音乐运行约 25 分钟；DiPlay 上连续约 6 分 36 秒，无音频丢包、欠载或写入错误 |
| mcarplay CarWith 桥接的实车适配 | 已在 2022 款奇瑞艾瑞泽 8、固件 `M1E_IC2172/82_03_21.387_250619_R` 上验证一条手动无线路径：画面、声音和触控到达车机；两轮媒体累计约 107 分钟，最长约 99 分钟。这不代表广泛车型兼容 |
| 导航提示 | WinPlay 1.2.5 台架（2026-10-30）：提示期间音乐压低到 -15dB，结束后恢复；压低幅度可在「车机设置 → 显示」调整。其他接收端未验证 |
| 手机锁屏 | 手动锁屏仍可能结束会话；已记录的奇瑞车机会话退出后可直接重连 |

测试手机：小米 17 Pro Max（`2509FPN0BC`），HyperOS `OS4.0.4.0.XPBCNXM`，
Android 17，CarWith `4.0.20-20260923`，小米 CarLife `8.8.5`（早期 DiPlay 测试为 `8.8.4`）。
下方实测覆盖：2022 款奇瑞艾瑞泽 8 车机、PA2353 / Android 14 平板上的 DiPlay 0.2.21，
以及 Windows 上的 WinPlay 1.2.4 / 1.2.5（x64）。
应用最低 SDK 为 Android 12 / API 31，这不代表所有 Android 12 及以上设备均兼容。
上游 CarDeck 的实车测试不能直接作为 mcarplay CarWith 桥接的车型适配结论。

### 实测画面

每张图只记录所拍会话。两张奇瑞图片是使用 **AI 图像编辑**从用户实拍生成的脱敏裁图：
移除车窗、周边环境和内饰，并模糊地点文字。它们是 AI 脱敏后的派生图片，不是原始截图；
原图保留在 Git 之外，涉及像素级或时间信息的结论仍以原图和日志为准。点击图片可查看大图。

| 接收端 / 环境 | 实测画面 | 能确认什么 | 边界 |
| --- | --- | --- | --- |
| **2022 款奇瑞艾瑞泽 8**<br>固件 `M1E_IC2172/82_03_21.387_250619_R` | <a href="docs/screenshots/chery-arrizo8-2026-10-31-1237-redacted.png"><img src="docs/screenshots/chery-arrizo8-2026-10-31-1237-redacted.png" width="420" alt="AI 脱敏后的奇瑞艾瑞泽 8 车机在 12:37 显示 CarWith 仪表盘"></a><br>2026-10-31 12:37，手动无线连接 | CarWith 地图、媒体、应用栏和仪表盘到达实体车机；用户另行确认画面、声音和触控正常 | 图片经过 AI 脱敏，单张图不能单独证明声音、触控、时长或地点 |
| **2022 款奇瑞艾瑞泽 8**<br>同一车机固件 | <a href="docs/screenshots/chery-arrizo8-2026-10-31-1305-redacted.png"><img src="docs/screenshots/chery-arrizo8-2026-10-31-1305-redacted.png" width="420" alt="AI 脱敏后的奇瑞艾瑞泽 8 车机在 13:05 显示 CarWith 长距离导航"></a><br>2026-10-31 13:05，长距离导航和媒体会话 | 导航、媒体、天气和转向卡片到达实体车机；日志记录两轮会话累计约 107 分钟，最长约 99 分钟 | 图片经过 AI 脱敏；手动锁屏仍可能结束会话，不据此承诺自动触发和完整冷启动 |
| **WinPlay 1.2.4**<br>Windows x64 | <a href="docs/screenshots/winplay-1.2.4-video-output.png"><img src="docs/screenshots/winplay-1.2.4-video-output.png" width="420" alt="WinPlay 1.2.4 显示 mcarplay 发送的 CarWith 画面"></a><br>2026-10-27 台架截图 | WinPlay 1.2.4 显示了 mcarplay 传来的 CarWith 视频 | 截图只证明视频输出，不证明触控、连续运行 10 分钟、断线重连或实车兼容性 |
| **DiPlay 0.2.21**<br>PA2353 / Android 14 平板 | <a href="docs/screenshots/diplay-carwith-photo.jpg"><img src="docs/screenshots/diplay-carwith-photo.jpg" width="420" alt="PA2353 Android 平板上的 DiPlay 显示 CarWith 网易云音乐界面"></a><br>2026-10-27 22:08，`v0.1.3-test.2` 代码 | DiPlay 显示了 CarWith 网易云音乐界面，CarWith 编码为 1920 × 1080、30fps；拍照时音乐暂停，开始播放后声音正常到达 | 照片只证明该会话的画面输出；当前版本的长时运行与重连仍待验证 |

## 已知限制与后续计划

| 项目 | 已知情况 | 后续计划 | 优先级 |
| --- | --- | --- | --- |
| 实车测试 | 已在一条记录的奇瑞车机固件上验证手动无线路径；其他车型与固件仍未验证。 | 继续记录车型与接收端组合，并覆盖点火、冷启动和更多车机状态。 | 高 |
| 手机品牌与系统版本 | 目前只在一套小米 17 Pro Max 配置上验证。桥接依赖 CarWith 和小米 CarLife 组件，其他 Android 品牌尚未验证，也可能不提供兼容服务。 | 建立手机、系统和 CarWith 版本兼容表；在具备兼容 CarWith 服务的前提下探索其他品牌。 | 高 |
| 会话稳定性 | 已记录的奇瑞车机固件上，两轮媒体累计约 107 分钟，最长约 99 分钟；手动锁屏仍可能结束会话。 | 保证自动息屏与手动锁屏不断连，并覆盖网络、蓝牙和媒体生命周期变化后的恢复。 | 高 |
| 上车自动连接 | 已验证的实车路径由手机启动连接，再在车机蓝牙列表中选择手机。 | 在更多蓝牙状态下验证触发去重、取消、冷启动和失败恢复。 | 高 |
| 方向盘控制 | 接收端上一曲／下一曲会转发给 CarWith，但完整方向盘按键与焦点状态矩阵尚未验证。 | 完成媒体键、暂停恢复、导航提示、电话打断和焦点状态回归。 | 高 |
| 接收端兼容性 | WinPlay 1.2.5 已有画面、触控、声音、约 25 分钟运行和重连的桌面测试证据；DiPlay 已有画面、音频和触控证据，但当前版本的长时运行和重连尚未验证。 | 验证 DiPlay 在当前版本上的长时运行与重连，在实机上确认 WinPlay 1.2.5 网络交接规则，再测试其他接收端。 | 高 |

以上为计划中的验证工作，不构成兼容性保证；实际结果可能受手机、CarWith 版本、接收端和车型影响。

## 安装和连接

1. 在本仓库的 **Actions** 构建产物或 **Releases** 中获取 APK，具体方式见下节。
   本项目没有另外维护外部网盘下载渠道。
2. 安装到已有 CarWith 和小米 CarLife 组件的小米手机。
3. 与无线 CarPlay 接收端完成蓝牙配对，按接收端流程连接 Wi-Fi / 热点，
   在 mcarplay 中选择目标设备并启动连接。
4. 按需开启声音。mcarplay 的 CarWith 原生音频无需额外录屏授权。
5. 桌面测试可用另一台 Android 设备运行 [DiPlay](https://github.com/shihabal3amri/DiPlay)，
   或在 Windows 电脑上运行 [WinPlay](https://github.com/Roylyl/WinPlay)。
   启动本机 CarWith 会话前，先断开其他 CarLife 接收端。

CarWith 原生画面、音频和触控使用普通应用 Intent 和本机套接字，无需 root 或 Shizuku。
WinPlay 1.2.4、1.2.5 等接收端还会校验手机的真实蓝牙身份，而 Android 不允许普通应用直接读取：
启动 [Shizuku](https://shizuku.rikka.app/) 后 mcarplay 会自动读取并保存；不用 Shizuku 时，
在主界面「本机蓝牙地址」中填入手机设置里查到的蓝牙地址。Shizuku 实时读取优先于手动填写。
可选显示、启动器辅助功能也可能使用 Shizuku。
接收端能力、蓝牙交接、配对降级和音频记忆遵循[接收端兼容设计](docs/RECEIVER-COMPATIBILITY.md)。
其中的策略矩阵描述实现与证据边界，不是设备支持清单。
覆盖安装后若配对停住，可重启测试接收端清理旧会话，再重新连接。

## 检查更新

停车并断开连接后，打开首页 **检查更新**。应用从
[GitHub Releases](https://github.com/wangxso/mcarplay/releases) 获取版本与更新说明，
可下载 APK 并打开 Android 系统安装器。本项目处于实验阶段，默认开启 **包含预发布版本**；
关闭后只检查正式版。安装始终需要用户确认。

下载后检查 SHA-256、包名/版本、Android 兼容性和当前应用的签名证书。
Debug 包通常无法覆盖更新为正式签名包；未记录发行标签的旧构建无法判断同一基础版本的预发布先后，
请从发行页手动更新。`remote` 调试版不支持标准 APK 更新。
正式签名的 `v0.1.3` APK 已覆盖安装到测试手机的上一版签名包；应用内下载和系统安装器流程
仍需单独做手机端验收。详见 [更新机制、限制与验收项](docs/UPDATES.md)。

## 界面语言与更新说明

界面支持简体中文（默认）和英文，可在首页 **界面语言** 中切换；Android 13 及以上也可在系统的
「应用语言」中设置。日志和诊断报告保持中文，便于与已有证据对照。

每次更新后首次打开，会按所选语言弹出该版本的更新内容，来源是打包进 APK 的 `CHANGELOG.md`
对应条目（不显示验证条目）；全新安装则先显示免责声明。更新页里的发行说明同样按所选语言显示。

## 连接问题反馈

点击首页 **导出脱敏诊断报告**，检查后分享文件，同时提供复现步骤、故障时间、接收端型号/固件和屏幕提示。
意外退出后请重新打开并立即导出，不要先重装或清除数据。报告自动包含最近进程关键记录，
无需电脑或 Shizuku，不会自动上传。详见 [诊断内容、错误码与排查方式](docs/DIAGNOSTICS.md)。

## 检测并记住当前车机

在 **选择设备** 中选定已配对目标，再打开 **检测当前车机 → 开始检测（含声音）**。
向导复用正常连接流程，展示源端、发送端和输入证据，由你确认实际画面、声音与触控后保存到本机。
可选的蓝牙阶段对照只运行一次，会暂时断开目标的 HFP/A2DP 通话／音乐连接。取消或失败保留旧配置，未知结果保持未验证。
保存后按设备复用，并检查环境与能力变化，无需车型列表，不自动上传。
坐标校准与配置导入属于后续扩展，详见 [实现范围与限制](docs/RECEIVER-AUTODETECT.md)。

## 兼容兜底开关

旧版本的两条兜底默认关闭。它们没有实车证据，只在遇到下表中的现象时打开，然后重新连接。
在首页点 **车机设置 → 显示**（车机屏上的设置面板相同），找到：

| 开关 | 适用现象 | 作用 | 风险 |
| --- | --- | --- | --- |
| **兼容兜底：配对失败后尝试明文** | 老接收端每次都配对失败，且确认它只接受不加密的会话 | 已连上接收端但配对普通失败时，再试一次不加密的明文会话（`et=NONE`）；接收端返回 403 时始终不降级 | 占用接收端的重连窗口，会话仍可能被拒 |
| **兼容兜底：音频格式未声明时仍尝试** | 画面正常但总是只推视频，而车机其实能出声 | 接收端没有声明可用的媒体音频格式时，仍按 PCM 48kHz 尝试（也会使用非媒体音频档） | 无声，或开声音后不久接收端断开 |

没有效果请关掉。反馈问题时请说明开了哪些开关；诊断日志里会有 `COMPAT:` 行或音频兜底行。

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
| [Shizuku API](https://github.com/RikkaApps/Shizuku-API) | 按需读取蓝牙身份及 shell 辅助功能 | MIT |
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
