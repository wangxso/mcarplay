package com.mcarplay.app.media

import android.content.ComponentName
import android.content.Context
import com.mcarplay.app.shizuku.Shell
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 通知使用权的查询与授予（音乐卡数据源的前置条件）。
 *
 * **为什么单独成一个对象**：此前授权只在 `MainActivity.onCreate` 里「Shizuku 已就绪」
 * 时才尝试一次。一旦那次时机不对——手机重启后 Shizuku 未运行、或者改包名后首次启动
 * 还没授权 Shizuku——授权就永远不会再补，用户看到的是「音乐在播但卡片显示媒体信息
 * 未连接」（2026-10-18 改包名后实测踩到）。现在改成**幂等、可重试**：
 * 任何 Shizuku 就绪的时机都可以调用 [ensureGranted]，已授权直接跳过（不跑 shell）。
 */
object NotificationAccess {
    /** 进程内上一次确认到的授权状态（服务 onListenerConnected 也会更新它）。 */
    @Volatile
    var granted: Boolean = false
        private set

    private val granting = AtomicBoolean(false)

    /** 服务连接回调里调用：系统已认可授权，无需再跑 shell 探测。 */
    internal fun markGranted() {
        granted = true
    }

    /** 本应用监听器组件（`包/类` 形式，`cmd notification allow_listener` 需要的格式）。 */
    fun componentSpec(context: Context): String = ComponentName(context, MediaListenerService::class.java).flattenToString()

    /**
     * 从系统设置串判断本应用是否已在授权列表里。
     *
     * 系统把多个监听器用 `:` 拼成一个字符串（`包/类:包/类:…`），
     * **必须按条目精确比对**——用 `contains(包名)` 会误判（别的应用名恰好包含同一前缀时）。
     */
    internal fun isGrantedIn(
        enabledListeners: String?,
        componentSpec: String,
    ): Boolean =
        enabledListeners
            ?.split(':')
            ?.any { it.trim().equals(componentSpec, ignoreCase = true) } == true

    /**
     * 确保已授予通知使用权（幂等、可重试；**后台线程调用**，内部含 shell 往返）。
     *
     * @return true = 已授权（原本就有，或本次授予成功）
     */
    fun ensureGranted(
        context: Context,
        log: (String) -> Unit,
    ): Boolean {
        val spec = componentSpec(context)
        val current =
            Shell
                .exec("settings get secure enabled_notification_listeners", timeoutMs = 4_000)
                .output
        if (isGrantedIn(current, spec)) {
            granted = true
            // 「在授权列表里」不等于「系统已把监听器绑上」：重装 APK 后列表项还在，
            // 但通知服务侧的绑定可能没建立（2026-10-19 实测：Live listeners 里没有
            // 本组件，metadata 恒为 null，卡片一直显示「无法读取播放信息」；
            // 手动 disallow+allow 一次立刻恢复）。这里做一次自愈重绑。
            rebindIfNotConnected(context, spec, log)
            return true
        }
        if (!granting.compareAndSet(false, true)) return granted
        try {
            val result = Shell.exec("cmd notification allow_listener $spec", timeoutMs = 4_000)
            // 命令 exitCode 不能作为判据（个别 ROM 成功也返回非 0）；
            // 而**立即**复核同样不可靠：settings 的写入有延迟，实测命令成功后
            // 立刻读仍是旧值（2026-10-18 真机：cmd 结果=0 但复核判为未授予）。
            // 因此按「短重试」确认，全部失败才判定失败。
            var ok = false
            for (attempt in 0 until 4) {
                if (attempt > 0) Thread.sleep(250)
                val after =
                    Shell
                        .exec("settings get secure enabled_notification_listeners", timeoutMs = 4_000)
                        .output
                if (isGrantedIn(after, spec)) {
                    ok = true
                    break
                }
            }
            granted = ok
            log(
                if (ok) {
                    "MEDIA: 通知使用权已授予（音乐卡可显示歌名/播放状态）"
                } else {
                    "MEDIA: 通知使用权未授予（cmd 结果=${result.exitCode}）——" +
                        "音乐卡只显示播放状态、无歌名；可在系统设置「通知使用权」手动开启"
                },
            )
            return ok
        } finally {
            granting.set(false)
        }
    }

    /**
     * 已授权但监听器未真正连上时，用 disallow + allow 触发系统重新绑定。
     *
     * 判据取自 `dumpsys notification` 的 `Live notification listeners`：只有出现在
     * 该段里，[MediaListenerService.onListenerConnected] 才会被调用、`metadata` 才可读。
     * 这一步只在「已离开 App 很久/重装后」偶发，代价是一次 shell 往返，可接受。
     */
    private fun rebindIfNotConnected(
        context: Context,
        spec: String,
        log: (String) -> Unit,
    ) {
        // 服务已连上（onListenerConnected 置位）就无需探测，避免每次会话建立都跑 dumpsys。
        if (serviceConnected) return
        if (!rebinding.compareAndSet(false, true)) return
        try {
            val live = Shell.exec("dumpsys notification --noredact", timeoutMs = 6_000).output
            if (isLiveIn(live, spec)) {
                serviceConnected = true
                return
            }
            log("MEDIA: 通知使用权在列表里但监听器未绑定——触发一次重绑")
            Shell.exec("cmd notification disallow_listener $spec", timeoutMs = 4_000)
            Thread.sleep(300)
            Shell.exec("cmd notification allow_listener $spec", timeoutMs = 4_000)
            // 绑定是异步的：短轮询确认，避免下游继续拿着「已授权但读不到」的状态
            for (attempt in 0 until 6) {
                Thread.sleep(400)
                val after = Shell.exec("dumpsys notification --noredact", timeoutMs = 6_000).output
                if (isLiveIn(after, spec)) {
                    serviceConnected = true
                    log("MEDIA: 监听器已重新绑定（音乐卡元数据恢复）")
                    return
                }
            }
            log("MEDIA: ⚠ 重绑后监听器仍未连上——音乐卡可能只显示播放状态")
        } catch (_: Exception) {
            // shell 受限/超时：不影响主流程，下次会话再试
        } finally {
            rebinding.set(false)
        }
    }

    /**
     * 从 `dumpsys notification` 输出判断本组件是否在 Live listeners 段里。
     * 只看该段是必要的——「All notification listeners」里可能仍有本组件（授权仍在），
     * 但真正决定能否读到 metadata 的是 Live 段。
     */
    internal fun isLiveIn(
        dump: String,
        componentSpec: String,
    ): Boolean {
        val start = dump.indexOf("Live notification listeners")
        if (start < 0) return false
        val section = dump.substring(start).substringBefore("Snoozed notification listeners")
        return section.contains(componentSpec, ignoreCase = true)
    }

    /** 服务连上时置位（[MediaListenerService.onListenerConnected] 调用）。 */
    @Volatile
    private var serviceConnected: Boolean = false

    internal fun markConnected() {
        serviceConnected = true
    }

    internal fun markDisconnected() {
        serviceConnected = false
    }

    private val rebinding = AtomicBoolean(false)
}
