package com.example.hyundai_hackathon

import com.example.hyundai_hackathon.domain.*
import org.junit.Assert.*
import org.junit.Test

class DriveEngineTest {
    private fun demo(mode: PassengerMode = PassengerMode.NORMAL): Drive {
        val engine = DriveEngine(Drive(mode = mode, source = DriveSource.DEMO))
        for (time in 0L..DemoRoute.DURATION_MS step 500) engine.accept(DemoRoute.sample(time))
        return engine.drive
    }

    @Test fun replayContainsGainsLossesAndDeferredBraking() {
        val drive = demo()
        assertTrue(drive.events.any { it.delta < 0 })
        assertTrue(drive.events.any { it.delta > 0 })
        assertTrue(drive.events.any { it.kind == EventKind.BRAKING && it.review == EventReview.PENDING && it.delta == 0 })
        assertTrue(drive.score in 0..100)
        assertTrue(drive.distanceMeters > 100)
        assertTrue(drive.coverage > .95)
    }

    @Test fun passengerWeightChangesComfortButNotVehicleComponent() {
        val normal = demo().events.first { it.kind == EventKind.ACCELERATION }
        val child = demo(PassengerMode.CHILD).events.first { it.kind == EventKind.ACCELERATION }
        assertEquals(normal.vehiclePoints, child.vehiclePoints)
        assertEquals(normal.comfortPoints * 2, child.comfortPoints)
        assertTrue(child.delta < normal.delta)
    }

    @Test fun continuousStrongAccelerationIsNotChargedEverySample() {
        val engine = DriveEngine(Drive(mode = PassengerMode.NORMAL, source = DriveSource.VEHICLE))
        for (t in 0L..4_000L step 200) engine.accept(VehicleSample(t, t / 1000.0 * 4))
        assertEquals(1, engine.drive.events.count { it.kind == EventKind.ACCELERATION })
    }

    @Test fun emergencyExclusionRecomputesLaterClippedRecovery() {
        val brake = DriveEvent(atMs = 1, kind = EventKind.BRAKING, vehiclePoints = -2, comfortPoints = -4,
            review = EventReview.APPLIED, observation = "감속")
        val reward = DriveEvent(atMs = 2, kind = EventKind.SMOOTH, vehiclePoints = 0, comfortPoints = 1, observation = "부드러움")
        val drive = Drive(mode = PassengerMode.CHILD, source = DriveSource.VEHICLE, events = ScorePolicy.recompute(listOf(brake, reward)))
        assertEquals(95, drive.score)
        val excluded = ScorePolicy.review(drive, brake.id, EventReview.EXCLUDED)
        assertEquals(100, excluded.score)
        assertEquals(0, excluded.events.last().delta)
        assertEquals(2, excluded.events.size)
    }

    @Test fun rewardsAtTheCeilingCannotBeBankedAgainstLaterLoss() {
        val rewards = (1..20).map { DriveEvent(atMs = it.toLong(), kind = EventKind.SMOOTH, vehiclePoints = 0, comfortPoints = 1, observation = "good") }
        val loss = DriveEvent(atMs = 30, kind = EventKind.ACCELERATION, vehiclePoints = -1, comfortPoints = -2, observation = "accel")
        assertEquals(97, ScorePolicy.recompute(rewards + loss).last().after)
    }

    @Test fun longSignalGapDoesNotBecomeAnAccelerationPenaltyOrDistance() {
        val engine = DriveEngine(Drive(mode = PassengerMode.NORMAL, source = DriveSource.VEHICLE))
        engine.accept(VehicleSample(0, 0.0))
        engine.accept(VehicleSample(15_000, 20.0))
        assertEquals(100, engine.drive.score)
        assertEquals(0.0, engine.drive.distanceMeters, .001)
        assertEquals(EventKind.SIGNAL_GAP, engine.drive.events.single().kind)
    }

    @Test fun invalidAndOutOfOrderSamplesCannotCorruptTheScore() {
        val engine = DriveEngine(Drive(mode = PassengerMode.NORMAL, source = DriveSource.VEHICLE))
        engine.accept(VehicleSample(1000, 1.0))
        engine.accept(VehicleSample(500, 50.0))
        engine.accept(VehicleSample(1500, Double.NaN))
        engine.accept(VehicleSample(1500, -2.0))
        assertEquals(1, engine.drive.sampleCount)
        assertEquals(1000L, engine.drive.durationMs)
        assertEquals(100, engine.drive.score)
    }

    @Test fun stationaryVehicleDoesNotEarnSmoothDrivingRewards() {
        val engine = DriveEngine(Drive(mode = PassengerMode.NORMAL, source = DriveSource.VEHICLE))
        for (t in 0L..120_000 step 500) engine.accept(VehicleSample(t, 0.0))
        assertTrue(engine.drive.events.isEmpty())
    }

    @Test fun rewardFrequencyAndDriveTotalAreBounded() {
        val engine = DriveEngine(Drive(mode = PassengerMode.NORMAL, source = DriveSource.VEHICLE))
        for (t in 0L..600_000 step 500) engine.accept(VehicleSample(t, 12.0))
        val rewards = engine.drive.events.filter { it.kind == EventKind.SMOOTH }
        assertEquals(10, rewards.size)
        rewards.zipWithNext().forEach { (a, b) -> assertTrue(b.atMs - a.atMs >= 20_000) }
    }

    @Test fun scoreNeverFallsBelowZero() {
        val many = (1..100).map { DriveEvent(atMs = it.toLong(), kind = EventKind.ACCELERATION, vehiclePoints = -1, comfortPoints = -4, observation = "accel") }
        val events = ScorePolicy.recompute(many)
        assertEquals(0, events.last().after)
        assertTrue(events.all { it.after in 0..100 })
    }

    @Test fun frontWheelObservationIsNeverAnUnvalidatedWearPenalty() {
        val engine = DriveEngine(Drive(mode = PassengerMode.CHILD, source = DriveSource.VEHICLE))
        engine.accept(VehicleSample(0, 0.0, frontWheelAngle = 0.0))
        engine.accept(VehicleSample(1000, 0.0, frontWheelAngle = 20.0))
        assertEquals(EventKind.STEERING, engine.drive.events.single().kind)
        assertEquals(100, engine.drive.score)
    }

    @Test fun shortDecelerationDoesNotClaimFiveSecondsOfGentleStopping() {
        val engine = DriveEngine(Drive(mode = PassengerMode.NORMAL, source = DriveSource.VEHICLE))
        for (t in 0L..1000 step 200) engine.accept(VehicleSample(t, 1.0 - t / 1000.0))
        assertFalse(engine.drive.events.any { it.kind == EventKind.GENTLE_STOP })
    }

    @Test fun sustainedGentleDecelerationEarnsAStoppingReward() {
        val engine = DriveEngine(Drive(mode = PassengerMode.NORMAL, source = DriveSource.VEHICLE))
        for (t in 0L..7000 step 500) engine.accept(VehicleSample(t, 7.0 - t / 1000.0))
        assertTrue(engine.drive.events.any { it.kind == EventKind.GENTLE_STOP })
    }
}
