# الوارد وإعدادات الشخص نفسه على الجوال (الدفعة 4)
المسؤول: twuijri · الفرع: night/apps-inbox-settings · الحالة: done

## المشكلة والهدف
قائمة الفجوات (الدفعة 4) قالت: وارد الآيفون للقراءة فقط (لا تعليم مقروء، ولا «علّم الكل»، ولا فتح ما يشير إليه
الإشعار)؛ حساب الأندرويد للعرض فقط (لا اسم ولا كلمة مرور)؛ «العرض» في الأندرويد لغة وسمة فقط بلا تفضيلات الهب التي
في الآيفون؛ «الخصوصية» في الأندرويد تعرض الرموز ولا تلغيها («تُلغى من الويب»)؛ و«حسابات المراسلة» (ربط تيليجرام
وواتساب) غائبة عن التطبيقين. الهدف: كل ما يفعله الشخص لحسابه بنفسه يُفعل من الجوال دون المتصفح، مثل الويب، في التطبيقين.

## القرار والموافقات
- **الوارد (التطبيقان):** تبويبان «الوارد | الإعدادات» (كما كان الأندرويد). الوارد: «الكل | غير المقروء» (`notify.listNotices`
  مع `unread`)، عدد غير المقروء، «علّم الكل مقروءًا» (`notify.markAllRead`، معطّل حين لا شيء)، النقر يعلّم الإشعار مقروءًا
  (`notify.updateNotice`) ثم يفتح ما يشير إليه (محادثة، اللوحة، الجدولة) بنفس توجيه إشعار الدفع؛ وتعليم مقروء/غير مقروء لكل
  إشعار (iOS سحب وضغطة مطوّلة، Android زر بجانب الصف)؛ «المزيد» بالمؤشر. التعليم يظهر فورًا، ورفض الهب يعيد القائمة كما
  عنده ويقول السبب.
- **الحساب (التطبيقان):** الصورة (اختيار صورة من الجهاز تُصغَّر إلى 512 بكسل للضلع الأطول وتُرسل JPEG ≤ 512 KB عبر
  `auth.updateMe` `avatar.kind=image`، و«أزل الصورة» = `kind=generated`)، الاسم الظاهر (يُقصّ، ≤ 80، لا يُرسل إن لم يتغير)،
  كلمة المرور (الحالية + الجديدة + تكرارها، ≥ 8، تُفرغ الحقول بعد النجاح؛ الهب يُخرج بقية الأجهزة)، حقائق (اسم المستخدم، الدور،
  البروفايلات)، و**حسابات المراسلة**: القائمة، «اربط حسابًا» (رمز ربط + أمر يُنسخ + وقت انتهاء، وتُقرأ القائمة كل 3 ثوانٍ حتى
  يظهر الربط)، وفكّ الربط بعد سؤال. الخروج بقي في حساب الأندرويد.
  - مقترح — للمالك أن يؤكد: **صورة الحساب** والاسم على الجوال مع أن صفحة حساب الويب تعرضهما دون تعديل؛ العقد يسمح بهما
    (`auth.updateMe`) والطلب ذكرهما.
- **العرض (الأندرويد):** قسم «المحادثات» بتفضيلات الهب نفسها التي في الآيفون: فتح الروابط، الإرسال أثناء عمل الوكيل،
  إظهار التفكير، إظهار استدعاءات الأدوات، المحادثة المضغوطة، حجم النص (0.85–1.45 بخطوات 5%). كل تغيير يُحفظ فورًا (كمفاتيح
  الويب)، والرفض يعيد القيمة ويقول السبب. «السمة» بقيت بلا هذا القسم. لا شيء في العقد لتقليل الحركة أو المنطقة الزمنية أو
  شكل التاريخ، فلم يُضف.
- **الخصوصية (التطبيقان):** كل رمز بنوعه (جهاز مقترن / رمز تطبيق)، صلاحياته، آخر استعمال، الانتهاء؛ **الإلغاء بعد سؤال**
  يقول ما سيحدث (الجهاز يُفصل) — الآيفون كان يلغي بسحب دون سؤال؛ ومفتاح Hermes «إخفاء المعرّفات عن النموذج»
  (`privacy.redact_pii` عبر `agents.getSettings/updateSettings`، §58) كما في الويب، يغيّره المشرف فقط، ويظهر فقط حيث في
  البروفايل Hermes؛ وملاحظة أن المتصفحات لا تُسرد.
- **لا يوجد في العقد:** حذف إشعار أو مسح الوارد، قائمة جلسات المتصفح أو «أخرج الأجهزة الأخرى» منفصلة (تغيير كلمة المرور
  يفعلها). لم أخترع عمليات.
- نصوص المنطقة: iOS `i18n/own_settings.{en,ar}.json`، Android `values*/strings_own_settings.xml`؛ حُذف النص القديم
  `privacy_tokens` («تُلغى من الويب») من `strings.xml` لأنه لم يعد صحيحًا ولا مستعملًا.

## العقد (ما تغيّر في packages/contracts، أو «لا شيء»)
لا شيء.

## الملفات والتأثير
- iOS: `Settings/Pages/{AccountPage,NotificationsPage,PrivacyPage}.swift` (أُعيدت كتابتها)، جديد `Settings/Pages/OwnSettingsRules.swift`
  و`ChannelAccountsSection.swift`، `i18n/own_settings.{en,ar}.json`، اختبار `CoreHubTests/OwnSettingsTests.swift`.
  (`DisplayPage.swift` كانت كاملة؛ لم تتغير.)
- Android: `ui/screens/settings/{AccountPage,DisplayPage,NotificationsPage,PrivacyPage}.kt`، جديد `settings/OwnSettings.kt`
  (القواعد + `OwnSettingsOps`) و`settings/ChannelAccountsCard.kt`، `res/values*/strings_own_settings.xml`، حذف سطر `privacy_tokens`
  من `strings.xml` (الإنجليزي والعربي)؛ اختبار `parity/OwnSettingsTest.kt` وصور `shots/OwnSettingsShots.kt`.
- لم أمسّ السجلّ ولا اختبارات التكافؤ ولا صفحات الجدولة.

## الفحوص (الأوامر ونواتجها الفعلية)
```
$ gradle :app:testDebugUnitTest :app:lintDebug   (mj-run, JDK 17)
BUILD SUCCESSFUL in 42s
tests 282 skipped 2 failed 0
lint: 0 errors, 67 warnings   (ثم أزلت تحذيرات هذه المهمة: privacy_tokens غير مستعمل، نصوص غير مستعملة، mutableIntStateOf)

$ gradle :app:testDebugUnitTest --tests '*OwnSettings*' '*StringsParityTest*' '*NavigationParityTest*' '*ScreenShots*'
BUILD SUCCESSFUL in 25s
OwnSettingsShots tests=2 failures=0 · NavigationParityTest tests=10 failures=0 · StringsParityTest tests=4 failures=0
OwnSettingsTest tests=13 failures=0 · ScreenShots tests=4 failures=0

$ pnpm i18n:check              → i18n:check  ios: 923 keys, ar/en in parity … android: Arabic resources use Latin digits … OK
$ pnpm contracts:check-clients → check-clients  OK — 891 client file(s) scanned, 254 contract path(s) known.
$ pnpm lint                    → All matched files use Prettier code style!
$ pnpm typecheck               → exit=0

$ gh workflow run ios.yml --ref night/apps-inbox-settings   (run 36276796078)
X OwnSettingsTests.testTheInboxMarksAtOnceAndReadsAgainWhenTheHubRefuses — XCTAssertNotNil failed
  (قراءة القائمة بعد الرفض كانت تمحو سبب الرفض؛ صُحّح في التطبيقين: السبب يبقى بعد إعادة القراءة)

$ gh workflow run ios.yml --ref night/apps-inbox-settings   (run 36277459665)
✓ Build and test on the iOS simulator
Executed 214 tests, with 0 failures (0 unexpected)
```
صور الأندرويد: `apps/android/app/build/shots/own-settings/android-{account,account-channels,display,privacy,inbox}-{light-en,dark-ar}.png`
(نظرتُ فيها؛ صف أدوات الوارد كان يضيق فيكسر «2 unread» حرفًا حرفًا، فصار سطرين).

## المخاطر والرجوع
- التواريخ في الأندرويد بالعربية تظهر بأسماء أشهر إنجليزية (`localTime` يستعمل لغة الهاتف لا لغة التطبيق) — موجود قبل هذه المهمة،
  يخص مهمة الأرقام/التواريخ.
- `SettingValues` و`agentText` و`localTime` تُستعمل من ملفات دفعات أخرى؛ إن نُقلت يلزم تحديث الاستيراد.
- صورة الحساب على iOS لا تُصحّح اتجاه الصورة يدويًا (UIImage يحترم الاتجاه عند الرسم)؛ على Android 8/8.1 (API 26–27) لا تُقرأ
  بيانات الاتجاه، فقد تظهر صورة الكاميرا مائلة هناك فقط.
- لم يُجرَّب شيء على هاتفي المالك؛ iOS على محاكي CI فقط.
- الرجوع: `git revert` لالتزامات هذا الفرع في فرع الليلة؛ لا عقد ولا بيانات.

## التسليم والخطوة التالية
دُمج في `night/2026-09-27-apps` (#181). التالي: تجربة المالك على الهاتفين، وتأكيد قرار صورة الحساب والاسم على الجوال.
