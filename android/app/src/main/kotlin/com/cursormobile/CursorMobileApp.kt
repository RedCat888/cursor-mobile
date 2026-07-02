package com.cursormobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * App entry point. We initialize Hilt, WorkManager (manually so our HiltWorkerFactory
 * is in play), and the notification channel for FCM messages.
 */
@HiltAndroidApp
class CursorMobileApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(getString(R.string.fcm_channel_id)) != null) return
        val ch = NotificationChannel(
            getString(R.string.fcm_channel_id),
            getString(R.string.fcm_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Notifications from your Mac when agents finish, error, or need input."
            enableLights(true)
            enableVibration(true)
        }
        mgr.createNotificationChannel(ch)
    }
}
