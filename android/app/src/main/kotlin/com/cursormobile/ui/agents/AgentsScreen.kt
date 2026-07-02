package com.cursormobile.ui.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cursormobile.data.db.AgentEntity
import com.cursormobile.data.net.AgentSummary
import com.cursormobile.data.net.ConnState
import com.cursormobile.data.net.IdeComposerSummary
import com.cursormobile.data.net.ModelDescriptor
import com.cursormobile.data.net.RemoteClient
import com.cursormobile.data.repo.AgentCreateOpts
import com.cursormobile.data.repo.AgentsRepository
import com.cursormobile.data.repo.FilesRepository
import com.cursormobile.data.repo.IdeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

@HiltViewModel
class AgentsListViewModel @Inject constructor(
    private val repo: AgentsRepository,
    private val files: FilesRepository,
    private val ide: IdeRepository,
    val client: RemoteClient,
) : ViewModel() {
    val agents = repo.observeAgents().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val models: StateFlow<List<ModelDescriptor>> = repo.models
    val state: StateFlow<ConnState> = client.state

    private val _ideComposers = MutableStateFlow<List<IdeComposerSummary>>(emptyList())
    val ideComposers: StateFlow<List<IdeComposerSummary>> = _ideComposers.asStateFlow()

    private val _showCreate = MutableStateFlow(false)
    val showCreate: StateFlow<Boolean> = _showCreate.asStateFlow()
    private val _workspaces = MutableStateFlow<List<String>>(emptyList())
    val workspaces: StateFlow<List<String>> = _workspaces.asStateFlow()

    fun openCreate() {
        _showCreate.value = true
        viewModelScope.launch {
            runCatching { repo.loadModels() }
            runCatching {
                val ws = files.workspaces()
                val all = (ws.recent.map { it.path } + ws.allowed + ws.home).distinct()
                _workspaces.value = all
            }
        }
    }
    fun closeCreate() { _showCreate.value = false }

    init {
        viewModelScope.launch {
            runCatching { repo.refreshList() }
            delay(500)
            runCatching { repo.refreshList() }
        }
        viewModelScope.launch {
            runCatching {
                val list = ide.list().composers
                _ideComposers.value = list
            }.onFailure {
                // IDE chats are best-effort; daemon may not have SQLite access.
            }
        }
    }

    fun refresh() = viewModelScope.launch {
        runCatching { repo.refreshList() }
        runCatching { _ideComposers.value = ide.list().composers }
    }
    suspend fun create(opts: AgentCreateOpts): AgentSummary = repo.create(opts)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsScreen(onOpen: (String) -> Unit, onOpenIde: (String) -> Unit) {
    val vm: AgentsListViewModel = hiltViewModel()
    val agents by vm.agents.collectAsState()
    val ideComposers by vm.ideComposers.collectAsState()
    val state by vm.state.collectAsState()
    val showCreate by vm.showCreate.collectAsState()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = { TopAppBar(title = { Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Agents")
            Spacer(Modifier.size(8.dp))
            ConnectionDot(state)
        } }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = vm::openCreate,
                icon = { Icon(Icons.Outlined.Add, null) },
                text = { Text("New") },
            )
        },
    ) { inner ->
        LazyColumn(Modifier.fillMaxSize().padding(inner)) {
            if (ideComposers.isNotEmpty()) {
                item {
                    SectionHeader(
                        "From Cursor IDE",
                        "${ideComposers.size} chats · open history, continue on phone",
                    )
                }
                items(ideComposers, key = { "ide:" + it.id }) { c ->
                    IdeRow(c, onClick = { onOpenIde(c.id) })
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                }
            }

            if (agents.isNotEmpty()) {
                item { SectionHeader("Mobile agents", "Type, stream, tools, switch models") }
            }
            items(agents, key = { "sdk:" + it.agentId }) { a ->
                AgentRow(a, onClick = { onOpen(a.agentId) })
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
            if (agents.isEmpty() && ideComposers.isEmpty()) {
                item { EmptyState(Modifier.fillMaxWidth().padding(top = 80.dp)) }
            }
        }

        if (showCreate) {
            NewAgentSheet(
                models = vm.models.collectAsState().value,
                workspaces = vm.workspaces.collectAsState().value,
                onDismiss = vm::closeCreate,
                onCreate = { opts ->
                    scope.launch {
                        runCatching { val a = vm.create(opts); onOpen(a.agentId) }
                        vm.closeCreate()
                    }
                },
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun IdeRow(c: IdeComposerSummary, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.ChatBubbleOutline,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f)) {
            Text(c.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(
                "${c.bubbleCount} msgs · ${relativeTime(c.lastUpdatedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "IDE",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f), MaterialTheme.shapes.small)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun AgentRow(a: AgentEntity, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (a.runtime == "cloud") Icons.Outlined.Cloud else Icons.Outlined.Computer,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f)) {
            Text(a.title ?: a.agentId.take(10), style = MaterialTheme.typography.titleMedium)
            Text(
                buildString {
                    append(a.model)
                    a.cwd?.let { append(" · "); append(it.substringAfterLast('/')) }
                    append(" · ")
                    append(relativeTime(a.lastActivityAt))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatusChip(a.status)
    }
}

@Composable
private fun StatusChip(status: String) {
    val (label, color) = when (status) {
        "running" -> "RUN" to MaterialTheme.colorScheme.primary
        "errored" -> "ERR" to MaterialTheme.colorScheme.error
        "finished" -> "DONE" to MaterialTheme.colorScheme.secondary
        else -> "IDLE" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun ConnectionDot(state: ConnState) {
    val color = when (state) {
        ConnState.Online -> Color(0xFF7CE38B)
        ConnState.Connecting -> Color(0xFFE3B23C)
        ConnState.Offline -> Color(0xFFE25C5C)
    }
    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("No agents yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(8.dp))
        Text("Tap New to start one on your Mac.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun relativeTime(ts: Long): String {
    val now = Clock.System.now()
    val then = Instant.fromEpochMilliseconds(ts)
    val secs = (now - then).inWholeSeconds
    return when {
        secs < 60 -> "${secs}s ago"
        secs < 3600 -> "${secs / 60}m ago"
        secs < 86_400 -> "${secs / 3600}h ago"
        else -> "${secs / 86_400}d ago"
    }
}
