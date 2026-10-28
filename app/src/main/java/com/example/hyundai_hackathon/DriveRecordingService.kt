package com.example.hyundai_hackathon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.example.hyundai_hackathon.domain.DriveSource

class DriveRecordingService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "주행 기록", NotificationManager.IMPORTANCE_LOW).apply {
            description = "주행을 기록하는 동안 조용히 표시합니다"
            setSound(null, null); enableVibration(false); setShowBadge(false)
        })
        val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(101, Notification.Builder(this, CHANNEL)
            .setContentTitle("온유 드라이브 · 기록 중")
            .setContentText("운전에 집중하세요. 상세 기록은 주차 후 확인할 수 있어요.")
            .setSmallIcon(R.drawable.ic_drive_notification).setContentIntent(intent).setOngoing(true)
            .setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE).build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val source = runCatching { DriveSource.valueOf(intent?.getStringExtra("source") ?: "VEHICLE") }.getOrDefault(DriveSource.VEHICLE)
        val runtime = CoachRuntime.get(this)
        if (runtime.state.value.active == null && runtime.canStart(source)) runtime.start(source)
        if (runtime.state.value.active == null) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() { CoachRuntime.get(this).interruptIfRecording(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object { const val CHANNEL = "drive_recording" }
}
