package com.mcarplay.app.bt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class BtSessionGateTest {
    @Test
    fun independentWifiHandoffBlocksOutbound() {
        assertFalse(BtSessionGate.beginOutbound(Closeable {}, wifiHandoffDetached = true))
        assertFalse(BtSessionGate.outboundPaused)
    }

    @Test
    fun acceptedInboundBlocksOutboundAndFurtherInbound() {
        assertTrue(BtSessionGate.admitInbound())
        try {
            assertTrue(BtSessionGate.outboundPaused)
            assertFalse(BtSessionGate.beginOutbound(Closeable {}))
            assertFalse(BtSessionGate.admitInbound())
        } finally {
            BtSessionGate.inboundFinished()
        }
        assertFalse(BtSessionGate.outboundPaused)
    }

    @Test
    fun registeredOutboundRejectsInboundWithoutClosingOutbound() {
        var closed = false
        val socket = Closeable { closed = true }
        assertTrue(BtSessionGate.beginOutbound(socket))
        try {
            assertFalse(BtSessionGate.admitInbound())
            assertFalse(BtSessionGate.beginOutbound(Closeable {}))
            assertFalse(closed)
        } finally {
            BtSessionGate.outboundFinished(socket)
        }
    }

    @Test
    fun inboundCanClaimGateAsSoonAsOutboundIsReleased() {
        val socket = Closeable {}
        assertTrue(BtSessionGate.beginOutbound(socket))
        try {
            assertFalse(BtSessionGate.admitInbound())
        } finally {
            BtSessionGate.outboundFinished(socket)
        }

        assertTrue(BtSessionGate.admitInbound())
        BtSessionGate.inboundFinished()
    }

    @Test
    fun releasingAnotherSocketKeepsRegisteredOutboundInControl() {
        val socket = Closeable {}
        assertTrue(BtSessionGate.beginOutbound(socket))
        try {
            BtSessionGate.outboundFinished(Closeable {})
            assertFalse(BtSessionGate.admitInbound())
        } finally {
            BtSessionGate.outboundFinished(socket)
        }
    }

    @Test
    fun timeoutReleasesClosedSocketBeforeTheDialReturns() {
        val pending = AtomicBoolean(true)
        val dialing = CountDownLatch(1)
        val returnFromConnect = CountDownLatch(1)
        var closed = false
        val socket =
            Closeable {
                assertFalse(pending.get())
                assertFalse(BtSessionGate.admitInbound())
                closed = true
            }
        val completed =
            FutureTask {
                try {
                    assertThrows(SocketTimeoutException::class.java) {
                        BtSessionGate.connectOutbound(pending) {
                            dialing.countDown()
                            check(returnFromConnect.await(5, TimeUnit.SECONDS))
                        }
                    }
                } finally {
                    BtSessionGate.outboundFinished(socket)
                }
            }
        assertTrue(BtSessionGate.beginOutbound(socket))
        val worker = thread(isDaemon = true) { completed.run() }
        try {
            assertTrue(dialing.await(5, TimeUnit.SECONDS))
            assertTrue(BtSessionGate.expireOutbound(socket, pending))
            assertTrue(closed)
            assertFalse(completed.isDone)
            assertTrue(BtSessionGate.admitInbound())
            try {
                returnFromConnect.countDown()
                completed.get(5, TimeUnit.SECONDS)
                assertTrue(BtSessionGate.outboundPaused)
            } finally {
                BtSessionGate.inboundFinished()
            }
        } finally {
            returnFromConnect.countDown()
            worker.join(5_000)
            BtSessionGate.outboundFinished(socket)
        }
        assertFalse(BtSessionGate.outboundPaused)
    }

    @Test
    fun timeoutLeavesCompletedDialAlone() {
        val socket = Closeable { error("completed dial must stay open") }
        assertTrue(BtSessionGate.beginOutbound(socket))
        try {
            assertFalse(BtSessionGate.expireOutbound(socket, AtomicBoolean(false)))
            assertFalse(BtSessionGate.admitInbound())
        } finally {
            BtSessionGate.outboundFinished(socket)
        }
    }

    @Test
    fun closeFailureLeavesTimedOutDialRegisteredUntilWorkerFinishes() {
        val pending = AtomicBoolean(true)
        val socket = Closeable { throw IOException("close failed") }
        assertTrue(BtSessionGate.beginOutbound(socket))
        try {
            assertThrows(IOException::class.java) { BtSessionGate.expireOutbound(socket, pending) }
            assertFalse(pending.get())
            assertFalse(BtSessionGate.admitInbound())
        } finally {
            BtSessionGate.outboundFinished(socket)
        }
    }

    @Test
    fun inFlightOutboundDialYieldsToLiveInboundAndClosesDialingSocket() {
        val pending = AtomicBoolean(true)
        val preempted = AtomicBoolean(false)
        val dialing = CountDownLatch(1)
        val unblockConnect = CountDownLatch(1)
        var closed = false
        val socket =
            Closeable {
                // 关闭完成前不允许重入放行第二条入站
                assertFalse(pending.get())
                assertTrue(preempted.get())
                assertFalse(BtSessionGate.admitInbound())
                closed = true
                unblockConnect.countDown()
            }
        val workerDone =
            FutureTask {
                try {
                    assertThrows(SocketTimeoutException::class.java) {
                        BtSessionGate.connectOutbound(pending, preempted) {
                            dialing.countDown()
                            check(unblockConnect.await(5, TimeUnit.SECONDS))
                        }
                    }
                } finally {
                    BtSessionGate.outboundFinished(socket)
                }
            }
        assertTrue(BtSessionGate.beginOutbound(socket))
        val worker = thread(isDaemon = true) { workerDone.run() }
        try {
            assertTrue(dialing.await(5, TimeUnit.SECONDS))
            // 出站仍在 connect() 阻塞时，真实入站连接抢占成功并关闭出站 socket
            assertTrue(BtSessionGate.admitInbound())
            assertTrue(closed)
            assertTrue(preempted.get())
            // 超时器随后触发不应误伤已接管的入站状态
            assertFalse(BtSessionGate.expireOutbound(socket, pending))
            workerDone.get(5, TimeUnit.SECONDS)
            assertTrue(BtSessionGate.outboundPaused)
            assertFalse(BtSessionGate.admitInbound())
        } finally {
            unblockConnect.countDown()
            worker.join(5_000)
            BtSessionGate.inboundFinished()
            BtSessionGate.outboundFinished(socket)
        }
        assertFalse(BtSessionGate.outboundPaused)
    }

    @Test
    fun completedOutboundDialStillRejectsInbound() {
        val pending = AtomicBoolean(true)
        val preempted = AtomicBoolean(false)
        var closed = false
        val socket = Closeable { closed = true }
        assertTrue(BtSessionGate.beginOutbound(socket))
        try {
            BtSessionGate.connectOutbound(pending, preempted) { }
            assertFalse(BtSessionGate.admitInbound())
            assertFalse(closed)
            assertFalse(preempted.get())
        } finally {
            BtSessionGate.outboundFinished(socket)
        }
    }
}
