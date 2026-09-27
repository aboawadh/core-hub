# بقايا الجوال: إرفاق ملف المركز بمحادثة، ورقة «في الخلفية»، تواريخ الأندرويد، وترويسة البروفايل للويب هوك
المسؤول: twuijri · الفرع: night/apps-leftovers · الحالة: review

## المشكلة والهدف
دفعات ليلة التطبيقات تركت أشياء صغيرة مكتوبة في سجلاتها:
1. **الإرفاق من صفحة الملفات** (الدفعتان 11 و13): «إرفاق بمحادثة جديدة» كان ينزّل الملف إلى الهاتف ثم يرفعه مرة أخرى مرفقًا،
   ولا يعرض المحادثات الأخيرة كما يفعل الويب.
2. **ورقة المهام الخلفية** (`background.list` / `background.stop`، الدفعة 6أ): لم تُبنَ في التطبيقين.
3. **تواريخ الأندرويد تتبع لغة الهاتف لا لغة التطبيق**: تطبيق بالعربية على هاتف إنجليزي يكتب أسماء الشهور بالإنجليزية.
4. **ترويسة البروفايل في عمليات الويب هوك** (الدفعة 10): الهاتفان يضيفان `X-Hub-Profile` يدويًا (معترض في الأندرويد، `customHeaders`
   في الآيفون) لأن العقد لا يعلنها. المطلوب التحقق من الخادم ثم إعلانها في العقد أو شرح السبب.
5. ما بقي صغيرًا في سجلات الدفعات ولا يمسّ صفحات الإدارة.

## القرار والموافقات
- **الإرفاق دون إعادة رفع (التطبيقان)**: «إرفاق بمحادثة…» في «⋯» الملف يفتح ورقة فيها «محادثة جديدة» وأحدث 8 محادثات في هذا
  البروفايل (بلا محادثة الوكيل العام، كما في الويب). الاختيار يطلب من المركز صنع المرفق (`knowledge.attachWorkspaceFile`) فلا ينزل
  الملف ولا يُرفع ثانية، ثم يوضع المرفق في «مكان تسليم» واحد بالبروفايل (مثل `attachments/handoff.ts` في الويب: يُؤخذ مرة، لنفس
  البروفايل فقط، وينتهي بعد دقيقة) وتُفتح المحادثة المختارة، وصندوق الكتابة فيها يأخذه جاهزًا (شريحة بلا رفع). Android
  `AttachmentHandOff` في `AppGraph`، iOS في `AppModel`؛ `AttachmentTray.addReady` في التطبيقين. حُذف طريق النسخ عبر `sharedFiles` /
  `pendingFiles` من صفحة الملفات.
- **ورقة «في الخلفية» (التطبيقان)** كما في ويب §56: ما يعمل للشخص في كل بروفايل (`profiles=all`)، لكل عنصر نوعه وبروفايله (إن كان
  له أكثر من بروفايل) وحالته ومدته بأرقام لاتينية، «فتح» حيث يعيش (المحادثة، لوحة المهام، الجدولة، صفحة العمل: البروفايلات
  والنماذج والويب هوك والأجهزة في الإعدادات، والوكلاء)، و«إيقاف» حيث يجوز (`background.stop` ببروفايل العنصر)، و«انتهت في آخر 24
  ساعة (n)» مطويّة. **مكانها**: زر Activity بعدّاد بجانب الجرس في شريط المحادثة الجديدة والمحادثة والغرفة، يظهر فقط حين يعمل شيء
  (كما يفعل الويب على الهاتف)، وبند «في الخلفية» في قائمة «⋯» للمحادثة يفتحها دائمًا. تُقرأ القائمة كل 10 ثوانٍ حين يعمل شيء وكل
  دقيقة غير ذلك، والتطبيق في المقدمة فقط؛ لا اشتراك في أحداث الوقت الحقيقي بعد (الويب يعيد القراءة عند الأحداث). مقترح — للمالك أن يؤكد.
- **تواريخ الأندرويد**: صار الإعداد المحلي الافتراضي للعملية هو لغة التطبيق مع `nu-latn` (`Digits.wrap` عند كل نشاط و
  `Digits.useAppLocale` عند بدء التطبيق للإشعارات)، فكل `localTime` و`d MMM yyyy` في الصفحات يكتب الشهر بلغة التطبيق وأرقامًا
  لاتينية. لغة الهاتف نفسها تُقرأ من إعداد النظام (`Digits.phoneLocales`) لخيار «اتبع الهاتف» ولقائمة لغات الإملاء، فلا تتأثر.
  `localTime` يقبل المنطقة واللغة للاختبار.
- **ترويسة البروفايل للويب هوك — الخادم يقرأها فعلًا**: `scopeOf` في `modules/notify/index.ts` يأخذ البروفايل من
  `X-Hub-Profile` (أو `default`) ويحفظ كل وجهة في بروفايلها ويبحث عنها فيه. فأُعلنت الترويسة (المعامل المشترك `Profile`) على العمليات
  السبع (`listWebhooks`, `createWebhook`, `updateWebhook`, `deleteWebhook`, `testWebhook`, `listWebhookDeliveries`,
  `redeliverWebhookDelivery`) وحُذف منها `x-scope: global`؛ و`listWebhookEvents` (الكتالوج) والوارد والتفضيلات بقيت عامة. صار
  الحارس `requireWorkspace` يحلّ البروفايل قبل المعالج بالأجوبة نفسها (غير معروف أو ممنوع = `404 profile_not_found`، بلا ترويسة =
  `default`)، فأُضيف `404` الموثّق إلى `listWebhooks` و`createWebhook`. العميلان المولَّدان صارا يأخذان البروفايل وسيطًا أول، وحُذف
  المعترض (`HubDataApis` بلا عميل خاص) واستعمال `inProfile` في صفحتي الويب هوك في الآيفون (بقي `inProfile` لتصدير البروفايل
  واستيراده في صفحة الإدارة). DECISIONS §115 (مقترح — للمالك أن يؤكد)، مع إشارة في §4.
- **البند 5**: انظر «التسليم» — لم أجد بندًا صغيرًا باقيًا لا يحتاج عقدًا ولا يمسّ الإدارة.

## العقد (ما تغيّر في packages/contracts، أو «لا شيء»)
- `openapi.yaml`: العمليات السبع للويب هوك تعلن `X-Hub-Profile` (على مستوى المسار حيث فيه `webhook_id`، فيأتي البروفايل أولًا في
  العملاء المولَّدين كبقية العمليات)، بلا `x-scope: global`؛ وصف لـ`listWebhooks`؛ `404` لـ`listWebhooks` و`createWebhook`.
- أُعيد توليد عملاء TypeScript وKotlin وSwift.
- `docs/contracts/DECISIONS.md` §115 وإشارة في §4.

## الملفات والتأثير
- العقد والخادم: `packages/contracts/openapi.yaml`، `docs/contracts/DECISIONS.md`،
  `packages/server/tests/contract/webhooks.contract.test.ts` (اختبار جديد: العمليات تعلن الترويسة، الوجهة تُحفظ في بروفايل الطلب ولا
  تُرى ولا تُعدَّل من غيره، والبروفايل المجهول `404`). لا تغيير في كود الخادم.
- Android: `chat/Attachments.kt` (`addReady`، `AttachmentHandOff`)، `AppGraph.kt` (`handOff`، `system()` من النظام، لغة التطبيق عند
  البدء)، `Digits.kt` (`phoneLocales`، `useAppLocale`، `wrap` يضبط الافتراضي)، `phone/DictationUi.kt` (لغات الهاتف من النظام)،
  `ui/screens/SchedulesScreen.kt` (`localTime`)، `ui/screens/ChatScreen.kt` (يأخذ المسلَّم)، `ui/screens/settings/FilesKit.kt`
  (`attach`، `recentChats`، `FilesRules.recentChats`)، `ui/screens/settings/FilesPage.kt` (ورقة `AttachToChatSheet`)،
  `ui/screens/BackgroundSheet.kt` (جديد: القواعد والنداءات والنموذج والزر والورقة)، `ui/screens/ChatMenu.kt` (بند «في الخلفية»)،
  `MainActivity.kt` (الزر في ثلاثة أشرطة)، `ui/screens/settings/HubDataKit.kt` و`KnowledgePage.kt` (بلا معترض)،
  `res/values*/strings_files.xml` (نصوص الإرفاق)، `res/values*/strings_leftovers.xml` (جديد: «في الخلفية»).
- iOS: `Chat/Attachments.swift` (`addReady`، `AttachmentHandOff`)، `App/AppModel.swift` (`handOff`)، `Chat/NewChatScreen.swift`
  و`Chat/ChatScreen.swift` (يأخذان المسلَّم؛ بند «في الخلفية» في «⋯»)، `Settings/Pages/FilesRules.swift` (`attach`، `recentChats`)،
  `Settings/Pages/FilesPage.swift`، `Settings/Pages/FilesAttachSheet.swift` (جديد)، `Shell/BackgroundSheet.swift` (جديد)،
  `Shell/ShellView.swift` (الزر والورقة و`openBackground`)، `Settings/Pages/WebhooksPage.swift` و`WebhookSheet.swift` و
  `HubDataRules.swift` (الوسيط المولَّد)، `i18n/files.{en,ar}.json`، `i18n/leftovers.{en,ar}.json` (جديد).
- اختبارات: Android `parity/LeftoversTest.kt` (جديد، 6)، `parity/FilesPageTest.kt` (+2)، `phone/DigitsTest.kt` (+3)،
  `parity/KnowledgeReportsTest.kt` (بلا وسيط البروفايل للعميل)، `shots/LeftoversShots.kt` (جديد، 4 صور مزدوجة)؛ iOS
  `CoreHubTests/LeftoversTests.swift` (جديد، 7).
- `docs/STATUS.md` وفهرس الليلة.

## الفحوص (الأوامر ونواتجها الفعلية)
```
$ pnpm contracts:generate (JDK 17)   → contracts:generate:native  OK
$ pnpm contracts:lint                → Woohoo! Your API description is valid. … contracts:lint  OK
$ pnpm contract:test                 → Test Files  19 passed (19) · Tests  405 passed (405)
  (قبل إضافة 404 فشل الاختبار الجديد: «status 404 is not documented for notify.listWebhooks»، وعلى العقد القديم يفشل فحص إعلان الترويسة)
$ vitest server tests/unit/webhook-events.test.ts tests/unit/hermes-webhooks.test.ts → 2 passed · 21 tests passed
$ vitest web tests/webhooks-privacy.test.tsx → 1 passed · 19 tests passed
$ pnpm typecheck                     → EXIT 0
$ pnpm contracts:check-clients       → check-clients  OK — 982 client file(s) scanned, 254 contract path(s) known.
$ pnpm i18n:check                    → ios: 2090 keys, ar/en in parity … android: Arabic resources use Latin digits … OK
$ gradlew :app:testDebugUnitTest (LeftoversTest, LeftoversShots, FilesPageTest, FilesPageShots, DigitsTest, KnowledgeReportsTest,
    KnowledgeShots, StringsParityTest, NavigationParityTest, ChatControlsShots, ChatInsightShots, ScreenShots) :app:lintDebug
  NavigationParityTest 10/0 · FilesPageTest 15/0 · KnowledgeReportsTest 12/0 · LeftoversTest 6/0 · DigitsTest 7/0
  ChatControlsShots 1/0 · ChatInsightShots 2/0 · FilesPageShots 2/0 · KnowledgeShots 2/0 · LeftoversShots 4/0 · ScreenShots 4/0
  StringsParityTest 4/0   (tests/failures)
  lintDebug: لا تحذير جديد في الملفات المعدّلة (تحذيرات قديمة في DictationUi وAppGraph وDigits.wrap)
```
اختبار التواريخ الجديد يفشل على الكود القديم: `Digits.wrap(…, AR)` على هاتف إنجليزي كان يترك الافتراضي إنجليزيًا فيكتب `localTime` «Sep … PM».

صور الأندرويد في `apps/android/app/build/shots/leftovers/` (راجعتُها): ورقة الإرفاق بالإنجليزية فاتحة وبالعربية داكنة (محادثة جديدة
+ ست محادثات أخيرة)، والمحادثة بعد الاختيار وفيها «summary.pdf» جاهزًا في صندوق الكتابة (والاختبار يتحقق أن لا تنزيل ولا رفع)،
وزر «في الخلفية» بعدّاد 2 بجانب الجرس، والورقة (محادثة ووكيل فرعي يعملان مع فتح وإيقاف، و«انتهت (1)»). الأوقات في الصور طويلة لأن
مثال العقد بدأ في 21 سبتمبر.

iOS لا يُبنى على لينكس:
```
$ gh workflow run ios.yml --ref night/apps-leftovers   (run 36290775773، الالتزام cd6f2159)
** BUILD SUCCEEDED **
Test Case '-[CoreHubTests.LeftoversTests testAHandedOffFileIsTakenOnceByTheComposerOfItsOwnProfile]' passed
Test Case '-[CoreHubTests.LeftoversTests testAReadyAttachmentGoesWithTheNextMessageWithoutAnUpload]' passed
Test Case '-[CoreHubTests.LeftoversTests testARowWithoutWordsSaysWhatItIs]' passed
Test Case '-[CoreHubTests.LeftoversTests testAttachOffersThisProfilesRecentChatsAtMostEightWithoutTheGlobalAgents]' passed
Test Case '-[CoreHubTests.LeftoversTests testEachItemOpensWhereItLives]' passed
Test Case '-[CoreHubTests.LeftoversTests testEveryKindAndStatusHasItsWordsInBothLanguages]' passed
Test Case '-[CoreHubTests.LeftoversTests testTimeIsCountedInLatinDigitsAndAQueuedItemHasNoneYet]' passed
Executed 301 tests, with 0 failures (0 unexpected)
```

## المخاطر والرجوع
- لم يُجرَّب على هاتفي المالك. صفحات iOS بلا صور (اختبارات القواعد فقط في CI).
- الافتراضي المحلي للعملية في الأندرويد صار لغة التطبيق: كل ما يقرأ `Locale.getDefault()` يتبع التطبيق الآن؛ ما يحتاج لغة الهاتف
  نفسها (الإملاء، «اتبع الهاتف») صار يقرأ النظام صراحة.
- ورقة «في الخلفية» تسأل المركز كل 10 ثوانٍ حين يعمل شيء؛ لا تسمع الأحداث بعد.
- العمليات السبع صار لها حارس بروفايل؛ عميل يرسل بروفايلًا لا يدخله كان يُرفض أيضًا (من `scopeOf`)، والجواب نفسه.
- الرجوع: `git revert` لالتزامات هذا الفرع في فرع الليلة؛ تغيير العقد يعود معها (والمعترضان يعودان من الدفعة 10).

## التسليم والخطوة التالية
يُدمج في `night/2026-09-27-apps` (#181).

**البند 5 — ما بقي في سجلات الدفعات (فحصتها كلها) ولماذا تُرك:**
- تعديل التعليق وحذفه في المهام (الدفعة 5): يحتاجان عمليتين جديدتين في العقد — ليسا صغيرين.
- النقل والإسناد الجماعيان للمهام (الدفعة 5): أكبر من نصف ساعة في التطبيقين (اختيار العمود/الوكيل لكل بروفايل ونتيجة لكل بطاقة).
- الرفع القابل للاستئناف لملفات البروفايل (الدفعتان 11 و13): يحتاج عملية في العقد.
- لسان المسار (Trajectory) ولوحة «الموافقات» المجمّعة وأدلة «كيف تبدأ» للقنوات (الدفعتان 6 و9): تركتها قائمة الفجوات للويب أو هي
  محتوى كبير.
- طلبات الاقتران في ورقة الانتظار: بنتها دفعة الإدارة الليلة.
- ترتيب المقاعد وموضوع الغرفة (الدفعة 7): غير موجودين في العقد ولا الويب.
- ملاحظتا «على الويب» القديمتان في `ar.json`/`en.json` بالآيفون (`models.edit_on_web`، `channels.link_on_web`) لم تعودا تُعرضان
  في أي شاشة؛ تُركتا في الملف المشترك.

للمالك: تأكيد §115، ومكان ورقة «في الخلفية» (زر يظهر عند العمل + بند في «⋯»).
