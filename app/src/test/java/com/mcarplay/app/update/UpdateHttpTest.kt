package com.mcarplay.app.update

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateHttpTest {
    @Test
    fun permitsOnlyFixedRepositoryAssetUrl() {
        val release = testRelease()
        UpdateSource.requireDownloadUrl(release.apk.url, release.version.tag, release.apk.name)
        listOf(
            release.apk.url.replace("https:", "http:"),
            release.apk.url.replace("github.com", "github.com.attacker.invalid"),
            release.apk.url.replace("github.com", "user@github.com"),
            release.apk.url.replace("github.com", "github.com:443"),
            release.apk.url + "?download=1",
            release.apk.url + "#fragment",
            release.apk.url.replace("wangxso", "other"),
            release.apk.url.replace("v0.1.2-test.1", "v0.1.1"),
        ).forEach { url ->
            expectUpdateError("UNTRUSTED_URL") { UpdateSource.requireDownloadUrl(url, release.version.tag, release.apk.name) }
        }
    }

    @Test
    fun redirectsAllowGithubAssetCdnButRejectHttpCredentialsAndForeignHosts() {
        listOf(
            "https://api.github.com/repos/wangxso/mcarplay/releases",
            "https://github.com/wangxso/mcarplay",
            "https://release-assets.githubusercontent.com/path?signature=example",
        ).forEach { UpdateSource.requireTransportUrl(it) }
        listOf(
            "http://github.com/x",
            "https://github.com:444/x",
            "https://user@github.com/x",
            "file:///tmp/app.apk",
            "https://githubusercontent.com/x",
            "https://github.com.attacker.invalid/x",
            "https://evilgithubusercontent.com/x",
            "https://release-assets.githubusercontent.com.attacker.invalid/x",
            "bad url",
        ).forEach { expectUpdateError("UNTRUSTED_REDIRECT") { UpdateSource.requireTransportUrl(it) } }
    }

    @Test
    fun cancelledJobFailsBeforeAnyNetworkRequest() {
        val job = UpdateJob().apply { cancel() }
        assertTrue(job.isCancelled)
        expectUpdateError("CANCELLED") { GitHubHttp.open("https://api.github.com", job) }
    }

    @Test
    fun oneActiveJobAndOnlyItsOwnerCanReleaseIt() {
        val first = requireNotNull(UpdateJobs.acquire())
        try {
            assertNull(UpdateJobs.acquire())
            UpdateJobs.release(UpdateJob())
            assertNull(UpdateJobs.acquire())
        } finally {
            UpdateJobs.release(first)
        }
        val next = UpdateJobs.acquire()
        assertNotNull(next)
        if (next != null) UpdateJobs.release(next)
    }
}
