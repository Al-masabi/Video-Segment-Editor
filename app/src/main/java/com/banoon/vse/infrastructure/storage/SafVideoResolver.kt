package com.banoon.vse.infrastructure.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.banoon.vse.domain.model.PickedVideoFile
import com.banoon.vse.domain.port.VideoFileResolverPort
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * يحوّل content:// Uri (الناتج عن SAF) إلى مرجع قابل للاستخدام من FFmpeg.
 *
 * الاستراتيجية:
 * 1. تثبيت صلاحية القراءة طويلة المدى عبر takePersistableUriPermission،
 *    حتى يبقى الوصول للملف صالحًا بعد إغلاق التطبيق وإعادة فتحه.
 * 2. نسخ الفيديو لملف حقيقي بمجلد cache الخاص بالتطبيق، واستخدام مساره
 *    الفعلي كمرجع لـ FFmpeg.
 *
 * ملاحظة: جُرِّب سابقًا استخدام `FFmpegKitConfig.getSafParameterForRead`
 * لتمرير الـ Uri مباشرة بدون نسخ (توفيرًا للمساحة والوقت)، لكن تبيّن عمليًا
 * أن المرجع الناتج عنها **يُستخدم مرة واحدة فقط** — أي تحليل ثانٍ لنفس
 * الفيديو (مثل التحليل الذي تنفّذه حالات الاستخدام قبل المعالجة الفعلية)
 * كان يفشل بصمت. لذلك تم التخلي عن هذا المسار نهائيًا لصالح النسخ المباشر،
 * وهو أبطأ قليلًا للملفات الضخمة لكنه موثوق 100%.
 */
class SafVideoResolver @Inject constructor(
    @ApplicationContext private val context: Context
) : VideoFileResolverPort {

    override suspend fun resolve(uriString: String): Result<PickedVideoFile> =
        withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(uriString)
                val resolver = context.contentResolver

                persistReadPermission(resolver, uri)

                val (displayName, sizeBytes) = queryMetadata(resolver, uri)

                val ffmpegRef = copyToCache(uri, displayName)

                PickedVideoFile(
                    ffmpegInputRef = ffmpegRef,
                    originalUriString = uriString,
                    displayName = displayName,
                    sizeBytes = sizeBytes
                )
            }
        }

    private fun persistReadPermission(resolver: ContentResolver, uri: Uri) {
        try {
            resolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            // بعض مزوّدي المحتوى (مثل بعض تطبيقات الاستوديو) لا يدعمون
            // الصلاحية الدائمة؛ نتجاهل الخطأ هنا لأن صلاحية الجلسة الحالية
            // (التي منحها منتقي الملفات) تبقى كافية لعملية المعالجة الحالية.
        }
    }

    private fun queryMetadata(resolver: ContentResolver, uri: Uri): Pair<String, Long?> {
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                val name = if (nameIndex >= 0) cursor.getString(nameIndex) else "video"
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
                return (name ?: "video") to size
            }
        }
        return "video" to null
    }

    private fun copyToCache(uri: Uri, displayName: String): String {
        val cacheDir = File(context.cacheDir, "input_videos").apply { mkdirs() }
        val safeName = displayName.ifBlank { "video_${System.currentTimeMillis()}" }
        val destination = File(cacheDir, safeName)

        context.contentResolver.openInputStream(uri)?.use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("تعذر فتح الملف المختار للقراءة")

        return destination.absolutePath
    }
}
