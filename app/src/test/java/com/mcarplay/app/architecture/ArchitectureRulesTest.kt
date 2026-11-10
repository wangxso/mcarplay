package com.mcarplay.app.architecture

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 会话编排层重构的依赖护栏（docs/ARCHITECTURE-REFACTOR.md 3.1、3.6、Phase 0）。
 *
 * 只扫描去掉注释与字符串后的代码，引用以 `com.mcarplay.app.X` 全限定名出现（显式 import 或内联全名）。
 * 允许清单登记的是基线已有的例外：修掉一个就删一行；清单里的条目不再违规时测试同样失败，提醒清理。
 */
class ArchitectureRulesTest {
    /** 协议、传输、媒体与诊断层：只通过构造参数里的回调/接口与上层通信。 */
    private val enginePackages =
        setOf(
            "airplay",
            "audio",
            "bt",
            "carlife",
            "compat",
            "diagnostics",
            "i18n",
            "iap2",
            "input",
            "media",
            "net",
            "shizuku",
            "transport",
            "util",
            "video",
        )

    /** 编排层与界面层（根包为空串）：可以依赖引擎层。 */
    private val orchestrationAndUiPackages = setOf("", "session", "service", "ui", "settings", "update", "legal")

    /** 引擎层不得引用的编排层与界面层。`R`、`BuildConfig` 是资源与构建常量，不在此列。 */
    private val upperLayers =
        listOf(
            Regex("""com\.mcarplay\.app\.SessionController\b"""),
            Regex("""com\.mcarplay\.app\.MainActivity\b"""),
            Regex("""com\.mcarplay\.app\.SettingsContentActivity\b"""),
            Regex("""com\.mcarplay\.app\.SenderApp\b"""),
            Regex("""com\.mcarplay\.app\.(${(orchestrationAndUiPackages - "").sorted().joinToString("|")})\b"""),
        )

    /** 基线（`ab0cb93`）已有的反向依赖：`文件 -> 被引用的全名前缀`。只减不增。 */
    private val allowedUpwardReferences =
        setOf(
            // CarWith 自检与外部车机控制直接写日志、认领 CarLife 会话；Phase 1f 改为注入 log 与注册表
            "carlife/CarLifeDiagnostics.kt -> com.mcarplay.app.SessionController",
            "carlife/CarLifeDiagnostics.kt -> com.mcarplay.app.service",
            "carlife/ExternalHuController.kt -> com.mcarplay.app.SessionController",
            // 支持报告读取蓝牙诊断摘要
            "diagnostics/SupportReport.kt -> com.mcarplay.app.SessionController",
            // btsnoop 查看页本身是 Activity，复用界面组件
            "diagnostics/btsnoop/BtSnoopActivity.kt -> com.mcarplay.app.ui",
            // 系统蓝牙广播入口负责拉起前台服务
            "bt/CarBluetoothReceiver.kt -> com.mcarplay.app.service",
        )

    /**
     * 编排流程代码（重构后新建）：车型差异只能经 `ReceiverCompatibility.Policy` 进入，
     * 标识符里不得出现接收端家族/车型名。注释与日志字符串不受限（日志文本是 logbot 契约）。
     */
    private val flowCode: (String) -> Boolean = { path ->
        path.startsWith("session/") ||
            path in
            setOf(
                "net/CarWifiJoiner.kt",
                "net/EndpointResolver.kt",
                "audio/AudioChannelStarter.kt",
                "airplay/ScreenOwnership.kt",
            )
    }

    private val vehicleWords =
        setOf("passat", "audi", "honda", "cadillac", "diplay", "winplay", "macplay", "chery", "arrizo", "mib3", "id7", "vw", "cinemo")

    @Test
    fun enginePackagesDoNotReferenceOrchestrationOrUi() {
        val found = upwardReferences()
        val unexpected = found - allowedUpwardReferences
        if (unexpected.isNotEmpty()) {
            fail(
                "引擎层引用了编排层/界面层（改为构造参数回调或接口注入；见 docs/ARCHITECTURE-REFACTOR.md 3.1）：\n" +
                    unexpected.sorted().joinToString("\n"),
            )
        }
    }

    @Test
    fun allowlistOnlyHoldsLiveExceptions() {
        val stale = allowedUpwardReferences - upwardReferences()
        assertTrue("以下例外已修复，请从允许清单删除：\n" + stale.sorted().joinToString("\n"), stale.isEmpty())
    }

    @Test
    fun flowCodeHasNoVehicleSpecificIdentifiers() {
        val violations =
            SourceTree.mainKotlinFiles.keys
                .filter(flowCode)
                .mapNotNull { path ->
                    val hits = KotlinSource.identifierWords(SourceTree.scan(path).code) intersect vehicleWords
                    hits.takeIf { it.isNotEmpty() }?.let { "$path: ${it.sorted()}" }
                }
        assertTrue(
            "编排流程代码按车型分支（改为 ReceiverCompatibility.Policy 字段；见 docs/ARCHITECTURE-REFACTOR.md 3.6）：\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun everyTopLevelPackageIsClassified() {
        // 新增顶层包必须先决定归属；同时防止包改名后规则扫不到文件而静默通过
        val packages =
            SourceTree.mainKotlinFiles.keys
                .map(SourceTree::topLevelPackage)
                .toSet()
        val unclassified = packages - enginePackages - orchestrationAndUiPackages
        assertTrue("未归类的顶层包（加入 enginePackages 或 orchestrationAndUiPackages）：$unclassified", unclassified.isEmpty())
        val missing = enginePackages - packages
        assertTrue("enginePackages 中的包在源码里不存在（改名或删除后同步更新）：$missing", missing.isEmpty())
    }

    private fun upwardReferences(): Set<String> =
        SourceTree.mainKotlinFiles.keys
            .filter { SourceTree.topLevelPackage(it) in enginePackages }
            .flatMap { path ->
                val code = SourceTree.scan(path).code
                upperLayers.flatMap { pattern -> pattern.findAll(code).map { "$path -> ${it.value}" } }
            }.toSet()
}
