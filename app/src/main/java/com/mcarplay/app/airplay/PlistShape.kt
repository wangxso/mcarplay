package com.mcarplay.app.airplay

/**
 * plist 值的「形状」描述（诊断用）：字典给键与子形状，数据只给长度，字符串只给长度，数字与布尔原样。
 * 车机命令里可能夹带 iAP2 报文或名字等内容，所以只记结构、不记内容。
 */
internal object PlistShape {
    fun describe(
        value: Any?,
        depth: Int = 0,
    ): String =
        when (value) {
            null -> "null"
            is ByteArray -> "data(${value.size}B)"
            is String -> "str(${value.length})"
            is Boolean, is Number -> value.toString()
            is Map<*, *> ->
                if (depth >= MAX_DEPTH) {
                    "dict(${value.size})"
                } else {
                    value.entries.joinToString(", ", "{", "}") { (key, child) -> "$key=${describe(child, depth + 1)}" }
                }
            is List<*> ->
                if (depth >= MAX_DEPTH || value.size > MAX_LIST) {
                    "list(${value.size})"
                } else {
                    value.joinToString(", ", "[", "]") { describe(it, depth + 1) }
                }
            else -> value.javaClass.simpleName
        }

    private const val MAX_DEPTH = 4
    private const val MAX_LIST = 12
}
