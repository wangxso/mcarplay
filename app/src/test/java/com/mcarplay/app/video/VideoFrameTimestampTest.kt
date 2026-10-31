package com.mcarplay.app.video

import com.mcarplay.app.airplay.TimingServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 视频帧头时间戳必须与计时应答同一时钟域：接收端按 timesync 偏移解释帧头 NTP。
 * TimingServer 改为 1900 纪元后，帧头若仍用开机时钟会落后约 126 年。
 */
class VideoFrameTimestampTest {
    private fun headerNtp(header: ByteArray): Long = ByteBuffer.wrap(header, 8, 8).order(ByteOrder.LITTLE_ENDIAN).long

    @Test
    fun frameTimestampMatchesTimesyncEncodingLittleEndian() {
        val nanos = TimingServer.ntpNanosNow()
        val header = ByteArray(128)

        VideoSender.writeFrameTimestamp(header, nanos)

        assertEquals(ByteBuffer.wrap(TimingServer.ntpFromNanos(nanos)).long, headerNtp(header))
    }

    @Test
    fun frameTimestampIsInNtpEpoch() {
        val header = ByteArray(128)
        VideoSender.writeFrameTimestamp(header, TimingServer.ntpNanosNow())

        val secs = headerNtp(header) ushr 32
        val expected = System.currentTimeMillis() / 1000L + 2_208_988_800L
        assertTrue("secs=$secs expected=$expected", Math.abs(secs - expected) <= 2)
    }
}
