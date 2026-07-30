package com.banoon.vse.infrastructure.ffmpeg

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.banoon.vse.domain.model.OutputMode
import com.banoon.vse.domain.model.PlannedSegment
import com.banoon.vse.domain.model.ProcessingPlan
import com.banoon.vse.domain.model.ProcessingResult
import com.banoon.vse.domain.model.SegmentProcessingMode
import com.banoon.vse.domain.model.VideoStreamInfo
import com.banoon.vse.domain.port.FfmpegPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * تطبيق حقيقي لمنفذ FFmpeg باستخدام FFmpegKit.
 *
 * الاستراتيجية المتبعة (Hybrid Smart Rendering):
 * 1. كل مقطع مخطط له (PlannedSegment) يُستخرج أولًا إلى ملف مؤقت:
 *    - STREAM_COPY  → `-ss/-to -c copy` (بدون إعادة ترميز، أسرع، بلا فقد جودة).
 *    - RE_ENCODE_BOUNDARY → إعادة ترميز بنفس مواصفات المصدر (كودك/دقة/معدل
 *      إطارات) للحفاظ على التطابق البصري الكامل مع بقية الفيديو.
 * 2. الملفات المؤقتة تُدمج عبر concat demuxer الخاص بـ FFmpeg (بدون إعادة
 *    ترميز في هذه الخطوة، لأن كل الملفات أصبحت متوافقة بنفس الترميز).
 *
 * ملاحظة مهمة: هذا أول تطبيق فعلي (MVP)، وسيُطوَّر لاحقًا لدعم إعادة ترميز
 * جزء صغير جدًا من الإطارات القريبة من الحد فقط (بدل المقطع كاملًا) لتقليل
 * زمن المعالجة أكثر — وهذا موثّق كخطوة تالية في README.
 */
@Singleton
class FFmpegKitAdapter @Inject constructor() : FfmpegPort {

    private var currentSession: FFmpegSession? = null

    override suspend fun execute(
        plan: ProcessingPlan,
        outputDirectory: String,
        onProgress: (Float) -> Unit
    ): Result<ProcessingResult> = withContext(Dispatchers.IO) {
        runCatching {
            val startTime = System.currentTimeMillis()
            val outDir = File(outputDirectory).apply { mkdirs() }
            val tempDir = File(outDir, "tmp_${UUID.randomUUID()}").apply { mkdirs() }

            try {
                val sourceVideo = plan.sourceMediaInfo.primaryVideoStream
                when (plan.outputMode) {
                    OutputMode.REMOVE_SEGMENTS, OutputMode.EXTRACT_SEGMENTS_MERGED -> {
                        val tempFiles = plan.segments.mapIndexed { i, seg ->
                            extractSegment(plan.inputFilePath, seg, tempDir, index = i, sourceVideo).also {
                                onProgress((i + 1).toFloat() / (plan.segments.size + 1))
                            }
                        }
                        val outputFile = File(outDir, buildOutputFileName(plan))
                        concatSegments(tempFiles, outputFile)
                        onProgress(1.0f)
                        ProcessingResult(
                            outputFilePaths = listOf(outputFile.absolutePath),
                            wasFullyLossless = plan.isFullyLossless,
                            processingTimeMs = System.currentTimeMillis() - startTime
                        )
                    }

                    OutputMode.EXTRACT_SEGMENTS_SEPARATE -> {
                        val outputs = plan.segments.mapIndexed { i, seg ->
                            val outputFile = File(outDir, "clip_${i + 1}${extensionOf(plan.inputFilePath)}")
                            extractSegmentDirect(plan.inputFilePath, seg, outputFile, sourceVideo)
                            onProgress((i + 1).toFloat() / plan.segments.size)
                            outputFile.absolutePath
                        }
                        ProcessingResult(
                            outputFilePaths = outputs,
                            wasFullyLossless = plan.isFullyLossless,
                            processingTimeMs = System.currentTimeMillis() - startTime
                        )
                    }
                }
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }

    override suspend fun cancelCurrentOperation() {
        // FFmpegSession ليس له دالة cancel() خاصة به؛ الإلغاء الفعلي في
        // FFmpegKit يتم عبر الدالة الساكنة FFmpegKit.cancel(sessionId).
        currentSession?.sessionId?.let { FFmpegKit.cancel(it) }
    }

    private suspend fun extractSegment(
        inputPath: String,
        segment: PlannedSegment,
        tempDir: File,
        index: Int,
        sourceVideo: VideoStreamInfo?
    ): File {
        val outputFile = File(tempDir, "seg_$index${extensionOf(inputPath)}")
        extractSegmentDirect(inputPath, segment, outputFile, sourceVideo)
        return outputFile
    }

    private suspend fun extractSegmentDirect(
        inputPath: String,
        segment: PlannedSegment,
        outputFile: File,
        sourceVideo: VideoStreamInfo?
    ) {
        val startSec = segment.range.start.value / 1_000_000.0
        val durationSec = segment.range.duration.value / 1_000_000.0

        val command = when (segment.mode) {
            SegmentProcessingMode.STREAM_COPY -> {
                // المدى واقع على keyframe، فالبحث السريع قبل -i دقيق بما يكفي
                // وأسرع بكثير من فك التشفير الكامل.
                "-y -ss $startSec -i \"$inputPath\" -t $durationSec -c copy " +
                    "-map 0 -map_metadata 0 -avoid_negative_ts make_zero \"${outputFile.absolutePath}\""
            }
            SegmentProcessingMode.RE_ENCODE_BOUNDARY -> {
                // الدقة على مستوى الإطار مهمة هنا لأن الحد لا يقع على keyframe.
                // نستخدم seek من مرحلتين: seek سريع (تقريبي) قبل -i لأقرب نقطة
                // آمنة قبل البداية بـ 5 ثوانٍ، ثم seek دقيق بعد -i لباقي الفارق.
                // هذا أسرع بكثير من فك تشفير الفيديو كاملًا من البداية، مع
                // الحفاظ على الدقة الكاملة على مستوى الإطار.
                val coarseSeek = (startSec - 5.0).coerceAtLeast(0.0)
                val fineSeek = startSec - coarseSeek
                val encodeArgs = buildReEncodeArgs(sourceVideo)
                "-y -ss $coarseSeek -i \"$inputPath\" -ss $fineSeek -t $durationSec $encodeArgs " +
                    "-map 0 -map_metadata 0 -avoid_negative_ts make_zero \"${outputFile.absolutePath}\""
            }
        }

        runFFmpegCommand(command)
    }

    /**
     * يبني وسائط إعادة الترميز بحيث تطابق كودك المصدر قدر الإمكان، مع
     * إعدادات سرعة خاصة بكل كودك — لأن `-preset` خيار خاص بـ x264/x265 فقط؛
     * تمريره لـ VP9 (libvpx-vp9) يُتجاهل بصمت ويرجع الترميز لأبطأ إعداد
     * افتراضي (قد يبدو وكأن التطبيق "معلّق" بينما هو فعليًا يرمّز ببطء شديد).
     */
    private fun buildReEncodeArgs(sourceVideo: VideoStreamInfo?): String {
        val pixFmt = sourceVideo?.pixelFormat?.takeIf { it != "unknown" } ?: "yuv420p"
        val hdrFlags = if (sourceVideo?.isHdr == true) {
            " -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc"
        } else ""

        val videoArgs = when (sourceVideo?.codec) {
            "hevc", "h265" -> "-c:v libx265 -preset slow -crf 16"
            "vp9" -> "-c:v libvpx-vp9 -crf 16 -b:v 0 -deadline good -cpu-used 4"
            "av1" -> "-c:v libaom-av1 -crf 16 -b:v 0 -cpu-used 6"
            else -> "-c:v libx264 -preset slow -crf 16" // h264 أو كودك غير معروف
        }

        return "$videoArgs -pix_fmt $pixFmt$hdrFlags -c:a aac -b:a 320k"
    }

    private suspend fun concatSegments(segmentFiles: List<File>, outputFile: File) {
        val listFile = File(outputFile.parentFile, "concat_${UUID.randomUUID()}.txt")
        listFile.writeText(segmentFiles.joinToString("\n") { "file '${it.absolutePath}'" })

        val command = "-y -f concat -safe 0 -i \"${listFile.absolutePath}\" -c copy -map 0 " +
            "\"${outputFile.absolutePath}\""

        try {
            runFFmpegCommand(command)
        } finally {
            listFile.delete()
        }
    }

    /**
     * ينفّذ أمر FFmpeg بشكل غير متزامن (executeAsync) مع مهلة زمنية قصوى.
     * سابقًا كنا نستخدم FFmpegKit.execute() المتزامن (يحجب حتى الانتهاء)
     * بدون أي مهلة — ولو تعلّق الأمر لأي سبب (مثل انتظار تأكيد الكتابة فوق
     * ملف موجود)، يعلّق التطبيق بصمت للأبد. الآن أي تعليق يوقف تلقائيًا
     * برسالة خطأ واضحة بدل التعليق الصامت.
     */
    private suspend fun runFFmpegCommand(command: String, timeoutMs: Long = 10 * 60 * 1000L) {
        val completedSession = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<FFmpegSession> { continuation ->
                val session = FFmpegKit.executeAsync(command) { finishedSession ->
                    if (continuation.isActive) {
                        continuation.resume(finishedSession)
                    }
                }
                currentSession = session
                continuation.invokeOnCancellation {
                    FFmpegKit.cancel(session.sessionId)
                }
            }
        }

        if (completedSession == null) {
            currentSession?.sessionId?.let { FFmpegKit.cancel(it) }
            throw IllegalStateException(
                "انتهت مهلة تنفيذ أمر FFmpeg (تجاوز ${timeoutMs / 60000} دقائق) — تم إلغاء العملية تلقائيًا"
            )
        }

        if (!ReturnCode.isSuccess(completedSession.returnCode)) {
            throw IllegalStateException(
                "فشل تنفيذ أمر FFmpeg (return code=${completedSession.returnCode}): " +
                    "${completedSession.failStackTrace}"
            )
        }
    }

    private fun buildOutputFileName(plan: ProcessingPlan): String {
        val suffix = if (plan.outputMode == OutputMode.REMOVE_SEGMENTS) "_edited" else "_extracted"
        val base = File(plan.inputFilePath).nameWithoutExtension
        return "$base$suffix${extensionOf(plan.inputFilePath)}"
    }

    private fun extensionOf(path: String): String {
        val ext = File(path).extension
        return if (ext.isNotBlank()) ".$ext" else ".mp4"
    }
}
