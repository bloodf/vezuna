package app.vezuna

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.vezuna.license.PlayLvlLicenseGate
import app.vezuna.llm.AndroidDeviceCapacity
import app.vezuna.llm.AndroidModelFileStore
import app.vezuna.llm.InstalledModel
import app.vezuna.llm.InstalledModelManager
import app.vezuna.llm.LlamatikLocalLlm
import app.vezuna.llm.ModelCatalog
import app.vezuna.llm.ModelPackSource
import app.vezuna.llm.ModelPackLoadState
import app.vezuna.llm.PlayAssetPackLoader
import app.vezuna.llm.SavedModelSelection
import app.vezuna.localization.L10nKey
import app.vezuna.localization.VitaMessageException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/** No Activity, application repository, model fixture copy, raw prompt or second adapter. */
@RunWith(AndroidJUnit4::class)
class LocalPadRuntimeInstrumentedTest {
    @Test
    fun verifiedPadSevenTypedHealthCasesAndLifecycle() {
        val started = monotonicMs()
        val controller = ResearchController(started)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val testContext = instrumentation.context
        val args = InstrumentationRegistry.getArguments()
        check(target.packageName == LocalPadResearchFixture.TARGET)
        check(testContext.packageName == LocalPadResearchFixture.TEST_PACKAGE)
        check(android.os.Process.myUid() == target.applicationInfo.uid) { "EXECUTION_UID_MISMATCH" }
        val runId = required(args, "localPadRunId")
        check(runId.matches(Regex("[a-zA-Z0-9_-]{1,64}")))
        required(args, "localPadDeviceLease")
        check(required(args, "localPadRunnerSha256").matches(Regex("[0-9a-f]{64}")))
        val expected = Json.parseToJsonElement(required(args, "localPadArtifactIdentity")).jsonObject
        check(expected.keys == setOf("target", "test"))
        check(expected.toString().contains("libllama")) { "PINNED_NATIVE_IDENTITY_REQUIRED" }
        val directory = File(target.noBackupFilesDir, "local-pad-research/$runId")
        check(directory.mkdir() || (!directory.exists() && directory.mkdirs())) { "RUN_DIRECTORY_MUST_BE_NEW" }
        val evidence = ResearchEvidence(directory)
        controller.evidence = evidence
        var primary: Throwable? = null
        var sampler: ResearchSampler? = null
        var manager: InstalledModelManager? = null
        var finalUnloadReturned = false
        var completed = 0
        try {
            evidence.event("START", buildJsonObject {
                put("runId", runId); put("identity", processIdentity())
                put("startMs", started); put("globalEndMs", controller.end)
                put("workEndMs", controller.workEnd); put("teardownReserveMs", 120_000)
                put("deviceLease", required(args, "localPadDeviceLease"))
                put("runnerSha256", required(args, "localPadRunnerSha256"))
                put("pinnedArtifacts", expected)
                put("receipts", "SOURCE-DERIVED; literal lease and receipt IDs unavailable")
            })
            val cases = controller.stage("FIXTURE_AND_ARTIFACT_PREFLIGHT", 60_000) {
                check(artifactIdentity(target, testContext) == expected) { "APK_NATIVE_IDENTITY_MISMATCH" }
                LocalPadResearchFixture.cases().also {
                    check(it.map { case -> case.id } == listOf(
                        "EN_SMALL", "EN_MAX_BYTES", "PT_SMALL", "PT_MAX_BYTES", "ES_SMALL", "ES_MAX_BYTES", "EN_SUCCESSOR",
                    ))
                    it.forEach { case ->
                        evidence.event("FIXTURE", buildJsonObject {
                            put("id", case.id); put("language", case.language.name)
                            put("renderedBytes", LocalPadResearchFixture.renderedBytes(case.prompt))
                            put("system", case.prompt.system); put("userData", case.prompt.userData)
                            put("expectedEnvelope", LocalPadResearchFixture.envelope(case.prompt))
                        })
                    }
                }
            }
            // The independent watchdog must observe START, verify UID/PID/start and acknowledge
            // ownership before any PAD/model access. The token is coordination, not proof by itself.
            controller.stage("WAIT_HOST_OWNERSHIP_ACK", 30_000) {
                val ack = File(directory, "host-ownership-ack.json")
                while (!ack.isFile) kotlinx.coroutines.delay(100)
                check(ack.length() in 1..4096) { "HOST_ACK_STORAGE_LIMIT" }
                val value = Json.parseToJsonElement(ack.readText()).jsonObject
                check(value == buildJsonObject {
                    put("runId", runId); put("identity", processIdentity())
                    put("runnerSha256", required(args, "localPadRunnerSha256"))
                }) { "HOST_OWNERSHIP_ACK_MISMATCH" }
            }
            val measurements = ResearchSampler(target, evidence, controller.end)
            sampler = measurements
            measurements.start()
            measurements.boundary("BEFORE_DELIVERY", controller)
            val files = AndroidModelFileStore(target)
            val llm = LlamatikLocalLlm() // The sole real adapter in this experiment process.
            val admission = ResearchOnlyLoadAdmission(target, testContext, expected, evidence)
            val models = InstalledModelManager(
                ModelCatalog(listOf(LocalPadResearchFixture.pack)), files, llm, AndroidDeviceCapacity(target), admission,
            )
            manager = models
            controller.stage("BEFORE_DELIVERY_FIT", 5_000) {
                // A pristine, root-owned package is a prerequisite. Never overwrite existing research state.
                check(files.installed().isEmpty() && files.savedSelection() == null) { "PRISTINE_MODEL_STORE_REQUIRED" }
                check(models.checkFit(LocalPadResearchFixture.pack)) { "BEFORE_DELIVERY_FIT_REFUSED ${models.state.value.error}" }
                evidence.event("FIT", buildJsonObject {
                    put("minimumRamBytes", LocalPadResearchFixture.pack.minimumRamBytes)
                    put("meaning", "SCHEMA_ONLY_RESEARCH_VALUE; PRODUCTION_FIT_NOT_EVALUATED")
                })
            }
            val loader = PlayAssetPackLoader(target, PlayLvlLicenseGate(target))
            var sourcePath: String? = null
            controller.stage("DELIVERY_AND_VERIFIED_COPY", 360_000) {
                val observer = launch {
                    loader.state.collect { state -> evidence.event("PAD_STATE", buildJsonObject { put("state", state.toString()) }) }
                }
                var acquired: ModelPackSource? = null
                var sourceFailure: Throwable? = null
                try {
                    controller.withinStage("PAD_DELIVERY", 180_000) {
                        loader.load(LocalPadResearchFixture.pack).also { acquired = it }
                    }
                    val source = acquired ?: error("PAD_SOURCE_MISSING ${loader.state.value}")
                    sourcePath = source.path
                    check(loader.state.value == ModelPackLoadState.Ready(LocalPadResearchFixture.pack.modelId))
                    evidence.event("SOURCE_ACQUIRED", buildJsonObject {
                        put("path", source.path); put("androidPackId", "vezuna_ai")
                        put("deliveryOrigin", "UNKNOWN; Ready may be preexisting; conflated StateFlow cannot prove fetch")
                        put("licensing", "localPad debug bypass; real Play license NOT_EVALUATED")
                    })
                    measurements.boundary("AFTER_DELIVERY_BEFORE_VERIFIED_COPY", controller)
                    controller.withinStage("VERIFIED_PRIVATE_COPY", 180_000) {
                        models.install(LocalPadResearchFixture.pack, source.path, System.currentTimeMillis() / 1000)
                    }
                } catch (failure: Throwable) {
                    sourceFailure = failure
                    throw failure
                } finally {
                    var cleanupFailure: Throwable? = null
                    try {
                        acquired?.let { source ->
                            source.release() // The sole release call, including acquisition/timeout/install failure.
                            evidence.event("SOURCE_RELEASE_CALLED", buildJsonObject { put("calls", 1); put("storageReclaimed", false) })
                        }
                    } catch (release: Throwable) { cleanupFailure = release }
                    try { observer.cancelAndJoin() } catch (observerFailure: Throwable) {
                        cleanupFailure = preserve(cleanupFailure, observerFailure)
                    }
                    cleanupFailure?.let { cleanup ->
                        val original = sourceFailure
                        if (original == null) throw cleanup else original.addSuppressed(cleanup)
                    }
                }
            }
            measurements.boundary("AFTER_VERIFIED_COPY", controller)
            val installed = controller.stage("VERIFY_PRIVATE_IDENTITY", 120_000) {
                val record = files.installed().single()
                check(record.pack == LocalPadResearchFixture.pack)
                val privateFile = File(record.localPath)
                check(privateFile.isAbsolute)
                check(privateFile.canonicalFile.parentFile == File(target.noBackupFilesDir, "models").canonicalFile)
                check(privateFile.canonicalPath != File(checkNotNull(sourcePath)).canonicalPath)
                check(privateFile.length() == record.pack.sizeBytes)
                check(files.verifyIntegrity(record))
                evidence.event("PRIVATE_IDENTITY", buildJsonObject {
                    put("path", record.localPath); put("sizeBytes", privateFile.length()); put("sha256", record.pack.sha256)
                    put("integrity", "AndroidModelFileStore.verifyIntegrity returned true")
                })
                record
            }
            measurements.boundary("BEFORE_FIRST_LOAD", controller)
            controller.stage("SELECT_FIRST_LOAD", 120_000) {
                models.select(LocalPadResearchFixture.pack.modelId, LocalPadResearchFixture.settings)
                assertState(models, files, installed, loaded = true)
                check(admission.admitted == 1)
            }
            measurements.boundary("AFTER_FIRST_LOAD", controller)
            for (case in cases.take(6)) {
                controller.stage("GENERATE_${case.id}", 60_000) {
                    generate(case, llm, evidence)
                    assertState(models, files, installed, loaded = true)
                }
                completed++
            }
            measurements.boundary("BEFORE_FIRST_UNLOAD", controller)
            controller.stage("FIRST_UNLOAD", 60_000) {
                models.unload()
                assertState(models, files, installed, loaded = false)
            }
            measurements.boundary("AFTER_FIRST_UNLOAD", controller)
            controller.stage("UNLOADED_NEGATIVE", 10_000) {
                var answered = false
                val failure = try {
                    llm.generateStream(cases.first().prompt).collect { if (it.isNotEmpty()) answered = true }
                    null
                } catch (failure: VitaMessageException) { failure }
                check(!answered && failure?.userText?.key == L10nKey.CHAT_NEEDS_MODEL) { "UNLOADED_NEGATIVE_FAILED" }
                assertState(models, files, installed, loaded = false)
                evidence.event("UNLOADED_CHAT_NEEDS_MODEL")
            }
            measurements.boundary("BEFORE_SUCCESSOR_LOAD", controller)
            controller.stage("REFRESH_SUCCESSOR_LOAD", 120_000) {
                models.refresh() // Absorbs expected failures; state assertions below are mandatory.
                assertState(models, files, installed, loaded = true)
                check(admission.admitted == 2)
            }
            measurements.boundary("AFTER_SUCCESSOR_LOAD", controller)
            controller.stage("GENERATE_EN_SUCCESSOR", 60_000) {
                generate(cases.last(), llm, evidence)
                assertState(models, files, installed, loaded = true)
            }
            completed++
            measurements.boundary("BEFORE_FINAL_UNLOAD", controller)
            controller.stage("FINAL_UNLOAD", 60_000, teardown = true) {
                models.unload()
                assertState(models, files, installed, loaded = false)
            }
            finalUnloadReturned = true
            measurements.boundary("AFTER_FINAL_UNLOAD", controller, teardown = true)
            check(completed == 7)
        } catch (failure: Throwable) {
            primary = failure
            evidence.failure("PRIMARY_FAILURE", failure)
        } finally {
            // Never enqueue native work after an unresolved worker or cancellation signal.
            if (manager != null && !finalUnloadReturned && !controller.unresolved) {
                try {
                    controller.stage("FAILURE_UNLOAD", 60_000, teardown = true) {
                        manager.unload()
                        check(!manager.state.value.busy && manager.state.value.error == null && manager.state.value.loadedModelId == null)
                    }
                } catch (teardown: Throwable) {
                    primary = preserve(primary, teardown)
                    evidence.failure("TEARDOWN_FAILURE", teardown)
                }
            }
            try { sampler?.stopAndJoin(controller) } catch (teardown: Throwable) {
                controller.unresolved = true
                primary = preserve(primary, teardown)
                evidence.failure("SAMPLER_TEARDOWN_FAILURE", teardown)
            }
            if (controller.unresolved) {
                val unresolved = IllegalStateException("TEARDOWN_UNRESOLVED; retain device/process ownership; host must reconcile actual exit")
                primary = preserve(primary, unresolved)
                evidence.failure("TEARDOWN_UNRESOLVED", unresolved)
            }
        }
        primary?.let { throw it }
        check(monotonicMs() < controller.end && finalUnloadReturned && completed == 7)
        evidence.event("LOCAL_PAD_SEVEN_CASES_COMPLETED", buildJsonObject {
            put("completed", completed); put("endMs", monotonicMs())
            put("processExit", "PENDING_INDEPENDENT_HOST_CONFIRMATION")
            put("productionFit", "PRODUCTION_FIT_NOT_EVALUATED")
            put("headroom", "NUMERICAL_MODEL_HEADROOM_NOT_EVALUATED")
        })
        instrumentation.sendStatus(0, Bundle().apply { putString("localPadCompletion", runId) })
    }

    private suspend fun assertState(models: InstalledModelManager, files: AndroidModelFileStore, installed: InstalledModel, loaded: Boolean) {
        val state = models.state.value
        check(!state.busy && state.error == null && state.catalogError == null && !state.metadataNeedsRefresh)
        check(state.catalog.packs == listOf(LocalPadResearchFixture.pack))
        check(state.installed == listOf(installed) && files.installed() == listOf(installed))
        check(state.selection == SavedModelSelection.Known(installed.pack.modelId))
        check(state.selectedModelId == installed.pack.modelId && files.savedSelection() == installed.pack.modelId)
        check(state.loadedModelId == installed.pack.modelId.takeIf { loaded })
    }

    private suspend fun generate(case: LocalPadResearchFixture.Case, llm: LlamatikLocalLlm, evidence: ResearchEvidence) {
        val start = monotonicMs()
        var firstPartial: Long? = null
        var nonblank = false
        // File operations and memory sampling never run in a native GenStream callback.
        // The collector persists the complete synthetic stream with a hard storage failure ceiling.
        evidence.output(case.id).use { output ->
            llm.generateStream(case.prompt).collect { chunk ->
                if (chunk.isNotEmpty() && firstPartial == null) firstPartial = monotonicMs()
                if (chunk.isNotBlank()) nonblank = true
                output.write(chunk)
            }
        } // Collection returns only after the adapter's structured native worker returns.
        check(nonblank) { "EMPTY_OUTPUT_${case.id}" }
        evidence.event("ANSWER_COMPLETED", buildJsonObject {
            put("id", case.id); put("startMs", start); put("endMs", monotonicMs())
            firstPartial?.let { put("firstNonemptyCollectorChunkMs", it) }
            put("meaning", "collector partial, not native first token; no clinical quality conclusion")
        })
    }
}

private fun required(args: Bundle, name: String): String = args.getString(name)?.takeIf { it.isNotBlank() }
    ?: error("MISSING_REVIEWED_RUNNER_INPUT_$name")
private fun preserve(primary: Throwable?, additional: Throwable): Throwable = primary?.also {
    if (it !== additional) it.addSuppressed(additional)
} ?: additional

/** Finite storage. Overflow or any I/O failure fails the run; partial files remain and are never
 * called full outputs. No overwrite, deletion, silent truncation or metrics work in callbacks. */
internal class ResearchEvidence(val directory: File) {
    private var eventBytes = 0L
    @Synchronized fun event(name: String, fields: JsonObject = buildJsonObject {}) {
        val row = buildJsonObject { put("event", name); put("atMs", monotonicMs()); put("fields", fields) }.toString() + "\n"
        val bytes = row.toByteArray(Charsets.UTF_8)
        check(eventBytes + bytes.size <= 8L * 1024 * 1024) { "EVIDENCE_STORAGE_LIMIT" }
        FileOutputStream(File(directory, "events.jsonl"), true).use { it.write(bytes) }
        eventBytes += bytes.size
        Log.i("LocalPadResearch", "$name atMs=${monotonicMs()}")
    }
    fun failure(name: String, failure: Throwable) {
        try { event(name, buildJsonObject { put("failure", failure.stackTraceToString()) }) }
        catch (storage: Throwable) {
            if (storage !== failure) failure.addSuppressed(storage)
            Log.e("LocalPadResearch", "$name EVIDENCE_STORAGE_FAILURE", failure)
            System.err.println(failure.stackTraceToString())
        }
    }
    fun output(id: String): java.io.Writer {
        val file = File(directory, "$id.txt")
        check(file.createNewFile())
        val stream = FileOutputStream(file)
        val bounded = object : java.io.OutputStream() {
            var count = 0L
            override fun write(value: Int) = write(byteArrayOf(value.toByte()))
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                check(count + length <= 1024L * 1024) { "FULL_OUTPUT_STORAGE_LIMIT_$id; partial file retained, run FAIL" }
                stream.write(bytes, offset, length)
                count += length
            }
            override fun close() { stream.use { it.fd.sync() } }
        }
        // Preserve supplementary characters even when a native stream splits a surrogate pair
        // across chunks. Reject malformed output instead of silently replacing characters.
        return java.io.OutputStreamWriter(bounded, Charsets.UTF_8.newEncoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT))
    }
}

/** Controller deadlines bound waiting, never claim to bound native execution. A worker thread is
 * actually joined before any later stage. Timeout cancellation runs separately because nativeCancel
 * itself may block. Unresolved worker/canceller means no further native commands and host ownership. */
internal class ResearchController(start: Long) {
    val end = start + 900_000
    val workEnd = end - 120_000
    var evidence: ResearchEvidence? = null
    var unresolved = false
    @Volatile private var innerDeadline = Long.MAX_VALUE

    suspend fun <T> withinStage(name: String, maximum: Long, action: suspend () -> T): T {
        // Cooperative child deadline; the outer worker/controller and independent host supervise it.
        val allowed = minOf(maximum, innerDeadline - monotonicMs())
        check(allowed > 0) { "GLOBAL_DEADLINE_$name" }
        evidence?.event("SUBSTAGE_START", buildJsonObject { put("name", name); put("deadlineMs", monotonicMs() + allowed) })
        val value = kotlinx.coroutines.withTimeout(allowed) { action() }
        evidence?.event("SUBSTAGE_RETURNED", buildJsonObject { put("name", name) })
        return value
    }

    fun <T> stage(name: String, maximum: Long, teardown: Boolean = false, action: suspend kotlinx.coroutines.CoroutineScope.() -> T): T {
        check(!unresolved) { "TEARDOWN_UNRESOLVED" }
        val deadline = minOf(monotonicMs() + maximum, if (teardown) end else workEnd)
        check(deadline > monotonicMs()) { "GLOBAL_DEADLINE_$name" }
        innerDeadline = deadline
        evidence?.event("STAGE_START", buildJsonObject { put("name", name); put("deadlineMs", deadline) })
        val job = Job()
        val outcome = AtomicReference<Result<T>>()
        val worker = Thread({
            outcome.set(runCatching { runBlocking(job) {
                check(monotonicMs() < deadline) { "STAGE_START_AFTER_DEADLINE_$name" }
                action()
            } })
        }, "localPad-$name")
        worker.start()
        try { worker.join((deadline - monotonicMs()).coerceAtLeast(1)) }
        catch (interrupted: InterruptedException) {
            unresolved = true
            Thread({
                try { job.cancel() } catch (cancellation: Throwable) {
                    interrupted.addSuppressed(cancellation)
                    evidence?.failure("INTERRUPTED_CANCELLATION_FAILURE", cancellation)
                }
            }, "localPad-interrupted-cancel").apply { isDaemon = true; start() }
            throw interrupted
        }
        if (worker.isAlive) {
            val timeout = TimeoutException("STAGE_DEADLINE_$name")
            evidence?.failure("STAGE_DEADLINE", timeout)
            val cancellationFailure = AtomicReference<Throwable?>()
            val canceller = Thread({ cancellationFailure.set(runCatching { job.cancel() }.exceptionOrNull()) },
                "localPad-cancel-$name").apply { isDaemon = true; start() }
            val reconcileEnd = minOf(end, monotonicMs() + 10_000)
            try {
                worker.join((reconcileEnd - monotonicMs()).coerceAtLeast(1))
                canceller.join((reconcileEnd - monotonicMs()).coerceAtLeast(1))
            } catch (interrupted: InterruptedException) {
                unresolved = true
                timeout.addSuppressed(interrupted)
                throw timeout
            }
            cancellationFailure.get()?.let { timeout.addSuppressed(it) }
            unresolved = worker.isAlive || canceller.isAlive
            outcome.get()?.exceptionOrNull()?.let { timeout.addSuppressed(it) }
            try {
                evidence?.event("DEADLINE_RECONCILIATION", buildJsonObject {
                    put("name", name); put("workerReturned", !worker.isAlive); put("cancelReturned", !canceller.isAlive)
                    put("ownership", if (unresolved) "TEARDOWN_UNRESOLVED" else "WORKER_JOINED_AFTER_FAILURE")
                })
            } catch (storage: Throwable) { timeout.addSuppressed(storage) }
            throw timeout // Late completion never erases deadline failure.
        }
        val result = checkNotNull(outcome.get())
        if (monotonicMs() >= deadline) {
            val timeout = TimeoutException("STAGE_RETURNED_AFTER_DEADLINE_$name")
            result.exceptionOrNull()?.let { timeout.addSuppressed(it) }
            throw timeout
        }
        try { evidence?.event("STAGE_RETURNED", buildJsonObject { put("name", name); put("success", result.isSuccess) }) }
        catch (storage: Throwable) { throw preserve(result.exceptionOrNull(), storage) }
        return result.getOrThrow()
    }
}

/** One measurement worker, no queued periodic catch-up. Boundary requests are acknowledged only
 * after a real sample completes. Missing mandatory samples and read errors fail measurement. */
internal class ResearchSampler(private val context: Context, private val evidence: ResearchEvidence, private val end: Long) {
    private data class Boundary(val label: String, val done: CompletableFuture<Unit>)
    private val requests = LinkedBlockingQueue<Boundary>(8)
    private val failure = AtomicReference<Throwable?>()
    @Volatile private var stopped = false
    private val thread = Thread({
        var previousStart: Long? = null
        try {
            while (!stopped && monotonicMs() < end) {
                val request = requests.poll(1000, TimeUnit.MILLISECONDS)
                if (stopped || monotonicMs() >= end) break
                val start = monotonicMs()
                try {
                    val system = memory(context)
                    val metrics = processMetrics()
                    val identity = processIdentity()
                    val finish = monotonicMs()
                    evidence.event("MEMORY_SAMPLE", buildJsonObject {
                        put("boundary", request?.label ?: "PERIODIC")
                        put("startMs", start); put("endMs", finish); put("readDurationMs", finish - start)
                        previousStart?.let { put("startGapMs", start - it) }
                        put("targetCadenceMs", 1000); put("system", system.json()); put("process", metrics); put("identity", identity)
                        put("peakMeaning", "observed peak lower bound; no production reserve inference")
                    })
                    previousStart = start
                    request?.done?.complete(Unit)
                } catch (error: Throwable) {
                    request?.done?.completeExceptionally(error)
                    throw error
                }
            }
        } catch (error: Throwable) { failure.set(error); evidence.failure("SAMPLING_FAILURE", error) }
    }, "localPad-measurements")
    fun start() = thread.start()
    fun boundary(label: String, controller: ResearchController, teardown: Boolean = false) {
        failure.get()?.let { throw it }
        check(thread.isAlive && !stopped)
        val remaining = (if (teardown) controller.end else controller.workEnd) - monotonicMs()
        check(remaining > 0) { "BOUNDARY_DEADLINE_$label" }
        val done = CompletableFuture<Unit>()
        check(requests.offer(Boundary(label, done))) { "MEASUREMENT_QUEUE_FULL" }
        done.get(minOf(5000, remaining), TimeUnit.MILLISECONDS)
        failure.get()?.let { throw it }
    }
    fun stopAndJoin(controller: ResearchController) {
        stopped = true
        // Wake poll without interrupting an in-flight metrics read.
        requests.offer(Boundary("STOP", CompletableFuture()))
        thread.join(minOf(5000, controller.end - monotonicMs()).coerceAtLeast(1))
        check(!thread.isAlive) { "SAMPLER_JOIN_UNRESOLVED" }
        failure.get()?.let { throw it }
        evidence.event("SAMPLER_JOINED")
    }
}
