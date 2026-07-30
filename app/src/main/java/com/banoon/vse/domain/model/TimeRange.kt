package com.banoon.vse.domain.model

/**
 * مدى زمني داخل الفيديو، معبَّر عنه بالميكروثانية لضمان الدقة الكاملة
 * (تفادي مشاكل التقريب التي تحدث عند استخدام Double بالثواني).
 */
@JvmInline
value class Microseconds(val value: Long) : Comparable<Microseconds> {
    init {
        require(value >= 0) { "لا يمكن أن تكون القيمة الزمنية سالبة: $value" }
    }

    operator fun minus(other: Microseconds): Microseconds = Microseconds(value - other.value)
    operator fun plus(other: Microseconds): Microseconds = Microseconds(value + other.value)
    override fun compareTo(other: Microseconds): Int = value.compareTo(other.value)

    companion object {
        fun fromSeconds(seconds: Double): Microseconds = Microseconds((seconds * 1_000_000).toLong())
        fun fromMillis(millis: Long): Microseconds = Microseconds(millis * 1_000)
    }
}

/**
 * مدى زمني (start -> end) داخل فيديو واحد.
 * هذا الكائن غير قابل للتغيير (immutable) ويتحقق من صحة بياناته عند الإنشاء.
 */
data class TimeRange(
    val start: Microseconds,
    val end: Microseconds
) {
    init {
        require(end > start) {
            "نهاية المدى الزمني ($end) يجب أن تكون أكبر من بدايته ($start)"
        }
    }

    val duration: Microseconds get() = end - start

    /** يتحقق إذا كان هذا المدى يتداخل مع مدى آخر */
    fun overlaps(other: TimeRange): Boolean =
        start < other.end && other.start < end

    companion object {
        fun ofSeconds(startSeconds: Double, endSeconds: Double): TimeRange = TimeRange(
            start = Microseconds.fromSeconds(startSeconds),
            end = Microseconds.fromSeconds(endSeconds)
        )
    }
}

/**
 * يرتب قائمة من المدى الزمني ويتحقق من عدم وجود تداخل بينها.
 * يُستخدم قبل أي عملية حذف أو استخراج للتأكد من صحة مدخلات المستخدم
 * (متطلب "Validate input / Detect overlapping ranges" في المواصفات).
 */
class TimeRangeSet private constructor(val ranges: List<TimeRange>) {

    companion object {
        fun create(ranges: List<TimeRange>): Result<TimeRangeSet> {
            if (ranges.isEmpty()) {
                return Result.failure(IllegalArgumentException("يجب تحديد مدى زمني واحد على الأقل"))
            }
            val sorted = ranges.sortedBy { it.start.value }
            for (i in 0 until sorted.size - 1) {
                if (sorted[i].overlaps(sorted[i + 1])) {
                    return Result.failure(
                        IllegalArgumentException(
                            "يوجد تداخل بين المدى الزمني ${sorted[i]} و ${sorted[i + 1]}"
                        )
                    )
                }
            }
            return Result.success(TimeRangeSet(sorted))
        }
    }
}
