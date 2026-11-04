package com.mcarplay.app.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticExportPolicyTest {
    @Test
    fun onlyExactRemoteDebugCombinationCanExportUnredacted() {
        assertTrue(DiagnosticExportPolicy.allowsUnredacted("remote", "debug", debug = true))
        assertFalse(DiagnosticExportPolicy.allowsUnredacted("remote", "release", debug = false))
        assertFalse(DiagnosticExportPolicy.allowsUnredacted("remote", "release", debug = true))
        assertFalse(DiagnosticExportPolicy.allowsUnredacted("remote", "debug", debug = false))
        assertFalse(DiagnosticExportPolicy.allowsUnredacted("standard", "debug", debug = true))
        assertFalse(DiagnosticExportPolicy.allowsUnredacted("standard", "release", debug = false))
        assertFalse(DiagnosticExportPolicy.allowsUnredacted("remotePreview", "debug", debug = true))
        assertFalse(DiagnosticExportPolicy.allowsUnredacted("REMOTE", "debug", debug = true))
    }
}
