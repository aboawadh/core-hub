# المهام على الجوال — الجزء الأول: تفاصيل المهمة والإنشاء والتعديل والحذف والإيقاف والإسناد
المسؤول: twuijri · الفرع: night/apps-tasks-1 · الحالة: done

## المشكلة والهدف
الدفعة 2 من ليلة التطبيقات (`2026-09-27-twuijri-night-apps.md`، قائمة الفجوات §2). لوحة المهام في الآيفون
والأندرويد كانت تعرض الأعمدة وتنقل البطاقات فقط (والأندرويد يُسند ويبدأ)؛ لا تفتح المهمة وحدها، ولا تُنشئ مهمة،
ولا تعدّل أو تحذف أو توقف أو تلغي الإسناد، والآيفون لا يُسند أصلًا. وقائمة «انقل إلى» كانت تعرض كل الحالات فيرفض
الخادم أكثرها (والحجب بلا سبب). الهدف: ما يفعله الويب في لوحة المهام لمهمة واحدة، في التطبيقين، بمكوّنات كل تطبيق.

## القرار والموافقات
- **تفاصيل المهمة** ورقة في التطبيقين تُفتح بلمس البطاقة (وفي الآيفون أيضًا من قائمة الضغط المطوّل): العنوان،
  الحالة، الأولوية، المشروع (اسمه من `tasks.listProjects` لبروفايل المهمة)، المسنَدة إليه (وكيل أو شخص)، الاستحقاق،
  الوصف مرسومًا Markdown، ما تعتمد عليه (المنتظَر منها بالاسم وحالته، والباقي عددًا — للقراءة فقط)، آخر تشغيل
  (`TaskDetail.runs[0]`: حالته ووقت بدئه) ومدخل محادثتها. كل فعل في بروفايل المهمة نفسه (ADR 0016).
- **الأفعال**: نقل (فقط النقلات التي يقبلها جدول الانتقالات نفسه الذي تستعمله اللوحة، كل معنى مرة واحدة — سبب
  للحجب وتأكيد للأرشفة؛ والقائمة نفسها صارت في قائمة الضغط المطوّل لبطاقة الآيفون بدل «كل الحالات»)، إسناد/إعادة
  إسناد (وكيل من وكلاء بروفايل المهمة القابلين للتشغيل، تعليمات اختيارية، «ابدأ الآن» مفعّل افتراضيًا، وتنبيه بما لم
  يكتمل مما تعتمد عليه كما في الويب §93)، إلغاء الإسناد، إيقاف المهمة الجارية، تعديل (العنوان، الوصف، الأولوية،
  المشروع)، حذف بعد السؤال. بطاقة من لوحة Hermes لا تُسنَد ولا يُلغى إسنادها (Hermes يوزّعها، §103).
- **مهمة جديدة**: زر **+** في شريط المهام العلوي (التطبيقان)، في البروفايل المختار كما في الويب («مهمة جديدة في …»):
  العنوان، الوصف (Markdown)، المشروع أو «قائمة البروفايل نفسه»، الأولوية، واختياريًا وكيل و«ابدأ الآن». الإنشاء
  `tasks.createTask` ثم `tasks.assignTask(start: true)` عند الطلب (مثل «أسند وابدأ» في الويب)، مع `Idempotency-Key`
  (ULID لكل ورقة) حتى لا يصنع الضغط الثاني على «إنشاء» بعد فشل البدء مهمة ثانية.
- الوصف الممسوح يُرسل نصًا فارغًا لا `null`: العميلان المولَّدان يحذفان الحقل الفارغ فلا يستطيعان إرسال `null`
  (الخادم يخزن النص الفارغ، والتطبيقان والويب يعرضانه «لا وصف»). مقترح — للمالك أن يؤكد.
- الإسناد نموذج (`FormSheet`/`FormBody`) بمفتاح «ابدأ الآن» بدل زرّين كما في الويب؛ وفي الأندرويد يحلّ النموذج
  محلّ التفاصيل داخل الورقة نفسها بدل ورقة فوق ورقة. مقترح — للمالك أن يؤكد.
- حُذفت شيفرة ميتة في منطقة المهام: `TaskRow` و`TaskColumns.order` في الآيفون، و`Board.moveTargets`/`canStart`
  و`TaskSheet`/`AssignDialog` القديمة ونصوصها الثلاثة (`tasks_assign`, `tasks_start`, `tasks_start_now`) في الأندرويد.
- خارج النطاق (الدفعة 5): التعليقات، قائمة التحقق، السجل، إدارة المشاريع، الأفعال الجماعية. لم أمسّ شاشات المحادثة.

## العقد (ما تغيّر في packages/contracts، أو «لا شيء»)
لا شيء. العمليات المستعملة كلها موجودة: `tasks.getTask`، `createTask`، `updateTask`، `deleteTask`، `moveTask`،
`assignTask`، `unassignTask`، `stopTask`، `listProjects`، و`agents.list`.

## الملفات والتأثير
- iOS: جديدة `Screens/Tasks/TaskRules.swift` (القواعد)، `TaskDetailView.swift`، `NewTaskSheet.swift`؛ معدّلة
  `Screens/TasksBoard.swift` (زر +، فتح التفاصيل، قائمة النقل من القواعد، تأكيد الأرشفة) و`Screens/Tasks/TasksScreen.swift`؛
  نصوص `i18n/tasks.{en,ar}.json`؛ اختبار `CoreHubTests/TaskDetailTests.swift`.
- Android: جديدة `ui/screens/TaskDetail.kt` (`TaskRules`، `TaskFacts`، `TaskOps`، الورقة، `TaskDetailBody`، `NewTaskSheet`)؛
  معدّلة `TasksScreen.kt` (الورقة الجديدة، `NewTaskButton`)، `TasksBoard.kt` (`actionLabel` داخلية)، `MainActivity.kt`
  (سطر واحد: زر + في شريط المهام)؛ نصوص `res/values*/strings_tasks.xml`؛ اختبارات `parity/TaskDetailTest.kt` (قواعد +
  خادم مصطنع) و`shots/TaskDetailShots.kt`، وتحديث `ui/DestinationsTest.kt` (النقلات صارت من الجدول لا «كل عمود آخر»).
- `docs/STATUS.md` (بند «Tasks on the phones, part I»).

## الفحوص (الأوامر ونواتجها الفعلية)
```
$ gradlew :app:testDebugUnitTest --tests TaskDetailTest BoardTest DestinationsTest StringsParityTest NavigationParityTest UiKitPolicyTest TaskDetailShots   (mj-run, JDK 17)
BUILD SUCCESSFUL
TaskDetailTest tests=11 failures=0 · BoardTest 5/0 · DestinationsTest 3/0 · StringsParityTest 4/0
NavigationParityTest 10/0 · UiKitPolicyTest 1/0 · TaskDetailShots 2/0

$ gradlew :app:lintDebug
BUILD SUCCESSFUL — 0 errors, 59 warnings (none in the new files; the three task strings it called unused are removed)

$ gh workflow run ios.yml --ref night/apps-tasks-1   (run 36274476398)
✓ Generate the Swift client (CoreHubClient) in 47s
✓ Build and test on the iOS simulator in 6m56s — Executed 198 tests, with 0 failures
Test Case '-[CoreHubTests.TaskDetailTests testTheDetailOffersOnlyTheMovesTheHubAccepts]' passed
Test Case '-[CoreHubTests.TaskDetailTests testStopAssignAndUnassignFollowTheStatusAndTheCardsOwner]' passed
Test Case '-[CoreHubTests.TaskDetailTests testWhatItWaitsForIsSaidOnlyBeforeItRuns]' passed
Test Case '-[CoreHubTests.TaskDetailTests testAnEditSendsOnlyWhatChanged]' passed
Test Case '-[CoreHubTests.TaskDetailTests testANewTaskLandsInTheProfilesListAndStartsOnlyWhenAsked]' passed
Test Case '-[CoreHubTests.TaskDetailTests testAnAssignCarriesTheAgentItsWordsAndWhetherToStart]' passed

$ pnpm contracts:check-clients → check-clients  OK — 876 client file(s) scanned, 254 contract path(s) known.
$ pnpm i18n:check              → ios: 804 keys, ar/en in parity … android: Arabic resources use Latin digits … OK
$ pnpm lint                    → All matched files use Prettier code style!
$ pnpm typecheck               → exit=0
```
صورة الأندرويد: `apps/android/app/build/shots/tasks/android-detail.png` و`android-detail-dark.png`.
الاختبارات الجديدة تفشل على الشيفرة القديمة (لا توجد `TaskRules`/`TaskOps` فيها، و`DestinationsTest` كان يتوقع كل الحالات).

## المخاطر والرجوع
- لم يُجرَّب على هاتف المالك؛ الآيفون مُتحقَّق منه على محاكي CI فقط.
- زر + في الأندرويد يعتمد على أن `TasksViewModel` واحد لشاشة المهام وشريطها (نفس `ViewModelStoreOwner`).
- الرجوع: `git revert` لالتزامات هذا الفرع في فرع الليلة؛ لا عقد ولا بيانات.

## التسليم والخطوة التالية
يُدمج في `night/2026-09-27-apps` (#181). الدفعة 5 (المهام II) تبني فوق `TaskDetailView.swift` و`TaskDetail.kt`: التعليقات،
قائمة التحقق، السجل، المشاريع، الأفعال الجماعية.
