package com.mcarplay.app.ui

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.mcarplay.app.R
import com.mcarplay.app.bt.TargetDevicePrefs
import com.mcarplay.app.compat.ReceiverDetection
import com.mcarplay.app.compat.ReceiverDetectionRuntime

/**
 * 目标车机选择弹窗（从 MainActivity 拆出，行为不变）：蓝牙已配对设备 + 实时扫描 + 系统配对。
 * 只依赖 [Host] 的三个回调（日志、提示条、目标变更后刷新准备行），不碰 Activity 的其它状态。
 *
 * 必须在 Activity 创建阶段构造（属性初始化器里）：内部注册了 ActivityResult 回调，
 * STARTED 之后再注册会抛异常。
 */
class TargetPickerController(
    private val activity: AppCompatActivity,
    private val host: Host,
) {
    interface Host {
        fun log(message: String)

        fun snack(message: String)

        /** 目标变更或弹窗关闭后调用（刷新「准备就绪」里的目标车机行）。 */
        fun onTargetChanged()
    }

    /** 停掉扫描并注销监听、丢弃弹窗引用（Activity 销毁时调用）。 */
    fun release() {
        runCatching { stopTargetScan() }
        shown = null
    }

    /** 当前打开的弹窗（扫描回调要往里加行；关闭时统一收尾）。 */
    private var shown: androidx.appcompat.app.AlertDialog? = null

    /** 当前打开的弹窗（旋转后 Activity 要重算它的窗口尺寸）。 */
    val dialog: androidx.appcompat.app.AlertDialog? get() = shown

    private var targetPickerReceiver: BroadcastReceiver? = null

    /** 弹窗内已展示的扫描结果（按地址去重；已配对的单独进上组，不进这里）。 */
    private val scannedTargets = LinkedHashMap<String, TargetPickerEntry>()

    /** 扫描期间点过「配对」的设备地址（配对完成后自动选中为目标）。 */
    private var pendingBondAddress: String? = null

    private data class TargetPickerEntry(
        val address: String,
        val name: String,
        val rssi: Int,
        val bonded: Boolean,
    )

    /** 扫描权限被拒后置位：授权回调里继续（弹窗可能已关，则忽略）。 */
    private var pendingTargetScan = false

    private val targetScanPermissionLauncher =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            host.log("BT-SCAN: BLUETOOTH_SCAN 授权: $granted")
            if (granted && pendingTargetScan) {
                pendingTargetScan = false
                shown?.let { startTargetScan(it) }
            } else if (!granted) {
                pendingTargetScan = false
                shown?.let { _ ->
                    updatePickerStatus(activity.getString(R.string.ma_scan_denied_status), warn = true)
                    pickerToast(activity.getString(R.string.ma_scan_denied_toast))
                }
            }
        }

    /**
     * 目标车机选择弹窗（2026-10-18 改版）：
     *  组 1「已配对」——直接选用（车机无线 CarPlay 的前置是系统级蓝牙配对）；
     *  组 2「附近的设备」——实时扫描（经典蓝牙 discovery），点击未配对设备即发起
     *  系统配对，配对成功后自动设为目标。
     *
     * 首行「自动（不指定）」保留原有语义：出站逐个尝试、入站来者不拒；
     * 选定后出站只连目标、入站拒绝非目标（防误连家里另一台车机）。
     * 选择立即生效（链路动态读取目标，无需重连）。
     */
    fun show() {
        if (ReceiverDetectionRuntime.detection.snapshot()?.state == ReceiverDetection.State.COLLECTING) {
            host.snack(activity.getString(R.string.ma_cancel_detection_first))
            return
        }
        val adapter =
            try {
                (activity.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
                    .adapter
            } catch (_: Exception) {
                null
            }
        if (adapter == null || !adapter.isEnabled) {
            host.snack(activity.getString(R.string.ma_bt_off_snack))
            return
        }
        val view = activity.layoutInflater.inflate(R.layout.dialog_target_picker, null)
        val dialog =
            androidx.appcompat.app.AlertDialog
                .Builder(activity)
                .setView(view)
                .create()
        shown = dialog
        scannedTargets.clear()
        pendingBondAddress = null
        view.findViewById<TextView>(R.id.btnCancelPicker).setOnClickListener { dialog.dismiss() }
        view.findViewById<TextView>(R.id.btnRescan).setOnClickListener { startTargetScan(dialog) }
        renderBondedGroup(view)
        dialog.setOnDismissListener {
            stopTargetScan()
            shown = null
            pendingBondAddress = null
            host.onTargetChanged()
        }
        dialog.setOnShowListener {
            // 面板自带圆角：窗口底色必须透明，否则四角露出方角
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            val width = (activity.resources.displayMetrics.widthPixels * PanelWindow.WIDTH_FRACTION).toInt()
            dialog.window?.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
        startTargetScan(dialog)
    }

    /** 渲染「已配对」组：自动行 + 每个已配对设备。 */
    private fun renderBondedGroup(view: View) {
        val list = view.findViewById<LinearLayout>(R.id.bondedList)
        val empty = view.findViewById<TextView>(R.id.bondedEmpty)
        list.removeAllViews()
        val current = TargetDevicePrefs.get(activity)
        // 「自动（不指定）」是模式而非设备：单独放在两组之上，不混进「已配对」组
        view.findViewById<LinearLayout>(R.id.autoRowContainer).apply {
            removeAllViews()
            addView(
                inflateTargetRow(
                    name = activity.getString(R.string.ma_target_auto),
                    address = activity.getString(R.string.ma_target_auto_desc),
                    action = activity.getString(if (current == null) R.string.ma_target_current else R.string.ma_target_select),
                    highlighted = current == null,
                    iconTint = R.color.m3_outline,
                ) {
                    TargetDevicePrefs.set(activity, null)
                    host.log("TARGET: 已取消目标指定——出站逐个尝试已配对设备，入站接受任一设备")
                    // 先关弹窗再提示（snackbar 在 Activity 窗口内，会被弹窗盖住）
                    shown?.dismiss()
                    host.snack(activity.getString(R.string.ma_target_auto_set))
                },
            )
        }
        val bonded = bondedDevices()
        empty.visibility = if (bonded.isEmpty()) View.VISIBLE else View.GONE
        bonded.forEach { device ->
            val address = device.address
            val name = deviceDisplayName(device)
            val isCurrent = current != null && TargetDevicePrefs.matches(current, address)
            list.addView(
                inflateTargetRow(
                    name = name,
                    address = address,
                    action = activity.getString(if (isCurrent) R.string.ma_target_current else R.string.ma_target_select),
                    highlighted = isCurrent,
                    iconTint = R.color.m3_primary,
                ) {
                    setTarget(device, name, closePicker = true)
                },
            )
        }
    }

    /** 单个设备行（两组共用；highlighted 时名称用主色）。 */
    private fun inflateTargetRow(
        name: String,
        address: String,
        action: String,
        highlighted: Boolean,
        iconTint: Int,
        onClick: () -> Unit,
    ): View {
        val row = activity.layoutInflater.inflate(R.layout.item_target_device, null)
        row.background =
            ContextCompat.getDrawable(
                activity,
                if (highlighted) R.drawable.bg_bt_device_row_current else R.drawable.bg_bt_device_row,
            )
        row.findViewById<TextView>(R.id.devName).apply {
            text = name
            setTextColor(activity.getColor(if (highlighted) R.color.m3_primary else R.color.m3_on_surface))
        }
        row.findViewById<TextView>(R.id.devAddr).text = address
        row.findViewById<TextView>(R.id.devAction).apply {
            // 「当前」= 实心绿胶囊 + 对勾 + 深色字；「选择/配对」= 描边胶囊 + 青色字。
            // 只靠色相区分不够（青 #7EDCE6 vs 绿 #7BD8A5 小字号下难辨），改用形态差异。
            if (highlighted) {
                text = "✓ $action"
                setTextColor(activity.getColor(R.color.m3_surface))
                background = ContextCompat.getDrawable(activity, R.drawable.bg_pill_current)
            } else {
                text = action
                setTextColor(activity.getColor(R.color.m3_primary))
                background = ContextCompat.getDrawable(activity, R.drawable.bg_tbtn)
            }
            // 实心/描边切换后 padding 由 drawable 决定，不需要重设
            setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom)
        }
        row.findViewById<ImageView>(R.id.devIcon).setColorFilter(activity.getColor(iconTint))
        row.setOnClickListener { onClick() }
        return row
    }

    /**
     * 开始扫描附近设备：注册 ACTION_FOUND / ACTION_BOND_STATE_CHANGED 后 startDiscovery。
     * 发现结果按地址去重、排除已配对设备（它们在上一组）；点「配对」调 createBond()。
     */
    private fun startTargetScan(dialog: androidx.appcompat.app.AlertDialog) {
        val adapter =
            try {
                (activity.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
                    .adapter
            } catch (_: Exception) {
                null
            } ?: return
        val granted =
            ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) {
            pendingTargetScan = true
            updatePickerStatus(activity.getString(R.string.ma_scan_needs_permission), warn = true)
            targetScanPermissionLauncher.launch(Manifest.permission.BLUETOOTH_SCAN)
            return
        }
        val scannedList = dialog.findViewById<LinearLayout>(R.id.scannedList) ?: return
        // 已配对的地址集合：扫描结果里剔掉（避免同一设备在两处出现）
        val bondedAddresses =
            bondedDevices()
                .map { it.address }
                .toSet()
        scannedTargets.clear()
        scannedList.removeAllViews()
        updatePickerStatus(activity.getString(R.string.ma_scanning))
        stopTargetScan()
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    when (intent.action) {
                        BluetoothDevice.ACTION_FOUND -> {
                            @Suppress("DEPRECATION")
                            val device =
                                intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                                    ?: return
                            val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, 0).toInt()
                            onTargetFound(dialog, device, rssi, bondedAddresses)
                        }

                        BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                            val device =
                                intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                                    ?: return
                            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                            onBondStateChanged(dialog, device, state)
                        }
                    }
                }
            }
        targetPickerReceiver = receiver
        val filter =
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            }
        // 系统（蓝牙栈）发出的广播：必须用 EXPORTED 才能在部分 ROM 上收到
        ContextCompat.registerReceiver(activity, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        val started =
            try {
                adapter.startDiscovery()
            } catch (_: SecurityException) {
                false
            }
        if (!started) {
            updatePickerStatus(activity.getString(R.string.ma_scan_failed), warn = true)
        } else {
            host.log("BT-SCAN: 开始扫描附近蓝牙设备")
        }
    }

    /** 扫描到一台设备：已配对→忽略；新设备→加一行（点「配对」发起系统配对）。 */
    private fun onTargetFound(
        dialog: androidx.appcompat.app.AlertDialog,
        device: BluetoothDevice,
        rssi: Int,
        bondedAddresses: Set<String>,
    ) {
        val address =
            try {
                device.address
            } catch (_: SecurityException) {
                return
            }
        if (address in bondedAddresses || scannedTargets.containsKey(address)) return
        val name = deviceDisplayName(device)
        scannedTargets[address] = TargetPickerEntry(address, name, rssi, bonded = false)
        val list = dialog.findViewById<LinearLayout>(R.id.scannedList) ?: return
        // 扫描仍在继续：状态行保留并计数，用户知道还可以继续等其它设备。
        // 刻意不走 updatePickerStatus——它会把列表滚到状态行，发现新设备时抢滚动位置。
        dialog.findViewById<TextView>(R.id.scanStatus)?.apply {
            text = activity.getString(R.string.ma_scan_progress, scannedTargets.size)
            setTextColor(activity.getColor(R.color.m3_mono_dim))
            visibility = View.VISIBLE
        }
        list.addView(
            inflateTargetRow(
                name = name,
                address = "$address · ${rssi}dBm",
                action = activity.getString(R.string.ma_target_pair),
                highlighted = false,
                iconTint = R.color.m3_amber,
            ) {
                beginBond(device)
            },
        )
    }

    /** 点击未配对设备：先停扫描（扫描期间配对易失败），再 createBond()。 */
    private fun beginBond(device: BluetoothDevice) {
        val address =
            try {
                device.address
            } catch (_: SecurityException) {
                return
            }
        stopDiscoveryOnly()
        pendingBondAddress = address
        val name = deviceDisplayName(device)
        val ok =
            try {
                device.createBond()
            } catch (error: SecurityException) {
                host.log("BT-PAIR: 配对「$name」失败 - 缺少权限：${error.message}")
                false
            }
        if (ok) {
            host.log("BT-PAIR: 已发起与「$name」($address) 的配对——请在系统弹窗确认")
            pickerNotice(activity.getString(R.string.ma_pairing, name))
        } else {
            pendingBondAddress = null
            host.log("BT-PAIR: 配对「$name」($address) 发起失败（设备可能已配对或蓝牙忙）")
            pickerNotice(activity.getString(R.string.ma_pair_start_failed))
        }
    }

    /**
     * 弹窗打开期间的提示。
     *
     * 弹窗是**独立窗口**（更高层级 + 全屏遮罩），Activity 里的 snackbar 必然被它盖住
     * ——用户只能看到底部一条被压暗的色块（2026-10-18 反馈）。系统 Toast 属更高窗口层，
     * 能浮在弹窗之上；同时更新弹窗内的状态行（滚动到可见位置）作为长期可见的说明。
     */
    private fun pickerNotice(message: String) {
        pickerToast(message)
        updatePickerStatus(message, warn = true)
    }

    /**
     * 弹窗打开期间的系统 Toast。系统 Toast 属更高窗口层，能浮在弹窗之上；
     * Activity 内的 snackbar 会被弹窗（独立窗口 + 遮罩）完全盖住。
     */
    private fun pickerToast(message: String) {
        android.widget.Toast
            .makeText(activity, message, android.widget.Toast.LENGTH_SHORT)
            .show()
    }

    /** 更新弹窗内的状态行并滚动到可见位置（`scanStatus` 在「附近的设备」标题下）。 */
    private fun updatePickerStatus(
        message: String,
        warn: Boolean = false,
    ) {
        val dialog = shown ?: return
        val status = dialog.findViewById<TextView>(R.id.scanStatus) ?: return
        status.text = message
        status.setTextColor(activity.getColor(if (warn) R.color.m3_amber else R.color.m3_mono_dim))
        status.visibility = View.VISIBLE
        // 状态行在视口外才滚动（用户在浏览上面的已配对列表时不该被抢走位置）。
        status.post {
            val scroll = dialog.findViewById<android.widget.ScrollView>(R.id.pickerScroll) ?: return@post
            val content = scroll.getChildAt(0) ?: return@post
            val top = offsetWithin(status, content)
            val bottom = top + status.height
            val visibleTop = scroll.scrollY
            val visibleBottom = visibleTop + scroll.height
            if (top < visibleTop || bottom > visibleBottom) {
                val y = top - (12 * activity.resources.displayMetrics.density).toInt()
                scroll.smoothScrollTo(0, y.coerceAtLeast(0))
            }
        }
    }

    /** view 相对 ancestor 的内容偏移（沿父链累加 top；ancestor 必须是其祖先）。 */
    private fun offsetWithin(
        view: View,
        ancestor: View,
    ): Int {
        var current: View? = view
        var y = 0
        while (current != null && current !== ancestor) {
            y += current.top
            current = current.parent as? View
        }
        return y
    }

    /** 配对状态变化：成功后设为目标并关弹窗；失败给出提示。 */
    private fun onBondStateChanged(
        dialog: androidx.appcompat.app.AlertDialog,
        device: BluetoothDevice,
        state: Int,
    ) {
        val address =
            try {
                device.address
            } catch (_: SecurityException) {
                return
            }
        if (address != pendingBondAddress) return
        when (state) {
            BluetoothDevice.BOND_BONDED -> {
                pendingBondAddress = null
                val name = deviceDisplayName(device)
                host.log("BT-PAIR: ✓ 已与「$name」($address) 完成配对——自动设为目标")
                // 先关弹窗再提示：snackbar 在 Activity 窗口内，弹窗未关时会被盖住
                dialog.dismiss()
                setTarget(device, name)
                // 保留系统配对后的连接；是否断开由本地适配策略或用户发起的检测决定。
            }

            BluetoothDevice.BOND_NONE -> {
                pendingBondAddress = null
                host.log("BT-PAIR: 与 $address 配对未完成（用户取消或失败）")
                // 弹窗仍开着：用 Toast（浮在弹窗之上）+ 弹窗内状态行，不用被遮挡的 snackbar
                pickerNotice(activity.getString(R.string.ma_pair_incomplete))
            }
            // BOND_BONDING：继续等（系统确认框未应答）
        }
    }

    /**
     * 设为出站目标；正在进行的连接在下一轮尝试/下一次连入时切换。
     *
     * @param closePicker true = 先关闭设备选择弹窗再提示（snackbar 属 Activity 窗口，
     *   弹窗开着时必被盖住——见 pickerNotice 注释）。
     */
    private fun setTarget(
        device: BluetoothDevice,
        name: String,
        closePicker: Boolean = false,
    ) {
        if (closePicker) shown?.dismiss()
        val previous = TargetDevicePrefs.get(activity)
        TargetDevicePrefs.set(activity, TargetDevicePrefs.Target(device.address, name))
        host.log(
            "TARGET: 目标车机已设为「$name」(${device.address})" +
                "——出站只连该设备、入站拒绝其它设备" +
                if (previous != null && previous.address != device.address) {
                    "（原目标「${previous.name}」）"
                } else {
                    ""
                },
        )
        host.snack(activity.getString(R.string.ma_target_set, name))
        host.onTargetChanged()
    }

    /** 停掉扫描并注销监听（弹窗关闭/重扫时调用）。 */
    private fun stopTargetScan() {
        stopDiscoveryOnly()
        targetPickerReceiver?.let {
            runCatching { activity.unregisterReceiver(it) }
            targetPickerReceiver = null
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun stopDiscoveryOnly() {
        // 调用点均已先确认 BLUETOOTH_SCAN 已授予（扫描流程入口），且此处整体捕获异常
        try {
            (activity.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
                .adapter
                ?.cancelDiscovery()
        } catch (_: Exception) {
        }
    }

    private fun bondedDevices(): List<BluetoothDevice> =
        try {
            (activity.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
                .adapter
                ?.bondedDevices
                ?.toList()
                .orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        }

    /** 设备展示名（无名称时退回地址）；读名需要 BLUETOOTH_CONNECT，异常时用地址。 */
    private fun deviceDisplayName(device: BluetoothDevice): String =
        try {
            device.name?.takeIf { it.isNotBlank() } ?: device.address
        } catch (_: SecurityException) {
            try {
                device.address
            } catch (_: SecurityException) {
                activity.getString(R.string.ma_unknown_device)
            }
        }
}
