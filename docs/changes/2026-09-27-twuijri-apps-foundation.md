# أساس صفحات الجوال: سجلّ الصفحات وتقسيم الملفات والقطع المشتركة
المسؤول: twuijri · الفرع: night/apps-foundation · الحالة: review

## المشكلة والهدف
ليلة التطبيقات (`2026-09-27-twuijri-night-apps.md`) فيها نحو ١٤ دفعة، وكيلان في وقت واحد، كل دفعة تضيف صفحات أصلية في
الآيفون والأندرويد. قبل هذه المهمة كانت كل صفحة تمرّ على «مفتاح» واحد مشترك (iOS `SettingsPage`/`AgentPage`، Android
`SettingsPageScreen`/`AgentPageEditable`)، وملفات ساخنة تجمع صفحات كثيرة (iOS `TasksSchedules.swift`، `ModelsAdminPages.swift`
١٣٠٧ سطرًا؛ Android `AgentPages.kt` و`SettingsScreen.kt`)، وملف نصوص واحد لكل لغة في الآيفون، ولا قطع مشتركة للنماذج والقوائم
والحذف والمحرر والتوقيت. النتيجة المتوقعة: تعارضات دمج في كل دفعة وتكرار الشغل نفسه. الهدف: أن تبني الدفعات بالتوازي بلا تعارض
وبسرعة، دون أي صفحة جديدة هنا ودون تغيير سلوك.

## القرار والموافقات
- **سجلّ صفحات، ملف لكل صفحة** (نفس الفكرة في التطبيقين): لكل وجهة من الإعدادات ومن صفحات الوكيل «مدخل» في ملف صفحته
  (iOS `extension PhonePage { static let … }`، Android `internal val …Page = SettingsPageEntry(…)`)، والقائمتان في
  `PageRegistry` كاملتان بترتيب الملف `navigation.json` ولا تُعدَّلان. الصفحة غير الأصلية بعد لها ملفها ومدخلها `native = false`
  يرسم البديل الوحيد (iOS `NotNativePage` = شاشة «قريبًا» كما كان، Android `OnTheWebPage` كما كان). جعل صفحة أصلية = تعديل ملفها
  فقط وحذف `native = false`. اخترتُ هذا بدل «قائمة بديل يحذف منها كل دفعة سطرًا» لأن حذف سطرين متجاورين من دفعتين يتعارض في git.
- **تقسيم بلا تغيير سلوك**، وحذف أربع صفحات iOS ميتة لا يستدعيها شيء (`UsersPage`، `ModelsPage` القديمة، `AgentChannelsPage`،
  `AgentSettingsPage` — لها نسخ حية `PeopleNativePage` و`ModelsNativePage` و`AgentChannelsLinkPage` و`AgentSettingsEditPage`)
  و`UsersPage` الميتة في الأندرويد، حتى لا تحتار الدفعات أيّها المستعمَل.
- **نصوص لكل منطقة**: iOS `i18n/<area>.<lang>.json` تُدمج بعد `en.json`/`ar.json`؛ Android `values*/strings_<area>.xml` (كان مسموحًا).
- **قطع مشتركة** في عدّة كل تطبيق (Android مبنية على `ui/kit` والرموز، لا Material الجاهز): نموذج، هيكل قائمة، تأكيد حذف، محرر
  نص/Markdown (مأخوذ من محرر ملفات الإعداد)، محرر توقيت (cron / كل N / مرة واحدة + منطقة زمنية + التشغيلات القادمة من الخادم).
- أُضيفت ثلاث أيقونات Lucide للتطبيقين: `eye`، `eye-off`، `calendar`.
- لم أمسّ تبديل البروفايل في القوائم الجانبية ولا تنسيق الأرقام (مهمة `night/digits-profile`).
- مقترح — للمالك أن يؤكد: مسارات الملفات الجديدة في الأندرويد (`ui/screens/settings/` و`ui/screens/agent/`) تبقى في الحزمة نفسها
  `hub.core.android.ui.screens` حتى تبقى الأدوات الداخلية المشتركة وأسماء الاختبارات كما هي.

## العقد (ما تغيّر في packages/contracts، أو «لا شيء»)
لا شيء.

## الملفات والتأثير
- iOS: `Pages/PhonePage.swift` (السجل والبديل)، `Components/{FormSheet,ListScaffold,ConfirmDelete,DocumentEditor,TriggerEditor}.swift`،
  `Settings/Pages/*` (صفحة لكل ملف)، `Settings/Models/*`، `Settings/AdminLogic.swift`، `Screens/Agent/*`، `Screens/Tasks/TasksScreen.swift`،
  `Screens/Schedules/SchedulesScreen.swift`؛ حُذفت `SettingsPages.swift` و`ManagementPages.swift` و`ModelsAdminPages.swift`
  و`TasksSchedules.swift` و`AgentPagesMore.swift`؛ `SettingsScreen.swift` و`AgentsScreens.swift` صارا يرسمان من السجل؛
  `i18n/L10n.swift` يدمج ملفات المناطق؛ `i18n/kit.{en,ar}.json`؛ اختبارات `PageKitTests` واختبار السجل في `NavigationParityTests`
  واختبار ملفات المناطق في `L10nTests`.
- Android: `ui/screens/PageRegistry.kt`، `ui/screens/settings/*`، `ui/screens/agent/*` (بدل `AgentPages.kt`)، `SettingsScreen.kt` بقي
  للقائمة فقط، مداخل في `ModelsScreen.kt` و`AdminPages.kt` و`ToolsScreens.kt`؛ `ui/components/{FormSheet,ListScaffold,ConfirmDelete,
  TextEditorSheet,TriggerEditor}.kt`؛ `res/values*/strings_kit.xml`؛ اختبارات `PageKitTest` و`PageKitShots` وإضافات في
  `NavigationParityTest` و`StringsParityTest`.
- `scripts/i18n-check.mjs` يقرأ ملفات مناطق iOS ويرفض المفتاح المكرر؛ `docs/clients/phone-pages.md` دليل الدفعات.

## الفحوص (الأوامر ونواتجها الفعلية)
```
$ gradle :app:testDebugUnitTest :app:lintDebug   (mj-run, JDK 17)
BUILD SUCCESSFUL in 1m 10s
tests 241 skipped 2 failed 0
lint: 0 errors, 56 warnings

$ gh workflow run ios.yml --ref night/apps-foundation   (run 36271864200)
✓ Generate the Swift client (CoreHubClient) in 38s
✓ Build and test on the iOS simulator in 7m24s
Test Case '-[CoreHubTests.L10nTests testAreaCataloguesAreMergedAndNoKeyIsInTwoFiles]' passed
Test Case '-[CoreHubTests.NavigationParityTests testThePageRegistryNamesEverySettingsAndAgentPageOnceInOrder]' passed
Test Case '-[CoreHubTests.PageKitTests testAFormRefusesWhatItCannotSaveAndSaysWhy]' passed
Test Case '-[CoreHubTests.PageKitTests testAPagedListAddsTheNextPageKeepsRowsOnceAndKeepsThemOnAFailure]' passed
Test Case '-[CoreHubTests.PageKitTests testASavedTriggerComesBackAsTheDraftThatMakesIt]' passed
Test Case '-[CoreHubTests.PageKitTests testASaveRefusedAsChangedElsewhereIsToldApart]' passed
Test Case '-[CoreHubTests.PageKitTests testATriggerIsBuiltFromWhatWasTypedAndRefusedWithAReason]' passed

$ pnpm lint        → All matched files use Prettier code style!
$ pnpm typecheck   → exit=0
$ pnpm i18n:check  → i18n:check  ios: 754 keys, ar/en in parity … i18n:check  OK
$ pnpm contracts:check-clients → check-clients  OK — 865 client file(s) scanned, 254 contract path(s) known.
$ node scripts/icons/lucide-mobile.mjs --check → lucide: 96 shared + 22 Android icon(s) from lucide-static 1.48.0 up to date
```
صور القطع في الأندرويد: `apps/android/app/build/shots/page-kit/android-{form-problems,form-filled,list,list-empty,confirm-delete,editor-edit,editor-preview,trigger-cron,trigger-every,trigger-once}.png`.
نتيجة CI على #181 تُضاف بعد الدمج في فرع الليلة.

## المخاطر والرجوع
- نقل ملفات كثيرة: خطر تعارض مع دفعات فتحت قبل هذا الدمج؛ الحل دمج فرع الليلة في فرعها قبل البدء.
- `native` في مدخل الصفحة يجب أن يطابق ما ترسمه: اختبار الأندرويد يقرأ المصادر ويفشل إن اختلفا؛ في iOS تُراجَع يدويًا.
- الرجوع: `git revert` لالتزامات هذا الفرع في فرع الليلة؛ لا عقد ولا بيانات.

## التسليم والخطوة التالية
دُمج في `night/2026-09-27-apps` (#181). الدفعات ١–١٥ تقرأ `docs/clients/phone-pages.md` وتملأ ملفات صفحاتها.
