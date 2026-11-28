package com.mcarplay.app.net

import com.mcarplay.app.SessionController
import com.mcarplay.app.architecture.KotlinSource
import com.mcarplay.app.architecture.SourceTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class CarWifiStateTest {
    @Test
    fun knownSsidWithoutCredentialsClearsThePreviousPassphrase() {
        val hint = CarWifiState()
        hint.remember("FirstCar", "first-secret")
        // 0x4301 只带 SSID（KNOWN_SSID）：不能继续把上一台车的密码留在剪贴板兜底路径里。
        hint.remember("SecondCar", null)
        assertEquals("SecondCar", hint.ssid)
        assertNull(hint.passphrase)
    }

    @Test
    fun ssidAndPassphraseArePublishedAsOneSnapshot() {
        // 并发读者只能看到「新 SSID + 它自己的密码」，不能看到新 SSID 配旧密码。
        val hint = CarWifiState()
        val stop = AtomicBoolean(false)
        val mismatch = AtomicReference<CarWifiState.Credential?>(null)
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

    @Test
    fun boundCarSsidAndCredentialSnapshotRemainIndependent() {
        val state = CarWifiState()
        assertNull(state.boundCarSsid)
        assertNull(state.current())
        assertNull(state.ssid)
        assertNull(state.passphrase)

        // 记住凭据不代表已成功绑定热点
        state.remember("CarHotspot-A", "secret-a")
        assertNull(state.boundCarSsid)
        assertEquals(CarWifiState.Credential("CarHotspot-A", "secret-a"), state.current())

        // 绑定成功后设置 boundCarSsid；断开清 null 不清空最近一次凭据记忆
        state.boundCarSsid = "CarHotspot-A"
        assertEquals("CarHotspot-A", state.boundCarSsid)
        state.boundCarSsid = null
        assertNull(state.boundCarSsid)
        assertEquals("CarHotspot-A", state.ssid)
        assertEquals("secret-a", state.passphrase)
    }

    @Test
    fun forgetPromptAndShellFallbackCleanupFieldsPreserveOriginalSemantics() {
        val state = CarWifiState()
        assertTrue(state.forgetPromptHandled.isEmpty())
        assertNull(state.shellFallbackCleanupSsid)

        // forgetPromptHandled：同一 SSID 在进程寿命内 add 仅首次返回 true
        assertTrue(state.forgetPromptHandled.add("SavedHotspot-1"))
        assertFalse(state.forgetPromptHandled.add("SavedHotspot-1"))
        assertTrue(state.forgetPromptHandled.add("SavedHotspot-2"))
        assertEquals(setOf("SavedHotspot-1", "SavedHotspot-2"), state.forgetPromptHandled)

        // shellFallbackCleanupSsid：deep 新存立即登记，cleanup 先读再清 null
        state.shellFallbackCleanupSsid = "FallbackHotspot"
        val toCleanup = state.shellFallbackCleanupSsid
        state.shellFallbackCleanupSsid = null
        assertEquals("FallbackHotspot", toCleanup)
        assertNull(state.shellFallbackCleanupSsid)
    }

    @Test
    fun sessionControllerFacadeReadsDirectlyFromSingleLongLivedCarWifiState() {
        val state = SessionController.carWifiState
        val previousCredential = state.current()
        val previousBound = state.boundCarSsid
        val previousCleanup = state.shellFallbackCleanupSsid
        try {
            assertSame(state, SessionController.carWifiState)
            state.remember("FacadeCar-1", "psk-1")
            assertEquals("FacadeCar-1", SessionController.lastCarWifiSsid)
            assertEquals("psk-1", SessionController.lastWifiPsk)

            state.remember("FacadeCar-2", null)
            assertEquals("FacadeCar-2", SessionController.lastCarWifiSsid)
            assertNull(SessionController.lastWifiPsk)
        } finally {
            if (previousCredential != null) {
                state.remember(previousCredential.ssid, previousCredential.passphrase)
            }
            state.boundCarSsid = previousBound
            state.shellFallbackCleanupSsid = previousCleanup
        }
    }

    @Test
    fun sourceContractEliminatesCarWifiCredentialHintAndUsesSingleCarWifiState() {
        assertFalse(
            "CarWifiCredentialHint.kt 已合并入 CarWifiState，不得残留原文件",
            SourceTree.mainKotlinFiles.containsKey("CarWifiCredentialHint.kt"),
        )
        SourceTree.mainKotlinFiles.forEach { (path, file) ->
            assertFalse(
                "$path 不得残留 CarWifiCredentialHint 引用",
                file.readText().contains("CarWifiCredentialHint"),
            )
        }

        val controllerCode = SourceTree.scan("SessionController.kt").code
        assertEquals(
            "SessionController 只能构造一次长生命周期 CarWifiState()",
            1,
            Regex("""\bCarWifiState\(\)""").findAll(controllerCode).count(),
        )
        assertTrue(
            "lastWifiPsk 必须直接转发 carWifiState.passphrase",
            controllerCode.contains("val lastWifiPsk: String? get() = carWifiState.passphrase"),
        )
        assertTrue(
            "lastCarWifiSsid 必须直接转发 carWifiState.ssid",
            controllerCode.contains("val lastCarWifiSsid: String? get() = carWifiState.ssid"),
        )
        assertTrue(
            "boundCarSsid 必须直接转发 carWifiState.boundCarSsid",
            controllerCode.contains("get() = carWifiState.boundCarSsid") &&
                controllerCode.contains("carWifiState.boundCarSsid = value"),
        )
        assertTrue(
            "forgetPromptHandled 必须直接转发 carWifiState.forgetPromptHandled",
            controllerCode.contains("get() = carWifiState.forgetPromptHandled"),
        )
        assertTrue(
            "shellFallbackCleanupSsid 必须直接转发 carWifiState.shellFallbackCleanupSsid",
            controllerCode.contains("get() = carWifiState.shellFallbackCleanupSsid") &&
                controllerCode.contains("carWifiState.shellFallbackCleanupSsid = value"),
        )
        assertEquals(
            "rememberEndpointWifi 与 onWifiCredentials 两条分支共 3 处应写入 carWifiState.remember",
            3,
            Regex("""\bcarWifiState\.remember\(""").findAll(controllerCode).count(),
        )

        val stateCode = SourceTree.scan("net/CarWifiState.kt").code
        val forbiddenTokens =
            listOf(
                "Context",
                "NetworkCallback",
                "SessionUiPort",
                "AttemptScope",
                "ShizukuGate",
                "CarPlayCtrlAdvertiser",
                "ctrlAdvertiser",
                "InternetNetwork",
                "AirPlayClient",
                "currentClient",
                "WifiCredentialCache",
                "synchronized",
                "compareAndSet",
            )
        forbiddenTokens.forEach { token ->
            assertFalse(
                "CarWifiState 不得引入 $token",
                Regex("""\b${Regex.escape(token)}\b""").containsMatchIn(stateCode),
            )
        }
        val vehicleWords =
            setOf(
                "passat",
                "audi",
                "honda",
                "cadillac",
                "diplay",
                "winplay",
                "macplay",
                "chery",
                "arrizo",
                "mib3",
                "id7",
                "vw",
                "cinemo",
            )
        val hits = KotlinSource.identifierWords(stateCode) intersect vehicleWords
        assertTrue("CarWifiState 标识符不得含车型词：$hits", hits.isEmpty())
    }
}
