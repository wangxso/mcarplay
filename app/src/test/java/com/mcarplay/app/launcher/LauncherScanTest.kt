package com.mcarplay.app.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 应用扫描的去重/过滤（2026-10-23 用户反馈「部分 app 没被扫描出来」的回归钉子）。
 *
 * 现场：旧实现 `queryIntentActivities(...).map{...}.take(200)` —— 上限写在排序**之前**，
 * 取的是系统返回顺序的前 200 个。应用装得多的机型上，排在后面的应用直接消失，
 * 而且丢哪些取决于系统返回顺序，看起来像「随机漏几个」。
 *
 * 实测数据（Redmi K40）：系统返回 83 个 launcher activity，去重后是 82 个包
 * （com.android.contacts 一个包挂了 4 个 Launcher activity）。
 */
class LauncherScanTest {
    @Test
    fun duplicateLauncherActivitiesOfSamePackageCollapse() {
        // 一个包多个 launcher activity 只保留一个（否则桌面出现重复图标）
        val result =
            dedupeLauncherPackages(
                listOf("com.android.contacts", "com.android.contacts", "com.android.contacts", "com.android.contacts", "com.zhihu.android"),
                selfPackage = "com.mcarplay.app",
            )
        assertEquals(listOf("com.android.contacts", "com.zhihu.android"), result)
    }

    @Test
    fun selfPackageIsExcluded() {
        val result =
            dedupeLauncherPackages(
                listOf("com.other.app", "com.mcarplay.app", "com.another.app"),
                selfPackage = "com.mcarplay.app",
            )
        assertEquals(listOf("com.other.app", "com.another.app"), result)
    }

    @Test
    fun noTruncationBeyondTwoHundredApps() {
        // 旧实现的 take(200) 会在这里丢掉 50 个——这正是「部分 app 扫不出来」的根因
        val many = (1..250).map { "com.example.app$it" }
        val result = dedupeLauncherPackages(many, selfPackage = "com.mcarplay.app")
        assertEquals("不得截断应用列表（旧实现 take(200) 丢尾部）", 250, result.size)
        assertEquals("com.example.app250", result.last())
    }

    @Test
    fun originalOrderIsPreserved() {
        // 顺序由调用方排序决定，本函数不得重排（否则拼音排序键会错位）
        val result = dedupeLauncherPackages(listOf("c", "a", "b", "a"), selfPackage = "self")
        assertEquals(listOf("c", "a", "b"), result)
    }

    @Test
    fun emptyInputIsHandled() {
        assertEquals(emptyList<String>(), dedupeLauncherPackages(emptyList(), selfPackage = "self"))
    }
}
