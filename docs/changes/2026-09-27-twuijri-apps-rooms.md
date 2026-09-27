# إدارة الغرف على الجوال (الدفعة 7)
المسؤول: twuijri · الفرع: night/apps-rooms · الحالة: done

## المشكلة والهدف
قائمة الفجوات (الدفعة 7) قالت: الغرف في التطبيقين محادثة فقط — لا إعادة تسمية ولا حذف (`rooms.update/delete`)، ولا إضافة
وكيل أو تعديل مقعده أو إزالته (`addSeat/updateSeat/removeSeat`)، ولا مسح السياق، ولا ملخّص الغرفة (`putMemory/refreshMemory`)،
ولا تمرير الدور (`listHandoffs/continueHandoff`)؛ والقائمة لا تعرض الغرف المؤرشفة ولا إجراءات بالضغطة المطوّلة. الهدف: كل ما
تديره صفحة الغرف في الويب يُدار من الآيفون والأندرويد، بمكوّنات كل تطبيق.

## القرار والموافقات
مطابقة لما في الويب (`packages/web/src/rooms/*`)، لا أكثر:
- **قائمة الغرف (التطبيقان):** «النشطة | المؤرشفة» كما في الويب، و**ضغطة مطوّلة** على الغرفة: لمديرها إعادة التسمية،
  الأرشفة/الإعادة من الأرشيف، الحذف (بعد سؤال)؛ ولغيره المغادرة (بعد سؤال؛ تُقرأ قائمة الأشخاص `rooms.listMembers` لمعرفة
  عضويتك ثم `rooms.removeMember`). صانع الغرفة لا يغادرها.
- **قائمة «…» في رأس الغرفة:** للمدير: إعادة تسمية، إعدادات الغرفة (السماح بـ@all، تمرير الدور بين الوكلاء، أقصى عدد تمريرات
  1–20 أو فارغ بلا حدّ)، مسح السياق (بعد سؤال، ثم تُقرأ الغرفة من جديد)، أرشفة/إعادة، حذف (بعد سؤال). لغير المدير: مغادرة.
  الإعدادات تُرسل ما تغيّر فقط، وسياسة التمرير كاملة (`enabled` مع `max_depth`) حين تتغير.
- **ورقة الأعضاء:** «إضافة وكيل» (الوكيل من وكلاء البروفايل المثبّتين المفعّلين، الاسم في الغرفة — فارغ = اسم الوكيل، الدور،
  التعليمات، النموذج — فارغ = نموذج الوكيل)؛ لكل مقعد «…» وضغطة مطوّلة (وسحب في iOS): تعديل (الوكيل لا يتغير، كما في الويب)،
  «اجعله القائد»، إزالة (بعد سؤال). سطر المقعد: دوره · نموذجه أو «نموذج الوكيل نفسه». وقسم **الملخّص**: نصّه، «لخّص الآن»،
  «تعديل الملخّص» (محرر النص المشترك)، وحالة «يُلخِّص…/تعذّر».
- **شريط تمرير الدور** فوق حقل الكتابة: «مرّر @أ الدور إلى @ب (التمرير n)» ما دامت سلسلة جارية؛ وإلا أحدث سلسلة أوقفها
  الحارس ولم تُستعمل جولتها الإضافية، مع «جولة أخرى» (ما لم يكن السبب إيقافًا يدويًا). يتبع `handoff.updated` و`memory.updated`.
- **إصلاح:** حدث `room.updated` يصل لكل الأعضاء بـ`can_manage: false` و`invite_code: null`، وكان التطبيقان يأخذانه كما هو
  فتختفي أدوات المدير ورمز الدعوة بعد أي تغيير. صار كلاهما يُبقي من يدير الغرفة ورمزها كما قالتهما الغرفة.
- **بعد دخول §114 (`null` الصريح) الليلة نفسها** أضفت: في إعدادات الغرفة «المشروع الذي يرسل تقاريره هنا» (قائمة مشاريع البروفايل
  `tasks.listProjects` تُقرأ عند فتح الإعدادات؛ تغيير الاختيار يفكّ ربط القديم بـ`report_room_id: null` ثم يربط الجديد عبر
  `tasks.updateProject`، كما في الويب)، والدور/التعليمات/النموذج الفارغة في تعديل المقعد تُرسل `null` (`sendNull`) فيعود المقعد
  إلى نموذج الوكيل؛ و«بلا حدّ» للتمريرات يُرسل `max_depth: null` (مطلوب يقبل `null`، يُرسل دائمًا).
- **لم يُبنَ (والسبب):** ترتيب المقاعد وموضوع الغرفة لا يوجدان في العقد ولا في الويب؛ «الأشخاص» عند الإنشاء: الويب يدعو بالرمز
  فقط، وهو موجود في التطبيقين. لم أخترع عمليات؛ لا تغيير في العقد.
- نصوص المنطقة: iOS `i18n/rooms.{en,ar}.json` (مفاتيح `rooms.manage.*`)، Android `values*/strings_rooms.xml` (`rooms_manage_*`)،
  مأخوذة من كلمات الويب نفسها، بأرقام لاتينية.

## العقد (ما تغيّر في packages/contracts، أو «لا شيء»)
لا شيء.

## الملفات والتأثير
- iOS: جديد `Rooms/RoomManage.swift` (القواعد) و`Rooms/RoomManageViews.swift` (قائمة «…»، أوراق التسمية والإعدادات والمقعد،
  شريط التمرير، الملخّص)؛ تعديل `Rooms/RoomModel.swift` (العمليات)، `Rooms/RoomState.swift` (السياسة، الملخّص، السلاسل،
  وإصلاح `room.updated`)، `Rooms/RoomScreen.swift` (الرأس، الشريط، ورقة الأعضاء)، `Rooms/RoomsList.swift` (نشطة/مؤرشفة
  والضغطة المطوّلة)؛ `i18n/rooms.{en,ar}.json`؛ اختبار `CoreHubTests/RoomManageTests.swift`.
- iOS/Android: الإعدادات تقرأ المشاريع (`TasksAPI.tasksListProjects`) وتكتب `tasksUpdateProject` من داخل ملفات الغرف فقط.
- Android: جديد `rooms/RoomManage.kt` (القواعد)، `rooms/RoomActions.kt` (الاستدعاءات)، `ui/screens/RoomManageUi.kt`؛ تعديل
  `rooms/RoomReducer.kt`، `ui/screens/RoomViewModel.kt`، `RoomScreen.kt`، `RoomsPanel.kt`؛ `res/values*/strings_rooms.xml`؛
  اختبار `rooms/RoomManageTest.kt` وصورة `shots/RoomManageShots.kt`.
- لم أمسّ العملاء المولّدين ولا ملفات المحادثة/المهام/الجدولة (مهمة §114 عملت عليها؛ دُمج فرع الليلة بعدها وأُعيد التوليد).

## الفحوص (الأوامر ونواتجها الفعلية)
```
$ gradle :app:testDebugUnitTest --tests 'hub.core.android.rooms.*' --tests '*RoomManageShots'   (mj-run, JDK 17)
BUILD SUCCESSFUL in 7s
RoomsTest tests=16 failures=0 · RoomManageTest tests=15 failures=0 · RoomManageShots tests=1 failures=0
(قبلها فشل اختباران: عنوان القاعدة كان بشرطتين `//api/v1` في الاختبار؛ صُحّح عنوان الخادم الوهمي)

$ gradle :app:testDebugUnitTest --tests '*StringsParityTest' '*UiKitPolicyTest' '*NavigationParityTest' '*Digits*'
BUILD SUCCESSFUL in 4s

$ gradle :app:lintDebug
BUILD SUCCESSFUL in 1m

$ pnpm contracts:check-clients → check-clients  OK — 909 client file(s) scanned, 254 contract path(s) known.
$ pnpm i18n:check              → i18n:check  ios: 1055 keys, ar/en in parity … android: Arabic resources use Latin digits … OK
$ pnpm lint                    → All matched files use Prettier code style!
$ pnpm typecheck               → exit=0

$ gh workflow run ios.yml --ref night/apps-rooms   (run 36279842834, c51d12a3)
✓ Generate the Swift client (CoreHubClient)
✓ Build and test on the iOS simulator — Executed 233 tests, with 0 failures · TEST SUCCEEDED
  (RoomManageTests: 12 passed)

# بعد دمج فرع الليلة (§114) وإضافة ربط المشروع ومسح الحقول بـ null:
$ pnpm --filter @corehub/contracts generate:native → kotlin: explicit nulls in 77 request model(s) · swift: … 71 … OK
$ gradle :app:testDebugUnitTest --tests 'hub.core.android.rooms.*' '*RoomManageShots' '*StringsParityTest'
BUILD SUCCESSFUL in 37s — RoomsTest tests=16 failures=0 · RoomManageTest tests=18 failures=0
$ gradle :app:lintDebug          → BUILD SUCCESSFUL in 17s
$ pnpm i18n:check                → i18n:check  OK
$ pnpm contracts:check-clients   → check-clients  OK — 907 client file(s) scanned, 254 contract path(s) known.
$ gh workflow run ios.yml --ref night/apps-rooms   (run 36280564719, 4a62ee74)
✓ Build and test on the iOS simulator — Executed 235 tests, with 0 failures · TEST SUCCEEDED (RoomManageTests: 13 passed)
```
CI على #181 بعد الدمج (الالتزام `6ab1c000`): كل الفحوص نجحت — Android build, unit tests, lint (5m38s)، Build and test on the iOS
simulator (5m11s)، Lint/typecheck/contracts/client tests/build، Server unit tests ×3، Web smoke journeys، Change record، Docker،
Desktop، Installers ×3 (Installers ubuntu فشل أول مرة في اختبار اقتران سطح المكتب `desktop-pairing`، لا صلة له بالجوال، ونجح عند
إعادة التشغيل)، db:generate + db:migrate.

صورة الأندرويد: `apps/android/app/build/shots/rooms/android-manage.png` (نظرتُ فيها: الشريط المتوقف مع «One more round»، بطاقة
الملخّص بزرّيها، ونموذج المقعد).

## المخاطر والرجوع
- `room.cleared` يعيد قراءة الغرفة كاملة؛ الرسائل تبقى في الجوال (الويب يُفرغ النص حتى تُعاد القراءة).
- ورقة المقعد في الأندرويد تُفتح فوق ورقة الأعضاء (ورقتان مكدستان).
- لم يُجرَّب شيء على هاتفي المالك؛ iOS على محاكي CI فقط.
- الرجوع: `git revert` لالتزامات هذا الفرع في فرع الليلة؛ لا عقد ولا بيانات.

## التسليم والخطوة التالية
يُدمج في `night/2026-09-27-apps` (#181). التالي: تجربة المالك على الهاتفين.
