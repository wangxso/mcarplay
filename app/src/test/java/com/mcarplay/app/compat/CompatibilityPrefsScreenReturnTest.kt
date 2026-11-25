package com.mcarplay.app.compat

import android.content.Context
import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 回屏偏好中性 API 的持久化契约：底层仍是 `settings` 文件里的历史键
 * `compat_passat_return_strategy`，只改 Kotlin 符号，不做键迁移。
 */
@RunWith(RobolectricTestRunner::class)
class CompatibilityPrefsScreenReturnTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        settings().edit().clear().commit()
    }

    @After
    fun tearDown() {
        settings().edit().clear().commit()
    }

    @Test
    fun legacyKeyRoundTripsThroughTheNeutralApi() {
        // 旧版本（或用户回滚）只写这个键；升级后必须原样读回。
        ScreenReturnStrategy.entries.forEach { strategy ->
            settings().edit().putString(LEGACY_KEY, strategy.key).commit()
            assertEquals(strategy, CompatibilityPrefs.screenReturnStrategy(context))
        }
    }

    @Test
    fun neutralSetterWritesTheUnchangedLegacyKeyAndNothingElse() {
        // apply() 的语义与旧 setter 相同：写完同上下文立刻可读，且不新增第二个键。
        ScreenReturnStrategy.entries.forEach { strategy ->
            CompatibilityPrefs.setScreenReturnStrategy(context, strategy)
            assertEquals(strategy.key, settings().getString(LEGACY_KEY, null))
            assertEquals("不得迁移到新键", setOf(LEGACY_KEY), settings().all.keys)
            assertEquals(strategy, CompatibilityPrefs.screenReturnStrategy(context))
        }
    }

    @Test
    fun missingUnknownWrongCaseOrBlankValuesFallBackToOff() {
        assertFalse(settings().contains(LEGACY_KEY))
        assertEquals(ScreenReturnStrategy.OFF, CompatibilityPrefs.screenReturnStrategy(context))
        listOf("", " ", "unknown", "OFF", "Rebuild_Retry", " rebuild_retry", "disconnect ").forEach { raw ->
            settings().edit().putString(LEGACY_KEY, raw).commit()
            assertEquals("$raw 必须回退 OFF", ScreenReturnStrategy.OFF, CompatibilityPrefs.screenReturnStrategy(context))
        }
    }

    @Test
    fun writeIsVisibleToTheSameContextWithoutTouchingOtherSettings() {
        settings().edit().putString(UNRELATED_KEY, UNRELATED_VALUE).commit()

        CompatibilityPrefs.setScreenReturnStrategy(context, ScreenReturnStrategy.NO_REBUILD)

        assertEquals(ScreenReturnStrategy.NO_REBUILD, CompatibilityPrefs.screenReturnStrategy(context))
        assertEquals(UNRELATED_VALUE, settings().getString(UNRELATED_KEY, null))
        assertTrue(settings().contains(LEGACY_KEY))
    }

    private fun settings(): SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private companion object {
        const val PREFS_NAME = "settings"
        const val LEGACY_KEY = "compat_passat_return_strategy"
        const val UNRELATED_KEY = "unrelated_key"
        const val UNRELATED_VALUE = "kept"
    }
}
