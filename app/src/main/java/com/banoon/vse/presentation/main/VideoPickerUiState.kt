package com.banoon.vse.presentation.main

import com.banoon.vse.domain.model.MediaInfo
import com.banoon.vse.domain.model.PickedVideoFile
import java.util.UUID

/** صف واحد بمحرر المدى الزمني: مربعي نص (ثانية البداية/النهاية). */
data class TimeRangeInput(
    val id: String = UUID.randomUUID().toString(),
    val startSecondsText: String = "",
    val endSecondsText: String = ""
)

enum class OperationMode { REMOVE, EXTRACT_MERGED, EXTRACT_SEPARATE }

data class VideoPickerUiState(
    val isLoading: Boolean = false,
    val pickedVideo: PickedVideoFile? = null,
    val mediaInfo: MediaInfo? = null,
    val errorMessage: String? = null,
    val ranges: List<TimeRangeInput> = listOf(TimeRangeInput()),
    val operationMode: OperationMode = OperationMode.REMOVE,
    val isProcessing: Boolean = false,
    val processingProgress: Float = 0f,
    val resultPaths: List<String>? = null,
    val isExporting: Boolean = false,
    val exportedUris: List<String>? = null,
    val needsLegacyPermission: Boolean = false
)
