package com.example.hyundai_hackathon.domain

import java.util.UUID

enum class PassengerMode(val title: String, val description: String, val comfortWeight: Int) {
    NORMAL("일반", "나와 차를 위한 부드러운 주행", 1),
    CHILD("어린이 동승", "작은 흔들림까지 더 세심하게", 2),
    SENIOR("어르신 동승", "출발부터 정차까지 편안하게", 2)
}

enum class DriveSource(val title: String) { VEHICLE("차량 주행"), DEMO("체험 주행") }
enum class EventKind(val title: String, val shortName: String) {
    ACCELERATION("빠른 가속", "가속"),
    BRAKING("강한 감속", "감속"),
    SMOOTH("부드러운 주행", "부드러움"),
    GENTLE_STOP("완만한 정차", "정차"),
    STEERING("정차 중 큰 조향", "조향"),
    SIGNAL_GAP("평가할 수 없는 구간", "신호 확인")
}
enum class EventReview { APPLIED, PENDING, EXCLUDED, REFERENCE }

data class VehicleSample(
    val elapsedMs: Long,
    val speedMps: Double,
    val brakePressed: Boolean? = null,
    val frontWheelAngle: Double? = null
)

data class DriveEvent(
    val id: String = UUID.randomUUID().toString(),
    val atMs: Long,
    val kind: EventKind,
    val vehiclePoints: Int,
    val comfortPoints: Int,
    val review: EventReview = EventReview.APPLIED,
    val before: Int = 100,
    val after: Int = 100,
    val observation: String,
    val context: String = "",
    val peakAcceleration: Double? = null
) {
    val delta get() = after - before
    val requestedDelta get() = vehiclePoints + comfortPoints
}

data class ScorePoint(val atMs: Long, val score: Int)
data class SignalPoint(val atMs: Long, val speedMps: Double, val estimatedAcceleration: Double?, val brakePressed: Boolean?, val frontWheelAngle: Double?)

data class Drive(
    val id: String = UUID.randomUUID().toString(),
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long? = null,
    val mode: PassengerMode,
    val source: DriveSource,
    val durationMs: Long = 0,
    val distanceMeters: Double = 0.0,
    val events: List<DriveEvent> = emptyList(),
    val sampleCount: Int = 0,
    val scoredMs: Long = 0,
    val signalTrace: List<SignalPoint> = emptyList(),
    val interrupted: Boolean = false,
    val comfortFeedback: Int? = null,
    val feedbackAuthor: String? = null,
    val ruleVersion: String = "0.1-pilot",
    val driverType: String = "미확인"
) {
    val score get() = events.lastOrNull()?.after ?: 100
    val gains get() = events.sumOf { it.delta.coerceAtLeast(0) }
    val losses get() = events.sumOf { (-it.delta).coerceAtLeast(0) }
    val pending get() = events.count { it.review == EventReview.PENDING }
    val scorePoints get() = listOf(ScorePoint(0, 100)) + events.map { ScorePoint(it.atMs, it.after) }
    val coverage get() = if (durationMs > 0) (scoredMs.toDouble() / durationMs).coerceIn(0.0, 1.0) else 0.0
}

object ScorePolicy {
    fun recompute(events: List<DriveEvent>): List<DriveEvent> {
        var score = 100
        return events.map { event ->
            val before = score
            if (event.review == EventReview.APPLIED) {
                score = (score + event.requestedDelta).coerceIn(0, 100)
            }
            event.copy(before = before, after = score)
        }
    }

    fun review(drive: Drive, eventId: String, decision: EventReview): Drive = drive.copy(
        events = recompute(drive.events.map { event ->
            if (event.id == eventId && event.kind == EventKind.BRAKING) event.copy(review = decision)
            else event
        })
    )
}

data class EventAdvice(val vehicle: String, val passenger: String, val next: String)

fun EventKind.advice(): EventAdvice = when (this) {
    EventKind.ACCELERATION -> EventAdvice(
        "반복적인 큰 속도 변화는 에너지 사용과 관련됩니다. 이 기록으로 실제 부품 마모나 연료 손실량을 계산하지는 않습니다.",
        "갑작스러운 가속은 몸이 뒤로 쏠리는 느낌을 줄 수 있어요.",
        "교통 상황에 맞는 범위에서 가속 페달을 조금 더 점진적으로 조작해보세요."
    )
    EventKind.BRAKING -> EventAdvice(
        "반복적인 강한 마찰 제동은 열과 마모에 영향을 줄 수 있습니다. 속도 감소만으로 마찰·회생제동의 비율이나 패드 마모량을 알 수는 없습니다.",
        "큰 감속은 몸이 앞으로 쏠리는 느낌을 줄 수 있어요. 위험을 피하기 위한 제동은 반드시 우선해야 합니다.",
        "안전하게 주차한 뒤 상황을 돌아보세요. 필요한 제동을 피하거나 늦추지 마세요."
    )
    EventKind.SMOOTH -> EventAdvice(
        "속도 변화가 완만하게 이어진 구간입니다. 실제 에너지 절감량을 측정한 결과는 아닙니다.",
        "앞뒤 흔들림이 적은 주행은 편안한 이동을 도울 수 있어요.",
        "주변 흐름과 충분한 여유를 살피며 지금의 부드러운 조작을 이어가세요."
    )
    EventKind.GENTLE_STOP -> EventAdvice(
        "속도가 완만하게 줄어든 정차를 기록했어요. 실제 브레이크 상태를 진단하는 정보는 아닙니다.",
        "정차 직전의 갑작스러운 흔들림을 줄이는 데 도움이 될 수 있어요.",
        "멈출 지점을 미리 살피고, 상황에 맞게 여유를 두고 감속하세요."
    )
    EventKind.STEERING -> EventAdvice(
        "정차 중 앞바퀴 각도가 크게 바뀌는 패턴을 참고 기록합니다. 차종·노면에 따른 근거를 검증하기 전에는 마모나 잘못된 행동으로 판정하지 않습니다.",
        "이 항목은 동승자의 편안함 점수에 반영하지 않아요.",
        "주차 공간과 주변 안전을 우선하세요. 해당 패턴은 차량 취급설명서와 함께 참고하세요."
    )
    EventKind.SIGNAL_GAP -> EventAdvice(
        "신호가 누락되거나 시간 간격이 충분하지 않아 해당 구간을 평가에서 제외했습니다.",
        "데이터 부족으로 부드러움이나 불편함을 판단하지 않았어요.",
        "차량 연결 상태를 확인하세요. 빈 구간은 점수에 불이익을 주지 않습니다."
    )
}
