package com.mcarplay.app.video

import com.mcarplay.app.architecture.SourceTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class VideoEndNotificationTest {
    private fun function(
        source: String,
        signature: String,
    ): String {
        val start = source.indexOf(signature)
        assertTrue("源码中找不到 $signature", start >= 0)
        val open = source.indexOf('{', start + signature.length)
        assertTrue("$signature 没有函数体", open >= 0)
        var depth = 0
        var index = open
        while (index < source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
            index++
        }
        throw AssertionError("$signature 的函数体没有闭合")
    }

    private fun requireOrder(
        source: String,
        first: String,
        second: String,
        message: String,
    ) {
        val firstAt = source.indexOf(first)
        val secondAt = if (firstAt >= 0) source.indexOf(second, firstAt + first.length) else -1
        assertTrue("$message（第一个未找到）", firstAt >= 0)
        assertTrue("$message（第二个未找到）", secondAt >= 0)
        assertTrue(message, firstAt < secondAt)
    }

    @Test
    fun serialExitCallsStopBeforeNotifyingOnce() {
        val notification = VideoEndNotification()
        val events = mutableListOf<String>()

        notification.afterDrain(
            stop = { events += "stop" },
            notify = { events += "notify" },
        )

        assertEquals(listOf("stop", "notify"), events)
    }

    @Test
    fun serialExternalFirstExitCallsStopBeforeNotifyingOnce() {
        val notification = VideoEndNotification()
        val events = mutableListOf<String>()

        notification.afterDrain(
            stop = { events += "external-stop" },
            notify = { events += "notify" },
        )
        notification.afterDrain(
            stop = { events += "encoder-stop" },
            notify = { events += "notify" },
        )

        assertEquals(listOf("external-stop", "notify", "encoder-stop"), events)
    }

    @Test
    fun serialEncoderFirstExitCallsStopBeforeNotifyingOnce() {
        val notification = VideoEndNotification()
        val events = mutableListOf<String>()

        notification.afterDrain(
            stop = { events += "encoder-stop" },
            notify = { events += "notify" },
        )
        notification.afterDrain(
            stop = { events += "external-stop" },
            notify = { events += "notify" },
        )

        assertEquals(listOf("encoder-stop", "notify", "external-stop"), events)
    }

    @Test
    fun secondExitStopsAgainButDoesNotNotifyAgain() {
        val notification = VideoEndNotification()
        val events = mutableListOf<String>()

        notification.afterDrain(
            stop = { events += "stop:1" },
            notify = { events += "notify" },
        )
        notification.afterDrain(
            stop = { events += "stop:2" },
            notify = { events += "notify" },
        )

        assertEquals(listOf("stop:1", "notify", "stop:2"), events)
    }

    @Test
    fun barrierCompetitorsEachStopAndOnlyWinnerNotifies() {
        val recorder = Recorder()
        val notification = VideoEndNotification()
        val barrier = CyclicBarrier(2)
        val notifyOnce = CountDownLatch(1)
        val workers =
            (1..2).map { id ->
                Worker {
                    notification.afterDrain(
                        stop = {
                            recorder.record("stop-enter:$id")
                            barrier.await(5, TimeUnit.SECONDS)
                            recorder.record("stop-exit:$id")
                        },
                        notify = {
                            recorder.record("notify")
                            notifyOnce.countDown()
                        },
                    )
                }
            }

        runWorkers(workers)

        assertTrue("通知未发生", notifyOnce.await(0, TimeUnit.MILLISECONDS))
        val eventList = recorder.events.toList()
        assertEquals(2, eventList.count { it.startsWith("stop-enter:") })
        assertEquals(2, eventList.count { it.startsWith("stop-exit:") })
        assertEquals(1, eventList.count { it == "notify" })
        val firstStopExit = eventList.indexOfFirst { it.startsWith("stop-exit:") }
        val notifyAt = eventList.indexOf("notify")
        assertTrue("通知只可在某个 stop 返回后尝试", firstStopExit >= 0 && notifyAt > firstStopExit)
    }

    @Test
    fun distinctSendersHaveDistinctNotificationDomains() {
        val first = VideoEndNotification()
        val second = VideoEndNotification()
        val firstEvents = mutableListOf<String>()
        val secondEvents = mutableListOf<String>()

        first.afterDrain(
            stop = { firstEvents += "stop:1" },
            notify = { firstEvents += "notify:1" },
        )
        second.afterDrain(
            stop = { secondEvents += "stop:2" },
            notify = { secondEvents += "notify:2" },
        )

        assertEquals(listOf("stop:1", "notify:1"), firstEvents)
        assertEquals(listOf("stop:2", "notify:2"), secondEvents)
    }

    @Test
    fun notifyLambdaRunsOnTheCallingThread() {
        val notification = VideoEndNotification()
        val callingThread = Thread.currentThread()
        var notifiedOn: Thread? = null

        notification.afterDrain(
            stop = {},
            notify = { notifiedOn = Thread.currentThread() },
        )

        assertSame(callingThread, notifiedOn)
    }

    @Test
    fun stopFailureDoesNotConsumeNotificationOrReadNotify() {
        val notification = VideoEndNotification()
        val failure = ThrowingException("stop")
        var notifyInvocations = 0

        try {
            notification.afterDrain(
                stop = { throw failure },
                notify = { notifyInvocations++ },
            )
        } catch (caught: Throwable) {
            assertSame(failure, caught)
        }

        assertEquals(0, notifyInvocations)
        val events = mutableListOf<String>()
        notification.afterDrain(
            stop = { events += "stop" },
            notify = { events += "notify" },
        )
        assertEquals(listOf("stop", "notify"), events)
    }

    @Test
    fun notifyFailureIsSwallowedAndOnceIsConsumed() {
        val notification = VideoEndNotification()
        val failure = ThrowingException("notify")
        var stopCount = 0

        notification.afterDrain(
            stop = { stopCount++ },
            notify = { throw failure },
        )
        notification.afterDrain(
            stop = { stopCount++ },
            notify = { stopCount = -1 },
        )

        assertEquals(2, stopCount)
    }

    @Test
    fun nullCallbackStillConsumesOnceOnDeferredInvocation() {
        val notification = VideoEndNotification()
        val callback: (() -> Unit)? = null
        var stopCount = 0

        notification.afterDrain(
            stop = { stopCount++ },
            notify = { callback?.invoke() },
        )
        notification.afterDrain(
            stop = { stopCount++ },
            notify = { callback?.invoke() },
        )

        assertEquals(2, stopCount)
    }

    @Test
    fun notifyReentryStillStopsButDoesNotNotifyAgain() {
        val recorder = Recorder()
        val notification = VideoEndNotification()

        notification.afterDrain(
            stop = { recorder.record("outer-stop") },
            notify = {
                recorder.record("outer-notify-enter")
                notification.afterDrain(
                    stop = { recorder.record("inner-stop") },
                    notify = { recorder.record("inner-notify") },
                )
                recorder.record("outer-notify-exit")
            },
        )

        assertEquals(
            listOf("outer-stop", "outer-notify-enter", "inner-stop", "outer-notify-exit"),
            recorder.events.toList(),
        )
    }

    @Test
    fun stopLeafReentryMayWinNotificationBeforeOuterExit() {
        val recorder = Recorder()
        val notification = VideoEndNotification()
        var reentered = false

        notification.afterDrain(
            stop = {
                recorder.record("outer-stop-enter")
                if (!reentered) {
                    reentered = true
                    notification.afterDrain(
                        stop = { recorder.record("inner-stop") },
                        notify = { recorder.record("inner-notify") },
                    )
                }
                recorder.record("outer-stop-exit")
            },
            notify = { recorder.record("outer-notify") },
        )

        assertEquals(
            listOf("outer-stop-enter", "inner-stop", "inner-notify", "outer-stop-exit"),
            recorder.events.toList(),
        )
    }

    @Test
    fun sourceHelperContractPinsDeferredCallbackAndPerSenderOnce() {
        val helper = SourceTree.scan("video/VideoEndNotification.kt").code
        val sender = SourceTree.scan("video/VideoSender.kt").code
        val externalBody = function(sender, "fun drainExternalVideo(")
        val encoderBody = function(sender, "fun drainEncoder(")

        assertTrue(helper.contains("private val endedNotified = AtomicBoolean(false)"))
        assertTrue(helper.contains("fun afterDrain("))
        requireOrder(helper, "stop()", "endedNotified.compareAndSet(false, true)", "stop 先于 CAS")
        requireOrder(
            helper,
            "endedNotified.compareAndSet(false, true)",
            "runCatching { notify() }",
            "CAS 成功才通知",
        )
        assertTrue("onEnded 仍保持 private val 构造参数", sender.contains("private val onEnded: (() -> Unit)? = null,"))
        assertFalse("不得新增 onEnded setter 或可变字段", sender.contains("var onEnded"))
        assertTrue("每个 sender 必须持有通知器实例", sender.contains("private val endNotification = VideoEndNotification()"))
        assertTrue(
            "notify 必须延迟读取 onEnded",
            sender.split("endNotification.afterDrain(stop = { stop() }, notify = { onEnded?.invoke() })").size - 1 == 2,
        )
        assertFalse("不得保留旧共享 CAS 字段", sender.contains("endedNotified"))
        listOf(externalBody, encoderBody).forEach { body ->
            requireOrder(body, "finally", "endNotification.afterDrain(stop = { stop() }", "finally 委托通知器")
            assertFalse("finally 不得直接保留旧 CAS", body.contains("endedNotified.compareAndSet"))
        }
        val stopBody = function(sender, "fun stop(")
        assertFalse("stop 本体不应通知 onEnded", stopBody.contains("onEnded"))
        val quiesceBody = function(sender, "fun quiesceForOrderlyStop(")
        assertFalse("quiesce 不应通知 onEnded", quiesceBody.contains("onEnded"))
        requireOrder(
            externalBody,
            "val output = socket?.getOutputStream() ?: return",
            "finally",
            "透传仍保持 try 前早退边界",
        )
        requireOrder(
            encoderBody,
            "val codec = encoder ?: return",
            "finally",
            "编码仍保持 try 前早退边界",
        )
    }

    private fun runWorkers(workers: List<Worker>) {
        workers.forEach { it.start() }
        workers.forEach { it.join(5_000) }
        workers.forEach { worker ->
            assertFalse("worker did not finish: ${worker.thread}", worker.thread.isAlive)
            worker.error.get()?.let { throw it }
        }
    }
}

private class Recorder {
    val events = java.util.concurrent.ConcurrentLinkedQueue<String>()

    fun record(event: String) {
        events.add(event)
    }
}

private class ThrowingException(
    message: String,
) : RuntimeException(message)

private class Worker(
    body: () -> Unit,
) {
    val thread = Thread({ run(body) }, "video-end-notification-worker")
    val error = AtomicReference<Throwable>()

    private fun run(body: () -> Unit) {
        try {
            body()
        } catch (caught: Throwable) {
            error.compareAndSet(null, caught)
        }
    }

    fun start() {
        thread.start()
    }

    fun join(millis: Long) {
        thread.join(millis)
    }
}
