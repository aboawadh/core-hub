# إضافات صفحة النماذج في الجوال (الدفعة 15)
المسؤول: twuijri · الفرع: night/apps-models · الحالة: in-progress

## المشكلة والهدف
صفحة النماذج في التطبيقين كانت أصلية لكن ناقصة عن صفحة الويب: لا تعديل للمزوّد (الاسم، العنوان، مفتاح جديد)، ولا
«مسح المفتاح»، ولا أسماء عرض للنماذج، ولا ذكر من أين جاءت قائمة النماذج («من حسابك» أو «قائمة احتياطية» مع السبب، §83)،
ولا واجهة مخصّصة متوافقة مع OpenAI عند الإضافة، والإعدادات المسبقة المضافة تُعرض مرة ثانية في النطاق نفسه (فيرد الخادم `409`)،
ولا المهام المساعدة في الافتراضيات، ولا نموذج الكلام ولغته، ونموذج الصور كان يعرض كل نماذج المزوّد الراسم لا ما يرسم فعلًا
(§84/§87/§110). الهدف: أن يدير المالك المزوّدين والافتراضيات والكلام والصور من الآيفون والأندرويد كما في الويب.

## القرار والموافقات
- **صفحة للمزوّد** (iOS صفحة تُفتح من القائمة، Android ورقة سفلية): الحقائق، ملاحظات القائمة (§83)، مفتاح التفعيل، تعديل،
  تسجيل الدخول / من جديد، اختبار بكلمات المزوّد ومدته، تحديث القائمة مع إعادة القراءة حتى تنتهي، إزالة المفتاح (بسؤال)،
  الحذف (بسؤال `ConfirmDelete` المشترك)، ونماذجه مع اسم عرض لكل نموذج (`models.putModel`).
- **معرّف النموذج يُرمَّز مرة هنا ثم يرمّزه العميل** كما يفعل الويب (`encodeURIComponent`)، لأن الخادم يفكّ الترميز مرتين؛
  وإلا ينكسر معرّف فيه `/` مثل نماذج OpenRouter. مختبَر في التطبيقين (المسار `…/models/anthropic%252Fclaude`).
- **الإضافة كالويب**: السؤال «لمن» أولًا، إخفاء الإعداد المسبق المضاف في هذا النطاق، المفتاح المحفوظ للحساب يجعل المفتاح
  اختياريًا (§94)، واجهة مخصّصة (محادثة أو كلام→نص أو نص→كلام)، وتنبيه العنوان المحلي في مركز داخل حاوية مع العنوان المقترح.
- **الافتراضيات**: «من البروفايل الافتراضي» لكل دور، الرجوع إلى اختيار البروفايل الافتراضي، الاحتياطي يحتاج نموذج محادثة أولًا،
  وسلسلة تُحفظ على نموذج موروث تحفظه معها (كما يفعل الويب)، والمهام المساعدة.
- **الكلام**: نموذج كل مزوّد (قائمته من نوعه، أو مكتوب، أو افتراضي المزوّد) ولغته (اكتشاف، اللغات الأشهر بأسمائها، أو رمز
  مكتوب)؛ قائمة الأصوات تقول إن كانت من الوثائق أو غائبة وتقبل معرّفًا مكتوبًا. القيمة الفارغة تُرسَل `null`.
- **الصور**: ما يرسم (`image_output`) على مزوّد يرسم فقط، نماذج الصور وحدها أولًا، ونموذج الاشتراك باسم «الصور عبر اشتراك …».
- **خارج النطاق عمدًا**: «اجلب» في نافذة الإضافة (probe)، قوائم النماذج الظاهرة وضبط كل نموذج، بطاقة Runtime، قائمة الوكلاء
  الوارثين، وعلامة تبويب Ensembles (في الويب ملاحظة فقط).
- **تسجيل الخروج من مزوّد مسجَّل الدخول غير موجود في العقد** (ولا في الويب)، فلم أخترعه. أقرب شيء «إزالة المزوّد».
- **مسح دور مساعد** غير ممكن من العميلين المولَّدين: قيمة `assignments` في Swift وKotlin لا تقبل `null`؛ يُختار نموذج آخر فقط.
- لا خيار منتج جديد يحتاج تأكيدًا؛ النصوص مأخوذة من صياغة الويب.

## العقد (ما تغيّر في packages/contracts، أو «لا شيء»)
لا شيء.

## الملفات والتأثير
- iOS: `Settings/Models/ModelLogic.swift` (قواعد جديدة)، `ModelsNativePage.swift` (التبويبات ومنتقي النماذج المشترك)،
  ملفات جديدة `ModelsProviders.swift`، `ModelsAddProvider.swift`، `ModelsDefaults.swift`، `ModelsSpeech.swift`، `ModelsImages.swift`؛
  نصوص `i18n/models.{en,ar}.json`؛ اختبار `CoreHubTests/ModelsExtrasTests.swift`.
- Android: `ui/screens/ModelsScreen.kt` (القواعد والعمليات والصفحة والمنتقي)، ملفات جديدة في `ui/screens/models/`
  (`ModelsProvidersTab.kt`، `ModelsAddProvider.kt`، `ModelsDefaultsTab.kt`، `ModelsSpeechTab.kt`، `ModelsImagesTab.kt`)؛
  نصوص `values*/strings_models.xml`؛ اختبار `test/.../parity/ModelsExtrasTest.kt` وصور `testDebug/.../shots/ModelsShots.kt`.
- `docs/STATUS.md`.

## الفحوص (الأوامر ونواتجها الفعلية)
```
$ gradlew :app:testDebugUnitTest --tests '*ModelsShots' '*ModelsExtrasTest' '*ModelsAdminTest' '*StringsParityTest' '*NavigationParityTest' '*UiKitPolicyTest' :app:lintDebug   (mj-run, JDK 17)
BUILD SUCCESSFUL in 1m 32s
NavigationParityTest tests="10" failures="0" errors="0"
ModelsAdminTest      tests="7"  failures="0" errors="0"
ModelsExtrasTest     tests="10" failures="0" errors="0"
ModelsShots          tests="2"  failures="0" errors="0"
StringsParityTest    tests="4"  failures="0" errors="0"
UiKitPolicyTest      tests="1"  failures="0" errors="0"

$ pnpm contracts:check-clients → check-clients  OK — 990 client file(s) scanned, 254 contract path(s) known.
$ pnpm i18n:check              → i18n:check  ios: 2142 keys, ar/en in parity … i18n:check  OK
$ pnpm lint                    → All matched files use Prettier code style!
```
صور الأندرويد: `apps/android/app/build/shots/models/android-{providers,defaults,speech,images}-{light-en,dark-ar}.png`
(ورقة المزوّد نافذة مستقلة لا تظهر في الصورة؛ يتحقق الاختبار من ظهور ملاحظة «قائمة احتياطية» و«من حسابك» فيها).
الآيفون لا يُبنى على لينكس: ينتظر مهمة iOS في CI.

## المخاطر والرجوع
- كود Swift لم يُترجم محليًا؛ يعتمد على CI. الرجوع: `git revert` لالتزامات الفرع في فرع الليلة؛ لا عقد ولا بيانات.
- لم يُجرَّب على هاتفي المالك ولا على مركز حقيقي.

## التسليم والخطوة التالية
يُدمج في `night/2026-09-27-apps` (#181). تبقى: probe وقوائم النماذج الظاهرة وبطاقة Runtime (الويب فقط الآن)،
وتسجيل الخروج إن أُضيفت له عملية في العقد.
