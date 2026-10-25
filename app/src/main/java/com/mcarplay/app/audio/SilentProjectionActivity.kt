package com.mcarplay.app.audio

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import com.mcarplay.app.shizuku.Shell
import com.mcarplay.app.util.DebugFileLog
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 静默领取 MediaProjection 令牌的**透明跳板**（自动连接专用）。
 *
 * 为什么需要它：AudioPlaybackCapture 必须有 MediaProjection 实例，而 Android 14+ 的令牌
 * 是**单次**的（停止捕获即作废），自动连接时没人能点「允许投屏」。
 *
 * 两条系统闸门与对策：
 *  1. **授权弹窗**：`appops set <pkg> PROJECT_MEDIA allow` 会让 SystemUI 的
 *     `MediaProjectionPermissionActivity` 直接返回 RESULT_OK（Tasker 官方文档同款做法）；
 *  2. **后台启动 Activity（BAL）**：由 Shizuku 执行
 *     `am start -n <pkg>/<activity> --activity-no-animation` 拉起本页——shell 持有
 *     `START_ACTIVITIES_FROM_BACKGROUND`，不受应用侧 BAL 限制；失败再退回
 *     `context.startActivity`（需要 `SYSTEM_ALERT_WINDOW`，缺失时先 `appops` 补授）。
 *
 * 页面本身：1×1、不可触摸、不可聚焦、无动画、不进最近任务；拿到结果立即 finish。
 *
 * **用完即弃**：令牌由 `CarPlayService` 立刻换成 MediaProjection 实例；本页不持有任何状态。
 */
class SilentProjectionActivity : Activity() {
    /** 授权结果（resultCode + 换 MediaProjection 所需的 Intent）。 */
    data class Grant(
        val resultCode: Int,
        val data: Intent,
    )

    private fun complete(
        grant: Grant?,
        error: String?,
    ) {
        val future = pending.getAndSet(null) ?: return
        if (grant != null) {
            future.complete(grant)
        } else {
            future.completeExceptionally(IllegalStateException(error ?: "未知原因"))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 手机在口袋里（锁屏 + 息屏）时，后台 Activity 不会被 resume，onActivityResult 会一直
        // 挂着；这两个标志让本页在锁屏上也能立刻走完（配合暗幕/真息屏，用户无感）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        // 1×1、不可触摸、不可聚焦：用户完全看不到
        window.setLayout(1, 1)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        )
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CODE)
        } catch (t: Throwable) {
            complete(null, "createScreenCaptureIntent 失败：$t")
            finishSilently()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CODE) return
        if (resultCode == RESULT_OK && data != null) {
            complete(Grant(resultCode, data), null)
        } else {
            complete(null, "授权被拒 resultCode=$resultCode")
        }
        finishSilently()
    }

    override fun onDestroy() {
        // 没拿到结果就被销毁（用户取消/被系统回收）：让等待方立刻失败，而不是等满超时
        complete(null, "Activity 在返回结果前被销毁")
        super.onDestroy()
    }

    private fun finishSilently() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val REQUEST_CODE = 10086

        /** 同一时刻只允许一个请求（自动连接是串行的）。 */
        private val pending = AtomicReference<CompletableFuture<Grant>?>(null)

        /**
         * 阻塞领取令牌（**必须在后台线程调用**，内部会走 2~3 条 Shizuku 命令）。
         *
         * @return null = 失败/超时：调用方应退回「只推视频」
         */
        fun request(
            context: Context,
            timeoutMs: Long = 8_000L,
        ): Grant? {
            val app = context.applicationContext
            val future = CompletableFuture<Grant>()
            pending.getAndSet(future)?.completeExceptionally(IllegalStateException("被新的请求覆盖"))
            val pkg = app.packageName
            // 1) 放行投屏：SystemUI 见到 PROJECT_MEDIA=allow 会直接返回 RESULT_OK，不弹窗
            Shell.exec("appops set $pkg PROJECT_MEDIA allow", 5_000L)
            // 2) 没有悬浮窗权限时，后台拉起 Activity / 前台服务都会被拦；暗幕本来也需要它
            if (!Settings.canDrawOverlays(app)) {
                val r = Shell.exec("appops set $pkg SYSTEM_ALERT_WINDOW allow", 5_000L)
                DebugFileLog.append(app, "PROJ: 补授「显示在其他应用上层」ok=${r.ok}（${r.output.take(60)}）")
            }
            // 3) 录音权限（AudioPlaybackCapture 需要）：没授过就顺手补上
            if (app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                val r = Shell.exec("appops set $pkg RECORD_AUDIO allow", 5_000L)
                DebugFileLog.append(app, "PROJ: 补授录音权限 ok=${r.ok}（${r.output.take(60)}）")
            }
            // 4) 用 shell am start 绕过后台启动 Activity 限制；失败再退回应用内启动
            val component = "$pkg/${SilentProjectionActivity::class.java.name}"
            val viaShell = Shell.exec("am start -n $component --activity-no-animation", 8_000L)
            if (!viaShell.ok) {
                DebugFileLog.append(
                    app,
                    "PROJ: am start 失败（exit=${viaShell.exitCode} ${viaShell.output.take(80)}）——改用应用内 startActivity",
                )
                runCatching {
                    app.startActivity(
                        Intent(app, SilentProjectionActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                    )
                }.onFailure {
                    DebugFileLog.append(app, "PROJ: 应用内 startActivity 也失败（${it.javaClass.simpleName}: ${it.message}）")
                }
            }
            return try {
                future.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (t: Throwable) {
                DebugFileLog.append(app, "PROJ: 静默领取投屏令牌失败（${t.javaClass.simpleName}: ${t.message}）")
                null
            } finally {
                pending.compareAndSet(future, null)
            }
        }
    }
}
