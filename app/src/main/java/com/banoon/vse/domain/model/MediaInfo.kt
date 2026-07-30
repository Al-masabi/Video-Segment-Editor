package com.banoon.vse.domain.model

/** يمثل نقطة اقتحام رئيسية (Keyframe / I-Frame) داخل الفيديو، بالميكروثانية. */
@JvmInline
value class KeyframeTimestamp(val time: Microseconds)

enum class ColorRange { LIMITED, FULL, UNKNOWN }
enum class ColorSpace { BT709, BT2020, SMPTE170M, UNKNOWN }

data class VideoStreamInfo(
    val index: Int,
    val codec: String,          // مثل: h264, hevc, av1
    val width: Int,
    val height: Int,
    val frameRate: Double,      // fps
    val bitDepth: Int,          // 8 أو 10
    val pixelFormat: String,    // مثل: yuv420p, yuv420p10le
    val colorSpace: ColorSpace,
    val colorRange: ColorRange,
    val isHdr: Boolean,
    val hdrMetadataRaw: String?, // البيانات الوصفية الخام لـ HDR/Dolby Vision عند وجودها
    val keyframes: List<KeyframeTimestamp>
)

data class AudioStreamInfo(
    val index: Int,
    val codec: String,           // aac, ac3, eac3, dts, truehd, flac, mp3, opus, pcm
    val channels: Int,
    val sampleRate: Int,
    val languageTag: String?,    // ar, en, ...
    val bitrate: Long?
)

enum class SubtitleFormat {
    SRT, ASS, SSA, VTT, SUP_PGS, DVD_SUB, MOV_TEXT, UNKNOWN
}

data class SubtitleStreamInfo(
    val index: Int,
    val format: SubtitleFormat,
    val languageTag: String?,
    val isForced: Boolean,
    val title: String?
)

data class ChapterInfo(
    val startTime: Microseconds,
    val endTime: Microseconds,
    val title: String?
)

/**
 * التحليل الكامل لملف وسائط واحد، مطابق لما تطلبه المواصفات من FFprobe:
 * الحاوية، الترميز، المسارات، الميتاداتا، والـ keyframes.
 */
data class MediaInfo(
    val containerFormat: String, // mp4, mkv, avi ...
    val durationUs: Microseconds,
    val overallBitrate: Long?,
    val videoStreams: List<VideoStreamInfo>,
    val audioStreams: List<AudioStreamInfo>,
    val subtitleStreams: List<SubtitleStreamInfo>,
    val chapters: List<ChapterInfo>,
    val hasAttachments: Boolean
) {
    val primaryVideoStream: VideoStreamInfo?
        get() = videoStreams.firstOrNull()
}
