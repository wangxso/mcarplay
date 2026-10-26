package com.mcarplay.app.update

import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class DownloadedUpdate(
    val file: File,
    val sha256: String,
)

class UpdateDownloader(
    private val transport: UpdateTransport = GitHubHttp,
) {
    fun download(
        release: GitHubRelease,
        directory: File,
        job: UpdateJob,
        progress: (Long, Long) -> Unit,
    ): DownloadedUpdate {
        job.check()
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create private update cache" }
        val asset = release.apk
        UpdateSource.requireDownloadUrl(asset.url, release.version.tag, asset.name)
        val expected =
            asset.sha256 ?: release.checksums?.let {
                UpdateSource.requireDownloadUrl(it.url, release.version.tag, it.name)
                UpdateSource.checksumFor(transport.text(it.url, 65_536, job), asset.name)
            } ?: throw UpdateException("MISSING_CHECKSUM", "没有可用的 SHA-256，已拒绝下载")
        if (!Regex("[a-fA-F0-9]{64}").matches(expected)) throw UpdateException("MISSING_CHECKSUM", "SHA-256 格式无效")
        if (asset.size !in 1..UpdateSource.MAX_APK_BYTES) throw UpdateException("APK_SIZE", "发行包大小无效")
        val partial = File(directory, "update-${UUID.randomUUID()}.part")
        val target = File(directory, "mcarplay-update-${release.version.tag}.apk")
        var completed = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            transport.open(asset.url, job).use { response ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(32_768)
                    while (true) {
                        job.check()
                        val count = response.input.read(buffer)
                        if (count < 0) break
                        received += count
                        if (received > asset.size || received > UpdateSource.MAX_APK_BYTES) {
                            throw UpdateException("APK_SIZE", "下载文件超过声明大小，已拒绝")
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        progress(received, asset.size)
                    }
                    output.fd.sync()
                }
            }
            job.check()
            if (received != asset.size) throw UpdateException("APK_TRUNCATED", "APK 下载不完整，请重试")
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(expected, ignoreCase = true)) throw UpdateException("CHECKSUM_MISMATCH", "APK 校验失败，文件已丢弃")
            if (target.exists() && !target.delete()) throw UpdateException("CACHE_WRITE", "无法替换更新缓存")
            if (!partial.renameTo(target)) throw UpdateException("CACHE_WRITE", "无法保存更新文件")
            completed = true
            return DownloadedUpdate(target, expected.lowercase())
        } finally {
            if (!completed) partial.delete()
        }
    }

    companion object {
        fun verifyHash(
            file: File,
            expected: String,
            job: UpdateJob,
        ) {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(32_768)
                while (true) {
                    job.check()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(expected, ignoreCase = true)) throw UpdateException("CHECKSUM_MISMATCH", "安装前校验失败，请重新下载")
        }
    }
}
