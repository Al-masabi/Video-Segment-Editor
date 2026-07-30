package com.banoon.vse.domain.model

/** طريقة معالجة مقطع واحد من الخطة. */
enum class SegmentProcessingMode {
    /** نسخ مباشر بدون إعادة ترميز — أسرع وبلا فقدان جودة (Lossless). */
    STREAM_COPY,

    /** إعادة ترميز جزئية عند حدود لا تقع على keyframe. */
    RE_ENCODE_BOUNDARY
}

/** مقطع واحد سيبقى في الملف الناتج، مع تحديد طريقة معالجته. */
data class PlannedSegment(
    val range: TimeRange,
    val mode: SegmentProcessingMode
)

enum class OutputMode { REMOVE_SEGMENTS, EXTRACT_SEGMENTS_MERGED, EXTRACT_SEGMENTS_SEPARATE }

/**
 * الخطة الكاملة الناتجة عن Hybrid Smart Rendering: قائمة المقاطع المطلوب
 * الاحتفاظ بها بالترتيب، مع طريقة معالجة كل مقطع على حدة.
 * هذا الكائن هو "العقد" بين طبقة التطبيق (application/use case) وطبقة
 * البنية التحتية (FfmpegPort) — الدومين لا يبني أوامر FFmpeg بنفسه.
 */
data class ProcessingPlan(
    val inputFilePath: String,
    val outputMode: OutputMode,
    val segments: List<PlannedSegment>,
    val sourceMediaInfo: MediaInfo
) {
    /** هل الخطة بالكامل قابلة للتنفيذ بدون أي إعادة ترميز؟ */
    val isFullyLossless: Boolean
        get() = segments.all { it.mode == SegmentProcessingMode.STREAM_COPY }
}

data class ProcessingResult(
    val outputFilePaths: List<String>,
    val wasFullyLossless: Boolean,
    val processingTimeMs: Long
)
