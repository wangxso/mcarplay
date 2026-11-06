package com.mcarplay.app.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

class BoundedDiagnosticCommandTest {
    @Test
    fun largeOutputIsDrainedAndBoundedOnBothPipes() {
        val out = ByteArrayInputStream(ByteArray(100_000) { 'a'.code.toByte() })
        val err = ByteArrayInputStream(ByteArray(100_000) { 'b'.code.toByte() })
        val result = BoundedDiagnosticCommand.read(out, err, { true }, { 7 }, {}, maxBytes = 1024)
        assertEquals(7, result.exitCode)
        assertFalse(result.timedOut)
        assertTrue(result.truncated)
        assertEquals(2049, result.output.length)
        assertEquals(0, out.available())
        assertEquals(0, err.available())
    }

    @Test
    fun timeoutDestroysProcessAndRemainsDistinctFromSuccess() {
        var destroyed = false
        val result =
            BoundedDiagnosticCommand.read(
                ByteArrayInputStream("partial".toByteArray()),
                ByteArrayInputStream(byteArrayOf()),
                { false },
                { error("must not query live process") },
                { destroyed = true },
            )
        assertTrue(destroyed)
        assertTrue(result.timedOut)
        assertEquals(-1, result.exitCode)
        assertTrue(result.output.contains("partial"))
    }

    @Test(timeout = 3_000)
    fun openPipeDoesNotBlockDeadlineBeforeProcessWait() {
        val stdout = PipedInputStream()
        val writer = PipedOutputStream(stdout)
        writer.write("pending".toByteArray())
        val result =
            BoundedDiagnosticCommand.read(
                stdout,
                ByteArrayInputStream(byteArrayOf()),
                {
                    Thread.sleep(50)
                    false
                },
                { 0 },
                { writer.close() },
                timeoutMs = 50,
            )
        assertTrue(result.timedOut)
        assertTrue(result.output.contains("pending"))
    }

    @Test
    fun diagnosticCommandsHaveNoShellOrDestructiveOperations() {
        val commands = CarWithSystemLogExporter.commands("com.mcarplay.app.rd")
        assertTrue(commands.any { it.first == "logcat" })
        assertTrue(commands.all { it.second.first() in setOf("logcat", "dumpsys", "ss", "appops", "settings") })
        assertTrue(commands.none { "-c" in it.second || "set" in it.second || "clear" in it.second })
    }
}
