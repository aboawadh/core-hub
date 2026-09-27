# الدفعة 10: المعرفة واستخدام المهارات وإضافات المركز ووجهات الإشعار في التطبيقين
المسؤول: twuijri · الفرع: night/apps-knowledge · الحالة: done

## المشكلة والهدف
في قائمة الفجوات (ليلة التطبيقات) كانت أربع صفحات إعدادات غير أصلية أو ناقصة: في الأندرويد تفتح «المعرفة» و«استخدام المهارات»
و«الإضافات» و«وجهات الإشعار» صفحة الويب (`OnTheWebPage`)، وفي الآيفون «استخدام المهارات» شاشة «قريبًا»، و«المعرفة» قائمة بلا
أنواع ولا بحث، و«وجهات الإشعار» قائمة وإرسال تجريبي فقط. الهدف: أن تعمل الصفحات الأربع على الجوال كما على الويب، من مكوّنات كل
تطبيق، بتخطيط مضغوط وأرقام لاتينية.

## القرار والموافقات
- **المعرفة**: كما في الويب تمامًا — قائمة واحدة (اليوميات والملاحظات والملفات) الأحدث أولًا، شرائح النوع، بحث يُرسل بعد توقف
  الكتابة، والصفحة التالية عند نهاية القائمة. العقد لا يملك إنشاء أو رفع أو حذف أو إعادة فهرسة لهذه الصفوف (والويب لا يعرض شيئًا
  منها)، فلم أخترع شيئًا؛ ملفات مساحة العمل (`knowledge.*WorkspaceFile*`) هي صفحة «الملفات» في الدفعة 11.
- **استخدام المهارات**: المدة (7/30/90/365 يومًا)، كل البروفايلات أو واحد، وكيل واحد أو الكل، منذ متى يعدّ المركز، أربعة
  إجماليات، الرسم اليومي كقائمة أعمدة مضغوطة مع مهارات اليوم، أبرز المهارات (الاستخدامات والحصة وآخر استخدام)، والمهارات المفعّلة التي
  لم يحمّلها أي دور. الوكيل المختار الذي خرج من المدة يبقى قابلًا للاختيار (كما في الويب).
- **إضافات المركز**: القائمة بالنوع والإصدار والحالة. العقد فيه `plugins.list` فقط — لا تثبيت ولا تفعيل ولا إعدادات لإضافات
  المركز — والحالة الفارغة تقول ذلك. التفعيل والإعدادات المطلوبة في المهمة غير موجودة في العقد ولا في الويب.
- **وجهات الإشعار** (notify، §59) كاملة: التفعيل، الإرسال التجريبي مع متابعة مهمته حتى النتيجة، آخر التسليمات (تُتابَع ما دام
  شيء ينتظر) وإعادة إرسال ما يجوز، الإضافة والتعديل (العنوان مع رفض المركز بكلماتنا، العناوين الخاصة، الأحداث من كتالوج المركز مع
  تصفية، كل البروفايلات أو بعضها، نص الرسائل، عدد المحاولات، السرّ: إبقاء أو جديد أو إيقاف)، والحذف بعد سؤال. السرّ الجديد يُصنع
  في التطبيق ويظهر مرة واحدة مع زر نسخ (في الآيفون تتحوّل ورقة التعديل إليه بعد الحفظ، حتى لا تُفتح ورقتان متتاليتان).
- **البروفايل في طلبات وجهات الإشعار**: عمليات notify هذه `x-scope: global` في العقد (بلا معامل بروفايل)، لكن المركز يحفظ كل وجهة
  في بروفايل الطلب من الترويسة `X-Hub-Profile` كما يرسلها الويب. العميلان المولَّدان لا يرسلانها، فتُضاف الترويسة في إعداد الطلب
  (iOS `inProfile`، Android عميل OkHttp بمعترض) دون كتابة أي مسار. مقترح — للمالك أن يؤكد؛ والأنظف لاحقًا أن يعلن العقد معامل
  الترويسة لهذه العمليات.
- **وجهات Hermes الواردة (§97)** مكانها صفحة قنوات الوكيل، وهي من نطاق الدفعة 9 (الوكلاء II) التي يبنيها وكيل آخر الآن، فلم ألمسها.
- «التحديثات» بقيت كما هي (رف الإصدارات على الويب فقط حسب قائمة الفجوات)، و«الملفات» للدفعة 11.

## العقد (ما تغيّر في packages/contracts، أو «لا شيء»)
لا شيء.

## الملفات والتأثير
- Android (`ui/screens/settings/`): `HubDataKit.kt` (العملاء والقواعد: `HubDataApis`، `HubDataOps`، `KnowledgeRules`،
  `SkillsUsageRules`، `NotifyWebhookRules`)، `KnowledgePage.kt`، `SkillsUsagePage.kt`، `HubPluginsPage.kt`، `WebhooksPage.kt`،
  `WebhookSheet.kt` — حُذف `native = false` من الأربع؛ `res/values*/strings_knowledge.xml`.
- iOS (`Settings/Pages/`): `HubDataRules.swift`، `KnowledgePage.swift`، `SkillsUsagePage.swift`، `HubPluginsPage.swift`،
  `WebhooksPage.swift`، `WebhookSheet.swift` — حُذف `native: false` من «استخدام المهارات»؛ `i18n/knowledge.{en,ar}.json`.
- اختبارات: Android `parity/KnowledgeReportsTest.kt` (القواعد والطلبات أمام خادم مُبرمج) و`shots/KnowledgeShots.kt` (الصفحات
  الأربع فاتحًا بالإنجليزية وداكنًا بالعربية، مع إرسال تجريبي وفتح التسليمات)؛ iOS `CoreHubTests/HubDataRulesTests.swift`.
- `docs/STATUS.md` وفهرس الليلة.

## الفحوص (الأوامر ونواتجها الفعلية)
```
$ gradlew :app:testDebugUnitTest --tests KnowledgeReportsTest --tests KnowledgeShots --tests NavigationParityTest --tests StringsParityTest :app:lintDebug   (mj-run, JDK 17)
EXIT 0
TEST-hub.core.android.parity.KnowledgeReportsTest.xml tests="12" skipped="0" failures="0" errors="0"
TEST-hub.core.android.shots.KnowledgeShots.xml tests="2" skipped="0" failures="0" errors="0"
TEST-hub.core.android.nav.NavigationParityTest.xml tests="10" skipped="0" failures="0" errors="0"
TEST-hub.core.android.ui.StringsParityTest.xml tests="4" skipped="0" failures="0" errors="0"

$ pnpm i18n:check              → i18n:check  ios: 1455 keys, ar/en in parity … android: Arabic resources use Latin digits … OK
$ pnpm contracts:check-clients → check-clients  OK — 943 client file(s) scanned, 254 contract path(s) known.
$ pnpm lint                    → All matched files use Prettier code style!
$ pnpm typecheck               → EXIT 0
```
صور الأندرويد: `apps/android/app/build/shots/knowledge/android-{knowledge,skills-usage,skills-usage-top,plugins,webhooks,webhooks-deliveries}-{light-en,dark-ar}.png` (راجعتُها: المعرفة، التقرير، وبطاقة الوجهة مع نتيجة الاختبار والتسليمات وزر إعادة الإرسال بالعربية).

iOS: لا يُبنى على لينكس. التشغيل الأول لـ`ios.yml` على الفرع (36286294590): `** BUILD SUCCEEDED **`، 270 اختبارًا وفشل واحد —
تاريخ اليوم بالعربية خرج بأرقام هندية لأن `setLocalizedDateFormatFromTemplate` يأخذ أرقام المنطقة؛ صار نمطًا ثابتًا (`d MMM y`)
ويبقى الاختبار يتحقق من ذلك.

الدمج مع فرع الليلة (بعد الدفعة 9): تعارضان نصيّان فقط في `STATUS.md` وفهرس الليلة (أُبقي الطرفان)، واسم مكرر: الدفعة 9 عرّفت
`WebhookRules` لوجهات الوكيل في الحزمة نفسها، فصارت قواعد هذه الصفحة `NotifyWebhookRules` في التطبيقين. بعد الدمج أُعيدت فحوص
الأندرويد (`KnowledgeReportsTest` 12، `KnowledgeShots` 2، `NavigationParityTest` 10، `StringsParityTest` 4، lint: EXIT 0) و`i18n:check`
و`contracts:check-clients` و`lint` و`typecheck` و`change-record:check` (كلها EXIT 0).

صفحة «وجهات الإشعار» في إعدادات الويب هي وجهات notify (`notify/WebhooksTab.tsx`)، لا مسارات Hermes؛ فلا تكرار مع صفحة قنوات الوكيل
(`AgentWebhooks` في الدفعة 9).

```
$ gh workflow run ios.yml --ref night/apps-knowledge   (run 36286785244، الالتزام 13715b76 بعد الدمج)
** BUILD SUCCEEDED **
Executed 277 tests, with 0 failures (0 unexpected)
Test Case '-[CoreHubTests.HubDataRulesTests testAChosenAgentGoneFromThePeriodStaysChoosable]' passed
Test Case '-[CoreHubTests.HubDataRulesTests testTheBodyDropsUnknownEventsKeepsOrStopsTheSecret]' passed
Test Case '-[CoreHubTests.HubDataRulesTests testEveryStringThePagesUseExistsInBothLanguages]' passed
… (10 of 10 HubDataRulesTests passed)
** TEST SUCCEEDED **
```

CI على #181 بعد الدمج (الالتزام `217f41cf`): كل الفحوص نجحت — Android build, unit tests, lint (6m16s)، Build and test on the iOS
simulator (6m22s)، Lint/typecheck/contracts/client tests/build، Server unit tests ×3، Web smoke journeys، Change record، Docker،
Desktop، Installers ×3.

## المخاطر والرجوع
- صفحات iOS لم تُبنَ محليًا؛ تعتمد على مهمة iOS في CI.
- ترويسة البروفايل تُضاف يدويًا لعمليات notify؛ إن أعلن العقد لاحقًا معاملًا لها تُحذف الإضافة.
- الرجوع: `git revert` لالتزامات هذا الفرع في فرع الليلة؛ لا عقد ولا بيانات.

## التسليم والخطوة التالية
دُمج في `night/2026-09-27-apps` (#181). لم يُجرَّب على هاتفي المالك بعد.
