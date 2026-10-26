package com.mcarplay.app.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

data class ApkIdentity(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val signerDigests: Set<String>,
    val minSdk: Int,
)

object ApkUpdateVerifier {
    fun requireCompatible(
        installed: ApkIdentity,
        candidate: ApkIdentity,
        version: ReleaseVersion,
        sdk: Int,
    ) {
        if (installed.packageName != candidate.packageName) throw UpdateException("PACKAGE_MISMATCH", "APK 包名不匹配，已拒绝安装")
        if (candidate.versionName != version.base || candidate.versionCode != version.versionCode) {
            throw UpdateException("VERSION_MISMATCH", "APK 版本与发行标签不一致，已拒绝安装")
        }
        if (candidate.versionCode < installed.versionCode) throw UpdateException("DOWNGRADE", "下载版本较低，已阻止降级")
        if (candidate.minSdk > sdk) throw UpdateException("SDK_UNSUPPORTED", "此更新不支持当前 Android 版本")
        if (installed.signerDigests.isEmpty() || candidate.signerDigests.isEmpty() || installed.signerDigests != candidate.signerDigests) {
            throw UpdateException(
                "SIGNATURE_MISMATCH",
                "APK 签名与当前应用不一致，不能覆盖安装。调试包与正式包通常使用不同证书；不会自动卸载或清除数据。",
            )
        }
    }

    @Suppress("DEPRECATION")
    fun verify(
        context: Context,
        downloaded: DownloadedUpdate,
        release: GitHubRelease,
        job: UpdateJob,
    ) {
        UpdateDownloader.verifyHash(downloaded.file, downloaded.sha256, job)
        val manager = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val installed = manager.getPackageInfo(context.packageName, flags)
        val candidate =
            manager.getPackageArchiveInfo(downloaded.file.absolutePath, flags)
                ?: throw UpdateException("APK_UNREADABLE", "无法读取 APK 的包信息或签名")
        requireCompatible(identity(installed), identity(candidate), release.version, Build.VERSION.SDK_INT)
        job.check()
    }

    private fun identity(info: PackageInfo): ApkIdentity =
        ApkIdentity(
            info.packageName,
            info.versionName.orEmpty(),
            info.longVersionCode,
            info.signingInfo
                ?.apkContentsSigners
                .orEmpty()
                .map {
                    MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { byte -> "%02x".format(byte) }
                }.toSet(),
            info.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE,
        )
}
