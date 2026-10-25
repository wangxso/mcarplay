package com.mcarplay.app.launcher.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `am compat` 失败分类测试。
 *
 * 这个分类决定「偏好要不要落盘」：永久拒绝（user build 限制）落盘只会让下次连接
 * 白跑一轮 applyAll，且面板显示「已开启」而系统实际没生效；临时故障则相反，应保留
 * 用户意愿以便重试。实测样本取自 2026-10-18 红米 Android 13 user build 的真实输出。
 */
class CompatFailureClassifyTest {
    /** 已安装的普通第三方应用在 user build 上被拒（本次问题的真实输出）。 */
    @Test
    fun nonDebuggableOnUserBuildIsUnsupported() {
        assertEquals(
            AppOrientationCompat.WriteResult.UNSUPPORTED,
            classifyCompatFailure("Cannot override a change on a non-debuggable app and user build."),
        )
    }

    /** 目标是该 ChangeId 的默认对象时的另一种拒绝措辞。 */
    @Test
    fun defaultChangeOnUserBuildIsUnsupported() {
        assertEquals(
            AppOrientationCompat.WriteResult.UNSUPPORTED,
            classifyCompatFailure("Cannot override a default enabled/disabled change on a user build."),
        )
    }

    /**
     * **本机 ROM 未注册该 ChangeId**（红米 Android 13 实测输出）。
     *
     * 这是「荣耀能改、红米不能」的真正原因：这两个 ChangeId 是 OEM 定制项，
     * 荣耀注册了（392 个）、红米没注册（173 个）。未注册时 `am compat` 先给
     * `is not known yet` 警告，再以 user build 的措辞拒绝。
     */
    @Test
    fun unregisteredChangeIdIsUnsupported() {
        assertEquals(
            AppOrientationCompat.WriteResult.UNSUPPORTED,
            classifyCompatFailure(
                "Warning! Change 265451093 is not known yet. Enabling/disabling it could have no effect.\n" +
                    "Cannot override a change on a non-debuggable app and user build.",
            ),
        )
        // 仅警告行（某些 ROM 只回警告）同样判永久
        assertEquals(
            AppOrientationCompat.WriteResult.UNSUPPORTED,
            classifyCompatFailure("Warning! Change 265464455 is not known yet."),
        )
    }

    /** Shizuku 未连接：临时故障，可重试。 */
    @Test
    fun shizukuNotConnectedIsTemporary() {
        assertEquals(
            AppOrientationCompat.WriteResult.FAILED,
            classifyCompatFailure("Shizuku 未连接"),
        )
    }

    /** 超时/进程被杀：临时故障。 */
    @Test
    fun emptyOrTimeoutIsTemporary() {
        assertEquals(AppOrientationCompat.WriteResult.FAILED, classifyCompatFailure(""))
        assertEquals(
            AppOrientationCompat.WriteResult.FAILED,
            classifyCompatFailure("命令执行失败：timeout"),
        )
    }

    /** 成功输出不该被判成永久拒绝（防御：调用方仅在失败时才分类）。 */
    @Test
    fun successTextIsNotPermanent() {
        assertEquals(
            AppOrientationCompat.WriteResult.FAILED,
            classifyCompatFailure("Enabled change 265451093 for com.example.app."),
        )
    }

    /**
     * 版本门槛：这两个 ChangeId 自 **Android 14 / SDK 34** 起才进 AOSP
     * （`android.content.pm.ActivityInfo`）。Android 13 及更早的 ActivityInfo 里
     * 一个 orientation 类 ChangeId 都没有（实测 A13=0、A14=9），因此低版本
     * 不必跑 dumpsys 即可判定不支持。
     *
     * 实测对照：红米 K40（SDK 33）不支持；荣耀 Magic7 Pro（SDK 37）支持。
     */
    @Test
    fun minSupportedSdkIsAndroid14() {
        assertEquals(34, AppOrientationCompat.MIN_SUPPORTED_SDK)
    }
}
