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

## ⚠️ نقاط تحتاج تحقق قبل أول بناء فعلي

بصراحة تامة، هذه النقاط لم تُختبر ببناء فعلي (بيئتي الحالية بدون اتصال إنترنت
لتشغيل Gradle)، فيرجى التحقق منها أول ما تبني المشروع على GitHub Actions:

1. **مكتبة FFmpeg**: تم اختيار `com.moizhassan.ffmpeg:ffmpeg-kit-16kb:6.1.1`
   (Maven Central) كونها نسخة مُحدَّثة متوافقة مع Android 15/16KB page size،
   بعد أن توقف الدعم عن `arthenica/ffmpeg-kit` الأصلية (أبريل 2025). يوجد أيضًا
   مشروع خلَف باسم **FFmpegKitNext** لم أتمكن من تأكيد إحداثيات Maven الدقيقة
   له بسبب قيود البحث — يستحق المراجعة كخيار بديل لاحقًا.
2. **أسماء دوال FFmpegKit Java API** في `FFprobeAdapter.kt` (مثل
   `stream.getProperty`, `getStringProperty`, `stream.bitrate`) مكتوبة بأفضل
   معرفتي ببنية المكتبة، لكن يجب مطابقتها مع التوثيق الفعلي للنسخة المثبتة
   عند أول تجربة بناء — من المحتمل تحتاج تعديلات بسيطة بأسماء الخصائص.
3. **compileSdk 35 / NDK**: عمال GitHub Actions (`ubuntu-latest`) عادة تأتي
   مع Android SDK مثبت مسبقًا، لكن يجب التأكد من توفر `platform-35` و
   `build-tools` المطلوبة، وقد تحتاج خطوة `android-actions/setup-android` إذا
   فشل البناء لهذا السبب.
4. **دعم SAF في FFmpegKit**: `SafVideoResolver` يحاول استخدام
   `FFmpegKitConfig.getSafParameterForRead(context, uri)` لتمرير الفيديو
   مباشرة لـ FFmpeg بدون نسخه. لم أتمكن من التأكد 100% أن هذه الدالة
   بالضبط موجودة في fork `ffmpeg-kit-16kb` المستخدم — لو فشلت أو لم تكن
   موجودة (خطأ تجميع)، يوجد مسار بديل جاهز بالكود (نسخ الملف لمجلد cache)
   لكن قد تحتاج حذف استدعاء `getSafParameterForRead` مؤقتًا حتى تتأكد من
   الاسم الصحيح بالتوثيق الفعلي للمكتبة المثبتة.

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
