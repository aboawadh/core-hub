# اللغات في تطبيقي الجوال: السجل نفسه، نصوص أندرويد من JSON، واختبارات اللغات التجريبية (المرحلة الثانية)
المسؤول: twuijri · الفرع: feat/i18n-languages-apps (مبني فوق feat/i18n-languages-foundation، PR #212) · الحالة: review

## المشكلة والهدف
المرحلة الثانية من طلب المالك (2026-09-28) لإضافة اللغات بأمان (ADR 0028): أن يقرأ أندرويد وiOS
سجل اللغات نفسه ويرجعا بالطريقة نفسها، وألا يلمس المترجم XML في أندرويد، وأن تُطبَّق قواعد المساحة
نفسها على التطبيقين مع اختبارات باللغات التجريبية، واختيار اللغة لكل تطبيق من إعدادات النظام.

## القرار والموافقات
امتداد ADR 0028 (قسم 8) — مقترح، بانتظار تأكيد المالك:
- **أندرويد**: نُقلت النصوص كما هي من `res/values{,-ar}/strings*.xml` إلى
  `apps/android/i18n/<area>.<lang>.json` (أسماء الموارد مفاتيح مسطحة، الجمع كائن بصيغ CLDR،
  الحوافظ `%1$s`)، و`:app:generateSharedSources` يكتب `values-<qualifier>/catalogue.xml` لكل لغة
  مسجلة (`zh-Hant` → `values-b+zh+Hant`) ويحل سلسلة الرجوع بنفسه. **لا نص ظاهر تغيّر**: موارد
  APK قبل النقل وبعده متطابقة (`aapt2 dump resources`: ‏2741 نصًا وجمعًا، 0 فروق).
  `locales_config.xml` و`android:localeConfig` من السجل (لغة لكل تطبيق في أندرويد 13+)، والتطبيق
  يقرأ اختيار النظام ويكتب اختياره فيه (`LocaleManager`). `AppLanguage` صار من السجل لا تعدادًا،
  و`hubLocale` هو أقرب العربية/الإنجليزية للعقد (§129). `MissingTranslation` معطّل في lint لأن
  اللغة الجزئية تقرأ الإنجليزية عمدًا. `pnpm i18n:check` يرفض أي `strings*.xml` مكتوب يدويًا.
- **iOS**: `AppLanguage` من السجل (`Generated/Languages.swift`)، و`L10n` يدمج السلسلة فوق
  الإنجليزية، و`InfoPlist.strings` و`CFBundleLocalizations` من السجل (فيظهر اختيار اللغة في
  إعدادات iOS للتطبيق). لا واجهة عامة لقواعد الجمع في iOS، فـ`PluralCategory` يحمل قواعد CLDR
  للأعداد الصحيحة للغات الشائعة.
- **قواعد المساحة**: كل `Text` بسطر واحد في أندرويد كان يُقص صار ينتهي بـ«…» (13 موضعًا)،
  وصف حالة الإشعارات لا يأخذ أكثر من نصف السطر (وجدته الاختبارات: كلمة «الإشعارات» كانت تنكسر
  حرفين حرفين)، و`.singleLine()` في iOS (سطر واحد + تصغير حتى 85% + «…») لرقائق الواجهة.
- **الاختبارات التجريبية**: Robolectric يمشي 10 شاشات أندرويد أمام الهب التجريبي بـ`en-XA`
  و`ar-XB` و`zh-XC` و`th-XD` (موارد نسخة debug فقط؛ en-XA/ar-XB مخزنتان كـen-XL/ar-XR لأن أندرويد
  يعامل الرمزين المحجوزين معاملة خاصة) ويقرأ تخطيط كل نص ويفشل على قص بلا «…» أو حرف وحيد؛
  واختبار ذاتي يثبت الفحص على تسميات مكسورة عمدًا. XCTest في iOS يتحقق أن الرقاقة سطر واحد في كل
  لغة تجريبية. `pnpm i18n:new` يكتب ملفات الجوال أيضًا.

## العقد (ما تغيّر في packages/contracts، أو «لا شيء»)
لا شيء (العقد والأحداث كما هي؛ التطبيقان يرسلان `hubLocale` حيث يوثّق العقد `ar|en`).

## الملفات والتأثير
- أندرويد: `apps/android/i18n/*.json` (58 ملفًا، بدل `res/values*/strings*.xml`)،
  `app/build.gradle.kts` (التوليد، اللغات التجريبية، `MissingTranslation`)، `AndroidManifest.xml`،
  `AppGraph.kt`، `Digits.kt`، `MainActivity.kt`، شاشات الدخول والدرج والعرض، `NotificationStatus.kt`،
  13 موضع `Text`، اختبارات `StringsParityTest` و`ToolActivityTest` و`PseudoLocaleLayoutTest`
  و`TextAudit(Test)`.
- iOS: `i18n/L10n.swift`، `i18n/Pseudo.swift`، `Generated/Languages.swift`، `scripts/generate-swift.mjs`،
  `project.yml`، `Theme/Icon.swift` (`singleLine`)، `Shell/Components.swift`، `DisplayPage.swift`،
  `NewChatScreen.swift`، `ToolActivity.swift`، `AppModel.swift`، `Push.swift`، `LanguagesTests.swift`.
- الأدوات: `scripts/i18n/{registry,new}.mjs`، `scripts/i18n-check.mjs` (مجموعة android، تكافؤ الجمع،
  حوافظ `%1$s`، منع XML اليدوي)، `scripts/i18n.test.mjs`.
- CI: `locales/**` يطلق وظيفتي أندرويد وiOS؛ لقطات أندرويد التجريبية artifact دائمًا.
- المستندات: ADR 0028 (قسم 8)، STATUS، `docs/guides/add-a-language.md`، `docs/clients/phone-pages.md`.

## الفحوص (الأوامر ونواتجها الفعلية)
محليًا (JDK 17، Gradle ‏`--max-workers=2`، الذاكرة عبر mj-run؛ iOS في CI فقط):
```
aapt2 dump resources before.apk / after.apk      2741 strings and plurals, differ 0
./gradlew :app:testDebugUnitTest --tests StringsParityTest --tests ToolActivityTest --tests NavigationParityTest   BUILD SUCCESSFUL
./gradlew :app:testDebugUnitTest --tests PseudoLocaleLayoutTest --tests TextAuditTest
    PseudoLocaleLayoutTest tests="5" failures="0" · TextAuditTest tests="1" failures="0"
./gradlew :app:compileDebugKotlin :app:compileReleaseKotlin   rc=0
node apps/ios/scripts/generate-swift.mjs --check  generate-swift  OK — every generated file matches its source
pnpm i18n:check                                   … ios: 2706 keys · android: 2564 keys, ar/en in parity · i18n:check  OK
node --test scripts/i18n.test.mjs                 pass 8 · fail 0
```
ما وجدته الاختبارات قبل الإصلاح: حالة الإشعارات تعصر كلمة «الإشعارات» إلى حرفين في السطر (en-XA)،
وتسميات بسطر واحد تُقص بلا «…». lint أندرويد لم يكتمل محليًا (نفدت الذاكرة الحرة إلى 5 GB مع
عمليات أخرى على الجهاز، فأوقفه الحارس) — يُشغَّل في CI.

CI: يُضاف بعد الدفع.

## المخاطر والرجوع
- iOS لا يُبنى محليًا: التغييرات في Swift مثبتة بـCI فقط.
- `ar-XB` في أندرويد هو تحويل المستودع (العربية بالتطويل) كما في الويب؛ لقطات Robolectric تستعمل
  خطوط Robolectric، لا خطوط هاتف حقيقي.
- قواعد الجمع في iOS مكتوبة لعدد من اللغات؛ لغة غيرها تُعد كالإنجليزية حتى تُضاف قاعدتها.
- الرجوع: التراجع عن الدمج يعيد ملفات XML (الموارد نفسها)؛ لا بيانات ولا عقد.

## التسليم والخطوة التالية
- طلب دمج مكدس فوق #212 (قاعدته `feat/i18n-languages-foundation` حتى يُدمج #212).
- للمالك: تأكيد ADR 0028 كله، ثم أول لغة حقيقية من مساهم.
