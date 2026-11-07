package app.vezuna

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.llamatik.core.platform.GenStream
import com.llamatik.core.platform.LlamaBridge
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Run alone in a fresh instrumentation process with exclusive device and native-engine ownership.
 * The runner must enforce a 15-minute process deadline and terminate its owned process on a hang.
 * A pass proves cancel delivery while the callback is held and subsequent reuse, not interruption semantics.
 */
@RunWith(AndroidJUnit4::class)
class NativeCancelDeliveryInstrumentedTest {
    @Test
    fun deliversCancelWhileNativeCallbackIsHeldThenRunsSuccessor() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        assumeTrue("smoke.gguf not staged", assets.list("")?.contains("smoke.gguf") == true)
        val model = File(instrumentation.targetContext.cacheDir, "native-direct-smoke.gguf")
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        assets.open("smoke.gguf").use { input ->
            model.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    size += count
                }
            }
        }
        check(size == MODEL_SIZE && digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) } == MODEL_SHA256) {
            "Staged synthetic fixture identity mismatch"
        }

        val state = State()
        val workers = mutableListOf<Thread>()
        var initialized = false
        var failure: Throwable? = null
        fun start(name: String, block: () -> Unit): Thread = Thread({
            try { block() } catch (t: Throwable) { state.fail("$name threw $t") }
            finally { state.event("$name exited") }
        }, name).apply { isDaemon = true; workers.add(this); start() }
        fun join(worker: Thread, timeout: Long) {
            worker.join(timeout)
            check(!worker.isAlive) { "${worker.name} did not exit within ${timeout}ms; stop the owned process" }
        }
        try {
            val load = start("load") {
                LlamaBridge.updateGenerateParams(0f, 64, 1f, 1, 1f, 2048, 1, true, false, 512, 0)
                check(LlamaBridge.initGenerateModel(model.absolutePath)) { "Model initialization failed" }
                initialized = true
            }
            join(load, LOAD_MS)
            state.checkClean()

            val attempt = Attempt(state, holdFirst = true)
            val generation = start("generation") { attempt.generate() }
            var cancel: Thread? = null
            var phaseFailure: Throwable? = null
            try {
                check(attempt.ready.await(FIRST_DELTA_MS, TimeUnit.MILLISECONDS)) { "No held nonempty callback before deadline" }
                synchronized(state.lock) {
                    check(attempt.held && attempt.terminalCount == 0 && !attempt.returned && state.firstFailure == null) {
                        "Generation terminated or failed before callback was held"
                    }
                    check(attempt.callbackThread === generation) { "Callback did not run on generation thread" }
                    state.eventLocked("cancel requested")
                }
                cancel = start("cancel") {
                    state.event("cancel thread=${Thread.currentThread().name} id=${Thread.currentThread().id}")
                    LlamaBridge.nativeCancelGenerate()
                    synchronized(state.lock) {
                        attempt.cancelReturnedWhileHeld = attempt.held && attempt.terminalCount == 0 && !attempt.returned && state.firstFailure == null
                        state.eventLocked("cancel returned held=${attempt.cancelReturnedWhileHeld}")
                    }
                }
                join(cancel, CANCEL_MS)
                synchronized(state.lock) {
                    check(attempt.cancelReturnedWhileHeld && attempt.held && state.firstFailure == null) {
                        "Cancel did not return successfully while callback remained held"
                    }
                }
            } catch (t: Throwable) {
                phaseFailure = t
            } finally {
                synchronized(state.lock) {
                    if (attempt.held) {
                        attempt.releaseReason = "coordinator"
                        state.eventLocked("coordinator released callback")
                    }
                }
                attempt.release.countDown()
                for (worker in listOfNotNull(generation, cancel)) {
                    try { join(worker, RETURN_MS) }
                    catch (t: Throwable) { if (phaseFailure == null) phaseFailure = t else phaseFailure.addSuppressed(t) }
                }
            }
            phaseFailure?.let { throw it }
            state.checkClean()
            attempt.checkCompleted()

            val successor = Attempt(state, holdFirst = false)
            val next = start("successor") { successor.generate() }
            join(next, SUCCESSOR_MS)
            state.checkClean()
            successor.checkCompleted()
            check(successor.nonblankDeltas > 0) { "Successor produced no nonblank progress" }
        } catch (t: Throwable) {
            failure = t
        } finally {
            if (workers.none { it.isAlive } && initialized) {
                val shutdown = start("shutdown") { LlamaBridge.shutdown() }
                try { join(shutdown, SHUTDOWN_MS); state.checkClean() }
                catch (t: Throwable) { if (failure == null) failure = t else failure.addSuppressed(t) }
            } else if (workers.any { it.isAlive }) {
                val t = IllegalStateException("Native worker remains alive; no successor or shutdown is safe")
                if (failure == null) failure = t else failure.addSuppressed(t)
            }
            println("NATIVE_CANCEL_DELIVERY_EVENTS ${state.snapshot()}")
        }
        failure?.let { throw it }
    }

    private class State {
        val lock = Any()
        var firstFailure: String? = null
        private val events = mutableListOf<String>()
        private var sequence = 0
        fun eventLocked(message: String) { sequence++; if (events.size < 64) events.add("$sequence:${android.os.SystemClock.elapsedRealtimeNanos()}:$message") }
        fun event(message: String) = synchronized(lock) { eventLocked(message) }
        fun fail(message: String) = synchronized(lock) {
            if (firstFailure == null) firstFailure = message.take(512)
            eventLocked("failure: ${message.take(512)}")
        }
        fun checkClean() = synchronized(lock) { check(firstFailure == null) { firstFailure ?: "unknown failure" } }
        fun snapshot() = synchronized(lock) { events.joinToString(" | ") }
    }

    private class Attempt(private val state: State, private val holdFirst: Boolean) : GenStream {
        val ready = CountDownLatch(1)
        val release = CountDownLatch(1)
        var held = false
        var releaseReason: String? = null
        var callbackThread: Thread? = null
        var terminalCount = 0
        var nonblankDeltas = 0
        var extraDeltas = 0
        var returned = false
        var cancelReturnedWhileHeld = false
        private var firstSeen = false

        override fun onDelta(text: String) {
            try {
                var wait = false
                synchronized(state.lock) {
                    if (terminalCount != 0) state.fail("delta after terminal")
                    if (text.isNotBlank()) nonblankDeltas++
                    if (holdFirst && !firstSeen && text.isNotEmpty()) {
                        firstSeen = true
                        callbackThread = Thread.currentThread()
                        held = true
                        state.eventLocked("callback held thread=${callbackThread?.name}")
                        ready.countDown()
                        wait = true
                    } else if (firstSeen) extraDeltas++
                }
                if (wait) {
                    val released = try { release.await(BARRIER_MS, TimeUnit.MILLISECONDS) }
                    catch (t: InterruptedException) {
                        synchronized(state.lock) { releaseReason = "interrupted"; state.fail("callback interrupted") }
                        Thread.currentThread().interrupt()
                        false
                    }
                    synchronized(state.lock) {
                        if (!released && releaseReason != "interrupted") { releaseReason = "timeout"; state.fail("callback barrier timed out") }
                        held = false
                        state.eventLocked("callback resumed reason=$releaseReason")
                    }
                }
            } catch (t: Throwable) { state.fail("callback onDelta threw $t"); ready.countDown() }
        }
        override fun onComplete() {
            try { synchronized(state.lock) { terminalCount++; if (terminalCount != 1) state.fail("duplicate terminal"); state.eventLocked("complete") }; ready.countDown() }
            catch (t: Throwable) { state.fail("callback onComplete threw $t"); ready.countDown() }
        }
        override fun onError(message: String) {
            try { synchronized(state.lock) { terminalCount++; state.fail("error callback: ${message.take(512)}") }; ready.countDown() }
            catch (t: Throwable) { state.fail("callback onError threw $t"); ready.countDown() }
        }
        fun generate() {
            try { LlamaBridge.generateStream(PROMPT, this) }
            finally { synchronized(state.lock) { returned = true; state.eventLocked("generation returned extraDeltas=$extraDeltas") }; ready.countDown() }
        }
        fun checkCompleted() = synchronized(state.lock) {
            check(returned && terminalCount == 1) { "Generation did not return with exactly one terminal callback" }
            if (holdFirst) check(releaseReason == "coordinator" && cancelReturnedWhileHeld) { "Barrier release or cancel delivery invalid" }
        }
    }

    private companion object {
        const val MODEL_SIZE = 491400032L
        const val MODEL_SHA256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db"
        const val PROMPT = "<|im_start|>user\nList several short ways to maintain a healthy routine.<|im_end|>\n<|im_start|>assistant\n"
        const val LOAD_MS = 120_000L
        const val FIRST_DELTA_MS = 120_000L
        const val CANCEL_MS = 5_000L
        const val BARRIER_MS = 15_000L
        const val RETURN_MS = 120_000L
        const val SUCCESSOR_MS = 120_000L
        const val SHUTDOWN_MS = 30_000L
    }
}
