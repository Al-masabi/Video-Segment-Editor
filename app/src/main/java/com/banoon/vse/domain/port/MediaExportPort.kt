package com.banoon.vse.domain.port

/**
 * يُرمى عند محاولة التصدير على أندرويد 9 وأقدم (قبل Scoped Storage) بدون
 * صلاحية WRITE_EXTERNAL_STORAGE. طبقة الـ presentation تلتقط هذا النوع
 * تحديدًا لعرض زر طلب الصلاحية بدل رسالة خطأ عامة.
 */
class LegacyStoragePermissionRequiredException :
    Exception("يحتاج صلاحية تخزين على هذا الإصدار من أندرويد")

/**
 * منفذ تصدير ملفات الفيديو الناتجة إلى معرض الجهاز (MediaStore)، بحيث تظهر
 * مباشرة بتطبيق المعرض ومدير الملفات العادي، بدل بقائها بمجلد خاص بالتطبيق
 * (Android/data/...) اللي صار مخفيًا عن المستخدم من أندرويد 11 فما فوق.
 */
interface MediaExportPort {
    suspend fun exportToGallery(filePaths: List<String>): Result<List<String>>
}
