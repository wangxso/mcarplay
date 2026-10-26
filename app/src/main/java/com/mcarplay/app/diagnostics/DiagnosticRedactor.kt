package com.mcarplay.app.diagnostics

/** A fresh instance per report keeps aliases useful within a report but unlinkable across reports. */
class DiagnosticRedactor(
    knownNames: List<String> = emptyList(),
) {
    private val aliases = mutableMapOf<String, MutableMap<String, String>>()

    init {
        knownNames.filter { it.length >= 3 && !it.startsWith('<') }.forEach { alias("NAME", it) }
    }

    fun redact(text: String): String {
        var prepared = pem.replace(text, "[key material omitted]")
        for (pattern in listOf(quotedNetwork, bareNetwork, namedPeer, angleName)) {
            pattern.findAll(prepared).forEach {
                val value = it.groupValues.last()
                if (value.length >= 3 && !value.startsWith('<')) alias("NAME", value)
            }
        }
        aliases["NAME"]?.entries?.sortedByDescending { it.key.length }?.forEach { (value, replacement) ->
            prepared = prepared.replace(value, replacement)
        }
        return prepared.lineSequence().joinToString("\n") { original ->
            var line = original.replace('\u0000', '?')
            if (wireDump.containsMatchIn(line)) {
                return@joinToString line
                    .takeWhile { it != ']' }
                    .takeIf { line.startsWith('[') }
                    ?.let { "$it] [wire payload omitted]" } ?: "[wire payload omitted]"
            }
            line = authorization.replace(line, "Authorization=<redacted>")
            line = secret.replace(line) { "${it.groupValues[1]}=<redacted>" }
            line = quotedNetwork.replace(line) { "${it.groupValues[1]}=${name(it.groupValues[2])}" }
            line = bareNetwork.replace(line) { "${it.groupValues[1]}=${name(it.groupValues[2])}" }
            line = namedPeer.replace(line) { "${it.groupValues[1]}=${name(it.groupValues[2])}" }
            line = angleName.replace(line) { "「${name(it.groupValues[1])}」" }
            line = mac.replace(line) { alias("MAC", it.value.lowercase()) }
            line = ipv4.replace(line) { alias("IP", it.value) }
            line =
                ipv6.replace(line) {
                    if ("::" in it.value || it.value.count { char -> char == ':' } >= 7) alias("IP", it.value.lowercase()) else it.value
                }
            line = email.replace(line) { alias("EMAIL", it.value) }
            line = phone.replace(line) { alias("PHONE", it.value) }
            line = coordinates.replace(line, "$1=<redacted>")
            line = longHex.replace(line, "<hex-redacted>")
            line = url.replace(line, "<url-redacted>")
            line
        }
    }

    private fun name(value: String): String = if (value.startsWith('<')) value else alias("NAME", value)

    private fun alias(
        kind: String,
        value: String,
    ): String {
        val values = aliases.getOrPut(kind) { linkedMapOf() }
        return values.getOrPut(value) { "<$kind-${values.size + 1}>" }
    }

    private companion object {
        val wireDump = Regex("(?i)(RAW\\s+(?:rx|tx)|wireHex|bodyHex|原始体|原始帧|hex\\s*=|0x4301.*(?:参数|组参数))")
        val secret =
            Regex(
                "(?i)(pass(?:word|phrase)?|psk|secret|token|authorization|privateKey|publicKey|aesKey|sessionKey|writeKey|readKey|seed|" +
                    "deviceI[Dd]|androidI[Dd]|serial(?:Number)?|pairingId|pairingIdentifier|identifier|authKey|userId|imei|imsi|" +
                    "cuid|nonce|signature|密码|密钥|配对码)" +
                    "[\\\"']?\\s*[:=：]\\s*(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;；）}]+)",
            )
        val quotedNetwork = Regex("(?i)(ssid|热点|Wi-?Fi|name)[\\s:=：]*[\\\"“]([^\\\"”]+)[\\\"”]")
        val bareNetwork =
            Regex(
                "(?i)(ssid)\\s*[:=：]\\s*([^<,;；）（→\\r\\n]+?)" +
                    "(?=\\s+[\\p{L}_][\\p{L}\\p{N}_]*\\s*[:=：]|[,;；）（→]|\\r?\\n|$)",
            )
        val namedPeer = Regex("(?i)(name|服务消失|发现服务|目标设备|车机名称)\\s*[:=： ]\\s*([^,;；\\s]+)")
        val angleName = Regex("「([^」]+)」")
        val mac = Regex("(?i)(?<![a-f0-9])(?:[a-f0-9]{2}[:-]){5}[a-f0-9]{2}(?![a-f0-9])")
        val ipv4 = Regex("(?<![\\w.])(?:\\d{1,3}\\.){3}\\d{1,3}(?![\\w.])")
        val ipv6 = Regex("(?i)(?<![\\w:])(?:[a-f0-9]{0,4}:){2,}[a-f0-9:]{0,4}(?:%[\\w.]+)?")
        val email = Regex("[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}")
        val phone = Regex("(?<!\\d)(?:\\+?86[- ]?)?1[3-9]\\d{9}(?!\\d)")
        val coordinates = Regex("(?i)(latitude|longitude|lat|lon|经度|纬度)\\s*[:=：]\\s*-?\\d+(?:\\.\\d+)?")
        val longHex = Regex("(?i)(?<![a-f0-9])[a-f0-9]{32,}(?![a-f0-9])")
        val url = Regex("(?i)https?://[^\\s]+")
        val pem = Regex("-----BEGIN [A-Z ]*KEY-----.*?-----END [A-Z ]*KEY-----", RegexOption.DOT_MATCHES_ALL)
        val authorization = Regex("(?i)authorization\\s*[:=]\\s*(?:Bearer|Basic)\\s+[^\\s]+")
    }
}
