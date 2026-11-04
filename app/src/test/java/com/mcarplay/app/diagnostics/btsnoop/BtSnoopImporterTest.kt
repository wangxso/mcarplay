package com.mcarplay.app.diagnostics.btsnoop

import com.mcarplay.app.diagnostics.btsnoop.BtSnoopFixtures.Companion.eir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BtSnoopImporterTest {
    private fun capture(): ByteArray =
        BtSnoopFixtures()
            .apply { command(0x0c52, byteArrayOf(0) + eir("P", emptyList(), listOf(BtUuids.CARPLAY))) }
            .bytes()

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun bugreportZipYieldsCurrentAndRotatedCaptures() {
        val bugreport =
            zip(
                "bugreport-device-2026-11-02.txt" to "BTSNOOP_LOG_SUMMARY text".toByteArray(),
                "FS/data/misc/bluetooth/logs/btsnoop_hci.log" to capture(),
                "FS/data/misc/bluetooth/logs/btsnoop_hci.log.last" to capture(),
            )
        val outcome = BtSnoopImporter.analyze(ByteArrayInputStream(bugreport))
        outcome as BtSnoopImporter.Outcome.Analyzed
        assertEquals(setOf("btsnoop_hci.log", "btsnoop_hci.log.last"), outcome.files.map { it.name }.toSet())
        assertTrue(outcome.files.all { it.result.eirWrites.size == 1 })
    }

    @Test
    fun nestedZipIsSearched() {
        val inner = zip("FS/data/misc/bluetooth/logs/btsnoop_hci.log" to capture())
        val outer = zip("bugreport.zip" to inner)
        val outcome = BtSnoopImporter.analyze(ByteArrayInputStream(outer))
        assertEquals(1, (outcome as BtSnoopImporter.Outcome.Analyzed).files.size)
    }

    @Test
    fun zipWithoutCaptureAndOtherFilesAreDistinguished() {
        assertSame(BtSnoopImporter.Outcome.NoCapture, BtSnoopImporter.analyze(ByteArrayInputStream(zip("main.txt" to "x".toByteArray()))))
        assertSame(BtSnoopImporter.Outcome.Unsupported, BtSnoopImporter.analyze(ByteArrayInputStream(byteArrayOf(-119, 0x50, 0x4e, 0x47))))
        val notSnoop = zip("FS/data/misc/bluetooth/logs/btsnoop_hci.log" to "garbage".toByteArray())
        assertSame(BtSnoopImporter.Outcome.NoCapture, BtSnoopImporter.analyze(ByteArrayInputStream(notSnoop)))
    }

    @Test
    fun rawCaptureIsAccepted() {
        val outcome = BtSnoopImporter.analyze(ByteArrayInputStream(capture()))
        assertEquals(1, (outcome as BtSnoopImporter.Outcome.Analyzed).files.size)
    }

    @Test
    fun captureNamesExcludeTextAndArchives() {
        assertTrue(BtSnoopImporter.isCaptureName("btsnoop_hci.log.last"))
        assertTrue(BtSnoopImporter.isCaptureName("btsnoop_hci.log.filtered"))
        assertFalse(BtSnoopImporter.isCaptureName("btsnoop_summary.txt"))
        assertFalse(BtSnoopImporter.isCaptureName("main_debug.log"))
    }
}
