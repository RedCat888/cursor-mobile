package com.cursormobile.push

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.cursormobile.MainActivity
import com.cursormobile.R
import com.cursormobile.data.net.MessageTypes
import com.cursormobile.data.net.RemoteClient
import com.cursormobile.data.prefs.AuthStore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@AndroidEntryPoint
class FcmService : FirebaseMessagingService() {

    @Inject lateinit var auth: AuthStore
    @Inject lateinit var client: RemoteClient
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        auth.fcmToken = token
        scope.launch {
            // Push to daemon as soon as we're connected. If offline, RemoteClient
            // will replay via the outbox.
            runCatching {
                client.send(MessageTypes.SYS_FCM, buildJsonObject { put("token", token) })
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val title = message.notification?.title ?: message.data["title"] ?: "Cursor Mobile"
        val body = message.notification?.body ?: message.data["body"] ?: return
        val agentId = message.data["agentId"]
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                putExtra("agentId", agentId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, getString(R.string.fcm_channel_id))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationManager.IMPORTANCE_HIGH)
            .setContentIntent(pi)
            .build()
        NotificationManagerCompat.from(this).notify((agentId?.hashCode() ?: 0xC0), n)
    }
}
