package com.mcarplay.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RotatingTextFileTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun keepsPreviousSegmentAcrossReopeningAfterProcessRestart() {
        val file = File(folder.root, "runtime.log")
        val log = RotatingTextFile(file, 20)
        log.append("previous-run")
        log.append("current-run")
        val reopened = RotatingTextFile(file, 20).read(100)
        assertTrue(reopened.contains("previous-run"))
        assertTrue(reopened.contains("current-run"))
    }

    @Test
    fun tailIsBoundedAndDoesNotSplitUtf8OrLines() {
        val log = RotatingTextFile(File(folder.root, "runtime.log"), 1000)
        repeat(20) { log.append("第 $it 行测试数据") }
        val tail = log.read(70)
        assertTrue(tail.startsWith("[earlier log content truncated]"))
        assertFalse(tail.contains('\uFFFD'))
        assertTrue(tail.endsWith("第 19 行测试数据\n"))
    }

    @Test
    fun retainsOnlyTwoBoundedSegments() {
        val file = File(folder.root, "runtime.log")
        val log = RotatingTextFile(file, 12)
        repeat(10) { log.append("record-$it") }
        assertTrue(file.length() <= 12)
        assertTrue(File(folder.root, "runtime.log.previous").length() <= 12)
        assertFalse(log.read(100).contains("record-0"))
        assertTrue(log.read(100).contains("record-9"))
    }

    @Test
    fun oversizedEntryCannotGrowStoragePastItsBound() {
        val file = File(folder.root, "runtime.log")
        val log = RotatingTextFile(file, 64)
        log.append("secret-data".repeat(1000))
        assertTrue(file.length() <= 64)
        assertTrue(log.read(100).contains("oversized log entry omitted"))
        assertFalse(log.read(100).contains("secret-data"))
    }

    @Test
    fun unreadableSegmentIsAnExplicitErrorRatherThanEmptyEvidence() {
        val log = RotatingTextFile(File(folder.root, "absent/child.log"), 100)
        assertEquals("", log.read(100))
        assertTrue(runCatching { log.append("data") }.exceptionOrNull() is java.io.IOException)
    }
}
