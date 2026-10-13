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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.testTag
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
    val state by viewModel.state.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.scan(uri)
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
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
            state.result?.let { ResultView(it, state, viewModel) }
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
private fun ResultView(r: ScanResult, state: ScanUiState, viewModel: ScanViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val visualSignal = r.visualHeadline && r.visual != null &&
        VisualBand.of(r.visual.probability) in setOf(VisualBand.LIKELY_AI, VisualBand.LEANS_AI)
    val signal = visualSignal || (!r.visualHeadline &&
        r.level in setOf(OriginLevel.AI_DECLARED, OriginLevel.AI_EDITED, OriginLevel.AI_TOOL, OriginLevel.PROMPT))
    Surface(
        color = if (signal) SignalContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.large
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(r.status, Modifier.testTag("verdict"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                color = if (signal) OnSignal else MaterialTheme.colorScheme.onSurface)
            Text(r.explanation, style = MaterialTheme.typography.bodyMedium)
        }
    }
    r.visual?.let { v ->
        Section(stringResource(R.string.s_visual)) {
            Text(stringResource(R.string.visual_value, Math.round(v.probability * 100)), Modifier.testTag("visual"),
                style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            if (v.frames > 1) {
                Text(stringResource(R.string.visual_frames, v.frames,
                    v.frameProbabilities.joinToString(" · ") { "${Math.round(it * 100)}%" }),
                    style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.visual_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    CloudSection(r, state, viewModel)
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

@Composable
private fun CloudSection(r: ScanResult, state: ScanUiState, viewModel: ScanViewModel) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    var needKeys by rememberSaveable { mutableStateOf(false) }
    Section(stringResource(R.string.s_cloud)) {
        when (val cloud = state.cloud) {
            CloudState.Running -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.cloud_running))
            }
            is CloudState.Done -> {
                val o = cloud.outcome
                val percent = Math.round(o.probability * 100)
                Text(
                    if (o.frames > 1) stringResource(R.string.cloud_result_video, percent, o.frames)
                    else stringResource(R.string.cloud_result, percent),
                    Modifier.testTag("cloud_result"), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold
                )
                o.topGenerator?.takeIf { it.second >= 0.2f }?.let { (name, score) ->
                    Text(stringResource(R.string.cloud_generator, name, Math.round(score * 100)), style = MaterialTheme.typography.bodyMedium)
                }
                o.operations?.let { Text(stringResource(R.string.cloud_operations, it), style = MaterialTheme.typography.bodySmall) }
                Text(stringResource(R.string.cloud_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is CloudState.Failed -> {
                Text(cloud.message, Modifier.testTag("cloud_error"), color = MaterialTheme.colorScheme.error)
                if (cloud.keysProblem) {
                    TextButton(onClick = { needKeys = true }) { Text(stringResource(R.string.cloud_fix_keys)) }
                }
            }
            CloudState.Idle -> {}
        }
        if (state.cloud != CloudState.Running && state.media != null) {
            OutlinedButton(
                onClick = { if (viewModel.hasCloudKeys()) confirm = true else needKeys = true },
                modifier = Modifier.fillMaxWidth().testTag("cloud_button")
            ) { Text(stringResource(R.string.cloud_button)) }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.cloud_consent_title)) },
            text = {
                Text(if (r.isVideo) stringResource(R.string.cloud_consent_video, CloudCheck.VIDEO_FRAMES)
                    else stringResource(R.string.cloud_consent_image))
            },
            confirmButton = {
                TextButton(onClick = { confirm = false; viewModel.runCloudCheck() }, modifier = Modifier.testTag("cloud_confirm")) {
                    Text(stringResource(R.string.cloud_send))
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel_dialog)) } }
        )
    }
    if (needKeys) SettingsDialog(viewModel, message = stringResource(R.string.cloud_no_keys), onSaved = { confirm = true }) { needKeys = false }
}

@Composable
private fun SettingsDialog(viewModel: ScanViewModel, message: String? = null, onSaved: () -> Unit = {}, onClose: () -> Unit) {
    var user by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    val hint = remember { viewModel.cloudKeyHint() }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.settings_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(stringResource(R.string.settings_body), style = MaterialTheme.typography.bodySmall)
                hint?.let { Text(stringResource(R.string.settings_has_keys, it), style = MaterialTheme.typography.bodySmall) }
                OutlinedTextField(user, { user = it }, singleLine = true, label = { Text(stringResource(R.string.settings_user)) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
                    modifier = Modifier.testTag("key_user"))
                OutlinedTextField(secret, { secret = it }, singleLine = true, label = { Text(stringResource(R.string.settings_secret)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
                    modifier = Modifier.testTag("key_secret"))
                if (hint != null) TextButton(onClick = { viewModel.clearCloudKeys(); onClose() }) { Text(stringResource(R.string.settings_clear)) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { viewModel.saveCloudKeys(user, secret); onClose(); onSaved() },
                enabled = user.isNotBlank() && secret.isNotBlank(),
                modifier = Modifier.testTag("key_save")
            ) { Text(stringResource(R.string.settings_save)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.cancel_dialog)) } }
    )
}
