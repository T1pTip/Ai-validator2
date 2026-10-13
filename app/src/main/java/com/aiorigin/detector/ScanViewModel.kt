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
    val notice: String? = null
)

/** Survives rotation and configuration changes, so a running scan and its result are kept. */
class ScanViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = _state.asStateFlow()
    private var scanJob: Job? = null
    private var noticeJob: Job? = null
    private var generation = 0

    private class UserFacingException(message: String) : Exception(message)

    fun scan(uri: Uri) = launchScan { Scanner.scan(getApplication(), uri) }

    /** Downloads the linked media (only on user request) and scans the downloaded copy. */
    fun scanUrl(input: String) {
        val app = getApplication<Application>()
        val url = UrlFetcher.normalize(input)
        if (url == null) {
            _state.update { it.copy(notice = app.getString(R.string.url_invalid)) }
            return
        }
        launchScan {
            val dir = File(app.cacheDir, "downloads")
            val target = File(dir, "media-${System.currentTimeMillis()}")
            try {
                scanDownload(app, url, dir, target)
            } finally {
                // Never keep downloaded media around; also removes partial or oversized downloads.
                withContext(NonCancellable + Dispatchers.IO) { target.delete() }
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

    private fun launchScan(block: suspend () -> ScanResult) {
        val app = getApplication<Application>()
        generation += 1
        val current = generation
        scanJob?.cancel()
        noticeJob?.cancel()
        _state.value = ScanUiState(busy = true)
        scanJob = viewModelScope.launch {
            try {
                val result = block()
                if (current != generation) return@launch
                _state.value = ScanUiState(result = result, notice = result.status)
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
}
