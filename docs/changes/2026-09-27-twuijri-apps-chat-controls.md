# أدوات المحادثة في تطبيقَي الجوال (الدفعة 1)
المسؤول: twuijri · الفرع: night/apps-chat-controls · الحالة: review

## المشكلة والهدف
قائمة الفجوات (`apps-gap-list.md` §1، الدفعة 1): المحادثة الجديدة والمحادثة في الآيفون والأندرويد ينقصها ما يقدّمه الويب:
شريحة النموذج وشريحة وضع الصلاحيات في محرّر الرسالة، اختيار مجلد العمل لمحادثة جديدة، إجراءات المحادثة الواحدة (إعادة
تسمية، تثبيت، أرشفة، حذف، نسخ إلى محادثة جديدة) من رأس المحادثة ومن قائمة المحادثات، إجراءات الرسالة (نسخ، قراءة بصوت،
رد، تفريع من هنا)، وتوجيه الرد الجاري وضغط السياق. الهدف: نفس عمليات العقد التي يستعملها الويب، بشاشة جوال مضغوطة وغير
مزدحمة (شرائح صغيرة، قائمة «…»)، في التطبيقين.

## القرار والموافقات
- **شرائح صغيرة فوق المحرّر** تفتح كل منها صفحة سفلية (sheet): مجلد العمل (محادثة جديدة فقط)، النموذج (بحث + تجميع حسب
  المزوّد، قيمته `key` من الكتالوج `<provider>/<model>` كما يفعل الويب)، وضع الصلاحيات (كل وضع مع سطر يشرح الفرق — طلب المالك
  2026-09-22)، و«وجّه» أثناء الرد الجاري لوكيل يملك `steer`. اخترتُ الصفحة السفلية بدل القائمة المنسدلة لأن الشرح يحتاج مساحة.
- **وضع الصلاحيات** هو حقل الوكيل نفسه (`approval_mode` لـ ACP، `approvals_mode` لـ Hermes) كما في الويب
  (`agents.getSettings` / `agents.updateSettings`)؛ يسري على كل محادثات الوكيل في البروفايل، والمدير وحده يغيّره (غير المدير
  يرى الأوضاع معطّلة مع السبب). وكيل لا يعلن الحقل: لا شريحة.
- **قائمة «…» في رأس المحادثة** بترتيب الويب: إعادة تسمية، تثبيت/إلغاء، أرشفة/إلغاء، نسخ إلى محادثة جديدة، ضغط السياق
  (وكيل يملك `compress`، ومعطّل مع السبب أثناء رد جارٍ)، تصدير، حذف بعد تأكيد. بعد الأرشفة أو الحذف يعود الجوال إلى محادثة
  جديدة (كما `/archive` في الويب). محادثة الوكيل العام: ضغط وتصدير فقط.
- **قائمة المحادثات**: ضغطة مطوّلة تعرض «تحديد» (وضع الدفعة كما كان) ثم إعادة التسمية والتثبيت والأرشفة والحذف لتلك المحادثة.
  لم أضف السحب (swipe): القائمة في الآيفون ليست `List` فلا سحب أصلي فيها، والضغطة المطوّلة تكفي في التطبيقين.
- **إجراءات الرسالة**: تحت رد الوكيل «نسخ» ظاهرة و«…» (اقرأ بصوت عالٍ، رد، فرّع من هنا)؛ رسالتك بضغطة مطوّلة (نسخ، رد، فرّع).
  الرد يرسل `RunCreate.reply_to_message_id` مع شريط «ردًّا على…» فوق المحرّر؛ التفريع `sessions.fork` مع `at_message_id` ويفتح
  المحادثة الجديدة. الغرف لا تتغيّر (نسخ فقط كما كانت).
- **التوجيه**: `sessions.steerRun`؛ إن رفضه الرد الجاري تُرسل الكلمات رسالةً تالية (ما يفعله الويب وHermes). **الضغط**:
  `sessions.compress` بلا تركيز، ويظهر سطر النتيجة (من نحو X إلى Y رمز / لا شيء يُضغط / ضغط جارٍ).
- مقترح — للمالك أن يؤكد: لا تعود المحادثة إلى «النموذج الافتراضي» بعد اختيار نموذج، ولا يُفرَّغ العنوان ليعيد المركز التسمية،
  لأن العميلين المولَّدين يحذفان الحقول `null` من `SessionPatch` (merge-patch)، فلا يمكن إرسال `model: null` أو `title: null`
  دون مسار يدوي. في المحادثة الجديدة يبقى «الافتراضي» متاحًا.
- أيقونات Lucide جديدة للتطبيقين: `pin`، `pin-off`، `archive`، `archive-restore`، `cpu`، `git-fork`، `reply`، `shrink`،
  `corner-down-right`.

## العقد (ما تغيّر في packages/contracts، أو «لا شيء»)
لا شيء. كل العمليات موجودة: `models.listCatalogue`، `agents.getSettings`، `agents.updateSettings`، `sessions.listWorkingDirs`،
`sessions.create` (`model`، `working_dir`)، `sessions.update`، `sessions.delete`، `sessions.fork`، `sessions.compress`،
`sessions.steerRun`، `sessions.createRun` (`reply_to_message_id`).

## الملفات والتأثير
- iOS: جديد `Chat/ChatControls.swift` (القواعد، `ChatActions`، `ChatControlsModel`)، `Chat/ChatControlsViews.swift` (الشرائح
  والصفحات السفلية، تنبيه إعادة التسمية، إجراءات الرسالة، شريط الرد)؛ تعديل `Chat/ChatScreen.swift` (قائمة «…»، الشرائح، الرد،
  التوجيه، إجراءات الرسالة)، `Chat/ChatModel.swift` (تغيير/حذف/تفريع/ضغط/توجيه، الرد في الإرسال)، `Chat/ChatState.swift`
  (النموذج، التثبيت، الأرشفة، المصدر، المجلد + `absorb`)، `Chat/NewChatScreen.swift` (المجلد والنموذج عند الإنشاء)،
  `Sessions/SessionList.swift` (قائمة الضغطة المطوّلة)، `Shell/ShellView.swift` (فتح التفريع والرجوع بعد الأرشفة/الحذف)؛
  نصوص `i18n/chat_controls.{en,ar}.json`؛ اختبار `CoreHubTests/ChatControlsTests.swift`.
- Android: جديد `chat/ChatControls.kt` (القواعد و`ChatActions`)، `ui/components/ChatControlsUi.kt` (الشرائح والصفحات السفلية،
  حوار إعادة التسمية، إجراءات الرسالة، شريط الرد، سطر النتيجة)، `ui/screens/ChatMenu.kt` (قائمة «…» بدل زر التصدير في
  `MainActivity.kt`)؛ تعديل `ChatViewModel.kt`، `ChatScreen.kt` (`rememberChatViewModel` مشترك مع الرأس)، `ChatParts.kt`
  (`LocalMessageActions`)، `chat/ChatReducer.kt` (`ChatSessionInfo` أوسع + `absorb`)، `Shell.kt` و`ShellViewModel.kt` (قائمة
  الضغطة المطوّلة في صف المحادثة)؛ نصوص `values*/strings_chat_controls.xml`؛ اختبار `chat/ChatControlsTest.kt` (MockWebServer)
  وصورة `shots/ChatControlsShots.kt`.
- `scripts/icons/lucide-mobile.json` والأيقونات المولَّدة للتطبيقين.
- لم ألمس شاشات المهام (الدفعة 2).

## الفحوص (الأوامر ونواتجها الفعلية)
```
$ gradle :app:testDebugUnitTest --tests 'hub.core.android.chat.*' --tests 'hub.core.android.ui.*' --tests 'hub.core.android.nav.*'   (mj-run, JDK 17)
BUILD SUCCESSFUL — tests 80 skipped 0 failed 0 (ChatControlsTest 8/8، ChatReducerTest، ChatsListTest، StringsParityTest، UiKitPolicyTest، NavigationParityTest)
$ gradle :app:testDebugUnitTest --tests 'hub.core.android.shots.ScreenShots'  → tests 4 failures 0
$ gradle :app:testDebugUnitTest --tests 'hub.core.android.shots.ChatControlsShots' → tests 1 failures 0
$ gradle :app:lintDebug → 0 errors, 57 warnings
$ pnpm lint        → All matched files use Prettier code style!
$ pnpm typecheck   → exit=0
$ pnpm i18n:check  → i18n:check  ios: 816 keys, ar/en in parity … i18n:check  OK
$ pnpm contracts:check-clients → check-clients  OK — 877 client file(s) scanned, 254 contract path(s) known.
$ node scripts/icons/lucide-mobile.mjs --check → lucide: 105 shared + 18 Android icon(s) from lucide-static 1.48.0 up to date
```
صور الأندرويد: `apps/android/app/build/shots/{light,dark}/{en-US,ar-SA}/android-01-chat.png` (شريحة النموذج فوق المحرّر، «نسخ» و«…»
تحت الردود)، `android-05-new-chat.png` (شريحتا المجلد والنموذج)، `apps/android/app/build/shots/chat-controls/android-chips.png`.
iOS لا يُبنى على لينكس: انظر نتيجة `ios.yml` أدناه.

## المخاطر والرجوع
- الكتالوج يُقرأ كاملًا (حتى 10 صفحات × 200) عند فتح المحادثة، ويُحفظ لكل بروفايل في الآيفون حتى لا تتأخر الشريحة.
- `PersonBubble` في الأندرويد صار يلتقط الضغطة المطوّلة داخل المحادثة (لا في الغرف)؛ في الآيفون `contextMenu` على رسالتك يأخذ
  الضغطة المطوّلة بدل تحديد النص، و«نسخ» في القائمة.
- لم يُجرَّب على هاتف المالك ولا على مركزه الحقيقي؛ اختبارات JVM ضد خادم وهمي وصور Robolectric، وiOS باختبارات الوحدة على محاكي CI.
- الرجوع: `git revert` لالتزامات هذا الفرع في فرع الليلة؛ لا عقد ولا بيانات.

## التسليم والخطوة التالية
يُدمج في `night/2026-09-27-apps` (#181). التالي من الدفعة 6: حلقة السياق وسجل التشغيلات والوكلاء الفرعيون وتغييرات الملفات.
