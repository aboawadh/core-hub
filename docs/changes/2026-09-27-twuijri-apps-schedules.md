# الجدولة على الجوال: إنشاء وتعديل وحذف، محرر التوقيت، السجل صفحة بصفحة، ومهام الوكيل
المسؤول: twuijri · الفرع: night/apps-schedules · الحالة: done

## المشكلة والهدف
الدفعة 3 من ليلة التطبيقات (`2026-09-27-twuijri-night-apps.md`، قائمة الفجوات §2). صفحة الجدولة في التطبيقين كانت
تعرض القائمة وتشغّل الآن فقط (والأندرويد يوقف مؤقتًا ويعرض آخر 10 تشغيلات)؛ لا إنشاء ولا تعديل ولا حذف، ولا معاينة
للمواعيد، ولا الجداول الشائعة، والآيفون بلا سجل ولا إيقاف مؤقت. ومهام الوكيل: الآيفون يعرض القائمة فقط، والأندرويد
يشغّل ويوقف بلا حذف. الهدف: ما تفعله صفحة الجدولة وصفحة مهام الوكيل في الويب، في التطبيقين، بمكوّنات كل تطبيق.

## القرار والموافقات
- **جدول جديد** (زر **+** في شريط الآيفون، وزر «جدول جديد» أعلى القائمة في الأندرويد) في البروفايل المختار كما في
  الويب، بنموذج الويب نفسه: الاسم، الموعد بمحرر التوقيت المشترك (`TriggerEditor`: cron بقوالبه، كل N دقائق/ساعات/أيام،
  مرة واحدة، المنطقة الزمنية، والمواعيد الثلاثة القادمة من `schedules.previewTrigger`)، قائمة «جداول شائعة» الستّة
  كما في الويب، الوكيل (هرمز أولًا كما في الويب؛ من الوكلاء القابلين للتشغيل في البروفايل)، النص، وخيارا التشغيل
  (تشغيل الموعد الفائت، وما يحدث إن كان السابق جاريًا) — لا يُعرضان ولا يُرسلان لهرمز لأنه يقررهما ويرفضهما.
- **رفض هرمز بكلماته** (نفس أسباب الويب: `hermes_refused`، `hermes_unreachable`، `hermes_timezone`، `hermes_delivery`،
  `hermes_prompt_required`، `hermes_run_options`، `target_unavailable`)، ومع `hermes_timezone` زر «استعمل {zone}» يعيد
  الحفظ بتوقيت هرمز. لذلك صار `HubFailure` (iOS) و`HubError` (Android) يقرآن `details.timezone` (إضافة حقل فقط).
- **الجدول وحده** (ورقة في التطبيقين، بلمس البطاقة): الحالة، الموعد، التالي والأخير، الوكيل، النص، القناة، آخر خطأ،
  تشغيل الآن، مفعّل/موقوف، **تعديل** (نموذج الإنشاء نفسه؛ يُرسل ما تغيّر فقط؛ الوكيل يبقى — نقل جدول من مجدول هرمز
  أو إليه جدول جديد)، **حذف** بعد السؤال، و**السجل صفحة بصفحة** (20 تشغيلًا، التالي عند نهاية القائمة أو «المزيد»):
  الحالة أو «ينتظر انتهاء السابق»، في موعده أو يدوي، البدء، المدة (أرقام لاتينية)، الناتج أو الخطأ، فشل التسليم، ولمسة
  تفتح محادثة التشغيل. يُعاد قراءته كل 3 ثوانٍ ما دام تشغيل جاريًا كما في الويب. وفي القائمة: قائمة «…»/الضغط المطوّل
  (تشغيل، إيقاف/استئناف، تعديل، حذف) والسحب للحذف.
- الويب لا يعدّل الاسم والموعد والنص (يعدّل التفعيل وخيارات التشغيل فقط)؛ التطبيقان يعدّلانها بـ`schedules.update`
  الموجودة لأن الدفعة طلبت التعديل. مقترح — للمالك أن يؤكد.
- **الهدف**: نص إلى وكيل فقط؛ الويب لا يعرض في النموذج جدولًا لسير عمل أو مهمة، فلم أضفه.
- **مهام الوكيل** (الدفعة تسمّيها): تشغيل الآن، إيقاف/استئناف، حذف بعد السؤال (مع «تُحذف من مجدول هرمز أيضًا» لمهمة
  هرمز)، ونص الويب بأن الإنشاء والتعديل في صفحة الجدولة (وفي الآيفون زر يفتحها). لا محرر توقيت فيها، كما في الويب.
- **إصلاح وجدته في الطريق**: العقد يجعل كل حقول `ScheduleTrigger` و`ScheduleTarget` مطلوبة وبعضها `null`، والعميلان
  المولَّدان يحذفان الحقل الفارغ، والخادم يتحقق من الجسم بالعقد فيرفض (400 «must have required property») المعاينة
  والإنشاء والتعديل. أي أن معاينة `TriggerEditor` من الأساس لم تكن لتعمل مع خادم حقيقي. أضفت `ScheduleBodies` في
  التطبيقين: يعيد الحقول الناقصة `null` في `trigger` و`target` فقط قبل خروج الطلب (iOS داخل `BodilessRequests`، Android
  معترض على عميل `schedules` وحده). تحقّقت من الرفض ومن القبول بمدقّق العقد نفسه (`createContractIndex`). الحل العام (أن
  يرسل المولِّد `null` للحقول المطلوبة القابلة للفراغ) أوسع من هذه الدفعة — أقترح مهمة مستقلة.
- في الآيفون تُفتح المحادثة من السجل عبر `app.pendingRoute` (الطريق الذي يأخذه الغلاف) فلم أعدّل `ShellView`.
- حُذف نص الأندرويد `schedules_started` (لم يعد مستعملًا). لم أمسّ مفاتيح `en.json`/`ar.json` في الآيفون.

## العقد (ما تغيّر في packages/contracts، أو «لا شيء»)
لا شيء. المستعمل: `schedules.list`، `create`، `update`، `delete`، `get`، `runNow`، `listRuns`، `previewTrigger`، و`agents.list`.

## الملفات والتأثير
- iOS: جديدة `Screens/Schedules/ScheduleRules.swift`، `ScheduleEditorSheet.swift`، `ScheduleDetailView.swift`،
  `Hub/ScheduleBodies.swift`؛ معدّلة `Screens/Schedules/SchedulesScreen.swift` (قائمة صفحات، +، الأفعال)،
  `Screens/Agent/AgentJobsPage.swift`، `Hub/HubAPI.swift` (سطر يستدعي `ScheduleBodies`)، `Hub/HubFailure.swift`
  (`timezone`)؛ نصوص `i18n/schedules.{en,ar}.json`؛ اختبار `CoreHubTests/SchedulesTests.swift`.
- Android: جديدة `ui/screens/ScheduleEditor.kt` (`ScheduleRules`، `ScheduleOps`، النموذج)، `ui/screens/ScheduleDetail.kt`،
  `data/ScheduleBodies.kt`؛ معدّلة `ui/screens/SchedulesScreen.kt` (قائمة `ListScaffold` بصفحات، الأفعال، الأوراق)،
  `ui/screens/agent/AgentJobsPage.kt`، `data/Hub.kt` (`timezone`، ومعترض عميل الجدولة)، `res/values*/strings.xml`
  (حذف نص واحد)؛ نصوص `res/values*/strings_schedules.xml`؛ اختبارات `parity/SchedulesTest.kt` و`shots/SchedulesShots.kt`.
- `docs/STATUS.md` (بند «Schedules on the phones»)، وسطر في سجل الليلة.

## الفحوص (الأوامر ونواتجها الفعلية)
```
$ gradlew :app:testDebugUnitTest --tests SchedulesTest SchedulesShots StringsParityTest NavigationParityTest PageKitTest   (mj-run, JDK 17)
BUILD SUCCESSFUL
SchedulesTest tests=11 failures=0 errors=0 · SchedulesShots tests=3 failures=0 errors=0
StringsParityTest, NavigationParityTest, PageKitTest: 0 failures

$ gradlew :app:lintDebug
BUILD SUCCESSFUL — 0 errors; no warning left in the new files (the unused sched_open_chat was dropped)

$ gh workflow run ios.yml --ref night/apps-schedules   (run 36276540708)
✓ Generate the Swift client (CoreHubClient) in 44s
✓ Build and test on the iOS simulator in 4m9s — Executed 212 tests, with 0 failures
SchedulesTests testANewScheduleAsksHermesFirstAndLeavesItsRunOptionsToIt]' passed
SchedulesTests testTheCommonSchedulesFillTheKindAndItsValue]' passed
SchedulesTests testAnEditSendsOnlyWhatChanged]' passed
SchedulesTests testAHermesJobKeepsNoRunOptionsOfItsOwn]' passed
SchedulesTests testHermesRefusalsAreSaidInWordsAndTheZoneItAsksForIsOffered]' passed
SchedulesTests testARunSaysHowLongItTookInLatinDigits]' passed
SchedulesTests testATriggerAndATargetGoOutWithTheirNullFields]' passed

$ vitest (server, one-off check with createContractIndex, not committed)
REFUSED trigger without every_minutes/run_at: "must have required property 'every_minutes'" …
OK      trigger with "every_minutes":null,"run_at":null
REFUSED target without model/provider/workflow_id/input

$ pnpm lint               → All matched files use Prettier code style!
$ pnpm typecheck          → exit=0
$ pnpm i18n:check         → ios: 942 keys, ar/en in parity … android: Arabic resources use Latin digits … OK
$ pnpm contracts:check-clients → check-clients  OK — 894 client file(s) scanned, 254 contract path(s) known.
$ pnpm nav:check          → nav:check  OK — 39 destinations …
```
صور الأندرويد: `apps/android/app/build/shots/schedules/android-{schedule-new,schedule-new-options,schedule-detail,schedule-detail-dark}.png`.

الاختبارات الجديدة تفشل على الشيفرة القديمة: لا `ScheduleRules`/`ScheduleOps`/`ScheduleBodies` فيها، وجسم الإنشاء
والمعاينة فيها يخلو من `"every_minutes":null` الذي يتحقق منه الاختبار.

## المخاطر والرجوع
- لم يُجرَّب على هاتف المالك ولا مع خادم حقيقي؛ الآيفون على محاكي CI فقط.
- `ScheduleBodies` يعدّل جسم أي طلب JSON فيه `trigger` بنوع cron/interval/once أو `target` بنوع agent_prompt/workflow
  (Android: على عميل الجدولة وحده؛ iOS: كل الطلبات، ولا عملية أخرى تحمل هذين الشكلين).
- اسم وكيل جدول من بروفايل غير المختار قد يظهر «—» في الأندرويد (قائمة الوكلاء للبروفايل المختار).
- الرجوع: `git revert` لالتزامات هذا الفرع في فرع الليلة؛ لا عقد ولا بيانات.

## التسليم والخطوة التالية
يُدمج في `night/2026-09-27-apps` (#181). التالي المقترح: مهمة تجعل مولِّد العملاء يرسل `null` للحقول المطلوبة القابلة
للفراغ (تحلّ أيضًا ما ذكرته دفعتا المحادثة والمهام) ثم يُحذف `ScheduleBodies`.
