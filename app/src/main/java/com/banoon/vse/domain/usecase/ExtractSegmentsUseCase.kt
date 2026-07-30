package com.banoon.vse.domain.usecase

import com.banoon.vse.domain.model.ProcessingResult
import com.banoon.vse.domain.model.TimeRange
import com.banoon.vse.domain.model.TimeRangeSet
import com.banoon.vse.domain.port.FfmpegPort
import com.banoon.vse.domain.port.MediaProbePort
import javax.inject.Inject

/**
 * حالة استخدام: استخراج مجموعة من المقاطع الزمنية من فيديو واحد،
 * مع خيار الدمج في ملف واحد أو الإبقاء على ملفات منفصلة.
 */
class ExtractSegmentsUseCase @Inject constructor(
    private val mediaProbePort: MediaProbePort,
    private val ffmpegPort: FfmpegPort,
    private val planner: SmartRenderingPlanner
) {
    suspend operator fun invoke(
        inputFilePath: String,
        outputDirectory: String,
        rangesToExtract: List<TimeRange>,
        mergeOutput: Boolean,
        onProgress: (Float) -> Unit = {}
    ): Result<ProcessingResult> {
        val mediaInfo = mediaProbePort.analyze(inputFilePath).getOrElse {
            return Result.failure(it)
        }

        val rangeSet = TimeRangeSet.create(rangesToExtract).getOrElse {
            return Result.failure(it)
        }

        rangesToExtract.forEach { range ->
            if (range.end > mediaInfo.durationUs) {
                return Result.failure(
                    IllegalArgumentException(
                        "المدى الزمني $range يتجاوز مدة الفيديو الفعلية (${mediaInfo.durationUs})"
                    )
                )
            }
        }

        val plan = planner.buildExtractionPlan(
            inputFilePath, mediaInfo, rangeSet, mergeOutput
        ).getOrElse {
            return Result.failure(it)
        }

        return ffmpegPort.execute(plan, outputDirectory, onProgress)
    }
}
