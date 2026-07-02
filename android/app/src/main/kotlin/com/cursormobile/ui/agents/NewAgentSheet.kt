package com.cursormobile.ui.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cursormobile.data.net.FsRecentWorkspace
import com.cursormobile.data.net.ModelDescriptor
import com.cursormobile.data.repo.AgentCreateOpts

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewAgentSheet(
    models: List<ModelDescriptor>,
    workspaces: List<String>,
    onDismiss: () -> Unit,
    onCreate: (AgentCreateOpts) -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var runtime by remember { mutableStateOf("local") }
    var cwd by remember { mutableStateOf(workspaces.firstOrNull() ?: "") }
    var prompt by remember { mutableStateOf("") }
    var modelId by remember { mutableStateOf("auto") }
    var modelMenu by remember { mutableStateOf(false) }
    var cwdMenu by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("New agent", style = MaterialTheme.typography.titleLarge)

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("local", "cloud").forEachIndexed { i, label ->
                    SegmentedButton(
                        selected = runtime == label,
                        onClick = { runtime = label },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                    ) { Text(label) }
                }
            }

            androidx.compose.foundation.layout.Box {
                androidx.compose.material3.OutlinedButton(
                    onClick = { modelMenu = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Model: $modelId") }
                DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                    DropdownMenuItem(text = { Text("auto") }, onClick = { modelId = "auto"; modelMenu = false })
                    models.forEach { m ->
                        DropdownMenuItem(text = { Text(m.displayName ?: m.id) }, onClick = { modelId = m.id; modelMenu = false })
                    }
                }
            }

            OutlinedTextField(
                value = cwd,
                onValueChange = { cwd = it },
                label = { Text(if (runtime == "local") "Working dir" else "Repo URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (runtime == "local" && workspaces.isNotEmpty()) {
                androidx.compose.foundation.layout.Box {
                    OutlinedButton(
                        onClick = { cwdMenu = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Pick from ${workspaces.size} recent…") }
                    DropdownMenu(expanded = cwdMenu, onDismissRequest = { cwdMenu = false }) {
                        workspaces.forEach { ws ->
                            DropdownMenuItem(
                                text = { Text(ws.substringAfterLast('/'), maxLines = 1) },
                                onClick = { cwd = ws; cwdMenu = false },
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("Initial prompt (optional)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    onCreate(
                        AgentCreateOpts(
                            cwd = cwd.takeIf { it.isNotBlank() },
                            model = modelId,
                            runtime = runtime,
                            initialPrompt = prompt.takeIf { it.isNotBlank() },
                        ),
                    )
                },
            ) { Text("Start") }
        }
    }
}
