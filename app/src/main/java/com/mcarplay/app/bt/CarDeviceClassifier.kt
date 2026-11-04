package com.mcarplay.app.bt

/**
 * 「这条 ACL 是不是车机」的纯判定（[CarBluetoothReceiver] 用；无 Android 依赖，便于单测）。
 *
 * 顺序：
 * 1. 选定了目标车机：只认目标地址（大小写不敏感），其余一律不是。
 * 2. 未选目标且系统给出了设备类别（Class of Device）：
 *    - 音视频大类下的「车载音响 0x0420」「免提 0x0408」直接认作车机——真车蓝牙名五花八门
 *      （2026-11-03/04 远程日志：奇瑞 `Cloudrive-023564`、大众 `VW BT 7094`、`i8`），
 *      关键词永远列不全，类别更可靠；
 *    - 音视频大类的其它小类（耳机 0x0404、头戴 0x0418 等）与可穿戴大类（手表）直接排除，
 *      即使名字碰巧含关键词（如 `… Buds Mini`）。
 * 3. 其余（手机 0x020C、未分类 0x1F00、电脑、类别未知）按名称关键词兜底：
 *    测试台接收端常跑在手机或电脑上，不能按类别一刀切。
 */
object CarDeviceClassifier {
    /** `BluetoothClass.getDeviceClass()` 的有效位（大类 + 小类，不含服务位）。 */
    private const val DEVICE_CLASS_MASK = 0x1FFC

    /** `BluetoothClass.Device.Major` 掩码。 */
    private const val MAJOR_MASK = 0x1F00

    /** `BluetoothClass.Device.Major.AUDIO_VIDEO`。 */
    const val MAJOR_AUDIO_VIDEO = 0x0400

    /** `BluetoothClass.Device.Major.WEARABLE`（手表等）。 */
    const val MAJOR_WEARABLE = 0x0700

    /** `BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO`。 */
    const val AUDIO_VIDEO_CAR_AUDIO = 0x0420

    /** `BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE`。 */
    const val AUDIO_VIDEO_HANDSFREE = 0x0408

    /** 未选目标且类别不确定时的兜底：常见车机蓝牙名关键词（真车 + 测试台）。 */
    val CAR_KEYWORDS =
        listOf(
            "BMW",
            "MINI",
            "AUDI",
            "PORSCHE",
            "MERCEDES",
            "BENZ",
            "VOLVO",
            "CARPLAY",
            // 奇瑞 Cloudrive 车机（2026-11-03 远程日志 `Cloudrive-023564`）
            "CLOUDRIVE",
            // 大众（2026-11-04 远程日志 `VW BT 7094`）
            "VW BT",
        )

    /**
     * @param targetAddress 用户选定的目标车机地址；null = 未选定
     * @param deviceClass `BluetoothClass.getDeviceClass()`；null = 系统未给出/无权限
     */
    fun isCarDevice(
        targetAddress: String?,
        address: String,
        name: String?,
        deviceClass: Int?,
    ): Boolean {
        if (targetAddress != null) return address.equals(targetAddress, ignoreCase = true)
        if (deviceClass != null) {
            val cls = deviceClass and DEVICE_CLASS_MASK
            if (cls == AUDIO_VIDEO_CAR_AUDIO || cls == AUDIO_VIDEO_HANDSFREE) return true
            val major = cls and MAJOR_MASK
            if (major == MAJOR_AUDIO_VIDEO || major == MAJOR_WEARABLE) return false
        }
        val label = name ?: return false
        return CAR_KEYWORDS.any { label.contains(it, ignoreCase = true) }
    }

    /** 日志用：`0x0420` 形式。 */
    fun describeClass(deviceClass: Int?): String =
        deviceClass?.let { String.format(java.util.Locale.ROOT, "0x%04X", it and DEVICE_CLASS_MASK) } ?: "?"
}
