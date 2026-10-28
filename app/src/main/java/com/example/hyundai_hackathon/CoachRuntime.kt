package com.example.hyundai_hackathon

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import com.example.hyundai_hackathon.data.DriveStore
import com.example.hyundai_hackathon.domain.*
import com.example.hyundai_hackathon.vehicle.CarBridge
import com.example.hyundai_hackathon.vehicle.VehicleState
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CoachState(
    val loading: Boolean = true,
    val vehicle: VehicleState = VehicleState(),
    val mode: PassengerMode = PassengerMode.NORMAL,
    val active: Drive? = null,
    val history: List<Drive> = emptyList(),
    val lastEvent: DriveEvent? = null,
    val lastEventAt: Long = 0,
    val demoSpeed: Double = 0.0,
    val now: Long = SystemClock.elapsedRealtime(),
    val notice: String? = null,
    val storageError: Boolean = false
) {
    val canInteract get() = vehicle.interactionAllowed
    val speedKmh get() = if (active?.source == DriveSource.DEMO) demoSpeed * 3.6 else if (vehicle.fresh) vehicle.speedMps?.times(3.6) else null
}

class CoachRuntime private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = DriveStore(context)
    private val automotive = context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
    private val mutableState = MutableStateFlow(CoachState(vehicle = VehicleState(automotive = automotive,
        message = if (automotive) "차량 연결 준비 중" else "차량 연결은 차량 환경에서 사용할 수 있어요")))
    val state = mutableState.asStateFlow()
    private val writes = Channel<() -> Unit>(Channel.UNLIMITED)
    private var bridge: CarBridge? = null
    private var uiAttached = false
    private var engine: DriveEngine? = null
    private var startedElapsed = 0L
    private var lastCheckpoint = 0L
    private var lastSampleTime = -1L
    private var demoJob: Job? = null
    private var demoElapsed = 0L

    init {
        scope.launch(Dispatchers.IO) {
            for (write in writes) {
                try { write() } catch (_: Exception) {
                    withContext(Dispatchers.Main) { change { it.copy(storageError = true, notice = "기록 저장에 실패했어요. 저장 공간을 확인해주세요.") } }
                }
            }
        }
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { runCatching { store.load() } }
            val history = loaded.getOrDefault(emptyList())
            change { it.copy(loading = false, history = history,
                storageError = loaded.isFailure,
                notice = if (loaded.isFailure) "기록을 불러오지 못했어요. 저장 공간을 확인해주세요." else if (store.readWarnings > 0) "일부 기록을 읽지 못했어요. 원본은 보관했어요." else null) }
        }
        scope.launch {
            while (isActive) {
                delay(500)
                val now = SystemClock.elapsedRealtime()
                change { it.copy(now = now) }
                val active = mutableState.value.active ?: continue
                val duration = if (active.source == DriveSource.DEMO) demoElapsed else now - startedElapsed
                change { it.copy(active = it.active?.copy(durationMs = duration)) }
                if (now - lastCheckpoint >= 5_000) checkpoint()
                if (duration >= 4 * 60 * 60 * 1000L) finish(force = true, interrupted = true)
            }
        }
    }

    private fun change(block: (CoachState) -> CoachState) { mutableState.value = block(mutableState.value) }

    fun attachUi() { uiAttached = true; connect() }
    fun detachUi() { uiAttached = false; disconnectIfIdle() }
    fun reconnect() {
        bridge?.close(); bridge = null
        change { it.copy(vehicle = VehicleState(automotive = automotive, message = "차량 정보를 다시 연결하고 있어요")) }
        connect()
    }

    private fun connect() {
        if (!automotive || bridge != null) return
        try {
            bridge = CarBridge(context) { vehicle ->
                change { it.copy(vehicle = vehicle) }
                val active = mutableState.value.active
                if (active?.source == DriveSource.DEMO && !vehicle.interactionAllowed) {
                    finish(force = true, interrupted = true)
                    change { it.copy(notice = "주행 상태가 바뀌어 체험을 종료했어요.") }
                }
                if (active?.source == DriveSource.VEHICLE && vehicle.fresh && vehicle.speedMps != null && vehicle.speedTimestampMs != lastSampleTime) {
                    lastSampleTime = vehicle.speedTimestampMs
                    accept(VehicleSample(vehicle.speedTimestampMs - startedElapsed, vehicle.speedMps, vehicle.brake, vehicle.wheelAngle))
                }
            }.also { it.start() }
        } catch (_: LinkageError) { change { it.copy(vehicle = it.vehicle.copy(message = "이 환경에서는 차량 연결을 사용할 수 없어요")) } }
    }

    private fun disconnectIfIdle() {
        if (!uiAttached && mutableState.value.active == null) { bridge?.close(); bridge = null }
    }

    fun chooseMode(mode: PassengerMode) {
        if (mutableState.value.canInteract && mutableState.value.active == null) change { it.copy(mode = mode) }
    }

    fun canStart(source: DriveSource): Boolean {
        val s = mutableState.value
        return !s.loading && s.active == null && s.canInteract &&
            // A parked vehicle may repeat a cached zero with no new measurement timestamp.
            // Starting is allowed in explicit P; only fresh samples are scored after departure.
            (source == DriveSource.DEMO || (s.vehicle.connected && s.vehicle.speedMps != null))
    }

    fun start(source: DriveSource) {
        if (!canStart(source)) return
        connect()
        startedElapsed = SystemClock.elapsedRealtime()
        lastSampleTime = -1
        demoElapsed = 0
        val drive = Drive(mode = mutableState.value.mode, source = source,
            driverType = if (source == DriveSource.DEMO) "체험 시나리오" else "미확인")
        engine = DriveEngine(drive)
        change { it.copy(active = drive, lastEvent = null, demoSpeed = 0.0, notice = null) }
        checkpoint()
        if (source == DriveSource.DEMO) {
            demoJob = scope.launch {
                while (demoElapsed <= DemoRoute.DURATION_MS) {
                    val sample = DemoRoute.sample(demoElapsed)
                    change { it.copy(demoSpeed = sample.speedMps) }
                    accept(sample)
                    delay(500)
                    demoElapsed += 500
                }
                finish(force = true)
            }
        } else {
            mutableState.value.vehicle.takeIf { it.fresh }?.speedMps?.let { speed -> accept(VehicleSample(0, speed)) }
        }
    }

    private fun accept(sample: VehicleSample) {
        val scorer = engine ?: return
        val count = scorer.drive.events.size
        val drive = scorer.accept(sample)
        val event = if (drive.events.size > count) drive.events.last() else null
        change { it.copy(active = drive, lastEvent = event ?: it.lastEvent,
            lastEventAt = if (event != null) SystemClock.elapsedRealtime() else it.lastEventAt) }
        if (event != null) checkpoint()
    }

    fun finish(force: Boolean = false, interrupted: Boolean = false) {
        if (!force && !mutableState.value.canInteract) return
        val scorer = engine ?: return
        val current = mutableState.value.active ?: return
        val duration = if (current.source == DriveSource.DEMO) demoElapsed.coerceAtMost(DemoRoute.DURATION_MS) else SystemClock.elapsedRealtime() - startedElapsed
        val completed = scorer.finish(duration, System.currentTimeMillis()).copy(interrupted = interrupted)
        engine = null
        demoJob?.cancel(); demoJob = null
        val all = (listOf(completed) + mutableState.value.history).distinctBy { it.id }
        val retained = all.filter { it.startedAt >= System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000 }.take(100)
        change { it.copy(active = null, history = retained, demoSpeed = 0.0, lastEvent = null,
            notice = if (interrupted) "주행 기록이 중단된 지점까지 저장됐어요." else "드라이브 기록을 저장했어요.") }
        writes.trySend {
            store.complete(completed)
            all.filter { old -> retained.none { it.id == old.id } }.forEach { store.delete(it.id) }
        }
        context.stopService(Intent(context, DriveRecordingService::class.java))
        disconnectIfIdle()
    }

    fun interruptIfRecording() { if (mutableState.value.active != null) finish(force = true, interrupted = true) }

    private fun checkpoint() {
        lastCheckpoint = SystemClock.elapsedRealtime()
        mutableState.value.active?.let { drive -> writes.trySend { store.checkpoint(drive) } }
    }

    fun review(driveId: String, eventId: String, decision: EventReview) {
        if (!mutableState.value.canInteract) return
        val drive = mutableState.value.history.find { it.id == driveId } ?: return
        val revised = ScorePolicy.review(drive, eventId, decision)
        change { it.copy(history = it.history.map { old -> if (old.id == driveId) revised else old }) }
        writes.trySend { store.save(revised) }
    }

    fun feedback(id: String, value: Int, author: String) {
        if (!mutableState.value.canInteract || value !in 1..3 || author !in listOf("동승자 본인", "운전자 관찰")) return
        val old = mutableState.value.history.find { it.id == id } ?: return
        val revised = old.copy(comfortFeedback = value, feedbackAuthor = author)
        change { it.copy(history = it.history.map { d -> if (d.id == id) revised else d }) }
        writes.trySend { store.save(revised) }
    }

    fun deleteDrive(id: String) {
        if (!mutableState.value.canInteract) return
        change { it.copy(history = it.history.filterNot { drive -> drive.id == id }) }
        writes.trySend { store.delete(id) }
    }

    fun clearHistory() {
        if (!mutableState.value.canInteract || mutableState.value.active != null) return
        change { it.copy(history = emptyList()) }
        writes.trySend { store.clear() }
    }

    fun showNotice(message: String) { change { it.copy(notice = message) } }
    fun dismissNotice() { change { it.copy(notice = null) } }

    companion object {
        @Volatile private var instance: CoachRuntime? = null
        fun get(context: Context): CoachRuntime = instance ?: synchronized(this) {
            instance ?: CoachRuntime(context.applicationContext).also { instance = it }
        }
    }
}
