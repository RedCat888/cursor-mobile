package com.cursormobile.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cursormobile.data.crypto.Crypto
import com.cursormobile.data.net.ConnState
import com.cursormobile.data.net.MessageTypes
import com.cursormobile.data.net.RemoteClient
import com.cursormobile.data.net.SysStatusOk
import com.cursormobile.data.prefs.AuthStore
import com.cursormobile.data.prefs.PairedMac
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    val auth: AuthStore,
    private val client: RemoteClient,
) : ViewModel() {
    val state: StateFlow<ConnState> = client.state
    private val _status = MutableStateFlow<SysStatusOk?>(null)
    val status: StateFlow<SysStatusOk?> = _status.asStateFlow()

    init {
        viewModelScope.launch { runCatching { refresh() } }
    }

    suspend fun refresh() {
        val env = client.request(MessageTypes.SYS_STATUS, buildJsonObject {}, MessageTypes.SYS_STATUS_OK)
        _status.value = client.json.decodeFromJsonElement(SysStatusOk.serializer(), env.body!!)
    }

    fun unpair() = viewModelScope.launch {
        auth.activePairId?.let { auth.forgetPair(it) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onUnpair: () -> Unit) {
    val vm: SettingsViewModel = hiltViewModel()
    val state by vm.state.collectAsState()
    val status by vm.status.collectAsState()
    val pair = remember { vm.auth.activePair() }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Paired Mac", style = MaterialTheme.typography.titleMedium)
                    Text(pair?.macLabel ?: "—", style = MaterialTheme.typography.bodyMedium)
                    Text("Fingerprint: ${pair?.macPubKey?.let { Crypto.fingerprint(it) } ?: "—"}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Status: $state", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            status?.let { s ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Daemon", style = MaterialTheme.typography.titleMedium)
                        Text("v${s.daemonVersion} · uptime ${(s.uptimeMs / 60_000)}m", style = MaterialTheme.typography.bodyMedium)
                        Text("API key: ${if (s.cursorApiKey) "configured" else "missing"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("FCM: ${if (s.fcm) "enabled" else "off"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Agents on file: ${s.agentCount}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Transport", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "When on the same Wi-Fi as your Mac you can connect directly for sub-50ms latency. Leave blank to always use the cloud relay.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.padding(top = 8.dp))
                    var lanHost by remember { mutableStateOf(vm.auth.lanHost ?: "") }
                    var lanPort by remember { mutableStateOf(vm.auth.lanPort.toString()) }
                    OutlinedTextField(
                        value = lanHost,
                        onValueChange = { lanHost = it },
                        label = { Text("Mac LAN IP (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = lanPort,
                        onValueChange = { lanPort = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Port") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.padding(top = 8.dp))
                    OutlinedButton(
                        onClick = {
                            vm.auth.lanHost = lanHost.trim().ifBlank { null }
                            vm.auth.lanPort = lanPort.toIntOrNull() ?: 7681
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Save transport") }
                }
            }
            Button(
                onClick = { vm.unpair(); onUnpair() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Unpair this Mac") }
        }
    }
}
