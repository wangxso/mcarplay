package com.mcarplay.app

/**
 * 最近一次车机热点凭据的原子快照。
 *
 * SSID 与密码必须成对替换：0x4301 只带 SSID（[com.mcarplay.app.iap2.WifiCredentialSource.KNOWN_SSID]）
 * 时若不把密码一并清掉，手动 WLAN 兜底按钮会把上一台车的密码复制到剪贴板。
 */
internal class CarWifiCredentialHint {
    internal data class Credential(
        val ssid: String,
        val passphrase: String?,
    )

    @Volatile
    private var snapshot: Credential? = null

    val ssid: String? get() = snapshot?.ssid
    val passphrase: String? get() = snapshot?.passphrase

    /** 一次原子读取，供需要同时核对 SSID 与密码的调用方/测试使用。 */
    fun current(): Credential? = snapshot

    fun remember(
        ssid: String,
        passphrase: String?,
    ) {
        snapshot = Credential(ssid, passphrase)
    }
}
