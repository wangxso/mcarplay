package com.mcarplay.app.ui.car

import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.os.Binder
import android.view.KeyEvent
import android.view.Surface
import com.mcarplay.app.session.EventCommandRouter
import com.mcarplay.app.shizuku.CarInputClient
import com.mcarplay.app.shizuku.ICarInput
import com.mcarplay.app.shizuku.Shell
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A car UI display feeding the encoder and an app display feeding the UI's TextureView. */
internal class DashboardProjection(
    private val context: Context,
    val width: Int,
    val height: Int,
    private val log: (String) -> Unit,
) : AutoCloseable,
    EventCommandRouter.TouchTarget {
    private val dpi = (minOf(width, height) * 160 / 440).coerceIn(96, 640)
    private val closed = AtomicBoolean(false)
    private val worker =
        ScheduledThreadPoolExecutor(1) { task -> Thread(task, "car-dashboard-apps").apply { isDaemon = true } }.apply {
            setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
        }
    private val displayLock = Any()
    private val displayOwner = Binder()
    private val dashboardBounds = Rect(0, 0, width, height)
    private var input: ICarInput? = null
    private var carDisplay = -1
    private var pointerTarget: TouchTarget? = null
    private var pointerX = 0
    private var pointerY = 0
    private var backProbe: BackProbe? = null

    @Volatile
    private var appDisplay: AppDisplay? = null

    @Volatile
    private var appInputBounds: Rect? = null

    private data class TouchTarget(
        val id: Int,
        val bounds: Rect,
        val width: Int,
        val height: Int,
    )

    private data class BackProbe(
        val display: AppDisplay,
        val onEmpty: () -> Unit,
        val remaining: Int = 2,
    )

    private data class AppDisplay(
        val controller: ICarInput,
        val id: Int,
        val texture: SurfaceTexture,
        val surface: Surface,
        val width: Int,
        val height: Int,
    ) {
        fun release() {
            runCatching { controller.releaseDisplay(id) }
            surface.release()
        }
    }

    // Called off the main thread, once for each encoder surface in this CarPlay session.
    fun attach(surface: Surface) {
        val displayId =
            synchronized(displayLock) {
                check(!closed.get()) { "Dashboard already closed" }
                val controller = input ?: checkNotNull(CarInputClient.awaitReady(context, 2_500L)) { "Shizuku input service unavailable" }
                input = controller
                if (carDisplay > 0) {
                    check(controller.setDisplaySurface(carDisplay, surface)) { "Could not reattach the dashboard display" }
                    return
                }
                carDisplay = controller.createDisplay("CarPlayDashboard", width, height, dpi, surface, true, true, displayOwner)
                check(carDisplay > 0) { "Could not create the dashboard display" }
                carDisplay
            }
        check(launch(ComponentName(context, CarDashboardActivity::class.java), displayId)) { "Could not start the dashboard activity" }
        log("DASHBOARD: 显示就绪 ${width}x$height @$dpi dpi, display=$displayId")
    }

    fun ownsDisplay(displayId: Int): Boolean = synchronized(displayLock) { !closed.get() && carDisplay > 0 && carDisplay == displayId }

    fun displayIds(): Set<Int> = synchronized(displayLock) { listOfNotNull(carDisplay.takeIf { it > 0 }, appDisplay?.id).toSet() }

    fun setAppInputBounds(bounds: Rect?) {
        appInputBounds = bounds
    }

    fun detachEncoder(): Boolean =
        synchronized(displayLock) {
            val controller = input ?: return@synchronized false
            if (closed.get() || carDisplay <= 0) return@synchronized false
            pointerTarget?.let {
                runCatching { controller.pointerUp(it.id, pointerX, pointerY) }
                pointerTarget = null
            }
            runCatching { controller.setDisplaySurface(carDisplay, null) }.getOrDefault(false)
        }

    override val videoWidth: Int get() = width
    override val videoHeight: Int get() = height

    override fun sendTouch(
        x: Int,
        y: Int,
        down: Boolean,
    ): Boolean =
        synchronized(displayLock) {
            val controller = input ?: return@synchronized false
            if (closed.get() || carDisplay <= 0) return@synchronized false
            val current = pointerTarget
            if (!down && current == null) return@synchronized false
            val target = current ?: touchTarget(x, y)
            pointerX = ((x - target.bounds.left) * target.width / target.bounds.width()).coerceIn(0, target.width - 1)
            pointerY = ((y - target.bounds.top) * target.height / target.bounds.height()).coerceIn(0, target.height - 1)
            val written = runCatching { sendTouch(controller, target.id, pointerX, pointerY, down, current != null) }.getOrDefault(false)
            pointerTarget = if (down && (written || current != null)) target else null
            written
        }

    // Send map and keyboard touches straight to their display to preserve the active editor's focus.
    private fun touchTarget(
        x: Int,
        y: Int,
    ): TouchTarget {
        val bounds = appInputBounds
        val app = appDisplay
        if (bounds != null && app != null && bounds.contains(x, y)) return TouchTarget(app.id, bounds, app.width, app.height)
        return TouchTarget(carDisplay, dashboardBounds, width, height)
    }

    fun key(keyCode: Int): Boolean =
        synchronized(displayLock) {
            if (closed.get() || carDisplay <= 0) return@synchronized false
            val app = if (keyCode != KeyEvent.KEYCODE_BACK && appInputBounds != null) appDisplay else null
            runCatching {
                input?.keyEvent(app?.id ?: carDisplay, keyCode) == true
            }.getOrDefault(false)
        }

    fun openApp(
        component: ComponentName,
        texture: SurfaceTexture,
        width: Int,
        height: Int,
        reuseVisibleApp: Boolean,
        verifyMap: Boolean,
        completed: (Boolean) -> Unit,
    ) {
        fun finish(success: Boolean) {
            if (!success && !closed.get()) log("DASHBOARD: 应用打开失败 ${component.packageName}")
            completed(success)
        }
        if (!enqueue {
                val previousDisplay = appDisplay?.id
                val display =
                    runCatching { ensureAppDisplay(texture, width, height) }.getOrNull()
                if (display == null || closed.get()) return@enqueue finish(false)
                if (reuseVisibleApp && previousDisplay == display.id && hasVisibleApp(display, component.packageName) != false) {
                    return@enqueue finish(true)
                }
                if (!runCatching { launchApp(component, display.id) }.getOrDefault(false)) return@enqueue finish(false)
                if (verifyMap) {
                    // A successful am start can still be followed by the map immediately exiting.
                    checkAfterMapLaunch(display, component.packageName, ::finish)
                } else {
                    finish(true)
                }
            }
        ) {
            finish(false)
        }
    }

    private fun checkAfterMapLaunch(
        display: AppDisplay,
        packageName: String,
        completed: (Boolean) -> Unit,
        remaining: Int = 3,
    ) {
        try {
            worker.schedule({
                if (closed.get() || appDisplay?.id != display.id) {
                    completed(false)
                } else if (hasVisibleApp(display, packageName) != false) {
                    completed(true)
                } else if (remaining > 1) {
                    checkAfterMapLaunch(display, packageName, completed, remaining - 1)
                } else {
                    completed(false)
                }
            }, 750L, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            completed(false)
        }
    }

    // SurfaceTexture destruction is serialized behind any in-flight launch using that texture.
    fun releaseTexture(texture: SurfaceTexture) {
        if (!enqueue {
                synchronized(displayLock) {
                    appDisplay?.takeIf { it.texture === texture }?.let { display ->
                        appDisplay = null
                        display.release()
                    }
                }
                texture.release()
            }
        ) {
            texture.release()
        }
    }

    fun appBack(onEmpty: () -> Unit) {
        enqueue {
            if (closed.get() || backProbe != null) return@enqueue
            val display = appDisplay ?: return@enqueue onEmpty()
            if (hasVisibleApp(display) == false) return@enqueue returnToDashboardHome(onEmpty)
            backProbe = BackProbe(display, onEmpty)
            runCatching { display.controller.keyEvent(display.id, KeyEvent.KEYCODE_BACK) }
            scheduleBackProbe()
        }
    }

    private fun scheduleBackProbe() {
        try {
            worker.schedule(::checkAfterAppBack, 300, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            backProbe = null
        }
    }

    private fun checkAfterAppBack() {
        val probe = backProbe ?: return
        backProbe = null
        if (closed.get() || appDisplay !== probe.display) return
        if (hasVisibleApp(probe.display) == false) {
            returnToDashboardHome(probe.onEmpty)
        } else if (probe.remaining > 1) {
            backProbe = probe.copy(remaining = probe.remaining - 1)
            scheduleBackProbe()
        }
    }

    private fun hasVisibleApp(
        display: AppDisplay,
        packageName: String? = null,
    ): Boolean? {
        val snapshot = Shell.exec("cmd activity stack list", timeoutMs = 800L)
        return if (snapshot.ok) hasVisibleDashboardTask(snapshot.output, display.id, packageName) else null
    }

    private fun returnToDashboardHome(onEmpty: () -> Unit) {
        log("DASHBOARD: 应用已退出，返回自绘首页")
        onEmpty()
    }

    private fun ensureAppDisplay(
        texture: SurfaceTexture,
        width: Int,
        height: Int,
    ): AppDisplay =
        synchronized(displayLock) {
            check(!closed.get()) { "Dashboard already closed" }
            val previous = appDisplay
            texture.setDefaultBufferSize(width, height)
            if (previous?.texture === texture && previous.width == width && previous.height == height) return previous
            if (previous?.texture === texture && previous.controller.resizeDisplay(previous.id, width, height, dpi)) {
                return previous.copy(width = width, height = height).also { appDisplay = it }
            }
            appDisplay = null
            previous?.release()
            val controller = checkNotNull(input)
            val surface = Surface(texture)
            try {
                val id = controller.createDisplay("CarPlayDashboardApp", width, height, dpi, surface, true, false, displayOwner)
                check(id > 0) { "Could not create the app display" }
                return AppDisplay(controller, id, texture, surface, width, height).also { appDisplay = it }
            } catch (error: Exception) {
                surface.release()
                throw error
            }
        }

    private fun launch(
        component: ComponentName,
        displayId: Int,
    ): Boolean {
        val name = component.flattenToString()
        require(name.matches(Regex("[a-zA-Z0-9_.$/]+"))) { "Invalid Android component" }
        return startActivity("am start --display $displayId -n '$name'")
    }

    private fun launchApp(
        component: ComponentName,
        displayId: Int,
    ): Boolean {
        val carEntry =
            when (component.packageName) {
                "com.autonavi.minimap" -> "com.autonavi.minimap/com.autonavi.map.activity.UcarSplashActivity"
                "com.baidu.BaiduMap" -> "com.baidu.BaiduMap/com.baidu.hicar.HiCarWelcomeScreen"
                else -> null
            }
        if (carEntry != null) {
            val startsCarMode =
                startActivity(
                    "am start --display $displayId --activity-single-top " +
                        "-n $carEntry " +
                        "-a com.ucar.intent.action.UCAR -c com.ucar.intent.category.UCAR " +
                        "--ez isUcarMode true --ei displayId $displayId --ei screenMode 1 " +
                        "--ez isUCarNaviUseVerticalScreenLayout false",
                )
            if (startsCarMode) {
                log("DASHBOARD: 已启动车载横屏入口 ${component.packageName}")
                return true
            }
            log("DASHBOARD: 车载入口不可用，尝试普通地图入口 ${component.packageName}")
        }
        return launch(component, displayId)
    }

    private fun startActivity(command: String): Boolean {
        if (closed.get()) return false
        val result = Shell.exec("$command 2>&1")
        return result.ok && result.output.lineSequence().none { it.trimStart().startsWith("Error", ignoreCase = true) }
    }

    private fun enqueue(block: () -> Unit): Boolean =
        try {
            worker.execute(block)
            true
        } catch (_: RejectedExecutionException) {
            false
        }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(displayLock) {
            val app = appDisplay
            appDisplay = null
            appInputBounds = null
            app?.release()
            if (carDisplay > 0) runCatching { input?.releaseDisplay(carDisplay) }
            carDisplay = -1
            input = null
            pointerTarget = null
        }
        worker.shutdown()
    }

    private fun sendTouch(
        controller: ICarInput,
        id: Int,
        x: Int,
        y: Int,
        down: Boolean,
        wasDown: Boolean,
    ): Boolean =
        when {
            down && !wasDown -> controller.pointerDown(id, x, y)
            down -> controller.pointerMove(id, x, y)
            wasDown -> controller.pointerUp(id, x, y)
            else -> false
        }
}

private val rootDisplay = Regex("\\bdisplayId=(\\d+)\\b")
private val taskVisibility = Regex("\\bvisible=(true|false)\\b")

private val topActivityPackage = Regex("""\btopActivity=ComponentInfo\{([\w.]+)/""")
private val taskPackage = Regex("""^taskId=\d+:\s+([\w.]+)/""")

/** Null means the system did not return a task list we can safely interpret. */
internal fun hasVisibleDashboardTask(
    snapshot: String,
    displayId: Int,
    packageName: String? = null,
): Boolean? {
    var hasRootTask = false
    var onAppDisplay = false
    var unknownTask = false
    for (line in snapshot.lineSequence().map(String::trimStart)) {
        if (line.startsWith("RootTask id=")) {
            val id =
                rootDisplay
                    .find(line)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull() ?: return null
            hasRootTask = true
            onAppDisplay = id == displayId
        } else if (onAppDisplay && line.startsWith("taskId=")) {
            when (taskVisibility.find(line)?.groupValues?.get(1)) {
                "true" -> {
                    if (packageName == null) return true
                    val visiblePackage = topActivityPackage.find(line) ?: taskPackage.find(line)
                    if (visiblePackage?.groupValues?.get(1) == packageName) return true
                    if (visiblePackage == null) unknownTask = true
                }

                "false" -> Unit
                else -> unknownTask = true
            }
        }
    }
    return if (hasRootTask && !unknownTask) false else null
}
