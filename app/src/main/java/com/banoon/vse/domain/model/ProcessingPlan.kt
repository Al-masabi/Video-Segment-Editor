package com.banoon.vse.domain.model

/** طريقة معالجة جزء واحد قابل للتنفيذ فعليًا. */
enum class SegmentProcessingMode {
    /** نسخ مباشر بدون إعادة ترميز — أسرع وبلا فقدان جودة (Lossless). */
    STREAM_COPY,

    /** إعادة ترميز — تُستخدم فقط للأجزاء القريبة من حدود لا تقع على keyframe. */
    RE_ENCODE_BOUNDARY
}

/** جزء واحد قابل للتنفيذ فعليًا (نطاق زمني + طريقة معالجته). */
data class SegmentChunk(
    val range: TimeRange,
    val mode: SegmentProcessingMode
)

/**
 * مقطع واحد سيبقى في الملف الناتج، مقسّم لأجزاء قابلة للتنفيذ (chunks).
 *
 * بدل إعادة ترميز المقطع كاملًا لما حدوده لا تقع بالضبط على keyframe، نقسّمه
 * إلى: جزء صغير عند البداية (إعادة ترميز، لو الحد غير محاذٍ)، جزء أوسط
 * (نسخ مباشر بين أقرب نقطتي keyframe)، وجزء صغير عند النهاية (إعادة ترميز
 * عند الحاجة). هذا يقلل زمن المعالجة بشكل كبير مقارنة بإعادة ترميز المقطع
 * بالكامل.
 */
data class PlannedSegment(
    val range: TimeRange,
    val chunks: List<SegmentChunk>
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
        get() = segments.all { seg -> seg.chunks.all { it.mode == SegmentProcessingMode.STREAM_COPY } }
}

data class ProcessingResult(
    val outputFilePaths: List<String>,
    val wasFullyLossless: Boolean,
    val processingTimeMs: Long
)
