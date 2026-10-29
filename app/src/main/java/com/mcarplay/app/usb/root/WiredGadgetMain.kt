package com.mcarplay.app.usb.root

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import com.mcarplay.app.usb.AppleUsbIdentity
import com.mcarplay.app.usb.Ep0Reply
import com.mcarplay.app.usb.FfsEventType
import com.mcarplay.app.usb.FunctionFs
import com.mcarplay.app.usb.GadgetPlan
import com.mcarplay.app.usb.UsbRoleNode
import com.mcarplay.app.usb.UsbRoleNodes
import com.mcarplay.app.usb.UsbSetupPacket
import com.mcarplay.app.usb.WiredSwitchMachine
import com.mcarplay.app.usb.WiredSwitchMachine.Action
import com.mcarplay.app.usb.WiredSwitchMachine.Event
import com.mcarplay.app.usb.WiredSwitchMachine.State
import java.io.File
import java.io.FileDescriptor
import kotlin.system.exitProcess

/**
 * 有线 CarPlay 台架工具：以 root 经 `app_process` 运行（不在 App 进程里），负责阶段 0–2：
 * 探测本机能力、冒充 iPhone 等待车机的角色切换请求、把手机切成 USB 主机并报告车机接口。
 *
 * ```
 * adb shell su -c 'CLASSPATH=$(pm path com.mcarplay.app | cut -d: -f2) \
 *   app_process /system/bin com.mcarplay.app.usb.root.WiredGadgetMain probe'
 * ```
 * 子命令：`probe`、`plan`、`up [--timeout 秒] [--pid 0x12a8]`、`down [--usb-config 进入前的值]`。
 * remote 包把 `com.mcarplay.app` 换成 `com.mcarplay.app.rd`（类名不变）。
 * `up` 会停掉 Android 的 USB gadget，USB adb 随之断开，请先切到无线 adb。
 * 输出均以 `WIRED:` 开头；不输出本机任何标识（序列号每次随机）。
 */
object WiredGadgetMain {
    private const val EXIT_ATTACHED = 0
    private const val EXIT_ERROR = 1
    private const val EXIT_ROLLED_BACK = 2
    private const val POLL_MILLIS = 200
    private const val USB_DEVICES = "/sys/bus/usb/devices"
    private val usbDeviceName = Regex("\\d+-[\\d.]+")

    @JvmStatic
    fun main(args: Array<String>) {
        val code =
            try {
                when (args.firstOrNull()) {
                    "probe" -> probe()
                    "plan" -> printPlan(identityFrom(args))
                    "up" -> Session(identityFrom(args), option(args, "--timeout")?.toLong() ?: 60L).run()
                    "down" -> down(option(args, "--usb-config").orEmpty())
                    else -> usage()
                }
            } catch (t: Throwable) {
                out("错误：${t.javaClass.simpleName}: ${t.message}")
                EXIT_ERROR
            }
        exitProcess(code)
    }

    private fun usage(): Int {
        out("用法：probe | plan | up [--timeout 秒] [--pid 0x12a8] | down [--usb-config 进入前的 sys.usb.config]")
        return EXIT_ERROR
    }

    private fun probe(): Int {
        out("uid=${Os.getuid()} sys.usb.config=${getprop("sys.usb.config")} sys.usb.controller=${getprop("sys.usb.controller")}")
        out("UDC=${File("/sys/class/udc").list()?.sorted()}")
        out("configfs gadgets=${File(GadgetPlan.CONFIGFS_ROOT).list()?.sorted()}")
        // 每个 gadget 占没占 UDC、各配置挂了哪些函数（判断由 init 还是 Gadget HAL 管理）；不输出序列号
        File(GadgetPlan.CONFIGFS_ROOT).listFiles()?.sortedBy { it.name }?.forEach { gadget ->
            val functions =
                File(gadget, "configs").listFiles()?.sortedBy { it.name }?.joinToString(" ") { config ->
                    config.name + "=" +
                        config
                            .listFiles()
                            ?.filter { it.isDirectory && it.name != "strings" }
                            ?.map { it.name }
                            ?.sorted()
                }
            out(
                "gadget ${gadget.name} UDC=${File(gadget, "UDC").readTextOrNull()?.trim()} " +
                    "idVendor=${File(gadget, "idVendor").readTextOrNull()?.trim()} configs: $functions",
            )
        }
        out("svc usb getFunctions=${sh(listOf("svc usb getFunctions 2>&1")).second.trim()}")
        sh(
            listOf("getprop | grep -iE 'usb\\.(config|state|controller|functions)|gadget'"),
        ).second.lines().filter { it.isNotBlank() }.forEach {
            out("prop $it")
        }
        out("functionfs 内核支持=${File("/proc/filesystems").readTextOrNull()?.contains("functionfs") == true}")
        out("cdc_ncm 主机驱动已加载=${File("/sys/module/cdc_ncm").exists()} usbnet=${File("/sys/module/usbnet").exists()}")
        val nodes = discoverRoleNodes()
        if (UsbRoleNodes.forAutomaticSwitch(nodes).isEmpty()) out("没有可自动写入的角色切换节点：原生有线 CarPlay 在本机基本不可行")
        nodes.forEach {
            val mode = if (it.source.autoWritable) "" else "（只读：up/down 不写入）"
            out("角色节点 ${it.source} ${it.path} 当前=${File(it.path).readTextOrNull()?.trim()}$mode")
        }
        typecPorts().forEach { port ->
            val roles =
                listOf(
                    "data_role",
                    "power_role",
                    "power_operation_mode",
                ).joinToString { "$it=${File(port, it).readTextOrNull()?.trim()}" }
            out("typec ${port.name} $roles")
        }
        return EXIT_ATTACHED
    }

    private fun printPlan(identity: AppleUsbIdentity): Int {
        val plan = GadgetPlan(identity)
        (plan.prepare() + "# 写入 ep0 描述符" + plan.bind("<udc>") + plan.teardown(getprop("sys.usb.config"))).forEach(::out)
        return EXIT_ATTACHED
    }

    private fun down(usbConfig: String): Int {
        UsbRoleNodes.forAutomaticSwitch(discoverRoleNodes()).forEach { sh(listOf(it.deviceCommand() + " 2>/dev/null || true")) }
        val (code, output) = sh(GadgetPlan(AppleUsbIdentity()).teardown(usbConfig))
        out("已回滚 exit=$code ${output.trim()}")
        return if (code == 0) EXIT_ATTACHED else EXIT_ERROR
    }

    /** 一次 `up`：所有可变状态都在这里，关停钩子与主循环共用 [lock]。 */
    private class Session(
        private val identity: AppleUsbIdentity,
        timeoutSeconds: Long,
    ) {
        private val plan = GadgetPlan(identity)
        private val previousUsbConfig = getprop("sys.usb.config")
        private val machine =
            WiredSwitchMachine(UsbRoleNodes.forAutomaticSwitch(discoverRoleNodes()), awaitRoleSwitchMillis = timeoutSeconds * 1000)
        private val lock = Object()
        private var ep0: FileDescriptor? = null
        private var currentSetup: UsbSetupPacket? = null
        private var baselineDevices: Set<String> = emptySet()

        fun run(): Int {
            val udc = File("/sys/class/udc").list()?.sorted()?.firstOrNull() ?: error("没有 UDC")
            out("进入前 sys.usb.config=$previousUsbConfig UDC=$udc PID=${GadgetPlan.hex(identity.productId)}")
            Runtime.getRuntime().addShutdownHook(
                Thread {
                    synchronized(lock) {
                        if (machine.state != State.ACCESSORY_ATTACHED) execute(machine.on(Event.Failure("进程被终止")))
                    }
                },
            )
            synchronized(lock) {
                checkShell(plan.prepare(), "建立 gadget")
                val fd = Os.open(plan.ep0Path, OsConstants.O_RDWR, 0).also { ep0 = it }
                writeFully(fd, FunctionFs.iapDescriptors(identity))
                writeFully(fd, FunctionFs.strings(identity.iapInterfaceName))
                checkShell(plan.bind(udc), "绑定 UDC")
                baselineDevices = usbDevices()
                execute(machine.on(Event.GadgetBound(now())))
            }
            while (true) {
                val fd = synchronized(lock) { ep0.takeIf { machine.state == State.GADGET_UP } }
                if (fd != null) pumpEp0(fd) else Thread.sleep(POLL_MILLIS.toLong())
                synchronized(lock) {
                    if (machine.state == State.AWAITING_ACCESSORY) {
                        val added = usbDevices() - baselineDevices
                        if (added.isNotEmpty()) {
                            added.forEach(::describeDevice)
                            execute(machine.on(Event.AccessoryEnumerated))
                        }
                    }
                    execute(machine.on(Event.Tick(now())))
                    when (machine.state) {
                        State.ACCESSORY_ATTACHED -> {
                            out("完成：手机保持 USB 主机，执行 down 可回滚")
                            return EXIT_ATTACHED
                        }
                        State.ROLLED_BACK -> {
                            out("已回滚：${machine.failureReason}")
                            return EXIT_ROLLED_BACK
                        }
                        else -> Unit
                    }
                }
            }
        }

        private fun pumpEp0(fd: FileDescriptor) {
            val pollfd =
                StructPollfd().apply {
                    this.fd = fd
                    events = OsConstants.POLLIN.toShort()
                }
            if (Os.poll(arrayOf(pollfd), POLL_MILLIS) <= 0) return
            val buffer = ByteArray(FunctionFs.EVENT_SIZE * 4)
            val read = Os.read(fd, buffer, 0, buffer.size)
            synchronized(lock) {
                for (event in FunctionFs.parseEvents(buffer, read)) {
                    if (event.type == FfsEventType.SETUP) {
                        currentSetup = event.setup
                        execute(machine.on(Event.Setup(event.setup!!)))
                    } else {
                        out("FFS ${event.type}")
                    }
                }
            }
        }

        private fun execute(actions: List<Action>) {
            for (action in actions) {
                when (action) {
                    is Action.Log -> out(action.message)
                    is Action.Reply -> reply(action.reply)
                    is Action.ApplyHostRole -> execute(machine.on(Event.HostRoleResult(applyHostRole(action.node), now())))
                    is Action.Rollback -> {
                        out("回滚：${action.reason}")
                        closeEp0()
                        action.touchedNodes.asReversed().forEach { sh(listOf(it.deviceCommand() + " 2>/dev/null || true")) }
                        sh(plan.teardown(previousUsbConfig))
                        execute(machine.on(Event.RollbackDone))
                    }
                }
            }
        }

        private fun applyHostRole(node: UsbRoleNode): Boolean {
            // ep0 仍打开时 functionfs 无法卸载
            closeEp0()
            sh(plan.teardown(previousUsbConfig, restoreAndroid = false))
            val (code, output) = sh(listOf(node.hostCommand()))
            val now = File(node.path).readTextOrNull()?.trim().orEmpty()
            // typec 节点读回形如 "[host] device"，其余直接是当前值
            val ok = code == 0 && (now == node.hostValue || now.contains("[${node.hostValue}]"))
            out("切主机 ${node.source} ${node.path} exit=$code 读回=$now ${output.trim()}")
            val powerRoles = typecPorts().map { File(it, "power_role").readTextOrNull()?.trim() }
            if (powerRoles.any(UsbRoleNodes::isPowerSource)) {
                // 手机成了 VBUS 供电方，会与车机对顶：立刻写回设备值，不等状态机回滚
                sh(listOf(node.deviceCommand() + " 2>/dev/null || true"))
                out("切主机后 power_role=$powerRoles：手机在向 VBUS 供电，已立即撤回")
                return false
            }
            return ok
        }

        /** FunctionFS：OUT 用读完成状态阶段，IN 用写；朝错误方向读写即 STALL（零长度读写需台架确认确实下发了系统调用）。 */
        private fun reply(reply: Ep0Reply) {
            val fd = ep0 ?: return
            val setup = currentSetup ?: return
            try {
                when (reply) {
                    is Ep0Reply.AckOut -> Os.read(fd, ByteArray(reply.length), 0, reply.length)
                    is Ep0Reply.WriteIn -> writeFully(fd, reply.data)
                    Ep0Reply.Stall ->
                        if (setup.isDeviceToHost) Os.read(fd, ByteArray(0), 0, 0) else Os.write(fd, ByteArray(0), 0, 0)
                }
            } catch (e: ErrnoException) {
                if (reply != Ep0Reply.Stall) out("ep0 应答失败：${e.message}")
            }
        }

        private fun closeEp0() {
            ep0?.let { runCatching { Os.close(it) } }
            ep0 = null
        }

        private fun checkShell(
            commands: List<String>,
            step: String,
        ) {
            val (code, output) = sh(commands)
            if (code != 0) {
                execute(machine.on(Event.Failure("$step 失败 exit=$code ${output.trim()}")))
                error("$step 失败")
            }
        }
    }

    private fun describeDevice(name: String) {
        val dir = File(USB_DEVICES, name)

        fun attr(
            base: File,
            key: String,
        ) = File(base, key).readTextOrNull()?.trim()
        out("车机设备 $name idVendor=${attr(dir, "idVendor")} idProduct=${attr(dir, "idProduct")} product=${attr(dir, "product")}")
        dir.listFiles()?.filter { it.name.startsWith("$name:") }?.sortedBy { it.name }?.forEach { intf ->
            val driver = File(intf, "driver").takeIf { it.exists() }?.canonicalFile?.name
            out(
                "  接口 ${intf.name} class=${attr(intf, "bInterfaceClass")} sub=${attr(intf, "bInterfaceSubClass")} " +
                    "proto=${attr(intf, "bInterfaceProtocol")} driver=$driver",
            )
        }
    }

    private fun typecPorts(): List<File> = File("/sys/class/typec").listFiles()?.filter { it.name.matches(Regex("port\\d+")) }.orEmpty()

    private fun usbDevices(): Set<String> =
        File(USB_DEVICES)
            .list()
            ?.filter { usbDeviceName.matches(it) }
            ?.toSet()
            .orEmpty()

    private fun discoverRoleNodes(): List<UsbRoleNode> {
        val existing =
            UsbRoleNodes.searchRoots.flatMap { root ->
                File(root).listFiles().orEmpty().flatMap { child ->
                    UsbRoleNodes.leafNames
                        .map { File(child, it) }
                        .filter { it.exists() }
                        .map { it.path }
                }
            }
        return UsbRoleNodes.fromPaths(existing)
    }

    private fun identityFrom(args: Array<String>): AppleUsbIdentity {
        val pid = option(args, "--pid")?.removePrefix("0x")?.toInt(16) ?: AppleUsbIdentity.DEFAULT_IPHONE_PRODUCT_ID
        return AppleUsbIdentity(productId = pid)
    }

    private fun option(
        args: Array<String>,
        name: String,
    ): String? = args.indexOf(name).takeIf { it >= 0 && it + 1 < args.size }?.let { args[it + 1] }

    private fun getprop(key: String): String = sh(listOf("getprop $key")).second.trim()

    private fun sh(commands: List<String>): Pair<Int, String> {
        val process = ProcessBuilder("sh", "-c", commands.joinToString("\n")).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor() to output
    }

    private fun writeFully(
        fd: FileDescriptor,
        bytes: ByteArray,
    ) {
        var offset = 0
        while (offset < bytes.size) offset += Os.write(fd, bytes, offset, bytes.size - offset)
    }

    private fun now(): Long = System.nanoTime() / 1_000_000

    private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()

    private fun out(line: String) {
        println("WIRED: $line")
        System.out.flush()
    }
}
