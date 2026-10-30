package com.mcarplay.app.carlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

class CarWithDiscoveryResponderTest {
    private val loopback = address("127.0.0.1")

    @Test
    fun respondsToCompleteRequestAndReleasesPortOnStop() {
        val responder = responder()
        assertTrue(responder.start(loopback))
        val port = requireNotNull(responder.boundPort)
        try {
            connect(port).use { client ->
                val request = ExternalHuDiscoveryProtocol.encodeRequest()
                client.getOutputStream().write(request, 0, 7)
                client.getOutputStream().write(request, 7, request.size - 7)
                assertResponse(client, sendRequest = false)
            }
            assertTrue(responder.accepted)
        } finally {
            responder.stop()
        }
        assertFalse(responder.accepted)
        assertNull(responder.boundAddress)
        assertPortReleased(port)
    }

    @Test
    fun rejectsWrongCommandPayloadChecksumAndTruncatedHeader() {
        val callbacks = AtomicInteger()
        val responder = responder(callbacks)
        assertTrue(responder.start(loopback))
        val port = requireNotNull(responder.boundPort)
        try {
            val invalid =
                listOf(
                    ExternalHuDiscoveryProtocol.encodeFrame(0x70000012),
                    ExternalHuDiscoveryProtocol.encodeFrame(ExternalHuDiscoveryProtocol.REQUEST_COMMAND, byteArrayOf(1)),
                    ExternalHuDiscoveryProtocol.encodeRequest().apply { this[8] = (this[8].toInt() xor 1).toByte() },
                    ExternalHuDiscoveryProtocol.encodeRequest().copyOf(9),
                )
            invalid.forEach { bytes ->
                connect(port).use { client ->
                    client.getOutputStream().write(bytes)
                    client.shutdownOutput()
                    assertPeerClosed(client)
                }
                assertFalse(responder.accepted)
            }
            assertEquals(0, callbacks.get())
            connect(port).use { assertResponse(it) }
            assertEquals(1, callbacks.get())
        } finally {
            responder.stop()
        }
    }

    @Test
    fun sameAddressStartIsIdempotent() {
        val responder = responder()
        assertTrue(responder.start(loopback))
        try {
            val port = requireNotNull(responder.boundPort)
            connect(port).use { assertResponse(it) }
            assertTrue(responder.start(loopback))
            assertEquals(port, responder.boundPort)
            assertTrue(responder.accepted)
        } finally {
            responder.stop()
        }
    }

    @Test
    fun addressChangeWithdrawsOldListenerEvenWhenNewAddressCannotBind() {
        val responder = responder()
        assertTrue(responder.start(loopback))
        val oldPort = requireNotNull(responder.boundPort)
        try {
            connect(oldPort).use { assertResponse(it) }
            val alternate = address("127.0.0.2")
            val rebound = responder.start(alternate)
            assertFalse(responder.accepted)
            if (rebound) {
                assertEquals(alternate, responder.boundAddress)
                connect(requireNotNull(responder.boundPort), alternate).use { assertResponse(it) }
            } else {
                // Some hosts only configure 127.0.0.1; a failed replacement must still retire the old endpoint.
                assertNull(responder.boundAddress)
                assertNull(responder.boundPort)
                assertTrue(responder.state.contains("失败"))
            }
            assertPortReleased(oldPort)
        } finally {
            responder.stop()
        }
    }

    @Test
    fun stopClosesPartialRequestAndOldClientCannotAcceptAfterRestart() {
        val callbacks = AtomicInteger()
        val responder = responder(callbacks)
        assertTrue(responder.start(loopback))
        try {
            connect(requireNotNull(responder.boundPort)).use { oldClient ->
                oldClient.getOutputStream().write(ExternalHuDiscoveryProtocol.encodeRequest().copyOf(8))
                awaitClient(responder)
                responder.stop()
                assertPeerClosed(oldClient)
                assertEquals(0, responder.activeClientCount)
                assertFalse(responder.accepted)
                assertTrue(responder.start(loopback))
                connect(requireNotNull(responder.boundPort)).use { assertResponse(it) }
                assertEquals(1, callbacks.get())
                assertTrue(responder.accepted)
            }
        } finally {
            responder.stop()
        }
    }

    @Test
    fun rapidRestartDoesNotLetRetiredAcceptLoopOverwriteNewState() {
        val callbacks = AtomicInteger()
        val responder = responder(callbacks)
        try {
            repeat(20) {
                assertTrue(responder.start(loopback))
                connect(requireNotNull(responder.boundPort)).use { assertResponse(it) }
                assertEquals("10930 已接受 CarWith 探测", responder.state)
                responder.stop()
                assertFalse(responder.accepted)
            }
            assertEquals(20, callbacks.get())
        } finally {
            responder.stop()
        }
    }

    @Test
    fun bindFailureDoesNotClaimListeningOrLeaveResources() {
        ServerSocket(0, 1, loopback).use { occupied ->
            val responder = CarWithDiscoveryResponder(log = {}, onAccepted = {}, port = occupied.localPort)
            assertFalse(responder.start(loopback))
            assertNull(responder.boundPort)
            assertNull(responder.boundAddress)
            assertFalse(responder.accepted)
            responder.stop()
        }
    }

    private fun responder(callbacks: AtomicInteger = AtomicInteger()) =
        CarWithDiscoveryResponder(log = {}, onAccepted = { callbacks.incrementAndGet() }, port = 0)

    private fun connect(
        port: Int,
        host: Inet4Address = loopback,
    ): Socket =
        Socket().apply {
            soTimeout = 2_000
            connect(InetSocketAddress(host, port), 2_000)
        }

    private fun assertResponse(
        client: Socket,
        sendRequest: Boolean = true,
    ) {
        if (sendRequest) client.getOutputStream().write(ExternalHuDiscoveryProtocol.encodeRequest())
        val input = DataInputStream(client.getInputStream())
        val header = ByteArray(16)
        input.readFully(header)
        val length = ExternalHuDiscoveryProtocol.frameLength(header)
        assertTrue(length in 16..1024)
        val body = ByteArray(length - header.size)
        input.readFully(body)
        val frame = ExternalHuDiscoveryProtocol.decodeFrame(header + body)
        assertEquals(ExternalHuDiscoveryProtocol.RESPONSE_COMMAND, frame?.kind)
        assertEquals("true", org.json.JSONObject(String(requireNotNull(frame).body)).get("status"))
        assertEquals(-1, input.read())
    }

    private fun assertPeerClosed(client: Socket) {
        try {
            assertEquals(-1, client.getInputStream().read())
        } catch (error: SocketTimeoutException) {
            throw AssertionError("Responder left a client open", error)
        } catch (_: IOException) {
            // Closing a socket with unread bytes can produce TCP RST instead of EOF.
        }
    }

    private fun assertPortReleased(port: Int) {
        val deadline = System.nanoTime() + 2_000_000_000L
        // A blocked native accept may release its listening descriptor shortly after close() returns.
        while (System.nanoTime() < deadline) {
            Socket().use { probe ->
                try {
                    probe.connect(InetSocketAddress(loopback, port), 200)
                } catch (_: ConnectException) {
                    return
                } catch (_: SocketException) {
                    // A probe queued in the backlog while the listener closes is reset; retry until it is refused.
                }
            }
            Thread.sleep(10)
        }
        throw AssertionError("Responder still accepts connections on $port after shutdown deadline")
    }

    private fun awaitClient(responder: CarWithDiscoveryResponder) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (responder.activeClientCount == 0 && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals(1, responder.activeClientCount)
    }

    private fun address(value: String) = InetAddress.getByName(value) as Inet4Address
}
