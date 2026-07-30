package com.banoon.vse.domain.port

import com.banoon.vse.domain.model.MediaInfo

/**
 * منفذ تحليل الوسائط. طبقة الـ domain تتعامل مع هذه الواجهة فقط،
 * والتنفيذ الفعلي (FFprobe أو أي بديل مستقبلي) يعيش في infrastructure.
 */
interface MediaProbePort {
    suspend fun analyze(filePath: String): Result<MediaInfo>
}
