package com.aiorigin.detector

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialUri = if (intent?.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        } else null
        setContent {
            MaterialTheme {
                var result by remember { mutableStateOf<ScanResult?>(null) }
                var busy by remember { mutableStateOf(false) }
                var notice by remember { mutableStateOf<String?>(null) }
                val scope = rememberCoroutineScope()
                var scanJob by remember { mutableStateOf<Job?>(null) }
                var noticeJob by remember { mutableStateOf<Job?>(null) }
                var scanGeneration by remember { mutableIntStateOf(0) }
                val profile = remember { DeviceProfiler.inspect(this@MainActivity) }
                fun process(uri: Uri) {
                    scanGeneration += 1
                    val generation = scanGeneration
                    scanJob?.cancel()
                    noticeJob?.cancel()
                    notice = null
                    result = null
                    scanJob = scope.launch {
                        busy = true
                        try {
                            val scanned = withContext(Dispatchers.IO) { Scanner.scan(this@MainActivity, uri) }
                            if (generation != scanGeneration) return@launch
                            result = scanned
                            notice = scanned.status
                            noticeJob = scope.launch {
                                delay(10_000)
                                if (generation == scanGeneration) notice = null
                            }
                        } catch (_: CancellationException) {
                            // Superseded scan: do not show stale results.
                        } catch (e: Exception) {
                            if (generation == scanGeneration) notice = "Scan failed: ${e.javaClass.simpleName}"
                        } finally {
                            if (generation == scanGeneration) busy = false
                        }
                    }
                }
                val picker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri -> if (uri != null) process(uri) }
                LaunchedEffect(initialUri) { if (initialUri != null) process(initialUri) }
                Scaffold { pad ->
                    Column(
                        Modifier.padding(pad).padding(20.dp).fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("AI Origin Detector", style = MaterialTheme.typography.headlineMedium)
                        Text("Local metadata prototype • No paid APIs • No uploads")
                        Text("Device: ${profile.device}")
                        Text("Physical RAM: ${String.format(Locale.US, "%.1f", profile.physicalRamGb)} GB; available: ${String.format(Locale.US, "%.1f", profile.availableRamGb)} GB")
                        Text("CPU cores: ${profile.cpuCores}; thermal: ${profile.thermalStatus}")
                        Text("Recommended processing mode: ${profile.recommendedMode}")
                        Button(onClick = { picker.launch(arrayOf("image/*", "video/*")) }) {
                            Text("Choose image or video")
                        }
                        if (busy) CircularProgressIndicator()
                        if (notice != null) {
                            Surface(color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = MaterialTheme.shapes.medium) {
                                Text(notice!!, Modifier.padding(16.dp))
                            }
                        }
                        result?.let { r ->
                            Text(r.status, style = MaterialTheme.typography.titleLarge)
                            Text("Evidence", style = MaterialTheme.typography.titleMedium)
                            r.evidence.forEach { Text("• $it") }
                            Text("Prompt source: ${r.source}")
                            if (r.prompt != null) {
                                Text("Positive prompt / extracted text", style = MaterialTheme.typography.titleMedium)
                                Text(r.prompt)
                                Button(onClick = {
                                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("Prompt", r.prompt))
                                }) { Text("Copy extracted text") }
                            }
                            if (r.negativePrompt != null) {
                                Text("Negative prompt", style = MaterialTheme.typography.titleMedium)
                                Text(r.negativePrompt)
                                Button(onClick = {
                                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("Negative prompt", r.negativePrompt))
                                }) { Text("Copy negative prompt") }
                            }
                            Button(onClick = {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, ScanReport.format(r))
                                }
                                startActivity(Intent.createChooser(send, "Share scan report"))
                            }) { Text("Share text report") }
                            HorizontalDivider()
                            Text(r.caveat, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}
