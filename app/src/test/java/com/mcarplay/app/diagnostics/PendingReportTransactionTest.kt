package com.mcarplay.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PendingReportTransactionTest {
    @Test
    fun publishesOnlyAfterCompleteWrite() {
        val steps = mutableListOf<String>()
        val item =
            PendingReportTransaction.write(
                create = {
                    steps.add("create")
                    "uri"
                },
                write = { steps.add("write") },
                publish = {
                    steps.add("publish")
                    true
                },
                delete = { steps.add("delete") },
            )
        assertEquals("uri", item)
        assertEquals(listOf("create", "write", "publish"), steps)
    }

    @Test
    fun failedWriteRemovesPendingReportAndNeverPublishes() {
        val steps = mutableListOf<String>()
        val failure =
            runCatching {
                PendingReportTransaction.write(
                    create = { "uri" },
                    write = { throw IOException("disk full") },
                    publish = {
                        steps.add("publish")
                        true
                    },
                    delete = { steps.add("delete") },
                )
            }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(listOf("delete"), steps)
    }

    @Test
    fun zeroRowPublicationIsFailureAndRemovesPendingReport() {
        var deleted = false
        val failure =
            runCatching {
                PendingReportTransaction.write({ "uri" }, {}, { false }, { deleted = true })
            }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(deleted)
    }

    @Test
    fun createFailureDoesNotDeleteAnUnrelatedItem() {
        var deleted = false
        val failure =
            runCatching {
                PendingReportTransaction.write<String>({ throw SecurityException() }, {}, { true }, { deleted = true })
            }.exceptionOrNull()
        assertTrue(failure is SecurityException)
        assertEquals(false, deleted)
    }

    @Test
    fun nullInsertDoesNotPretendExportSucceeded() {
        val failure = runCatching { PendingReportTransaction.write<String>({ null }, {}, { true }, {}) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }

    @Test
    fun cleanupFailureDoesNotHideOriginalWriteFailure() {
        val failure =
            runCatching {
                PendingReportTransaction.write({ "uri" }, { throw IOException("original") }, { true }, { throw SecurityException() })
            }.exceptionOrNull()
        assertEquals("original", failure?.message)
    }
}
