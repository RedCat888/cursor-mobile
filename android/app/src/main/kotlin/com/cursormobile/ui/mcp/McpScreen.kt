package com.cursormobile.ui.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cursormobile.data.net.McpServer
import com.cursormobile.data.repo.McpRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class McpViewModel @Inject constructor(private val repo: McpRepository) : ViewModel() {
    private val _servers = MutableStateFlow<List<McpServer>>(emptyList())
    val servers: StateFlow<List<McpServer>> = _servers.asStateFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        runCatching { repo.list() }.onSuccess { _servers.value = it.user }
    }

    fun toggle(server: McpServer) = viewModelScope.launch {
        runCatching { repo.toggle("user", null, server.name, !server.enabled) }
            .onSuccess { _servers.value = it }
    }

    fun delete(server: McpServer) = viewModelScope.launch {
        runCatching { repo.delete("user", null, server.name) }.onSuccess { _servers.value = it }
    }

    fun upsert(server: McpServer) = viewModelScope.launch {
        runCatching { repo.upsert("user", null, server) }.onSuccess { _servers.value = it }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpScreen() {
    val vm: McpViewModel = hiltViewModel()
    val servers by vm.servers.collectAsState()
    var showAdd by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MCP servers") },
                actions = { IconButton(onClick = { showAdd = true }) { Icon(Icons.Outlined.Add, null) } },
            )
        },
    ) { inner ->
        LazyColumn(Modifier.fillMaxSize().padding(inner).padding(horizontal = 12.dp)) {
            items(servers, key = { it.name }) { s ->
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Switch(checked = s.enabled, onCheckedChange = { vm.toggle(s) })
                            IconButton(onClick = { vm.delete(s) }) { Icon(Icons.Outlined.Delete, null) }
                        }
                        Text(
                            s.url ?: listOfNotNull(s.command, *(s.args?.toTypedArray() ?: emptyArray())).joinToString(" "),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (showAdd) AddServerDialog(onClose = { showAdd = false }, onSave = { vm.upsert(it); showAdd = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddServerDialog(onClose: () -> Unit, onSave: (McpServer) -> Unit) {
    var name by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("http") }
    var command by remember { mutableStateOf("") }
    var args by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Add MCP server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row { TextButton(onClick = { mode = "http" }) { Text("HTTP", color = if (mode == "http") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                    TextButton(onClick = { mode = "stdio" }) { Text("stdio", color = if (mode == "stdio") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) } }
                if (mode == "http") {
                    OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                } else {
                    OutlinedTextField(value = command, onValueChange = { command = it }, label = { Text("Command") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = args, onValueChange = { args = it }, label = { Text("Args (space-separated)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (name.isBlank()) return@Button
                onSave(
                    McpServer(
                        name = name.trim(),
                        url = url.takeIf { mode == "http" && it.isNotBlank() },
                        command = command.takeIf { mode == "stdio" && it.isNotBlank() },
                        args = args.takeIf { mode == "stdio" && it.isNotBlank() }?.split(" ")?.filter { it.isNotEmpty() },
                        type = mode,
                        enabled = true,
                    ),
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
