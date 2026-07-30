package com.banoon.vse.domain.usecase

import com.banoon.vse.domain.model.ProcessingResult
import com.banoon.vse.domain.model.TimeRange
import com.banoon.vse.domain.model.TimeRangeSet
import com.banoon.vse.domain.port.FfmpegPort
import com.banoon.vse.domain.port.MediaProbePort
import javax.inject.Inject

/**
 * حالة استخدام: حذف مجموعة من المقاطع الزمنية من فيديو واحد.
 *
 * التدفق:
 * 1. تحليل الملف عبر MediaProbePort (FFprobe).
 * 2. التحقق من صحة المدى الزمني المطلوب حذفه (بدون تداخل).
 * 3. بناء خطة معالجة عبر SmartRenderingPlanner (قرار stream copy أو إعادة ترميز جزئية).
 * 4. تنفيذ الخطة عبر FfmpegPort.
 */
class RemoveSegmentsUseCase @Inject constructor(
    private val mediaProbePort: MediaProbePort,
    private val ffmpegPort: FfmpegPort,
    private val planner: SmartRenderingPlanner
) {
    suspend operator fun invoke(
        inputFilePath: String,
        outputDirectory: String,
        rangesToRemove: List<TimeRange>,
        onProgress: (Float) -> Unit = {}
    ): Result<ProcessingResult> {
        val mediaInfo = mediaProbePort.analyze(inputFilePath).getOrElse {
            return Result.failure(it)
        }

        val rangeSet = TimeRangeSet.create(rangesToRemove).getOrElse {
            return Result.failure(it)
        }

        // التحقق من أن كل مدى محذوف يقع فعلًا ضمن مدة الفيديو
        rangesToRemove.forEach { range ->
            if (range.end > mediaInfo.durationUs) {
                return Result.failure(
                    IllegalArgumentException(
                        "المدى الزمني $range يتجاوز مدة الفيديو الفعلية (${mediaInfo.durationUs})"
                    )
                )
            }
        }

        val plan = planner.buildRemovalPlan(inputFilePath, mediaInfo, rangeSet).getOrElse {
            return Result.failure(it)
        }

        return ffmpegPort.execute(plan, outputDirectory, onProgress)
    }
}
