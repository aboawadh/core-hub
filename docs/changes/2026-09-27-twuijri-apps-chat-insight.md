# رؤية المحادثة في تطبيقَي الجوال (الدفعة 6)
المسؤول: twuijri · الفرع: night/apps-chat-insight · الحالة: done

## المشكلة والهدف
قائمة الفجوات (`apps-gap-list.md` §1 و§2، الدفعة 6أ و6ب معًا): محادثة الويب تُظهر ما لا يُظهره الآيفون والأندرويد:
حلقة السياق (كم امتلأت نافذة النموذج وما الذي يملؤها) مع الضغط، سجل تشغيلات المحادثة، لوحة الوكلاء الفرعيين
(الجارية والمنتهية، وإيقاف أحدهم وتوجيهه وقراءة مخرجاته)، الملفات التي غيّرها كل تشغيل مع الفروق (وحيّةً أثناء
التشغيل)، وملفات المحادثة (زر «الملفات» في رأس محادثة الويب). الهدف: نفس عمليات العقد، على شاشة جوال غير مزدحمة:
كل هذا خلف قائمة «…» في رأس المحادثة وشريحة صغيرة واحدة، لا شيء ظاهر دائمًا.

## القرار والموافقات
- **الحلقة في شريط المحادثة العلوي** (بجانب «…»): دائرة صغيرة بلون شريحتها (عادي، تحذير من 70%، خطر من 90%) تظهر
  فقط حين تُعرف النافذة: تقرير الوكيل (`Session.context` و`context.updated`)، وإلا `context_window` من الكتالوج مع
  آخر دور عدّه المزوّد — كما يفعل الويب (`ContextRing.tsx`)، ولا رقم مخترع أبدًا. الويب يضعها بجانب المايك؛ في الجوال
  اخترتُ الشريط العلوي حتى لا يزدحم المحرّر (شرائح الدفعة 1 فوقه). الضغط عليها يفتح **صفحة السياق**.
- **صفحة السياق**: النسبة، الشريط، «X من Y رمز»، مصدر الرقم، ثم «ما الذي يملؤها» من `sessions.getContextBreakdown`
  (تُقرأ فقط والصفحة مفتوحة، وتُقرأ ثانيةً حين يتغيّر العدد، §102)، ثم **الضغط مع تركيز اختياري**
  (`SessionCompressRequest.focus`، مقصوص إلى 2000 حرف، والفارغ = جسم فارغ يضغط المحادثة كلها) لوكيل يملك `compress`،
  ومعطّل مع السبب أثناء رد جارٍ. أضفتُ `focus` إلى `ChatActions.compress` و`ChatModel/ChatViewModel.compress`
  (افتراضيه فارغ، فعنصر «ضغط السياق» في قائمة الدفعة 1 لا يتغيّر).
- **عدّاد الوكلاء الفرعيين** في الشريط العلوي، يظهر فقط ما دام أحدهم يعمل (أيقونة bot مع العدد)، ويفتح صفحتهم.
  القائمة تُقرأ مرة عند فتح المحادثة وتبقى حيّة من أحداث `subagent.*` (على مستوى البروفايل) مع قراءة كل 15 ثانية
  ما دام أحدهم يعمل، كما في الويب.
- **قائمة «…»** صار فيها قسم أول: السياق، التشغيلات، الوكلاء الفرعيون، الملفات المعدّلة، الملفات؛ ثم إجراءات الدفعة 1
  كما هي (لم أغيّر ترتيبها ولا منطقها).
- **التشغيلات** (`sessions.listRuns` صفحةً صفحة، الأحدث أولًا): الحالة، النموذج، الوقت، المدة (تعدّ للجاري)، الرموز
  دخولًا وخروجًا، والتكلفة بقاعدة الويب (`costLabel`: لا سطر بلا سعر، «0 USD» للمجاني، ثم منزلتان أو أربع)، وسبب الفشل.
  الويب لا يعرض قائمة تشغيلات بل يعرض هذه البيانات تحت كل دور — **مقترح — للمالك أن يؤكد**: صفحة قائمة في الجوال،
  والتكلفة تظهر فيها دائمًا (الويب يُظهرها تحت الدور فقط حين يُفعّل «show_cost»).
- **الوكلاء الفرعيون**: الجارون شجرةً (الابن تحت أبيه، ومن غاب أبوه يبقى بعمقه) ثم «انتهوا (n)» مطويّة؛ لكل واحد
  الهدف، الحالة، الساعة، النموذج، عدد الأدوات وآخرها، وكلماته الأخيرة إن انتهى. حيث `support: full`: إيقاف
  (`interruptSubagent`)، توجيه بنص (`steerSubagent`، مقصوص إلى 4000، يظهر «أُرسلت» أو «لم يعد يقبل التوجيه»)،
  و«المخرجات»: آخر نص الوكيل الفرعي (`tailSubagent`، يُقرأ كل ثانيتين ما دام يعمل) — هذا «فتح نصّه» في الجوال؛
  زر الويب «عرض» الذي يفتح لسان المسار (Trajectory) غير موجود لأن المسار ليس في الجوال (§3 في قائمة الفجوات).
- **الملفات المعدّلة**: التشغيل الجاري أولًا («الجاري الآن، حتى اللحظة» من `getRunChanges` كل 4 ثوانٍ والصفحة
  مفتوحة؛ 404 = لا شيء يُقارن)، ثم كل تشغيل غيّر شيئًا (`listChanges`، 20 في الصفحة، تُقرأ ثانيةً حين ينتهي تشغيل):
  الوقت، «n ملفات +a −d»، ولكل ملف نوع التغيير ومساره (ومن أين أُعيدت تسميته) وعدد الأسطر. الملف يفتح **صفحة الفروق**:
  موحّدة، بخط ثابت العرض، برقمي السطر قبل وبعد، تتمرّر أفقيًا، ومن اليسار إلى اليمين في اللغتين؛ وتقول لماذا لا فروق
  (جارٍ، ثنائي، كبير جدًا، لا نسخة سابقة، مقطوعة). «افتح» يعرض الملف كما هو الآن عبر عارض ملفات الجوال (#179)،
  بعد البحث عنه في `listFiles` ليأخذ حجمه ووقته (فلا يُعرض نسخة قديمة محفوظة)؛ الملف المحذوف لا يُفتح.
- **الملفات**: `sessions.listFiles` بصفوف رسائل الدفعة #179 نفسها (تنزيل مع التقدّم والإلغاء، ثم العارض أو المشغّل أو
  المشاركة)، مع ملاحظة المجلد وإن كانت القائمة مقصوصة. تُقرأ ثانيةً حين ينتهي تشغيل، وبالسحب في الآيفون.
- أيقونة Lucide جديدة للتطبيقين: `file-diff` (والبقية موجودة: `gauge`، `rotate-ccw-clock`، `bot`، `folder`، `eye`…).
- الأرقام إنجليزية (123) في اللغتين (§113): الآيفون بلغة التطبيق مع `latn`، والأندرويد `Locale.US`.
- **لم أبنِ** من الدفعة 6أ: ورقة المهام الخلفية (`background.list/stop`) — ليست من شاشة المحادثة وخارج نطاق المهمة
  المرسلة؛ والتوجيه أثناء الرد بُني في الدفعة 1.

## العقد (ما تغيّر في packages/contracts، أو «لا شيء»)
لا شيء. كل العمليات موجودة: `sessions.getContextBreakdown`، `sessions.compress` (`focus`)، `sessions.listRuns`،
`sessions.listSubagents`، `sessions.interruptSubagent`، `sessions.steerSubagent`، `sessions.tailSubagent`،
`sessions.listChanges`، `sessions.getRunChanges`، `sessions.getRunChangeDiff`، `sessions.listFiles`، وحدث
`context.updated` و`subagent.*`. كل الطلبات من العميلين المولَّدين (`contracts:check-clients` OK).

## الملفات والتأثير
- iOS: جديد `Chat/ChatInsight.swift` (القواعد، `ChatInsightCalls`، `ChatInsightModel` الحي للوكلاء الفرعيين)،
  `Chat/ChatInsightViews.swift` (الشريط، الحلقة، قسم القائمة، صفحة السياق، التشغيلات)، `Chat/ChatInsightWork.swift`
  (الوكلاء الفرعيون ومخرجاتهم، الملفات المعدّلة والفروق، الملفات)؛ تعديلات صغيرة: `Chat/ChatScreen.swift` (عنصر في
  الشريط، القسم في «…»، الصفحة، `contextUse`)، `Chat/ChatControls.swift` (`ModelOption.window`، `compress(focus:)`)،
  `Chat/ChatModel.swift` (`compress(focus:)`)؛ نصوص `i18n/chat_insight.{en,ar}.json`؛ اختبار
  `CoreHubTests/ChatInsightTests.swift`.
- Android: جديد `chat/ChatInsight.kt` (القواعد و`ChatInsightApi`)، `ui/screens/ChatInsightViewModel.kt`،
  `ui/components/ChatInsightUi.kt` (الشريط، الحلقة، عناصر القائمة، مضيف الصفحات، السياق، التشغيلات)،
  `ui/components/ChatInsightWork.kt` (الوكلاء الفرعيون، الملفات المعدّلة والفروق، الملفات)؛ تعديلات صغيرة:
  `MainActivity.kt` (سطر في شريط المحادثة)، `ui/screens/ChatMenu.kt` (عناصر القائمة)، `chat/ChatControls.kt`
  (`ModelOption.window`، `compress(focus)`)، `ui/screens/ChatViewModel.kt` (`compress(focus)`)، `chat/ChatReducer.kt`
  (`ChatSessionInfo.context` و`context.updated`)، `ui/components/MessageFiles.kt` (`OpenableFile`/`rememberOpener`
  صارا `internal`)؛ نصوص `values*/strings_chat_insight.xml`؛ اختبار `chat/ChatInsightTest.kt` (MockWebServer) وصور
  `shots/ChatInsightShots.kt`.
- `scripts/icons/lucide-mobile.json` والأيقونة المولَّدة للتطبيقين؛ `docs/STATUS.md`؛ سطر في سجل الليلة.
- لم ألمس صفحات الوكلاء (الدفعة 8 تعمل بالتوازي) ولا منطق أدوات الدفعة 1.

## الفحوص (الأوامر ونواتجها الفعلية)
```
$ ./gradlew :app:testDebugUnitTest --tests 'hub.core.android.chat.*' --tests 'hub.core.android.ui.*' --tests 'hub.core.android.nav.*'
    --tests 'hub.core.android.shots.ChatInsightShots' --tests 'hub.core.android.shots.ChatControlsShots'   (mj-run, JDK 17، بعد دمج فرع الليلة)
tests 93 skipped 0 failed 0   (ChatInsightTest 9/9، ChatInsightShots 2/2، ChatControlsTest، ChatReducerTest، StringsParityTest، UiKitPolicyTest، NavigationParityTest…)
$ ./gradlew :app:lintDebug → lint errors: 0, warnings: 64   (خطآن LocalContextGetResourceValueCall في ملفي أُصلحا قبلها)
$ pnpm lint        → All matched files use Prettier code style!
$ pnpm typecheck   → exit=0
$ pnpm i18n:check  → i18n:check  ios: 1200 keys, ar/en in parity … android: Arabic resources use Latin digits … i18n:check  OK
$ pnpm contracts:check-clients → check-clients  OK — 925 client file(s) scanned, 254 contract path(s) known.
$ node scripts/icons/lucide-mobile.mjs --check → lucide: 106 shared + 18 Android icon(s) from lucide-static 1.48.0 up to date
```
صور الأندرويد: `apps/android/app/build/shots/chat-insight/android-insight-{light,dark}.png` (الحلقة بشرائحها الثلاث،
صفّا تشغيل، وفروق موحّدة بأرقام الأسطر والألوان حتى نهاية السطر).
iOS لا يُبنى على لينكس؛ شغّلتُ `ios.yml` على الفرع (الأولى فشلت: `RunRow` اسم موجود في الجدولة، فصار `ChatRunRow`):
```
$ gh workflow run ios.yml --ref night/apps-chat-insight   (run 36283768579)
✓ Generate the Swift client (CoreHubClient)
✓ Build and test on the iOS simulator — Executed 251 tests, with 0 failures
Test Case '-[CoreHubTests.ChatInsightTests testAUnifiedDiffReadsIntoHunksWithLineNumbers]' passed
Test Case '-[CoreHubTests.ChatInsightTests testChangedFilesSayWhatTheyCanShowAndWhenToReadAgain]' passed
Test Case '-[CoreHubTests.ChatInsightTests testCompressSendsTheFocusOrAnEmptyBody]' passed
Test Case '-[CoreHubTests.ChatInsightTests testEveryChatInsightStringExistsInBothLanguages]' passed
Test Case '-[CoreHubTests.ChatInsightTests testRunsReadNewestFirstWithTheirTimeTokensAndCost]' passed
Test Case '-[CoreHubTests.ChatInsightTests testSubagentsRunAsATreeAndTheFinishedNewestFirst]' passed
Test Case '-[CoreHubTests.ChatInsightTests testTheBreakdownSharesTheWholeWindowOrTheirSum]' passed
Test Case '-[CoreHubTests.ChatInsightTests testTheCatalogueCarriesTheModelsWindowForTheRing]' passed
Test Case '-[CoreHubTests.ChatInsightTests testTheRingSaysHowFullTheWindowIsAndWhereTheFigureCameFrom]' passed
```

CI على #181 بعد الدمج (الالتزام `3942e442`): كل الفحوص نجحت — Android build, unit tests, lint (6m25s)، Build and test
on the iOS simulator (5m46s)، Generate the Swift client، Lint/typecheck/contracts/client tests/build، Server unit tests ×3،
Web smoke journeys، Change record، Docker، Desktop، Installers ×3، db:generate + db:migrate.

## المخاطر والرجوع
- الوكلاء الفرعيون يُقرؤون مرة عند فتح كل محادثة (طلب واحد إضافي)، ثم من الأحداث؛ القراءة كل 15 ثانية فقط ما دام أحدهم يعمل.
- الفروق تُرسم كاملة (غير كسولة)؛ المركز يقصّ الفرق الكبير (`truncated`)، فالحجم محدود.
- في الآيفون صف الوكيل الفرعي داخل `List` فيه رابط «المخرجات»، فالضغط على الصف كله قد يفتحها؛ الإيقاف والتوجيه أزرار منفصلة.
- الحلقة في الأندرويد تحتاج نماذج الكتالوج التي تحمّلها شاشة المحادثة (الدفعة 1) لتقدير النافذة حين لا يبلّغ الوكيل.
- لم يُجرَّب على هاتف المالك ولا على مركزه الحقيقي؛ اختبارات JVM ضد خادم وهمي وصور Robolectric، وiOS باختبارات الوحدة على محاكي CI.
- الرجوع: `git revert` لالتزامات هذا الفرع في فرع الليلة؛ لا عقد ولا بيانات.

## التسليم والخطوة التالية
يُدمج في `night/2026-09-27-apps` (#181). الباقي من الدفعة 6: ورقة المهام الخلفية (`background.list/stop`) في شريط
التطبيق العلوي، ولسان المسار (Trajectory) الذي تركته قائمة الفجوات للويب.
