package com.banoon.vse.domain.model

/**
 * يمثل ملف فيديو تم اختياره عبر Storage Access Framework، بعد حل الصلاحيات
 * واستخراج المعلومات الأساسية اللازمة لعرضها للمستخدم قبل التحليل الكامل.
 */
data class PickedVideoFile(
    /** المعرّف الذي سيُستخدم لاحقًا كمدخل لـ FFmpeg (قد يكون مسار SAF خاص). */
    val ffmpegInputRef: String,

    /** الـ Uri الأصلي كما رجع من منتقي الملفات، للاحتفاظ بالصلاحية طويلة المدى. */
    val originalUriString: String,

    val displayName: String,
    val sizeBytes: Long?
)
