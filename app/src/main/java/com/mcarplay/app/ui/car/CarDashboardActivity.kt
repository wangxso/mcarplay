package com.mcarplay.app.ui.car

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.TextureView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.doOnAttach
import com.mcarplay.app.R
import com.mcarplay.app.SessionController
import com.mcarplay.app.i18n.AppLanguage
import com.mcarplay.app.media.MediaSessionsState
import com.mcarplay.app.settings.DashboardMaps
import com.mcarplay.app.util.Bg

/** Our own controls stay on the encoder display, including while a map occupies the app area. */
class CarDashboardActivity :
    Activity(),
    TextureView.SurfaceTextureListener {
    private lateinit var projection: DashboardProjection
    private lateinit var appView: TextureView
    private lateinit var homeView: View
    private lateinit var appDrawer: View
    private lateinit var mapButton: ImageButton
    private lateinit var backButton: ImageButton
    private lateinit var dockPlay: ImageButton
    private lateinit var musicPlay: ImageButton
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var artwork: ImageView
    private lateinit var musicProgress: ProgressBar
    private lateinit var elapsedTime: TextView
    private lateinit var totalTime: TextView
    private lateinit var networkStatus: LinearLayout
    private lateinit var batteryIndicator: LinearLayout
    private lateinit var batteryStatusIcon: ImageView
    private lateinit var batteryStatus: TextView
    private var lastPhoneStatus: DashboardStatus? = null
    private var simStatuses = emptyList<DashboardSimSignal>()
    private val simSignals by lazy {
        DashboardSimSignals(applicationContext) { statuses ->
            if (simStatuses != statuses) {
                simStatuses = statuses
                lastPhoneStatus?.let(::renderNetworkStatus)
            }
        }
    }
    private var palette = DashboardPalette.LIGHT
    private val themeUpdates = mutableListOf<() -> Unit>()
    private var launchGeneration = 0
    private var pendingApp: ComponentName? = null
    private var foregroundApp: ComponentName? = null
    private lateinit var installedMaps: List<Pair<Int, ComponentName>>
    private var homeMap: ComponentName? = null
    private var panelDialog: AlertDialog? = null
    private val statusHandler = Handler(Looper.getMainLooper())
    private val refreshStatus =
        object : Runnable {
            override fun run() {
                val state = DashboardStatus.read(applicationContext)
                if (state != lastPhoneStatus) {
                    lastPhoneStatus = state
                    renderStatus(state)
                }
                statusHandler.postDelayed(this, 5_000L)
            }
        }
    private val refreshProgress =
        object : Runnable {
            override fun run() {
                renderProgress()
                statusHandler.postDelayed(this, 1_000L)
            }
        }
    private val refreshMedia: () -> Unit = { runOnUiThread { if (!isDestroyed) renderMedia() } }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(if (Build.VERSION.SDK_INT >= 33) newBase else AppLanguage.localized(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = SessionController.dashboardFor(display?.displayId ?: -1)
        if (source == null) {
            finish()
            return
        }
        projection = source
        palette = paletteFor(resources.configuration)
        installedMaps =
            DashboardMaps.apps.mapNotNull { (name, packages) ->
                packages.firstNotNullOfOrNull(::launcher)?.let { name to it }
            }
        val savedPackage = getPreferences(MODE_PRIVATE).getString("home_map", null)
        homeMap =
            installedMaps.firstOrNull { it.second.packageName == savedPackage }?.second
                ?: installedMaps.firstOrNull { it.first == R.string.car_amap }?.second
                ?: installedMaps.firstOrNull()?.second
        val root =
            LinearLayout(this)
                .apply {
                    orientation = LinearLayout.HORIZONTAL
                }.themed {
                    background =
                        GradientDrawable(
                            GradientDrawable.Orientation.TL_BR,
                            palette.backdrop,
                        )
                }
        // A 16:9 dashboard with a 440 dp short edge is about 782 dp wide.
        val showSideMusic = resources.configuration.screenWidthDp >= 780
        val area =
            FrameLayout(this)
                .apply {
                    clipToOutline = true
                }.themed {
                    background = rounded(palette.card, 18)
                }
        appView =
            TextureView(this).apply {
                surfaceTextureListener = this@CarDashboardActivity
                addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateAppInputBounds() }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        area.addView(appView, FrameLayout.LayoutParams(-1, -1))
        homeView = createHome(showSideMusic)
        area.addView(homeView, FrameLayout.LayoutParams(-1, -1))
        val dashboard = LinearLayout(this)
        dashboard.addView(
            area,
            LinearLayout.LayoutParams(0, -1, if (showSideMusic) 0.69f else 1f).apply {
                setMargins(dp(10), dp(10), if (showSideMusic) 0 else dp(10), dp(10))
            },
        )
        if (showSideMusic) {
            val sideMusic =
                FrameLayout(this).apply {
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    addView(createMusic(expanded = true), FrameLayout.LayoutParams(-1, -1))
                }
            dashboard.addView(sideMusic, LinearLayout.LayoutParams(0, -1, 0.31f))
        }
        appDrawer = createAppDrawer().apply { visibility = View.GONE }
        val content =
            FrameLayout(this).apply {
                addView(dashboard, FrameLayout.LayoutParams(-1, -1))
                addView(appDrawer, FrameLayout.LayoutParams(-1, -1).apply { setMargins(dp(10), dp(10), dp(10), dp(10)) })
            }
        root.addView(createDock(showSideMusic), LinearLayout.LayoutParams(dp(96), -1).apply { setMargins(dp(6), dp(10), 0, dp(10)) })
        root.addView(content, LinearLayout.LayoutParams(0, -1, 1f))
        root.doOnAttach { view ->
            view.windowInsetsController?.let {
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                it.hide(WindowInsets.Type.systemBars())
            }
        }
        setContentView(root)
        MediaSessionsState.addListener(refreshMedia)
        renderMedia()
        if (showSideMusic) homeMap?.let(::selectApp)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val next = paletteFor(newConfig)
        if (palette === next) return
        palette = next
        themeUpdates.forEach { it() }
        panelDialog?.dismiss()
        lastPhoneStatus?.let(::renderStatus)
        if (::artwork.isInitialized) renderMedia()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK || !::projection.isInitialized) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
            returnFromApp()
        }
        return true
    }

    private fun createHome(showSideMusic: Boolean): View {
        val content =
            LinearLayout(this)
                .apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(20), dp(16), dp(20), dp(16))
                }.themed {
                    setBackgroundColor(palette.card)
                }
        if (installedMaps.isEmpty()) {
            content.addView(label(getString(R.string.car_no_maps), 20, bold = true))
        } else {
            content.addView(button(getString(R.string.car_choose_map), ::chooseMap))
        }
        if (!showSideMusic) {
            content.addView(createMusic(expanded = false), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        }
        return ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
    }

    private fun createMusic(expanded: Boolean): View {
        val panel = column()
        artwork =
            ImageView(this)
                .apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    clipToOutline = true
                }.themed {
                    background = rounded(palette.control, 12)
                }
        val metadata = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title = label("", 19, bold = true)
        artist = label("", 14, muted = true).apply { if (expanded) maxLines = 1 }
        metadata.addView(title)
        metadata.addView(artist)
        if (expanded) {
            // The footer is measured first, so extra metadata lines reduce the cover area.
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val coverArea = FrameLayout(this)
            coverArea.addView(artwork, FrameLayout.LayoutParams(dp(104), dp(104), Gravity.CENTER))
            coverArea.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                val size = minOf(view.width, view.height)
                val params = artwork.layoutParams
                if (params.width != size) {
                    params.width = size
                    params.height = size
                    artwork.layoutParams = params
                }
            }
            details.addView(coverArea, LinearLayout.LayoutParams(-1, 0, 1f))
            details.addView(metadata, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            panel.addView(details, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            val media = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            media.addView(artwork, LinearLayout.LayoutParams(dp(64), dp(64)))
            media.addView(metadata, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
            panel.addView(media, LinearLayout.LayoutParams(-1, -2))
        }
        musicProgress =
            ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
                .apply {
                    max = 1_000
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }.themed {
                    progressTintList = ColorStateList.valueOf(palette.accent)
                    progressBackgroundTintList = ColorStateList.valueOf(palette.track)
                }
        panel.addView(musicProgress, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(12) })
        elapsedTime = label("", 12, muted = true)
        totalTime = label("", 12, muted = true).apply { gravity = Gravity.END }
        val times = LinearLayout(this)
        times.addView(elapsedTime, LinearLayout.LayoutParams(0, -2, 1f))
        times.addView(totalTime, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(times, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        val actions = LinearLayout(this)
        musicPlay = iconButton(R.string.car_play, R.drawable.ic_play, prominent = true, pressed = ::togglePlayback)
        listOf(
            iconButton(R.string.car_previous, R.drawable.ic_dashboard_previous) { mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) },
            musicPlay,
            iconButton(R.string.car_next, R.drawable.ic_dashboard_next) { mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) },
        ).forEach { icon ->
            actions.addView(icon, LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        }
        panel.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) })
        return panel
    }

    private fun createDock(showSideMusic: Boolean): View =
        LinearLayout(this)
            .apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(7), 0, dp(7), 0)
                addView(
                    TextClock(context)
                        .apply {
                            format12Hour = "h:mm"
                            format24Hour = "HH:mm"
                            textSize = 22f
                            gravity = Gravity.CENTER
                        }.themed {
                            setTextColor(palette.text)
                        },
                    LinearLayout.LayoutParams(-1, -2),
                )
                networkStatus = LinearLayout(context).apply { gravity = Gravity.CENTER }
                addView(networkStatus, LinearLayout.LayoutParams(-1, dp(30)))
                batteryIndicator =
                    LinearLayout(context).apply {
                        gravity = Gravity.CENTER
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    }
                batteryStatusIcon = ImageView(context).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                batteryStatus = label("", 12).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                batteryIndicator.addView(batteryStatusIcon, LinearLayout.LayoutParams(dp(26), dp(24)))
                batteryIndicator.addView(batteryStatus, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4) })
                addView(batteryIndicator, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
                backButton = iconButton(R.string.car_back, R.drawable.ic_chev_left, pressed = ::returnFromApp)
                backButton.isEnabled = false
                dockPlay = iconButton(R.string.car_play, R.drawable.ic_play, pressed = ::togglePlayback)
                mapButton =
                    iconButton(R.string.car_navigation, R.drawable.ic_dashboard_map) { openNavigation(showSideMusic) }
                        .themed { renderMapButton(this) }
                val apps = iconButton(R.string.car_apps, R.drawable.ic_menu_grid, pressed = ::showAppDrawer)
                val settings = iconButton(R.string.car_dashboard_settings, R.drawable.ic_tab_settings, pressed = ::showSettings)
                listOf(mapButton, dockPlay, backButton, settings, apps).forEach {
                    addView(it, LinearLayout.LayoutParams(dp(60), dp(48)).apply { setMargins(0, dp(3), 0, dp(3)) })
                }
            }.themed {
                background = rounded(palette.dock, 20)
            }

    private fun createAppDrawer(): View {
        val apps = column()
        val mapButtons =
            installedMaps.map { (name, component) ->
                button(getString(name)) { selectMap(component) }.apply {
                    isSingleLine = false
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(7), dp(8), dp(7), dp(8))
                    runCatching { packageManager.getActivityIcon(component) }.getOrNull()?.let {
                        it.setBounds(0, 0, dp(42), dp(42))
                        setCompoundDrawables(null, it, null, null)
                        compoundDrawablePadding = dp(5)
                    }
                }
            }
        val actions =
            listOf(
                button(getString(R.string.car_return_to_car)) {
                    if (!SessionController.returnDashboardToCar(projection)) notice(R.string.car_return_to_car_unavailable)
                }.apply {
                    gravity = Gravity.CENTER
                    setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_car, 0, 0)
                    compoundDrawablePadding = dp(4)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                }.themed {
                    compoundDrawableTintList = ColorStateList.valueOf(palette.text)
                },
                button(getString(R.string.car_choose_player), ::choosePlayer),
                button(getString(R.string.car_dashboard_settings), ::showSettings),
            )
        for ((buttons, height) in listOf(mapButtons to 112, actions to 80)) {
            if (buttons.isEmpty()) continue
            val row = LinearLayout(this).apply { isBaselineAligned = false }
            buttons.forEach {
                row.addView(it, LinearLayout.LayoutParams(0, dp(height), 1f).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
            }
            apps.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        return ScrollView(this).apply {
            isFillViewport = true
            addView(apps)
        }
    }

    private fun showAppDrawer() {
        projection.setAppInputBounds(null)
        appDrawer.visibility = View.VISIBLE
    }

    private fun openNavigation(showSideMusic: Boolean) {
        appDrawer.visibility = View.GONE
        if (showSideMusic) {
            homeMap?.takeIf { it != foregroundApp }?.let(::selectApp)
            updateAppInputBounds()
        } else {
            showHome()
        }
    }

    override fun onStart() {
        super.onStart()
        if (::networkStatus.isInitialized) {
            simSignals.start()
            statusHandler.post(refreshStatus)
            statusHandler.post(refreshProgress)
        }
    }

    override fun onStop() {
        statusHandler.removeCallbacks(refreshStatus)
        statusHandler.removeCallbacks(refreshProgress)
        if (::networkStatus.isInitialized) simSignals.stop()
        super.onStop()
    }

    private fun renderStatus(state: DashboardStatus) {
        renderNetworkStatus(state)
        batteryStatusIcon.setImageDrawable(
            batteryIcon(state, if (state.charging) palette.charging else palette.accent, palette.text, palette.dock),
        )
        batteryStatus.text = state.battery?.let { getString(R.string.car_battery_percent, it) }.orEmpty()
        batteryIndicator.contentDescription =
            when {
                state.battery == null -> getString(R.string.car_battery_unknown)
                state.charging -> getString(R.string.car_battery_charging, state.battery)
                else -> getString(R.string.car_battery, state.battery)
            }
    }

    private fun renderNetworkStatus(state: DashboardStatus) {
        networkStatus.removeAllViews()
        if (simStatuses.isEmpty()) {
            val network = getString(state.network.label)
            val statusIcon =
                ImageView(this).apply {
                    setImageDrawable(networkIcon(state.network, state.signal, palette.text, palette.track))
                    contentDescription = state.signal?.let { getString(R.string.car_network_with_signal, network, it) } ?: network
                }
            networkStatus.addView(statusIcon, LinearLayout.LayoutParams(dp(26), dp(24)))
            return
        }
        simStatuses.forEachIndexed { index, sim ->
            val generation = sim.generation
            val badge =
                TextView(this).apply {
                    text = generation ?: getString(R.string.car_status_unknown)
                    textSize = 9f
                    includeFontPadding = false
                    gravity = Gravity.CENTER
                    typeface = Typeface.DEFAULT_BOLD
                    val color = if (index == 0) palette.accent else palette.charging
                    setTextColor(if (generation == null) palette.muted else palette.dock)
                    background = rounded(if (generation == null) palette.control else color, 4)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
            val signal =
                ImageView(this).apply {
                    setImageDrawable(networkIcon(DashboardStatus.Network.CELLULAR, sim.level, palette.text, palette.track))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
            val column =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    val label = getString(R.string.car_sim_network, sim.slot + 1, generation ?: getString(R.string.car_network_cell))
                    contentDescription = sim.level?.let { getString(R.string.car_network_with_signal, label, it) } ?: label
                    addView(badge, LinearLayout.LayoutParams(dp(23), dp(13)))
                    addView(signal, LinearLayout.LayoutParams(dp(22), dp(15)))
                }
            networkStatus.addView(column, LinearLayout.LayoutParams(dp(31), -2))
        }
    }

    private fun chooseMap() {
        if (installedMaps.isEmpty()) {
            notice(R.string.car_no_maps)
            return
        }
        showPanel(R.string.car_choose_map, mapOptions())
    }

    private fun mapOptions(): List<Pair<String, () -> Unit>> =
        installedMaps.map { (name, component) -> getString(name) to { selectMap(component) } }

    private fun selectMap(component: ComponentName) {
        homeMap = component
        renderMapButton(mapButton)
        getPreferences(MODE_PRIVATE).edit().putString("home_map", component.packageName).apply()
        selectApp(component)
    }

    private fun showSettings() {
        showPanel(
            R.string.car_dashboard_settings,
            listOf(getString(R.string.car_choose_map) to ::chooseMap, getString(R.string.car_choose_player) to ::choosePlayer),
        )
    }

    private fun showPanel(
        heading: Int,
        options: List<Pair<String, () -> Unit>>,
    ) {
        panelDialog?.dismiss()
        val dialog =
            AlertDialog
                .Builder(
                    this,
                    if (palette === DashboardPalette.DARK) {
                        android.R.style.Theme_Material_Dialog_Alert
                    } else {
                        android.R.style.Theme_Material_Light_Dialog_Alert
                    },
                ).setTitle(heading)
                .setItems(options.map { it.first }.toTypedArray()) { _, selected -> options[selected].second() }
                .setNegativeButton(R.string.action_close, null)
                .create()
        panelDialog = dialog
        projection.setAppInputBounds(null)
        dialog.setOnDismissListener {
            if (panelDialog === dialog) {
                panelDialog = null
                updateAppInputBounds()
            }
        }
        dialog.show()
    }

    private fun showHome() {
        ++launchGeneration
        pendingApp = null
        foregroundApp = null
        projection.setAppInputBounds(null)
        homeView.visibility = View.VISIBLE
        backButton.isEnabled = false
    }

    private fun returnFromApp() {
        if (foregroundApp == null) {
            showHome()
            return
        }
        val generation = launchGeneration
        projection.appBack {
            runOnUiThread {
                if (!isDestroyed && !isFinishing && generation == launchGeneration) showHome()
            }
        }
    }

    private fun selectApp(component: ComponentName) {
        pendingApp = component
        launchPendingApp()
    }

    private fun launchPendingApp() {
        val component = pendingApp ?: return
        val texture = appView.surfaceTexture ?: return
        if (appView.width <= 0 || appView.height <= 0) return
        val generation = ++launchGeneration
        projection.openApp(component, texture, appView.width, appView.height) { ok ->
            runOnUiThread {
                if (isDestroyed || isFinishing || generation != launchGeneration) return@runOnUiThread
                if (ok) {
                    pendingApp = null
                    foregroundApp = component
                    appDrawer.visibility = View.GONE
                    homeView.visibility = View.GONE
                    backButton.isEnabled = true
                    updateAppInputBounds()
                } else {
                    showHome()
                    notice(R.string.car_app_start_failed)
                }
            }
        }
    }

    private fun updateAppInputBounds() {
        if (foregroundApp == null || appView.width == 0 || appView.height == 0) return
        if (panelDialog != null || appDrawer.visibility == View.VISIBLE) return
        val origin = IntArray(2)
        appView.getLocationOnScreen(origin)
        projection.setAppInputBounds(Rect(origin[0], origin[1], origin[0] + appView.width, origin[1] + appView.height))
    }

    override fun onSurfaceTextureAvailable(
        texture: SurfaceTexture,
        width: Int,
        height: Int,
    ) {
        launchPendingApp()
    }

    override fun onSurfaceTextureSizeChanged(
        texture: SurfaceTexture,
        width: Int,
        height: Int,
    ) = Unit

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        val resumeApp = pendingApp ?: foregroundApp
        showHome()
        pendingApp = resumeApp
        projection.releaseTexture(texture)
        return false
    }

    private fun launcher(packageName: String): ComponentName? =
        runCatching { packageManager.getLaunchIntentForPackage(packageName)?.component }.getOrNull()

    private fun choosePlayer() {
        val manager = packageManager
        val mediaIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC)
        val browsers = Intent("android.media.browse.MediaBrowserService")
        Bg.run("dashboard-players") {
            val packages =
                MEDIA_PACKAGES + manager.queryIntentActivities(mediaIntent, 0).map { it.activityInfo.packageName } +
                    manager.queryIntentServices(browsers, 0).map { it.serviceInfo.packageName } +
                    listOfNotNull(MediaSessionsState.nowPlaying?.pkg)
            val players =
                packages
                    .distinct()
                    .mapNotNull { pkg ->
                        val component = launcher(pkg) ?: return@mapNotNull null
                        val name =
                            runCatching {
                                manager.getApplicationLabel(manager.getApplicationInfo(pkg, 0)).toString()
                            }.getOrDefault(pkg)
                        name to component
                    }.sortedBy { it.first }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                if (players.isEmpty()) {
                    notice(R.string.car_no_players)
                    return@runOnUiThread
                }
                showPanel(R.string.car_choose_player, players.map { (name, component) -> name to { selectApp(component) } })
            }
        }
    }

    private fun renderMedia() {
        val state = MediaSessionsState.nowPlaying
        val playing = state?.playing == true
        val action = getString(if (playing) R.string.car_pause else R.string.car_play)
        val icon = if (playing) R.drawable.ic_dashboard_pause else R.drawable.ic_play
        listOf(musicPlay, dockPlay).forEach { button ->
            button.setImageResource(icon)
            button.contentDescription = action
            button.tooltipText = action
        }
        title.text = state?.title ?: getString(R.string.car_music_idle)
        artist.text =
            state?.artist ?: if (MediaSessionsState.accessGranted) "" else getString(R.string.car_media_access)
        val cover = state?.art
        if (cover != null) {
            artwork.scaleType = ImageView.ScaleType.CENTER_CROP
            artwork.imageTintList = null
            artwork.setImageBitmap(cover)
        } else {
            artwork.scaleType = ImageView.ScaleType.CENTER
            artwork.imageTintList = ColorStateList.valueOf(palette.muted)
            artwork.setImageResource(R.drawable.ic_music)
        }
        renderProgress()
    }

    private fun renderProgress() {
        val state = MediaSessionsState.nowPlaying
        val duration = state?.durationMs?.takeIf { it > 0 }
        val position = state?.takeIf { it.positionMs >= 0 && duration != null }?.displayPositionAt(SystemClock.elapsedRealtime())
        musicProgress.progress =
            if (duration != null && position != null) (position.toDouble() * musicProgress.max / duration).toInt() else 0
        val unavailable = getString(R.string.car_status_unknown)
        elapsedTime.text = position?.let { DateUtils.formatElapsedTime(it / 1_000L) } ?: unavailable
        totalTime.text = duration?.let { DateUtils.formatElapsedTime(it / 1_000L) } ?: unavailable
    }

    private fun togglePlayback() =
        mediaKey(
            if (MediaSessionsState.nowPlaying?.playing == true) KeyEvent.KEYCODE_MEDIA_PAUSE else KeyEvent.KEYCODE_MEDIA_PLAY,
        )

    private fun mediaKey(keyCode: Int) {
        Bg.run("dashboard-media") {
            if (!MediaSessionsState.controlCurrentPlayer(applicationContext, keyCode)) {
                SessionController.dispatchMediaKey(keyCode)
            }
            MediaSessionsState.requestResync()
        }
    }

    private fun notice(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun renderMapButton(view: ImageButton) {
        val icon = homeMap?.let { runCatching { packageManager.getActivityIcon(it) }.getOrNull() }
        if (icon == null) {
            view.imageTintList = ColorStateList.valueOf(palette.text)
            view.setImageResource(R.drawable.ic_dashboard_map)
        } else {
            view.imageTintList = null
            view.setImageDrawable(icon)
        }
    }

    private fun column() =
        LinearLayout(this)
            .apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }.themed {
                background = rounded(palette.card, 18)
            }

    private fun button(
        label: String,
        pressed: () -> Unit,
    ) = Button(this)
        .apply {
            text = label
            isAllCaps = false
            textSize = 17f
            isSingleLine = true
            minHeight = dp(52)
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { pressed() }
        }.themed {
            setTextColor(palette.text)
            background = actionBackground(palette.control)
        }

    private fun iconButton(
        description: Int,
        icon: Int,
        prominent: Boolean = false,
        pressed: () -> Unit,
    ) = ImageButton(this)
        .apply {
            contentDescription = getString(description)
            tooltipText = contentDescription
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(11), dp(10), dp(11), dp(10))
            setImageResource(icon)
            setOnClickListener { pressed() }
        }.themed {
            imageTintList =
                ColorStateList(
                    arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                    intArrayOf(palette.track, if (prominent) palette.onAccent else palette.text),
                )
            background = actionBackground(if (prominent) palette.accent else palette.control)
        }

    private fun actionBackground(color: Int) =
        RippleDrawable(ColorStateList.valueOf(palette.ripple), rounded(color, 12), rounded(Color.WHITE, 12))

    private fun label(
        text: String,
        size: Int,
        bold: Boolean = false,
        muted: Boolean = false,
    ) = TextView(this)
        .apply {
            this.text = text
            textSize = size.toFloat()
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }.themed {
            setTextColor(if (muted) palette.muted else palette.text)
        }

    private fun <T : View> T.themed(update: T.() -> Unit): T {
        update()
        themeUpdates += { update() }
        return this
    }

    private fun paletteFor(configuration: Configuration): DashboardPalette =
        if ((configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) {
            DashboardPalette.DARK
        } else {
            DashboardPalette.LIGHT
        }

    private fun rounded(
        color: Int,
        radius: Int,
    ) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDestroy() {
        MediaSessionsState.removeListener(refreshMedia)
        panelDialog?.dismiss()
        super.onDestroy()
    }

    private companion object {
        val MEDIA_PACKAGES =
            listOf(
                "com.spotify.music",
                "com.google.android.apps.youtube.music",
                "com.apple.android.music",
                "deezer.android.app",
                "org.videolan.vlc",
                "com.netease.cloudmusic",
                "com.tencent.qqmusic",
                "com.kugou.android",
            )
    }
}
