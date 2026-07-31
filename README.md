# محرر مقاطع الفيديو (Video Segment Editor)

تطبيق أندرويد احترافي متخصص فقط في:
1. حذف مقاطع زمنية محددة من الفيديو.
2. استخراج مقاطع زمنية محددة من الفيديو (ملف واحد مدمج، أو ملفات منفصلة).

مبني بـ Clean Architecture + MVVM + Hilt + FFmpeg، بدعم كامل للعربية (RTL) كلغة افتراضية.

---

## حالة المشروع الحالية (القطعة الأولى)

هذه أول قطعة من مشروع طويل يُبنى تدريجيًا. ما تم إنجازه في هذه المرحلة:

- ✅ هيكل Clean Architecture كامل: `domain` (قيم، منافذ Ports، حالات استخدام) و
  `infrastructure` (تطبيق فعلي عبر FFmpeg) و `presentation` (Compose أولي).
- ✅ نماذج الدومين: `TimeRange`، `MediaInfo`، `ProcessingPlan` مع التحقق من صحة
  المدخلات ومنع تداخل المدى الزمني.
- ✅ خدمة **Hybrid Smart Rendering** (`SmartRenderingPlanner`): تقرر تلقائيًا
  هل القطع يقع على keyframe (نسخ مباشر بلا فقد جودة) أو يحتاج إعادة ترميز.
- ✅ حالتا استخدام: `RemoveSegmentsUseCase` و `ExtractSegmentsUseCase`.
- ✅ تطبيق فعلي عبر FFmpegKit (`FFmpegKitAdapter`, `FFprobeAdapter`).
- ✅ ربط Hilt الكامل بين الطبقات.
- ✅ GitHub Actions workflow يبني APK (debug) تلقائيًا عند كل push.
- ✅ واجهة اختيار فيديو عبر SAF (`ActivityResultContracts.OpenDocument`)،
  مع حل الـ `content://` Uri إلى مرجع قابل للاستخدام من FFmpeg
  (`SafVideoResolver`)، وعرض ملخص تحليل الفيديو (الدقة، الكودك، HDR،
  عدد الـ keyframes، مسارات الصوت/الترجمة) بعد التحليل.

## ⚠️ قيود معروفة (مؤكدة عبر اختبار فعلي على جهاز)

1. **مكتبة FFmpeg لا تحتوي على x264/x265 البرمجية**: `ffmpeg-kit-16kb`
   نسخة LGPL (بدون مكتبات GPL مثل x264/x265). **الحل المُطبَّق**: نستخدم
   الترميز العتادي عبر Android MediaCodec (`h264_mediacodec` /
   `hevc_mediacodec`) بدل المكتبات البرمجية — متوفر مضمون في كل نسخ
   FFmpegKit (مكتبة نظام أندرويد)، وأسرع بكثير من الترميز البرمجي.
   VP9/AV1 لسا يعتمدون على مكتبات برمجية (`libvpx`/`libaom`) قد لا تكون
   متوفرة بهذي النسخة تحديدًا — لو فشلت، رسالة الخطأ توضح السبب بدقة الآن.
2. **SAF**: تم التخلي نهائيًا عن `FFmpegKitConfig.getSafParameterForRead`
   (كانت تسبب فشل صامت عند تحليل نفس الفيديو مرتين). الحل الحالي: نسخ
   الفيديو المختار لملف حقيقي بمجلد cache الخاص بالتطبيق قبل أي معالجة —
   أبطأ للملفات الكبيرة لكنه موثوق 100% ومُختبر فعليًا.
3. **compileSdk 35 / NDK**: يبني بنجاح على GitHub Actions حاليًا (مؤكد).
4. **نسخ الملف كامل قبل التحليل**: للفيديوهات الطويلة/الكبيرة، هذا يسبب
   بطء ملحوظ عند الاختيار الأول. تحسين هذا (قراءة مباشرة بدون نسخ كامل)
   مؤجل لمرحلة لاحقة.

## البنية المعمارية

```
app/src/main/java/com/banoon/vse/
├── domain/                     ← منطق الأعمال الخالص، لا يعرف شيئًا عن FFmpeg
│   ├── model/                  ← TimeRange, MediaInfo, ProcessingPlan ...
│   ├── port/                   ← MediaProbePort, FfmpegPort (Interfaces)
│   └── usecase/                ← RemoveSegmentsUseCase, ExtractSegmentsUseCase,
│                                  SmartRenderingPlanner
├── infrastructure/
│   ├── ffmpeg/                 ← FFmpegKitAdapter, FFprobeAdapter (التطبيق الفعلي)
│   └── di/                     ← AppModule (ربط Hilt)
└── presentation/
    └── main/                   ← MainActivity (Compose، أولي جدًا حاليًا)
```

الفكرة الأساسية: طبقة `domain` لا تستورد أي شيء من `com.arthenica.ffmpegkit`.
لو احتجنا نستبدل FFmpeg بمكتبة أخرى (أو ببناء NDK مخصص) مستقبلًا، التعديل
يقتصر على `infrastructure/ffmpeg/` فقط.

## القطع القادمة (بالترتيب المخطط)

1. التحقق من نجاح أول بناء فعلي على GitHub Actions وإصلاح أي أخطاء تجميع.
2. واجهة اختيار الفيديو (Storage Access Framework) + عرض معلومات الفيديو.
3. واجهة الخط الزمني (Timeline) لتحديد المدى الزمني المطلوب حذفه/استخراجه.
4. اختبارات الوحدة (Unit Tests) لـ `SmartRenderingPlanner` و `TimeRangeSet`.
5. معالجة الترجمات (SRT/ASS/PGS) وضبط توقيتها تلقائيًا بعد الحذف/الاستخراج.
6. خدمة أمامية (Foreground Service) لدعم المعالجة بالخلفية مع Pause/Resume/Cancel.
7. تحسين Hybrid Smart Rendering لإعادة ترميز الإطارات القريبة من الحد فقط
   بدل المقطع كاملًا (تقليل زمن المعالجة).

## متطلبات البناء

- Android Studio (أحدث إصدار) أو GitHub Actions فقط (بدون جهاز محلي).
- JDK 17.
- Android SDK 35، الحد الأدنى للدعم: Android 8 (API 26).

## الترخيص والخصوصية

التطبيق يعمل بالكامل بدون إنترنت (offline-first)، بدون إعلانات، بدون تتبع،
وبدون صلاحية إنترنت في الـ Manifest.
