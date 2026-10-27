package com.mcarplay.app.compat

import com.mcarplay.app.iap2.ReceiverProfile

/** 只选择影响现有媒体／输入协议的字段；不把动态 UUID、IP 或端口固化成能力。 */
object ReceiverCapabilities {
    fun digest(
        signals: ReceiverProfile.Signals,
        info: Map<*, *>?,
        instance: String?,
    ): String? {
        if (info.isNullOrEmpty()) return null
        val values =
            ReceiverFingerprint.identity(signals) +
                listOf(
                    instance.orEmpty(),
                    info["model"]?.toString().orEmpty(),
                    info["manufacturer"]?.toString().orEmpty(),
                    info["firmwareRevision"]?.toString().orEmpty(),
                    info["sourceVersion"]?.toString().orEmpty(),
                    info["features"]?.toString().orEmpty(),
                    fields(info["audioFormats"], listOf("type", "audioType", "audioOutputFormats", "audioInputFormats")),
                    fields(info["audioLatencies"], listOf("type", "audioType", "inputLatencyMicros", "outputLatencyMicros")),
                    fields(info["displays"], listOf("widthPixels", "heightPixels", "maxFPS", "features")),
                    fields(info["hidDevices"], listOf("hidDescriptor", "countryCode", "vendorID", "productID")),
                )
        return ReceiverFingerprint.digest(values)
    }

    private fun fields(
        value: Any?,
        names: List<String>,
    ): String {
        if (value !is List<*>) return "missing"
        return value
            .mapNotNull { it as? Map<*, *> }
            .map { item ->
                names.joinToString(";") { name ->
                    val raw = item[name]
                    val text =
                        if (raw is ByteArray) {
                            ReceiverFingerprint.digest(listOf(raw.joinToString(",")))
                        } else {
                            raw?.toString()
                                ?: "missing"
                        }
                    "$name=${text.length}:$text"
                }
            }.sorted()
            .joinToString("|")
    }
}
