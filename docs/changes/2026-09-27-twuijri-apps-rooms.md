# إدارة الغرف على الجوال (الدفعة 7)
المسؤول: twuijri · الفرع: night/apps-rooms · الحالة: review

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
- **لم يُبنَ (والسبب):**
  - «المشروع الذي يرسل تقاريره هنا» (في إعدادات الغرفة على الويب): فكّ الربط يحتاج `report_room_id: null`، والعميلان المولّدان
    لا يرسلان `null` بعد (مهمة §114 تجري الليلة نفسها). يُضاف بعدها.
  - إرجاع نموذج المقعد إلى «نموذج الوكيل» بعد أن كان له نموذج: يحتاج `model: null` (§114). الدور والتعليمات يُمسحان الآن
    (يُرسلان نصًا فارغًا فيحفظهما الهب فارغين).
  - «بلا حدّ» لعدد التمريرات: `max_depth` مطلوب ويقبل `null`، والعميلان الآن يحذفانه حين يكون فارغًا فيرفض الهب الطلب؛
    يعمل تلقائيًا حين يدخل §114 (المطلوب القابل لـ`null` يُرسل دائمًا). الأرقام 1–20 تعمل الآن.
  - ترتيب المقاعد وموضوع الغرفة: لا يوجدان في العقد ولا في الويب. «الأشخاص» عند الإنشاء: الويب يدعو بالرمز فقط، وهو موجود.
  - لم أخترع عمليات؛ لا تغيير في العقد.
- نصوص المنطقة: iOS `i18n/rooms.{en,ar}.json` (مفاتيح `rooms.manage.*`)، Android `values*/strings_rooms.xml` (`rooms_manage_*`)،
  مأخوذة من كلمات الويب نفسها، بأرقام لاتينية.

## العقد (ما تغيّر في packages/contracts، أو «لا شيء»)
لا شيء.

## الملفات والتأثير
- iOS: جديد `Rooms/RoomManage.swift` (القواعد) و`Rooms/RoomManageViews.swift` (قائمة «…»، أوراق التسمية والإعدادات والمقعد،
  شريط التمرير، الملخّص)؛ تعديل `Rooms/RoomModel.swift` (العمليات)، `Rooms/RoomState.swift` (السياسة، الملخّص، السلاسل،
  وإصلاح `room.updated`)، `Rooms/RoomScreen.swift` (الرأس، الشريط، ورقة الأعضاء)، `Rooms/RoomsList.swift` (نشطة/مؤرشفة
  والضغطة المطوّلة)؛ `i18n/rooms.{en,ar}.json`؛ اختبار `CoreHubTests/RoomManageTests.swift`.
- Android: جديد `rooms/RoomManage.kt` (القواعد)، `rooms/RoomActions.kt` (الاستدعاءات)، `ui/screens/RoomManageUi.kt`؛ تعديل
  `rooms/RoomReducer.kt`، `ui/screens/RoomViewModel.kt`، `RoomScreen.kt`، `RoomsPanel.kt`؛ `res/values*/strings_rooms.xml`؛
  اختبار `rooms/RoomManageTest.kt` وصورة `shots/RoomManageShots.kt`.
- لم أمسّ العملاء المولّدين ولا ملفات المحادثة/المهام/الجدولة (مهمة §114 تعمل عليها).

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
```
صورة الأندرويد: `apps/android/app/build/shots/rooms/android-manage.png` (نظرتُ فيها: الشريط المتوقف مع «One more round»، بطاقة
الملخّص بزرّيها، ونموذج المقعد).

## المخاطر والرجوع
- «بلا حدّ» في الإعدادات يُرفض من الهب حتى يدخل §114 (انظر القرار)؛ بعده يلزم في هذه الشاشات فقط إضافة `sendNull` لمسح
  نموذج المقعد.
- `room.cleared` يعيد قراءة الغرفة كاملة؛ الرسائل تبقى في الجوال (الويب يُفرغ النص حتى تُعاد القراءة).
- ورقة المقعد في الأندرويد تُفتح فوق ورقة الأعضاء (ورقتان مكدستان).
- لم يُجرَّب شيء على هاتفي المالك؛ iOS على محاكي CI فقط.
- الرجوع: `git revert` لالتزامات هذا الفرع في فرع الليلة؛ لا عقد ولا بيانات.

## التسليم والخطوة التالية
يُدمج في `night/2026-09-27-apps` (#181). التالي: بعد دخول §114، ربط المشروع بالغرفة ومسح نموذج المقعد؛ ثم تجربة المالك.
