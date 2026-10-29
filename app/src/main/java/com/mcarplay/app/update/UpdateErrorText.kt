package com.mcarplay.app.update

import android.content.Context
import com.mcarplay.app.R

/** 更新失败的界面文案（随界面语言，按错误码）；未知错误码退回异常自带的说明。 */
object UpdateErrorText {
    fun message(
        context: Context,
        error: UpdateException,
    ): String {
        val id =
            when (error.code) {
                "LOCAL_VERSION" -> R.string.update_err_local_version
                "CANCELLED" -> R.string.update_err_cancelled
                "MISSING_CHECKSUM" -> R.string.update_err_missing_checksum
                "APK_SIZE" -> R.string.update_err_apk_size
                "APK_TRUNCATED" -> R.string.update_err_apk_truncated
                "CHECKSUM_MISMATCH" -> R.string.update_err_checksum_mismatch
                "CACHE_WRITE" -> R.string.update_err_cache_write
                "PACKAGE_MISMATCH" -> R.string.update_err_package_mismatch
                "VERSION_MISMATCH" -> R.string.update_err_version_mismatch
                "DOWNGRADE" -> R.string.update_err_downgrade
                "SDK_UNSUPPORTED" -> R.string.update_err_sdk_unsupported
                "SIGNATURE_MISMATCH" -> R.string.update_err_signature_mismatch
                "APK_UNREADABLE" -> R.string.update_err_apk_unreadable
                "UNTRUSTED_URL" -> R.string.update_err_untrusted_url
                "UNTRUSTED_REDIRECT" -> R.string.update_err_untrusted_redirect
                "BAD_REDIRECT" -> R.string.update_err_bad_redirect
                "RATE_LIMIT" -> R.string.update_err_rate_limit
                "REDIRECT_LIMIT" -> R.string.update_err_redirect_limit
                "RESPONSE_TOO_LARGE" -> R.string.update_err_response_too_large
                else -> null
            }
        if (id != null) return context.getString(id)
        if (error.code.startsWith("HTTP_")) return context.getString(R.string.update_err_http, error.code.removePrefix("HTTP_"))
        return error.message.orEmpty()
    }
}
