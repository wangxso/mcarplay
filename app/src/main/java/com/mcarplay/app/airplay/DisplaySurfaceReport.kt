package com.mcarplay.app.airplay

/**
 * `/info` 显示屏几何与「车机实际接收视频的表面」的对照（只描述，不改协商结果）。
 *
 * issue #62（奔驰 MB_E-P-4，AirPlay 480.7.4）里 `/info` 有**两套自相矛盾的几何**，
 * 共用同一个 display UUID：
 *
 * ```
 * displays[0]      heightPixels=1278  primaryInputDevice=2  type=110（输入/HID 表面）
 * displayPanels[0] heightPixels=1728  initialVideoStreams=[{uuid=<同一 UUID>}]
 * ```
 *
 * 屏幕流 SETUP 用的是 `displays[0]` 的 UUID（[AirPlayClient.uuidText]），但真正挂在
 * `initialVideoStreams` 上的面板高度不同。若车机按 panel 建立解码画布，我们按 1278
 * 编的流会「送达但不显示」。本类只把这两组几何并列写进日志，供报告判断属于哪种情况；
 * **不改变**现有协商（换成 panel 几何必须先有实车对照，见 docs/CARWITH-ENTRY-FINDINGS.md）。
 */
internal object DisplaySurfaceReport {
    /** 一组几何：宽、高与它来自 `/info` 的哪个键。 */
    data class Geometry(
        val width: Int?,
        val height: Int?,
        val source: String,
    ) {
        val text: String get() = "${width ?: "?"}x${height ?: "?"}"
    }

    /**
     * 一行对照说明。`display` = `displays[0]`；`panels` = `displayPanels`；
     * 两者高度不同时明确写出，让「流送到却没画面」的报告能一眼看到几何冲突。
     *
     * **不打印原始 display UUID**：它是接收端提供的稳定标识，`DiagnosticRedactor` 的
     * longHex 只匹配 32 位以上连续十六进制，带连字符的规范 UUID 会原样进支持报告（也会经
     * 调试桥导出）。这里只报「是否与 `displays[0]` 同一表面」：相同写「同 displays[0]」，
     * 不同则按本行首次出现顺序给 `<DISPLAY-2>` 这类**报告内别名**——既能比较各表面，
     * 又不把接收端标识写出去。
     */
    fun describe(
        display: Map<*, *>?,
        panels: List<*>?,
    ): String {
        val displayGeometry = geometryOf(display, "displays[0]")
        val displayUuid = uuidOf(display)
        val videoSurfaces = videoSurfaceGeometry(panels)
        if (displayGeometry == null && videoSurfaces.isEmpty()) return "无显示屏几何（/info 未声明 displays/displayPanels）"
        val head = "displays[0]=${displayGeometry?.text ?: "缺"}" + if (displayUuid == null) "（无 uuid）" else ""
        if (videoSurfaces.isEmpty()) return "$head；displayPanels 未声明可用的初始视频表面"
        // 同一表面多次出现必须拿同一个别名，否则「表面之间能否比较」就没了意义。
        val otherAliases = LinkedHashMap<String, String>()
        val panelText =
            videoSurfaces.joinToString("; ") { surface ->
                "${surface.source} uuid=${uuidLabel(surface.uuid, displayUuid, otherAliases)} ${surface.text}"
            }
        val mismatch =
            displayGeometry?.height?.let { displayHeight ->
                videoSurfaces.any { it.geometry.height != null && it.geometry.height != displayHeight }
            } ?: false
        return "$head；车机视频表面：$panelText" +
            if (mismatch) "（与 displays[0] 高度不同——按 displays 发送的流可能落在不显示的面板上）" else ""
    }

    /**
     * 表面 UUID 的可报告写法：缺失 / 与 `displays[0]` 相同 / 报告内稳定别名。
     * [otherAliases] 让同一接收端 UUID 在多个表面条目上始终得到同一个 `<DISPLAY-N>`，
     * 同时不把这个标识本身写进日志。
     */
    private fun uuidLabel(
        uuid: String?,
        displayUuid: String?,
        otherAliases: MutableMap<String, String>,
    ): String =
        when {
            uuid == null -> "缺"
            uuid == displayUuid -> "同 displays[0]"
            else -> otherAliases.getOrPut(uuid) { "<DISPLAY-${otherAliases.size + 2}>" }
        }

    /** `displayPanels[].initialVideoStreams[]` 各自指向的表面与其父面板几何。 */
    fun videoSurfaceGeometry(panels: List<*>?): List<VideoSurface> {
        if (panels == null) return emptyList()
        val out = ArrayList<VideoSurface>()
        panels.forEachIndexed { index, entry ->
            val panel = entry as? Map<*, *> ?: return@forEachIndexed
            val geometry = geometryOf(panel, "displayPanels[$index]") ?: return@forEachIndexed
            val streams = (panel["initialVideoStreams"] as? List<*>).orEmpty()
            if (streams.isEmpty()) {
                out += VideoSurface(geometry, uuid = null, source = "displayPanels[$index]")
            } else {
                streams.forEachIndexed { streamIndex, stream ->
                    val map = stream as? Map<*, *>
                    out +=
                        VideoSurface(
                            geometry,
                            uuid = uuidOf(map),
                            source = "displayPanels[$index].initialVideoStreams[$streamIndex]",
                        )
                }
            }
        }
        return out
    }

    /** 一个 `initialVideoStreams` 条目：它指向的 display UUID 与父面板几何。 */
    data class VideoSurface(
        val geometry: Geometry,
        val uuid: String?,
        val source: String = "displayPanels",
    ) {
        val text: String get() = geometry.text
    }

    /** 取整数像素字段：bplist 解析成 Long，个别接收端给 Int。 */
    private fun geometryOf(
        map: Map<*, *>?,
        source: String,
    ): Geometry? {
        if (map == null) return null
        val width = (map["widthPixels"] as? Number)?.toInt()
        val height = (map["heightPixels"] as? Number)?.toInt()
        if (width == null && height == null) return null
        return Geometry(width, height, source)
    }

    private fun uuidOf(map: Map<*, *>?): String? =
        when (val raw = map?.get("uuid") ?: map?.get("UUID")) {
            null -> null
            is String -> raw.takeIf { it.isNotBlank() }
            is ByteArray ->
                if (raw.size == 16) {
                    val hex = raw.joinToString("") { "%02x".format(it) }
                    "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
                        "${hex.substring(16, 20)}-${hex.substring(20)}"
                } else {
                    raw.joinToString("") { "%02x".format(it) }
                }
            is Number -> raw.toString()
            else -> raw.toString().takeIf { it.isNotBlank() }
        }
}
