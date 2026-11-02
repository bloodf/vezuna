package app.vezuna.ios

import androidx.compose.ui.window.ComposeUIViewController
import app.vezuna.data.restoreWithVitals
import app.vezuna.domain.DoseEvent
import app.vezuna.domain.DoseEvents
import app.vezuna.domain.DoseEventStatus
import app.vezuna.domain.Medication
import app.vezuna.domain.ReferenceRange
import app.vezuna.domain.HeartRateBucket
import app.vezuna.domain.StepsDay
import app.vezuna.domain.VitalsBatch
import app.vezuna.domain.VitalsWindow
import app.vezuna.domain.WeightReading
import app.vezuna.export.CsvDoctorExporter
import app.vezuna.export.DoctorExportContent
import app.vezuna.export.DoctorPdfDocument
import app.vezuna.export.ExportedPdf
import app.vezuna.export.ExportOptions
import app.vezuna.export.ExportText
import app.vezuna.export.NativePdfExporter
import app.vezuna.llm.InstalledModelManager
import app.vezuna.llm.LocalHealthChat
import app.vezuna.localization.ContentLanguage
import app.vezuna.llm.LocalLlm
import app.vezuna.llm.ModelCatalog
import app.vezuna.llm.loadBundledModelCatalog
import app.vezuna.llm.ModelPack
import app.vezuna.llm.ModelPackSource
import app.vezuna.llm.llamatikLocalLlm
import app.vezuna.platform.currentInstant
import app.vezuna.security.BackupService
import app.vezuna.platform.randomId
import app.vezuna.platform.sha256
import app.vezuna.platform.toHex
import app.vezuna.security.AppLock
import app.vezuna.security.AppLockStore
import app.vezuna.security.DeviceAuthenticator
import app.vezuna.security.CipherEnvelope
import app.vezuna.security.SecureVault
import app.vezuna.security.VaultFailure
import app.vezuna.security.VaultState
import app.vezuna.security.VaultUnreadableException
import app.vezuna.security.createPlatformVault
import app.vezuna.ui.AppController
import app.vezuna.ui.AppLockHost
import app.vezuna.ui.BackupFileAccess
import app.vezuna.ui.BackupRecovery
import app.vezuna.ui.ExtractedCandidate
import app.vezuna.ui.ExtractedValueKind
import app.vezuna.ui.PickedSource
import app.vezuna.ui.ReminderPlatformState
import app.vezuna.ui.VaultGate
import app.vezuna.ui.VezunaApp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSUserDefaults
import platform.Foundation.dataWithContentsOfFile
import platform.UIKit.UIViewController
import kotlin.coroutines.resume

interface NativeSourcePicker {
    fun pickSource(completion: (PickedSource?) -> Unit)
}

data class NativeMessage(val key: String, val args: List<String> = emptyList())

fun nativeMessage(key: String, vararg args: Any?): NativeMessage =
    NativeMessage(key, args.map { it?.toString().orEmpty() })

interface NativeBackupFiles {
    /** Completion receives the chosen file's bytes, or null when cancelled or unreadable. */
    fun pickBackup(completion: (ByteArray?) -> Unit)

    /** Completion receives true when the file was saved or shared, false when cancelled. */
    fun saveBackup(fileName: String, bytes: ByteArray, completion: (Boolean) -> Unit)
}

interface NativeModelPackLoader {
    /** Completion receives the absolute path of the pack file; the store streams it, so no full copy is made. */
    fun loadModelPack(pack: ModelPack, completion: (String?, NativeMessage?) -> Unit)

    /** Releases the resources behind the last delivered path once installation finished or failed. */
    fun releaseModelPack()
}

interface NativeLicenseProvider {
    fun isPremiumActive(): Boolean
}

/** Face ID / Touch ID with passcode fallback (LAPolicy.deviceOwnerAuthentication); completion runs on any thread. */
interface NativeDeviceAuthenticator {
    fun canAuthenticate(): Boolean
    fun authenticate(completion: (Boolean) -> Unit)
}

/** Read directly by the Swift privacy cover, so the key is part of the bridge contract. */
const val APP_LOCK_ENABLED_KEY = "app_lock_enabled"

private object IosAppLockStore : AppLockStore {
    override fun isEnabled(): Boolean = NSUserDefaults.standardUserDefaults.boolForKey(APP_LOCK_ENABLED_KEY)
    override fun setEnabled(enabled: Boolean) = NSUserDefaults.standardUserDefaults.setBool(enabled, APP_LOCK_ENABLED_KEY)
}

interface NativeRepositoryObserver {
    fun repositoryDidChange()
}

/** One HealthKit sync as encoded by Swift. Values are validated here, not in Swift. */
@Serializable
private data class NativeHealthBatch(
    val replaceFromEpochSeconds: Long,
    val steps: List<NativeSteps> = emptyList(),
    val heartRate: List<NativeHeartRate> = emptyList(),
    val weights: List<NativeWeight> = emptyList(),
)

@Serializable
private data class NativeSteps(val dayStartEpochSeconds: Long, val count: Double)

@Serializable
private data class NativeHeartRate(val startEpochSeconds: Long, val min: Double, val avg: Double, val max: Double)

@Serializable
private data class NativeWeight(val recordId: String, val epochSeconds: Long, val kg: Double)

data class NativeCandidateInput(
    val testCode: String,
    val displayName: String,
    val rawValue: String,
    val rawUnit: String,
    val referenceLower: String,
    val referenceUpper: String,
    val confidence: Float?,
    val collectedEpochSeconds: Long,
    val rawToken: String? = null,
    val referenceText: String? = null,
    val referenceUnit: String? = null,
    val qualitative: Boolean = false,
)

private fun NativeCandidateInput.toExtracted(): ExtractedCandidate {
    val unit = rawUnit.trim()
    return ExtractedCandidate(
        testCode = testCode,
        displayName = displayName,
        rawValue = rawValue,
        rawUnit = unit,
        referenceRange = ReferenceRange(
            app.vezuna.localization.parseDecimalInput(referenceLower),
            app.vezuna.localization.parseDecimalInput(referenceUpper),
            text = referenceText?.trim()?.takeIf { it.isNotEmpty() },
            unit = referenceUnit?.takeIf { it.isNotBlank() } ?: unit.takeIf { it.isNotEmpty() },
        ),
        collectedAt = Instant.fromEpochSeconds(collectedEpochSeconds),
        confidence = confidence?.coerceIn(0f, 1f),
        valueKind = if (qualitative) ExtractedValueKind.QUALITATIVE else ExtractedValueKind.NUMERIC,
        rawToken = rawToken,
    )
}

object IosRuntime {
    private val json = Json { encodeDefaults = true }
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var vault: SecureVault
    private lateinit var repository: PersistentVezunaRepository
    internal lateinit var controller: AppController
    private lateinit var llm: LocalLlm
    private var initialized = false

    var sourcePicker: NativeSourcePicker? = null
    var backupFiles: NativeBackupFiles? = null
    var modelPackLoader: NativeModelPackLoader? = null
    var licenseProvider: NativeLicenseProvider? = null
    var repositoryObserver: NativeRepositoryObserver? = null

    /** Must be installed before the first Compose view controller is made. */
    var deviceAuthenticator: NativeDeviceAuthenticator? = null

    /** Lets native chrome (toolbar, sheets) hide while the Compose lock screen is showing. */
    fun observeLock(callback: (Boolean) -> Unit) {
        scope.launch { appLock.state.map { it.locked }.distinctUntilChanged().collect { callback(it) } }
    }

    /** Monotonic clock that keeps counting while the device sleeps, so changing the date cannot defeat the timeout. */
    val appLock: AppLock by lazy {
        AppLock(
            IosAppLockStore,
            object : DeviceAuthenticator {
                override fun canAuthenticate() = deviceAuthenticator?.canAuthenticate()
                override suspend fun authenticate(): Boolean {
                    val native = deviceAuthenticator ?: return false
                    return suspendCancellableCoroutine { continuation ->
                        native.authenticate { ok -> if (continuation.isActive) continuation.resume(ok) }
                    }
                }
            },
        ) { monotonicMillis() }
    }
    var catalogError: NativeMessage? = null
        private set

    internal val vaultState = MutableStateFlow<VaultState>(VaultState.Loading)

    private const val HIDE_NAMES_KEY = "vezuna.hideMedicationNames"

    /** Lock-screen preference lives in UserDefaults (not the vault) so the notification coordinator can read it before the vault opens. */
    internal val reminderState = ReminderPlatformState(
        hideMedicationNames = NSUserDefaults.standardUserDefaults.boolForKey(HIDE_NAMES_KEY),
        persistHideMedicationNames = { NSUserDefaults.standardUserDefaults.setBool(it, HIDE_NAMES_KEY) },
    )

    /** Called by the notification coordinator after planning; a negative value clears the "scheduled until" line. */
    fun setReminderCoverage(untilEpochSeconds: Long) {
        reminderState.setCoverageUntil(if (untilEpochSeconds < 0) null else kotlinx.datetime.Instant.fromEpochSeconds(untilEpochSeconds))
    }

    /** Backup chosen on the recovery screen; stored into the fresh vault by the reset that follows. */
    internal var pendingRestore: app.vezuna.domain.ProfileData? = null

    fun isVaultRecoveryNeeded(): Boolean = vaultState.value is VaultState.Recovery

    fun configure(vaultPath: String, modelPath: String, completion: (NativeMessage?) -> Unit) {
        if (initialized) {
            completion(null)
            return
        }
        open(vaultPath, modelPath, reset = false, completion)
    }

    /** Recovery screen actions. [reset] quarantines the unreadable vault (never deletes it) before reopening. */
    fun recoverVault(vaultPath: String, modelPath: String, reset: Boolean, completion: (NativeMessage?) -> Unit) {
        if (initialized) {
            completion(null)
            return
        }
        open(vaultPath, modelPath, reset, completion)
    }

    private var opening = false
    private var stateObserverJob: Job? = null

    private fun open(vaultPath: String, modelPath: String, reset: Boolean, completion: (NativeMessage?) -> Unit) {
        // Double taps and overlapping configure/recover calls: the first call's completion reports the outcome.
        if (opening) return
        opening = true
        scope.launch {
            try {
                openNow(vaultPath, modelPath, reset, completion)
            } finally {
                opening = false
            }
        }
    }

    private suspend fun openNow(vaultPath: String, modelPath: String, reset: Boolean, completion: (NativeMessage?) -> Unit) {
        vaultState.value = VaultState.Loading
        // Taken first so a failed restore can never replay on a later reset.
        val restore = pendingRestore
        pendingRestore = null
        var restoreStarted = false
        try {
            vault = createPlatformVault(vaultPath)
            if (reset) {
                check(vault.quarantineAll()) { "Vault cannot preserve unreadable files" }
                vault.discardUnusableKey()
            }
            repository = PersistentVezunaRepository.open(vault, json)
            if (reset && restore != null) {
                restoreStarted = true
                repository.restoreWithVitals(restore)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: VaultUnreadableException) {
            vaultState.value = VaultState.Recovery(error.failure, restoreStarted)
            completion(nativeMessage("storage_open_failed"))
            return
        } catch (error: Exception) {
            vaultState.value = VaultState.Recovery(VaultFailure.UNAVAILABLE, restoreStarted)
            completion(nativeMessage("storage_open_failed"))
            return
        }
        vaultState.value = VaultState.Ready
        try {
            repository.changeObserver = { repositoryObserver?.repositoryDidChange() }
            llm = llamatikLocalLlm()
            val catalog = loadCatalog()
            val files = IosModelFileStore(modelPath, premiumActive = { licenseProvider?.isPremiumActive() == true })
            val manager = InstalledModelManager(catalog, files, llm, IosDeviceCapacity())
            controller = AppController(repository, manager, LocalHealthChat(repository, llm), backup = BackupService(vault), sourceStore = VaultSourceFileStore(vault, json))
            initialized = true
            controller.initialize()
            // Edits and deletes made in Compose reload the controller after the repository commit; notify native
            // reminder reconciliation once the controller's view of the active profile is consistent again.
            stateObserverJob?.cancel()
            stateObserverJob = scope.launch {
                controller.state.map { it.data }.distinctUntilChanged().drop(1).collect { repositoryObserver?.repositoryDidChange() }
            }
            completion(null)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            completion(nativeMessage("storage_open_failed"))
        }
    }

    fun lockPremiumFeatures() {
        if (!initialized) return
        scope.launch { controller.modelManager.unload() }
    }

    fun currentProfileId(): String? = if (initialized) controller.state.value.data?.profile?.profileId else null

    fun refresh() {
        if (!initialized) return
        scope.launch {
            controller.initialize()
            repositoryObserver?.repositoryDidChange()
        }
    }

    // --- Device vitals (HealthKit) ---
    /**
     * The first local day Swift must re-read and the first day of the weight history, as epoch seconds of local midnight.
     * Every sync replaces its window, so there is no anchor to keep in step with the stored data.
     */
    fun vitalsSyncWindow(completion: (Long, Long, NativeMessage?) -> Unit) {
        if (!initialized) {
            completion(0L, 0L, nativeMessage("bridge_storage_not_ready"))
            return
        }
        scope.launch {
            try {
                val id = repository.linkedHealthProfileId() ?: throw IllegalStateException("No profile to link")
                val lastSync = repository.peekProfile(id).vitals.lastSync
                val now = currentInstant()
                val zone = TimeZone.currentSystemDefault()
                completion(
                    VitalsWindow.replaceFrom(lastSync, now, zone).atStartOfDayIn(zone).epochSeconds,
                    VitalsWindow.weightsFrom(lastSync, now, zone).atStartOfDayIn(zone).epochSeconds,
                    null,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                completion(0L, 0L, nativeMessage("action_failed"))
            }
        }
    }

    /**
     * Stores one HealthKit sync in the linked profile's encrypted vitals. The completion fires only after the data
     * and the "synced up to" time are durable together, so a failed save is simply retried over the same window.
     */
    fun ingestHealthBatch(batchJson: String, completion: (NativeMessage?) -> Unit) {
        if (!initialized) {
            completion(nativeMessage("bridge_storage_not_ready"))
            return
        }
        scope.launch {
            try {
                val zone = TimeZone.currentSystemDefault()
                val native = json.decodeFromString(NativeHealthBatch.serializer(), batchJson)
                val steps = native.steps.map { StepsDay.ofOrNull(Instant.fromEpochSeconds(it.dayStartEpochSeconds).toLocalDateTime(zone).date, it.count.toLong()) }
                val heart = native.heartRate.map { HeartRateBucket.ofOrNull(Instant.fromEpochSeconds(it.startEpochSeconds), it.min, it.avg, it.max) }
                val weights = native.weights.map { WeightReading.ofOrNull(it.recordId, Instant.fromEpochSeconds(it.epochSeconds), it.kg) }
                val batch = VitalsBatch(
                    replaceFrom = Instant.fromEpochSeconds(native.replaceFromEpochSeconds).toLocalDateTime(zone).date,
                    steps = steps.filterNotNull(),
                    heartRate = heart.filterNotNull(),
                    weights = weights.filterNotNull(),
                    skipped = (steps + heart + weights).count { it == null },
                )
                repository.ingestDeviceVitals(batch, currentInstant())
                controller.refreshDashboard()
                completion(null)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                completion(nativeMessage("action_failed"))
            }
        }
    }

    /**
     * [languageTag] is the per-export override ("en", "pt", "es"); empty follows the device language.
     * [fromDate] and [toDate] are optional ISO dates (YYYY-MM-DD); blank means unbounded and malformed text fails the export.
     */
    fun exportCsv(
        languageTag: String, fromDate: String, toDate: String, testCodes: List<String>, activeMedicationsOnly: Boolean, includeMedicationsCsv: Boolean,
        completion: (String?, NativeMessage?) -> Unit,
    ) = withProfile(completion) { data ->
        val options = exportOptions(fromDate, toDate, testCodes, activeMedicationsOnly, includeMedicationsCsv)
        CsvDoctorExporter().export(data, exportLanguage(languageTag), options)
    }

    fun exportPdf(
        languageTag: String, fromDate: String, toDate: String, testCodes: List<String>, activeMedicationsOnly: Boolean, includeMedicationsCsv: Boolean,
        completion: (ExportedPdf?, NativeMessage?) -> Unit,
    ) = withProfile(completion) { data ->
        val language = exportLanguage(languageTag)
        val options = exportOptions(fromDate, toDate, testCodes, activeMedicationsOnly, includeMedicationsCsv)
        NativePdfExporter().export(DoctorExportContent.document(data, ExportText.of(language).title, language, options))
    }

    @kotlinx.serialization.Serializable
    private data class ExportTest(val code: String, val name: String)

    /** JSON array of `{code, name}` for the confirmed tests of the active profile, for the export test picker. */
    fun listExportTests(completion: (String?, NativeMessage?) -> Unit) = withProfile(completion) { data ->
        val tests = data.observations.filter { it.confirmation == app.vezuna.domain.ConfirmationStatus.CONFIRMED }
            .groupBy { app.vezuna.domain.UnitNormalizer.canonicalTestCode(it.testCode) }
            .map { (code, rows) -> ExportTest(code, rows.first().displayName) }
            .sortedBy { it.code }
        json.encodeToString(ListSerializer(ExportTest.serializer()), tests)
    }

    private fun exportOptions(fromDate: String, toDate: String, testCodes: List<String>, activeMedicationsOnly: Boolean, includeMedicationsCsv: Boolean): ExportOptions {
        fun date(text: String) = if (text.isBlank()) null else requireNotNull(ExportOptions.parseDate(text)) { "Invalid export date" }
        return ExportOptions(date(fromDate), date(toDate), testCodes.toSet(), activeMedicationsOnly, includeMedicationsCsv)
    }

    private fun exportLanguage(languageTag: String): ContentLanguage =
        if (languageTag.isEmpty()) ContentLanguage.device() else ContentLanguage.fromTag(languageTag)

    fun listMedications(completion: (String?, NativeMessage?) -> Unit) = withProfile(completion) { data ->
        json.encodeToString(ListSerializer(Medication.serializer()), data.medications.filter { it.active })
    }

    /** Active medications of every profile, used to clear delivered reminders and skip MISSED for stopped or deleted ones. */
    fun listAllActiveMedications(completion: (String?, NativeMessage?) -> Unit) {
        if (!initialized) {
            completion(null, nativeMessage("bridge_storage_not_ready"))
            return
        }
        scope.launch {
            try {
                val active = repository.profiles.value.flatMap { repository.peekProfile(it.profileId).medications.filter { m -> m.active } }
                completion(json.encodeToString(ListSerializer(Medication.serializer()), active), null)
            } catch (error: Throwable) {
                completion(null, nativeMessage("action_failed"))
            }
        }
    }

    fun recordDoseEvent(
        profileId: String,
        medicationId: String,
        scheduleId: String,
        scheduledEpochSeconds: Long,
        status: String,
        completion: (NativeMessage?) -> Unit,
    ) {
        if (!initialized) {
            completion(nativeMessage("bridge_storage_not_ready"))
            return
        }
        scope.launch {
            try {
                val scheduled = Instant.fromEpochSeconds(scheduledEpochSeconds)
                val doseStatus = DoseEventStatus.valueOf(status)
                val data = repository.peekProfile(profileId)
                require(data.profile.profileId == profileId)
                if (data.medications.none { medication ->
                    medication.medicationId == medicationId && medication.schedules.any { it.scheduleId == scheduleId }
                }) {
                    completion(nativeMessage("medication_schedule_missing"))
                    return@launch
                }
                // The repository enforces one terminal status per dose; a repeat or lower-priority write is a no-op.
                repository.addDoseEvent(
                    DoseEvent(profileId, DoseEvents.idFor(scheduleId, scheduled), medicationId, scheduleId, scheduled, doseStatus, currentInstant()),
                )
                repositoryObserver?.repositoryDidChange()
                completion(null)
            } catch (error: Throwable) {
                completion(nativeMessage("dose_record_failed"))
            }
        }
    }

    fun importNativeCandidates(
        profileId: String,
        fileName: String,
        mediaType: String,
        sourceBytes: ByteArray,
        candidates: List<NativeCandidateInput>,
        additionalPages: List<ByteArray>,
        laboratoryName: String?,
        orderedEpochSeconds: Long?,
        completion: (NativeMessage?) -> Unit,
    ) {
        if (!initialized) {
            completion(nativeMessage("bridge_storage_not_ready"))
            return
        }
        scope.launch {
            try {
                if (profileId != currentProfileId()) {
                    completion(nativeMessage("active_profile_changed"))
                    return@launch
                }
                // The same AppController path as Android: SHA-256 dedupe, identical validation, one report per source.
                controller.importSource(
                    PickedSource(
                        fileName, mediaType, sourceBytes, candidates.map { it.toExtracted() },
                        laboratoryName, orderedEpochSeconds?.let { Instant.fromEpochSeconds(it) }, additionalPages,
                    ),
                )
                if (controller.state.value.error != null) {
                    completion(nativeMessage("import_save_failed"))
                    return@launch
                }
                repositoryObserver?.repositoryDidChange()
                completion(null)
            } catch (error: Throwable) {
                completion(nativeMessage("import_save_failed"))
            }
        }
    }

    private fun <T> withProfile(completion: (T?, NativeMessage?) -> Unit, block: suspend (app.vezuna.domain.ProfileData) -> T) {
        if (!initialized) {
            completion(null, nativeMessage("bridge_storage_not_ready"))
            return
        }
        scope.launch {
            val profileId = currentProfileId()
            if (profileId == null) {
                completion(null, nativeMessage("select_profile_first"))
                return@launch
            }
            try {
                completion(block(repository.peekProfile(profileId)), null)
            } catch (error: Throwable) {
                completion(null, nativeMessage("action_failed"))
            }
        }
    }

    /** Same bundled catalog file as Android (shared compose resources). */
    private suspend fun loadCatalog(): ModelCatalog {
        catalogError = null
        return try {
            loadBundledModelCatalog()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            catalogError = nativeMessage("model_catalog_invalid")
            ModelCatalog.unconfigured()
        }
    }
}

fun makeComposeViewController(): UIViewController = ComposeUIViewController {
    val picker = IosRuntime.sourcePicker
    val loader = IosRuntime.modelPackLoader
    val backupPicker = IosRuntime.backupFiles
    AppLockHost(IosRuntime.appLock) {
    VezunaApp(
        backupFiles = backupPicker?.let(::composeBackupFiles),
        controller = IosRuntime.controllerForCompose(),
        scope = IosRuntime.composeScope(),
        sourcePicker = if (picker == null) null else {
            {
                suspendCancellableCoroutine { continuation ->
                    picker.pickSource { source -> if (continuation.isActive) continuation.resume(source) }
                }
            }
        },
        modelPackLoader = if (loader == null) null else {
            { pack ->
                if (IosRuntime.licenseProvider?.isPremiumActive() != true) {
                    null
                } else {
                    suspendCancellableCoroutine { continuation ->
                        loader.loadModelPack(pack) { path, _ ->
                            if (path == null) {
                                if (continuation.isActive) continuation.resume(null)
                            } else if (continuation.isActive) {
                                continuation.resume(ModelPackSource(path) { loader.releaseModelPack() })
                            } else {
                                loader.releaseModelPack()
                            }
                        }
                    }
                }
            }
        },
        animationsEnabled = !platform.UIKit.UIAccessibilityIsReduceMotionEnabled(),
        reminders = IosRuntime.reminderState,
    )
    }
}

/** CLOCK_MONOTONIC on Darwin keeps counting while the device sleeps and ignores wall-clock changes. */
@OptIn(ExperimentalForeignApi::class)
private fun monotonicMillis(): Long = memScoped {
    val now = alloc<platform.posix.timespec>()
    check(platform.posix.clock_gettime(platform.posix.CLOCK_MONOTONIC.toUInt(), now.ptr) == 0) { "monotonic clock unavailable" }
    now.tv_sec * 1000L + now.tv_nsec / 1_000_000L
}

private fun composeBackupFiles(native: NativeBackupFiles) = BackupFileAccess(
    pick = { suspendCancellableCoroutine { continuation -> native.pickBackup { bytes -> if (continuation.isActive) continuation.resume(bytes) } } },
    save = { fileName, bytes ->
        suspendCancellableCoroutine { continuation -> native.saveBackup(fileName, bytes) { saved -> if (continuation.isActive) continuation.resume(saved) } }
    },
)

/** [onRestore] runs after the chosen backup is parked in [IosRuntime.pendingRestore]; the shell then resets the vault. */
fun makeVaultRecoveryViewController(onRetry: () -> Unit, onReset: () -> Unit, onRestore: () -> Unit): UIViewController = ComposeUIViewController {
    val state by IosRuntime.vaultState.collectAsState()
    val files = IosRuntime.backupFiles?.let(::composeBackupFiles)
    val recovery = files?.let {
        BackupRecovery(BackupService(), it) { data ->
            IosRuntime.pendingRestore = data
            onRestore()
        }
    }
    AppLockHost(IosRuntime.appLock) { VaultGate(state, onRetry, onReset, recovery) {} }
}

// These accessors keep Compose construction in this module while the runtime fields remain encapsulated.
private fun IosRuntime.controllerForCompose(): AppController = IosRuntime.controller
private fun IosRuntime.composeScope(): CoroutineScope = IosRuntime.scope
