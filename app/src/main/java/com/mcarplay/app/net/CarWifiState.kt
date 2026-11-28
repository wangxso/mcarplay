package com.mcarplay.app.net

import java.util.concurrent.ConcurrentHashMap

/**
 * 车机 Wi-Fi 入网与绑定的进程级状态容器（Phase 2c-1）。
 *
 * 集中保存 [boundCarSsid]、最近一次车机热点凭据的原子快照（[Credential]）、
 * [forgetPromptHandled] 与 [shellFallbackCleanupSsid]。
 * 沿用原有 volatile / 并发集合语义：不加新 CAS 或全状态锁，不带持久化、轮次重置或自动清理逻辑，
 * 也不混入按目标实例隔离的 `WifiCredentialCache`。
 */
internal class CarWifiState {
    /**
     * 最近一次车机热点凭据的原子快照。
     *
     * SSID 与密码必须成对替换：0x4301 只带 SSID（`KNOWN_SSID`）时若不把密码一并清掉，
     * 手动 WLAN 兜底按钮会把上一台车的密码复制到剪贴板。
     */
    internal data class Credential(
        val ssid: String,
        val passphrase: String?,
    )

    /** 当前已绑定到本进程的车机热点 SSID（绑定成功后写入，断开/收尾时清 null）。 */
    @Volatile
    var boundCarSsid: String? = null

    @Volatile
    private var snapshot: Credential? = null

    val ssid: String?
        get() = snapshot?.ssid

    val passphrase: String?
        get() = snapshot?.passphrase

    /** 本次进程内已就「是否清除系统保存热点」询问过的 SSID（进程寿命，避免重试重复弹窗）。 */
    val forgetPromptHandled: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 本轮 shell 兜底把热点新存成系统网络时记录的 SSID，供会话终态读取并清空后异步 forget。 */
    @Volatile
    var shellFallbackCleanupSsid: String? = null

    /** 一次原子读取，供需要同时核对 SSID 与密码的调用方/测试使用。 */
    fun current(): Credential? = snapshot

    fun remember(
        ssid: String,
        passphrase: String?,
    ) {
        snapshot = Credential(ssid, passphrase)
    }
}
