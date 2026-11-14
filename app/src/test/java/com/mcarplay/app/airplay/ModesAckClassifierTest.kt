package com.mcarplay.app.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** issue #62：`modesChanged` 的 42B `{}` 应答必须有明确归类，而不是只记形状。 */
class ModesAckClassifierTest {
    private fun encode(value: Any?): ByteArray = BinaryPlist.encode(value)

    @Test
    fun emptyBodyIsEmpty() {
        assertEquals(ModesAckClassifier.Kind.EMPTY, ModesAckClassifier.classify(ByteArray(0)))
    }

    @Test
    fun emptyDictionaryIsNoAppStates() {
        val body = encode(linkedMapOf<String, Any?>())
        assertEquals(ModesAckClassifier.Kind.NO_APP_STATES, ModesAckClassifier.classify(body))
        assertTrue(ModesAckClassifier.describe(body, null).contains("未回显模式状态"))
    }

    @Test
    fun dictionaryWithUnrelatedKeysIsStillNoAppStates() {
        val body = encode(linkedMapOf<String, Any?>("status" to 0L))
        assertEquals(ModesAckClassifier.Kind.NO_APP_STATES, ModesAckClassifier.classify(body))
    }

    @Test
    fun appStatesAreRecognisedAndConfirmationIsReported() {
        val confirmed =
            encode(
                linkedMapOf(
                    "appStates" to listOf(linkedMapOf<String, Any?>("appStateID" to 1L, "entity" to 1L, "state" to true)),
                ),
            )
        assertEquals(ModesAckClassifier.Kind.APP_STATES, ModesAckClassifier.classify(confirmed))
        assertTrue(ModesAckClassifier.describe(confirmed, null).contains("含 state=true"))

        val unconfirmed =
            encode(linkedMapOf("appStates" to listOf(linkedMapOf<String, Any?>("appStateID" to 1L, "entity" to 2L))))
        assertEquals(ModesAckClassifier.Kind.APP_STATES, ModesAckClassifier.classify(unconfirmed))
        assertFalse(ModesAckClassifier.describe(unconfirmed, null).contains("含 state=true"))
    }

    @Test
    fun emptyAppStatesListIsNotAConfirmation() {
        val body = encode(linkedMapOf<String, Any?>("appStates" to emptyList<Any>()))
        assertEquals(ModesAckClassifier.Kind.NO_APP_STATES, ModesAckClassifier.classify(body))
    }

    @Test
    fun garbageIsUnreadableNotSilentlyEmpty() {
        val junk = byteArrayOf(1, 2, 3, 4, 5)
        assertEquals(ModesAckClassifier.Kind.UNREADABLE, ModesAckClassifier.classify(junk))
        assertTrue(ModesAckClassifier.describe(junk, null).contains("无法解析"))
        assertNull(junk.takeIf { ModesAckClassifier.classify(it) == ModesAckClassifier.Kind.APP_STATES })
    }

    @Test
    fun describeKeepsByteCountAndShapeForReports() {
        val body = encode(linkedMapOf<String, Any?>())
        val text = ModesAckClassifier.describe(body, "{appStateID=1}")
        assertTrue(text, text.contains("${body.size}B"))
        assertTrue(text, text.contains("形状={appStateID=1}"))
    }
}
