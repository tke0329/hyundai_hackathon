package com.example.hyundai_hackathon

import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.hyundai_hackathon.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DriveFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun demoRecordsAcrossBackgroundAndOpensItsEventDetails() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            instrument.uiAutomation.executeShellCommand("pm grant com.example.hyundai_hackathon android.permission.POST_NOTIFICATIONS").close()
        }
        val runtime = CoachRuntime.get(compose.activity)
        compose.waitUntil(20_000) { !runtime.state.value.loading }
        capture("home")
        compose.onNodeWithText("어린이 동승", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("체험 주행 · 약 1분").performScrollTo().performClick()
        compose.waitUntil(20_000) { runtime.state.value.active?.events?.any { it.kind == EventKind.ACCELERATION } == true }
        val id = runtime.state.value.active!!.id
        assertEquals(PassengerMode.CHILD, runtime.state.value.active!!.mode)
        assertTrue(runtime.state.value.active!!.score < 100)
        capture("driving")
        val before = runtime.state.value.active!!.durationMs
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        Thread.sleep(2500)
        assertTrue(runtime.state.value.active!!.durationMs > before)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(35_000) { runtime.state.value.active?.events?.any { it.kind == EventKind.BRAKING } == true }
        compose.onNodeWithText("주행 마치고 기록 보기").performScrollTo().performClick()
        compose.waitUntil(10_000) { runtime.state.value.history.any { it.id == id } }
        compose.onNodeWithText("확인").performClick()
        compose.onNodeWithTag("drive-$id").performScrollTo().performClick()
        compose.onNodeWithText("이번 이동을 돌아볼까요?").assertIsDisplayed()
        capture("report")
        val event = runtime.state.value.history.first { it.id == id }.events.first { it.kind == EventKind.ACCELERATION }
        compose.onNodeWithTag("drive-report").performScrollToNode(hasTestTag("event-${event.id}"))
        compose.onNodeWithTag("event-${event.id}").performClick()
        compose.onNodeWithText("점수에 반영된 요인").assertExists()
        compose.onNodeWithText("동승 배려 추가 가중치").assertExists()
        capture("event")
        assertEquals(-4, event.comfortPoints)
        compose.onNodeWithText("←  드라이브 상세").performScrollTo().performClick()
        val brake = runtime.state.value.history.first { it.id == id }.events.first { it.kind == EventKind.BRAKING }
        compose.onNodeWithTag("drive-report").performScrollToNode(hasTestTag("event-${brake.id}"))
        compose.onNodeWithTag("event-${brake.id}").performClick()
        compose.onNodeWithText("위험 회피 · 평가 제외").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(EventReview.EXCLUDED, runtime.state.value.history.first { it.id == id }.events.first { it.id == brake.id }.review) }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val i = InstrumentationRegistry.getInstrumentation()
        i.waitForIdleSync()
        Thread.sleep(400)
        val folder = File(i.targetContext.filesDir, "verification").apply { mkdirs() }
        val bitmap = i.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
