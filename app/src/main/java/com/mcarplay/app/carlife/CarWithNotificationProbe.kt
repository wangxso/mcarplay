package com.mcarplay.app.carlife

import android.app.Notification
import android.os.SystemClock
import android.service.notification.StatusBarNotification

/** 通知监听器是否真的连着；授权列表里存在本组件不等于系统已绑定。 */
enum class NotificationProbeState {
    UNKNOWN,
    CONNECTED,
    DISCONNECTED,
}

/**
 * 只保留 CarWith 通知的存在性和前台服务标志。标题、正文、进度、tag 等私有内容
 * 不进入快照，也不写日志；用于区分「没有观察到 CarWith 启动」与「CarWith 服务已启动但七口未就绪」。
 */
data class CarWithNotificationSnapshot(
    val probeState: NotificationProbeState = NotificationProbeState.UNKNOWN,
    val postEvents: Long = 0L,
    val foregroundPostEvents: Long = 0L,
    val removalEvents: Long = 0L,
    val activeNotifications: Int = 0,
    val activeForegroundNotifications: Int = 0,
    val everObserved: Boolean = false,
    val everForegroundObserved: Boolean = false,
    val firstSeenElapsedMs: Long? = null,
    val lastSeenElapsedMs: Long? = null,
) {
    val anyObserved: Boolean get() = everObserved || activeNotifications > 0

    val foregroundObserved: Boolean get() = everForegroundObserved || activeForegroundNotifications > 0

    fun deltaSince(baseline: CarWithNotificationSnapshot?): CarWithNotificationDelta =
        CarWithNotificationDelta(
            postEvents = (postEvents - (baseline?.postEvents ?: 0L)).coerceAtLeast(0L),
            foregroundPostEvents = (foregroundPostEvents - (baseline?.foregroundPostEvents ?: 0L)).coerceAtLeast(0L),
        )

    fun diagnosticSummary(
        nowElapsedMs: Long,
        baseline: CarWithNotificationSnapshot? = null,
    ): String {
        val delta = deltaSince(baseline)
        val active = if (probeState == NotificationProbeState.CONNECTED) activeNotifications.toString() else "unknown"
        val foregroundActive = if (probeState == NotificationProbeState.CONNECTED) activeForegroundNotifications.toString() else "unknown"
        val firstAge = firstSeenElapsedMs?.let { (nowElapsedMs - it).coerceAtLeast(0L).toString() } ?: "none"
        val lastAge = lastSeenElapsedMs?.let { (nowElapsedMs - it).coerceAtLeast(0L).toString() } ?: "none"
        val baselineKind = if (baseline == null) "none" else "run"
        return "access=$probeState posts=$postEvents foregroundPosts=$foregroundPostEvents " +
            "active=$active foregroundActive=$foregroundActive removed=$removalEvents " +
            "firstSeenAgoMs=$firstAge lastSeenAgoMs=$lastAge " +
            "baseline=$baselineKind deltaPosts=${delta.postEvents} deltaForegroundPosts=${delta.foregroundPostEvents}"
    }
}

data class CarWithNotificationDelta(
    val postEvents: Long,
    val foregroundPostEvents: Long,
)

internal data class CarWithNotificationEntry(
    val key: String,
    val foreground: Boolean,
)

/** 纯状态机，Android 服务只负责把通知事件转成固定字段。 */
internal class CarWithNotificationTracker(
    private val packageName: String = CarLifeLauncher.CARWITH_PACKAGE,
    private val nowElapsedMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val lock = Any()
    private val active = LinkedHashMap<String, Boolean>()
    private var probeState = NotificationProbeState.UNKNOWN
    private var postEvents = 0L
    private var foregroundPostEvents = 0L
    private var removalEvents = 0L
    private var everObserved = false
    private var everForegroundObserved = false
    private var firstSeenElapsedMs: Long? = null
    private var lastSeenElapsedMs: Long? = null

    fun onListenerConnected(current: List<CarWithNotificationEntry>) =
        synchronized(lock) {
            probeState = NotificationProbeState.CONNECTED
            active.clear()
            current.forEach { active[it.key] = it.foreground }
            if (active.isNotEmpty()) {
                val now = nowElapsedMs()
                everObserved = true
                everForegroundObserved = everForegroundObserved || active.any { it.value }
                firstSeenElapsedMs = firstSeenElapsedMs ?: now
                lastSeenElapsedMs = now
            }
        }

    fun onListenerDisconnected() =
        synchronized(lock) {
            probeState = NotificationProbeState.DISCONNECTED
            active.clear()
        }

    fun onPosted(
        key: String,
        packageName: String,
        foreground: Boolean,
    ) = synchronized(lock) {
        if (packageName != this.packageName) return@synchronized
        val now = nowElapsedMs()
        postEvents++
        if (foreground) foregroundPostEvents++
        everObserved = true
        everForegroundObserved = everForegroundObserved || foreground
        active[key] = foreground
        firstSeenElapsedMs = firstSeenElapsedMs ?: now
        lastSeenElapsedMs = now
    }

    fun onRemoved(key: String) =
        synchronized(lock) {
            if (active.remove(key) != null) removalEvents++
        }

    fun snapshot(): CarWithNotificationSnapshot =
        synchronized(lock) {
            CarWithNotificationSnapshot(
                probeState = probeState,
                postEvents = postEvents,
                foregroundPostEvents = foregroundPostEvents,
                removalEvents = removalEvents,
                activeNotifications = active.size,
                activeForegroundNotifications = active.count { it.value },
                everObserved = everObserved,
                everForegroundObserved = everForegroundObserved,
                firstSeenElapsedMs = firstSeenElapsedMs,
                lastSeenElapsedMs = lastSeenElapsedMs,
            )
        }
}

/**
 * 进程内唯一的 CarWith 通知探针。它复用媒体卡已有的通知使用权，
 * 不新增权限，也不读取通知文字；没有绑定通知监听器时明确返回 UNKNOWN。
 */
object CarWithNotificationProbe {
    private val tracker = CarWithNotificationTracker()

    fun onListenerConnected(notifications: Array<StatusBarNotification>?) {
        tracker.onListenerConnected(
            notifications.orEmpty().mapNotNull { notification ->
                if (notification.packageName != CarLifeLauncher.CARWITH_PACKAGE) {
                    null
                } else {
                    CarWithNotificationEntry(
                        key = notification.key,
                        foreground = notification.isForegroundService(),
                    )
                }
            },
        )
    }

    fun onListenerDisconnected() = tracker.onListenerDisconnected()

    fun onNotificationPosted(notification: StatusBarNotification?) {
        notification ?: return
        tracker.onPosted(
            key = notification.key,
            packageName = notification.packageName,
            foreground = notification.isForegroundService(),
        )
    }

    fun onNotificationRemoved(notification: StatusBarNotification?) {
        notification ?: return
        if (notification.packageName == CarLifeLauncher.CARWITH_PACKAGE) tracker.onRemoved(notification.key)
    }

    fun snapshot(): CarWithNotificationSnapshot = tracker.snapshot()

    fun diagnosticSummary(baseline: CarWithNotificationSnapshot? = null): String =
        snapshot().diagnosticSummary(SystemClock.elapsedRealtime(), baseline)

    private fun StatusBarNotification.isForegroundService(): Boolean = notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0
}
