package com.mcarplay.app.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 默认地图解析回归测试。
 *
 * 重点是「未设置默认地图」这条路径：旧实现里 `resolve()` 在该分支回调
 * `VirtualLauncher.defaultMapComponent()`、后者又转调回 `resolve()`，构成无限递归
 * → StackOverflowError。症状是卸载重装后地图卡不渲染（偏好被清空才触发），而
 * 用户在设置里重选一次即恢复——很容易被误判成虚拟屏/渲染问题（2026-10-18 实测）。
 *
 * 这里用包名字符串代替 ComponentName：`resolveDefaultMap` 是泛型化的纯函数，
 * 单测环境里 Android 类（ComponentName）的方法都是未实现的空桩。
 */
class DefaultMapPrefsTest {
    private val priority = listOf("car.map", "phone.map", "baidu.map")

    /** 用字符串做「已安装应用 → 可启动组件」的替身。 */
    private fun pick(
        manual: String?,
        installed: Set<String>,
    ): String? =
        resolveDefaultMap(
            manual = manual,
            priority = priority,
            componentFor = { pkg -> if (pkg in installed) pkg else null },
        )

    /** 未设置默认地图：按优先序取第一个「已安装」的（旧实现此处会栈溢出）。 */
    @Test
    fun unsetFallsBackToPriorityOrder() {
        assertEquals("phone.map", pick(manual = null, installed = setOf("phone.map", "baidu.map")))
    }

    /** 显式设置优先于优先序。 */
    @Test
    fun manualChoiceWins() {
        assertEquals("baidu.map", pick(manual = "baidu.map", installed = setOf("car.map", "baidu.map")))
    }

    /** 显式设置的应用已卸载：回落到优先序，而不是返回 null/崩溃。 */
    @Test
    fun uninstalledManualFallsBack() {
        assertEquals("car.map", pick(manual = "gone.map", installed = setOf("car.map")))
    }

    /** 一台地图都没有：返回 null（调用方显示「地图 tile 空」提示）。 */
    @Test
    fun noneInstalledReturnsNull() {
        assertNull(pick(manual = null, installed = emptySet()))
    }

    /** 反复解析必须每次都能终止（旧实现会 StackOverflowError）。 */
    @Test
    fun resolveTerminatesWithoutRecursion() {
        repeat(100) { assertNull(pick(manual = null, installed = emptySet())) }
    }
}

/**
 * 车机版地图启动入口（高德/百度）的候选顺序与回退。
 *
 * 背景：车机版地图的 LAUNCHER intent 常落在启动页而不是地图主界面，所以需要显式
 * 指定主 Activity；但**显式入口不保证在所有固件/版本存在**（百度车机版在不同定制
 * 固件上就有 NaviAutoActivity / SlashActivity 两种）。因此必须给候选列表：
 * 显式主界面优先，失败回退 launcher 解析结果。
 */
class MapLaunchCandidatesTest {
    @Test
    fun gaodeCarEditionPrefersMainMapActivity() {
        assertEquals(
            listOf("com.autonavi.amapauto/.MainMapActivity", "com.autonavi.amapauto/.Launcher"),
            mapLaunchCandidates("com.autonavi.amapauto", "com.autonavi.amapauto/.Launcher"),
        )
    }

    @Test
    fun baiduCarEditionUsesLauncherEntryOnly() {
        // 百度车机版（com.baidu.naviauto）不设显式主界面入口：实测 22.0.1 的包内
        // 没有地图主界面 Activity，地图由 mapautosdk 在 launcher 的 DefaultActivity
        // 内渲染。曾误写成 .NaviAutoActivity（不存在），am start 直接报
        // "Activity class does not exist"——不再保留该候选。
        assertEquals(
            listOf("com.baidu.naviauto/.SlashActivity.DefaultActivity"),
            mapLaunchCandidates("com.baidu.naviauto", "com.baidu.naviauto/.SlashActivity.DefaultActivity"),
        )
    }

    @Test
    fun phoneMapsFallBackToLauncherComponentOnly() {
        // 手机版没有已知的专用主界面：只保留 launcher 解析结果
        listOf("com.autonavi.minimap", "com.baidu.BaiduMap", "com.tencent.map").forEach { pkg ->
            assertEquals(listOf("$pkg/.Launcher"), mapLaunchCandidates(pkg, "$pkg/.Launcher"))
        }
    }

    @Test
    fun explicitEntryIsNotDuplicatedWhenItIsTheLauncher() {
        // launcher 本身就指向主界面时不要重复尝试同一个 component
        assertEquals(
            listOf("com.baidu.naviauto/.NaviAutoActivity"),
            mapLaunchCandidates("com.baidu.naviauto", "com.baidu.naviauto/.NaviAutoActivity"),
        )
    }

    @Test
    fun baiduCarEditionIsWhitelistedAheadOfPhoneMaps() {
        val priority = VirtualLauncher.MAP_PACKAGES_PRIORITY
        val auto = priority.indexOf("com.baidu.naviauto")
        assertTrue("百度车机版必须在白名单里", auto >= 0)
        assertTrue(
            "车机版应排在手机版之前（车机上优先用横屏适配好的车机版）",
            auto < priority.indexOf("com.baidu.BaiduMap"),
        )
        assertTrue(auto < priority.indexOf("com.autonavi.minimap"))
    }
}
