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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cursormobile.data.net.IdeComposerDetail
import com.cursormobile.data.net.IdeComposerMessage
import com.cursormobile.data.net.IdeComposerMeta
import com.cursormobile.data.net.IdeStreamBody
import com.cursormobile.data.repo.IdeRepository
import com.cursormobile.data.repo.applyStream
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class IdeChatViewModel @Inject constructor(
    private val ideRepo: IdeRepository,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val id: String = savedState.get<String>("id") ?: ""
    private val _detail = MutableStateFlow<IdeComposerDetail?>(null)
    val detail: StateFlow<IdeComposerDetail?> = _detail.asStateFlow()
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()
    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _meta = MutableStateFlow<IdeComposerMeta?>(null)
    val meta: StateFlow<IdeComposerMeta?> = _meta.asStateFlow()
    private val _showInfo = MutableStateFlow(false)
    val showInfo: StateFlow<Boolean> = _showInfo.asStateFlow()
    val bridgeReady = ideRepo.bridgeReady
    private var activeRunId: String? = null

    init {
        viewModelScope.launch { runCatching { ideRepo.list() } }
        viewModelScope.launch { runCatching { ideRepo.watch(id) } }
        viewModelScope.launch {
            runCatching { ideRepo.get(id) }
                .onSuccess {
                    _detail.value = it.composer
                    _meta.value = it.meta
                }
                .onFailure { _error.value = it.message ?: "Couldn't load chat" }
            _loading.value = false
        }
        viewModelScope.launch {
            ideRepo.streamEvents.collect { event ->
                if (event.composerId != id) return@collect
                when (event.event.type) {
                    "status" -> {
                        val gen = event.event.status == "generating"
                        _generating.value = gen
                        if (!gen) activeRunId = null
                    }
                }
                ensureDetailShell()
                _detail.value = _detail.value?.applyStream(event)
            }
        }
        viewModelScope.launch {
            ideRepo.runResults.collect { result ->
                if (result.composerId != id) return@collect
                _generating.value = false
                _sending.value = false
                _detail.value = _detail.value?.let { d ->
                    d.copy(messages = d.messages.map { m ->
                        if (m.isStreaming) m.copy(isStreaming = false) else m
                    })
                }
                if (result.runId == activeRunId) activeRunId = null
            }
        }
    }

    private fun ensureDetailShell() {
        if (_detail.value != null) return
        _detail.value = IdeComposerDetail(
            id = id,
            name = "Cursor IDE chat",
            messages = emptyList(),
        )
        _loading.value = false
    }

    suspend fun sendMessage(prompt: String) {
        _sending.value = true
        _error.value = null
        ensureDetailShell()
        _detail.value = _detail.value!!.copy(
            messages = _detail.value!!.messages + IdeComposerMessage(
                bubbleId = "pending-${System.currentTimeMillis()}",
                role = "user",
                text = prompt,
            ),
            bubbleCount = _detail.value!!.messages.size + 1,
        )
        try {
            val runId = ideRepo.send(id, prompt)
            activeRunId = runId
        } catch (e: Exception) {
            _generating.value = false
            activeRunId = null
            _error.value = e.message ?: "Send failed"
            throw e
        } finally {
            _sending.value = false
        }
    }

    fun cancelGeneration() {
        _generating.value = false
        _sending.value = false
        val runId = activeRunId
        activeRunId = null
        viewModelScope.launch {
            runCatching { ideRepo.cancel(id, runId) }
                .onFailure { _error.value = it.message ?: "Cancel failed" }
        }
    }

    fun openInfo() {
        _showInfo.value = true
        viewModelScope.launch {
            runCatching { ideRepo.get(id) }.onSuccess {
                _meta.value = it.meta
                it.composer?.let { c -> _detail.value = c }
            }
        }
    }

    fun closeInfo() { _showInfo.value = false }

    fun refresh() {
        viewModelScope.launch {
            runCatching { ideRepo.get(id) }.onSuccess {
                _detail.value = it.composer
                _meta.value = it.meta
            }
        }
    }

    override fun onCleared() {
        viewModelScope.launch { ideRepo.unwatch(id) }
        super.onCleared()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdeChatScreen(id: String, onBack: () -> Unit, onOpenAgent: (String) -> Unit) {
    val vm: IdeChatViewModel = hiltViewModel()
    val detail by vm.detail.collectAsState()
    val loading by vm.loading.collectAsState()
    val sending by vm.sending.collectAsState()
    val generating by vm.generating.collectAsState()
    val error by vm.error.collectAsState()
    val bridgeReady by vm.bridgeReady.collectAsState()
    val meta by vm.meta.collectAsState()
    val showInfo by vm.showInfo.collectAsState()
    val sheetState = rememberModalBottomSheetState()
    val listState = rememberLazyListState()
    var draft by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(detail?.messages?.size, detail?.messages?.lastOrNull()?.text, generating) {
        detail?.messages?.size?.takeIf { it > 0 }?.let { listState.animateScrollToItem(it - 1) }
    }

    Scaffold(
        modifier = Modifier
            .imePadding()
            .windowInsetsPadding(WindowInsets.navigationBars),
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(detail?.name ?: "Cursor IDE chat", maxLines = 1, style = MaterialTheme.typography.titleMedium)
                            Text(
                                when {
                                    !bridgeReady -> "Mac bridge not ready"
                                    generating -> "Streaming from Mac…"
                                    sending -> "Sending…"
                                    else -> "Live IDE composer"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null) }
                    },
                    actions = {
                        IconButton(onClick = { vm.openInfo() }) {
                            Icon(Icons.Outlined.Info, contentDescription = "Chat info")
                        }
                        if (generating) {
                            IconButton(onClick = { vm.cancelGeneration() }) {
                                Icon(Icons.Outlined.Close, contentDescription = "Stop generation")
                            }
                        }
                    },
                )
                if (generating) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth()) {
                error?.let { msg ->
                    Text(
                        msg,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val sendIt: () -> Unit = {
                        val t = draft.trim()
                        if (t.isNotEmpty() && !sending) {
                            scope.launch {
                                runCatching { vm.sendMessage(t) }.onSuccess { draft = "" }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        enabled = !sending,
                        placeholder = { Text("Message this chat…") },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Send,
                        ),
                        keyboardActions = KeyboardActions(onSend = { sendIt() }),
                        modifier = Modifier.weight(1f),
                        maxLines = 4,
                        shape = MaterialTheme.shapes.large,
                    )
                    IconButton(onClick = sendIt, enabled = !sending && draft.isNotBlank()) {
                        if (sending) {
                            CircularProgressIndicator(modifier = Modifier.padding(4.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Outlined.Send, contentDescription = null)
                        }
                    }
                }
            }
        },
    ) { inner ->
        when {
            loading && detail == null -> Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            detail == null -> Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                Text("Couldn't load chat.", color = MaterialTheme.colorScheme.error)
            }
            else -> {
                Column(Modifier.fillMaxSize().padding(inner)) {
                    if (detail!!.truncated) {
                        Text(
                            "Showing recent messages · ${detail!!.bubbleCount} total",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
                    ) {
                        items(detail!!.messages, key = { it.bubbleId }) { m -> IdeMessageRow(m) }
                    }
                }
            }
        }
    }

    if (showInfo) {
        ModalBottomSheet(onDismissRequest = { vm.closeInfo() }, sheetState = sheetState) {
            IdeChatInfoSheet(meta = meta, bridgeReady = bridgeReady)
        }
    }
}

@Composable
private fun IdeChatInfoSheet(meta: IdeComposerMeta?, bridgeReady: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("IDE chat info", style = MaterialTheme.typography.titleMedium)
        InfoRow("Bridge", if (bridgeReady) "Connected" else "Not ready")
        meta?.modelName?.let { InfoRow("Model", it + if (meta.maxMode == true) " · Max" else "") }
        meta?.contextUsagePercent?.let { pct ->
            val used = meta.contextTokensUsed?.let { " · ${it / 1000}k tokens" } ?: ""
            InfoRow("Context", "${pct.toInt()}%$used")
        }
        meta?.filesChangedCount?.let { count ->
            val lines = buildString {
                append("$count files")
                meta.totalLinesAdded?.takeIf { it > 0 }?.let { append(" · +$it") }
                meta.totalLinesRemoved?.takeIf { it > 0 }?.let { append(" · -$it") }
            }
            InfoRow("Session changes", lines)
        }
        meta?.gitBranch?.let { InfoRow("Git branch", it) }
        meta?.gitStatus?.let { InfoRow("Git status", it) }
        meta?.workspacePath?.let { InfoRow("Workspace", it) }
        meta?.composerStatus?.let { InfoRow("Composer", it) }
        Text(
            "Model switching from phone is coming soon — change it in Cursor on your Mac for now.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun IdeMessageRow(m: IdeComposerMessage) {
    when (m.role) {
        "user" -> UserBubble(m.text)
        "tool" -> IdeToolCard(m)
        else -> AssistantBlock(m.text, m.isStreaming)
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            Modifier
                .widthIn(max = 320.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.large)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(text, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun AssistantBlock(text: String, isStreaming: Boolean) {
    if (text.isBlank() && !isStreaming) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .width(3.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f), MaterialTheme.shapes.extraSmall),
        )
        Column(Modifier.weight(1f)) {
            if (text.isBlank() && isStreaming) {
                Text(
                    "Thinking…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                MarkdownText(text = text, isStreaming = isStreaming)
            }
        }
    }
}
