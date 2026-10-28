package com.example.hyundai_hackathon

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.hyundai_hackathon.data.DriveJson
import com.example.hyundai_hackathon.domain.DriveSource
import com.example.hyundai_hackathon.ui.CoachApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var runtime: CoachRuntime
    private var speedPermissionAsked = false
    private val speedPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { runtime.reconnect() }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null && runtime.state.value.canInteract) lifecycleScope.launch {
            val snapshot = runtime.state.value.history
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val stream = contentResolver.openOutputStream(uri) ?: error("출력 파일을 열 수 없습니다")
                    stream.bufferedWriter().use { it.write(DriveJson.export(snapshot)) }
                }
            }
            runtime.showNotice(if (result.isSuccess) "선택한 위치에 주행 기록을 저장했어요." else "내보내기에 실패했어요. 저장 위치를 확인해주세요.")
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runtime = CoachRuntime.get(applicationContext)
        speedPermissionAsked = savedInstanceState?.getBoolean("speedPermissionAsked") ?: false
        setContent {
            CoachApp(runtime, onStart = ::startDrive, onRequestPermission = {
                if (!speedPermissionAsked && runtime.state.value.vehicle.gear == 4 &&
                    ContextCompat.checkSelfPermission(this, "android.car.permission.CAR_SPEED") != PackageManager.PERMISSION_GRANTED) {
                    speedPermissionAsked = true
                    speedPermission.launch("android.car.permission.CAR_SPEED")
                }
            }, onExport = {
                if (runtime.state.value.canInteract && runtime.state.value.active == null)
                    export.launch("onyou-drives-${System.currentTimeMillis()}.json")
            })
        }
    }
    private fun startDrive(source: DriveSource) {
        if (!runtime.canStart(source)) return
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        try {
            ContextCompat.startForegroundService(this, Intent(this, DriveRecordingService::class.java).putExtra("source", source.name))
        } catch (_: Exception) { runtime.showNotice("주행 기록을 시작하지 못했어요. 앱을 다시 열어 시도해주세요.") }
    }
    override fun onStart() { super.onStart(); runtime.attachUi() }
    override fun onStop() { runtime.detachUi(); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("speedPermissionAsked", speedPermissionAsked); super.onSaveInstanceState(outState) }
}
