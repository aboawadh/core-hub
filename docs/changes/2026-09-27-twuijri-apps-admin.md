# الدفعتان 12 و14: صفحات الإدارة في التطبيقين
المسؤول: twuijri · الفرع: night/apps-admin · الحالة: in-progress

## المشكلة والهدف
قائمة الفجوات (ليلة التطبيقات) تركت صفحات الإدارة ناقصة على الجوال: «المستخدمون» بلا أقفال ولا حسابات المراسلة المرتبطة، وتغيير
المشرف إلى عضو يُرسل الدور وحده فيرفضه المركز؛ «البروفايلات» في الآيفون قائمة وإنشاء فقط وفي الأندرويد قائمة فقط (لا إعادة تسمية
ولا أرشفة ولا تصدير ولا استيراد)؛ «ربط الأجهزة» بلا مرسِلات الإشعارات؛ وطلبات الاقتران لا تظهر في ورقة «بانتظارك». الهدف أن يدير
المالك أو المشرف هذا كله من الجوال كما في الويب، بمكوّنات كل تطبيق وأرقام لاتينية. وجهات الإشعار بُنيت في الدفعة 10 فلم أُعِدها.

## القرار والموافقات
- **المستخدمون** كما في الويب: الصف لا يعرض إلا ما يقبله المركز — حساب المالك لا يعدّله مشرف (سطر يقول ذلك)، المالك يغيّر كلمة
  مروره هو، ولا أحد يعطّل نفسه أو يحذفها. الإجراءات: كلمة مرور (تُكتب مرة وتُمسح فور الإرسال)، ترقية إلى مشرف، **إرجاع إلى عضو مع
  اختيار بروفايلاته في الخطوة نفسها** (المركز يرفض الدور وحده — كان خطأً في التطبيقين)، بروفايلات العضو، تعطيل/تفعيل، حذف بعد سؤال.
  تحتها: **حسابات المراسلة المرتبطة** لكل الناس (إزالة بعد سؤال) و**الأقفال** (العنوان، السبب، المحاولات، حتى متى).
- **فكّ قفل عنوان واحد** إضافة على الويب (الويب «فكّ الكل» فقط، والعقد يقبل `ip`) — مقترح، للمالك أن يؤكد.
- **البروفايلات**: جديد من الصفر أو نسخة من بروفايل يُختار (المعرّف يتبع الاسم حتى يُكتب)، إعادة تسمية (المعرّف لا يتغيّر، وكلمات
  Hermes نفسها إن رفض)، **أرشفة** بعد سؤال يقول ما يبقى (كلمة المركز والويب «أرشفة» لا «حذف»، لأن الصفوف لا تُمحى)، **تصدير** يسأل
  مع المزوّدين أو بدونهم (تحذير المفاتيح المكشوفة)، يتابع المهمة، ينزّل الأرشيف ويفتح المشاركة (في الأندرويد أيضًا «حفظ في
  التنزيلات» — مقترح)، و**استيراد** من ملفات الجوال: يقترح المعرّف والاسم من اسم الملف، يقول ما سيُنشأ ويسأل مرة أخيرة، يرفع الملف
  (`purpose: import`، قطعًا فوق 25 ميغابايت) ويتابع المهمة.
- التصدير والاستيراد `x-scope: global` في العقد، لكن المركز يسجّل المهمة والأرشيف في بروفايل الطلب (`X-Hub-Profile`) كما يرسلها
  الويب؛ فتُضاف الترويسة في إعداد الطلب كما فعلت الدفعة 10 (iOS `inProfile`، Android معترض OkHttp)، دون كتابة أي مسار.
- **مرسِلات الإشعارات** (للمشرف، مطويّة أسفل «ربط الأجهزة» كما في الويب؛ في الآيفون أسفل تبويب «الأجهزة»): حالة كل مرسِل ومصدره
  وعدد أجهزته وما حُفظ بلا سرّ؛ الإعداد بالملف أولًا (JSON حساب الخدمة أو `AuthKey_….p8` من ملفات الجوال، يُفحص على الجوال: ملف
  google-services.json وغيره يُقال عنه ذلك، ومعرّف المفتاح من اسم الملف)، أو اللصق، ومعرّف المفتاح والفريق والحزمة والبيئة لـAPNs؛
  السرّ المحفوظ يبقى إن تُرك الحقل فارغًا، ولا يُعرض أبدًا؛ حكم المركز بعد الحفظ؛ و**«حذف المحفوظ» بعد سؤال** (الويب بلا سؤال —
  مقترح). Web Push ومرسِل متغيرات البيئة لا يُعدّلان من هنا.
- **طلبات الاقتران في ورقة «بانتظارك»** (للمشرف): وكيل البروفايل الذي له قنوات، طلباته مع الموافقة والرفض، والجرس يعدّها مع الموافقات.
- ملفات مشتركة لمستها لأن المهمة تحتاجها (إضافات فقط): `HubFailure.swift` و`Hub.kt` (`details.message` — كلمات Hermes أو Apple/Google
  عند الرفض)، `ShellViewModel.kt` (`loadProfiles` صارت عامة، وحالة طلبات الاقتران)، `PendingSheet.kt` و`PendingList.swift` (القائمة
  والعدّ)، `AdminPages.kt` (نُقلت صفحة المستخدمين إلى ملفها، وأُضيف قسم المرسِلات)، `DeviceCardsList.swift` (القسم).

## العقد (ما تغيّر في packages/contracts، أو «لا شيء»)
لا شيء.

## الملفات والتأثير
- Android (`ui/screens/`): جديد `settings/AdminKit.kt` (القواعد `PeopleRules` و`ProfileRules` و`PushSenderRules` و`PairingRules`،
  والعملاء `AdminApis`/`AdminTwoOps`)، `settings/PeoplePage.kt`، `settings/PeopleSections.kt`، `settings/ProfileTransfer.kt`،
  `settings/PushSendersCard.kt`، `PendingPairingCard.kt`؛ أُعيدت كتابة `settings/ProfilesPage.kt`؛ عُدّلت `AdminPages.kt`،
  `PendingSheet.kt`، `ShellViewModel.kt`، `data/Hub.kt`؛ `res/values*/strings_admin.xml`.
- iOS: جديد `Settings/Pages/AdminPagesRules.swift`، `PeopleSections.swift`، `ProfileTransfer.swift`، `PushSendersSection.swift`،
  `Shell/PendingPairingCard.swift`؛ أُعيدت كتابة `PeoplePage.swift` و`WorkspacesPage.swift`؛ عُدّلت `DeviceCardsList.swift`،
  `Shell/PendingList.swift`، `Hub/HubFailure.swift`؛ `i18n/admin.{en,ar}.json`.
- اختبارات: Android `parity/AdminPagesTest.kt` (القواعد والطلبات أمام خادم مُبرمج) و`shots/AdminShots.kt`؛ iOS `CoreHubTests/AdminPagesTests.swift`.
- `docs/STATUS.md` وفهرس الليلة.

## الفحوص (الأوامر ونواتجها الفعلية)
```
$ gradlew :app:testDebugUnitTest --tests AdminPagesTest --tests ModelsAdminTest --tests StringsParityTest --tests 'nav.*'   (mj-run, JDK 17)
TEST-hub.core.android.parity.AdminPagesTest.xml tests="13" skipped="0" failures="0" errors="0"
TEST-hub.core.android.parity.ModelsAdminTest.xml tests="7" skipped="0" failures="0" errors="0"
TEST-hub.core.android.nav.NavigationParityTest.xml tests="10" skipped="0" failures="0" errors="0"
TEST-hub.core.android.nav.AppPathsTest.xml tests="4" skipped="0" failures="0" errors="0"
TEST-hub.core.android.ui.StringsParityTest.xml tests="4" skipped="0" failures="0" errors="0"
$ gradlew :app:testDebugUnitTest --tests 'hub.core.android.shots.AdminShots'
TEST-hub.core.android.shots.AdminShots.xml tests="2" skipped="0" failures="0" errors="0"
$ gradlew :app:lintDebug          → BUILD SUCCESSFUL
$ pnpm i18n:check                 → ios: 2001 keys, ar/en in parity … android: Arabic resources use Latin digits … OK
$ pnpm contracts:check-clients    → check-clients  OK — 971 client file(s) scanned, 254 contract path(s) known.
$ pnpm lint                       → All matched files use Prettier code style!
$ pnpm typecheck                  → EXIT 0
```
صور الأندرويد: `apps/android/app/build/shots/admin/android-{people,people-lockouts,profiles,push-senders}-{light-en,dark-ar}.png`
(راجعتُها؛ رقم واتساب في سطر الحساب المرتبط انقلب في العربية فصار داخل عزل اتجاه). الأوراق نافذة مستقلة فلا تظهر في الصورة؛ الاختبار
يتحقق من أنها تُفتح (بروفايل جديد، إعداد APNs).

iOS: لا يُبنى على لينكس؛ نتيجة `ios.yml` على الفرع تُضاف هنا.

## المخاطر والرجوع
- صفحات iOS لم تُبنَ محليًا؛ تعتمد على مهمة iOS في CI. لم تُجرَّب على هاتفي المالك.
- طلبات الاقتران تُقرأ لوكيل البروفايل الحالي فقط (كالويب)، ولا حدث لها: تُحدَّث عند فتح الورقة ومع كل تحديث للموافقات.
- الرجوع: `git revert` لالتزامات هذا الفرع في فرع الليلة؛ لا عقد ولا بيانات.

## التسليم والخطوة التالية
يُدمج في `night/2026-09-27-apps` (#181) بعد نجاح iOS على الفرع.
