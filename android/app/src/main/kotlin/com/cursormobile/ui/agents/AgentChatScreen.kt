package com.cursormobile.ui.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cursormobile.data.db.MessageEntity
import com.cursormobile.data.net.ModelDescriptor
import com.cursormobile.data.repo.AgentsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class AgentChatViewModel @Inject constructor(
    private val repo: AgentsRepository,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val agentId: String = savedState.get<String>("id") ?: ""
    val messages = repo.observeMessages(agentId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val agent = repo.observeAgent(agentId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val models = repo.models

    init {
        viewModelScope.launch {
            runCatching { repo.reapStalePending(System.currentTimeMillis() - 5 * 60_000L) }
            runCatching { repo.loadModels() }
            runCatching { repo.openInDetail(agentId) }
        }
    }

    fun send(text: String) = viewModelScope.launch { runCatching { repo.send(agentId, text) } }
    fun cancel(runId: String) = viewModelScope.launch { runCatching { repo.cancel(agentId, runId) } }
    fun setModel(model: String) = viewModelScope.launch { runCatching { repo.setModel(agentId, model) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentChatScreen(agentId: String, onBack: () -> Unit) {
    val vm: AgentChatViewModel = hiltViewModel()
    val msgs by vm.messages.collectAsState()
    val agent by vm.agent.collectAsState()
    val models by vm.models.collectAsState()
    val listState = rememberLazyListState()
    var draft by remember { mutableStateOf("") }
    var modelMenuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(msgs.size, msgs.lastOrNull()?.text) {
        if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size - 1)
    }

    Scaffold(
        modifier = Modifier
            .imePadding()
            .windowInsetsPadding(WindowInsets.navigationBars),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(agent?.title?.takeIf { it.isNotBlank() && it != "New Agent" } ?: "Agent", style = MaterialTheme.typography.titleMedium)
                        agent?.let { ModelPickerRow(it.model, models, modelMenuOpen, { modelMenuOpen = it }, { vm.setModel(it); modelMenuOpen = false }) }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null) }
                },
                actions = {
                    val running = msgs.lastOrNull { it.pending }
                    val runId = running?.runId
                    if (runId != null) {
                        IconButton(onClick = { vm.cancel(runId) }) { Icon(Icons.Outlined.Cancel, null) }
                    }
                },
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val sendIt: () -> Unit = {
                    val t = draft.trim()
                    if (t.isNotEmpty()) {
                        vm.send(t)
                        draft = ""
                    }
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("Message your agent") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendIt() }),
                    modifier = Modifier.weight(1f),
                    maxLines = 6,
                )
                IconButton(onClick = sendIt) { Icon(Icons.Outlined.Send, null) }
            }
        },
    ) { inner ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(inner).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
        ) {
            items(msgs, key = { it.id }) { m -> ChatBubble(m) }
        }
    }
}

@Composable
private fun ModelPickerRow(
    currentModel: String,
    models: List<ModelDescriptor>,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onPick: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { onMenuOpenChange(true) }, modifier = Modifier.padding(top = 2.dp)) {
            Text(currentModel, style = MaterialTheme.typography.labelSmall)
            Icon(Icons.Outlined.KeyboardArrowDown, null, modifier = Modifier.padding(start = 4.dp))
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpenChange(false) }) {
            if (models.isEmpty()) {
                DropdownMenuItem(text = { Text("auto") }, onClick = { onPick("auto") })
            } else {
                models.forEach { m ->
                    DropdownMenuItem(
                        text = { Text(m.displayName ?: m.id) },
                        onClick = { onPick(m.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(m: MessageEntity) {
    val isUser = m.role == "user"
    val align = if (isUser) Alignment.End else Alignment.Start
    Column(Modifier.fillMaxWidth(), horizontalAlignment = align) {
        when (m.role) {
            "tool" -> ToolCallBubble(m)
            "thinking", "system" -> Text(
                m.text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            )
            else -> {
                val containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
                val textColor = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface
                Box(
                    Modifier
                        .widthIn(max = 340.dp)
                        .background(containerColor, MaterialTheme.shapes.medium)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Column {
                        Text(
                            m.text.ifEmpty { if (m.pending) "…" else "" },
                            color = textColor,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (m.pending) {
                            Spacer(Modifier.padding(top = 4.dp))
                            Text("streaming…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}
