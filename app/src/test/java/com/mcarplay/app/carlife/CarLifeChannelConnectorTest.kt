package com.mcarplay.app.carlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.Socket

class CarLifeChannelConnectorTest {
    @Test
    fun connectsAsClientToAllSevenListeners() {
        val opened = mutableListOf<Int>()
        val connector =
            CarLifeChannelConnector(
                connect = { port ->
                    opened += port
                    Socket()
                },
            )
        val sockets = connector.connectAll(timeoutMs = 100, retryDelayMs = 1)
        assertEquals(CarLifeChannel.entries.map { it.port }, opened)
        assertEquals(CarLifeChannel.entries.toSet(), sockets.keys)
        CarLifeChannel.entries.forEach { assertNotNull(connector.socket(it)) }
        connector.close()
        assertTrue(sockets.values.all { it.isClosed })
    }

    @Test
    fun partialAttemptIsClosedBeforeRetry() {
        var failNext = true
        val connector =
            CarLifeChannelConnector(
                connect = { port ->
                    val socket = Socket()
                    if (failNext) {
                        failNext = false
                        socket.close()
                        throw IOException("not ready")
                    }
                    socket
                },
                sleep = {},
            )
        val sockets = connector.connectAll(timeoutMs = 100, retryDelayMs = 1)
        assertEquals(7, sockets.size)
        assertEquals(7, sockets.keys.size)
        connector.close()
    }

    @Test
    fun missingListenersFailWithoutClaimingSuccess() {
        var now = 0L
        val connector =
            CarLifeChannelConnector(
                connect = { throw IOException("connection refused") },
                sleep = { now += 2 },
                nowMs = { now },
            )
        assertThrows(IOException::class.java) {
            connector.connectAll(timeoutMs = 3, retryDelayMs = 1)
        }
        connector.close()
    }

    @Test
    fun connectAllDoesNotLogSuccessBeforeEveryChannelIsConnected() {
        val logs = mutableListOf<String>()
        val connector =
            CarLifeChannelConnector(
                connect = { port ->
                    if (port == CarLifeChannel.DATA.port) throw IOException("last listener not ready")
                    Socket()
                },
                sleep = {},
                nowMs =
                    object {
                        private var now = 0L

                        fun read(): Long {
                            now += 10
                            return now - 10
                        }
                    }::read,
                log = logs::add,
            )

        assertThrows(IOException::class.java) {
            connector.connectAll(timeoutMs = 20, retryDelayMs = 1)
        }
        assertTrue(logs.none { it.contains("连接完成") })
        connector.close()
    }
}
