package com.example.hyundai_hackathon

import android.content.pm.PackageManager
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hyundai_hackathon.vehicle.CarBridge
import com.example.hyundai_hackathon.vehicle.VehicleState
import com.example.hyundai_hackathon.domain.DriveSource
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VehicleBridgeTest {
    @Test fun detailsRequireExplicitParkAndKnownUnrestrictedUx() {
        val parked = VehicleState(automotive = true, connected = true, gear = 4,
            speedMps = 0.0, uxKnown = true, uxRestricted = false)
        assertTrue(parked.interactionAllowed)
        assertFalse(parked.copy(gear = 8).interactionAllowed)
        assertFalse(parked.copy(gear = null).interactionAllowed)
        assertFalse(parked.copy(uxKnown = false).interactionAllowed)
        assertFalse(parked.copy(uxRestricted = true).interactionAllowed)
        assertFalse(parked.copy(speedMps = 2.0).interactionAllowed)
        assertFalse(parked.copy(connected = false).interactionAllowed)
        assertTrue(VehicleState(automotive = false).interactionAllowed)
    }

    @Test fun pleosExposesReadOnlyVehicleSignalsAndUxState() {
        val i = InstrumentationRegistry.getInstrumentation()
        val context = i.targetContext
        assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE))
        assumeTrue(context.checkSelfPermission("android.car.permission.CAR_SPEED") == PackageManager.PERMISSION_GRANTED)
        val latest = AtomicReference(VehicleState(automotive = true))
        var bridge: CarBridge? = null
        try {
            i.runOnMainSync { bridge = CarBridge(context) { latest.set(it) }.also { it.start() } }
            val deadline = SystemClock.elapsedRealtime() + 45_000
            while (SystemClock.elapsedRealtime() < deadline) {
                val s = latest.get()
                if (s.connected && s.gear != null && s.uxKnown && s.speedMps != null && s.brake != null && s.wheelAngle != null) break
                Thread.sleep(200)
            }
            val state = latest.get()
            println("Pleos adapter: $state")
            assertTrue("AAOS connection: $state", state.connected)
            assertNotNull("Gear signal: $state", state.gear)
            assertTrue("UX restriction state: $state", state.uxKnown)
            assertNotNull("Speed signal: $state", state.speedMps)
            assertNotNull("Pleos brake signal: $state", state.brake)
            assertNotNull("Pleos front-wheel angle: $state", state.wheelAngle)
        } finally {
            i.runOnMainSync { bridge?.close() }
        }
    }

    @Test fun parkedPleosCanRecordEvenWhenStationarySpeedTimestampStopsChanging() {
        val i = InstrumentationRegistry.getInstrumentation()
        val context = i.targetContext
        assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE))
        assumeTrue(context.checkSelfPermission("android.car.permission.CAR_SPEED") == PackageManager.PERMISSION_GRANTED)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val runtime = CoachRuntime.get(context)
            val deadline = SystemClock.elapsedRealtime() + 45_000
            while (SystemClock.elapsedRealtime() < deadline && !runtime.canStart(DriveSource.VEHICLE)) Thread.sleep(200)
            assertTrue("Parked vehicle ready: ${runtime.state.value.vehicle}", runtime.canStart(DriveSource.VEHICLE))
            scenario.onActivity { activity -> ContextCompat.startForegroundService(activity,
                Intent(activity, DriveRecordingService::class.java).putExtra("source", "VEHICLE")) }
            try {
                val startDeadline = SystemClock.elapsedRealtime() + 10_000
                while (runtime.state.value.active == null && SystemClock.elapsedRealtime() < startDeadline) Thread.sleep(100)
                assertEquals(DriveSource.VEHICLE, runtime.state.value.active?.source)
                Thread.sleep(2500)
                assertTrue((runtime.state.value.active?.durationMs ?: 0) >= 1000)
                val id = runtime.state.value.active!!.id
                scenario.onActivity { runtime.finish() }
                assertTrue(runtime.state.value.history.any { it.id == id && it.endedAt != null })
            } finally {
                scenario.onActivity { runtime.finish() }
            }
        }
    }
}
