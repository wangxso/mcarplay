package com.mcarplay.app.launcher

import android.content.ComponentName
import android.content.Context

/**
 * 默认地图偏好（仪表盘 tile 与地图屏启动用）。
 *
 * 白名单 [VirtualLauncher.MAP_PACKAGES_PRIORITY] 内单选；未设置时按优先序取
 * 第一个已安装者。持久化 key 为包名。
 *
 * ⚠️ 解析逻辑集中在 [resolveDefaultMap]（纯函数）。**不要**让解析路径回调
 * `VirtualLauncher.defaultMapComponent()`：后者只是转调本对象的 [resolve]，
 * 会构成无限递归 → StackOverflowError（2026-10-18 定谳，见 resolveDefaultMap 注释）。
 */
object DefaultMapPrefs {
    private const val PREFS_NAME = "settings"
    private const val KEY_DEFAULT_MAP = "defaultMapPackage"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 用户显式设置的默认地图包名（null = 未设置，走优先序）。 */
    fun manualPackage(context: Context): String? = prefs(context).getString(KEY_DEFAULT_MAP, null)

    /** 当前生效的默认地图（显式设置优先；否则按优先序取第一个已安装的）。 */
    fun resolve(context: Context): ComponentName? =
        resolveDefaultMap(
            manual = manualPackage(context),
            priority = VirtualLauncher.MAP_PACKAGES_PRIORITY,
            componentFor = { pkg -> launchComponent(context, pkg) },
        )

    /** 该包的可启动 Activity（未安装 / 无启动入口 → null）。 */
    private fun launchComponent(
        context: Context,
        pkg: String,
    ): ComponentName? {
        val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: return null
        val activity = context.packageManager.resolveActivity(launch, 0)?.activityInfo ?: return null
        // resolveActivity 在无匹配时可能给出其它包的兜底结果：只接受包名一致者，
        // 否则「已卸载」的显式设置会被误判为可用。
        if (activity.packageName != pkg) return null
        return ComponentName(pkg, activity.name)
    }

    fun set(
        context: Context,
        packageName: String?,
    ) {
        if (packageName == null) {
            prefs(context).edit().remove(KEY_DEFAULT_MAP).apply()
        } else {
            prefs(context).edit().putString(KEY_DEFAULT_MAP, packageName).apply()
        }
    }
}

/**
 * 默认地图解析的**纯函数**部分（显式设置优先 → 优先序扫描）。
 *
 * 独立出来的两个原因：
 *  1. 可单测（不需要 Context）——见 DefaultMapPrefsTest。
 *  2. 明确禁止回环：本函数只依赖传入的 [componentFor]，**不得**回调
 *     `VirtualLauncher.defaultMapComponent()`。旧实现里 `resolve()` 在「未设置默认
 *     地图」时回调它、而它又转调回 `resolve()`，构成无限递归 → StackOverflowError。
 *     症状（2026-10-18 实测）：卸载重装后地图卡不渲染（偏好被清空才进入该分支；
 *     异常被 `Bg.run` 吞进 logcat，文件日志完全静默），在设置里重选一次默认地图
 *     即恢复——极易被误判为虚拟屏/渲染问题。
 */
internal fun <T> resolveDefaultMap(
    manual: String?,
    priority: List<String>,
    componentFor: (String) -> T?,
): T? {
    // 显式设置优先；其应用已卸载时自然回落到优先序
    manual?.let { pkg -> componentFor(pkg)?.let { return it } }
    priority.forEach { pkg -> componentFor(pkg)?.let { return it } }
    return null
}

/**
 * 车机版地图的「地图主界面」Activity（`am start -n` 的显式入口；未知则返回 null）。
 *
 * 为什么要显式指定：车机版地图的 LAUNCHER intent 常落在启动页/引导页（会丢
 * `--display`，导致界面跑到手机主屏而不是地图屏）。高德车机版实测必须显式启动
 * `MainMapActivity`。
 *
 * 百度车机版（`com.baidu.naviauto`）**不需要**显式入口，返回 null 交给系统解析：
 * 实测 22.0.1（versionCode 1645）的 launcher 是
 * `com.baidu.naviauto/.SlashActivity.DefaultActivity`，包内**没有独立的地图主界面
 * Activity**——地图由 `com.baidu.mapautosdk` 在该 Activity 内渲染。
 * （曾误写成 `.NaviAutoActivity`：该版本不存在，`am start` 直接报
 * "Activity class does not exist"；虽有候选回退兜住，但每次启动会白跑一次
 * 失败的 shell 往返并留下误导性日志，2026-10-23 真机实测删除。）
 *
 * ⚠️ 显式入口**不保证**在所有版本存在，所以调用方必须用 [mapLaunchCandidates]
 * 拿候选列表并逐个尝试，失败后退回 launcher 解析出的 component——单点写死会让
 * 地图屏黑着且无从排查。
 */
internal fun carMapMainActivity(packageName: String): String? =
    when (packageName) {
        "com.autonavi.amapauto" -> "com.autonavi.amapauto/.MainMapActivity"
        else -> null
    }

/**
 * 地图启动的 `am start -n` 候选目标（按顺序尝试）：
 * ① 车机版的显式主界面（若已知）→ ② launcher 解析出的 component。
 */
internal fun mapLaunchCandidates(
    packageName: String,
    launcherComponent: String,
): List<String> {
    val explicit = carMapMainActivity(packageName)?.takeIf { it != launcherComponent }
    return listOfNotNull(explicit, launcherComponent).distinct()
}

/** [mapLaunchCandidates] 的 ComponentName 重载（真机路径用；单测走纯字符串版）。 */
internal fun mapLaunchCandidates(component: ComponentName): List<String> =
    mapLaunchCandidates(component.packageName, component.flattenToShortString())
