package com.cursormobile.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cursormobile.data.net.PtyOpenedBody
import com.cursormobile.data.repo.TerminalRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class TerminalTab(
    val ptyId: String,
    val label: String,
    val buffer: String,
)

@HiltViewModel
class TerminalViewModel @Inject constructor(private val repo: TerminalRepository) : ViewModel() {
    private val _tabs = MutableStateFlow<List<TerminalTab>>(emptyList())
    val tabs: StateFlow<List<TerminalTab>> = _tabs.asStateFlow()
    private val _active = MutableStateFlow<String?>(null)
    val active: StateFlow<String?> = _active.asStateFlow()

    fun newTab() = viewModelScope.launch {
        runCatching { repo.openTerminal(cwd = null, cols = 100, rows = 30) }
            .onSuccess { opened ->
                val tab = TerminalTab(opened.ptyId, opened.shell.substringAfterLast('/'), buffer = "")
                _tabs.value = _tabs.value + tab
                _active.value = tab.ptyId
                viewModelScope.launch {
                    repo.stdoutFor(opened.ptyId).collectLatest { bytes ->
                        appendTo(opened.ptyId, bytes.toString(Charsets.UTF_8))
                    }
                }
            }
    }

    fun select(id: String) { _active.value = id }

    fun closeTab(id: String) = viewModelScope.launch {
        runCatching { repo.close(id) }
        _tabs.value = _tabs.value.filterNot { it.ptyId == id }
        if (_active.value == id) _active.value = _tabs.value.lastOrNull()?.ptyId
    }

    fun send(input: String) = viewModelScope.launch {
        val id = _active.value ?: return@launch
        runCatching { repo.writeStdin(id, input.toByteArray(Charsets.UTF_8)) }
    }

    fun sendKey(bytes: ByteArray) = viewModelScope.launch {
        val id = _active.value ?: return@launch
        runCatching { repo.writeStdin(id, bytes) }
    }

    private fun appendTo(id: String, chunk: String) {
        // Strip the common ANSI escape sequences so the output is readable in
        // our plain-text view. (We're not a full xterm emulator yet — that's a
        // future enhancement; for now the user just needs legible output.)
        val cleaned = ansiStrip(chunk)
        _tabs.value = _tabs.value.map {
            if (it.ptyId == id) it.copy(buffer = (it.buffer + cleaned).takeLast(200_000)) else it
        }
    }
}

/**
 * Strip CSI / OSC / SGR / DCS / bell etc. and bracketed-paste prologues so the
 * output is readable without an emulator. We keep \n, \r, \t and printable
 * characters. The PTY itself still receives the raw bytes the user types.
 */
private val ansiCsiRegex = Regex("""\u001B\[[0-?]*[ -/]*[@-~]""")
private val ansiOscRegex = Regex("""\u001B\][^\u0007\u001B]*(?:\u0007|\u001B\\)""")
private val ansiSingleRegex = Regex("""\u001B[=>NOPVWXZ\\\^_\[]""")
private fun ansiStrip(s: String): String =
    s.replace(ansiCsiRegex, "")
        .replace(ansiOscRegex, "")
        .replace(ansiSingleRegex, "")
        .replace("\u0007", "")
        .replace("\u0008", "")
        .replace("\u000F", "")
        .replace("\u000E", "")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen() {
    val vm: TerminalViewModel = hiltViewModel()
    val tabs by vm.tabs.collectAsState()
    val active by vm.active.collectAsState()
    var draft by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    val activeTab = tabs.firstOrNull { it.ptyId == active }

    LaunchedEffect(activeTab?.buffer?.length) { scroll.animateScrollTo(scroll.maxValue) }

    Scaffold(
        modifier = Modifier
            .imePadding()
            .windowInsetsPadding(WindowInsets.navigationBars),
        topBar = {
            TopAppBar(
                title = { Text("Terminal") },
                actions = { IconButton(onClick = vm::newTab) { Icon(Icons.Outlined.Add, null) } },
            )
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tabs.forEach { t ->
                    val selected = t.ptyId == active
                    if (selected) {
                        AssistChip(
                            onClick = { vm.select(t.ptyId) },
                            label = { Text(t.label) },
                            trailingIcon = {
                                IconButton(onClick = { vm.closeTab(t.ptyId) }, modifier = Modifier.size(20.dp)) {
                                    Icon(Icons.Outlined.Close, null)
                                }
                            },
                        )
                    } else {
                        SuggestionChip(onClick = { vm.select(t.ptyId) }, label = { Text(t.label) })
                    }
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .verticalScroll(scroll)
                    .padding(8.dp),
            ) {
                Text(
                    activeTab?.buffer ?: if (tabs.isEmpty()) "Tap + to start a shell on your Mac." else "Loading…",
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val fire: () -> Unit = {
                    val payload = draft + "\n"
                    draft = ""
                    vm.send(payload)
                }
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { fire() }),
                )
                IconButton(onClick = fire) { Icon(Icons.Outlined.KeyboardArrowUp, null) }
            }
            // Quick keys for common shortcuts that mobile keyboards lack.
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "Tab" to byteArrayOf(0x09),
                    "Esc" to byteArrayOf(0x1B),
                    "Ctrl-C" to byteArrayOf(0x03),
                    "Ctrl-D" to byteArrayOf(0x04),
                    "Ctrl-L" to byteArrayOf(0x0C),
                    "↑" to byteArrayOf(0x1B, 0x5B, 0x41),
                    "↓" to byteArrayOf(0x1B, 0x5B, 0x42),
                ).forEach { (label, bytes) ->
                    SuggestionChip(onClick = { vm.sendKey(bytes) }, label = { Text(label) })
                }
            }
        }
    }
}
