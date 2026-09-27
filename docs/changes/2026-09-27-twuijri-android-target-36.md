# تطبيق أندرويد يستهدف Android 16 (API 36)
المسؤول: twuijri · الفرع: chore/android-target-36 · الحالة: review

## المشكلة والهدف
Google Play لا يقبل تطبيقًا جديدًا ولا تحديثًا منذ ٣١ أغسطس ٢٠٢٦ إلا إذا استهدف **Android 16 (API 36)**
(developer.android.com/google/play/requirements/target-sdk). تطبيق أندرويد (`apps/android`) كان يبني على
`compileSdk = 35` ويستهدف `targetSdk = 35`، فأول رفع إلى Play كان سيُرفض، و`docs/store/google/README.md` كان يذكرها
عائقًا قبل الرفع الأول.

الهدف: رفع `compileSdk` و`targetSdk` إلى 36، ومراجعة كل تغيير سلوك في Android 16 يسري على التطبيقات التي تستهدف 36
مقابل الكود، وإصلاح ما ينكسر، دون تغيير اسم الحزمة ولا التوقيع ولا الترقيم في نسخة GitHub.

## القرار والموافقات
- `compileSdk = 36` و`targetSdk = 36` في `app/build.gradle.kts`، و`tools:targetApi="36"` في المانيفست. لم يلزم رفع
  AGP (8.9.2 يبني على 36 دون تحذير) ولا Kotlin ولا Compose ولا أي مكتبة.
- **اختبارات JVM (Robolectric) تبقى على إطار API 35** بملف `app/src/test/resources/robolectric.properties` (`sdk=35`):
  Robolectric 4.16 لا يشغّل إطار API 36 إلا على Java 21، والمشروع يبني ويختبر على Java 17 (CI `android.yml` ومحليًا).
  اختبارات اللقطات وواجهة Compose في `src/testDebug` كانت أصلًا مثبّتة على `@Config(sdk = [35])`. للتحقق شغّلتها
  كلها مرة واحدة على إطار 36 محليًا (انظر الفحوص) دون أن يدخل ذلك في المستودع.
- `play-upload.yml`: فحص الهدف صار **خطأً يوقف الرفع** بدل التحذير: التطبيق يستهدف 36 الآن، فهدف أقل يعني أن
  الإعداد رجع، وPlay سيرفضه على كل حال.
- نسخة GitHub وPlay بإعداد واحد (36): لا فرع بناء منفصل. اسم الحزمة `com.twuijri.corehub` والتوقيع و`versionCode`/
  `versionName` كما هي.

### تغييرات Android 16 التي تسري عند استهداف 36، وحال التطبيق مع كل منها

| التغيير | ما في الكود | النتيجة |
|---|---|---|
| **Edge-to-edge إلزامي** (`windowOptOutEdgeToEdgeEnforcement` يُتجاهَل) | لا يستعمل التطبيق الخروج أصلًا؛ `MainActivity` يستدعي `enableEdgeToEdge()`، والشاشات تحسب الحواف: `statusBarsPadding`/`navigationBarsPadding`/`imePadding` في `Shell.kt` و`MainActivity`، و`safeDrawingPadding` في `ConnectScreen`. `android:statusBarColor`/`navigationBarColor` في `themes.xml` بلا أثر منذ 35 (شفافة أصلًا) | لا تغيير. لقطات 36 مطابقة بصريًا للقطات 35 |
| **الرجوع التنبّؤي (Predictive back)**: `onBackPressed()` لا يُستدعى و`KEYCODE_BACK` لا يُرسَل، و`enableOnBackInvokedCallback` مفعّل افتراضيًا | لا `onBackPressed` ولا `KEYCODE_BACK` في الكود. الرجوع كله `BackHandler` (activity-compose 1.10.1، يسجّل `OnBackInvokedCallback`) في `Shell.kt` (الدرج والمكدّس) و`FilesPage.kt` (المجلد الأب)، والنوافذ `Dialog`/`ModalBottomSheet` من Compose تتعامل مع الرجوع بنفسها | لا تغيير. حين لا يكون هناك ما يُرجَع إليه يعرض النظام حركة «الرجوع إلى الشاشة الرئيسية» |
| **الشاشات الكبيرة (sw ≥ 600dp)**: تجاهُل `screenOrientation` و`resizeableActivity=false` و`min/maxAspectRatio` | `MainActivity` لا يقيّد الاتجاه ولا الحجم. ماسح QR (`CaptureActivity` من zxing) عليه `fullSensor` (يتبع دوران الجهاز)؛ على اللوحي يُتجاهل ويبقى قابلًا للدوران | لا تغيير. تحذير lint `DiscouragedApi` على هذا السطر قديم وغير مانع |
| **ارتفاع السطر الأنيق للنصوص** (`elegantTextHeight` يُتجاهَل، الافتراضي أنيق للعربية وغيرها) | لا يضبطه التطبيق؛ الافتراضي أنيق منذ استهداف 35 | لا تغيير في العربية (اللقطات العربية على 36 مطابقة) |
| **حصص JobScheduler وتقارير المهام المهجورة** | عمل دوري واحد عبر WorkManager (`NoticeWorker` كل ١٥ دقيقة، `Notices.kt`) قصير؛ الإشعارات الأساسية عبر FCM | لا تغيير. قد يتأخر العمل الدوري أكثر في وضع توفير الطاقة، كما قبل |
| **`scheduleAtFixedRate`**: تنفيذ فائت واحد فقط بعد العودة | غير مستعمل | لا ينطبق |
| **ترتيب أولوية البثّ المرتّب** بين العمليات | لا `BroadcastReceiver` في التطبيق (خدمة FCM فقط) | لا ينطبق |
| **حماية إعادة توجيه الـIntent** (Intent داخل Intent) | لا يُطلق التطبيق Intent مأخوذًا من extras؛ المشاركة تقرأ `EXTRA_STREAM` (Uri) و`EXTRA_TEXT`، والإشعار يقرأ نصوصًا فقط | لا ينطبق |
| **تغييرات ART الداخلية** (واجهات غير عامة) | لا انعكاس (reflection) على واجهات النظام؛ قواعد ProGuard لـ kotlinx.serialization وSocket.IO فقط | لا ينطبق |
| **الإعلانات الإتاحية المقاطِعة** (`announceForAccessibility` مُهمَل) | غير مستعمل | لا ينطبق |
| **خدمات المقدّمة (FGS) والمنبّهات** | لا خدمة مقدّمة ولا `AlarmManager` | لا ينطبق |
| **صلاحيات الصحة والبلوتوث وMediaStore** | غير مستعملة | لا ينطبق |
| **حماية الشبكة المحلية** (`ACCESS_LOCAL_NETWORK`) | الهب كثيرًا على الشبكة المنزلية (`http://192.168.x.x`) | في Android 16 اختيارية للاختبار فقط ولا تُفرض عند 36؛ تُراقب لإصدار لاحق (مخاطر) |
| **محاذاة الصفحات 16 KB** (شرط Play للمكتبات الأصلية، لا للهدف) | مكتبتان أصليتان من androidx (`graphics.path`, `datastore_shared_counter`) | محاذاة 16 KB سليمة (`zipalign -P 16` و`LOAD 0x4000`) |

## العقد
لا شيء.

## الملفات والتأثير
- `apps/android/app/build.gradle.kts`: `compileSdk`/`targetSdk` من 35 إلى 36.
- `apps/android/app/src/main/AndroidManifest.xml`: `tools:targetApi="36"`.
- `apps/android/app/src/test/resources/robolectric.properties` (جديد): `sdk=35` لاختبارات JVM مع سبب ذلك.
- `.github/workflows/play-upload.yml`: هدف أقل من 36 يوقف الرفع (كان تحذيرًا).
- `docs/store/google/README.md`: فقرة «Target API level» لم تعد عائقًا، وحُذف بندها من «What the owner still does».
- `docs/STATUS.md`: سطر أندرويد يذكر الاستهداف 36.

## الفحوص (الأوامر ونواتجها الفعلية)
بناء APK الإصدار وAPK التطوير والحزمة لـPlay على API 36 (JDK 17، `platforms;android-36` و`build-tools;36.0.0`):

```
$ ./gradlew --max-workers=2 assembleDebug assembleRelease
BUILD SUCCESSFUL in 1m 46s
$ aapt2 dump badging app/build/outputs/apk/release/app-release-unsigned.apk
package: name='com.twuijri.corehub' versionCode='1' versionName='1.1.4' platformBuildVersionName='16' platformBuildVersionCode='36' compileSdkVersion='36' compileSdkVersionCodename='16'
targetSdkVersion:'36'
$ zipalign -c -P 16 -v 4 app-release-unsigned.apk
 lib/arm64-v8a/libandroidx.graphics.path.so (OK) … lib/x86_64/libdatastore_shared_counter.so (OK)
Verification successful
$ ./gradlew --max-workers=2 bundleRelease -Pcorehub.selfUpdate=false
BUILD SUCCESSFUL in 55s
merged manifest: package="com.twuijri.corehub" versionName="1.1.4" targetSdkVersion="36"; REQUEST_INSTALL_PACKAGES: 0
```

اختبارات الوحدة وlint كما يشغّلها CI (`android.yml`):

```
$ ./gradlew --max-workers=2 test lint
BUILD SUCCESSFUL in 2m 4s
testDebugUnitTest:   tests 440 skipped 2 failures 0 errors 0
testReleaseUnitTest: tests 381 skipped 2 failures 0 errors 0
lint: 0 errors (79 warnings, none new to API 36: UseKtx, UnusedResources, PluralsCandidate, …)
```

قبل ملف `robolectric.properties` فشل اختباران يعملان على Robolectric بهذا بالضبط:
`Failed to create a Robolectric sandbox: Android SDK 36 requires Java 21 (have Java 17)`.

تحقق إضافي لمرة واحدة على **إطار API 36 نفسه** (لا يدخل المستودع): شغّلت الاختبارات بـJava 25 من Android Studio
(`robolectric.enabledSdks=36`) وبعد تحويل `@Config(sdk = [35])` مؤقتًا إلى 36 ثم إرجاعه:

```
testDebugUnitTest (test/, SDK 36):  tests 381 skipped 2 failures 0 errors 0
ui.* + shots.ScreenShots + shots.PlayStoreShots (SDK 36):
  KeyboardDismissTest 5/5, KitTest 3/3, ThemeContrastTest 2/2, ScreenShots 4/4, PlayStoreShots 2/2 … 0 failures
```

وقارنت اللقطات الـ٥٢ (فاتح/داكن × عربي/إنجليزي × ١٣ شاشة) على 36 مع نفسها على 35: كلها تختلف في بكسلات
التنعيم فقط، والمقارنة جنبًا إلى جنب (المحادثة بالعربية، الدرج بالإنجليزية) متطابقة في التخطيط والحواف والنص.

**لم أشغّله**: محاكٍ أو جهاز حقيقي بـAndroid 16. لا صورة نظام 36 على الجهاز (الموجود صورة TV لـAPI 33)، والذاكرة
المتاحة (~٨ GB مع حدّ الحارس ٦ GB) لا تتحمّل محاكيًا. حركة الرجوع التنبّؤي والتدوير على لوحي لم تُجرَّب على جهاز.

## المخاطر والرجوع
- التغيير يمسّ نسخة GitHub أيضًا (الهدف نفسه). الخطر الأعلى نظريًا: الرجوع التنبّؤي وحواف الشاشة؛ الكود لا يستعمل
  الواجهات التي تغيّرت، واللقطات على 36 مطابقة. أول تجربة على جهاز Android 16 حقيقي مطلوبة من المالك.
- حماية الشبكة المحلية: حين يفرضها Android لاحقًا (استهداف 37 على الأرجح) سيحتاج الاتصال بهب على الشبكة المنزلية
  إذنًا جديدًا؛ خارج هذه المهمة.
- الرجوع: إعادة `compileSdk`/`targetSdk` إلى 35 وحذف `robolectric.properties`؛ لكن Play لن يقبل رفعًا بهدف 35.
- لا تغيير في العقد ولا في البيانات ولا في أسماء ملفات الإصدار؛ التطبيق القديم والهب الجديد (والعكس) كما هما.

## التسليم والخطوة التالية
PR إلى `main`. بعده: تجربة المالك على جهاز Android 16 (الرجوع بالإيماءة، لوحة المفاتيح في المحادثة، ماسح QR)، ثم
`play-upload.yml` بمسار `internal`.
