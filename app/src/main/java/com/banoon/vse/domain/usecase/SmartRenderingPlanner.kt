package com.banoon.vse.domain.usecase

import com.banoon.vse.domain.model.KeyframeTimestamp
import com.banoon.vse.domain.model.MediaInfo
import com.banoon.vse.domain.model.Microseconds
import com.banoon.vse.domain.model.OutputMode
import com.banoon.vse.domain.model.PlannedSegment
import com.banoon.vse.domain.model.ProcessingPlan
import com.banoon.vse.domain.model.SegmentProcessingMode
import com.banoon.vse.domain.model.TimeRange
import com.banoon.vse.domain.model.TimeRangeSet

/**
 * خدمة دومين خالصة (بدون أي I/O) تنفّذ منطق "Hybrid Smart Rendering" المطلوب
 * في المواصفات:
 *
 * - إذا وقعت حدود القطع على keyframe بالضبط → نسخ مباشر (Lossless).
 * - إذا لم تقع الحدود على keyframe → إعادة ترميز الجزء القريب من الحد فقط،
 *   ونسخ باقي المقطع مباشرة.
 *
 * هذه الخدمة لا تعرف شيئًا عن FFmpeg؛ فقط تنتج "خطة" (ProcessingPlan) نظيفة.
 */
class SmartRenderingPlanner(
    /** أقصى انحراف مسموح به عن keyframe لاعتباره "متطابق" (تفاوت التقاط زمني بسيط). */
    private val keyframeToleranceUs: Microseconds = Microseconds(20_000) // 20ms
) {

    fun buildRemovalPlan(
        inputFilePath: String,
        mediaInfo: MediaInfo,
        rangesToRemove: TimeRangeSet
    ): Result<ProcessingPlan> = runCatching {
        val keptRanges = invertRanges(mediaInfo.durationUs, rangesToRemove.ranges)
        val keyframes = mediaInfo.primaryVideoStream?.keyframes.orEmpty()
        val segments = keptRanges.map { range -> toPlannedSegment(range, keyframes) }
        ProcessingPlan(
            inputFilePath = inputFilePath,
            outputMode = OutputMode.REMOVE_SEGMENTS,
            segments = segments,
            sourceMediaInfo = mediaInfo
        )
    }

    fun buildExtractionPlan(
        inputFilePath: String,
        mediaInfo: MediaInfo,
        rangesToExtract: TimeRangeSet,
        mergeOutput: Boolean
    ): Result<ProcessingPlan> = runCatching {
        val keyframes = mediaInfo.primaryVideoStream?.keyframes.orEmpty()
        val segments = rangesToExtract.ranges.map { range -> toPlannedSegment(range, keyframes) }
        ProcessingPlan(
            inputFilePath = inputFilePath,
            outputMode = if (mergeOutput) OutputMode.EXTRACT_SEGMENTS_MERGED
                         else OutputMode.EXTRACT_SEGMENTS_SEPARATE,
            segments = segments,
            sourceMediaInfo = mediaInfo
        )
    }

    /** يحوّل مدى زمني واحد إلى مقطع مخطط له، بتحديد هل حدوده على keyframe أم لا. */
    private fun toPlannedSegment(range: TimeRange, keyframes: List<KeyframeTimestamp>): PlannedSegment {
        val startOnKeyframe = isNearAnyKeyframe(range.start, keyframes)
        val endOnKeyframe = isNearAnyKeyframe(range.end, keyframes)
        val mode = if (startOnKeyframe && endOnKeyframe) {
            SegmentProcessingMode.STREAM_COPY
        } else {
            SegmentProcessingMode.RE_ENCODE_BOUNDARY
        }
        return PlannedSegment(range = range, mode = mode)
    }

    private fun isNearAnyKeyframe(time: Microseconds, keyframes: List<KeyframeTimestamp>): Boolean {
        if (keyframes.isEmpty()) return false // لا معلومات كافية → افتراض الأسوأ (إعادة ترميز)
        return keyframes.any { kf ->
            val diff = kotlin.math.abs(kf.time.value - time.value)
            diff <= keyframeToleranceUs.value
        }
    }

    /** يحسب المقاطع المتبقية (complement) بعد حذف المدى المطلوب حذفه من مدة الفيديو الكاملة. */
    private fun invertRanges(totalDuration: Microseconds, removed: List<TimeRange>): List<TimeRange> {
        if (removed.isEmpty()) return listOf(TimeRange(Microseconds(0), totalDuration))
        val result = mutableListOf<TimeRange>()
        var cursor = Microseconds(0)
        for (r in removed) {
            if (r.start > cursor) {
                result.add(TimeRange(cursor, r.start))
            }
            cursor = maxOf(cursor, r.end)
        }
        if (cursor < totalDuration) {
            result.add(TimeRange(cursor, totalDuration))
        }
        return result
    }
}
