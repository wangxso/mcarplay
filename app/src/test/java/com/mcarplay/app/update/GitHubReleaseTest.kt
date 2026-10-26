package com.mcarplay.app.update

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseTest {
    @Test
    fun choosesHighestVersionRegardlessOfResponseOrder() {
        val json = JSONArray().put(releaseJson("v0.1.2-test.10")).put(releaseJson("v0.1.1")).put(releaseJson("v0.1.2-test.2"))
        assertEquals("v0.1.2-test.10", UpdateSource.latest(json.toString(), true)?.version?.tag)
        assertEquals("v0.1.1", UpdateSource.latest(json.toString(), false)?.version?.tag)
    }

    @Test
    fun stablePromotionWinsOverPreviewWithSameBase() {
        val json = JSONArray().put(releaseJson("v0.1.2-test.10")).put(releaseJson("v0.1.2"))
        assertEquals("v0.1.2", UpdateSource.latest(json.toString(), true)?.version?.tag)
    }

    @Test
    fun ignoresDraftsAndNonCanonicalTags() {
        val json = JSONArray().put(releaseJson().put("draft", true)).put(releaseJson().put("tag_name", "0.1.2-test.1"))
        assertNull(UpdateSource.latest(json.toString(), true))
    }

    @Test
    fun testTagIsPreviewEvenWhenApiFlagIsWrong() {
        val json = JSONArray().put(releaseJson().put("prerelease", false)).toString()
        assertNull(UpdateSource.latest(json, false))
        assertTrue(requireNotNull(UpdateSource.latest(json, true)).preview)
    }

    @Test
    fun rejectsMissingOrAmbiguousApks() {
        val json = releaseJson()
        val assets = json.getJSONArray("assets")
        assets.put(assetJson("v0.1.2-test.1", "mcarplay-0.1.2-test.1.apk"))
        assertNull(UpdateSource.latest(JSONArray().put(json).toString(), true))
        json.put("assets", JSONArray())
        assertNull(UpdateSource.latest(JSONArray().put(json).toString(), true))
    }

    @Test
    fun rejectsWrongRepositoryUnfinishedAssetsAndOversizeApks() {
        listOf(
            "browser_download_url" to "https://github.com/attacker/mcarplay/releases/download/v0.1.2-test.1/mcarplay-0.1.2.apk",
            "state" to "new",
            "size" to UpdateSource.MAX_APK_BYTES + 1,
            "size" to 0,
            "name" to "../mcarplay-0.1.2.apk",
        ).forEach { (field, value) ->
            val json = releaseJson()
            json.getJSONArray("assets").getJSONObject(0).put(field, value)
            assertNull("$field=$value", UpdateSource.latest(JSONArray().put(json).toString(), true))
        }
    }

    @Test
    fun requiresDigestOrChecksumAsset() {
        val json = releaseJson()
        json.getJSONArray("assets").getJSONObject(0).remove("digest")
        assertNull(UpdateSource.latest(JSONArray().put(json).toString(), true))
        json.getJSONArray("assets").put(assetJson("v0.1.2-test.1", "SHA256SUMS.txt"))
        assertEquals("SHA256SUMS.txt", UpdateSource.latest(JSONArray().put(json).toString(), true)?.checksums?.name)
    }

    @Test
    fun derivesReleasePageAndBoundsNotesInsteadOfUsingUntrustedHtmlUrl() {
        val json = releaseJson().put("html_url", "https://attacker.invalid").put("body", "x".repeat(30_000))
        val release = requireNotNull(UpdateSource.latest(JSONArray().put(json).toString(), true))
        assertEquals("https://github.com/wangxso/mcarplay/releases/tag/v0.1.2-test.1", release.pageUrl)
        assertEquals(24_000, release.notes.length)
    }

    @Test
    fun stableFetchUsesLatestEndpointRatherThanLimitedPreviewList() {
        val transport = MemoryTransport(mapOf(UpdateSource.STABLE_API_URL to releaseJson("v0.1.1").toString().toByteArray()))
        assertEquals("v0.1.1", UpdateSource.fetch(transport, false, UpdateJob())?.version?.tag)
        assertEquals(listOf(UpdateSource.STABLE_API_URL), transport.opened)
        assertEquals(1, transport.closed)
    }

    @Test
    fun previewFetchUsesListEndpoint() {
        val transport = MemoryTransport(mapOf(UpdateSource.API_URL to JSONArray().put(releaseJson()).toString().toByteArray()))
        assertEquals("v0.1.2-test.1", UpdateSource.fetch(transport, true, UpdateJob())?.version?.tag)
        assertEquals(listOf(UpdateSource.API_URL), transport.opened)
    }

    @Test
    fun noStableReleaseIsNormalButOtherHttpErrorsPropagate() {
        val missing = UpdateTransport { _, _ -> throw UpdateException("HTTP_404", "Not found") }
        assertNull(UpdateSource.fetch(missing, false, UpdateJob()))
        expectUpdateError("HTTP_404") { UpdateSource.fetch(missing, true, UpdateJob()) }
        val limited = UpdateTransport { _, _ -> throw UpdateException("RATE_LIMIT", "Rate limited") }
        expectUpdateError("RATE_LIMIT") { UpdateSource.fetch(limited, false, UpdateJob()) }
    }

    @Test
    fun checksumAcceptsTextBinaryAndRelativeFormats() {
        val hash = "A".repeat(64)
        listOf("$hash  mcarplay.apk", "$hash *mcarplay.apk", "$hash  ./mcarplay.apk\r\n").forEach {
            assertEquals(hash.lowercase(), UpdateSource.checksumFor(it, "mcarplay.apk"))
        }
    }

    @Test
    fun checksumRejectsMissingDuplicateAndPathEntries() {
        val hash = "a".repeat(64)
        listOf("", "$hash  another.apk", "$hash  ../mcarplay.apk", "$hash  mcarplay.apk\n$hash  mcarplay.apk").forEach {
            expectUpdateError("MISSING_CHECKSUM") { UpdateSource.checksumFor(it, "mcarplay.apk") }
        }
    }
}
