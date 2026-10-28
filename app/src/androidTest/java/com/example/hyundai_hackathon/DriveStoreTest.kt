package com.example.hyundai_hackathon

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.hyundai_hackathon.data.DriveJson
import com.example.hyundai_hackathon.data.DriveStore
import com.example.hyundai_hackathon.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DriveStoreTest {
    private fun inStore(test: (DriveStore) -> Unit) {
        val parent = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(parent.cacheDir, "store-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(parent) { override fun getFilesDir(): File = dir }
        try { test(DriveStore(context)) } finally { dir.deleteRecursively() }
    }

    @Test fun interruptedCheckpointIsRecoveredOnceWithoutPretendingToResume() = inStore { store ->
        val drive = Drive(mode = PassengerMode.CHILD, source = DriveSource.VEHICLE, durationMs = 2500)
        store.checkpoint(drive)
        val restored = store.load().single()
        assertEquals(drive.id, restored.id)
        assertTrue(restored.interrupted)
        assertEquals(drive.startedAt + 2500, restored.endedAt)
        assertEquals(1, store.load().size)
    }

    @Test fun eventReviewTraceAndFeedbackSurviveSaving() = inStore { store ->
        val engine = DriveEngine(Drive(mode = PassengerMode.SENIOR, source = DriveSource.DEMO))
        for (at in 0L..DemoRoute.DURATION_MS step 500) engine.accept(DemoRoute.sample(at))
        val drive = engine.finish(DemoRoute.DURATION_MS, System.currentTimeMillis()).copy(comfortFeedback = 2, feedbackAuthor = "동승자 본인")
        store.complete(drive)
        val restored = store.load().single()
        assertEquals(drive, restored)
        assertTrue(restored.signalTrace.isNotEmpty())
        val exported = JSONObject(DriveJson.export(listOf(restored)))
        assertEquals("DEMO", exported.getJSONArray("drives").getJSONObject(0).getString("source"))
    }

    @Test fun deletionRemovesOnlyTheChosenDrive() = inStore { store ->
        val first = Drive(mode = PassengerMode.NORMAL, source = DriveSource.VEHICLE, endedAt = System.currentTimeMillis())
        val second = Drive(mode = PassengerMode.NORMAL, source = DriveSource.DEMO, endedAt = System.currentTimeMillis())
        store.save(first); store.save(second); store.delete(first.id)
        assertEquals(listOf(second.id), store.load().map { it.id })
        store.clear(); assertTrue(store.load().isEmpty())
    }

    @Test fun retentionRemovesOldRecordsWithoutInventingNewMeasurements() = inStore { store ->
        store.save(Drive(startedAt = System.currentTimeMillis() - 31L * 24 * 60 * 60 * 1000,
            endedAt = System.currentTimeMillis(), mode = PassengerMode.NORMAL, source = DriveSource.VEHICLE))
        assertTrue(store.load().isEmpty())
    }
}
