# GitHub updates / GitHub 应用更新

## Use / 使用

While parked, disconnect CarPlay and stop the CarWith self-test. Open **检查更新**
on the main screen. This queries GitHub automatically when the update screen opens;
**检查更新** retries it. The app does not check in the background at startup.
**包含预发布版本** defaults to enabled and is remembered. Read the release notes,
tap **下载更新**, then **安装更新**. Allow this app as an installation source if Android
asks, and confirm in the system installer. Cancelling does not remove the installed app.

停车、断开 CarPlay 并停止 CarWith 自检后，打开首页 **检查更新**。进入该页面会自动查询一次，
点击同名按钮可重试；不会在应用启动时后台检查。**包含预发布版本** 默认开启并记住选择。
查看说明，依次点击 **下载更新**、**安装更新**；按 Android 提示允许此应用作为安装来源，
再在系统安装器中确认。取消不会卸载当前应用。

## Source and version ordering / 来源与版本排序

- Fixed source: `wangxso/mcarplay` public GitHub Releases; no credentials, mirrors,
  arbitrary source URL or Actions artifact download. Fork maintainers must change
  `UpdateSource.REPOSITORY` to their own trusted repository before distributing.
- Stable-only uses `/releases/latest`; previews use the most recent 20 releases,
  ordered numerically by `vX.Y.Z[-test.N]`. A stable version sorts after its previews.
  Drafts, malformed tags and releases without a unique standard APK and SHA-256 are skipped.
- Tagged GitHub builds record `GITHUB_REF_NAME` as `BuildConfig.RELEASE_TAG`.
  Local reproduction: `./gradlew -Pmcarplay.releaseTag=v0.1.2-test.1 :app:assembleStandardDebug`.
  This property must match `appVersionName`; it does not change signing or Android's versionCode.
- An older/local build with no known release tag can update to a higher base version;
  it cannot automatically order previews within its own base. Open the release page
  for manual installation. Equal Android versionCodes between preview iterations
  are allowed after tag comparison; lower versionCodes are rejected.
- Numbered development builds and the `remote` flavor cannot use standard release
  APKs as interchangeable updates. The release workflow must keep its existing naming,
  base-version encoding and signing key.

固定来源为 `wangxso/mcarplay` 的公开 GitHub Releases，无令牌、镜像、自定义来源或 Actions 产物下载。
Fork 维护者发布前须修改 `UpdateSource.REPOSITORY`。正式版使用 `/releases/latest`；
包含预发布时读取最近 20 个 Release，按 `vX.Y.Z[-test.N]` 的数字顺序排序，同基础版本正式版在预发布之后。
草稿、异常标签、缺少唯一标准 APK 或校验值的发行项会跳过。

GitHub 标签构建自动将标签记录到 `BuildConfig.RELEASE_TAG`；本地可用上述 Gradle 参数复现，
参数必须匹配基础版本，不能改变签名或 Android versionCode。
缺少发行标签的旧版/本地包只自动判断更高基础版本，同基础版本请打开发行页手动更新。
标签先后明确时允许预发布迭代使用相同 versionCode，禁止降级。
带构建序号的开发包及 `remote` 调试版不能与标准发行包互换；发布须沿用当前命名、版本编码和签名密钥。

## Verification and privacy / 校验与隐私

The downloader accepts only this repository's asset paths, HTTPS and GitHub/CDN
redirects. APKs are bounded to 100 MB and the declared size. SHA-256 comes from
GitHub's asset digest, or `SHA256SUMS.txt` when the digest is unavailable. It detects
corrupt/mismatched downloads; a checksum from the same source is not an independent signature.
Partial files are deleted on failure/cancellation. Network waits may delay cancellation.

Files stay in private cache. Before enabling installation, and again before opening
the installer, the app verifies the hash, package, release versionName/versionCode,
minSdk and equality of the current signing certificates. Certificate rotation is
not supported. Debug/release certificate mismatches stop the update with an explanation;
there is no automatic uninstall or data clearing. Android performs the final install checks.
The FileProvider exposes only the update-cache directory, with a temporary read grant.

GitHub receives normal network request information, including the IP address.
No diagnostics, pairing data or signing secrets are uploaded. Logs record failure
type/code, not temporary signed download URLs. Updates belong to this Activity:
closing the screen cancels work; backgrounding has no persistent-download guarantee,
and process death has no automatic download/install resume.
There is no periodic polling, silent installation or installation-success claim.

下载只接受本仓库资产路径、HTTPS 与 GitHub/CDN 跳转，限制为声明大小且不超过 100 MB。
优先使用 GitHub 资产 digest，缺失时读取 `SHA256SUMS.txt`；同源校验和只能检出损坏或错配，
不是独立签名。失败/取消清理未完成文件，网络等待可能延迟取消。
APK 保存在私有缓存；启用安装按钮前及启动安装器前均检查哈希、包名、发行版本、minSdk 和当前签名证书一致性。
不支持证书轮换。Debug/正式签名不同会明确拒绝，不自动卸载或清除数据；最终安装检查由 Android 执行。
FileProvider 只开放更新缓存目录，并临时授予读取权限。

GitHub 会收到包括 IP 在内的普通网络请求信息，不上传诊断、配对数据或密钥；日志只记录失败类型/错误码，
不记录临时签名下载 URL。退出更新页面会取消任务；切到后台不提供持久下载保证，进程结束不自动恢复。
无定时轮询、静默安装或“安装成功”的推断。

## Acceptance / 验收

Offline tests cover version/channel selection, trusted URLs, bounded downloads,
hashes, cancellation/cleanup and package/version/signer rejection. Android resource,
lint and build checks do not establish installer behavior. Device acceptance is pending:

1. Use two releases signed by the **same release key**, with known increasing tags.
   Confirm notes, download progress, source permission and successful in-place installation.
2. Deny the installation-source permission or cancel the installer; the existing app
   and settings remain usable. Retry after granting permission.
3. Interrupt the network, cancel a download, rotate the screen and leave/reopen the
   page. Retry without duplicate tasks, leftover partial files or unintended installation.
4. Confirm a debug certificate, wrong package/hash, unsupported SDK and lower
   versionCode are rejected. Check stable-only mode when no stable release exists.
5. During a connection/self-test, updates are refused. Verify normal CarWith video,
   music and touch after updating; record phone/system/receiver and APK SHA-256.

离线测试覆盖版本/频道、可信地址、下载边界、哈希、取消清理及包名/版本/签名拒绝。
资源、lint、构建通过不能替代手机安装验收；尚待完成：
使用同一正式密钥的递增标签完成覆盖安装；拒绝来源权限或取消安装后可重试且数据保留；
断网、取消、旋转及离开重开无重复任务或误安装；错误签名/包名/哈希、过高 minSdk 和低版本被拒绝；
无正式版时提示正确；连接/自检期间拒绝更新，更新后视频、音乐、触控回归，记录环境与 APK SHA-256。
