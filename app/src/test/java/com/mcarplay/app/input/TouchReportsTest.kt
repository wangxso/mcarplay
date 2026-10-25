package com.mcarplay.app.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 触摸报文格式判定（按设备 HID 描述符，不按车机型号/uuid 写死）。
 *
 * 两类设备实测：
 *  - 奥迪 MIB3 eso_touchScreen：单指 5 字节 [tip][X u16LE][Y u16LE]，描述符 62B
 *  - DiPlay 测试台 Touchscreen：双槽 12 字节，描述符含两个 Finger Collection
 */
class TouchReportsTest {
    /** 奥迪触摸屏描述符（Digitizer 页，单指，X/Y 16bit）。 */
    private val audiTouchDescriptor =
        byteArrayOf(
            0x05,
            0x0d,
            0x09,
            0x04,
            0xa1.toByte(),
            0x01,
            0x05,
            0x0d,
            0x09,
            0x22,
            0xa1.toByte(),
            0x02,
            0x05,
            0x0d,
            0x09,
            0x33,
            0x15,
            0x00,
            0x25,
            0x01,
            0x75,
            0x01,
            0x95.toByte(),
            0x01,
            0x81.toByte(),
            0x02,
            0x75,
            0x07,
            0x95.toByte(),
            0x01,
            0x81.toByte(),
            0x01,
            0x05,
            0x01,
            0x09,
            0x30,
            0x15,
            0x00,
            0x26,
            0x84.toByte(),
            0x05,
            0x75,
            0x10,
            0x95.toByte(),
            0x01,
            0x81.toByte(),
            0x02,
            0x09,
            0x31,
            0x15,
            0x00,
            0x26,
            0x8e.toByte(),
            0x02,
            0x75,
            0x10,
            0x95.toByte(),
            0x01,
            0x81.toByte(),
            0x02,
            0xc0.toByte(),
            0xc0.toByte(),
        )

    /** DiPlay 双指描述符（两个 Finger Collection）。 */
    private val diplayTouchDescriptor =
        byteArrayOf(
            0x05,
            0x0d,
            0x09,
            0x04,
            0xa1.toByte(),
            0x01,
            0x05,
            0x0d,
            0x09,
            0x22,
            0xa1.toByte(),
            0x02,
            0x09,
            0x38,
            0x75,
            0x08,
            0x95.toByte(),
            0x01,
            0x81.toByte(),
            0x02,
            0x15,
            0x00,
            0x25,
            0x01,
            0x09,
            0x33,
            0x75,
            0x01,
            0x95.toByte(),
            0x01,
            0x81.toByte(),
            0x02,
            0x95.toByte(),
            0x07,
            0x81.toByte(),
            0x03,
            0x05,
            0x01,
            0x26,
            0x84.toByte(),
            0x05,
            0x09,
            0x30,
            0x75,
            0x10,
            0x95.toByte(),
            0x01,
            0x81.toByte(),
            0x02,
            0x26,
            0x8e.toByte(),
            0x02,
            0x09,
            0x31,
            0x81.toByte(),
            0x02,
            0xc0.toByte(),
            // 第二个手指 Collection
            0x05,
            0x0d,
            0x09,
            0x22,
            0xa1.toByte(),
            0x02,
            0x09,
            0x38,
            0x75,
            0x08,
            0x95.toByte(),
            0x01,
            0x81.toByte(),
            0x02,
            0x15,
            0x00,
            0x25,
            0x01,
            0x09,
            0x33,
            0x75,
            0x01,
            0x95.toByte(),
            0x01,
            0x81.toByte(),
            0x02,
            0x95.toByte(),
            0x07,
            0x81.toByte(),
            0x03,
            0x05,
            0x01,
            0x26,
            0x84.toByte(),
            0x05,
            0x09,
            0x30,
            0x75,
            0x10,
            0x95.toByte(),
            0x01,
            0x81.toByte(),
            0x02,
            0x26,
            0x8e.toByte(),
            0x02,
            0x09,
            0x31,
            0x81.toByte(),
            0x02,
            0xc0.toByte(),
            0xc0.toByte(),
        )

    @Test
    fun audiSingleTouchDescriptorDetected() {
        assertEquals(
            TouchReports.Format.SINGLE_ABSOLUTE,
            TouchReports.formatFromDescriptor(audiTouchDescriptor),
        )
    }

    @Test
    fun diplayMultitouchDescriptorDetected() {
        assertEquals(
            TouchReports.Format.MULTI_SLOT,
            TouchReports.formatFromDescriptor(diplayTouchDescriptor),
        )
    }

    @Test
    fun nonDigitizerDescriptorIsNotTouch() {
        // Consumer Control（媒体键）不是触摸设备
        val consumer =
            byteArrayOf(
                0x05,
                0x0c,
                0x09,
                0x01,
                0xa1.toByte(),
                0x01,
                0x15,
                0x00,
                0x25,
                0x08,
                0x05,
                0x0c,
                0x0a,
                0xb0.toByte(),
                0x00,
                0x75,
                0x08,
                0x95.toByte(),
                0x01,
                0x81.toByte(),
                0x00,
                0xc0.toByte(),
            )
        assertNull(TouchReports.formatFromDescriptor(consumer))
    }

    @Test
    fun audiSingleTouchReportParses() {
        // [tip=1][X=0x0200 LE][Y=0x0100 LE]
        val report = byteArrayOf(0x01, 0x00, 0x02, 0x00, 0x01)
        val contacts =
            TouchReports.parse(
                report,
                TouchReports.formatFromDescriptor(audiTouchDescriptor),
            )!!
        assertEquals(1, contacts.size)
        assertTrue(contacts[0].down)
        assertEquals(0x0200, contacts[0].xPixels)
        assertEquals(0x0100, contacts[0].yPixels)
    }

    @Test
    fun diplayMultiSlotReportParses() {
        // 槽0 按下 (100,200)、槽1 抬起 (0,0)
        val report =
            byteArrayOf(
                0x00,
                0x01,
                100.toByte(),
                0,
                200.toByte(),
                0,
                0x01,
                0x00,
                0,
                0,
                0,
                0,
            )
        val contacts =
            TouchReports.parse(
                report,
                TouchReports.formatFromDescriptor(diplayTouchDescriptor),
            )!!
        assertEquals(2, contacts.size)
        assertTrue(contacts[0].down)
        assertEquals(100, contacts[0].xPixels)
        assertEquals(200, contacts[0].yPixels)
        assertEquals(false, contacts[1].down)
    }

    @Test
    fun lengthFallbackWhenDescriptorMissing() {
        // 描述符不可得：5B → 单指；12B → 多槽；其它 → 不解析
        assertEquals(1, TouchReports.parse(byteArrayOf(0x01, 0x00, 0x02, 0x00, 0x01), null)!!.size)
        assertEquals(2, TouchReports.parse(ByteArray(12), null)!!.size)
        assertNull(TouchReports.parse(byteArrayOf(0x00, 0x00, 0x00, 0x00), null))
    }
}
