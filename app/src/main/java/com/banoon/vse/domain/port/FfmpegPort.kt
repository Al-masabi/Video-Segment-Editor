package com.banoon.vse.domain.port

import com.banoon.vse.domain.model.ProcessingPlan
import com.banoon.vse.domain.model.ProcessingResult

/**
 * منفذ تنفيذ عمليات المعالجة الفعلية. الدومين يمرر "خطة" جاهزة
 * (ProcessingPlan) بدون أي معرفة بأوامر FFmpeg الفعلية. هذا يسمح لاحقًا
 * باستبدال المكتبة المستخدمة (FFmpegKit، بناء NDK مخصص، إلخ) دون تعديل
 * منطق الأعمال في use cases.
 */
interface FfmpegPort {
    /**
     * ينفذ خطة معالجة كاملة (حذف أو استخراج مقاطع) على ملف الفيديو المدخل.
     *
     * @param plan الخطة الناتجة عن SmartRenderingPlanner
     * @param outputDirectory المجلد الذي ستُكتب فيه الملفات الناتجة
     * @param onProgress نسبة التقدم من 0.0 إلى 1.0
     */
    suspend fun execute(
        plan: ProcessingPlan,
        outputDirectory: String,
        onProgress: (Float) -> Unit
    ): Result<ProcessingResult>

    /** إلغاء أي عملية جارية حاليًا. */
    suspend fun cancelCurrentOperation()
}
