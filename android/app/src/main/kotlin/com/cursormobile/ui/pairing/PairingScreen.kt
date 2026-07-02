package com.cursormobile.ui.pairing

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.cursormobile.data.net.PairQrPayload
import com.cursormobile.data.net.PairingClient
import com.cursormobile.work.RelayForegroundService
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.Executors
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PairUiState {
    data object Idle : PairUiState
    data class Found(val payload: PairQrPayload) : PairUiState
    data object Pairing : PairUiState
    data class Error(val msg: String) : PairUiState
    data object Success : PairUiState
}

@HiltViewModel
class PairingViewModel @Inject constructor(
    private val pairing: PairingClient,
) : ViewModel() {
    private val _state = MutableStateFlow<PairUiState>(PairUiState.Idle)
    val state: StateFlow<PairUiState> = _state.asStateFlow()

    fun scanned(text: String) {
        val payload = pairing.parseQr(text) ?: run {
            _state.value = PairUiState.Error("Unrecognized QR code")
            return
        }
        _state.value = PairUiState.Found(payload)
    }

    suspend fun confirm(payload: PairQrPayload) {
        _state.value = PairUiState.Pairing
        try {
            pairing.pair(payload)
            _state.value = PairUiState.Success
        } catch (t: Throwable) {
            _state.value = PairUiState.Error(t.message ?: "Pair failed")
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PairingScreen(initialQr: String?, onPaired: () -> Unit) {
    val vm: PairingViewModel = hiltViewModel()
    val state by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val cam = rememberPermissionState(Manifest.permission.CAMERA)
    var manual by remember { mutableStateOf("") }

    LaunchedEffect(initialQr) {
        if (initialQr != null) vm.scanned(initialQr)
    }

    LaunchedEffect(state) {
        if (state is PairUiState.Success) {
            ctx.startForegroundService(Intent(ctx, RelayForegroundService::class.java))
            onPaired()
        }
    }

    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Pair with your Mac", style = MaterialTheme.typography.headlineLarge)
        Text(
            "On your Mac, run `cursor-mobile-daemon pair`. Scan the QR it prints, or paste the code below.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (cam.status.isGranted) {
                QrCamera(onText = { vm.scanned(it) })
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Camera permission needed", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { cam.launchPermissionRequest() }) { Text("Grant") }
                }
            }
        }

        when (val s = state) {
            is PairUiState.Found -> ConfirmCard(s.payload) { scope.launch { vm.confirm(s.payload) } }
            is PairUiState.Pairing -> Text("Pairing…", style = MaterialTheme.typography.titleMedium)
            is PairUiState.Error -> Text(s.msg, color = MaterialTheme.colorScheme.error)
            else -> Unit
        }

        OutlinedTextField(
            value = manual,
            onValueChange = { manual = it },
            label = { Text("Paste cm1:// link") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row.HorizontalEnd { TextButton(onClick = { if (manual.isNotBlank()) vm.scanned(manual.trim()) }) { Text("Use link") } }
    }
}

@Composable
private fun ConfirmCard(p: PairQrPayload, onConfirm: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp)) {
            Text(p.label, style = MaterialTheme.typography.titleMedium)
            Text("Fingerprint: ${p.fp}", style = MaterialTheme.typography.bodySmall)
            Text("Relay: ${p.relay}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
                Text("Confirm & pair")
            }
        }
    }
}

@Composable
private fun QrCamera(onText: (String) -> Unit) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { c ->
            val view = PreviewView(c)
            val providerFuture = ProcessCameraProvider.getInstance(c)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                val analyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build().also { ia -> ia.setAnalyzer(Executors.newSingleThreadExecutor(), QrAnalyzer(onText)) }
                provider.unbindAll()
                provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analyzer)
            }, ContextCompat.getMainExecutor(c))
            view
        },
    )
}

private class QrAnalyzer(private val onText: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val scanner = BarcodeScanning.getClient()

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) { imageProxy.close(); return }
        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { codes ->
                for (b in codes) {
                    val raw = b.rawValue ?: continue
                    if (raw.startsWith("cm1://pair")) {
                        onText(raw)
                        break
                    }
                }
            }
            .addOnCompleteListener { imageProxy.close() }
    }
}

// Tiny horizontal end alignment helper.
private object Row {
    @Composable
    fun HorizontalEnd(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { content() }
    }
}
