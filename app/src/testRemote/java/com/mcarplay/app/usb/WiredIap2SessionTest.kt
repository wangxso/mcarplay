package com.mcarplay.app.usb

import com.mcarplay.app.iap2.Iap2Csm
import com.mcarplay.app.iap2.Iap2MessageIds
import com.mcarplay.app.iap2.asString
import com.mcarplay.app.transport.Iap2LinkChannel
import com.mcarplay.app.transport.InMemoryStreamPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WiredIap2SessionTest {
    private val accessoryParser = Iap2Csm.Parser()
    private val accessoryFrames = ArrayDeque<Iap2Csm.Frame>()

    @Test
    fun completesWiredIdentificationMfiAndCarPlayStart() {
        val streams = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(streams.sideA, "phone-test")
        val accessoryChannel = Iap2LinkChannel(streams.sideB, "accessory-test")
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertTrue(accessoryChannel.awaitReady(5_000))
            val result =
                executor.submit<WiredIap2Session.CarPlayStart> {
                    WiredIap2Session(phoneChannel, "usb-serial").run(5_000)
                }

            expect(accessoryChannel, Iap2MessageIds.START_IDENTIFICATION)
            accessoryChannel.sendControl(
                Iap2Csm.encode(
                    Iap2MessageIds.IDENTIFICATION_INFORMATION,
                    Iap2Csm.string(0, "MacPlay"),
                ),
            )
            expect(accessoryChannel, Iap2MessageIds.IDENTIFICATION_ACCEPTED)

            expect(accessoryChannel, Iap2MessageIds.MFI_REQUEST_CERTIFICATE)
            accessoryChannel.sendControl(
                Iap2Csm.encode(
                    Iap2MessageIds.MFI_CERTIFICATE,
                    Iap2Csm.param(0, byteArrayOf(1, 2, 3)),
                ),
            )
            val challenge = expect(accessoryChannel, Iap2MessageIds.MFI_REQUEST_CHALLENGE)
            assertEquals(
                32,
                Iap2Csm
                    .parseParams(challenge.body)
                    .single()
                    .value.size,
            )
            accessoryChannel.sendControl(
                Iap2Csm.encode(
                    Iap2MessageIds.MFI_RESPONSE,
                    Iap2Csm.param(0, byteArrayOf(4, 5, 6)),
                ),
            )
            expect(accessoryChannel, Iap2MessageIds.MFI_AUTHENTICATION_SUCCEEDED)

            expectAny(accessoryChannel, setOf(Iap2MessageIds.DEVICE_INFORMATION_UPDATE, Iap2MessageIds.DEVICE_LANGUAGE_UPDATE))
            val availability = expect(accessoryChannel, Iap2MessageIds.CARPLAY_AVAILABILITY)
            val wired = Iap2Csm.parseParams(availability.body).single()
            val attributes = Iap2Csm.parseParams(wired.value)
            assertEquals(0, wired.id)
            assertEquals(
                1,
                attributes
                    .single { it.id == 0 }
                    .value
                    .single()
                    .toInt(),
            )
            assertEquals("usb-serial", attributes.single { it.id == 1 }.asString())

            accessoryChannel.sendControl(
                Iap2Csm.encode(
                    Iap2MessageIds.CARPLAY_START_SESSION,
                    Iap2Csm.group(0, Iap2Csm.string(0, "fe80::1")) +
                        Iap2Csm.u32(2, 17_000) +
                        Iap2Csm.string(3, "MacPlay") +
                        Iap2Csm.param(4, byteArrayOf(1, 2, 3, 4)) +
                        Iap2Csm.string(5, "1.2.4"),
                ),
            )

            val start = result.get(5, TimeUnit.SECONDS)
            assertEquals(listOf("fe80::1"), start.addresses)
            assertEquals(17_000, start.port)
            assertEquals("MacPlay", start.deviceIdentifier)
            assertEquals(4, start.publicKeyLength)
            assertEquals("1.2.4", start.sourceVersion)
        } finally {
            phoneChannel.close()
            accessoryChannel.close()
            executor.shutdownNow()
        }
    }

    private fun expect(
        channel: Iap2LinkChannel,
        messageId: Int,
    ): Iap2Csm.Frame = expectAny(channel, setOf(messageId))

    private fun expectAny(
        channel: Iap2LinkChannel,
        messageIds: Set<Int>,
    ): Iap2Csm.Frame {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (System.nanoTime() < deadline) {
            accessoryFrames.firstOrNull { it.messageId in messageIds }?.let {
                accessoryFrames.remove(it)
                return it
            }
            val bytes = channel.recvControl(100) ?: continue
            accessoryFrames += accessoryParser.offer(bytes)
        }
        error("did not receive any of ${messageIds.map { "0x${it.toString(16)}" }}")
    }
}
