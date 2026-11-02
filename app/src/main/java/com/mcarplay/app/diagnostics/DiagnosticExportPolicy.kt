package com.mcarplay.app.diagnostics

import com.mcarplay.app.BuildConfig

/** 明文诊断只存在于 remote/debug；不能用运行时偏好开启日常版或 release 的明文导出。 */
object DiagnosticExportPolicy {
    val unredacted: Boolean get() = allowsUnredacted(BuildConfig.FLAVOR, BuildConfig.BUILD_TYPE, BuildConfig.DEBUG)

    internal fun allowsUnredacted(
        flavor: String,
        buildType: String,
        debug: Boolean,
    ): Boolean = flavor == "remote" && buildType == "debug" && debug

    fun storedDetail(text: String): String = if (unredacted) text else DiagnosticRedactor.scrubAtSource(text)

    fun rawEvent(
        stage: String,
        detail: String,
    ) {
        if (unredacted) ConnectionDiagnostics.runtimeLog("REMOTE-RAW stage=$stage $detail")
    }

    /** 经已有后台写入队列和调试桥记录原始异常，避免在 UI/媒体线程写盘。 */
    fun exception(
        stage: String,
        error: Throwable,
    ) {
        if (!unredacted) return
        ConnectionDiagnostics.runtimeLog("REMOTE-RAW stage=$stage\n${error.stackTraceToString().take(24_000)}")
    }
}
