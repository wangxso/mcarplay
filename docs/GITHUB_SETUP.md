# GitHub Upload and APK Builds

[English README](../README.md) | [中文说明](../README.zh-CN.md)

## Upload the source

1. Run `python3 tools/prepare_upload.py` from the repository root.
2. Create an empty GitHub repository, then upload the contents of
   `dist/github-upload/`. If using `dist/mcarplay-source.zip`, extract it first;
   uploading the ZIP alone does not install the source or workflows.
3. Include `.github/workflows/`, `.gitignore`, `.gitattributes`, `.editorconfig`,
   the Gradle Wrapper JAR and both `gradlew` scripts. Do not upload the enclosing
   folder as an extra directory: `README.md` must be at the repository root.
4. Commit the files. The **Android CI** workflow runs on branch pushes and PRs.
   If Actions is disabled, enable it under repository settings. You can also
   choose **Actions → Android CI → Run workflow**.
5. After a successful run, download `mcarplay-debug-<run number>` from **Artifacts**.
   Extract it to obtain the APK and `SHA256SUMS.txt`. No secrets are needed.

The upload script uses an explicit source-file allowlist. It preserves file
permissions, refuses symbolic links, and omits caches, keystores, local SDK
settings, reference APKs, logs and media captures. Keep personal fixtures and
credentials outside source directories. The original local research APK is left
on disk but is ignored and excluded from the upload package.

## Configure stable signing

Choose your own release signing key and back it up. A debug-signed APK and a
release-signed APK cannot update each other directly; switching certificates
requires uninstalling the old app, which can remove its local settings.

Generate a key outside the repository, using interactive password prompts:

```bash
mkdir -p "$HOME/.config/mcarplay"
keytool -genkeypair -v \
  -keystore "$HOME/.config/mcarplay/mcarplay-release.jks" \
  -alias mcarplay -keyalg RSA -keysize 4096 -validity 10000
```

Encode the file for the `KEYSTORE_BASE64` secret:

```bash
# macOS: copy the encoded value to the clipboard, without printing it.
base64 -i "$HOME/.config/mcarplay/mcarplay-release.jks" | tr -d '\n' | pbcopy

# Linux: paste this output directly into the repository secret.
base64 -w 0 "$HOME/.config/mcarplay/mcarplay-release.jks"
```

Under **Settings → Secrets and variables → Actions → New repository secret**, add:

| Secret | Meaning |
| --- | --- |
| `KEYSTORE_BASE64` | Entire encoded keystore, not its path |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | `mcarplay`, or your chosen alias |
| `KEY_PASSWORD` | Password for that private key |

Secrets belong in **Secrets**, not repository variables or source files.
The workflow restores the key into the runner's temporary directory and removes
it in its final cleanup step. PR builds never restore release-signing material.

For a local signed build, use `keystore.properties.example` as a template or set
`MCARPLAY_KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` in the
environment. Then run:

```bash
./gradlew -Pmcarplay.requireReleaseSigning=true :app:assembleStandardRelease
```

## Publish a release

1. Update `appVersionName` in `app/build.gradle.kts` for each new version.
2. Commit that change, then create and push a matching tag. Use `v0.1.0` for a
   regular release or `v0.1.0-test.1` for a test prerelease. The base version in
   either tag must match `appVersionName`. A local tag that has not been pushed
   does not trigger GitHub Actions.
   Before committing, add the tag's exact `## [v0.1.0-test.1]` (or stable tag)
   heading and nonempty release notes to `CHANGELOG.md`. The workflow extracts
   that entry for the release body and rejects missing or duplicate entries.
3. **Signed APK** checks the tag, tests and lint, builds a signed APK, verifies
   its signature, and publishes it with checksums and a certificate report in
   GitHub Releases. Tags ending in `-test.N` are marked as prereleases; the APK
   keeps the base `appVersionName`.
4. Download the APK and check its SHA-256 and certificate before distributing it.

Manual **Signed APK** runs upload artifacts only; they do not create a release
when run on a branch. Reusing an existing release tag does not overwrite its assets;
publish a new version for an update. Signed artifacts are kept for 30 days;
release assets remain available until removed from the release.

The optional `-Pmcarplay.buildNumber=N` suffix is available for local signed
development builds (`N` is 1–9999). Tag workflows use the base version.
Do not mix numbered development builds and stable builds of the same base version
when testing Android upgrade ordering.

Tag builds also record the release tag for the [in-app updater](UPDATES.md).
Retain `mcarplay-<base>.apk`, `SHA256SUMS.txt` and the same signing key. Forks must
change `UpdateSource.REPOSITORY` before distributing APKs that check for updates.
标签构建同时记录应用内更新所需的发行标签；请保持上述资产命名与签名密钥，Fork 发布前修改更新仓库常量。

## 中文快速操作

1. 执行 `python3 tools/prepare_upload.py`，上传 `dist/github-upload/` **里面的内容**。
   若使用 ZIP，先解压；只上传 ZIP 不会自动构建。不要遗漏隐藏目录 `.github/`。
2. 上传后进入 **Actions → Android CI → 成功运行 → Artifacts**，下载并解压测试 APK。
   此流程无需签名配置。
3. 如需固定签名，按表格在仓库 **Actions Secrets** 中配置四项，妥善备份自己的私钥。
4. 修改 `appVersionName` 后推送匹配标签：`v0.1.0` 发布正式 Release，`v0.1.0-test.1`
   发布测试预览版（GitHub prerelease）。工作流会生成正式签名 APK；手动运行只保存构建产物。
   提交前须在 `CHANGELOG.md` 添加对应标签的非空版本说明；发布正文自动取自这一节。
5. 旧 debug 包与新正式签名不同，首次切换通常需要卸载旧包；本地设置可能随之删除。

GitHub-hosted execution can only be confirmed after uploading this repository and
running its workflows. Local verification does not claim that a hosted run occurred.
