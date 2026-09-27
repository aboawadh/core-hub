# الوكلاء II على الجوال: خوادم MCP وبطاقات الإعدادات والقنوات (الدفعة 9)
المسؤول: twuijri · الفرع: night/apps-agents-2 · الحالة: done

## المشكلة والهدف
قائمة الفجوات (الدفعة 9، 9a و9b) قالت: صفحة MCP في التطبيقين قائمة واختبار فقط (لا إضافة ولا تعديل ولا حذف ولا بطاقة
«أدوات كور هب»)، وصفحة إعدادات الوكيل تعرض حقول `list`/`json` للقراءة («تُحرَّر على الويب») وليس فيها بطاقة تسجيل الدخول ولا ضغط
السياق ولا «بانتظار المراجعة»، وصفحة القنوات ربط وفك ربط واقتران فقط (لا حالة ولا تشغيل/إيقاف ولا إعدادات القناة ولا الوضع ولا عنوان
الردود ولا نسيان الهوية ولا إعادة التشغيل ولا الويب هوك). الهدف: ما تفعله صفحات الويب (`packages/web/src/agents/AgentMcpScreen.tsx`،
`HubToolsCard.tsx`، `AgentSettingsScreen.tsx` وبطاقاتها، `AgentChannelsScreen.tsx`، `ChannelSettingsPanel.tsx`، `WebhooksSection.tsx`)
يُفعل من الآيفون والأندرويد بمكوّنات كل تطبيق.

## القرار والموافقات
مطابقة للويب، مع ما يناسب الهاتف:
- **خوادم MCP:** القائمة (دون كتلة المركز نفسه، فلها بطاقتها) بمفتاح التشغيل، ونوع النقل، وما يشغّله الخادم أو عنوانه، والحالة
  (متصل/غير متصل/متوقف)، وخطأ هرمز؛ **اختبار** يعرض عدد الأدوات والمدة وأسماء الأدوات أو جملة هرمز حين يفشل الاتصال؛ «⋯» والسحب:
  **تعديل** و**حذف** (بعد سؤال بكلمات الويب). **خادم جديد/تعديل** — مقترح، للمالك أن يؤكد: الويب يحرّر الخادم كتلة JSON واحدة؛ على الهاتف
  نموذج بنوعين: **أمر** (الأمر، المعاملات سطرًا سطرًا، متغيرات البيئة) أو **عنوان** (الرابط، الترويسات)، كل متغير/ترويسة صف باسم وقيمة
  سرّية (إظهار/إخفاء)؛ القيمة المحفوظة تُقرأ `[stored]` فتظهر فارغة بعبارة «محفوظ — اتركه فارغًا ليبقى» وتُرسل `[stored]` كما هي
  فيبقى المفتاح. المفاتيح الأخرى في الخادم تبقى كما هي (ويُقال ذلك)، و«تحرير كـ JSON» يفتح الكتلة كاملة كالويب لما لا يسعه النموذج. النقل
  يُستنتج من الشكل كالويب (عنوان ⇒ `http`، أمر ⇒ `stdio`)، ويُرسل مع التعديل فقط حين ينتقل الخادم بين الأمر والعنوان. الاسم بقاعدة الويب
  `^[A-Za-z0-9._-]{1,60}$`.
- **أدوات كور هب** (§67) أعلى صفحة MCP: المفتاح (معطّل حين لا يتوفر هرمز)، سبب عدم التوفر، «باسم من يعمل»، المجموعات بمفتاحها ووصفها
  وأسماء أدواتها ومفتاح «السماح بالتغييرات»، اختبار، العنوان، وآخر الاستدعاءات بسبب الرفض بكلماتنا. وكيل ليس هرمز (409) لا يظهر له شيء.
- **الإعدادات:** كل الحقول تُحرَّر الآن — `list` سطر لكل عنصر (يُرسل مصفوفة، الفارغ يعيد الافتراضي)، و`json` يجب أن يُقرأ قبل الإرسال
  (مقترح، للمالك أن يؤكد: الويب يرسلهما نصًا ويترك التحقق للمركز). يبقى الحفظ لكل حقل فور تغييره كما كان في التطبيقين (الويب يحفظ
  القسم بزر)، وبعد الحفظ سطر بما قاله المركز: «يُعاد تشغيل هرمز الآن» / «يسري بعد إعادة تشغيله» / «من الرسالة التالية»، وتحت القسم متى
  يسري إن لم يكتب المحوّل ملاحظته. البطاقات: **تسجيل الدخول إلى حساب الوكيل** (Kimi Code وGrok Build حين `install.sign_in` ومُثبّت
  من المركز: الرمز مع النسخ، فتح صفحة الدخول، انتظار بقراءة كل ثانيتين، النتيجة، إعادة المحاولة)، **الإعدادات المحفوظة** كما كانت،
  **ضغط السياق** (§57) للبروفايل حين يعلن الوكيل `compress` (نسب مئوية على الشاشة ونسب عشرية على الخط، بقواعد الويب نفسها)،
  و**بانتظار المراجعة** (§58) لهرمز: نوع الكتابة ومصدرها ووقتها وملخّصها والنص القديم مشطوبًا والجديد، موافقة/رفض، وتُقرأ كل 15 ث.
  بطاقة «التحديثات» بقيت على بطاقة الوكيل في صفحة الوكلاء كما قررت الدفعة 8.
- **القنوات:** ما هو مربوط أو ينتظر أحدٌ عليه فقط (ومستقبِل الويب هوك له قسمه)، كل منصة بطاقة: المفتاح (`updateChannel enabled`)،
  الاسم، الوسوم (هوية واحدة، مربوط/غير مربوط، الحالة، وضع واتساب)، الحساب، و«N بانتظار الموافقة». أول ما تعرضه زرًا والبقية في «⋯»
  بترتيب الويب: **اقتران بـQR** (واتساب غير المربوط → ملاحظة «اقرنه من حاسوب أو جهاز لوحي» ورابط فتح الصفحة على الويب؛ الهاتف لا
  يمسح شاشته، كما في §3 من قائمة الفجوات)، **ربط** (تيليجرام برمز البوت، والمنصات ذات الاعتماد بنموذجها، مباشرة إلى نموذجها)،
  **الإعدادات** (كل خيار من `getChannelSettings` بأقسامه الخمسة، بكلمات الويب لكل خيار ولكل منصة، ما يعنيه وافتراضه، «يغيّر كل القنوات»
  للخيارات المشتركة، «العودة للافتراضي»، الأرقام بحدودها، والقوائم بالفواصل؛ الحفظ مرة واحدة = إعادة تشغيل واحدة للبوابة)، **تغيير الوضع**
  (بوت/مراسلة نفسي)، **عنوان الردود** (اسم الوكيل أو نص حتى 64 حرفًا مع المعاينة)، **الحقول** (محرر الحقول لمنصة لا يعرفها المركز:
  السرّي يبقى إن تُرك)، **فك الربط** (بعد سؤال بكلمات كل منصة) و**نسيان الهوية** (بعد سؤال). حين `restart_needed` ملاحظة «يحتاج هرمز إعادة
  تشغيل» وزر **أعد التشغيل الآن** (`agents.restart`، لهرمز الذي يشرف عليه المركز فقط) يتابع المهمة ثم يقرأ القنوات. سطر البوابة
  («يسري فورًا» مع حالتها، أو «أعد التشغيل ليسري»). الاقتران (موافقة/رفض/إزالة) كما كان مع رقم المرسل.
- **الويب هوك** (§97) تحت القنوات: حالة المستمع (تُقرأ كل 3 ث وهو يبدأ)، ملاحظة أن العنوان يجب أن يُصل إليه من الإنترنت، كل مسار
  باسمه وأحداثه ووصفه وموجّهه وعنوانه على المركز (نسخ) وسرّه (إظهار/نسخ) أو «سرّ المستمع المشترك»، **اختبار** بنتيجته، **حذف** بعد سؤال
  (لا للمسارات من `config.yaml`)، و**ويب هوك جديد** (الاسم بقاعدة هرمز، التعليمة، الوصف، الأحداث، أين يُرسل الرد: السجل أو قناة
  مفعّلة). مقترح، للمالك أن يؤكد: الهاتف لا يعرف إن كان عنوان المركز خاصًا (الويب يقرأ عنوان الصفحة)، فيقول الملاحظة العامة فقط.
- النصوص (400 مفتاح، منطقة `agents2`): iOS `i18n/agents2.{en,ar}.json`، Android `values*/strings_agents2.xml` و`Agents2Words.kt`
  (خرائط المفاتيح التي يرسلها المركز: خيارات القناة، الاختيارات، كلمات كل منصة، مجموعات أدوات المركز وأسباب رفضها)، كلها مولّدة من
  جدول واحد يأخذ كلمات الويب نفسها (`packages/web/src/i18n`) ويضيف كلمات الهاتف، بأرقام لاتينية. المولّد في مجلد العمل المؤقت لا في المستودع
  (كالدفعة 8). حُذفت من الأندرويد خمسة نصوص لم تعد تُستعمل (`channels_linked`، `channels_qr_body`، `channels_unlink_confirm`،
  `mcp_test_ok`، `settings_edit_on_web`).
- لم يُبنَ هنا: «كيف تبدأ» لكل منصة (أدلة الويب)، ولوحة «الموافقات» المجمّعة (الهاتف يعرض الانتظار والموافَق عليهم تحت القنوات كما كان)،
  واقتران واتساب بـQR (يبقى على الويب عمدًا).

## العقد (ما تغيّر في packages/contracts، أو «لا شيء»)
لا شيء. كل العمليات موجودة: `agents.listMcpServers/createMcpServer/updateMcpServer/deleteMcpServer/testMcpServer`،
`agents.getHubTools/updateHubTools`، `agents.getSettings/updateSettings`، `agents.startSignIn/getSignIn`،
`agents.listPendingWrites/approvePendingWrite/rejectPendingWrite`، `auth.listProfiles/getProfileSettings/updateProfileSettings`،
`agents.listChannels/listChannelPlatforms/updateChannel/clearChannel/unlinkChannel/setChannelMode/setChannelReplyHeader/getChannelSettings/updateChannelSettings/restart`،
`agents.listPairing/approvePairing/denyPairing/revokePairing`، `agents.listWebhooks/createWebhook/deleteWebhook/testWebhook`، `jobs.get`.
وصف الويب هوك الفارغ يُرسل `null` صريحًا (§114) كما يرسله الويب.

## الملفات والتأثير
- Android (`ui/screens/agent/`): جديد `AgentsTwoRules.kt` (القواعد)، `AgentsTwoOps.kt` (الاستدعاءات)، `AgentHubToolsCard.kt`،
  `AgentSettingsCards.kt`، `AgentChannelSheets.kt`، `AgentWebhooks.kt`، `Agents2Words.kt` (مولّد)؛ أعيدت كتابة `AgentMcpPage.kt` و`AgentChannelsPage.kt`؛
  `AgentSettingsPage.kt` (قوائم وJSON، البطاقات، سطر الحفظ)؛ `values*/strings_agents2.xml`، و`values*/strings_parity.xml` (حذف خمسة)؛
  اختبار `parity/AgentsTwoTest.kt`، تعديل `parity/AgentPagesTest.kt`، صور `shots/AgentsTwoShots.kt`.
- iOS (`Screens/Agent/`): جديد `AgentsTwoRules.swift`، `AgentSettingsCards.swift`، `AgentChannelSheets.swift`، `AgentWebhooks.swift`؛
  أعيدت كتابة `AgentMcpPage.swift` و`AgentChannelsLinkPage.swift`؛ `AgentSettingsEditPage.swift`؛ `i18n/agents2.{en,ar}.json`؛
  اختبار `CoreHubTests/AgentsTwoRulesTests.swift`، تعديل `CoreHubTests/AgentPagesTests.swift`.
- لم أمسّ السجلّ ولا اختبارات التكافؤ ولا صفحات المحادثة (يعمل عليها وكيل رؤى المحادثة) ولا ملفات الإعدادات والمهام في الوكيل.

## الفحوص (الأوامر ونواتجها الفعلية)
```
$ pnpm --filter @corehub/contracts generate:native (JDK 17) → kotlin: explicit nulls in 77 request model(s) · swift: … 71 … OK
$ gradle :app:testDebugUnitTest --tests '*AgentsTwoTest' '*AgentsTwoShots' '*AgentPagesTest' '*AgentToolsTest' '*StringsParityTest' '*NavigationParityTest' '*UiKitPolicyTest' '*Digits*' :app:lintDebug   (mj-run)
BUILD SUCCESSFUL in 30s
hub.core.android.parity.AgentsTwoTest tests=10 failures=0 errors=0
hub.core.android.shots.AgentsTwoShots tests=2 failures=0 errors=0
hub.core.android.parity.AgentPagesTest tests=6 failures=0 errors=0
hub.core.android.parity.AgentToolsTest tests=11 failures=0 errors=0
hub.core.android.ui.StringsParityTest tests=4 failures=0 errors=0
hub.core.android.nav.NavigationParityTest tests=10 failures=0 errors=0
hub.core.android.ui.UiKitPolicyTest tests=1 failures=0 errors=0
hub.core.android.phone.DigitsTest tests=4 failures=0 errors=0
lint: 0 errors، ولا تحذير في ملفاتي بعد الحذف والتنظيف

$ pnpm i18n:check              → i18n:check  ios: 1670 keys, ar/en in parity … android: Arabic resources use Latin digits … OK
$ pnpm contracts:check-clients → check-clients  OK — 940 client file(s) scanned, 254 contract path(s) known.
$ pnpm lint                    → All matched files use Prettier code style!
```
```
$ gh workflow run ios.yml --ref night/apps-agents-2   (run 36285941847، الالتزام 3583cd5f)
✓ Generate the Swift client (CoreHubClient)
✓ Build and test on the iOS simulator — Executed 258 tests, with 0 failures · AgentsTwoRulesTests: 7 passed
```
بعد ذلك تغيّران صغيران في Swift (وصف الويب هوك `null` صريحًا، وحذف نصوص غير مستعملة) يتحقق منهما iOS في #181.

بعد دمج `origin/night/2026-09-27-apps` (رؤية المحادثة): أعدتُ `i18n:check` و`contracts:check-clients` و`lint` و`change-record:check`
و`typecheck` (exit 0) واختبارات الأندرويد نفسها واللنت أعلاه — كلها نجحت.

صور الأندرويد: `apps/android/app/build/shots/agents/android-mcp.png` (نظرتُ فيها: بطاقة «أدوات كور هب» بمجموعتين ومفتاحي «السماح
بالتغييرات» وأدوات الكتابة بلون التحذير وآخر استدعاءين أحدهما مرفوض بسببه بكلماتنا؛ خادم github بـ stdio وملخّص الأمر ونتيجة اختبار
«Hermes connected — 3 tools, 0.8 s.» بأسماء الأدوات؛ كتابة بانتظار المراجعة بنص عربي؛ محرر الخادم بالنوعين والمعاملات وصف
`GITHUB_TOKEN` المحفوظ و«Other keys … timeout») و`android-channels.png` (سطر البوابة، بطاقة واتساب «مراسلة نفسي» مع ملاحظة إعادة
التشغيل وزرها و«2 waiting — Review» و«Change mode»، بطاقة تيليجرام مع «Settings»، ويب هوك بعنوانه وسرّه المخفي، ولوحة إعدادات تيليجرام
بأقسامها وافتراضاتها و«Also changes WhatsApp and other channels»).

CI على #181 بعد الدفع (الالتزام `222c2d08`): كل الفحوص نجحت — Android build, unit tests, lint (7m59s)، Generate the Swift client
وBuild and test on the iOS simulator (6m30s)، Lint/typecheck/contracts/client tests/build، Server unit tests ×3، Web smoke journeys،
Desktop smoke، Docker، Installers ×3، db:generate + db:migrate، Change record، graphify-out.

## المخاطر والرجوع
- حقول `list`/`json` تُرسل الآن قيمًا منظّمة (مصفوفة/JSON) لا نصًا؛ لا محوّل في المركز يعلن هذين النوعين اليوم، فلم يُجرَّب مع مركز حقيقي.
- حفظ الإعداد فوري لكل حقل (كما كان على الهاتف)، فحقل في قسم يعيد التشغيل يعيد هرمز مع كل حقل.
- لم يُجرَّب شيء على هاتفي المالك؛ iOS على محاكي CI فقط.
- الرجوع: `git revert` لالتزامات هذا الفرع في فرع الليلة؛ لا عقد ولا بيانات.

## التسليم والخطوة التالية
يُدمج في `night/2026-09-27-apps` (#181). التالي: تجربة المالك على الهاتفين (خاصة اختبار MCP وإعدادات تيليجرام وإعادة التشغيل).
