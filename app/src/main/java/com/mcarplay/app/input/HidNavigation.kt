package com.mcarplay.app.input

import android.view.KeyEvent

/**
 * 车机「导航类」HID（iDrive 旋钮 / 四向拨杆 / 确认键）→ Android 方向键。
 *
 * 与 [ConsumerKeys] 的分工：
 *  - Consumer 页（0x0C）= 媒体键 → `AudioManager.dispatchMediaKeyEvent`，全局生效、不需要焦点；
 *  - Generic Desktop 页（0x01）的 Wheel(0x38)/Hat(0x39) = 旋钮/方向 → **DPAD_***，
 *    必须注入虚拟屏、由焦点系统处理（`CarInputBridge.injectNavigationKey`）。
 *
 * **不按车机型号写死**：先用 HID 描述符判定（有 Wheel/Hat 且不是 Digitizer 才算导航设备），
 * 描述符缺失时才按报文长度兜底（2~4 字节、非 5 字节触摸）。
 *
 * 报文布局（宝马 iD8.5 实测校准用：[rawLogEnabled] 打开后日志里有原始 hex）：
 *  - 末字节按**有符号**解析为旋钮步进（顺时针为正）；
 *  - 前面的字节按位图解析按钮：bit0=确认、1=上、2=下、3=左、4=右、5=返回。
 *  - 描述符声明了 Hat switch 时改用「低 4 位 = 8 向帽」语义。
 */
object HidNavigation {
    /** 原始报文日志开关（设置 → 显示 →「HID 原始日志」）。 */
    @Volatile
    var rawLogEnabled: Boolean = false

    private const val PAGE_GENERIC_DESKTOP = 0x01
    private const val PAGE_BUTTON = 0x09
    private const val PAGE_DIGITIZER = 0x0D
    private const val USAGE_WHEEL = 0x38
    private const val USAGE_HAT_SWITCH = 0x39

    /** 单次报告最多走几步（旋钮快拧时避免一次注入几十个按键把 UI 打死）。 */
    private const val MAX_STEPS = 5

    private val lastButtonMask = HashMap<String, Int>()
    private val lastHat = HashMap<String, Int>()

    /** 报告对应的设备是否是导航类（有 Wheel/Hat、且不是触摸屏）。 */
    fun isNavigationDevice(descriptor: ByteArray?): Boolean {
        if (descriptor == null || descriptor.isEmpty()) return false
        if (hasPage(descriptor, PAGE_DIGITIZER)) return false
        return hasPage(descriptor, PAGE_GENERIC_DESKTOP) &&
            (hasUsage(descriptor, USAGE_WHEEL) || hasUsage(descriptor, USAGE_HAT_SWITCH))
    }

    /**
     * 处理一帧导航报告，返回需要注入的按键序列（0 个或多个）。
     *
     * @param uuid HID 设备 uuid（同一 uuid 的边沿状态单独维护）
     */
    fun handle(
        uuid: String,
        descriptor: ByteArray?,
        report: ByteArray,
    ): List<Int> {
        if (report.isEmpty()) return emptyList()
        val nav = isNavigationDevice(descriptor) || (descriptor == null && report.size in 2..4)
        if (!nav) return emptyList()
        if (rawLogEnabled) {
            android.util.Log.i(
                "mcarplay-HID",
                "HID-RAW uuid=$uuid bytes=" + report.joinToString(" ") { "%02X".format(it) } +
                    " nav=${isNavigationDevice(descriptor)}",
            )
        }
        return if (descriptor != null && hasUsage(descriptor, USAGE_HAT_SWITCH) && report.size == 1) {
            parseHat(uuid, report[0].toInt() and 0xFF)
        } else {
            parseWheelAndButtons(uuid, report)
        }
    }

    /** 8 向帽：0=上 1=右上 2=右 3=右下 4=下 5=左下 6=左 7=左上，其余=松开。 */
    private fun parseHat(
        uuid: String,
        value: Int,
    ): List<Int> {
        val hat = value and 0x0F
        if (hat == lastHat[uuid]) return emptyList()
        lastHat[uuid] = hat
        return when (hat) {
            0 -> listOf(KeyEvent.KEYCODE_DPAD_UP)
            1 -> listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_RIGHT)
            2 -> listOf(KeyEvent.KEYCODE_DPAD_RIGHT)
            3 -> listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
            4 -> listOf(KeyEvent.KEYCODE_DPAD_DOWN)
            5 -> listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)
            6 -> listOf(KeyEvent.KEYCODE_DPAD_LEFT)
            7 -> listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT)
            else -> emptyList()
        }
    }

    /** 末字节=有符号步进，前面的字节=按钮位图（边沿触发）。 */
    private fun parseWheelAndButtons(
        uuid: String,
        report: ByteArray,
    ): List<Int> {
        val keys = ArrayList<Int>(3)
        val buttonBytes = if (report.size >= 2) report.size - 1 else report.size
        var mask = 0
        for (i in 0 until buttonBytes.coerceAtMost(2)) {
            mask = mask or ((report[i].toInt() and 0xFF) shl (8 * i))
        }
        val pressed = mask and lastButtonMask[uuid].orZero().inv()
        lastButtonMask[uuid] = mask
        if (pressed != 0) {
            if ((pressed and 0x01) != 0) keys += KeyEvent.KEYCODE_DPAD_CENTER
            if ((pressed and 0x02) != 0) keys += KeyEvent.KEYCODE_DPAD_UP
            if ((pressed and 0x04) != 0) keys += KeyEvent.KEYCODE_DPAD_DOWN
            if ((pressed and 0x08) != 0) keys += KeyEvent.KEYCODE_DPAD_LEFT
            if ((pressed and 0x10) != 0) keys += KeyEvent.KEYCODE_DPAD_RIGHT
            if ((pressed and 0x20) != 0) keys += KeyEvent.KEYCODE_BACK
        }
        if (report.size >= 2) {
            val delta = report[report.size - 1].toInt()
            if (delta != 0) {
                val key = if (delta > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
                repeat(kotlin.math.abs(delta).coerceAtMost(MAX_STEPS)) { keys += key }
            }
        }
        return keys
    }

    private fun Int?.orZero(): Int = this ?: 0

    /**
     * 描述符里是否出现某个 Usage Page。
     * 只做「条目扫描」：UsagePage 是全局项（prefix `0000 0101`? 实际 bTag=0x0），
     * 这里按「任何 1~2 字节数据项的值等于该页」宽松匹配——误判只影响分流，
     * 不会把触摸屏当旋钮（Digitizer 页优先排除）。
     */
    private fun hasPage(
        descriptor: ByteArray,
        page: Int,
    ): Boolean {
        var index = 0
        while (index < descriptor.size) {
            val prefix = descriptor[index].toInt() and 0xFF
            val size =
                when (prefix and 0x03) {
                    0 -> 0
                    1 -> 1
                    2 -> 2
                    else -> 4
                }
            val type = (prefix shr 2) and 0x03
            val tag = (prefix shr 4) and 0x0F
            if (index + 1 + size > descriptor.size) return false
            var value = 0
            for (offset in 0 until size) {
                value = value or ((descriptor[index + 1 + offset].toInt() and 0xFF) shl (8 * offset))
            }
            if (type == 1 && tag == 0x0 && value == page) return true
            index += 1 + size
        }
        return false
    }

    /** 描述符里是否出现某个 Usage（局部项 bTag=0x0，1/2 字节）。 */
    private fun hasUsage(
        descriptor: ByteArray,
        usage: Int,
    ): Boolean {
        var index = 0
        while (index < descriptor.size) {
            val prefix = descriptor[index].toInt() and 0xFF
            val size =
                when (prefix and 0x03) {
                    0 -> 0
                    1 -> 1
                    2 -> 2
                    else -> 4
                }
            val type = (prefix shr 2) and 0x03
            val tag = (prefix shr 4) and 0x0F
            if (index + 1 + size > descriptor.size) return false
            var value = 0
            for (offset in 0 until size) {
                value = value or ((descriptor[index + 1 + offset].toInt() and 0xFF) shl (8 * offset))
            }
            if (type == 2 && tag == 0x0 && value == usage) return true
            index += 1 + size
        }
        return false
    }

    /** 键盘键的可读名（日志用）。 */
    fun nameOf(keyCode: Int): String =
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> "上"
            KeyEvent.KEYCODE_DPAD_DOWN -> "下"
            KeyEvent.KEYCODE_DPAD_LEFT -> "左"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "右"
            KeyEvent.KEYCODE_DPAD_CENTER -> "确认"
            KeyEvent.KEYCODE_BACK -> "返回"
            else -> "$keyCode"
        }
}
