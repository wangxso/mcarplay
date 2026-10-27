package com.mcarplay.app.service

import android.app.Service
import android.content.Intent
import com.mcarplay.app.SessionController
import com.mcarplay.app.airplay.StreamFrameCipher
import com.mcarplay.app.video.ExternalVideoFrame
import com.mcarplay.app.video.ExternalVideoSource
import com.mcarplay.app.video.VideoSender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CarPlayServiceBluetoothDisconnectTest {
    @Test
    fun bluetoothHandoffKeepsWifiVideoBeforeAndAfterFirstFrame() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { receiver ->
            receiver.soTimeout = 2000
            val ended = CountDownLatch(1)
            val sender =
                VideoSender(
                    "127.0.0.1",
                    receiver.localPort,
                    StreamFrameCipher.ChaCha(ByteArray(32)),
                    640,
                    480,
                    log = {},
                    onEnded = { ended.countDown() },
                    externalVideoSource =
                        object : ExternalVideoSource {
                            override fun requestKeyFrame(): Boolean = true
                        },
                )
            SessionController.videoSender = sender
            try {
                assertTrue(sender.start {})
                receiver.accept().use { socket ->
                    socket.soTimeout = 2000
                    val service = CarPlayService()
                    val input = DataInputStream(socket.getInputStream())
                    assertEquals(0L, sender.framesSentTotal)
                    assertEquals(Service.START_STICKY, service.onStartCommand(autoStopIntent(), 0, 1))
                    assertTrue("Bluetooth release must not close a stream waiting for its first CarWith frame", sender.isRunning)

                    assertTrue(sender.submitExternalFrame(ExternalVideoFrame(accessUnit(), true, 0)))
                    assertEquals(1, readPacket(input))
                    assertEquals(0, readPacket(input))

                    assertEquals(Service.START_STICKY, service.onStartCommand(autoStopIntent(), 0, 2))
                    assertTrue("A late or repeated ACL disconnect must preserve live video", sender.isRunning)
                    assertTrue(sender.submitExternalFrame(ExternalVideoFrame(accessUnit(), true, 50_000)))
                    assertEquals(0, readPacket(input))
                }
                sender.stop()
                assertTrue(ended.await(2, TimeUnit.SECONDS))
                assertEquals(Service.START_NOT_STICKY, CarPlayService().onStartCommand(autoStopIntent(), 0, 3))
            } finally {
                sender.stop()
                SessionController.videoSender = null
                assertTrue(ended.await(2, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun bluetoothDisconnectWithoutWifiStreamStillStopsService() {
        assertEquals(Service.START_NOT_STICKY, CarPlayService().onStartCommand(autoStopIntent(), 0, 1))
    }

    private fun autoStopIntent(): Intent =
        object : Intent() {
            override fun getAction(): String = CarPlayService.ACTION_AUTO_STOP_SESSION
        }

    private fun readPacket(input: DataInputStream): Int {
        val header = ByteArray(128)
        input.readFully(header)
        val length = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(0)
        assertTrue(length in 1..4096)
        input.readFully(ByteArray(length))
        return header[4].toInt() and 0xFF
    }

    private fun accessUnit(): ByteArray =
        listOf(
            byteArrayOf(0x67, 0x42, 0, 0x1f),
            byteArrayOf(0x68, 0xce.toByte(), 6, 0xe2.toByte()),
            byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte()),
        ).fold(ByteArray(0)) { bytes, nal -> bytes + byteArrayOf(0, 0, 0, 1) + nal }
}
