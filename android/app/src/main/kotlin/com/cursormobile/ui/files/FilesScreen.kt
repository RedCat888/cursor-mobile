package com.cursormobile.ui.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cursormobile.data.net.FsEntry
import com.cursormobile.data.repo.FilesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FilesUiState(
    val path: String = "",
    val entries: List<FsEntry> = emptyList(),
    val loading: Boolean = false,
    val openFile: String? = null,
    val openContent: String = "",
    val saving: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class FilesViewModel @Inject constructor(private val repo: FilesRepository) : ViewModel() {
    private val _state = MutableStateFlow(FilesUiState())
    val state: StateFlow<FilesUiState> = _state.asStateFlow()

    init { browse(null) }

    fun browse(path: String?) = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = null)
        val target = path ?: run {
            runCatching { repo.workspaces().home }
                .getOrElse { "/" }
        }
        runCatching { repo.list(target) }.onSuccess {
            _state.value = _state.value.copy(path = it.path, entries = it.entries, loading = false)
        }.onFailure { e ->
            _state.value = _state.value.copy(loading = false, error = e.message)
        }
    }

    fun openFile(path: String) = viewModelScope.launch {
        runCatching { repo.read(path) }.onSuccess { (_, text) ->
            _state.value = _state.value.copy(openFile = path, openContent = text)
        }
    }

    fun closeFile() { _state.value = _state.value.copy(openFile = null, openContent = "") }

    fun updateContent(s: String) { _state.value = _state.value.copy(openContent = s) }

    fun save() = viewModelScope.launch {
        val s = _state.value
        val target = s.openFile ?: return@launch
        _state.value = s.copy(saving = true)
        runCatching { repo.write(target, s.openContent) }
        _state.value = _state.value.copy(saving = false)
    }

    fun openInIde() = viewModelScope.launch {
        val target = _state.value.openFile ?: _state.value.path
        runCatching { repo.openInIde(target) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen() {
    val vm: FilesViewModel = hiltViewModel()
    val s by vm.state.collectAsState()

    if (s.openFile != null) {
        EditorView(
            path = s.openFile!!,
            content = s.openContent,
            saving = s.saving,
            onChange = vm::updateContent,
            onSave = vm::save,
            onOpenInIde = vm::openInIde,
            onClose = vm::closeFile,
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s.path.ifEmpty { "Files" }, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    val parent = s.path.substringBeforeLast('/', "")
                    if (parent.isNotEmpty() && parent != s.path) {
                        IconButton(onClick = { vm.browse(parent) }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = vm::openInIde) { Icon(Icons.Outlined.OpenInNew, null) }
                },
            )
        },
    ) { inner ->
        LazyColumn(Modifier.fillMaxSize().padding(inner)) {
            items(s.entries, key = { it.name }) { e ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            val full = "${s.path.trimEnd('/')}/${e.name}"
                            if (e.kind == "dir") vm.browse(full) else vm.openFile(full)
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        if (e.kind == "dir") Icons.Outlined.FolderOpen else Icons.Outlined.Description,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(e.name, style = MaterialTheme.typography.bodyLarge)
                        if (e.kind != "dir") {
                            Text(
                                humanSize(e.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorView(
    path: String,
    content: String,
    saving: Boolean,
    onChange: (String) -> Unit,
    onSave: () -> Unit,
    onOpenInIde: () -> Unit,
    onClose: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(path.substringAfterLast('/'), style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null) } },
                actions = {
                    IconButton(onClick = onOpenInIde) { Icon(Icons.Outlined.OpenInNew, null) }
                    IconButton(onClick = onSave, enabled = !saving) { Icon(Icons.Outlined.Save, null) }
                },
            )
        },
    ) { inner ->
        OutlinedTextField(
            value = content,
            onValueChange = onChange,
            modifier = Modifier.fillMaxSize().padding(inner).padding(8.dp),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )
    }
}

private fun humanSize(b: Long): String {
    val kb = 1024.0
    return when {
        b < kb -> "$b B"
        b < kb * kb -> "${(b / kb).toInt()} KB"
        b < kb * kb * kb -> "%.1f MB".format(b / (kb * kb))
        else -> "%.1f GB".format(b / (kb * kb * kb))
    }
}
