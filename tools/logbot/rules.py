"""Fixed knowledge used by the rule engine. Keep in sync with diagnostics/ConnectionTimeline.kt and docs/DIAGNOSTICS.md."""

from __future__ import annotations

import re
from dataclasses import dataclass

STAGE_LABELS = {
    "PRECHECK": "连接准备",
    "BLUETOOTH": "等待车机蓝牙",
    "WIFI": "连接车机网络",
    "DISCOVERY": "解析接收端",
    "PAIRING": "AirPlay 配对",
    "SETUP": "建立 CarPlay 会话",
    "CARWITH": "等待 CarWith 首帧",
    "STREAMING": "传输画面",
    "SELF_TEST": "CarWith 独立自检（非 CarPlay）",
}

# code -> (结论, 建议)。结论只说日志能证明的事。
CODE_ADVICE: dict[str, tuple[str, str]] = {
    "BT_PERMISSION": ("附近设备/蓝牙权限被拒绝。", "在系统设置里允许 mcarplay 的「附近设备」权限后重试。"),
    "BT_DISABLED": ("蓝牙处于关闭状态。", "开启蓝牙，确认手机已与接收端配对后重连。"),
    "BT_LINK_CLOSED": (
        "会话建立前蓝牙控制链路已结束。",
        "检查接收端是否仍在等待连接；重启接收端后重连。部分接收端在网络会话接管后会正常断开蓝牙，这本身不是故障。",
    ),
    "ENDPOINT_PENDING": ("蓝牙已连接，仍在等待接收端提供热点和地址信息，不是终止性失败。", "保持接收端在 CarPlay 等待界面，稍等后看是否继续。"),
    "LOCATION_REQUIRED": ("Android 12 加入热点需要位置权限和位置服务。", "允许位置权限并打开系统位置服务后重试。"),
    "WIFI_PERMISSION": ("「附近 WLAN 设备」权限被拒绝（Android 13+）。", "在应用权限中允许「附近 WLAN 设备」后重试。"),
    "WIFI_JOIN_FAILED": (
        "未能加入或绑定车机热点。",
        "确认 WLAN 已开启、附近设备权限已允许，并留意系统的「加入网络」确认框；接收端热点需正常广播。",
    ),
    "WIFI_LOST": ("连接过程中车机网络丢失。", "检查接收端热点是否掉线、手机是否自动切回其它 WLAN，然后重连。"),
    "PAIRING_FAILED": ("AirPlay 配对没有完成。", "查看接收端屏幕提示；重启接收端后重试，必要时在接收端删除旧的配对记录。"),
    "SETUP_FAILED": ("CarPlay 会话（SETUP）没有建立。", "需要维护者对照报告中的 SETUP 响应和接收端能力；请附上接收端型号和固件版本。"),
    "CARWITH_FAILED": (
        "CarWith 没有完成出流。",
        "核对 CarWith / 小米 CarLife 组件版本、原厂连接开关和授权；先用应用内「CarWith 自检」确认能单独出画面。",
    ),
    "CARWITH_ENTRY_BLOCKED": (
        "应用在后台时系统拦截了 CarWith 入口，CarWith 的监听端口一直没有出现。",
        "小米手机请在应用权限里允许「后台弹出界面」，或连接时保持 mcarplay 在前台，然后重连。",
    ),
    "CARWITH_ENTRY_NOT_OBSERVED": (
        "通知监听可用，但等待七路连接期间没有观察到 CarWith 通知，端口也没有就绪。",
        "这不能证明 CarWith 内部从未执行，但入口很可能没有完成初始化。保持 mcarplay 在前台，从 CarWith 设置手动选择百度 CarLife 无线连接后重试。",
    ),
    "CARWITH_ENTRY_STATUS_UNKNOWN": (
        "七路端口未就绪，且本进程没有绑定通知监听器，无法判断 CarWith 入口是否执行。",
        "从 CarWith 设置手动发起百度 CarLife 无线连接；如允许 mcarplay 的通知使用权，下一次报告可区分入口是否启动到通知阶段。",
    ),
    "CARWITH_LISTENERS_PENDING": (
        "已经观察到 CarWith 通知，但七路 CarLife 监听端口仍未就绪。",
        "CarWith 至少启动到了通知阶段；检查 CarWith/小米 CarLife 版本，并从 CarWith 设置重新选择百度 CarLife 无线连接。",
    ),
    "CARWITH_PROTOCOL_SILENT": (
        "七路端口都已连上，但命令通道没有收到任何 CarLife 协议帧。",
        "端口监听不等于有效投屏会话；停止现有 CarWith 连接，再从 CarWith 设置重新发起百度 CarLife 无线连接并复测。",
    ),
    "CARWITH_BUSY": ("CarWith 正被独立自检占用。", "先停止自检，再启动正式 CarPlay 连接。"),
    "VIDEO_CLOSED": (
        "视频连接已结束。已发过帧时多为正常结束（例如接收端关闭）；一帧都没发出则是失败。",
        "仅凭连接重置不能断定接收端崩溃；请结合接收端当时的画面和网络情况判断。",
    ),
    "AUDIO_UNAVAILABLE": ("音频未能启用，画面可以继续。", "需要维护者核对音频格式协商；请说明接收端是否有声音输出设置。"),
    "CONNECTION_INCOMPLETE": ("连接在配对前结束，日志里没有更具体的原因。", "看最后所处阶段和接收端提示；报告中若有 EXCEPTION 记录会附堆栈。"),
    "DETECTION_TIMEOUT": ("接收端适配检测达到 8 分钟上限。", "未确定的项目仍是「未验证」；检查车机后可重新检测。"),
    "UNCAUGHT_EXCEPTION": ("应用线程发生未捕获异常（不一定导致进程退出）。", "请把完整诊断报告发给维护者，不要只发界面截图。"),
    "USER_STOP": ("用户主动停止，不是连接故障。", ""),
    "SUPERSEDED": ("新的连接尝试替代了上一轮，不是故障。", ""),
}

# 环境中被拒绝时值得提示的权限。
PERMISSION_HINTS = {
    "BLUETOOTH_CONNECT": "附近设备（蓝牙连接）",
    "BLUETOOTH_SCAN": "附近设备（蓝牙扫描）",
    "NEARBY_WIFI_DEVICES": "附近 WLAN 设备",
    "ACCESS_FINE_LOCATION": "精确位置（Android 12 加入热点需要）",
}
# 只在这些 SDK 上需要的权限；其余版本未授予不算问题。
PERMISSION_SDKS = {"ACCESS_FINE_LOCATION": range(31, 33)}


@dataclass(frozen=True)
class LogPattern:
    key: str
    severity: str
    regex: re.Pattern
    title: str
    advice: str
    min_count: int = 1
    exclude: re.Pattern | None = None


LOG_PATTERNS = [
    LogPattern(
        "oom",
        "error",
        re.compile(r"OutOfMemoryError"),
        "出现内存不足（OutOfMemoryError）。",
        "关闭其它占内存的应用后重试，并把报告发给维护者。",
    ),
    LogPattern(
        "enonet",
        "warn",
        re.compile(r"ENONET|Machine is not on the network"),
        "多次出现 ENONET（套接字不在网络上）。",
        "通常是进程的网络绑定已失效，而不是配对协议问题：断开重连车机热点，或重启 mcarplay 后重试。",
        min_count=3,
    ),
    LogPattern(
        "refused",
        "warn",
        re.compile(r"ECONNREFUSED|Connection refused"),
        "连接接收端端口被拒绝。",
        "接收端可能还没进入 CarPlay 等待状态，或端口已关闭；重启接收端后重连。",
        exclude=re.compile(r"CARLIFE:"),
    ),
    LogPattern(
        "carlife_refused",
        "info",
        re.compile(r"CARLIFE:.*(?:ECONNREFUSED|Connection refused)"),
        "CarWith 本地端口暂未就绪，mcarplay 在重试。",
        "启动时短暂重试是正常的；最终结论以 CARWITH_* 错误码和通知探针字段为准。",
    ),
    LogPattern(
        "reset",
        "info",
        re.compile(r"ECONNRESET|Connection reset|Broken pipe|EPIPE"),
        "出现连接重置 / Broken pipe。",
        "这只说明连接被对端或网络中断，不能单独证明接收端崩溃。",
    ),
    LogPattern(
        "timeout",
        "info",
        re.compile(r"SocketTimeoutException|ETIMEDOUT|timed out"),
        "出现网络超时。",
        "检查车机热点信号和手机是否在省电模式下限制了网络。",
        min_count=2,
    ),
    LogPattern(
        "video_eof",
        "warn",
        re.compile(r"VIDEO: 车机关闭了屏幕数据口"),
        "接收端主动关闭了屏幕数据口。",
        "若画面刚出现就断开，常见于接收端解码不了当前码流；可附上 `VIDEO: CarWith 参数集` 等行给维护者。",
    ),
    LogPattern(
        "screen_off",
        "warn",
        re.compile(r"STREAM_END_SCREEN_OFF"),
        "推流在手机熄屏期间结束。",
        "在系统电池设置里把 mcarplay 设为「无限制」，并允许后台运行，再测试熄屏是否断开。",
    ),
    LogPattern(
        "battery_opt",
        "warn",
        re.compile(r"POWER: 未豁免电池优化"),
        "mcarplay 没有被豁免电池优化。",
        "锁屏后系统可能冻结 mcarplay 并断网：在系统电池设置里把 mcarplay 设为「无限制」。",
    ),
    LogPattern(
        "transcode",
        "info",
        re.compile(r"VIDEO: 转码统计"),
        "本轮启用了手机端转码。",
        "说明接收端持续要求关键帧或尺寸不匹配，已切换转码兜底；若仍无画面，请附上转码统计行。",
    ),
]

EXIT_REASON_HINTS = {
    "LOW_MEMORY": "系统因内存不足结束过本应用。",
    "CRASH": "应用曾发生 Java 崩溃。",
    "CRASH_NATIVE": "应用曾发生原生（native）崩溃。",
    "ANR": "应用曾发生 ANR（界面无响应）。",
    "SIGNALED": "进程曾被系统信号结束（可能是省电/后台清理）。",
}

EXCEPTION_LINE = re.compile(r"\b((?:java|kotlin|android)\.[\w.$]*(?:Exception|Error))\b(?::\s*(.{0,160}))?")
