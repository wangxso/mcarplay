package com.mcarplay.app.update

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

class UpdateDownloaderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun successfulDownloadChecksBytesHashProgressAndClosesResponse() {
        val bytes = ByteArray(70_000) { it.toByte() }
        val release = testRelease(bytes)
        val transport = MemoryTransport(mapOf(release.apk.url to bytes))
        val progress = mutableListOf<Pair<Long, Long>>()
        val artifact =
            UpdateDownloader(transport).download(release, temporary.root, UpdateJob()) { count, total ->
                progress.add(
                    count to total,
                )
            }
        assertArrayEquals(bytes, artifact.file.readBytes())
        assertEquals(sha256(bytes), artifact.sha256)
        assertEquals(bytes.size.toLong() to bytes.size.toLong(), progress.last())
        assertEquals(listOf(artifact.file), temporary.root.listFiles()?.toList())
        assertEquals(1, transport.closed)
        UpdateDownloader.verifyHash(artifact.file, artifact.sha256, UpdateJob())
    }

    @Test
    fun checksumFallbackIsReadBeforeApk() {
        val bytes = "apk bytes".toByteArray()
        val base = testRelease(bytes)
        val checksum = ReleaseAsset("SHA256SUMS.txt", base.apk.url.substringBeforeLast('/') + "/SHA256SUMS.txt", 90, null)
        val release = base.copy(apk = base.apk.copy(sha256 = null), checksums = checksum)
        val transport =
            MemoryTransport(
                mapOf(
                    checksum.url to "${sha256(bytes)}  ${release.apk.name}\n".toByteArray(),
                    release.apk.url to bytes,
                ),
            )
        UpdateDownloader(transport).download(release, temporary.root, UpdateJob()) { _, _ -> }
        assertEquals(listOf(checksum.url, release.apk.url), transport.opened)
        assertEquals(2, transport.closed)
    }

    @Test
    fun hashMismatchRemovesPartialAndPreservesPreviousVerifiedFile() {
        val release = testRelease().let { it.copy(apk = it.apk.copy(sha256 = "0".repeat(64))) }
        val old = File(temporary.root, "mcarplay-update-${release.version.tag}.apk").apply { writeText("previous verified file") }
        val transport = MemoryTransport(mapOf(release.apk.url to "test apk".toByteArray()))
        expectUpdateError("CHECKSUM_MISMATCH") {
            UpdateDownloader(transport).download(release, temporary.root, UpdateJob()) { _, _ -> }
        }
        assertEquals("previous verified file", old.readText())
        assertEquals(listOf(old), temporary.root.listFiles()?.toList())
        assertEquals(1, transport.closed)
    }

    @Test
    fun truncatedAndOverlongDownloadsLeaveNoPartialFiles() {
        val release = testRelease()
        listOf("short" to "APK_TRUNCATED", "too many bytes" to "APK_SIZE").forEach { (body, code) ->
            val transport = MemoryTransport(mapOf(release.apk.url to body.toByteArray()))
            expectUpdateError(code) { UpdateDownloader(transport).download(release, temporary.root, UpdateJob()) { _, _ -> } }
            assertTrue(
                temporary.root
                    .listFiles()
                    .orEmpty()
                    .isEmpty(),
            )
            assertEquals(1, transport.closed)
        }
    }

    @Test
    fun cancellationDuringDownloadClosesStreamAndDeletesPartial() {
        val bytes = ByteArray(70_000)
        val release = testRelease(bytes)
        val transport = MemoryTransport(mapOf(release.apk.url to bytes))
        val job = UpdateJob()
        expectUpdateError("CANCELLED") {
            UpdateDownloader(transport).download(release, temporary.root, job) { _, _ -> job.cancel() }
        }
        assertTrue(
            temporary.root
                .listFiles()
                .orEmpty()
                .isEmpty(),
        )
        assertEquals(1, transport.closed)
    }

    @Test
    fun ioFailureClosesStreamAndDeletesPartial() {
        val release = testRelease()
        var closed = false
        val transport =
            UpdateTransport { _, _ ->
                UpdateResponse(
                    object : InputStream() {
                        override fun read(): Int = throw IOException("Disconnected")
                    },
                ) { closed = true }
            }
        assertThrows(IOException::class.java) { UpdateDownloader(transport).download(release, temporary.root, UpdateJob()) { _, _ -> } }
        assertTrue(closed)
        assertTrue(
            temporary.root
                .listFiles()
                .orEmpty()
                .isEmpty(),
        )
    }

    @Test
    fun invalidMetadataIsRejectedBeforeOpeningApk() {
        val release = testRelease()
        listOf(
            release.copy(apk = release.apk.copy(sha256 = null)) to "MISSING_CHECKSUM",
            release.copy(apk = release.apk.copy(sha256 = "bad")) to "MISSING_CHECKSUM",
            release.copy(apk = release.apk.copy(size = 0)) to "APK_SIZE",
            release.copy(apk = release.apk.copy(url = "https://attacker.invalid/app.apk")) to "UNTRUSTED_URL",
        ).forEach { (candidate, code) ->
            val transport = MemoryTransport(emptyMap())
            expectUpdateError(code) { UpdateDownloader(transport).download(candidate, temporary.root, UpdateJob()) { _, _ -> } }
            assertTrue(transport.opened.isEmpty())
        }
    }

    @Test
    fun modifiedVerifiedFileFailsRecheck() {
        val file = temporary.newFile().apply { writeText("original") }
        val hash = sha256(file.readBytes())
        file.writeText("tampered")
        expectUpdateError("CHECKSUM_MISMATCH") { UpdateDownloader.verifyHash(file, hash, UpdateJob()) }
    }

    @Test
    fun boundedTextClosesResponseOnOverflowAndCancellation() {
        val transport = MemoryTransport(mapOf("fixture" to "hello".toByteArray()))
        assertEquals("hello", transport.text("fixture", 5, UpdateJob()))
        expectUpdateError("RESPONSE_TOO_LARGE") { transport.text("fixture", 4, UpdateJob()) }
        assertEquals(2, transport.closed)
        var closed = false
        val job = UpdateJob()
        val cancelling =
            UpdateTransport { _, _ ->
                job.cancel()
                UpdateResponse(ByteArrayInputStream(byteArrayOf(1))) { closed = true }
            }
        expectUpdateError("CANCELLED") { cancelling.text("fixture", 5, job) }
        assertTrue(closed)
    }

    @Test
    fun alreadyCancelledDownloadDoesNotCreateDirectoryOrOpenTransport() {
        val transport = MemoryTransport(emptyMap())
        val directory = File(temporary.root, "not-created")
        val job = UpdateJob().apply { cancel() }
        expectUpdateError("CANCELLED") { UpdateDownloader(transport).download(testRelease(), directory, job) { _, _ -> } }
        assertFalse(directory.exists())
        assertTrue(transport.opened.isEmpty())
    }
}
