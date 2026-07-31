package com.banoon.vse.infrastructure.ffmpeg

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.banoon.vse.domain.model.OutputMode
import com.banoon.vse.domain.model.PlannedSegment
import com.banoon.vse.domain.model.ProcessingPlan
import com.banoon.vse.domain.model.ProcessingResult
import com.banoon.vse.domain.model.SegmentChunk
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
 * 1. كل مقطع مخطط له (PlannedSegment) مقسّم لأجزاء (chunks) عبر
 *    SmartRenderingPlanner: الأجزاء القريبة من حدود لا تقع على keyframe
 *    تُعاد ترميزها (صغيرة جدًا عادة، ثانية أو ثانيتين)، والجزء الأوسط
 *    بين أقرب نقطتي keyframe يُنسخ مباشرة بدون إعادة ترميز.
 * 2. كل جزء يُستخرج لملف مؤقت مستقل، ثم تُدمج أجزاء المقطع الواحد بملف
 *    واحد، ثم تُدمج كل المقاطع ببعضها للناتج النهائي — كل ذلك عبر concat
 *    demuxer الخاص بـ FFmpeg (بدون إعادة ترميز إضافية في خطوات الدمج).
 *
 * هذا يقلل زمن المعالجة بشكل كبير مقارنة بإعادة ترميز المقطع كاملًا، لأن
 * الجزء المُعاد ترميزه فعليًا صغير جدًا بدل المقطع بأكمله.
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
                            val segmentFile = extractSegment(plan.inputFilePath, seg, tempDir, index = i, sourceVideo)
                            val finalFile = File(outDir, "clip_${i + 1}${extensionOf(plan.inputFilePath)}")
                            segmentFile.copyTo(finalFile, overwrite = true)
                            onProgress((i + 1).toFloat() / plan.segments.size)
                            finalFile.absolutePath
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

    /**
     * يستخرج مقطعًا كاملًا (PlannedSegment) إلى ملف واحد. لو المقطع مقسّم
     * لأكثر من جزء (مثلًا: جزء إعادة ترميز صغير + جزء نسخ أوسط)، نستخرج كل
     * جزء لملف مؤقت مستقل ثم ندمجهم بملف واحد يمثل المقطع الكامل.
     */
    private suspend fun extractSegment(
        inputPath: String,
        segment: PlannedSegment,
        tempDir: File,
        index: Int,
        sourceVideo: VideoStreamInfo?
    ): File {
        if (segment.chunks.size == 1) {
            val outputFile = File(tempDir, "seg_$index${extensionOf(inputPath)}")
            extractChunk(inputPath, segment.chunks[0], outputFile, sourceVideo)
            return outputFile
        }

        val chunkFiles = segment.chunks.mapIndexed { ci, chunk ->
            val chunkFile = File(tempDir, "seg_${index}_chunk_$ci${extensionOf(inputPath)}")
            extractChunk(inputPath, chunk, chunkFile, sourceVideo)
            chunkFile
        }
        val segmentFile = File(tempDir, "seg_$index${extensionOf(inputPath)}")
        concatSegments(chunkFiles, segmentFile)
        return segmentFile
    }

    private suspend fun extractChunk(
        inputPath: String,
        chunk: SegmentChunk,
        outputFile: File,
        sourceVideo: VideoStreamInfo?
    ) {
        val startSec = chunk.range.start.value / 1_000_000.0
        val durationSec = chunk.range.duration.value / 1_000_000.0

        val command = when (chunk.mode) {
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
                // بما إن الجزء نفسه أصبح صغيرًا جدًا الآن (ثانية أو ثانيتين
                // بدل المقطع كاملًا)، هذا التحسين يقلل زمن المعالجة بشكل كبير.
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
     * يبني وسائط إعادة الترميز. **ملاحظة مهمة**: مكتبة FFmpegKit المستخدمة
     * (`ffmpeg-kit-16kb`، نسخة LGPL) **لا تحتوي على x264/x265 البرمجية**
     * (مرخّصة GPL ومستبعدة عمدًا). الحل: استخدام الترميز العتادي عبر
     * MediaCodec الخاص بأندرويد (`h264_mediacodec` / `hevc_mediacodec`)
     * وهو متوفر مضمون بكل نسخ FFmpegKit (مكتبة نظام أندرويد، مو خارجية)،
     * وأسرع بكثير من الترميز البرمجي لأنه يستخدم شريحة الجهاز مباشرة.
     */
    private fun buildReEncodeArgs(sourceVideo: VideoStreamInfo?): String {
        val pixFmt = sourceVideo?.pixelFormat?.takeIf { it != "unknown" } ?: "yuv420p"
        val hdrFlags = if (sourceVideo?.isHdr == true) {
            " -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc"
        } else ""

        val videoArgs = when (sourceVideo?.codec) {
            "hevc", "h265" -> "-c:v hevc_mediacodec -b:v 12M"
            // VP9/AV1 العتادي غير مضمون التوفر على كل الأجهزة، ونبقي محاولة
            // المكتبة البرمجية كخطة احتياطية (قد تفشل لو غير متوفرة بهذي
            // النسخة تحديدًا — رسالة الخطأ الآن واضحة لو صار ذلك).
            "vp9" -> "-c:v libvpx-vp9 -crf 16 -b:v 0 -deadline good -cpu-used 4"
            "av1" -> "-c:v libaom-av1 -crf 16 -b:v 0 -cpu-used 6"
            else -> "-c:v h264_mediacodec -b:v 12M" // h264 (الأشيع)، مضمون التوفر عتاديًا
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
            // failStackTrace غالبًا فارغ (null) لفشل FFmpeg العادي (مثل كودك
            // غير مدعوم أو معامل خاطئ) لأنه مو استثناء Java، هو خروج طبيعي
            // بكود خطأ. السبب الحقيقي موجود بآخر أسطر اللوق (stderr) بدل ذلك.
            val logs = completedSession.allLogsAsString
                ?.lines()
                ?.filter { it.isNotBlank() }
                ?.takeLast(6)
                ?.joinToString(" | ")
                ?: "لا توجد تفاصيل إضافية"
            throw IllegalStateException(
                "فشل تنفيذ أمر FFmpeg (return code=${completedSession.returnCode}): $logs"
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
