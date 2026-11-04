package com.mcarplay.app.bridge

import android.content.Context
import com.mcarplay.app.BuildConfig
import java.security.MessageDigest
import java.util.UUID

/** 远程调试桥偏好（独立文件 "bridge"，不与业务设置混放）。 */
object BridgePrefs {
    private const val FILE = "bridge"
    private const val KEY_URL = "serverUrl"
    private const val KEY_CODE = "pairCode"
    private const val KEY_DEVICE_ID = "deviceId"
    private const val KEY_AUTO = "autoConnect"
    private const val KEY_RESUME = "resumePending"
    private const val KEY_PRESET_ANSWER = "presetAnswer"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun serverUrl(context: Context): String = prefs(context).getString(KEY_URL, "") ?: ""

    fun pairCode(context: Context): String = prefs(context).getString(KEY_CODE, "") ?: ""

    fun save(
        context: Context,
        url: String,
        code: String,
    ) {
        prefs(context)
            .edit()
            .putString(KEY_URL, url.trim())
            .putString(KEY_CODE, code.trim())
            .apply()
    }

    /** 本机稳定设备标识（首次生成后持久化；服务端按它区分设备）。 */
    fun deviceId(context: Context): String {
        val p = prefs(context)
        p.getString(KEY_DEVICE_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString().substring(0, 8)
        p.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }

    /** App 启动即自动连桥（用户在设置页点过「连接」后置位；「断开」清零）。 */
    fun autoConnect(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO, false)

    fun setAutoConnect(
        context: Context,
        value: Boolean,
    ) {
        prefs(context).edit().putBoolean(KEY_AUTO, value).apply()
    }

    // ---- 构建时预置的调试服务器（仅 remoteDebug；需用户确认才写入并连接）----

    val presetUrl: String get() = BuildConfig.BRIDGE_PRESET_URL

    val presetKey: String get() = BuildConfig.BRIDGE_PRESET_KEY

    fun hasPreset(): Boolean = presetUrl.isNotBlank() && presetKey.isNotBlank()

    /** 本预置（地址+密钥指纹）是否还未被用户答复；已手动配好同一服务器视为已同意。 */
    fun presetPending(context: Context): Boolean {
        if (!hasPreset()) return false
        val p = prefs(context)
        if (p.getString(KEY_PRESET_ANSWER, null)?.substringAfter(':') == presetFingerprint()) return false
        return !(autoConnect(context) && serverUrl(context) == presetUrl && pairCode(context) == presetKey)
    }

    /** 同意：写入预置并开启自动连接；拒绝：只记下答复，保持离线（已有的手动配置不动）。 */
    fun answerPreset(
        context: Context,
        accepted: Boolean,
    ) {
        val edit = prefs(context).edit().putString(KEY_PRESET_ANSWER, "${if (accepted) "accepted" else "declined"}:${presetFingerprint()}")
        if (accepted) edit.putString(KEY_URL, presetUrl).putString(KEY_CODE, presetKey).putBoolean(KEY_AUTO, true)
        edit.apply()
    }

    // 只存指纹不存明文：预置变更（换服务器/密钥）后会重新询问
    private fun presetFingerprint(): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest("$presetUrl\n$presetKey".toByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it) }

    /** 重装前置位：新版本首次启动时弹「继续进入调试模式？」。 */
    fun resumePending(context: Context): Boolean = prefs(context).getBoolean(KEY_RESUME, false)

    // ---- 差分基线 sha 缓存（版本键控：装新包后自然失效重算）----

    fun baseApkSha(
        context: Context,
        version: String,
    ): String? {
        val p = prefs(context)
        if (p.getString("baseApkShaVer", "") != version) return null
        return p.getString("baseApkSha", null)
    }

    fun setBaseApkSha(
        context: Context,
        version: String,
        sha: String,
    ) {
        prefs(context)
            .edit()
            .putString("baseApkShaVer", version)
            .putString("baseApkSha", sha)
            .apply()
    }

    fun setResumePending(
        context: Context,
        value: Boolean,
    ) {
        prefs(context).edit().putBoolean(KEY_RESUME, value).apply()
    }
}
