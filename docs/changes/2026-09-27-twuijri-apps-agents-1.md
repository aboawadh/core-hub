# الوكلاء I على الجوال: المهارات والذاكرة والإضافات وبطاقات الوكلاء (الدفعة 8)
المسؤول: twuijri · الفرع: night/apps-agents-1 · الحالة: review

## المشكلة والهدف
قائمة الفجوات (الدفعة 8) قالت: صفحات الوكيل في التطبيقين تعرض ولا تدير — المهارات قائمة ومفتاح فقط (لا فتح ولا تحرير ولا حذف
ولا استيراد ولا استعادة ولا بطاقة المكتبة)، والذاكرة تحرير كامل للوثيقة فقط (لا مدخلات ولا إزالة مدخل ولا حدّ الأحرف)، والإضافات
مفتاح فقط (لا تثبيت ولا إزالة)، وبطاقات صفحة الوكلاء بلا تثبيت/إزالة/تحديث/بحث عن تحديث (والأندرويد بلا إعادة تشغيل). الهدف: ما
تفعله صفحات الويب (`packages/web/src/agents/AgentSkillsScreen.tsx`، `AgentMemoryScreen.tsx`، `AgentPluginsScreen.tsx`،
`AgentManagerScreen.tsx` وبطاقة التحديثات في `AgentSettingsScreen.tsx`) يُفعل من الآيفون والأندرويد، بمكوّنات كل تطبيق.

## القرار والموافقات
مطابقة للويب، مع ما يناسب الهاتف:
- **المهارات:** بحث (في الاسم والمعرّف والوصف) وشرائح تصفية «الكل | مهاراتك | مكتبة Core Hub | هرمز» — مقترح، للمالك أن يؤكد:
  الويب فيه البحث فقط، والشرائح أضيفت لأن قائمة هرمز طويلة على الهاتف. المهارات بأقسامها ومفتاحها وعلاماتها (مثبّتة، لا تُقرأ،
  مدمجة في هرمز، مكتبة Core Hub، معدّلة). النقر يفتحها: مهارة هرمز للقراءة (Markdown)، وغيرها في محرر النص المشترك (تحرير/معاينة،
  ملاحظة المكتبة للمهارة المشحونة). «⋯» والضغطة المطوّلة: فتح، تثبيت/إلغاء التثبيت، استعادة المعدّلة (بعد سؤال)، حذف (بعد سؤال،
  لا لمهارات هرمز). مهارة جديدة: المعرّف (`^[a-z0-9][a-z0-9._-]{0,63}$`، أي اسم المجلد كما يقبله مسار العقد) والنص من ترويسة جاهزة.
  **استيراد** SKILL.md أو zip من ملفات الهاتف: يُرفع كل ملف `purpose: skill`، ثم `agents.importSkills`، ثم تُحذف المرفوعات في كل
  حال (كالويب). **بطاقة المكتبة:** عدد المثبّت والمعدّل، «تثبيت» حين ينقص شيء، والمفتاح (الإطفاء بعد سؤال).
- **الذاكرة:** الوثائق الثلاث بأسمائها؛ قائمتا الذاكرة مدخلات منفصلة: تحرير مدخل، **إزالة مدخل بعد سؤال**، إضافة مدخل، «حرّر
  الكل»، وشريط الحدّ (تحذير من 80%، ويُمنع الحفظ الذي يزيد قائمة فوق حدّها قبل إرساله؛ التقليص مسموح دائمًا). الحفظ يرسل
  `revision` الذي قُرئ. **لا حذف للوثائق** كما في الويب («تُفرَغ بالتحرير»)، ولا «مسح الكل» لأن الويب لا يعرضه. `agents.deleteMemoryItem`
  في العقد لمدخلات Ekko التي لا يعرضها الويب، فلم يُستعمل.
- **الإضافات:** المصدر وحالة هرمز والمفتاح (معطّل لما لا يُدار)، **تثبيت** باسم من الكتالوج أو `owner/repo` أو رابط Git (مهمة
  تُتابَع حتى تنتهي: التقدّم ثم «ثُبّتت x وهي مطفأة» أو رفض هرمز بكلماته)، و**إزالة** ما ثُبّت في البروفايل (بعد سؤال).
- **بطاقات الوكلاء:** زر واحد بحسب الحال — «تثبيت» لغير المثبّت، «حدّث إلى x.y» حين يتوفر تحديث، «إعادة تشغيل» لهرمز الذي يشرف
  عليه المركز (نفس `canRestart` في الويب) — و«⋯» فيه البقية: البحث عن تحديث، التحديث التلقائي تشغيل/إيقاف (إن دعمه المحوّل)،
  **إزالة** (بعد سؤال، لما ثبّته المركز فقط). وسم «تحديث متاح» و«أحدث من النسخة المختبرة». كل مهمة تُتابَع بـ`jobs.get` كل 1.5 ث
  بشريط تقدّم ثم نتيجتها (النسخة المثبّتة، «لا تحديث متاح» أو التحديث الموجود، أو الفشل بكلمات المركز)، ثم تُقرأ القائمة من جديد.
  مقترح، للمالك أن يؤكد: التحديث والتحديث التلقائي على البطاقة في الهاتف (في الويب هما في بطاقة «التحديثات» بصفحة إعدادات الوكيل)،
  والإزالة بعد سؤال (الويب يزيل مباشرة).
- الرفض بكلماتنا حيث يسمّي المركز السبب (كـ`toolErrors.ts` في الويب): أسباب الاستيراد العشرون (يتبعها نص المركز)، مهارات هرمز،
  الدليل الأساسي، المكتبة، الإضافة المشحونة، هرمز الذي لا يشرف عليه المركز، الذاكرة الطويلة.
- نصوص المنطقة: iOS `i18n/agents.{en,ar}.json` (مفاتيح `agents.skill.*`، `agents.memory.*`، `agents.plugin.*`، `agents.card.*`،
  `agents.import.*`، `agents.tools.*`)، Android `values*/strings_agents.xml` (`agents_*`)، مأخوذة من كلمات الويب، بأرقام لاتينية؛
  مصدرها جدول واحد يولّد الملفات الأربعة.
- لم يُبنَ هنا (الدفعة 9): خوادم MCP، بطاقات الإعدادات، القنوات.

## العقد (ما تغيّر في packages/contracts، أو «لا شيء»)
لا شيء. كل العمليات موجودة: `agents.listSkills/getSkill/putSkill/updateSkill/deleteSkill/restoreSkill/updateSkillLibrary/importSkills`،
`sessions.uploadAttachment/deleteAttachment`، `agents.listMemory/putMemoryItem`، `agents.listPlugins/updatePlugin/installPlugin/deletePlugin`،
`agents.install/uninstall/upgrade/checkUpdate/restart/update`، `jobs.get`.

## الملفات والتأثير
- Android: جديد `ui/screens/agent/AgentToolRules.kt` (القواعد)، `AgentToolOps.kt` (الاستدعاءات ومتابعة المهمة والاستيراد)،
  `AgentToolUi.kt` (شريط الحدّ، الرفض)، `ui/screens/AgentCardActions.kt`؛ أعيدت كتابة `AgentSkillsPage.kt` و`AgentMemoryPage.kt`
  و`AgentPluginsPage.kt`؛ `AgentsScreen.kt` يضع أزرار البطاقة؛ `data/Hub.kt` أضاف `jobs = JobsApi` (سطران)؛ `values*/strings_agents.xml`؛
  اختبار `parity/AgentToolsTest.kt` وصورة `shots/AgentToolsShots.kt`.
- iOS: جديد `Screens/Agent/AgentToolRules.swift`، `AgentToolKit.swift` (متابعة المهمة، شريط الحدّ، سؤال بكلمات الإجراء)،
  `AgentCardActions.swift`؛ أعيدت كتابة `AgentSkillsPage.swift` و`AgentMemoryPage.swift` و`AgentPluginsPage.swift`؛ `AgentsScreens.swift`
  (زر إعادة التشغيل القديم صار ضمن أزرار البطاقة)؛ `i18n/agents.{en,ar}.json`؛ اختبار `CoreHubTests/AgentToolRulesTests.swift`.
- لم أمسّ السجلّ ولا اختبارات التكافؤ ولا ملفات المهام (يعمل عليها وكيل المهام II) ولا صفحات MCP/الإعدادات/القنوات.

## الفحوص (الأوامر ونواتجها الفعلية)
```
$ pnpm --filter @corehub/contracts generate:native (JDK 17) → kotlin: explicit nulls in 77 request model(s) · swift: … 71 … OK
$ gradle :app:testDebugUnitTest --tests '*AgentToolsTest' '*AgentToolsShots' '*AgentPagesTest' '*StringsParityTest' '*NavigationParityTest' '*UiKitPolicyTest' '*Digits*' :app:lintDebug   (mj-run)
BUILD SUCCESSFUL in 26s
hub.core.android.parity.AgentToolsTest tests=11 failures=0 errors=0
hub.core.android.shots.AgentToolsShots tests=1 failures=0 errors=0
hub.core.android.parity.AgentPagesTest tests=6 failures=0 errors=0
hub.core.android.ui.StringsParityTest tests=4 failures=0 errors=0
hub.core.android.nav.NavigationParityTest tests=10 failures=0 errors=0
hub.core.android.ui.UiKitPolicyTest tests=1 failures=0 errors=0
hub.core.android.phone.DigitsTest tests=4 failures=0 errors=0
lint: لا تحذير في ملفاتي (أزلتُ خمسة نصوص غير مستعملة ظهرت في أول تشغيل)

$ pnpm i18n:check              → i18n:check  ios: 1201 keys, ar/en in parity … android: Arabic resources use Latin digits … OK
$ pnpm contracts:check-clients → check-clients  OK — 917 client file(s) scanned, 254 contract path(s) known.
$ pnpm lint                    → All matched files use Prettier code style!
$ pnpm typecheck               → exit=0
```
صورة الأندرويد: `apps/android/app/build/shots/agents/android-tools.png` (نظرتُ فيها: بطاقة المكتبة مع «1 edited» و«Install»، صفوف
المهارات بعلاماتها ومفاتيحها و«⋯»، بطاقة الذاكرة بشريط 98 من 120 (لون التحذير) ومدخلاتها الثلاثة ومنها عربي، وصف الإضافة
بمصدرها وحالتها وزر الإزالة).

iOS: لا بناء على لينكس؛ `gh workflow run ios.yml --ref night/apps-agents-1` (run 36282775630) — النتيجة تُضاف أدناه.

## المخاطر والرجوع
- متابعة المهمة بالقراءة كل 1.5 ث لا بالمقبس؛ تتوقف حين تغادر الصفحة (ينتهي نطاق الواجهة) والمهمة تكمل في المركز.
- الاستيراد في iOS ينسخ الملف المختار إلى مجلد مؤقت باسمه ثم يرفعه (25 MB حدّ الرفع الواحد؛ لا رفع مجزّأ للحزم).
- رسالة الرفض في الاستيراد تذكر السبب بكلماتنا ثم جملة المركز؛ لا تذكر اسم الملف داخل الحزمة لأن العميلين لا يقرآن `details.file`.
- لم يُجرَّب شيء على هاتفي المالك؛ iOS على محاكي CI فقط.
- الرجوع: `git revert` لالتزامات هذا الفرع في فرع الليلة؛ لا عقد ولا بيانات.

## التسليم والخطوة التالية
يُدمج في `night/2026-09-27-apps` (#181). التالي: الدفعة 9 (MCP وبطاقات الإعدادات والقنوات)، وتجربة المالك على الهاتفين.
