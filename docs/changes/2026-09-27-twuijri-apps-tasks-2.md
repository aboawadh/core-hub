# المهام على الجوال — الجزء الثاني: قائمة التحقق والتعليقات والمشاريع والتحديد الجماعي
المسؤول: twuijri · الفرع: night/apps-tasks-2 · الحالة: done

## المشكلة والهدف
الدفعة 5 من ليلة التطبيقات (`2026-09-27-twuijri-night-apps.md`، قائمة الفجوات §2)، فوق الجزء الأول
(`2026-09-27-twuijri-apps-tasks-1.md`). ورقة المهمة في الآيفون والأندرويد لم تكن تعرض التعليقات ولا قائمة
التحقق ولا تعريف الإنجاز والقيود، ولا تغيّر تاريخ الاستحقاق؛ واللوحة لا تُرشَّح بالمشروع ولا تدير المشاريع ولا
تحدد عدة بطاقات. الهدف: ما يفعله الويب في المهام (`packages/web/src/tasks/*`) في التطبيقين، وما طلبه المالك
في الدفعة مما يدعمه العقد.

## القرار والموافقات
- **ورقة المهمة** (التطبيقان) تضيف:
  - «ابدأ تلقائيًا» (`auto_start`) كما في الويب، يُحفظ عند التبديل.
  - **قائمة التحقق** (المهام الفرعية في العقد): تعليم/إلغاء، إضافة، حذف، وسحب لإعادة الترتيب (الآيفون: ضغط
    مطوّل على الصف في القائمة، والسحب جانبًا للحذف؛ الأندرويد: ضغط مطوّل على مقبض الصف). بعد السحب يُكتب
    `index` الجديد للصفوف التي تغيّر مكانها فقط، والترتيب الجديد يظهر فورًا. الويب يعرض العدد على البطاقة فقط؛
    هذا على الجوال من العقد (`tasks.createSubtask/updateSubtask/deleteSubtask`) لأن المالك طلبه. مقترح — للمالك أن يؤكد.
  - **تعريف الإنجاز والقيود** (§104) كما في الويب: إضافة بند وحذفه، والتعليم للمراجع فقط والمهمة في المراجعة؛
    تُرسل القائمة كلها عند كل تغيير (الويب يحفظ بزر «حفظ»؛ الجوال يحفظ فورًا). تعديل نص بند قائم غير متاح
    (يُحذف ويُضاف). مقترح — للمالك أن يؤكد.
  - **التعليقات**: عرضها (الكاتب، الوقت، Markdown) وكتابة تعليق (على بطاقة Hermes يُقال على بطاقة Hermes).
  - **بطاقة Hermes** تعرض سجلها من Hermes (التشغيلات والأحداث، §103) بدل القوائم، كما في الويب.
  - **تاريخ الاستحقاق** في نموذج التعديل: حقل «تاريخ ووقت» جديد في النموذج المشترك للتطبيقين (`FormKind.date`
    / `FormKind.Date`، القيمة `yyyy-MM-ddTHH:mm` بتوقيت الهاتف وأرقام لاتينية): الآيفون منتقي النظام وزر مسح،
    الأندرويد حقلان مكتوبان كمحرر التوقيت مع «غدًا 09:00» و«امسح». التاريخ الممسوح يُرسل `null` (§114).
    الويب لا يعدّل الاستحقاق؛ أضفته لأن المالك طلبه. مقترح — للمالك أن يؤكد.
- **اللوحة** (التطبيقان): صف أدوات فوق الأعمدة فيه:
  - **مرشّح المشروع** (مشاريع بروفايل المحدِّد كما في الويب، يظهر إن كان أكثر من مشروع) → `tasks.getColumns(project_id)`.
  - **ورقة المشاريع**: القائمة (النشطة والمتوقفة، ثم المؤرشفة) بعدد مهام كل مشروع ومستودعه؛ إنشاء (الاسم،
    المستودع، الفرع)؛ تعديل (الاسم، الحالة نشط/متوقف/مؤرشف، المستودع والفرع — إعدادات المشروع في الويب)؛
    أرشفة/إعادة؛ حذف بعد سؤال يقول إن مهامه تُحذف معه (الخادم يحذفها بالتتابع). المستودع الممسوح يُرسل `null`.
    الويب لا يُنشئ ولا يعيد التسمية ولا يؤرشف ولا يحذف؛ هذا من العقد لأن المالك طلبه. اللون غير معروض
    (الويب لا يعرضه). مقترح — للمالك أن يؤكد.
  - **تحديد**: لمس البطاقة يعلّمها بدل فتحها (ولا تُسحب)، وشريط سفلي: الأولوية، تعليق واحد على الكل، أرشفة
    (للمنجزة فقط)، حذف بعد سؤال. نداء لكل بروفايل (وكل 100 بطاقة) كما في الويب؛ المرفوضة تبقى محددة مع
    عددها، ونجاح الكل ينهي التحديد. الويب يقدم الأولوية والتعليق فقط؛ الأرشفة والحذف الجماعيان من العقد
    (`archived`، `tasks.bulkDeleteTasks`). مقترح — للمالك أن يؤكد.
  - البطاقة تعرض عدد قائمة التحقق (`2/5`) كما في الويب.
- **غير ممكن الآن**: تعديل تعليق أو حذفه (العقد فيه `tasks.createComment` فقط، ولا أخترع عملية)؛ النقل
  والإسناد الجماعيان (ليسا في `TaskBulkUpdate`). **متروك**: سجل المهمة في المركز (`tasks.listActivity`) — الويب
  لا يعرضه؛ وقسم نسخة العمل (worktree) الذي في نافذة الويب — لم يُطلب في الدفعة.
- لم أمسّ شاشات الغرف (دفعة أخرى تعمل عليها) ولا غير ملفات المهام، سوى الحقل الجديد في النموذج المشترك
  ونصوصه الثلاثة في ملفات `kit`.

## العقد (ما تغيّر في packages/contracts، أو «لا شيء»)
لا شيء. المستعمل كله موجود: `tasks.createSubtask/updateSubtask/deleteSubtask`، `createComment`، `updateTask`
(`auto_start`، `due_at`، `definition_of_done`، `constraints`)، `listProjects/createProject/updateProject/deleteProject`،
`getColumns(project_id)`، `bulkUpdateTasks`، `bulkDeleteTasks`.

## الملفات والتأثير
- iOS: جديدة `Screens/Tasks/TaskListsRules.swift` (القواعد)، `TaskDetailSections.swift` (الأقسام)، `ProjectsSheet.swift`؛
  معدّلة `Screens/Tasks/TaskDetailView.swift`، `TaskRules.swift` (الاستحقاق)، `Screens/TasksBoard.swift` (المرشّح،
  التحديد، الشريط، عدد القائمة)، `Components/FormSheet.swift` (حقل التاريخ)؛ نصوص `i18n/tasks.{en,ar}.json`
  و`i18n/kit.{en,ar}.json`؛ اختبار `CoreHubTests/TasksTwoTests.swift`.
- Android: جديدة `ui/screens/TaskLists.kt` (القواعد)، `TaskDetailParts.kt` (الأقسام)، `TasksTools.kt` (صف الأدوات،
  الشريط، ورقة المشاريع)؛ معدّلة `TaskDetail.kt` (الاستحقاق، `TaskOps`، الأقسام)، `TasksScreen.kt` (الحالة والأفعال
  في `TasksViewModel`)، `TasksBoard.kt` (التحديد، عدد القائمة)، `ui/components/FormSheet.kt` (حقل التاريخ)؛ نصوص
  `res/values*/strings_tasks.xml` و`strings_kit.xml`؛ اختبارات `parity/TasksTwoTest.kt` و`shots/TasksTwoShots.kt`.
- `docs/STATUS.md` (بند «Tasks on the phones, part II»)، وسطر في `2026-09-27-twuijri-night-apps.md`.

## الفحوص (الأوامر ونواتجها الفعلية)
```
$ gradlew :app:lintDebug :app:testDebugUnitTest --tests TasksTwoTest TaskDetailTest PageKitTest StringsParityTest
    NavigationParityTest UiKitPolicyTest BoardTest DestinationsTest TasksTwoShots TaskDetailShots   (mj-run, JDK 17, بعد دمج فرع الليلة)
BUILD SUCCESSFUL in 24s
TasksTwoTest tests=11 failures=0 · TaskDetailTest 11/0 · TasksTwoShots 4/0 · TaskDetailShots 2/0 · BoardTest 5/0
DestinationsTest 3/0 · PageKitTest 5/0 · StringsParityTest 4/0 · NavigationParityTest 10/0 · UiKitPolicyTest 1/0
lintDebug: 0 errors (67 warnings, none in the new files)

$ gh workflow run ios.yml --ref night/apps-tasks-2   (run 36281480880)
✓ Generate the Swift client (CoreHubClient)
✓ Build and test on the iOS simulator — Executed 229 tests, with 0 failures
Test Case '-[CoreHubTests.TasksTwoTests testABulkEditGoesOncePerProfileAndCountsTheRefusals]' passed
Test Case '-[CoreHubTests.TasksTwoTests testADateFieldReadsAndWritesToTheMinuteAndRefusesWhatIsNotADate]' passed
Test Case '-[CoreHubTests.TasksTwoTests testADragTellsTheHubOnlyTheLinesWhosePlaceChanged]' passed
Test Case '-[CoreHubTests.TasksTwoTests testAProjectIsMadeWithWhatWasGivenAndEditedWithOnlyWhatChanged]' passed
Test Case '-[CoreHubTests.TasksTwoTests testATickFlipsALineAndANewLineNeedsWords]' passed
Test Case '-[CoreHubTests.TasksTwoTests testTheDueDateIsSetChangedOrClearedAsNull]' passed
Test Case '-[CoreHubTests.TasksTwoTests testTheListsAreTickedOnlyInReviewAndSentWhole]' passed

$ pnpm contracts:check-clients → check-clients  OK — 916 client file(s) scanned, 254 contract path(s) known.
$ pnpm i18n:check              → ios: 1118 keys, ar/en in parity · android: Arabic resources use Latin digits · OK
$ pnpm lint                    → All matched files use Prettier code style!
$ pnpm typecheck               → exit=0
```
صور الأندرويد: `apps/android/app/build/shots/tasks/android-lists.png`، `android-lists-dark.png`،
`android-hermes-history.png`، `android-card-selected.png`.

CI على #181 بعد الدمج (رأس الفرع `7ed8f2e7`، يضم هذا العمل ودفعة الغرف): كل الفحوص نجحت — Android build, unit tests, lint
(6m15s)، Build and test on the iOS simulator (6m6s)، Lint/typecheck/contracts/client tests/build، Server unit tests ×3، Web smoke
journeys، db:generate + db:migrate، Docker، Desktop، Installers ×3.

الاختبارات الجديدة تفشل على الشيفرة القديمة (لا توجد `SubtaskRules`/`CheckLines`/`BulkRules`/`ProjectRules`
ولا `FormKind.date` ولا مفتاح `due` فيها).

## المخاطر والرجوع
- لم يُجرَّب على هاتف المالك؛ الآيفون مُتحقَّق منه على محاكي CI فقط (السحب بالضغط المطوّل في `List` بلا وضع
  التحرير سلوك iOS 16+).
- حذف مشروع يحذف مهامه (سلوك الخادم)؛ السؤال يقول ذلك بعددها.
- إعادة الترتيب ترسل نداءً لكل صف تغيّر مكانه؛ إن فشل أحدها تُقرأ المهمة من جديد بترتيب الخادم.
- الرجوع: `git revert` لالتزامات هذا الفرع في فرع الليلة؛ لا عقد ولا بيانات.

## التسليم والخطوة التالية
يُدمج في `night/2026-09-27-apps` (#181). تبقى: تعديل التعليق وحذفه (يحتاجان عمليتين في العقد أولًا)، والنقل
والإسناد الجماعيان، وسجل المهمة وقسم نسخة العمل إن أرادهما المالك على الجوال.
