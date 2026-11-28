package com.mcarplay.app.architecture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 2h S 层源码契约：
 *  1. `session/SessionLog` 接管日志缓冲、格式化、监听回调与快照读取；
 *  2. `service/ScreenPowerController` 接管物理屏断电/点亮/休眠恢复与启动兜底；
 *  3. 3.1 节列出的 4 处引擎→上层反向依赖已消除（CarLifeDiagnostics、ExternalHuController、BtSnoopActivity），
 *     控制器与上层服务只保留薄门面/宿主装配。
 */
class Phase2hSourceContractTest {
    private val controller: String by lazy { SourceTree.mainKotlinFiles.getValue("SessionController.kt").readText() }
    private val sessionLog: String by lazy { SourceTree.mainKotlinFiles.getValue("session/SessionLog.kt").readText() }
    private val screenPower: String by lazy { SourceTree.mainKotlinFiles.getValue("service/ScreenPowerController.kt").readText() }
    private val carLifeDiag: String by lazy { SourceTree.mainKotlinFiles.getValue("carlife/CarLifeDiagnostics.kt").readText() }
    private val externalHu: String by lazy { SourceTree.mainKotlinFiles.getValue("carlife/ExternalHuController.kt").readText() }
    private val carPlayService: String by lazy { SourceTree.mainKotlinFiles.getValue("service/CarPlayService.kt").readText() }
    private val externalHuService: String by lazy { SourceTree.mainKotlinFiles.getValue("service/ExternalHuService.kt").readText() }
    private val btSnoopActivity: String by lazy { SourceTree.mainKotlinFiles.getValue("ui/BtSnoopActivity.kt").readText() }
    private val mainActivity: String by lazy { SourceTree.mainKotlinFiles.getValue("MainActivity.kt").readText() }
    private val manifest: String by lazy { File(SourceTree.repoRoot, "app/src/main/AndroidManifest.xml").readText() }

    private fun code(source: String): String = KotlinSource.scan(source).code

    @Test
    fun sessionLogOwnsBufferingAndControllerDelegates() {
        val logCode = code(sessionLog)
        listOf(
            "internal class SessionLog(",
            "var listener: ((String) -> Unit)?",
            "var enabled: Boolean",
            "fun append(message: String)",
            "fun snapshot(): String",
            "fun clear()",
        ).forEach { assertTrue("SessionLog 缺少声明：$it", logCode.contains(it)) }

        val scCode = code(controller)
        listOf("private val logLock", "private val logBuilder", "private val timestamp").forEach {
            assertFalse("日志状态应已迁入 SessionLog：$it", scCode.contains(it))
        }
        listOf(
            "private val sessionLog =",
            "SessionLog(",
            "get() = sessionLog.listener",
            "get() = sessionLog.enabled",
            "fun appendLog(message: String) = sessionLog.append(message)",
            "fun logText(): String = sessionLog.snapshot()",
            "fun clearLog() = sessionLog.clear()",
        ).forEach { assertTrue("SessionController 缺少日志门面委托：$it", scCode.contains(it)) }
    }

    @Test
    fun screenPowerControllerOwnsPhysicalPowerAndControllerDelegates() {
        val powerCode = code(screenPower)
        listOf(
            "internal interface ScreenPowerPorts",
            "internal class ScreenPowerController(",
            "private var phoneScreenOff",
            "private val lock = Any()",
            "fun restoreAtStartup()",
            "fun applyPreference(enabled: Boolean)",
            "fun enterOff()",
            "fun restore(sleepIfCarSide: Boolean)",
        ).forEach { assertTrue("ScreenPowerController 缺少声明：$it", powerCode.contains(it)) }

        val scCode = code(controller)
        listOf("private var phoneScreenOff", "private val screenPowerLock", "private fun enterPhoneScreenOff(").forEach {
            assertFalse("物理屏电源状态应已迁入 ScreenPowerController：$it", scCode.contains(it))
        }
        listOf(
            "private val screenPowerController =",
            "ScreenPowerController.create(",
            "screenPowerController.restoreAtStartup()",
            "fun applyScreenOffPreference(enabled: Boolean) = screenPowerController.applyPreference(enabled)",
            "private fun restorePhoneScreen(sleepIfCarSide: Boolean) = screenPowerController.restore(sleepIfCarSide)",
        ).forEach { assertTrue("SessionController 缺少物理屏电源门面委托：$it", scCode.contains(it)) }
    }

    @Test
    fun newComponentsHaveNoSessionControllerReferencesOrVehicleWords() {
        val banned =
            setOf("passat", "audi", "honda", "cadillac", "diplay", "winplay", "macplay", "chery", "arrizo", "mib3", "id7", "vw", "cinemo")
        listOf("session/SessionLog.kt" to sessionLog, "service/ScreenPowerController.kt" to screenPower).forEach { (name, raw) ->
            val stripped = code(raw)
            assertFalse("$name 不得反向引用 SessionController", stripped.contains("SessionController"))
            val hits = KotlinSource.identifierWords(stripped) intersect banned
            assertTrue("$name 标识符不得包含车型禁词：$hits", hits.isEmpty())
        }
    }

    @Test
    fun screenPowerLogTemplatesStayVerbatim() {
        listOf(
            "SCREEN: 启动恢复物理屏失败（ROM 未授权 shell 电源控制）",
            "SCREEN: 会话已结束——跳过物理息屏",
            "SCREEN: 手机处于休眠——先唤醒再物理息屏（避免框架把面板重新点亮）",
            "SCREEN: 真息屏已进入（暗幕承载触摸检测——点暗幕唤醒，无需按电源键）",
            "SCREEN: 物理屏已断电（暗幕不可用——无触摸唤醒手段，按电源键两次可恢复）",
            "SCREEN: 物理屏断电失败（ROM 未给 shell 权限 / 取不到 display token）",
            "SCREEN: 车机侧断开——已让手机休眠（面板保持黑）",
            "SCREEN: 物理屏已恢复点亮 ok=",
        ).forEach { assertTrue("ScreenPowerController 日志模板必须逐字保留：$it", screenPower.contains(it)) }
    }

    @Test
    fun reverseDependenciesAreEliminatedAndWiredInUpperLayers() {
        val diagCode = code(carLifeDiag)
        assertTrue("CarLifeDiagnostics 必须暴露宿主注入端口", diagCode.contains("interface CarLifeDiagnosticsHost"))
        assertFalse("CarLifeDiagnostics 不得引用 SessionController", diagCode.contains("SessionController"))
        assertFalse("CarLifeDiagnostics 不得引用 CarPlayService", diagCode.contains("CarPlayService"))
        assertTrue(
            "CarPlayService 必须向 CarLifeDiagnostics.start 注入宿主",
            code(carPlayService).contains("CarLifeDiagnostics.start(applicationContext, carLifeDiagnosticsHost)"),
        )

        val huCode = code(externalHu)
        assertFalse("ExternalHuController 不得引用 SessionController", huCode.contains("SessionController"))
        assertTrue(
            "ExternalHuService 必须向 ExternalHuController.start 注入日志函数",
            code(externalHuService).contains("ExternalHuController.start(applicationContext, SessionController::appendLog)"),
        )

        assertTrue("BtSnoopActivity 必须位于 ui 包", code(btSnoopActivity).contains("package com.mcarplay.app.ui"))
        assertFalse(
            "旧路径 diagnostics/btsnoop/BtSnoopActivity.kt 应已移除",
            SourceTree.mainKotlinFiles.containsKey("diagnostics/btsnoop/BtSnoopActivity.kt"),
        )
        assertTrue("Manifest 必须注册 .ui.BtSnoopActivity", manifest.contains("""android:name=".ui.BtSnoopActivity""""))
        assertFalse("Manifest 不得残留旧 BtSnoopActivity 路径", manifest.contains(".diagnostics.btsnoop.BtSnoopActivity"))
        assertTrue("MainActivity 必须打开 ui.BtSnoopActivity", code(mainActivity).contains("com.mcarplay.app.ui.BtSnoopActivity::class.java"))
    }
}
