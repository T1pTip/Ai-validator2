package com.aiorigin.detector

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.util.Locale

data class ScanUiState(
    val busy: Boolean = false,
    val result: ScanResult? = null,
    val notice: String? = null,
    /** The scanned media, kept so the user can ask for a cloud check afterwards. */
    val media: Uri? = null,
    val cloud: CloudState = CloudState.Idle,
    val prompt: PromptState = PromptState.Idle,
    val gemini: GeminiState = GeminiState.Idle
)

/** Local prompt reconstruction (Florence-2 description -> prompt). */
sealed class PromptState {
    data object Idle : PromptState()
    /** The description model is not on the phone yet; it is downloaded only when the user asks. */
    data object NeedsModel : PromptState()
    data class Downloading(val done: Long, val total: Long) : PromptState()
    data object Generating : PromptState()
    data class Ready(val caption: String, val width: Int, val height: Int, val millis: Long) : PromptState()
    data class Failed(val message: String) : PromptState()
}

sealed class GeminiState {
    data object Idle : GeminiState()
    data object Running : GeminiState()
    data class Done(val result: GeminiClient.Result) : GeminiState()
    data class Failed(val message: String, val keysProblem: Boolean = false) : GeminiState()
}

sealed class CloudState {
    data object Idle : CloudState()
    data object Running : CloudState()
    data class Done(val outcome: CloudCheck.Outcome) : CloudState()
    data class Failed(val message: String, val keysProblem: Boolean = false) : CloudState()
}

/** Survives rotation and configuration changes, so a running scan and its result are kept. */
class ScanViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = _state.asStateFlow()
    private var scanJob: Job? = null
    private var noticeJob: Job? = null
    private var generation = 0

    private class UserFacingException(message: String) : Exception(message)

    private var cloudJob: Job? = null
    private val secrets by lazy { SecretStore(getApplication()) }

    fun scan(uri: Uri) = launchScan(uri) {
        // A local scan replaces any earlier downloaded copy.
        withContext(Dispatchers.IO) { downloadsDir().listFiles()?.forEach { it.delete() } }
        Scanner.scan(getApplication(), uri)
    }

    private fun downloadsDir() = File(getApplication<Application>().cacheDir, "downloads")

    /** Downloads the linked media (only on user request) and scans the downloaded copy. */
    fun scanUrl(input: String) {
        val app = getApplication<Application>()
        val url = UrlFetcher.normalize(input)
        if (url == null) {
            _state.update { it.copy(notice = app.getString(R.string.url_invalid)) }
            return
        }
        val dir = downloadsDir()
        val target = File(dir, "media-${System.currentTimeMillis()}")
        launchScan(Uri.fromFile(target)) {
            var keep = false
            try {
                scanDownload(app, url, dir, target).also { keep = true }
            } finally {
                // Partial, oversized or failed downloads are removed at once. A successfully scanned
                // copy stays in the app cache only until the next scan, for an optional cloud check.
                if (!keep) withContext(NonCancellable + Dispatchers.IO) { target.delete() }
            }
        }
    }

    private suspend fun scanDownload(app: Application, url: String, dir: File, target: File): ScanResult {
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }
        }
        val fetched = try {
            runInterruptible(Dispatchers.IO) { UrlFetcher.fetch(url, target) }
        } catch (e: UrlFetcher.FetchException) {
            throw UserFacingException(
                when (e.reason) {
                    UrlFetcher.Reason.INVALID -> app.getString(R.string.url_invalid)
                    UrlFetcher.Reason.NOT_MEDIA -> app.getString(R.string.url_not_media)
                    UrlFetcher.Reason.TOO_BIG -> app.getString(R.string.url_too_big)
                    UrlFetcher.Reason.HTTP, UrlFetcher.Reason.NETWORK -> app.getString(R.string.url_failed, e.message ?: "")
                }
            )
        }
        val host = try { URL(fetched.finalUrl).host } catch (_: Exception) { fetched.finalUrl.take(60) }
        val sizeMb = String.format(Locale.US, "%.2f MB", fetched.bytes / 1_048_576.0)
        val source = mutableListOf(app.getString(R.string.e_url_source, host, fetched.contentType.ifEmpty { "?" }, sizeMb))
        if (fetched.pageUrl != null) source.add(app.getString(R.string.e_url_page))
        return Scanner.scan(app, Uri.fromFile(fetched.file), source, fetched.contentType.substringBefore(';').trim())
    }

    private fun launchScan(media: Uri, block: suspend () -> ScanResult) {
        val app = getApplication<Application>()
        generation += 1
        val current = generation
        scanJob?.cancel()
        noticeJob?.cancel()
        cloudJob?.cancel()
        promptJob?.cancel()
        geminiJob?.cancel()
        _state.value = ScanUiState(busy = true)
        scanJob = viewModelScope.launch {
            try {
                val result = block()
                if (current != generation) return@launch
                _state.value = ScanUiState(result = result, notice = result.status, media = media)
                // No prompt in the file: describe the image locally if the model is already here.
                if (result.prompt == null) {
                    if (ModelDownloader.isReady(app)) startPrompt(download = false)
                    else _state.update { it.copy(prompt = PromptState.NeedsModel) }
                }
                noticeJob = viewModelScope.launch {
                    delay(10_000)
                    if (current == generation) _state.update { it.copy(notice = null) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: UserFacingException) {
                if (current == generation) _state.value = ScanUiState(notice = e.message)
            } catch (e: Exception) {
                if (current == generation) {
                    _state.value = ScanUiState(notice = app.getString(R.string.scan_failed, e.javaClass.simpleName))
                }
            }
        }
    }

    fun cancel() {
        generation += 1
        scanJob?.cancel()
        noticeJob?.cancel()
        _state.value = ScanUiState(notice = getApplication<Application>().getString(R.string.scan_cancelled))
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    fun hasCloudKeys(): Boolean = secrets.load() != null

    fun cloudKeyHint(): String? = secrets.load()?.user?.takeLast(4)

    fun saveCloudKeys(user: String, secret: String) {
        secrets.save(SightengineClient.Credentials(user, secret))
        _state.update { it.copy(notice = getApplication<Application>().getString(R.string.settings_saved)) }
    }

    fun clearCloudKeys() {
        secrets.clear()
        _state.update { it.copy(notice = getApplication<Application>().getString(R.string.settings_cleared)) }
    }

    /** Called only after the user confirmed the upload in the consent dialog. */
    fun runCloudCheck(client: SightengineClient = SightengineClient()) {
        val app = getApplication<Application>()
        val snapshot = _state.value
        val media = snapshot.media ?: return
        val result = snapshot.result ?: return
        val credentials = secrets.load()
        if (credentials == null) {
            _state.update { it.copy(cloud = CloudState.Failed(app.getString(R.string.cloud_no_keys), keysProblem = true)) }
            return
        }
        val current = generation
        cloudJob?.cancel()
        _state.update { it.copy(cloud = CloudState.Running) }
        cloudJob = viewModelScope.launch {
            val outcome: CloudState = try {
                val payloads = try {
                    runInterruptible(Dispatchers.IO) {
                        if (result.isVideo) CloudCheck.prepareVideoFrames(app, media)
                        else listOf(CloudCheck.prepareImage(app, media))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw IllegalArgumentException(app.getString(R.string.cloud_err_prepare, e.javaClass.simpleName))
                }
                val verdicts = runInterruptible(Dispatchers.IO) {
                    payloads.mapIndexed { i, bytes -> client.checkImage(credentials, bytes, "media-$i.jpg") }
                }
                CloudState.Done(CloudCheck.aggregate(verdicts))
            } catch (e: CancellationException) {
                throw e
            } catch (e: SightengineClient.ApiException) {
                CloudState.Failed(
                    when (e.kind) {
                        SightengineClient.Kind.AUTH -> return@launch finishCloud(current,
                            CloudState.Failed(app.getString(R.string.cloud_err_auth), keysProblem = true))
                        SightengineClient.Kind.QUOTA -> app.getString(R.string.cloud_err_quota)
                        SightengineClient.Kind.BAD_MEDIA -> app.getString(R.string.cloud_err_media, e.message ?: "")
                        SightengineClient.Kind.SERVER -> app.getString(R.string.cloud_err_server, e.message ?: "")
                        SightengineClient.Kind.NETWORK -> app.getString(R.string.cloud_err_network, e.message ?: "")
                    }
                )
            } catch (e: IllegalArgumentException) {
                CloudState.Failed(e.message ?: "")
            } catch (e: Exception) {
                CloudState.Failed(app.getString(R.string.cloud_err_server, e.javaClass.simpleName))
            }
            finishCloud(current, outcome)
        }
    }

    private var promptJob: Job? = null
    private var geminiJob: Job? = null

    /** Local description -> prompt. [download] is true when the user asked to fetch the model. */
    fun startPrompt(download: Boolean) {
        val app = getApplication<Application>()
        val snapshot = _state.value
        val media = snapshot.media ?: return
        val result = snapshot.result ?: return
        val current = generation
        promptJob?.cancel()
        promptJob = viewModelScope.launch {
            val outcome: PromptState = try {
                if (!ModelDownloader.isReady(app)) {
                    if (!download) return@launch
                    _state.update { it.copy(prompt = PromptState.Downloading(0, ModelDownloader.TOTAL_BYTES)) }
                    var lastUpdate = 0L
                    runInterruptible(Dispatchers.IO) {
                        ModelDownloader.download(app) { done, total ->
                            val now = System.currentTimeMillis()
                            if (now - lastUpdate > 300 || done == total) {
                                lastUpdate = now
                                if (current == generation) _state.update { it.copy(prompt = PromptState.Downloading(done, total)) }
                            }
                        }
                    }
                }
                _state.update { it.copy(prompt = PromptState.Generating) }
                runInterruptible(Dispatchers.Default) {
                    val bitmap = (if (result.isVideo) CloudCheck.middleFrame(app, media) else LocalDetector.decode(app, media))
                        ?: throw IllegalArgumentException("undecodable")
                    try {
                        val caption = FlorenceCaptioner.caption(app, LocalDetector.pixels(bitmap), bitmap.width, bitmap.height)
                        PromptState.Ready(caption.text, bitmap.width, bitmap.height, caption.millis)
                    } finally { bitmap.recycle() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                PromptState.Failed(app.getString(R.string.prompt_failed, e.message ?: e.javaClass.simpleName))
            }
            if (current == generation) _state.update { it.copy(prompt = outcome) }
        }
    }

    fun hasGeminiKey(): Boolean = secrets.loadGemini() != null

    fun saveGeminiKey(key: String) {
        secrets.saveGemini(key)
        _state.update { it.copy(notice = getApplication<Application>().getString(R.string.settings_saved)) }
    }

    /** Called only after the user confirmed sending the image to Gemini. */
    fun runGemini(target: PromptTarget, client: GeminiClient = GeminiClient()) {
        val app = getApplication<Application>()
        val snapshot = _state.value
        val media = snapshot.media ?: return
        val result = snapshot.result ?: return
        val key = secrets.loadGemini()
        if (key == null) {
            _state.update { it.copy(gemini = GeminiState.Failed(app.getString(R.string.gemini_no_key), keysProblem = true)) }
            return
        }
        val caption = (snapshot.prompt as? PromptState.Ready)?.caption
        val generator = (snapshot.cloud as? CloudState.Done)?.outcome?.topGenerator?.takeIf { it.second >= 0.3f }?.first
            ?: result.generatorHints.firstOrNull()
        val current = generation
        geminiJob?.cancel()
        _state.update { it.copy(gemini = GeminiState.Running) }
        geminiJob = viewModelScope.launch {
            val outcome: GeminiState = try {
                val jpeg = runInterruptible(Dispatchers.IO) { CloudCheck.jpegForGemini(app, media, result.isVideo) }
                GeminiState.Done(runInterruptible(Dispatchers.IO) { client.reconstruct(key, jpeg, caption, generator, target) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: GeminiClient.ApiException) {
                when (e.kind) {
                    GeminiClient.Kind.AUTH -> GeminiState.Failed(app.getString(R.string.gemini_err_auth), keysProblem = true)
                    GeminiClient.Kind.QUOTA -> GeminiState.Failed(app.getString(R.string.gemini_err_quota))
                    GeminiClient.Kind.NETWORK -> GeminiState.Failed(app.getString(R.string.cloud_err_network, e.message ?: ""))
                    else -> GeminiState.Failed(app.getString(R.string.gemini_err, e.message ?: ""))
                }
            } catch (e: Exception) {
                GeminiState.Failed(app.getString(R.string.gemini_err, e.javaClass.simpleName))
            }
            if (current == generation) _state.update { it.copy(gemini = outcome) }
        }
    }

    private fun finishCloud(current: Int, outcome: CloudState) {
        if (current == generation) _state.update { it.copy(cloud = outcome) }
    }
}
