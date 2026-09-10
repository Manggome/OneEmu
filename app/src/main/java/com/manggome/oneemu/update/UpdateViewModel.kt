package com.manggome.oneemu.update

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.data.Settings
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Drives the update flow: check → (dialog) → download → install. One instance per screen that
 * shows [UpdateDialog]; state is not shared across screens on purpose (each is short-lived).
 */
class UpdateViewModel : ViewModel() {
    sealed interface UiState {
        data object Idle : UiState
        data object Checking : UiState
        data object UpToDate : UiState
        data class Available(val info: UpdateInfo) : UiState
        data class Downloading(val info: UpdateInfo, val progress: Float) : UiState
        data class Downloaded(val info: UpdateInfo, val file: File) : UiState
        data class Error(val message: String, val info: UpdateInfo? = null) : UiState
    }

    private val app = OneEmuApp.get()
    private val settings = app.settings
    private val checker = UpdateChecker()
    private val downloader = UpdateDownloader(app.dirs)

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Shown when the user pressed 설치 but the app may not install packages yet. */
    private val _needsInstallPermission = MutableStateFlow(false)
    val needsInstallPermission: StateFlow<Boolean> = _needsInstallPermission.asStateFlow()

    private var downloadJob: Job? = null

    /**
     * Startup check: only when 시작할 때 업데이트 확인 is on and the last check is older than 6 hours.
     * Failures are logged, never shown. Skipped versions stay silent.
     */
    fun checkAutomatically() {
        if (_state.value != UiState.Idle) return
        viewModelScope.launch {
            if (!settings.get(Settings.Keys.updateCheckOnStart, true)) return@launch
            val last = settings.get(Settings.Keys.updateLastCheckAt, 0L)
            val now = System.currentTimeMillis()
            if (now - last < CHECK_INTERVAL_MS) return@launch
            settings.set(Settings.Keys.updateLastCheckAt, now)
            when (val r = checker.check()) {
                is UpdateChecker.Result.Available -> {
                    val skipped = settings.get(Settings.Keys.updateSkippedVersion, "")
                    if (r.info.version != skipped) _state.value = UiState.Available(r.info)
                    else Log.i(TAG, "update ${r.info.version} skipped by user")
                }
                UpdateChecker.Result.UpToDate -> Log.i(TAG, "up to date")
                is UpdateChecker.Result.Failed -> Log.i(TAG, "auto check failed: ${r.message}")
            }
        }
    }

    /** Manual check from the About screen: every outcome is surfaced. */
    fun checkNow() {
        if (_state.value is UiState.Checking || _state.value is UiState.Downloading) return
        _state.value = UiState.Checking
        viewModelScope.launch {
            settings.set(Settings.Keys.updateLastCheckAt, System.currentTimeMillis())
            _state.value = when (val r = checker.check()) {
                is UpdateChecker.Result.Available -> UiState.Available(r.info)
                UpdateChecker.Result.UpToDate -> UiState.UpToDate
                is UpdateChecker.Result.Failed -> UiState.Error(r.message)
            }
        }
    }

    fun download() {
        val info = when (val s = _state.value) {
            is UiState.Available -> s.info
            is UiState.Error -> s.info ?: return
            else -> return
        }
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            _state.value = UiState.Downloading(info, 0f)
            val progressJob = launch {
                downloader.progress.collect { p ->
                    val cur = _state.value
                    if (cur is UiState.Downloading) _state.value = cur.copy(progress = p)
                }
            }
            try {
                val file = downloader.download(info)
                _state.value = UiState.Downloaded(info, file)
            } catch (e: Exception) {
                Log.w(TAG, "download failed", e)
                _state.value = UiState.Error(e.message ?: e.javaClass.simpleName, info)
            } finally {
                progressJob.cancel()
            }
        }
    }

    fun install(context: Context) {
        val s = _state.value as? UiState.Downloaded ?: return
        if (!UpdateInstaller.install(context, s.file)) _needsInstallPermission.value = true
    }

    fun openInstallPermission(context: Context) {
        _needsInstallPermission.value = false
        UpdateInstaller.openUnknownSourcesSettings(context)
    }

    fun dismissPermissionPrompt() { _needsInstallPermission.value = false }

    /** 나중에: hide the dialog; the next startup check may show it again. */
    fun later() {
        downloadJob?.cancel()
        _state.value = UiState.Idle
    }

    /** 이 버전 건너뛰기: remember the version so the startup check stays quiet about it. */
    fun skip() {
        val version = when (val s = _state.value) {
            is UiState.Available -> s.info.version
            is UiState.Downloading -> s.info.version
            is UiState.Downloaded -> s.info.version
            is UiState.Error -> s.info?.version
            else -> null
        }
        downloadJob?.cancel()
        viewModelScope.launch { if (version != null) settings.set(Settings.Keys.updateSkippedVersion, version) }
        _state.value = UiState.Idle
    }

    /** Clears a transient result (최신 버전입니다 / error without update info). */
    fun acknowledge() {
        if (_state.value is UiState.UpToDate || (_state.value as? UiState.Error)?.info == null) _state.value = UiState.Idle
    }

    companion object {
        private const val TAG = "UpdateViewModel"
        const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000
    }
}
