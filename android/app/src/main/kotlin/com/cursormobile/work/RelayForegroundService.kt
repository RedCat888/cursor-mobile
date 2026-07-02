package com.cursormobile.work

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.cursormobile.MainActivity
import com.cursormobile.R
import com.cursormobile.data.net.ConnState
import com.cursormobile.data.net.RemoteClient
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Lifecycle-aware foreground service that keeps the WS open even when the
 * user backgrounds the app. Without this, Android suspends the process and
 * we'd miss streaming chunks until the app is reopened.
 *
 * Promoted as "data sync" foreground type which is the appropriate category
 * for keeping a persistent app↔server channel alive.
 */
@AndroidEntryPoint
class RelayForegroundService : LifecycleService() {

    @Inject lateinit var client: RemoteClient

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(NOTIF_ID, buildNotification(ConnState.Connecting))
        client.start()
        lifecycleScope.launch {
            client.state.collectLatest { state ->
                val n = buildNotification(state)
                ServiceCompat.startForeground(this@RelayForegroundService, NOTIF_ID, n, FOREGROUND_TYPE)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        client.stop()
        super.onDestroy()
    }

    private fun buildNotification(state: ConnState): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = when (state) {
            ConnState.Online -> "Connected to your Mac"
            ConnState.Connecting -> "Reconnecting…"
            ConnState.Offline -> "Offline"
        }
        return NotificationCompat.Builder(this, getString(R.string.fcm_channel_id))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Cursor Mobile")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pi)
            .build()
    }

    companion object {
        const val NOTIF_ID = 1001
        private const val FOREGROUND_TYPE = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    }
}
