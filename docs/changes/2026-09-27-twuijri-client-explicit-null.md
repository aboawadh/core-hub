# العميلان المولَّدان (Kotlin وSwift) يرسلان `null` حين يطلبه العقد
المسؤول: twuijri · الفرع: night/client-nulls · الحالة: review

## المشكلة والهدف
العميلان المولَّدان للجوال كانا يحذفان كل حقل فارغ من جسم الطلب: Kotlin بـ`explicitNulls = false` (حتى لا يمسح
PATCH ما لم يقصده) وSwift بـ`encodeIfPresent`. فلم يكن التطبيقان يستطيعان قول «اجعل هذا فارغًا»: الرجوع إلى النموذج
الافتراضي (`SessionPatch.model: null`)، وإعادة تسمية المحادثة إلى المركز (`title: null`، §26)، ومسح وصف المهمة
(كانت الدفعة 2 ترسل نصًا فارغًا). والأسوأ: كل حقل **مطلوب** يقبل `null` كان يُحذف فيرفض المركز الجسم (400)، ومنه
`trigger` و`target` في الجدولة، فرقّعتها الدفعة 3 بـ`ScheduleBodies` في كل تطبيق. الهدف: قاعدة عامة مكتوبة في العقد
ومولَّدة للعميلين، وحذف الترقيع، وتفعيل الأفعال التي صارت ممكنة.

## القرار والموافقات
- **القاعدة** (DECISIONS §114، مقترح — للمالك أن يؤكد)، لكل مخطط يحمله جسم طلب JSON (المكوّنات، الأجسام المضمّنة،
  الكائنات المضمّنة في خاصية، عناصر المصفوفة المضمّنة، والأسماء البديلة `$ref`):
  - خاصية **مطلوبة وتقبل `null`** تُكتب دائمًا، `null` إن لم تُعطَ قيمة؛
  - خاصية **اختيارية وتقبل `null`** تبقى غائبة إن لم تُعطَ قيمة (فالـPATCH ما زال يغيّر ما يسمّيه فقط)، وتُكتب `null`
    فقط إذا أدرجها المستدعي في `sendNull` للنموذج: `SessionPatch(sendNull = setOf(SessionPatch.Clearable.MODEL))`
    في Kotlin و`SessionPatch(sendNull: [.model])` في Swift. `Clearable` فيه هذه الخصائص فقط، فيرفض المترجم ما لا
    يُمسح. وإن أُدرجت خاصية ولها قيمة تُرسل قيمتها؛
  - الخصائص للقراءة فقط لا تُكتب، وقراءة الردود لا تتغيّر.
- **كيف تُولَّد**: `packages/contracts/scripts/explicit-nulls.mjs` بعد المولِّد (مثل رقعتي multipart والمسلسِل):
  Swift: `sendNull` و`Clearable` في النموذج، و`encode(to:)` يكتب `encodeNil` حيث تقول القاعدة (النماذج المتداخلة
  تكتب نفسها). Kotlin: النموذج ينفّذ `ExplicitNulls` (ملف جديد بجانب العميل) ويعيد الـnull إلى شجرة JSON، والمتداخل
  والقوائم والخرائط معه، و`ApiClient` يرسل كل جسم JSON عبره. يفشل التوليد بصوت إن تغيّر شكل ناتج المولِّد، أو إن
  كان حقل يقبل `null` داخل كائن مضمّن لا يُعرف اسمه (اجعله مكوّنًا). 77 نموذجًا في Kotlin و71 في Swift.
- **رُفض**: `explicitNulls = true` (كل PATCH يمسح ما لم يسمّه)، ونوع ثلاثي الحالة لكل خاصية (`NullEncodable` في
  مولِّد Swift، `Patch<T>` في Kotlin) لأنه يغيّر نوع كل حقل وكل موضع يبني نموذجًا، ومعترضات لكل تطبيق (ما فعلته الدفعة 3).
- **حُذف** `ScheduleBodies` من التطبيقين (iOS: الملف وسطره في `BodilessRequests`؛ Android: الملف ومعترض عميل الجدولة).
- **فُعّل** (تغييرات صغيرة):
  - «النموذج الافتراضي» في منتقي النموذج لمحادثة مفتوحة أيضًا (كان للمحادثة الجديدة فقط) → `model: null`، كما يرسل الويب.
  - «تسمية تلقائية» في قائمة «…» لمحادثة لها عنوان → `title: null` فيسمّيها المركز من أول دور (§26). الويب لا يعرض هذا
    الفعل؛ العقد يسمح به. مقترح — للمالك أن يؤكد.
  - وصف المهمة الممسوح (أو الفراغ فقط) يُرسل `null` بدل النص الفارغ.

## العقد (ما تغيّر في packages/contracts، أو «لا شيء»)
لا تغيير في `openapi.yaml` ولا في شكل أي طلب. تغيّر ما يولّده `generate:native`: `scripts/explicit-nulls.mjs` (جديد)،
`scripts/generate-native.mjs` (يستدعيه)، `README.md` (القاعدة)، و`docs/contracts/DECISIONS.md` §114.

## الملفات والتأثير
- العقد: `packages/contracts/scripts/explicit-nulls.mjs`، `scripts/generate-native.mjs`، `tests/explicit-nulls.test.ts`،
  `README.md`؛ `docs/contracts/DECISIONS.md` §114.
- Android: حُذف `data/ScheduleBodies.kt`؛ `data/Hub.kt` (بلا معترض)، `chat/ChatControls.kt` (`modelPatch`،
  `Action.AUTO_TITLE`)، `ui/screens/ChatMenu.kt`، `ChatScreen.kt` (`allowDefault = true`)، `ChatViewModel.kt`
  (`setModel(null)`)، `ui/screens/TaskDetail.kt`؛ نص `chat_controls_auto_title` في `strings_chat_controls.xml` (en/ar)؛
  اختبارات `chat/ChatControlsTest.kt`، `parity/SchedulesTest.kt`، `parity/TaskDetailTest.kt`.
- iOS: حُذف `Hub/ScheduleBodies.swift`؛ `Hub/HubAPI.swift`، `Chat/ChatControls.swift`، `Chat/ChatControlsViews.swift`
  (تعليق)، `Chat/ChatModel.swift`، `Chat/ChatScreen.swift`، `Screens/Tasks/TaskRules.swift`؛ `i18n/chat_controls.{en,ar}.json`؛
  اختبارات `ChatControlsTests.swift`، `SchedulesTests.swift`، `TaskDetailTests.swift`.
- `docs/STATUS.md` (بند جديد، وتصحيح ثلاثة أسطر عن المحادثة والمهام والجدولة)، وسطر في سجل الليلة.
- أثر جانبي مقصود: كل حقل مطلوب يقبل `null` في أي جسم طلب يُرسل الآن (مثل `ConfigFileWrite.revision`،
  `WorkspaceTextWrite.etag`، `DeliveryTarget`، `WorkflowNode`)؛ كانت هذه الأجسام تُرفض من قبل إن كان الحقل فارغًا.

## الفحوص (الأوامر ونواتجها الفعلية)
```
$ pnpm --filter @corehub/contracts generate:native   (JDK 17)
contracts:generate:native  kotlin: explicit nulls in 77 request model(s)
contracts:generate:native  swift: explicit nulls in 71 request model(s)
contracts:generate:native  OK

$ (packages/contracts) vitest run            → Tests  58 passed (58)  (منها explicit-nulls.test.ts: 11)

$ gradlew :client:compileKotlin              → نجح
$ gradlew :app:testDebugUnitTest (كل الاختبارات)   → suites 55 tests 297 failures 0 errors 0
  منها ChatControlsTest 9/0، SchedulesTest 11/0، TaskDetailTest 11/0، StringsParityTest 4/0

$ تحقق لمرة واحدة بمدقّق العقد في الخادم (createContractIndex، ملف vitest مؤقت لم يُحفظ)،
  على الأجسام التي أرسلها عميل Android فعلًا إلى MockWebServer:
schedules.create         OK   interval / cron / once   — والجسم نفسه بلا nulls (العميل القديم): REFUSED "must have required property 'expression'" …
schedules.previewTrigger OK   interval / cron / once   — بلا nulls: REFUSED
schedules.update         OK   interval / cron / once   — بلا nulls: REFUSED
schedules.update         OK   {"enabled":false}
sessions.update          OK   {"model":null}  و {"title":null}
tasks.updateTask         OK   {"description":null}

$ pnpm lint                     → All matched files use Prettier code style! (exit 0)
$ pnpm typecheck                → exit 0
$ pnpm contracts:lint           → contracts:lint  OK
$ pnpm contracts:check-clients  → check-clients  OK — 899 client file(s) scanned, 254 contract path(s) known.
$ pnpm i18n:check               → ios: 1000 keys, ar/en in parity … android: Arabic resources use Latin digits … OK
```
iOS لا يُبنى على Linux: تُقرأ الشيفرة بعناية ويتحقق منها CI (نتيجة `ios.yml` وCI الـPR #181 تُضاف أدناه).
الاختبارات الجديدة تفشل على الشيفرة القديمة: لا `sendNull`/`Clearable`/`modelPatch`/`AUTO_TITLE` فيها، وأجسام
`{"model":null}` و`{"title":null}` و`"expression":null` لا تخرج من العميل القديم بلا `ScheduleBodies`.

## المخاطر والرجوع
- كل جسم JSON في Android يمرّ الآن بـ`ExplicitNulls.encodeToString`؛ النموذج الذي لا ينفّذ الواجهة يُسلسل كما كان.
  ترميز نموذج خارج `ApiClient` (بـ`Serializer` مباشرة) لا يضيف الـnull — لا موضع كهذا لطلبات اليوم.
- ترتيب المفاتيح في Android: الـnull المضاف يأتي آخر الكائن (لا أثر له في JSON).
- iOS لم يُبنَ محليًا؛ لم يُجرَّب على هاتف المالك ولا مع خادم حقيقي.
- الرجوع: `git revert` لالتزامات هذا الفرع في فرع الليلة يعيد السلوك القديم ومعه `ScheduleBodies` (حُذف في هذا
  الفرع نفسه)، فتبقى الجدولة مقبولة؛ لا عقد ولا بيانات.

## التسليم والخطوة التالية
يُدمج في `night/2026-09-27-apps` (#181). التالي: تأكيد المالك لـ§114 ولفعل «تسمية تلقائية»، وتجربة الجدولة والنموذج
الافتراضي على الهاتفين.
