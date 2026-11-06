# Pixel 5 / AOSP 上 CarWith 七口恢复说明（2026-11-06）

本文记录一次台架排查：为什么 Pixel 5 上的 CarWith 一直没有出现七个 CarLife 监听端口，以及最终怎样让端口出现。
结论只适用于受控测试台，不是 mcarplay 的产品修复、无 root 方案或真车兼容性证明。

## 结论摘要

七个端口由 CarWith/CarLife 创建并监听，mcarplay 只是客户端。`7240` 返回 `ECONNREFUSED` 时，mcarplay 的连接代码
还没有进入协议阶段，不能把它当成 mcarplay 的 TCP 或 CarLife 解析失败。

本次端口不出现有三层原因，必须同时处理：

1. **CarWith 在 AOSP 上尝试创建 MIUI 私有窗口，进程被拒绝后崩溃或提前退出。**
2. **绕开正常 UI 直接启动服务后，`ConnectTypeUtil` 仍为 `none`，没有进入 CarLife Wi-Fi AP 准备路径。**
3. **mcarplay 切到前台启动接收端时，CarWith 的 `CastingActivity.onStop()` 主动拆掉投屏并清理连接。**

台架上的恢复方式是：用 Frida 临时修改 CarWith 的连接状态、跳过两个 AOSP 不支持的窗口辅助调用，并在 CarLife
会话存续期间阻止 `onStop()` 清链；随后由 root 启动 CarWith 的 `CarlinkService`。处理后七口持续监听，
mcarplay 能附着七路并持续收到、发送视频。

## 测试环境

- 发射端：Google Pixel 5，LineageOS/AOSP Android 16。
- CarWith：`3.0.1-20240722`。
- 接收端：vivo Pad Air 上的 DiPlay。
- 桥接端：mcarplay debug 构建。
- 设备序列、无线 ADB 端口、配对码、手机和接收端 IP 均不记录；复现时使用 `<device>`、`<phone-ip>` 和
  `<receiver-ip>` 占位。

这不是 HyperOS，也不是把 OS1 整套系统刷到 Pixel 5。所谓“OS1 适配”在这里仅指：台架临时补齐 CarWith
在 MIUI/OS1 上通常已经具备的运行条件，使低版本 CarWith 能在 AOSP 台架上完成一次 CarLife 投屏。

## 七个端口是什么

| 端口 | 用途 |
| ---: | --- |
| `7240` | 命令/控制通道 |
| `8240` | 视频通道 |
| `9240` | 媒体音频 |
| `9241` | TTS 音频 |
| `9242` | Voice 音频 |
| `9340` | 触摸/按键 |
| `9440` | 数据通道 |

七路通常一起建立。`7240` 是第一个必要的连接点，所以它拒绝连接时，其他通道也不会进入 CarLife 握手。

## 现象与最早失败点

现象是 CarWith 没有可用投屏会话，mcarplay 日志反复出现 `7240 ECONNREFUSED`。设备上执行：

```bash
adb -s <device> shell "su -c 'ss -ltn'" |
  rg ':(7240|8240|9240|9241|9242|9340|9440)\b'
```

没有任何七口监听。`ECONNREFUSED` 的含义是目标端口没有监听者；此时失败发生在 mcarplay 建立 TCP 之前，
因此不能通过修改 CarLife 协议、认证、AES 或视频解析来解决。

## 为什么端口没有出现

### 1. CarWith 使用了 AOSP 不接受的 MIUI 私有窗口

CarWith 会通过 `createWindowContext()` 和 `WindowManager.addView()` 创建窗口类型 `2998`：

```text
com.carwith.launcher.wms.a.r()
    -> createWindowContext(2998)
    -> addView(DockerView, type=2998)
```

类型 `2998` 是 MIUI 私有窗口类型。AOSP WindowManager 不授予第三方应用该类型，异常为：

```text
android.view.WindowManager$BadTokenException:
    permission denied for window type 2998
```

本次还在以下两个 1×1 辅助窗口路径观察到同类失败：

```text
com.carwith.launcher.e.c()          // type 2006, launcher refresh helper
com.carwith.common.utils.d.i()     // type 2006, surface refresh helper
```

这些窗口不是 CarLife 媒体协议的一部分，但异常发生在 CarWith 进程内。进程退出后，CarLife 监听线程不会启动，
外部只会看到七口不存在和 `7240 ECONNREFUSED`。

因此第一层不是“mcarplay 没连上”，而是**源端没有活到能监听七口的状态**。

### 2. 直接启动服务绕过了正常 UI 的连接状态

CarWith 的正常无线 CarLife AP 流程会由 `CarlifeApConnectActivity.realStartCarLifeConnect()` 设置：

```text
ConnectTypeUtil.a = "wifi_ap_carlife_connect"
ConnectTypeUtil.b = true
```

本次没有走完整 UI，而是直接以 `startType=4` 启动服务，所以这个状态没有被设置，仍为默认的 `none`。
`CarlinkService.prepareCast()` 只在以下情况继续：

```text
ConnectTypeUtil.i()  // 类型包含 usb
ConnectTypeUtil.j()  // 类型包含 wifi
```

`none` 两个条件都不满足，正常的 CarLife 准备不会完成，随后表现为超时或没有后续视频初始化。这里要区分两个字符串：

| 层级 | 本流程使用的值 | 作用 |
| --- | --- | --- |
| Intent extra `carlife_wireless_type` | `Baidu_ap` | 告诉 `CarlinkService` 进入 CarLife AP 分支 |
| `ConnectTypeUtil.a` | `wifi_ap_carlife_connect` | 让 `prepareCast()`、生命周期和断开逻辑按 CarLife Wi-Fi AP 处理 |

只有 `Baidu_ap` 而没有 `wifi_ap_carlife_connect`，服务虽然收到启动命令，仍可能停在错误状态。

### 3. mcarplay 切到前台时 CarWith 主动清链

即使前两层已经处理，七口仍可能在 mcarplay 进入前台后立即消失。原因是 CarWith 的
`CastingActivity.onStop()` 在亮屏、已解锁且交互状态下调用 `e1()`。`e1()` 会：

1. 清理当前连接；
2. 在 CarLife 模式执行 `ConnectTypeUtil.k("none")`；
3. 调用 `CarlinkService.k()` 结束投屏状态机；
4. 执行 `finishAndRemoveTask()`。

这适合手机端 CarWith 的正常交互，却和当前台架拓扑冲突：mcarplay 必须在前台启动接收端并连接源端，而 CarWith
把它视为“用户离开了投屏”。

现场 Frida 输出重复确认了这条路径：

```text
[preserve] keep cast while another foreground app is visible: wifi_ap_carlife_connect
[preserve] ignore connection type reset none (was wifi_ap_carlife_connect)
```

因此第三层是**CarWith 自己拆链**，不是 mcarplay 主动断开。

## 最小 Frida hook 做了什么

使用的脚本是工作区中的 `.claude/hook-carwith-minimal-preserve.js`，不纳入提交。它只处理上述阻塞，不屏蔽媒体、
软件更新、通知或其他无关系统能力。旧的宽范围 CarWith hook 会同时修改媒体和更新路径，不适合作为协议排查证据。

核心逻辑如下：

```javascript
Java.perform(function () {
  var q = Java.use("com.carwith.common.utils.q");
  var cls = q.class;

  var connectionType = cls.getDeclaredField("a");
  connectionType.setAccessible(true);
  connectionType.set(
    null,
    Java.use("java.lang.String").$new("wifi_ap_carlife_connect")
  );

  var active = cls.getDeclaredField("b");
  active.setAccessible(true);
  active.set(null, Java.use("java.lang.Boolean").valueOf(true));

  Java.use("com.carwith.launcher.wms.a").r.overload().implementation = function () {
    console.log("[preserve] skip unsupported window type 2998");
  };

  Java.use("com.carwith.launcher.e").c.overload().implementation = function () {
    console.log("[preserve] skip unsupported window type 2006 (launcher)");
  };

  Java.use("com.carwith.common.utils.d").i.overload().implementation = function () {
    console.log("[preserve] skip unsupported window type 2006 (surface)");
  };

  var castingActivity = Java.use("com.miui.carlink.castfwk.CastingActivity");
  var disconnect = castingActivity.e1.overload();
  disconnect.implementation = function () {
    var current = q.a();
    if (current.indexOf("carlife") >= 0) {
      console.log("[preserve] keep cast while another foreground app is visible: " + current);
      return;
    }
    return disconnect.call(this);
  };

  var setConnectionType = q.k.overload("java.lang.String");
  setConnectionType.implementation = function (next) {
    var current = q.a();
    if (next === "none" && current.indexOf("carlife") >= 0) {
      console.log("[preserve] ignore connection type reset none (was " + current + ")");
      return;
    }
    return setConnectionType.call(this, next);
  };
});
setInterval(function () {}, 1000);
```

各项作用：

| 修改 | 目的 |
| --- | --- |
| `q.a = wifi_ap_carlife_connect` | 补回正常 CarLife AP UI 会设置的连接类型 |
| `q.b = true` | 保持连接状态可用，避免旧会话或错误初始化残留 |
| 跳过 `wms.a.r()` | 不创建 AOSP 拒绝的 `2998` 导航栏窗口 |
| 跳过两个 `2006` 辅助方法 | 不因 1×1 刷新窗口异常退出 CarWith |
| 拦截 CarLife 模式下的 `CastingActivity.e1()` | mcarplay 切前台时不主动拆链 |
| 忽略 CarLife 模式下的 `q.k("none")` | 防止其他生命周期路径在台架会话中把状态重置 |

最后保留一个空的 `setInterval`，避免 Frida 脚本注入后因事件循环退出而失去 hook。

## 台架启动顺序

以下步骤需要 root。普通 shell 的 UID 2000 不能启动受签名权限保护的 `CarlinkService`，会得到类似：

```text
Requires permission not exported from uid 10242
```

### 1. 清理旧进程

```bash
DEVICE=<device>
adb -s "$DEVICE" shell '
  am force-stop com.mcarplay.app
  am force-stop com.miui.carlink
  am force-stop com.baidu.carlife.xiaomi
  logcat -c
'
```

### 2. 等待 CarWith 进程并注入 hook

在单独终端中先启动 Frida，等待 `com.miui.carlink` 被后面的服务命令拉起：

```bash
/path/to/frida -U -W com.miui.carlink \
  -l .claude/hook-carwith-minimal-preserve.js
```

不要在这里使用 `frida -f com.miui.carlink`。当前 CarWith 没有可直接 spawn 的前台 Activity，Frida 会报
`unable to find a front-door activity`；对本次服务启动路径，等待进程出现才是有效方式。

### 3. 由 root 启动 CarWith 的 CarLife 服务

Frida 已进入等待后，在另一个终端执行：

```bash
adb -s "$DEVICE" shell "su -c \
  'am start-foreground-service \
   -n com.miui.carlink/com.miui.carlink.castfwk.CarlinkService \
   --ei startType 4 \
   --es carlife_wireless_type Baidu_ap \
   --es carlife_wireless_type_name mcarplay'"
```

成功时 Frida 至少应输出：

```text
[preserve] connection type=wifi_ap_carlife_connect, active=true
```

### 4. 检查七口

```bash
adb -s "$DEVICE" shell "su -c 'ss -ltn'" |
  rg ':(7240|8240|9240|9241|9242|9340|9440)\b'
```

应当同时看到七个本地监听端口。只有 `7240` 出现还不算完整，必须等七路都铺好再让 mcarplay 接入。

### 5. 启动 mcarplay

让 mcarplay 先附着已经存在的七口，不要让它再把 CarWith 切到另一个新会话。接入后检查 debug 日志：

```bash
adb -s "$DEVICE" shell run-as com.mcarplay.app \
  cat files/main_debug.log |
  rg '7240|8240|9240|9241|9242|9340|9440|allConnected|VIDEO|CARLIFE'
```

调试日志可能出现 `allConnectedOnce=true`，随后出现 CarLife 认证、AES 就绪和视频帧计数。这里的帧计数是
mcarplay 收到的源端帧，不等于接收端已完成长时间动态画面验收。

## 本次实际观察

- 七个端口 `7240/8240/9240/9241/9242/9340/9440` 均处于监听状态。
- mcarplay 已建立七路连接并持续发送视频，没有停留在 `7240 ECONNREFUSED`。
- 截至 10:54（Asia/Shanghai），源端累计约 `43,821` 帧、`729 MB`，观察时长约 `4,361` 秒。
- mcarplay 和 CarWith 进程在观察窗口内保持运行。
- 会话期间曾意外打开音频，但音频不属于本轮端口恢复的验收范围，不能据此声明音频通过。

这组结果证明：七口缺失问题已被绕过，源端媒体链路能够持续产出并被 mcarplay 消费。它不证明接收端画面长时间
持续变化、触摸稳定、音频正常或真实车机兼容。

## 同类表面现象：正常小米手机也可能没有七口

2026-11-04 的一份脱敏报告来自正常小米环境：Xiaomi `M2102J2SC`、Android 13、CarWith
`4.0.4-20260522`、小米 CarLife `8.6.8`、mcarplay `0.1.4`。它也有 `7240 ECONNREFUSED`，但**不能套用本页
Pixel 5 的窗口崩溃结论**。

### 报告观察

- 除一次附着外，16:21–17:02 的每次自检都在 25 秒内轮询 62 次 `7240`，全部 `REFUSED`；
  `entry=REQUESTED`、`background=false`、`entryBlockedSuspected=false`。
- 最终一轮导出时的环境显示「后台弹出界面」已允许，CarWith 导出的入口为
  `exported=true enabled=true`，功能开关为 `DEFAULT_ON`，CarWith/CarLife 的关键权限也已授予。
  因此这份报告不能把失败归因于后台启动限制或缺少常规权限；“后台弹出界面”在本轮不是主因。
- 16:57:44 曾出现另一条路径：mcarplay 在 13 ms 内连接了已经存在的七口，
  `entry=ATTACHED`、`allConnectedOnce=true`。它发送了 `0x18001`，但 8 秒内没有视频帧；
  自检随后“补请求入口”，到 16:59:07（总计 83.332 秒）七路全部断开，仍是 `frames=0`。

所以这份报告同时包含两种状态：

1. **入口请求已提交，但七口从未出现。**
2. **七口已经存在，但不是一个已完成准备的有效 Cast 会话，`0x18001` 无响应。**

两者都不能被“端口在监听”或“入口已请求”单独证明为正常。

### 为什么不能仅凭报告定因

`entry=REQUESTED` 只表示本应用的 `startActivity()` 返回了，不表示 CarWith 的
`CarlifeAdbActivity` 被创建、完成了授权，或成功调用了 `CarlinkService`。

CarWith `4.0.4` 的入口自身还有以下门槛：

- CarWith 的无线连接开关关闭时，入口直接结束；
- 当前连接类型不是 `none` 时，入口可能认为“连接已经开始”而直接结束，不重新启动服务；
- 首次 CTA、隐私或权限页面尚未确认时，入口等待或退出；
- 入口启动内部服务失败，或 CarWith 进程随后退出。

这份报告只能看到 mcarplay 的环境快照，无法读取 CarWith 的私有偏好、当前 Activity、进程存活和 logcat，
也没有系统退出记录或入口页面截图。因此，当前证据**只能证明入口没有产出七口**，不能确定是上述哪一项。

16:57 的七口附着另说明：端口存在也可能只是遗留会话、单独打开的 CarLife 监听或未完成的 CarWith 投射状态。
该轮没有 `0x18001` 响应，不能把它记为一次成功的 CarWith 投屏准备。

### 当前实现的诊断边界

在 `COMPATIBLE` 策略下，`requestPublicEntry()` 返回 `REQUESTED` 后，mcarplay 会继续等待 25 秒，不会因为
没有七口而改走 CarWith 设置页；只有入口结果本身不是 `REQUESTED` 时才回退。对于“`startActivity()` 成功、
CarWith 入口静默结束”的情况，当前报告只能给出 `entry=REQUESTED`、`lastFailedPort=7240`，不能进一步指明
CarWith 内部停在哪一个门槛。

如果要在正常小米上继续定位，应先做一次不经过 mcarplay 的 CarWith 原厂路径对照：

1. 保持亮屏解锁，打开 CarWith，确认无线开关和百度 CarLife 隐私/授权已完成；
2. 在 CarWith 中选择百度 CarLife 的无线连接，观察是否进入“正在连接”状态；
3. 保持该状态，再回到 mcarplay 做自检；
4. 若仍失败，保留 CarWith 入口画面截图，并采集 CarWith 的 logcat、进程和前台 Activity，而不只是
   mcarplay 自己的诊断报告。

有 ADB 时，最小补充证据是：

```bash
adb -s <device> shell pidof com.miui.carlink
adb -s <device> shell dumpsys activity activities |
  rg -i 'carlink|CarlifeAdbActivity|CastingActivity'
adb -s <device> shell settings get global ex_func_carWith
adb -s <device> logcat -b all -d -v threadtime |
  rg -i 'CarlifeAdbActivity|CarlinkService|CastingActivity|AndroidRuntime'
```

这些信息用于区分“CarWith 入口被开关/授权挡住”“入口结束了已有连接”“CarWith 进程崩溃”和“内部服务启动失败”。
没有这些证据，不应把正常小米的失败写成某个已确认根因。

## 为什么会出现画面卡住

端口恢复和播放稳定性是两个不同层次的问题。七口存在只说明 CarLife 会话和媒体通道建立；接收端卡住还可能来自：

- 接收端解码或渲染停顿；
- AirPlay 接收端的缓冲、重连或会话生命周期；
- 视频发送队列、关键帧等待或网络抖动；
- CarWith 虚拟显示与源画面刷新状态。

因此不能用“七口都在监听”证明“接收端画面不会卡住”。本次文档只负责前者，后者仍需要独立的播放时长、
解锁时间、丢帧和画面变化证据。

## 边界与后续

- Frida 和 root 仅用于 bench 验证。它们没有进入 mcarplay APK，也不是普通用户的安装步骤。
- mcarplay 没有创建这七个端口，也没有替代 CarWith 的 CarLife 投屏准备；本次修复的是测试台运行条件。
- 这不是完整的 OS1 移植。要变成可交付方案，需要修补 CarWith 的 AOSP 兼容问题，或在 CarWith 侧提供正式入口；
  不能把 `frida` hook 当作产品功能。
- 本结果不能声明 Pixel 5、LineageOS 或该 CarWith 版本已被支持，也不能作为真车、无 root、音频、触控或长时间
  稳定性验收。
- 显示分辨率不铺满属于显示配置问题，与七口未出现是两个独立问题；修改分辨率不会解决 `7240 ECONNREFUSED`。
