package com.example.hyundai_hackathon.domain

import kotlin.math.abs

/** Receives timestamped speed samples; no Android or vehicle-service dependency. */
class DriveEngine(initial: Drive) {
    var drive: Drive = initial
        private set
    private var previous: VehicleSample? = null
    private var filteredAcceleration = 0.0
    private var candidate: EventKind? = null
    private var candidateMs = 0L
    private var smoothMs = 0L
    private var lastReward = -30_000L
    private var lastStrong = -10_000L
    private var lastGap = -30_000L
    private var lastSteering = -30_000L
    private var rewards = 0
    private var decelerationWindow = ArrayDeque<Pair<Long, Double>>()

    fun accept(sample: VehicleSample): Drive {
        if (!sample.speedMps.isFinite() || sample.speedMps < 0 || sample.speedMps > 75 || sample.elapsedMs < 0) return drive
        val prior = previous
        if (prior != null && sample.elapsedMs <= prior.elapsedMs) return drive
        previous = sample
        drive = drive.copy(durationMs = sample.elapsedMs, sampleCount = drive.sampleCount + 1)
        if (prior == null) { recordSample(sample, null); return drive }
        val dt = sample.elapsedMs - prior.elapsedMs
        if (dt !in 150L..2_000L) {
            resetWindow()
            if (dt > 2_000 && sample.elapsedMs - lastGap >= 30_000) {
                lastGap = sample.elapsedMs
                add(DriveEvent(atMs = sample.elapsedMs, kind = EventKind.SIGNAL_GAP,
                    vehiclePoints = 0, comfortPoints = 0, review = EventReview.REFERENCE,
                    observation = "주행 신호가 ${dt / 1000}초 동안 이어지지 않았어요."))
            }
            return drive
        }
        val seconds = dt / 1000.0
        val raw = (sample.speedMps - prior.speedMps) / seconds
        if (abs(raw) > 12.0) {
            resetWindow()
            return drive
        }
        filteredAcceleration = 0.55 * raw + 0.45 * filteredAcceleration
        recordSample(sample, filteredAcceleration)
        drive = drive.copy(
            distanceMeters = drive.distanceMeters + (sample.speedMps + prior.speedMps) * seconds / 2,
            scoredMs = drive.scoredMs + dt
        )
        val kind = when {
            filteredAcceleration >= 2.5 && sample.speedMps >= 2.0 -> EventKind.ACCELERATION
            filteredAcceleration <= -2.8 && prior.speedMps >= 2.0 -> EventKind.BRAKING
            else -> null
        }
        if (kind != null) {
            smoothMs = 0
            if (candidate == kind) candidateMs += dt else { candidate = kind; candidateMs = dt }
            if (candidateMs >= 600 && sample.elapsedMs - lastStrong >= 8_000) {
                lastStrong = sample.elapsedMs
                val braking = kind == EventKind.BRAKING
                add(DriveEvent(atMs = sample.elapsedMs, kind = kind,
                    vehiclePoints = if (braking) -2 else -1,
                    comfortPoints = -2 * drive.mode.comfortWeight,
                    review = if (braking) EventReview.PENDING else EventReview.APPLIED,
                    observation = "${if (braking) "강한 감속" else "빠른 가속"}이 연속된 속도 신호에서 관찰됐어요.",
                    context = if (braking) "원인은 아직 알 수 없어 감점을 보류했어요. 주차 후 상황을 확인해주세요." else "관찰된 움직임에 대한 시범 평가이며 운전자의 의도를 판단하지 않습니다.",
                    peakAcceleration = filteredAcceleration))
            }
        } else { candidate = null; candidateMs = 0 }
        if (abs(filteredAcceleration) <= 0.7 && sample.speedMps >= 3.0) {
            smoothMs += dt
            if (smoothMs >= 15_000 && sample.elapsedMs - lastReward >= 20_000 && rewards < 10) {
                reward(EventKind.SMOOTH, sample.elapsedMs, "15초 이상 완만한 속도 변화가 이어졌어요.")
                smoothMs = 0
            }
        } else smoothMs = 0

        decelerationWindow.addLast(sample.elapsedMs to raw)
        while (decelerationWindow.isNotEmpty() && decelerationWindow.first().first < sample.elapsedMs - 5_000) decelerationWindow.removeFirst()
        if (prior.speedMps >= 0.25 && sample.speedMps < 0.25 && decelerationWindow.size >= 5 &&
            decelerationWindow.last().first - decelerationWindow.first().first >= 4_000 &&
            decelerationWindow.any { it.second < -0.2 } &&
            decelerationWindow.all { abs(it.second) < 1.6 } &&
            sample.elapsedMs - lastReward >= 20_000 && rewards < 10) {
            reward(EventKind.GENTLE_STOP, sample.elapsedMs, "정차 전 5초 구간에서 완만하게 속도가 줄었어요.")
        }
        val angle = sample.frontWheelAngle
        val priorAngle = prior.frontWheelAngle
        if (angle != null && priorAngle != null && angle.isFinite() && priorAngle.isFinite() &&
            sample.speedMps < 0.1 && prior.speedMps < 0.1 && abs(angle - priorAngle) >= 12 &&
            sample.elapsedMs - lastSteering >= 30_000) {
            lastSteering = sample.elapsedMs
            add(DriveEvent(atMs = sample.elapsedMs, kind = EventKind.STEERING,
                vehiclePoints = 0, comfortPoints = 0, review = EventReview.REFERENCE,
                observation = "정차 중 앞바퀴 각도가 크게 바뀌었어요.",
                context = "차종별 영향 검증 전이므로 점수에는 반영하지 않는 참고 기록입니다."))
        }
        return drive
    }

    fun finish(durationMs: Long, endedAt: Long): Drive {
        drive = drive.copy(durationMs = maxOf(drive.durationMs, durationMs), endedAt = endedAt)
        return drive
    }

    private fun add(event: DriveEvent) {
        drive = drive.copy(events = ScorePolicy.recompute(drive.events + event))
    }

    private fun recordSample(sample: VehicleSample, acceleration: Double?) {
        if (drive.signalTrace.size < 14_401 && (drive.signalTrace.lastOrNull()?.atMs ?: -1_000) <= sample.elapsedMs - 1_000) {
            drive = drive.copy(signalTrace = drive.signalTrace + SignalPoint(sample.elapsedMs, sample.speedMps,
                acceleration, sample.brakePressed, sample.frontWheelAngle))
        }
    }

    private fun reward(kind: EventKind, at: Long, observation: String) {
        lastReward = at
        rewards++
        add(DriveEvent(atMs = at, kind = kind, vehiclePoints = 0, comfortPoints = 1, observation = observation))
    }

    private fun resetWindow() {
        filteredAcceleration = 0.0
        candidate = null
        candidateMs = 0
        smoothMs = 0
        decelerationWindow.clear()
    }
}

object DemoRoute {
    const val DURATION_MS = 72_000L
    private val points = listOf(
        0L to 0.0, 6_000L to 5.0, 8_000L to 14.0,
        27_000L to 14.0, 29_000L to 3.0, 33_000L to 7.0,
        53_000L to 7.0, 60_000L to 0.0, 64_000L to 0.0,
        66_000L to 10.0, 72_000L to 10.0
    )
    fun sample(at: Long): VehicleSample {
        val end = points.indexOfFirst { it.first >= at }.coerceAtLeast(1)
        val a = points[end - 1]
        val b = points[end]
        val progress = ((at - a.first).toDouble() / (b.first - a.first)).coerceIn(0.0, 1.0)
        return VehicleSample(at, a.second + (b.second - a.second) * progress)
    }
}
