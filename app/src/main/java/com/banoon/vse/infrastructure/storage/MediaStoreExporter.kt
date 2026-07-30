package com.banoon.vse.infrastructure.storage

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.banoon.vse.domain.port.LegacyStoragePermissionRequiredException
import com.banoon.vse.domain.port.MediaExportPort
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * تطبيق حقيقي لتصدير الفيديو للمعرض عبر MediaStore (الطريقة الرسمية
 * الحديثة من جوجل، بدون أي صلاحية على أندرويد 10+). على أندرويد 9 وأقدم
 * (قبل Scoped Storage)، نحتاج صلاحية WRITE_EXTERNAL_STORAGE وقت التشغيل.
 */
class MediaStoreExporter @Inject constructor(
    @ApplicationContext private val context: Context
) : MediaExportPort {

    override suspend fun exportToGallery(filePaths: List<String>): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) throw LegacyStoragePermissionRequiredException()
                }
                filePaths.map { path -> exportSingleFile(path) }
            }
        }

    private fun exportSingleFile(path: String): String {
        val file = File(path)
        val mimeType = mimeTypeFor(file.extension)
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/محرر مقاطع الفيديو")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                val appDir = File(moviesDir, "محرر مقاطع الفيديو").apply { mkdirs() }
                put(MediaStore.Video.Media.DATA, File(appDir, file.name).absolutePath)
            }
        }

        val uri = context.contentResolver.insert(collection, values)
            ?: throw IllegalStateException("تعذر إنشاء مدخل بالمعرض لـ ${file.name}")

        context.contentResolver.openOutputStream(uri)?.use { output ->
            file.inputStream().use { input -> input.copyTo(output) }
        } ?: throw IllegalStateException("تعذر الكتابة لملف المعرض ${file.name}")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val updateValues = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            context.contentResolver.update(uri, updateValues, null, null)
        }

        return uri.toString()
    }

    private fun mimeTypeFor(extension: String): String = when (extension.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "3gp" -> "video/3gpp"
        "ts" -> "video/mp2t"
        else -> "video/*"
    }
}
