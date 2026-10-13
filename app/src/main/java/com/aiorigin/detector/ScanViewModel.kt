package com.aiorigin.detector

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

    fun scan(uri: Uri) {
        val app = getApplication<Application>()
        generation += 1
        val current = generation
        scanJob?.cancel()
        noticeJob?.cancel()
        _state.value = ScanUiState(busy = true)
        scanJob = viewModelScope.launch {
            try {
                val result = Scanner.scan(app, uri)
                if (current != generation) return@launch
                _state.value = ScanUiState(result = result, notice = result.status)
                noticeJob = viewModelScope.launch {
                    delay(10_000)
                    if (current == generation) _state.update { it.copy(notice = null) }
                }
            } catch (e: CancellationException) {
                throw e
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
