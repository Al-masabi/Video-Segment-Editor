package com.banoon.vse.domain.usecase

import com.banoon.vse.domain.model.KeyframeTimestamp
import com.banoon.vse.domain.model.MediaInfo
import com.banoon.vse.domain.model.Microseconds
import com.banoon.vse.domain.model.OutputMode
import com.banoon.vse.domain.model.PlannedSegment
import com.banoon.vse.domain.model.ProcessingPlan
import com.banoon.vse.domain.model.SegmentChunk
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

    /**
     * يحوّل مدى زمني واحد إلى مقطع مخطط له، مقسّم لأجزاء (chunks):
     * - لو البداية غير محاذية لـ keyframe: جزء صغير إعادة ترميز من البداية
     *   حتى أول keyframe بعدها.
     * - الجزء الأوسط (بين أول/آخر keyframe مناسب): نسخ مباشر دائمًا.
     * - لو النهاية غير محاذية: جزء صغير إعادة ترميز من آخر keyframe قبلها
     *   حتى النهاية.
     * - لو ما فيه أي keyframe مناسب بين البداية والنهاية (مقطع قصير جدًا أو
     *   فجوة keyframes كبيرة): إعادة ترميز المقطع بالكامل كخطة احتياطية آمنة.
     */
    private fun toPlannedSegment(range: TimeRange, keyframes: List<KeyframeTimestamp>): PlannedSegment {
        if (keyframes.isEmpty()) {
            return PlannedSegment(range, listOf(SegmentChunk(range, SegmentProcessingMode.RE_ENCODE_BOUNDARY)))
        }

        val sortedTimes = keyframes.map { it.time }.sorted()
        val startAligned = isNearAnyTime(range.start, sortedTimes)
        val endAligned = isNearAnyTime(range.end, sortedTimes)

        val headKeyframe = sortedTimes.firstOrNull { it >= range.start }
        val tailKeyframe = sortedTimes.lastOrNull { it <= range.end }

        if (headKeyframe == null || tailKeyframe == null || headKeyframe >= tailKeyframe) {
            // لا يوجد keyframe مناسب بين البداية والنهاية → إعادة ترميز آمنة للمقطع كاملًا
            return PlannedSegment(range, listOf(SegmentChunk(range, SegmentProcessingMode.RE_ENCODE_BOUNDARY)))
        }

        val chunks = mutableListOf<SegmentChunk>()

        if (!startAligned) {
            chunks.add(SegmentChunk(TimeRange(range.start, headKeyframe), SegmentProcessingMode.RE_ENCODE_BOUNDARY))
        }

        val middleStart = if (startAligned) range.start else headKeyframe
        if (middleStart < tailKeyframe) {
            chunks.add(SegmentChunk(TimeRange(middleStart, tailKeyframe), SegmentProcessingMode.STREAM_COPY))
        }

        if (!endAligned && tailKeyframe < range.end) {
            chunks.add(SegmentChunk(TimeRange(tailKeyframe, range.end), SegmentProcessingMode.RE_ENCODE_BOUNDARY))
        }

        if (chunks.isEmpty()) {
            // احتياط نادر: لو ما انبنى أي جزء، انسخ المقطع كاملًا مباشرة
            chunks.add(SegmentChunk(range, SegmentProcessingMode.STREAM_COPY))
        }

        return PlannedSegment(range, chunks)
    }

    private fun isNearAnyTime(time: Microseconds, sortedTimes: List<Microseconds>): Boolean {
        if (sortedTimes.isEmpty()) return false
        return sortedTimes.any { kf ->
            val diff = kotlin.math.abs(kf.value - time.value)
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
