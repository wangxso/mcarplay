package com.mcarplay.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceEnvironmentTest {
    @Test
    fun missingValuesAreNamedInsteadOfBlank() {
        assertEquals(
            "ENV: 手机=Xiaomi 2509FPN0BC Android=16 (SDK 36) HyperOS=未知 ROM=未知 CarWith=未安装 CarLife=v8.8.4 (1) mcarplay=0.1.3",
            DeviceEnvironment.format("Xiaomi 2509FPN0BC", "16 (SDK 36)", null, null, null, "v8.8.4 (1)", "0.1.3"),
        )
    }
}
