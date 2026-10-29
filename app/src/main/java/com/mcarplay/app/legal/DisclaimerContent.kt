package com.mcarplay.app.legal

import com.mcarplay.app.i18n.AppLanguage

/**
 * 免责声明正文（中英两版字面量集中在代码里，便于与版本号一起审阅改动；
 * 改任一版都要同步另一版并把 [DisclaimerPrefs.CURRENT_VERSION] +1）。
 *
 * 写作要点：
 * - **先讲范围与风险，再讲法律条款**——用户最需要知道的是「这东西测过什么、
 *   什么时候会出事」，把免责条款放最后更符合阅读习惯，也更容易真正被读到；
 * - 如实写清测试机型与已知限制（Android 14 以下没有「应用横屏」、行驶中断连的
 *   具体后果），空洞的免责声明没有意义；
 * - 明确写出依赖的系统权限（Shizuku shell、通知使用权），这是用户决定是否
 *   授权的前提；
 * - 明确非官方、无关联，避免让用户误认为与 Apple / 车厂有授权关系。
 *
 * 正文刻意**不用 Markdown 记号**（`**`、`#` 等）：这里直接进 TextView，
 * 那些符号会原样显示。分节标题由 UI 单独加粗。
 */
internal object DisclaimerContent {
    fun sectionsFor(language: AppLanguage.Language): List<Section> = if (language == AppLanguage.Language.EN) sectionsEn else sections

    /** 分节：标题加粗显示，正文常规。 */
    data class Section(
        val title: String,
        val body: String,
    )

    val sections: List<Section> =
        listOf(
            Section(
                title = "这是什么",
                body =
                    "mcarplay 是一个非官方的实验性项目：把小米 CarWith 的原生视频、声音和触控" +
                        "桥接到无线 CarPlay 接收端，显示的界面由 CarWith 提供。\n\n" +
                        "本项目与 Apple Inc.、小米及任何汽车厂商均无关联，未获得任何授权或认证，" +
                        "也不是 MFi 认证产品。「CarPlay」「AirPlay」是 Apple Inc. 的商标，" +
                        "此处仅用于说明兼容目标，不表示任何关联或背书。",
            ),
            Section(
                title = "开发中，可能随时出问题",
                body =
                    "当前为开发测试版本，功能、界面与协议实现都可能随时变更，可能存在未发现的" +
                        "缺陷。不承诺任何稳定性、兼容性或可用性。\n\n" +
                        "本轮 CarWith 桥接测试组合：\n" +
                        "· 手机：小米 17 Pro Max / HyperOS OS4.0.4.0.XPBCNXM\n" +
                        "· 组件：CarWith 4.0.20 / 小米 CarLife 8.8.4\n" +
                        "· 接收端：PA2353 Android 14 平板 / DiPlay 0.2.21\n\n" +
                        "已验证视频、触控和音乐播放；导航提示、暂停恢复、长时间稳定性和真实车机" +
                        "仍待验收。\n\n" +
                        "其他手机、其他 Android 版本、其他车型均未验证，可能完全无法工作，" +
                        "也可能出现画面异常、触摸错位或反复断连。\n\n" +
                        "「应用横屏」功能需要 Android 14 及以上系统；Android 13 及更早机型" +
                        "该功能不可用（设置面板会提示不支持）。",
            ),
            Section(
                title = "驾驶安全（请务必阅读）",
                body =
                    "本应用不是安全关键系统，不能替代原厂导航与车辆仪表。\n\n" +
                        "· 请在停车状态下完成连接、配对与所有设置；\n" +
                        "· 行驶过程中如需操作，必须由车上的乘客进行，驾驶员不得手持或操作手机；\n" +
                        "· 无线链路可能因信号干扰、系统休眠、热点切换、蓝牙重连等原因随时中断，" +
                        "表现为画面冻结、黑屏、触摸失灵或导航中断；\n" +
                        "· 行驶中断连可能导致错过路口、路线丢失或注意力分散。" +
                        "请始终以车机原厂导航作为安全冗余，并对实际驾驶行为负全部责任。",
            ),
            Section(
                title = "系统权限与数据",
                body =
                    "为了正常工作，本应用会请求并使用以下能力，请确认你理解后再授权：\n\n" +
                        "· Shizuku（shell 权限）：用于创建受信虚拟屏、注入触摸事件、写入系统" +
                        "兼容性设置（如强制应用横屏）、读取窗口与显示状态。本应用不会用它执行" +
                        "与本应用服务无关的命令，CarWith 主链路不依赖此权限；\n" +
                        "· 通知使用权 / 媒体会话：仅用于在车机桌面显示当前播放的音乐信息" +
                        "（歌名、歌手、封面、播放进度）；\n" +
                        "· 蓝牙：用于与接收端配对及无线控制链路；\n" +
                        "· 屏幕录制（投屏）授权：用于非原生音频路径的播放捕获。CarWith 原生音频" +
                        "不需要 mcarplay 额外申请录屏授权。\n\n" +
                        "配对信息、目标设备名称、壁纸与设置项仅保存在本机，不会上传到任何服务器；" +
                        "本应用不含统计分析或广告 SDK。",
            ),
            Section(
                title = "免责条款",
                body =
                    "本应用按「现状」提供，不附带任何明示或默示的担保，包括但不限于适销性、" +
                        "特定用途适用性与不侵权担保。\n\n" +
                        "在适用法律允许的最大范围内，作者与贡献者不对因使用或无法使用本应用而" +
                        "产生的任何直接或间接损失承担责任，包括但不限于：连接中断、导航或通话" +
                        "中断、数据丢失、设备异常、车辆系统异常，以及因驾驶中操作或分心导致的" +
                        "事故与损失。\n\n" +
                        "你是否使用本应用、如何配置与操作、以及在何种场景下使用，完全由你自行" +
                        "判断并自行承担风险。若你不同意上述任何一条，请点击「放弃使用」并卸载" +
                        "本应用。",
            ),
            Section(
                title = "开源与反馈",
                body =
                    "本项目为个人实验性作品，以 MIT 许可证开源，欢迎反馈问题，但不承诺修复时限。" +
                        "由于涉及协议实现与第三方 ROM 差异，部分问题可能无法解决。\n\n" +
                        "完整条款（非官方声明、商标归属、安全警告、已测试范围）见仓库根目录的 " +
                        "DISCLAIMER.md，与本页内容同源。",
            ),
        )

    /** 英文版：与中文版逐节对应，条款范围相同。 */
    val sectionsEn: List<Section> =
        listOf(
            Section(
                title = "What this is",
                body =
                    "mcarplay is an unofficial, experimental project: it bridges the native video, sound and touch of " +
                        "Xiaomi CarWith to a wireless CarPlay receiver, and the interface you see is provided by CarWith.\n\n" +
                        "This project is not affiliated with Apple Inc., Xiaomi or any car maker, has no authorization or " +
                        "certification from them, and is not an MFi-certified product. \"CarPlay\" and \"AirPlay\" are " +
                        "trademarks of Apple Inc., used here only to describe the compatibility target; no affiliation or " +
                        "endorsement is implied.",
            ),
            Section(
                title = "Under development; things may break at any time",
                body =
                    "This is a development test build. Features, interface and protocol implementation may change at any " +
                        "time, and there may be undiscovered defects. No stability, compatibility or availability is promised.\n\n" +
                        "CarWith bridge combination tested in this round:\n" +
                        "· Phone: Xiaomi 17 Pro Max / HyperOS OS4.0.4.0.XPBCNXM\n" +
                        "· Components: CarWith 4.0.20 / Xiaomi CarLife 8.8.4\n" +
                        "· Receiver: PA2353 Android 14 tablet / DiPlay 0.2.21\n\n" +
                        "Video, touch and music playback have been verified; navigation prompts, pause and resume, long-run " +
                        "stability and real head units still need acceptance testing.\n\n" +
                        "Other phones, other Android versions and other car models are unverified. They may not work at all, " +
                        "or may show picture problems, misplaced touches or repeated disconnects.\n\n" +
                        "Landscape apps requires Android 14 or later; on Android 13 and earlier it is unavailable (the settings " +
                        "panel says it is unsupported).",
            ),
            Section(
                title = "Driving safety (please read)",
                body =
                    "This app is not a safety-critical system and cannot replace the factory navigation or vehicle " +
                        "instruments.\n\n" +
                        "· Connect, pair and change all settings while parked;\n" +
                        "· If anything must be done while driving, a passenger must do it; the driver must not hold or " +
                        "operate the phone;\n" +
                        "· The wireless link can drop at any time because of interference, system sleep, hotspot switching or " +
                        "Bluetooth reconnection, showing up as a frozen or black screen, unresponsive touch or interrupted " +
                        "navigation;\n" +
                        "· A disconnect while driving can make you miss a turn, lose the route or be distracted. Always keep " +
                        "the factory navigation as a safety backup, and you are fully responsible for how you drive.",
            ),
            Section(
                title = "System permissions and data",
                body =
                    "To work, this app requests and uses the following; make sure you understand them before allowing:\n\n" +
                        "· Shizuku (shell permission): creates trusted virtual displays, injects touch events, writes system " +
                        "compatibility settings (such as forcing apps to landscape) and reads window and display state. The " +
                        "app does not use it to run commands unrelated to its own function, and the CarWith main path does " +
                        "not need it;\n" +
                        "· Notification access / media sessions: used only to show the currently playing music on the car " +
                        "home screen (title, artist, cover, progress);\n" +
                        "· Bluetooth: for pairing with the receiver and the wireless control link;\n" +
                        "· Screen recording (screen capture) permission: for playback capture on the non-native audio path. " +
                        "Native CarWith audio does not need mcarplay to request it.\n\n" +
                        "Pairing information, target device names, wallpaper and settings are stored only on this phone and " +
                        "are never uploaded to any server; the app contains no analytics or advertising SDK.",
            ),
            Section(
                title = "Disclaimer of liability",
                body =
                    "This app is provided \"as is\", without warranty of any kind, express or implied, including but not " +
                        "limited to merchantability, fitness for a particular purpose and non-infringement.\n\n" +
                        "To the maximum extent permitted by applicable law, the authors and contributors are not liable for " +
                        "any direct or indirect loss arising from the use of or inability to use this app, including but not " +
                        "limited to: dropped connections, interrupted navigation or calls, data loss, device faults, vehicle " +
                        "system faults, and accidents or losses caused by operating the phone or being distracted while " +
                        "driving.\n\n" +
                        "Whether you use this app, how you configure and operate it, and in which situations, is entirely your " +
                        "own decision and at your own risk. If you do not agree with any of the above, tap Don't use and " +
                        "uninstall the app.",
            ),
            Section(
                title = "Open source and feedback",
                body =
                    "This is a personal experimental project, open source under the MIT license. Feedback is welcome, but " +
                        "no fix timeline is promised. Because it involves protocol implementation and differences between " +
                        "third-party ROMs, some problems may not be solvable.\n\n" +
                        "The full terms (unofficial status, trademark ownership, safety warnings, tested scope) are in " +
                        "DISCLAIMER.md at the repository root, from the same source as this page.",
            ),
        )
}
