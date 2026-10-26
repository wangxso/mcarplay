package com.mcarplay.app.update

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

class UpdateException(
    val code: String,
    message: String,
) : Exception(message)

data class ReleaseAsset(
    val name: String,
    val url: String,
    val size: Long,
    val sha256: String?,
)

data class GitHubRelease(
    val version: ReleaseVersion,
    val title: String,
    val notes: String,
    val preview: Boolean,
    val apk: ReleaseAsset,
    val checksums: ReleaseAsset?,
) {
    val pageUrl: String get() = "${UpdateSource.RELEASES_URL}/tag/${version.tag}"
}

object UpdateSource {
    const val REPOSITORY = "wangxso/mcarplay"
    const val RELEASES_URL = "https://github.com/$REPOSITORY/releases"
    const val API_URL = "https://api.github.com/repos/$REPOSITORY/releases?per_page=20"
    const val STABLE_API_URL = "https://api.github.com/repos/$REPOSITORY/releases/latest"
    const val MAX_APK_BYTES = 100_000_000L

    fun fetch(
        transport: UpdateTransport,
        includePreviews: Boolean,
        job: UpdateJob,
    ): GitHubRelease? {
        val json =
            try {
                transport.text(if (includePreviews) API_URL else STABLE_API_URL, 1_048_576, job)
            } catch (error: UpdateException) {
                if (!includePreviews && error.code == "HTTP_404") return null
                throw error
            }
        return latest(if (includePreviews) json else JSONArray().put(JSONObject(json)).toString(), includePreviews)
    }

    fun requireDownloadUrl(
        url: String,
        tag: String,
        name: String,
    ) {
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri == null ||
            uri.scheme != "https" ||
            uri.host != "github.com" ||
            uri.userInfo != null ||
            uri.port != -1 ||
            uri.query != null ||
            uri.fragment != null ||
            uri.path != "/$REPOSITORY/releases/download/$tag/$name"
        ) {
            throw UpdateException("UNTRUSTED_URL", "更新下载地址不属于本仓库，已拒绝")
        }
    }

    fun requireTransportUrl(url: String) {
        val uri = runCatching { URI(url) }.getOrNull()
        val host = uri?.host?.lowercase().orEmpty()
        if (uri?.scheme != "https" ||
            uri.userInfo != null ||
            uri.port !in listOf(-1, 443) ||
            !(host == "github.com" || host == "api.github.com" || host.endsWith(".githubusercontent.com"))
        ) {
            throw UpdateException("UNTRUSTED_REDIRECT", "更新连接跳转到非 GitHub HTTPS 地址，已拒绝")
        }
    }

    fun latest(
        json: String,
        includePreviews: Boolean,
    ): GitHubRelease? {
        val array = JSONArray(json)
        val candidates = mutableListOf<GitHubRelease>()
        for (i in 0 until array.length()) {
            val release = array.optJSONObject(i) ?: continue
            if (release.optBoolean("draft")) continue
            val tag = release.optString("tag_name")
            val version = ReleaseVersion.parse(tag) ?: continue
            if (tag != version.tag) continue
            val preview = release.optBoolean("prerelease") || version.test != null
            if (preview && !includePreviews) continue
            val assets = release.optJSONArray("assets") ?: continue
            val parsed =
                (0 until assets.length()).mapNotNull { index ->
                    assets.optJSONObject(index)?.let { asset(it, version.tag) }
                }
            val names = setOf("mcarplay-${version.base}.apk", "mcarplay-${version.tag.removePrefix("v")}.apk")
            val apks = parsed.filter { it.name in names && it.size in 1..MAX_APK_BYTES }
            if (apks.size != 1) continue
            val checksums = parsed.singleOrNull { it.name == "SHA256SUMS.txt" && it.size in 1..65_536 }
            if (apks.single().sha256 == null && checksums == null) continue
            candidates.add(
                GitHubRelease(
                    version,
                    release.optString("name", tag).take(160),
                    release.optString("body").take(24_000),
                    preview,
                    apks.single(),
                    checksums,
                ),
            )
        }
        return candidates.maxByOrNull { it.version }
    }

    private fun asset(
        json: JSONObject,
        tag: String,
    ): ReleaseAsset? {
        if (json.optString("state") != "uploaded") return null
        val name = json.optString("name")
        if (!Regex("[A-Za-z0-9._-]+").matches(name)) return null
        val url = json.optString("browser_download_url")
        if (runCatching { requireDownloadUrl(url, tag, name) }.isFailure) return null
        val digest =
            json
                .optString("digest")
                .takeIf { Regex("sha256:[a-fA-F0-9]{64}").matches(it) }
                ?.substringAfter(':')
                ?.lowercase()
        return ReleaseAsset(name, url, json.optLong("size", -1), digest)
    }

    fun checksumFor(
        text: String,
        fileName: String,
    ): String {
        val matches =
            text
                .lineSequence()
                .mapNotNull { line ->
                    val match = Regex("([a-fA-F0-9]{64})\\s+\\*?(?:\\./)?([^/\\s]+)\\s*").matchEntire(line) ?: return@mapNotNull null
                    if (match.groupValues[2] == fileName) match.groupValues[1].lowercase() else null
                }.toList()
        if (matches.size != 1) throw UpdateException("MISSING_CHECKSUM", "发行包缺少唯一有效的 SHA-256 校验值")
        return matches.single()
    }
}
