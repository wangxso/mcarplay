package com.mcarplay.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ctrl mDNS 广播身份字段的回归保护。
 *
 * 2026-10-19 奥迪 MMI 3315 实车对照（`docs/PROTOCOL-NOTES.md` §9i）：
 * 0.1.6 用 `name=sender` + `srcvers=950.7.1` 时车机 0.65 秒内下发 0x4301；
 * 0.1.7 改成 catplay 的 `name=iPhone` + `srcvers=566.25.21` 后，车机**连 ctrl-int
 * 探测都不再发**、0x4301 永不到达。这两项是车机在 WiFi 上唯一可见的手机身份，
 * 改动等于换了台设备——必须有测试挡住「照 catplay 抄」的再次修改。
 *
 * 同时锁住 `_device-info._tcp` 不再注册：Android 的 NsdServiceInfo 不接受 port=0
 * （真机报 `The port is missing`），而它是未验证收益的附加广播，失败反而制造噪音。
 */
class CarPlayCtrlAdvertiserTest {
    @Test
    fun ctrlTxtKeepsValuesVerifiedOnAudi() {
        val srcvers = CarPlayCtrlAdvertiser.advertisedSrcversForTest()
        val name = CarPlayCtrlAdvertiser.advertisedServiceNameForTest()
        assertEquals("车机按该版本号分支：改掉会连 ctrl 探测都不发", "950.7.1", srcvers)
        assertEquals("0.1.6 起实测可用的实例名", "sender", name)
        assertTrue("服务类型不得改动", CarPlayCtrlAdvertiser.SERVICE_TYPE == "_carplay-ctrl._tcp")
    }

    @Test
    fun deviceInfoIsNotAdvertised() {
        // _device-info._tcp 曾以 port=0 注册并必然失败（The port is missing）。
        // 若将来要恢复，必须先确认 NsdManager 接受合法端口且该车机确有此需求。
        assertTrue(
            "不应再广播 _device-info._tcp",
            !CarPlayCtrlAdvertiser.advertisedServiceTypesForTest().contains("_device-info._tcp"),
        )
    }
}
