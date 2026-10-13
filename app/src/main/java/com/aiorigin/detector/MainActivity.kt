package com.aiorigin.detector

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.util.Locale

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF0B1A33),
    primaryContainer = Color(0xFF1E3A5F),
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = Color(0xFFB0BEC5),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF161B22),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF21262D),
    onSurfaceVariant = Color(0xFFB7C0CA),
    outline = Color(0xFF30363D),
    error = Color(0xFFFFB4A9)
)
private val SignalContainer = Color(0xFF3A2E12)
private val OnSignal = Color(0xFFF2C46B)

class MainActivity : ComponentActivity() {
    private val viewModel: ScanViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        // Only the first creation handles a share intent; rotation must not rescan.
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            MaterialTheme(colorScheme = DarkColors) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    AppScreen(viewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** A shared image/video is scanned directly; a shared link (e.g. from a browser) is downloaded first. */
    private fun handleShare(intent: Intent?) {
        sharedUri(intent)?.let { viewModel.scan(it); return }
        if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { viewModel.scanUrl(it) }
        }
    }

    private fun sharedUri(intent: Intent?): Uri? {
        if (intent?.action != Intent.ACTION_SEND) return null
        if (Build.VERSION.SDK_INT >= 33) return intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        @Suppress("DEPRECATION")
        return intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
    }
}

@Composable
private fun AppScreen(viewModel: ScanViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by viewModel.state.collectAsState()
    val profile = remember { DeviceProfiler.inspect(context) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.scan(uri)
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(stringResource(R.string.app_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            DeviceCard(profile)
            Button(
                onClick = { picker.launch(arrayOf("image/*", "video/*")) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
            ) { Text(stringResource(R.string.choose), style = MaterialTheme.typography.titleMedium) }
            UrlInput(enabled = !state.busy, onSubmit = viewModel::scanUrl)
            if (state.busy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    Text(stringResource(R.string.scanning), Modifier.weight(1f))
                    OutlinedButton(onClick = viewModel::cancel) { Text(stringResource(R.string.cancel)) }
                }
            }
            state.notice?.let { notice ->
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium,
                    onClick = viewModel::dismissNotice) {
                    Text(notice, Modifier.fillMaxWidth().padding(14.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            state.result?.let { ResultView(it) }
        }
    }
}

@Composable
private fun UrlInput(enabled: Boolean, onSubmit: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            singleLine = true,
            enabled = enabled,
            label = { Text(stringResource(R.string.url_label)) },
            placeholder = { Text("https://…") },
            textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { focus.clearFocus(); onSubmit(text) })
        )
        FilledTonalButton(
            onClick = { focus.clearFocus(); onSubmit(text) },
            enabled = enabled && text.isNotBlank(),
            modifier = Modifier.heightIn(min = 56.dp)
        ) { Text(stringResource(R.string.url_check)) }
    }
}

@Composable
private fun DeviceCard(profile: DeviceProfile) {
    val mode = stringResource(
        when (profile.recommendedMode) {
            "Performance" -> R.string.mode_performance
            "Conservative" -> R.string.mode_conservative
            else -> R.string.mode_balanced
        }
    )
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(
                    R.string.device_line, profile.device,
                    String.format(Locale.US, "%.1f", profile.availableRamGb),
                    String.format(Locale.US, "%.1f", profile.physicalRamGb),
                    profile.cpuCores, profile.thermalStatus
                ),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(stringResource(R.string.mode_line, mode), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ResultView(r: ScanResult) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val signal = r.level in setOf(OriginLevel.AI_DECLARED, OriginLevel.AI_EDITED, OriginLevel.AI_TOOL, OriginLevel.PROMPT)
    Surface(
        color = if (signal) SignalContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.large
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(r.status, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                color = if (signal) OnSignal else MaterialTheme.colorScheme.onSurface)
            Text(r.explanation, style = MaterialTheme.typography.bodyMedium)
        }
    }
    r.prompt?.let { PromptCard(stringResource(R.string.s_prompt), it, stringResource(R.string.copy_prompt), context) }
    r.negativePrompt?.let { PromptCard(stringResource(R.string.s_negative), it, stringResource(R.string.copy_negative), context) }
    Text(stringResource(R.string.s_source, r.source), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (r.settings.isNotEmpty()) {
        Section(stringResource(R.string.s_settings)) {
            r.settings.forEach { (k, v) -> Text("$k: $v", style = MaterialTheme.typography.bodyMedium) }
        }
    }
    r.description?.let { d ->
        Section(stringResource(R.string.s_description)) {
            SelectionContainer { Text(d, style = MaterialTheme.typography.bodyMedium) }
            Text(stringResource(R.string.description_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Section(stringResource(R.string.s_evidence)) {
        r.evidence.forEach { line ->
            Text("• $line", style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (line.contains("SHA-256")) FontFamily.Monospace else FontFamily.Default)
        }
    }
    val labels = ReportLabels(
        title = stringResource(R.string.r_title), status = stringResource(R.string.r_status),
        source = stringResource(R.string.r_source), evidence = stringResource(R.string.s_evidence),
        positive = stringResource(R.string.r_positive), negative = stringResource(R.string.r_negative),
        settings = stringResource(R.string.s_settings), notFound = stringResource(R.string.r_not_found),
        limitations = stringResource(R.string.s_limits)
    )
    val chooser = stringResource(R.string.share_chooser)
    OutlinedButton(onClick = {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, ScanReport.format(r, labels))
        }
        context.startActivity(Intent.createChooser(send, chooser))
    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.share)) }
    Section(stringResource(R.string.s_limits)) {
        Text(r.caveat, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun PromptCard(title: String, text: String, copyLabel: String, context: Context) {
    val copied = stringResource(R.string.copied)
    Section(title) {
        SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium) }
        TextButton(onClick = {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(title, text))
            if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
        }) { Text(copyLabel) }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}
