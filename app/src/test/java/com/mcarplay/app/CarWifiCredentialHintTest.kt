package com.mcarplay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class CarWifiCredentialHintTest {
    @Test
    fun knownSsidWithoutCredentialsClearsThePreviousPassphrase() {
        val hint = CarWifiCredentialHint()
        hint.remember("FirstCar", "first-secret")
        // 0x4301 只带 SSID（KNOWN_SSID）：不能继续把上一台车的密码留在剪贴板兜底路径里。
        hint.remember("SecondCar", null)
        assertEquals("SecondCar", hint.ssid)
        assertNull(hint.passphrase)
    }

    @Test
    fun ssidAndPassphraseArePublishedAsOneSnapshot() {
        // 并发读者只能看到「新 SSID + 它自己的密码」，不能看到新 SSID 配旧密码。
        val hint = CarWifiCredentialHint()
        val stop = AtomicBoolean(false)
        val mismatch = AtomicReference<CarWifiCredentialHint.Credential?>(null)
        val writerDone = CountDownLatch(1)
        val writer =
            Thread {
                repeat(50_000) { i ->
                    if (i % 2 == 0) hint.remember("A", "psk-A") else hint.remember("B", null)
                }
                stop.set(true)
                writerDone.countDown()
            }
        writer.start()
        while (!stop.get()) {
            val snapshot = hint.current() ?: continue
            val expected = if (snapshot.ssid == "A") "psk-A" else null
            if (snapshot.ssid !in setOf("A", "B") || snapshot.passphrase != expected) {
                mismatch.set(snapshot)
                break
            }
        }
        writerDone.await()
        writer.join()
        assertNull(mismatch.get())
    }
}
