package com.banoon.vse.domain.port

import com.banoon.vse.domain.model.PickedVideoFile

/**
 * منفذ حلّ ملف مختار عبر SAF (Storage Access Framework).
 *
 * لا يقوم بعرض منتقي الملفات نفسه (هذا مسؤولية طبقة الـ presentation عبر
 * ActivityResultContracts.OpenDocument القياسية من أندرويد)، بل يستقبل الـ
 * Uri الناتج ويحوّله إلى مرجع قابل للاستخدام من قبل FfmpegPort، مع تثبيت
 * صلاحية القراءة طويلة المدى.
 */
interface VideoFileResolverPort {
    suspend fun resolve(uriString: String): Result<PickedVideoFile>
}
