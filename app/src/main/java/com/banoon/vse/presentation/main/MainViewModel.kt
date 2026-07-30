package com.banoon.vse.presentation.main

import android.content.Context
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.banoon.vse.domain.model.TimeRange
import com.banoon.vse.domain.port.LegacyStoragePermissionRequiredException
import com.banoon.vse.domain.port.MediaExportPort
import com.banoon.vse.domain.port.MediaProbePort
import com.banoon.vse.domain.port.VideoFileResolverPort
import com.banoon.vse.domain.usecase.ExtractSegmentsUseCase
import com.banoon.vse.domain.usecase.RemoveSegmentsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val videoFileResolverPort: VideoFileResolverPort,
    private val mediaProbePort: MediaProbePort,
    private val removeSegmentsUseCase: RemoveSegmentsUseCase,
    private val extractSegmentsUseCase: ExtractSegmentsUseCase,
    private val mediaExportPort: MediaExportPort,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(VideoPickerUiState())
    val uiState: StateFlow<VideoPickerUiState> = _uiState.asStateFlow()

    fun onVideoUriPicked(uriString: String) {
        _uiState.update {
            it.copy(
                isLoading = true,
                errorMessage = null,
                mediaInfo = null,
                resultPaths = null,
                ranges = listOf(TimeRangeInput())
            )
        }

        viewModelScope.launch {
            val resolved = videoFileResolverPort.resolve(uriString).getOrElse { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "تعذر فتح الملف: ${error.message ?: "خطأ غير معروف"}"
                    )
                }
                return@launch
            }

            _uiState.update { it.copy(pickedVideo = resolved) }

            val info = mediaProbePort.analyze(resolved.ffmpegInputRef).getOrElse { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "تعذر تحليل الفيديو: ${error.message ?: "خطأ غير معروف"}"
                    )
                }
                return@launch
            }

            _uiState.update { it.copy(isLoading = false, mediaInfo = info) }
        }
    }

    fun setOperationMode(mode: OperationMode) {
        _uiState.update { it.copy(operationMode = mode) }
    }

    fun addRange() {
        _uiState.update { it.copy(ranges = it.ranges + TimeRangeInput()) }
    }

    fun removeRange(id: String) {
        _uiState.update { state ->
            val updated = state.ranges.filterNot { it.id == id }
            state.copy(ranges = updated.ifEmpty { listOf(TimeRangeInput()) })
        }
    }

    fun updateRangeStart(id: String, value: String) {
        _uiState.update { state ->
            state.copy(ranges = state.ranges.map {
                if (it.id == id) it.copy(startSecondsText = value) else it
            })
        }
    }

    fun updateRangeEnd(id: String, value: String) {
        _uiState.update { state ->
            state.copy(ranges = state.ranges.map {
                if (it.id == id) it.copy(endSecondsText = value) else it
            })
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun startProcessing() {
        val state = _uiState.value
        val video = state.pickedVideo
        if (video == null) {
            _uiState.update { it.copy(errorMessage = "لم يتم اختيار فيديو بعد") }
            return
        }

        val parsedRanges = mutableListOf<TimeRange>()
        for (input in state.ranges) {
            val start = input.startSecondsText.toDoubleOrNull()
            val end = input.endSecondsText.toDoubleOrNull()
            if (start == null || end == null) {
                _uiState.update { it.copy(errorMessage = "تأكد من إدخال أرقام صحيحة لكل مدى زمني") }
                return
            }
            if (end <= start) {
                _uiState.update { it.copy(errorMessage = "نهاية المدى الزمني يجب أن تكون أكبر من بدايته") }
                return
            }
            parsedRanges.add(TimeRange.ofSeconds(start, end))
        }

        if (parsedRanges.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "أضف مدى زمني واحد على الأقل") }
            return
        }

        _uiState.update {
            it.copy(isProcessing = true, processingProgress = 0f, errorMessage = null, resultPaths = null)
        }

        viewModelScope.launch {
            val outputDir = resolveOutputDirectory()

            val result = when (state.operationMode) {
                OperationMode.REMOVE -> removeSegmentsUseCase(
                    inputFilePath = video.ffmpegInputRef,
                    outputDirectory = outputDir,
                    rangesToRemove = parsedRanges,
                    onProgress = { progress ->
                        _uiState.update { it.copy(processingProgress = progress) }
                    }
                )
                OperationMode.EXTRACT_MERGED -> extractSegmentsUseCase(
                    inputFilePath = video.ffmpegInputRef,
                    outputDirectory = outputDir,
                    rangesToExtract = parsedRanges,
                    mergeOutput = true,
                    onProgress = { progress ->
                        _uiState.update { it.copy(processingProgress = progress) }
                    }
                )
                OperationMode.EXTRACT_SEPARATE -> extractSegmentsUseCase(
                    inputFilePath = video.ffmpegInputRef,
                    outputDirectory = outputDir,
                    rangesToExtract = parsedRanges,
                    mergeOutput = false,
                    onProgress = { progress ->
                        _uiState.update { it.copy(processingProgress = progress) }
                    }
                )
            }

            result.fold(
                onSuccess = { processingResult ->
                    _uiState.update {
                        it.copy(isProcessing = false, resultPaths = processingResult.outputFilePaths)
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isProcessing = false,
                            errorMessage = "فشلت المعالجة: ${error.message ?: "خطأ غير معروف"}"
                        )
                    }
                }
            )
        }
    }

    fun exportToGallery() {
        val paths = _uiState.value.resultPaths ?: return
        _uiState.update { it.copy(isExporting = true, needsLegacyPermission = false, errorMessage = null) }

        viewModelScope.launch {
            mediaExportPort.exportToGallery(paths).fold(
                onSuccess = { uris ->
                    _uiState.update { it.copy(isExporting = false, exportedUris = uris) }
                },
                onFailure = { error ->
                    if (error is LegacyStoragePermissionRequiredException) {
                        _uiState.update { it.copy(isExporting = false, needsLegacyPermission = true) }
                    } else {
                        _uiState.update {
                            it.copy(
                                isExporting = false,
                                errorMessage = "فشل الحفظ بالمعرض: ${error.message ?: "خطأ غير معروف"}"
                            )
                        }
                    }
                }
            )
        }
    }

    private fun resolveOutputDirectory(): String {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: File(context.filesDir, "output")
        dir.mkdirs()
        return dir.absolutePath
    }
}
