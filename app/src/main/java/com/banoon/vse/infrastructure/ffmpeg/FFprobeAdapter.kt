package com.banoon.vse.infrastructure.ffmpeg

import android.media.MediaExtractor
import android.media.MediaFormat
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.MediaInformation
import com.arthenica.ffmpegkit.StreamInformation
import com.banoon.vse.domain.model.AudioStreamInfo
import com.banoon.vse.domain.model.ChapterInfo
import com.banoon.vse.domain.model.ColorRange
import com.banoon.vse.domain.model.ColorSpace
import com.banoon.vse.domain.model.KeyframeTimestamp
import com.banoon.vse.domain.model.MediaInfo
import com.banoon.vse.domain.model.Microseconds
import com.banoon.vse.domain.model.SubtitleFormat
import com.banoon.vse.domain.model.SubtitleStreamInfo
import com.banoon.vse.domain.model.VideoStreamInfo
import com.banoon.vse.domain.port.MediaProbePort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject

/**
 * تطبيق حقيقي لمنفذ تحليل الوسائط: التحليل العام (الكودك، الدقة، المسارات)
 * عبر FFprobeKit، واستخراج نقاط keyframe عبر MediaExtractor الرسمي من
 * أندرويد (أوثق من الاعتماد على تحليل نص مخرجات FFprobe لهذا الغرض).
 */
class FFprobeAdapter @Inject constructor() : MediaProbePort {

    override suspend fun analyze(filePath: String): Result<MediaInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val session = FFprobeKit.getMediaInformation(filePath)
            val info: MediaInformation = session.mediaInformation
                ?: throw IllegalStateException("تعذر تحليل الملف: ${session.failStackTrace}")

            // نستخرج الـ keyframes مرة واحدة فقط (عملية أثقل) ونربطها بمسار
            // الفيديو الأساسي لاحقًا — هذا ضروري لعمل Hybrid Smart Rendering،
            // لأن SmartRenderingPlanner يعتمد كليًا على هذه القائمة لقرار
            // النسخ المباشر مقابل إعادة الترميز.
            val keyframesResult = extractKeyframes(filePath)
            val keyframes = keyframesResult.getOrDefault(emptyList())
            val keyframeError = keyframesResult.exceptionOrNull()?.let { e ->
                "${e.javaClass.simpleName}: ${e.message}"
            }

            val videoStreams = mutableListOf<VideoStreamInfo>()
            val audioStreams = mutableListOf<AudioStreamInfo>()
            val subtitleStreams = mutableListOf<SubtitleStreamInfo>()
            var isFirstVideoStream = true

            info.streams.forEachIndexed { idx, stream ->
                val realIndex: Int = stream.index?.toInt() ?: idx
                when (stream.type) {
                    "video" -> {
                        videoStreams.add(
                            VideoStreamInfo(
                                index = realIndex,
                                codec = stream.codec ?: "unknown",
                                width = stream.width?.toInt() ?: 0,
                                height = stream.height?.toInt() ?: 0,
                                frameRate = parseFrameRate(stream.averageFrameRate ?: stream.realFrameRate),
                                bitDepth = parseBitDepth(stream.getProperty("pix_fmt") as? String),
                                pixelFormat = (stream.getProperty("pix_fmt") as? String) ?: "unknown",
                                colorSpace = parseColorSpace(stream.getProperty("color_space") as? String),
                                colorRange = parseColorRange(stream.getProperty("color_range") as? String),
                                isHdr = detectHdr(stream.getProperty("color_transfer") as? String),
                                hdrMetadataRaw = stream.getProperty("side_data_list")?.toString(),
                                // نربط keyframes بأول مسار فيديو فقط حاليًا (الحالة الشائعة).
                                // دعم استخراجها لكل مسار فيديو على حدة سيُضاف عند الحاجة الفعلية
                                // لملفات متعددة مسارات الفيديو.
                                keyframes = if (isFirstVideoStream) keyframes else emptyList(),
                                keyframeExtractionError = if (isFirstVideoStream) keyframeError else null
                            )
                        )
                        isFirstVideoStream = false
                    }
                    "audio" -> audioStreams.add(
                        AudioStreamInfo(
                            index = realIndex,
                            codec = stream.codec ?: "unknown",
                            channels = (stream.getProperty("channels") as? Int) ?: 0,
                            sampleRate = (stream.getProperty("sample_rate") as? String)?.toIntOrNull() ?: 0,
                            languageTag = stream.nestedString("tags", "language"),
                            bitrate = stream.bitrate?.toLongOrNull()
                        )
                    )
                    "subtitle" -> subtitleStreams.add(
                        SubtitleStreamInfo(
                            index = realIndex,
                            format = parseSubtitleFormat(stream.codec),
                            languageTag = stream.nestedString("tags", "language"),
                            isForced = stream.nestedInt("disposition", "forced") == 1,
                            title = stream.nestedString("tags", "title")
                        )
                    )
                }
            }

            MediaInfo(
                containerFormat = info.format ?: "unknown",
                durationUs = Microseconds.fromSeconds(info.duration?.toDoubleOrNull() ?: 0.0),
                overallBitrate = info.bitrate?.toLongOrNull(),
                videoStreams = videoStreams,
                audioStreams = audioStreams,
                subtitleStreams = subtitleStreams,
                chapters = emptyList(), // تُستخرج من info.chapters عند تفعيل دعم الفصول
                hasAttachments = false
            )
        }
    }

    /**
     * الوصول لخاصية متداخلة (nested) داخل JSON الخاص بمسار الوسائط، مثل
     * tags.language أو disposition.forced. لا يمكن استخدام getProperty
     * بمفتاح يحتوي نقطة مباشرة لأن ffprobe يُخرج هذه الحقول كـ JSONObject
     * متداخل، وليس كمفتاح مسطّح.
     */
    private fun StreamInformation.nestedString(parentKey: String, childKey: String): String? = try {
        (getProperty(parentKey) as? JSONObject)?.optString(childKey)?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    private fun StreamInformation.nestedInt(parentKey: String, childKey: String): Int? = try {
        (getProperty(parentKey) as? JSONObject)?.optInt(childKey)
    } catch (e: Exception) {
        null
    }

    /**
     * يستخرج جميع نقاط الـ keyframe عبر MediaExtractor (واجهة أندرويد
     * الرسمية)، بدل الاعتماد على تحليل نص مخرجات FFprobe (كانت طريقة غير
     * موثوقة عمليًا: لم يكن واضحًا هل FFmpegKit يضع نتائج -show_entries
     * بنفس مكان "اللوق" الذي نقرأ منه، وثبت أنها ترجع فارغة دائمًا).
     * MediaExtractor.seekTo(..., SEEK_TO_NEXT_SYNC) يقفز مباشرة من keyframe
     * لآخر بدون فحص كل إطار، فهو سريع حتى على الفيديوهات الطويلة.
     */
    suspend fun extractKeyframes(filePath: String): Result<List<KeyframeTimestamp>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val extractor = MediaExtractor()
                val keyframes = mutableListOf<KeyframeTimestamp>()
                try {
                    extractor.setDataSource(filePath)
                    val videoTrackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                        val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                        mime?.startsWith("video/") == true
                    } ?: return@runCatching emptyList()

                    extractor.selectTrack(videoTrackIndex)
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

                    var lastTimeUs = -1L
                    while (true) {
                        val timeUs = extractor.sampleTime
                        if (timeUs < 0 || timeUs == lastTimeUs) break // نهاية الملف أو توقف التقدم
                        keyframes.add(KeyframeTimestamp(Microseconds(timeUs)))
                        lastTimeUs = timeUs
                        extractor.seekTo(timeUs + 1, MediaExtractor.SEEK_TO_NEXT_SYNC)
                    }
                } finally {
                    extractor.release()
                }
                keyframes
            }
        }

    private fun parseFrameRate(raw: String?): Double {
        if (raw.isNullOrBlank()) return 0.0
        val parts = raw.split("/")
        return if (parts.size == 2) {
            val num = parts[0].toDoubleOrNull() ?: 0.0
            val den = parts[1].toDoubleOrNull() ?: 1.0
            if (den == 0.0) 0.0 else num / den
        } else raw.toDoubleOrNull() ?: 0.0
    }

    private fun parseBitDepth(pixFmt: String?): Int = when {
        pixFmt == null -> 8
        pixFmt.contains("10le") || pixFmt.contains("10be") -> 10
        pixFmt.contains("12le") || pixFmt.contains("12be") -> 12
        else -> 8
    }

    private fun parseColorSpace(raw: String?): ColorSpace = when (raw) {
        "bt709" -> ColorSpace.BT709
        "bt2020nc", "bt2020c" -> ColorSpace.BT2020
        "smpte170m" -> ColorSpace.SMPTE170M
        else -> ColorSpace.UNKNOWN
    }

    private fun parseColorRange(raw: String?): ColorRange = when (raw) {
        "tv", "limited" -> ColorRange.LIMITED
        "pc", "full" -> ColorRange.FULL
        else -> ColorRange.UNKNOWN
    }

    private fun detectHdr(colorTransfer: String?): Boolean =
        colorTransfer == "smpte2084" || colorTransfer == "arib-std-b67"

    private fun parseSubtitleFormat(codec: String?): SubtitleFormat = when (codec) {
        "subrip", "srt" -> SubtitleFormat.SRT
        "ass" -> SubtitleFormat.ASS
        "ssa" -> SubtitleFormat.SSA
        "webvtt" -> SubtitleFormat.VTT
        "hdmv_pgs_subtitle" -> SubtitleFormat.SUP_PGS
        "dvd_subtitle" -> SubtitleFormat.DVD_SUB
        "mov_text" -> SubtitleFormat.MOV_TEXT
        else -> SubtitleFormat.UNKNOWN
    }
}
