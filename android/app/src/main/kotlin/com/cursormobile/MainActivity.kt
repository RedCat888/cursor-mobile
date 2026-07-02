package com.cursormobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.cursormobile.data.prefs.AuthStore
import com.cursormobile.ui.nav.CursorMobileNavHost
import com.cursormobile.ui.theme.CursorTheme
import com.cursormobile.work.RelayForegroundService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var auth: AuthStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // If we're already paired, kick the relay foreground service so the
        // WebSocket comes up immediately — without this the app sits on the
        // Agents tab with a red dot until the user navigates.
        if (auth.activePairId != null) {
            ContextCompat.startForegroundService(this, Intent(this, RelayForegroundService::class.java))
        }

        setContent {
            CursorTheme {
                CursorMobileNavHost(initialIntent = intent)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}
