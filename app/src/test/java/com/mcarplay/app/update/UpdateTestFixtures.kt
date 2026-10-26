package com.mcarplay.app.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import java.io.ByteArrayInputStream
import java.security.MessageDigest

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal fun assetJson(
    tag: String,
    name: String = "mcarplay-${requireNotNull(ReleaseVersion.parse(tag)).base}.apk",
    bytes: ByteArray = "test apk".toByteArray(),
): JSONObject =
    JSONObject()
        .put("name", name)
        .put("state", "uploaded")
        .put("browser_download_url", "https://github.com/${UpdateSource.REPOSITORY}/releases/download/$tag/$name")
        .put("size", bytes.size)
        .put("digest", "sha256:${sha256(bytes)}")

internal fun releaseJson(tag: String = "v0.1.2-test.1"): JSONObject =
    JSONObject()
        .put("tag_name", tag)
        .put("name", "Release $tag")
        .put("body", "Release notes")
        .put("draft", false)
        .put("prerelease", tag.contains("-test."))
        .put("assets", JSONArray().put(assetJson(tag)))

internal fun testRelease(bytes: ByteArray = "test apk".toByteArray()): GitHubRelease {
    val tag = "v0.1.2-test.1"
    val json = releaseJson(tag).put("assets", JSONArray().put(assetJson(tag, bytes = bytes)))
    return requireNotNull(UpdateSource.latest(JSONArray().put(json).toString(), true))
}

internal fun expectUpdateError(
    code: String,
    block: () -> Unit,
) {
    assertEquals(code, assertThrows(UpdateException::class.java, block).code)
}

internal class MemoryTransport(
    private val contents: Map<String, ByteArray>,
) : UpdateTransport {
    val opened = mutableListOf<String>()
    var closed = 0

    override fun open(
        url: String,
        job: UpdateJob,
    ): UpdateResponse {
        job.check()
        opened.add(url)
        return UpdateResponse(ByteArrayInputStream(requireNotNull(contents[url]) { "Unexpected URL $url" })) { closed++ }
    }
}
