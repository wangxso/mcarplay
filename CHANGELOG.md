# Changelog

Release notes are listed newest first. English is followed by Simplified Chinese.
Test releases are experimental and do not establish real-vehicle compatibility.

## [v0.1.0-test.4] - 2026-10-26

### English

**Added**
- README badges for Android CI, the latest release, release downloads, Android 12+, MIT licensing, experimental status and GitHub stars.
- A versioned bilingual changelog, linked from both READMEs and included in source upload bundles.

**Changed**
- Tagged releases now use the matching changelog entry as their GitHub release notes, instead of only generating a commit link.
- Missing, empty or duplicate release-note entries stop publication before signing.
- The discovery responder's port-release assertion now allows bounded kernel shutdown latency, avoiding an immediate-connect race seen on hosted CI runners.

**Validation and scope**
- No application runtime changes compared with `v0.1.0-test.3`; this is a documentation and release-process update.
- The existing 364 JVM tests, Android lint, formatting checks and APK build are retained as release gates; release-note extraction has dedicated Python tests.
- The APK still reports Android version `0.1.0`, as in the previous test release. The GitHub tag identifies this prerelease iteration.
- APK, SHA-256 checksums and signing-certificate information are published as release assets. Keep the previous signing key for in-place updates.
- Xiaomi-to-DiPlay video, music and touch have bench-test evidence. Real vehicles, navigation announcements, pause/resume and extended stability still require validation.

### 简体中文

**新增**
- 中英文 README 徽章：构建状态、最新版本、Release 下载量、Android 12+、MIT 许可证、实验状态和 Stars。
- 按版本维护的中英文 changelog；两份 README 均提供入口，源码上传包也包含更新日志。

**调整**
- 标签发布直接读取对应版本的 changelog 作为 GitHub Release 正文，不再只有自动生成的提交链接。
- 对应更新说明缺失、为空或重复时，在签名前终止发布。
- 发现服务的端口释放断言改为有界等待内核完成关闭，避免托管 CI 上立即重连探测产生的竞态误报。

**验证与范围**
- 相比 `v0.1.0-test.3` 没有应用运行逻辑变化，本次为文档与发布流程更新。
- 保留 364 项 JVM 测试、Android lint、格式检查和 APK 构建门槛；为更新说明提取新增 Python 专项测试。
- APK 在 Android 中仍显示 `0.1.0`，与上一测试版一致；GitHub 标签区分本轮预发布。
- Release 附带 APK、SHA-256 校验文件和签名证书信息；覆盖更新需沿用上一版签名密钥。
- 小米到 DiPlay 的画面、音乐和触控已有测试证据；实车、导航提示、暂停恢复和长时间稳定性仍待验证。

Full changes / 完整变更: [v0.1.0-test.3...v0.1.0-test.4](https://github.com/wangxso/mcarplay/compare/v0.1.0-test.3...v0.1.0-test.4)

## [v0.1.0-test.3] - 2026-10-26

### English
- Fixed uncaught heartbeat interruption during normal CarLife shutdown, with a 20-cycle regression test.
- Stopped the session's CarLife source and native audio bridge when video streaming ends; guarded stale video callbacks from cleaning up a replacement session.
- Documented Xiaomi/DiPlay disconnect evidence and intentional receiver-loss, manual-disconnect and reconnect checks; no new heartbeat FATAL was observed in that device regression.
- Shipped mcarplay branding, the Mi-style launcher icon and the existing CarWith video/audio/touch bridge.
- Local verification: 364 JVM tests and 12 Python tests passed, alongside formatting, lint and build checks. The signed GitHub APK workflow completed successfully.

### 简体中文
- 修复 CarLife 正常停止时心跳线程中断产生未捕获异常的问题，新增连续 20 次启停回归。
- 视频结束时停止本轮 CarLife 源和原生音频桥；隔离旧视频回调，避免清理替代会话。
- 补充小米与 DiPlay 断连证据，以及接收端丢失、手动断开和重连记录；设备回归未新增心跳 FATAL。
- 包含 mcarplay 品牌、Mi 风格启动图标和已有 CarWith 视频、音频、触控桥接。
- 本地验证：364 项 JVM 测试、12 项 Python 测试、格式检查、lint 和构建通过；GitHub 签名 APK 流程成功。

[v0.1.0-test.4]: https://github.com/wangxso/mcarplay/releases/tag/v0.1.0-test.4
[v0.1.0-test.3]: https://github.com/wangxso/mcarplay/releases/tag/v0.1.0-test.3
