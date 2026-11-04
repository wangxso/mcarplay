package com.mcarplay.app.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * 电池优化豁免：锁屏不断连的前提。
 *
 * 2026-10-31 艾瑞泽 8 实测：未豁免时 HyperOS 在锁屏约 9 秒后冻结本进程并切断其网络，
 * 前台服务与 CPU 唤醒锁都挡不住，亮屏解冻时所有套接字已被系统关闭（`ECONNABORTED` /
 * `sendto EPERM`），车机随即退出 CarPlay。见 docs/CARWITH-ENTRY-FINDINGS.md。
 *
 * 标准的豁免请求 Intent 在 HyperOS 4 上会被重定向到安全中心的单应用耗电详情页
 * （`com.miui.powercenter.legacypowerrank.PowerDetailActivity`），省电策略选「无限制」即可；
 * 其他 ROM 弹系统的「允许忽略电池优化」对话框。
 */
object BatteryExemption {
    /** 是否已豁免；读不到（极少数 ROM 抛异常）返回 null，调用方不提示。 */
    fun isExempt(context: Context): Boolean? =
        runCatching {
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            power.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrNull()

    /** 打开豁免设置：单应用豁免请求 → 电池优化列表 → 应用详情，逐个回退。 */
    fun openSettings(context: Context): Boolean {
        val pkg = Uri.fromParts("package", context.packageName, null)
        val candidates =
            listOf(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg),
            )
        return candidates.any { intent ->
            runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
        }
    }

    /** 连接前是否提示：只在明确未豁免且本进程尚未提示过时（「仍然连接」后不再打扰）。 */
    internal fun shouldPrompt(
        exempt: Boolean?,
        alreadyPrompted: Boolean,
    ): Boolean = exempt == false && !alreadyPrompted
}
