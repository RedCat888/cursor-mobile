package com.cursormobile.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.cursormobile.data.prefs.AuthStore
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Re-launch the foreground service after a reboot. Only does so if the user
 * has already paired a Mac (no point keeping a service alive for nothing).
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var auth: AuthStore

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (auth.activePairId == null) return
        ContextCompat.startForegroundService(context, Intent(context, RelayForegroundService::class.java))
    }
}
