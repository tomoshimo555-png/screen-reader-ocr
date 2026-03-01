package com.screenreader.ocr

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class ScreenReaderApp : Application() {

    companion object {
        const val CHANNEL_ID_CAPTURE = "screen_capture_channel"
        const val CHANNEL_ID_STATUS = "status_channel"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val captureChannel = NotificationChannel(
            CHANNEL_ID_CAPTURE,
            "画面キャプチャ",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "画面キャプチャ実行中の通知"
        }

        val statusChannel = NotificationChannel(
            CHANNEL_ID_STATUS,
            "ステータス",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "OCR処理の状態通知"
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(captureChannel)
        manager.createNotificationChannel(statusChannel)
    }
}
