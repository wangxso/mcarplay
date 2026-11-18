package com.mcarplay.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** LegacySessionUiPort 的纯行为契约，不访问 SessionController，避免其 Android Handler 初始化。 */
class LegacySessionUiPortTest {
    private class DialogSpec(
        val id: Int,
    )

    private class TestException(
        message: String,
    ) : RuntimeException(message)

    private class Host<T>(
        initialValue: T?,
    ) {
        val readCount = AtomicInteger()
        val current = AtomicReference<T?>(initialValue)

        val reader: () -> T? =
            {
                readCount.incrementAndGet()
                current.get()
            }
    }

    private class Supplier<T>(
        private val value: T,
    ) {
        val evalCount = AtomicInteger()

        val function: () -> T =
            {
                evalCount.incrementAndGet()
                value
            }
    }

    private class RecordingSink<T> : (T) -> Unit {
        val calls = mutableListOf<T>()
        val threads = mutableListOf<Thread?>()

        override fun invoke(value: T) {
            synchronized(this) {
                calls.add(value)
                threads.add(Thread.currentThread())
            }
        }
    }

    private class RecordingDialogSink<D> : (D, (Boolean?) -> Unit) -> Unit {
        val specs = mutableListOf<D>()
        val callbacks = mutableListOf<(Boolean?) -> Unit>()
        val threads = mutableListOf<Thread?>()

        override fun invoke(
            spec: D,
            callback: (Boolean?) -> Unit,
        ) {
            synchronized(this) {
                specs.add(spec)
                callbacks.add(callback)
                threads.add(Thread.currentThread())
            }
        }
    }

    private class ThrowingHost<T>(
        private val exception: TestException,
    ) : () -> T? {
        var readCount = 0
            private set

        override fun invoke(): T? {
            readCount++
            throw exception
        }
    }

    private class ThrowingSupplier(
        private val exception: TestException,
    ) : () -> String {
        var evalCount = 0
            private set

        override fun invoke(): String {
            evalCount++
            throw exception
        }
    }

    private inline fun <reified T : Throwable> assertThrows(
        expected: T,
        action: () -> Unit,
    ) {
        val thrown =
            try {
                action()
                throw AssertionError("未抛出 ${T::class.simpleName}")
            } catch (actual: Throwable) {
                actual
            }
        assertSame(expected, thrown)
    }

    @Test
    fun constructorDoesNotReadAnyHost() {
        val phaseSink = RecordingSink<String?>()
        val hintHost = Host<((String) -> Unit)?>(null)
        val ssidHost = Host<((String) -> Unit)?>(null)
        val dialogHost = Host<((DialogSpec, (Boolean?) -> Unit) -> Unit)?>(null)
        val dismissHost = Host<((Long) -> Unit)?>(null)

        LegacySessionUiPort<DialogSpec>(
            setPhase = phaseSink,
            hintSink = hintHost.reader,
            ssidSink = ssidHost.reader,
            dialogSink = dialogHost.reader,
            dismissSink = dismissHost.reader,
        )

        assertEquals(0, hintHost.readCount.get())
        assertTrue(phaseSink.calls.isEmpty())
        assertEquals(0, ssidHost.readCount.get())
        assertEquals(0, dialogHost.readCount.get())
        assertEquals(0, dismissHost.readCount.get())
    }

    @Test
    fun phaseWritesValuesSynchronouslyIncludingNull() {
        val phaseSink = RecordingSink<String?>()
        val port =
            LegacySessionUiPort<DialogSpec>(
                setPhase = phaseSink,
                hintSink = { null },
                ssidSink = { null },
                dialogSink = { null },
                dismissSink = { null },
            )

        port.phase("starting")
        port.phase(null)

        assertEquals(listOf("starting", null), phaseSink.calls)
    }

    @Test
    fun hintWithoutHostDoesNotEvaluateSupplier() {
        val supplier = Supplier("hint")
        val port =
            LegacySessionUiPort<DialogSpec>(
                setPhase = {},
                hintSink = { null },
                ssidSink = { null },
                dialogSink = { null },
                dismissSink = { null },
            )

        port.hint(supplier.function)

        assertEquals(0, supplier.evalCount.get())
    }

    @Test
    fun hintReadsHostAndEvaluatesSupplierOncePerCall() {
        val hintSink = RecordingSink<String>()
        val hintHost = Host<((String) -> Unit)?>(hintSink)
        val firstSupplier = Supplier("first")
        val secondSupplier = Supplier("second")
        val port =
            LegacySessionUiPort<DialogSpec>(
                setPhase = {},
                hintSink = hintHost.reader,
                ssidSink = { null },
                dialogSink = { null },
                dismissSink = { null },
            )

        port.hint(firstSupplier.function)
        port.hint(secondSupplier.function)

        assertEquals(2, hintHost.readCount.get())
        assertEquals(1, firstSupplier.evalCount.get())
        assertEquals(1, secondSupplier.evalCount.get())
        assertEquals(listOf("first", "second"), hintSink.calls)
    }

    @Test
    fun hintFollowsHostFromAtoBtoNull() {
        val sinkA = RecordingSink<String>()
        val sinkB = RecordingSink<String>()
        val host = Host<((String) -> Unit)?>(sinkA)
        val supplier = Supplier("hint")
        val port =
            LegacySessionUiPort<DialogSpec>(
                setPhase = {},
                hintSink = host.reader,
                ssidSink = { null },
                dialogSink = { null },
                dismissSink = { null },
            )

        port.hint(supplier.function)
        host.current.set(sinkB)
        port.hint(supplier.function)
        host.current.set(null)
        port.hint(supplier.function)

        assertEquals(3, host.readCount.get())
        assertEquals(2, supplier.evalCount.get())
        assertEquals(listOf("hint"), sinkA.calls)
        assertEquals(listOf("hint"), sinkB.calls)
    }

    @Test
    fun hintKeepsSinkCapturedBeforeSupplierRebindsHost() {
        val sinkA = RecordingSink<String>()
        val sinkB = RecordingSink<String>()
        val host = Host<((String) -> Unit)?>(sinkA)
        val port =
            LegacySessionUiPort<DialogSpec>(
                setPhase = {},
                hintSink = host.reader,
                ssidSink = { null },
                dialogSink = { null },
                dismissSink = { null },
            )

        port.hint {
            host.current.set(sinkB)
            "message-after-rebind"
        }

        assertEquals(listOf("message-after-rebind"), sinkA.calls)
        assertTrue(sinkB.calls.isEmpty())
    }

    @Test
    fun ssidWithoutHostDoesNotEvaluateSupplier() {
        val supplier = Supplier("ssid")
        val port =
            LegacySessionUiPort<DialogSpec>(
                setPhase = {},
                hintSink = { null },
                ssidSink = { null },
                dialogSink = { null },
                dismissSink = { null },
            )

        port.ssidChanged(supplier.function)

        assertEquals(0, supplier.evalCount.get())
    }

    @Test
    fun ssidReadsHostAndEvaluatesSupplierOncePerCall() {
        val ssidSink = RecordingSink<String>()
        val ssidHost = Host<((String) -> Unit)?>(ssidSink)
        val firstSupplier = Supplier("first")
        val secondSupplier = Supplier("second")
        val port =
            LegacySessionUiPort<DialogSpec>(
                setPhase = {},
                hintSink = { null },
                ssidSink = ssidHost.reader,
                dialogSink = { null },
                dismissSink = { null },
            )

        port.ssidChanged(firstSupplier.function)
        port.ssidChanged(secondSupplier.function)

        assertEquals(2, ssidHost.readCount.get())
        assertEquals(1, firstSupplier.evalCount.get())
        assertEquals(1, secondSupplier.evalCount.get())
        assertEquals(listOf("first", "second"), ssidSink.calls)
    }

    @Test
    fun ssidFollowsHostFromAtoBtoNull() {
        val sinkA = RecordingSink<String>()
        val sinkB = RecordingSink<String>()
        val host = Host<((String) -> Unit)?>(sinkA)
        val supplier = Supplier("ssid")
        val port =
            LegacySessionUiPort<DialogSpec>(
                setPhase = {},
                hintSink = { null },
                ssidSink = host.reader,
                dialogSink = { null },
                dismissSink = { null },
            )

        port.ssidChanged(supplier.function)
        host.current.set(sinkB)
        port.ssidChanged(supplier.function)
        host.current.set(null)
        port.ssidChanged(supplier.function)

        assertEquals(3, host.readCount.get())
        assertEquals(2, supplier.evalCount.get())
        assertEquals(listOf("ssid"), sinkA.calls)
        assertEquals(listOf("ssid"), sinkB.calls)
    }

    @Test
    fun ssidKeepsSinkCapturedBeforeSupplierRebindsHost() {
        val sinkA = RecordingSink<String>()
        val sinkB = RecordingSink<String>()
        val host = Host<((String) -> Unit)?>(sinkA)
        val port =
            LegacySessionUiPort<DialogSpec>(
                setPhase = {},
                hintSink = { null },
                ssidSink = host.reader,
                dialogSink = { null },
                dismissSink = { null },
            )

        port.ssidChanged {
            host.current.set(sinkB)
            "ssid-after-rebind"
        }

        assertEquals(listOf("ssid-after-rebind"), sinkA.calls)
        assertTrue(sinkB.calls.isEmpty())
    }

    @Test
    fun askWithoutHostReturnsFalseAndDoesNotInvokeCallback() {
        val callback: (Boolean?) -> Unit =
            {
                throw AssertionError("callback must not run without a host")
            }
        val dialogHost = Host<((DialogSpec, (Boolean?) -> Unit) -> Unit)?>(null)
        val port =
            LegacySessionUiPort<DialogSpec>(
                setPhase = {},
                hintSink = { null },
                ssidSink = { null },
                dialogSink = dialogHost.reader,
                dismissSink = { null },
            )

        val handled = port.ask(DialogSpec(1), callback)

        assertFalse(handled)
        assertEquals(1, dialogHost.readCount.get())
    }

    @Test
    fun askPassesSameSpecAndCallbackToDialogSink() {
        val spec = DialogSpec(42)
        val callback: (Boolean?) -> Unit = {}
        val dialogSink = RecordingDialogSink<DialogSpec>()
        val dialogHost = Host<((DialogSpec, (Boolean?) -> Unit) -> Unit)?>(dialogSink)
        val port =
            LegacySessionUiPort<DialogSpec>(
                setPhase = {},
                hintSink = { null },
                ssidSink = { null },
                dialogSink = dialogHost.reader,
                dismissSink = { null },
            )

        val handled = port.ask(spec, callback)

        assertTrue(handled)
        assertEquals(1, dialogHost.readCount.get())
        assertEquals(1, dialogSink.specs.size)
        assertSame(spec, dialogSink.specs.single())
        assertSame(callback, dialogSink.callbacks.single())
    }

    @Test
    fun askForwardsTrueFalseAndNullChoices() {
        val choices = mutableListOf<Boolean?>()
        val callback: (Boolean?) -> Unit = choices::add
        val pendingChoices = ArrayDeque(listOf<Boolean?>(true, false, null))
        val callbackRecords = mutableListOf<(Boolean?) -> Unit>()
        val dialogSink: (DialogSpec, (Boolean?) -> Unit) -> Unit = { _, dialogCallback ->
            callbackRecords.add(dialogCallback)
            dialogCallback(pendingChoices.removeFirst())
        }
        val dialogHost = Host<((DialogSpec, (Boolean?) -> Unit) -> Unit)?>(dialogSink)
        val port =
            LegacySessionUiPort<DialogSpec>(
                setPhase = {},
                hintSink = { null },
                ssidSink = { null },
                dialogSink = dialogHost.reader,
                dismissSink = { null },
            )

        assertTrue(port.ask(DialogSpec(1), callback))
        assertTrue(port.ask(DialogSpec(2), callback))
        assertTrue(port.ask(DialogSpec(3), callback))

        assertEquals(listOf(true, false, null), choices)
        assertEquals(3, callbackRecords.size)
        assertSame(callback, callbackRecords[0])
        assertSame(callback, callbackRecords[1])
        assertSame(callback, callbackRecords[2])
    }

    @Test
    fun askFollowsHostFromAtoBtoNull() {
        val sinkA = RecordingDialogSink<DialogSpec>()
        val sinkB = RecordingDialogSink<DialogSpec>()
        val specA = DialogSpec(100)
        val specB = DialogSpec(200)
        val callback: (Boolean?) -> Unit =
            {
                throw AssertionError("callback must not run without a dialog sink")
            }
        val dialogHost = Host<((DialogSpec, (Boolean?) -> Unit) -> Unit)?>(sinkA)
        val port =
            LegacySessionUiPort<DialogSpec>(
                setPhase = {},
                hintSink = { null },
                ssidSink = { null },
                dialogSink = dialogHost.reader,
                dismissSink = { null },
            )

        assertTrue(port.ask(specA, callback))
        assertEquals(1, dialogHost.readCount.get())

        dialogHost.current.set(sinkB)
        assertTrue(port.ask(specB, callback))
        assertEquals(2, dialogHost.readCount.get())

        dialogHost.current.set(null)
        assertFalse(port.ask(specA, callback))
        assertEquals(3, dialogHost.readCount.get())

        assertSame(specA, sinkA.specs.single())
        assertSame(callback, sinkA.callbacks.single())
        assertSame(specB, sinkB.specs.single())
        assertSame(callback, sinkB.callbacks.single())
    }

    @Test
    fun dismissWithoutHostDoesNotInvokeSink() {
        val dismissHost = Host<((Long) -> Unit)?>(null)
        val port =
            LegacySessionUiPort<DialogSpec>(
                setPhase = {},
                hintSink = { null },
                ssidSink = { null },
                dialogSink = { null },
                dismissSink = dismissHost.reader,
            )

        port.dismiss(1001)

        assertEquals(1, dismissHost.readCount.get())
    }

    @Test
    fun dismissForwardsExactIdsAndFollowsHostRebinding() {
        val sinkA = RecordingSink<Long>()
        val sinkB = RecordingSink<Long>()
        val dismissHost = Host<((Long) -> Unit)?>(sinkA)
        val port =
            LegacySessionUiPort<DialogSpec>(
                setPhase = {},
                hintSink = { null },
                ssidSink = { null },
                dialogSink = { null },
                dismissSink = dismissHost.reader,
            )

        port.dismiss(1001)
        dismissHost.current.set(sinkB)
        port.dismiss(2002)
        dismissHost.current.set(null)
        port.dismiss(3003)

        assertEquals(listOf(1001L), sinkA.calls)
        assertEquals(listOf(2002L), sinkB.calls)
        assertEquals(3, dismissHost.readCount.get())
    }

    @Test
    fun adaptersRunOnCallingThread() {
        val phaseSink = RecordingSink<String?>()
        val hintSink = RecordingSink<String>()
        val ssidSink = RecordingSink<String>()
        val dialogSink = RecordingDialogSink<DialogSpec>()
        val dismissSink = RecordingSink<Long>()
        val port =
            LegacySessionUiPort<DialogSpec>(
                setPhase = phaseSink,
                hintSink = { hintSink },
                ssidSink = { ssidSink },
                dialogSink = { dialogSink },
                dismissSink = { dismissSink },
            )
        val callingThread = Thread.currentThread()

        port.phase("phase")
        port.hint { "hint" }
        port.ssidChanged { "ssid" }
        port.ask(DialogSpec(1)) {}
        port.dismiss(1001)

        assertEquals(listOf<Thread?>(callingThread), phaseSink.threads)
        assertEquals(listOf<Thread?>(callingThread), hintSink.threads)
        assertEquals(listOf<Thread?>(callingThread), ssidSink.threads)
        assertEquals(listOf<Thread?>(callingThread), dialogSink.threads)
        assertEquals(listOf<Thread?>(callingThread), dismissSink.threads)
    }

    @Test
    fun phasePropagatesSinkExceptionIdentityOnCallingThread() {
        val exception = TestException("phase sink failed")
        var sinkThread: Thread? = null
        val port =
            LegacySessionUiPort<DialogSpec>(
                setPhase = {
                    sinkThread = Thread.currentThread()
                    throw exception
                },
                hintSink = { null },
                ssidSink = { null },
                dialogSink = { null },
                dismissSink = { null },
            )

        assertThrows(exception) { port.phase("phase") }
        assertSame(Thread.currentThread(), sinkThread)
    }

    @Test
    fun hintPropagatesSupplierExceptionIdentityWithoutCallingSink() {
        val exception = TestException("hint supplier failed")
        val supplier = ThrowingSupplier(exception)
        val hintSink = RecordingSink<String>()
        val port =
            LegacySessionUiPort<DialogSpec>(
                setPhase = {},
                hintSink = { hintSink },
                ssidSink = { null },
                dialogSink = { null },
                dismissSink = { null },
            )

        assertThrows(exception) { port.hint(supplier) }

        assertEquals(1, supplier.evalCount)
        assertTrue(hintSink.calls.isEmpty())
    }

    @Test
    fun hintPropagatesSinkExceptionIdentity() {
        val exception = TestException("hint sink failed")
        val port =
            LegacySessionUiPort<DialogSpec>(
                setPhase = {},
                hintSink = { throw exception },
                ssidSink = { null },
                dialogSink = { null },
                dismissSink = { null },
            )

        assertThrows(exception) { port.hint { "hint" } }
    }

    @Test
    fun ssidPropagatesSinkExceptionIdentity() {
        val exception = TestException("ssid sink failed")
        val port =
            LegacySessionUiPort<DialogSpec>(
                setPhase = {},
                hintSink = { null },
                ssidSink = { throw exception },
                dialogSink = { null },
                dismissSink = { null },
            )

        assertThrows(exception) { port.ssidChanged { "ssid" } }
    }

    @Test
    fun dialogHostReaderExceptionsPropagate() {
        val exception = TestException("dialog reader failed")
        val dialogHost = ThrowingHost<((DialogSpec, (Boolean?) -> Unit) -> Unit)>(exception)
        val port =
            LegacySessionUiPort<DialogSpec>(
                setPhase = {},
                hintSink = { null },
                ssidSink = { null },
                dialogSink = dialogHost,
                dismissSink = { null },
            )

        assertThrows(exception) { port.ask(DialogSpec(1)) {} }

        assertEquals(1, dialogHost.readCount)
    }

    @Test
    fun askPropagatesCallbackExceptionIdentity() {
        val exception = TestException("callback failed")
        val dialogSink: (DialogSpec, (Boolean?) -> Unit) -> Unit = { _, callback ->
            callback(true)
        }
        val port =
            LegacySessionUiPort<DialogSpec>(
                setPhase = {},
                hintSink = { null },
                ssidSink = { null },
                dialogSink = { dialogSink },
                dismissSink = { null },
            )

        assertThrows(exception) {
            port.ask(DialogSpec(1)) { throw exception }
        }
    }

    @Test
    fun dismissPropagatesSinkExceptionIdentity() {
        val exception = TestException("dismiss sink failed")
        val port =
            LegacySessionUiPort<DialogSpec>(
                setPhase = {},
                hintSink = { null },
                ssidSink = { null },
                dialogSink = { null },
                dismissSink = { throw exception },
            )

        assertThrows(exception) { port.dismiss(1001) }
    }
}
