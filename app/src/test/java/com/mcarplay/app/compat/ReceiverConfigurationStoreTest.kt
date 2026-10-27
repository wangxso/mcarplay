package com.mcarplay.app.compat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverConfigurationStoreTest {
    private val store = ReceiverConfigurationStore(secret = ByteArray(32) { it.toByte() })
    private val address = "A0:B1:C2:D3:E4:F5"

    private fun entry(index: Int = 0) =
        LocalReceiverConfiguration(
            binding = ReceiverFingerprint.digest(listOf("binding-$index")),
            environment = ReceiverFingerprint.digest(listOf("phone-env")),
            capabilities = ReceiverFingerprint.digest(listOf("capabilities")),
            savedAtMs = 42,
            build = "0.1.3-debug",
            bluetooth = BluetoothBringup.KEEP_PROFILES,
            infoRequest = InfoRequest.GET_EMPTY_PLIST,
            audioProfile = "102|media|8388608|AAC_LC",
            verification = ReceiverVerification(ReceiverCheck.CONFIRMED, ReceiverCheck.CONFIRMED),
            observedSeconds = 600,
        )

    @Test
    fun addressBindingIsStableCaseInsensitiveAndSpecificToInstallation() {
        assertEquals(store.binding(address), store.binding(address.lowercase()))
        assertNotEquals(store.binding(address), ReceiverConfigurationStore(secret = ByteArray(32) { 7 }).binding(address))
        assertNull(store.binding(null))
        assertNull(store.binding("unknown"))
        assertNull(store.binding("02:00:00:00:00:00"))
    }

    @Test
    fun roundTripKeepsOnlyWhitelistedParametersAndNoRawIdentifiers() {
        val original = entry().copy(binding = store.binding(address)!!)
        val encoded = store.with(original).encode()
        val loaded = ReceiverConfigurationStore.decode(encoded)
        assertEquals(original, loaded.entries.single())
        assertEquals(store.binding(address), loaded.binding(address))
        assertFalse(encoded.contains(address, ignoreCase = true))
        assertFalse(encoded.contains("passphrase"))
        assertFalse(encoded.contains("publicKey"))
        assertFalse(encoded.contains("airPlayPort"))
        assertFalse(encoded.contains("ipAddresses"))
    }

    @Test
    fun changedEnvironmentCannotReuseSavedParameters() {
        val entry = entry()
        val saved = store.with(entry)
        assertEquals(entry, saved.find(entry.binding, entry.environment))
        assertNull(saved.find(entry.binding, ReceiverFingerprint.digest(listOf("updated-ROM"))))
        assertNull(saved.find("different-instance", entry.environment))
    }

    @Test
    fun cancelledAndUncommittedDraftsLeavePreviousConfigurationIntact() {
        val previous = entry()
        val saved = store.with(previous)
        val detector = ReceiverDetection()
        detector.begin(previous.binding, previous.environment)
        detector.stop("USER_STOP", cancelled = true)
        assertNull(detector.prepareSave(ReceiverVerification(video = ReceiverCheck.CONFIRMED), 99, "test"))
        assertEquals(previous, saved.find(previous.binding, previous.environment))
    }

    @Test
    fun replacementAndDeletionAffectOnlyOneReceiverAndStorageIsBounded() {
        val first = entry()
        val second = entry(1)
        val saved = store.with(first).with(second).with(first.copy(infoRequest = InfoRequest.POST_EMPTY_PLIST))
        assertEquals(2, saved.entries.size)
        assertEquals(InfoRequest.POST_EMPTY_PLIST, saved.find(first.binding, first.environment)!!.infoRequest)
        assertEquals(listOf(second), saved.without(first.binding).entries)
        val bounded = (0..50).fold(store) { previous, index -> previous.with(entry(index)) }
        assertEquals(ReceiverConfigurationStore.MAX_ENTRIES, bounded.entries.size)
        assertEquals(entry(50), bounded.entries.first())
    }

    @Test
    fun unknownSchemaEngineAndInvalidParameterValuesAreRejected() {
        for ((field, value) in listOf("schema" to 999, "engine" to 999)) {
            rejects(JSONObject(store.with(entry()).encode()).put(field, value).toString())
        }
        for ((field, value) in listOf("bluetooth" to "DROP_EVERYTHING", "infoRequest" to "EXEC", "audioProfile" to "shell command")) {
            val root = JSONObject(store.with(entry()).encode())
            root.getJSONArray("entries").getJSONObject(0).put(field, value)
            rejects(root.toString())
        }
        rejects("x".repeat(ReceiverConfigurationStore.MAX_BYTES + 1))
    }

    @Test
    fun invalidDigestsDuplicateBindingsAndUnconfirmedVideoCannotBeLoaded() {
        val valid = store.with(entry()).encode()
        for ((field, value) in listOf("binding" to "unknown", "video" to "UNVERIFIED", "observedSeconds" to -1)) {
            val root = JSONObject(valid)
            root.getJSONArray("entries").getJSONObject(0).put(field, value)
            rejects(root.toString())
        }
        val duplicate = JSONObject(valid)
        duplicate.getJSONArray("entries").put(duplicate.getJSONArray("entries").getJSONObject(0))
        rejects(duplicate.toString())
    }

    private fun rejects(text: String) {
        assertTrue(runCatching { ReceiverConfigurationStore.decode(text) }.isFailure)
    }
}
